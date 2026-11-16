#!/usr/bin/env python3
"""Build a pinned implementation, never linker stubs, with genuine LIBSYNC versions."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import elf, run

REQUIRED = {'sync_file_info', 'sync_file_info_free', 'sync_merge', 'sync_wait'}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('ndk'); parser.add_argument('output')
    args = parser.parse_args()
    src = Path(__file__).resolve().parent / 'libsync-source'
    pins = json.loads((src / 'pins.json').read_text())
    ndk = Path(args.ndk).resolve()
    properties = (ndk / 'source.properties').read_text()
    if f'Pkg.Revision = {pins["ndk"]}' not in properties:
        raise RuntimeError('NDK revision mismatch')
    for name, pin in pins['files'].items():
        if hashlib.sha256((src / name).read_bytes()).hexdigest() != pin['sha256']:
            raise RuntimeError('Source pin mismatch: ' + name)
    tool = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    out = Path(args.output).resolve(); out.mkdir(parents=True, exist_ok=False)
    receipt = {'source': pins, 'variants': [], 'acceptance': False,
               'limitations': ['No runtime verification', 'Codec namespace integration not yet staged',
                               'Mapper C++ compatibility remains independently required']}
    for abi, target, directory in [('arm64', 'aarch64-linux-android26', 'lib64'),
                                   ('arm32', 'armv7a-linux-androideabi26', 'lib')]:
        output = out / directory / 'libsync.so'; output.parent.mkdir()
        command = [str(tool / (target + '-clang')), '-shared', '-fPIC', '-O2', '-Werror',
                   '-I' + str(src), str(src / 'sync.c'), '-Wl,--version-script=' + str(src / 'libsync.map.txt'),
                   '-Wl,-soname,libsync.so', '-Wl,--as-needed', '-Wl,--no-undefined', '-Wl,--build-id=sha1', '-o', str(output)]
        run(*command)
        # common.elf uses readelf from PATH; workflow installs the matching LLVM tools.
        pin = pins.get('outputs', {}).get(abi)
        if pin and (output.stat().st_size != pin['bytes'] or hashlib.sha256(output.read_bytes()).hexdigest() != pin['sha256']):
            raise RuntimeError('Measured provider output pin mismatch: ' + abi)
        metadata = elf(output)
        expected = {name + '@LIBSYNC' for name in REQUIRED}
        if metadata['abi'] != abi or not expected.issubset(metadata['exports']):
            raise RuntimeError('Missing genuine versioned provider exports: ' + abi)
        if metadata['needed'] != ['libc.so']:
            raise RuntimeError('Unexpected provider dependencies: ' + repr(metadata['needed']))
        raw = run(tool / 'llvm-readelf', '--dyn-syms', '--version-info', '--dynamic', '--wide', output)
        (output.parent / 'readelf.txt').write_text(raw)
        receipt['variants'].append({'abi': abi, 'path': str(output.relative_to(out)),
            'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'bytes': output.stat().st_size,
            'exports': sorted(metadata['exports']), 'imports': sorted(metadata['imports']),
            'needed': metadata['needed'], 'command': command})
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')

if __name__ == '__main__':
    main()
