#!/usr/bin/env python3
"""Add only required legacy interfaces to the codec APEX, then re-sign its payload.
Uses the same generated test signing identities as the existing runtime APEX.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import struct
import tempfile
import zipfile
from common import digest, dump, run, unpack_apex, elf
from stage_legacy import LEGACY


def filesystem_geometry(path):
    with Path(path).open('rb') as stream:
        stream.seek(1024)
        data = stream.read(1024)
    if len(data) != 1024 or data[56:58] != b'\x53\xef':
        raise RuntimeError('Not an ext4 codec payload')
    value = lambda offset: struct.unpack_from('<I', data, offset)[0]
    log = value(24)
    if log > 6 or value(4 + 0x14c):
        raise RuntimeError('Unsupported codec filesystem geometry')
    geometry = {'blocks': value(4), 'free_inodes': value(16),
                'first_block': value(20), 'block_bytes': 1024 << log,
                'blocks_per_group': value(32), 'inodes_per_group': value(40)}
    if not all(geometry[k] > 0 for k in ('blocks', 'blocks_per_group', 'inodes_per_group')):
        raise RuntimeError('Invalid codec filesystem geometry')
    return geometry


def growth_bytes(geometry, original_bytes, required_inodes):
    block = geometry['block_bytes']
    group = geometry['blocks_per_group']
    minimum = (original_bytes + 16 * 1024 * 1024 + block - 1) // block
    if geometry['free_inodes'] < required_inodes:
        # Byte growth within the existing group does not add an inode table.
        # Allocate one complete additional group, and verify free inodes afterward.
        groups = (geometry['blocks'] - geometry['first_block'] + group - 1) // group
        minimum = max(minimum, geometry['first_block'] + (groups + 1) * group)
    target = minimum * block
    if target - original_bytes > 256 * 1024 * 1024:
        raise RuntimeError('Codec growth exceeds bounded 256 MiB allowance')
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('input'); parser.add_argument('output'); parser.add_argument('stage')
    parser.add_argument('key'); parser.add_argument('avbtool')
    parser.add_argument('ui64'); parser.add_argument('ui32')
    args = parser.parse_args()
    stage = Path(args.stage)
    sync_root = stage / 'sync-provider'
    sync_receipt = json.loads((sync_root / 'receipt.json').read_text())
    source_pins = json.loads((Path(__file__).resolve().parent / 'libsync-source/pins.json').read_text())
    if sync_receipt['source'] != source_pins or len(sync_receipt['variants']) != 2 or {v['abi'] for v in sync_receipt['variants']} != {'arm64', 'arm32'}:
        raise RuntimeError('Incomplete or unpinned codec sync implementation receipt')
    for variant in sync_receipt['variants']:
        library = sync_root / variant['path']
        pin = source_pins['outputs'][variant['abi']]
        if digest(library) != pin['sha256'] or library.stat().st_size != pin['bytes']:
            raise RuntimeError('Codec sync provider stage hash mismatch')
        metadata = elf(library)
        required = {n + '@LIBSYNC' for n in ('sync_file_info', 'sync_file_info_free', 'sync_merge', 'sync_wait')}
        if metadata['abi'] != variant['abi'] or not required <= metadata['exports'] or metadata['needed'] != ['libc.so']:
            raise RuntimeError('Invalid codec sync implementation ABI/versions/dependencies')
    receipt = {'sync_provider': sync_receipt, 'input_package_sha256': digest(args.input), 'replacements': [], 'added': [],
               'namespace_strategy': 'Legacy interfaces and pinned genuine LIBSYNC implementations reside inside codec payload; local providers precede declared external requirements; libc remains external'}
    with tempfile.TemporaryDirectory(prefix='codec-graphics-') as scratch:
        tmp = Path(scratch)
        original, payload, _ = unpack_apex(args.input, tmp / 'input')
        zdir = tmp / 'zip'; zdir.mkdir()
        with zipfile.ZipFile(original) as z:
            names = [n for n in z.namelist() if n and not n.endswith('/')]
            if any(Path(n).is_absolute() or '..' in Path(n).parts for n in names):
                raise RuntimeError('Unsafe APEX member path')
            z.extractall(zdir)
        pay = zdir / 'apex_payload.img'
        info = run('python3', '-I', args.avbtool, 'info_image', '--image', pay)
        original_bytes = int(re.search(r'Original image size:\s+(\d+)', info).group(1))
        block = int(re.search(r'Data Block Size:\s+(\d+)', info).group(1))
        apex_key = re.search(r"Prop: apex.key -> '([^']+)'", info).group(1)
        with pay.open('r+b') as stream:
            stream.truncate(original_bytes)
        run('e2fsck', '-fy', pay, ok=(0, 1))
        before = filesystem_geometry(pay)
        if before['block_bytes'] != block or before['blocks'] * block != original_bytes:
            raise RuntimeError('AVB and ext4 payload sizes disagree')
        required_inodes = 2 * (len(LEGACY) + 1) + 16
        new_bytes = growth_bytes(before, original_bytes, required_inodes)
        with pay.open('r+b') as stream:
            stream.truncate(new_bytes)
        run('resize2fs', pay)
        run('e2fsck', '-fy', '-E', 'unshare_blocks', pay, ok=(0, 1))
        run('e2fsck', '-fn', pay)
        after = filesystem_geometry(pay)
        if after['free_inodes'] < required_inodes or after['blocks'] * block != new_bytes:
            raise RuntimeError('Codec payload growth did not provide required inode capacity')
        receipt['filesystem_growth'] = {'before': before, 'after': after,
                                      'required_free_inodes': required_inodes,
                                      'original_bytes': original_bytes, 'new_bytes': new_bytes}
        commands = []
        for directory, library in [('lib64', Path(args.ui64)), ('lib', Path(args.ui32))]:
            original_path = '/' + directory + '/libui.so'
            label = run('debugfs', '-R', f'ea_get -V {original_path} security.selinux', pay).split('\n')[0].split('\x00')[0].strip()
            if not label.startswith('u:object_r:'):
                raise RuntimeError('Missing codec library SELinux label')
            label_file = tmp / (directory + '-label'); label_file.write_bytes(label.encode() + b'\x00')
            sync_path = '/' + directory + '/libsync.so'
            if 'Inode:' in run('debugfs', '-R', 'stat ' + sync_path, pay):
                raise RuntimeError('Unexpected existing codec-local sync provider')
            planned = [(original_path, library, 'replacements'),
                       (sync_path, sync_root / directory / 'libsync.so', 'added')]
            for name in LEGACY:
                destination = '/' + directory + '/' + name
                stat = run('debugfs', '-R', 'stat ' + destination, pay)
                if 'Inode:' not in stat:
                    planned.append((destination, stage / 'interfaces' / directory / name, 'added'))
            for destination, source, kind in planned:
                if not source.is_file() or not source.stat().st_size:
                    raise RuntimeError('Missing codec donor file: ' + str(source))
                if kind == 'replacements':
                    commands.append('rm ' + destination)
                commands.extend([f'write {source.resolve()} {destination}',
                                 f'set_inode_field {destination} mode 0100644',
                                 f'set_inode_field {destination} uid 0', f'set_inode_field {destination} gid 0',
                                 f'ea_set -f {label_file} {destination} security.selinux'])
                receipt[kind].append({'path': destination, 'sha256': digest(source), 'selinux_label': label})
        script = tmp / 'commands'; script.write_text('\n'.join(commands) + '\n')
        output = run('debugfs', '-w', '-f', script, pay)
        if re.search(r'(?i)(file not found|no such|not enough|no space|error)', output):
            raise RuntimeError('Codec payload edit failed: ' + output[-2000:])
        run('e2fsck', '-fn', pay)
        for entry in receipt['replacements'] + receipt['added']:
            check = dump(pay, entry['path'], tmp / 'readback' / entry['path'].lstrip('/'))
            if digest(check) != entry['sha256']:
                raise RuntimeError('Codec payload readback mismatch')
            actual_label = run('debugfs', '-R', f'ea_get -V {entry["path"]} security.selinux', pay).split('\n')[0].split('\x00')[0].strip()
            if actual_label != entry['selinux_label']:
                raise RuntimeError('Codec payload label readback mismatch')
        total = ((new_bytes + new_bytes // 64 + 1024 * 1024) // block) * block
        run('python3', '-I', args.avbtool, 'add_hashtree_footer', '--image', pay,
            '--partition_size', str(total), '--partition_name', '', '--do_not_generate_fec',
            '--algorithm', 'SHA256_RSA4096', '--key', args.key, '--hash_algorithm', 'sha256',
            '--block_size', str(block), '--salt', os.urandom(32).hex(), '--prop', 'apex.key:' + apex_key)
        run('python3', '-I', args.avbtool, 'verify_image', '--image', pay, '--key', args.key)
        run('python3', '-I', args.avbtool, 'extract_public_key', '--key', args.key, '--output', zdir / 'apex_pubkey')
        unsigned = tmp / 'unsigned.apex'
        with zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_STORED) as z:
            for name in names:
                if not name.startswith('META-INF/'):
                    z.write(zdir / name, name)
        aligned = tmp / 'aligned.apex'
        run(os.environ['ZIPALIGN'], '-f', '4096', unsigned, aligned)
        run(os.environ['APKSIGNER'], 'sign', '--ks', os.environ['APEX_SIGN_KS'], '--ks-pass', 'pass:android',
            '--key-pass', 'pass:android', '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true', '--alignment-preserved', 'true', '--out', args.output, aligned)
        run(os.environ['APKSIGNER'], 'verify', args.output)
        run(os.environ['ZIPALIGN'], '-c', '4096', args.output)
    receipt['output_package_sha256'] = digest(args.output)
    receipt['validation'] = ['ext4 check', 'each modified file SHA256 readback', 'AVB hashtree verify', 'APK signature verify', '4096 ZIP alignment']
    (stage / 'codec-repack-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
