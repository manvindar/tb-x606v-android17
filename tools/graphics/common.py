"""Strict host-side extraction and ELF metadata helpers for graphics verification."""
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def run(*args, ok=(0,)):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True)
    if result.returncode not in ok:
        raise RuntimeError(f'{args}: {result.stderr[-2000:]}')
    if Path(str(args[0])).name == 'debugfs':
        errors = []
        stat_query = len(args) > 2 and str(args[2]).startswith('stat ')
        for line in result.stderr.splitlines():
            if not line.strip() or line.startswith('debugfs '):
                continue
            # Host-side ownership warnings do not imply missing bytes. All other
            # extraction errors are fatal, even when debugfs incorrectly exits zero.
            if 'Operation not permitted while changing ownership of ' in line:
                continue
            if stat_query and 'File not found by ext2_lookup' in line:
                continue  # expected existence query, never an extraction pass
            errors.append(line)
        if errors:
            raise RuntimeError('debugfs failed: ' + '\n'.join(errors))
    return result.stdout


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def dump(image, source, target):
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    run('debugfs', '-R', f'dump {source} {target}', image)
    if not target.is_file() or not target.stat().st_size:
        raise RuntimeError(f'Failed or empty extraction: {source}')
    return target


def extract_fs(image, destination):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    with Path(image).open('rb') as stream:
        stream.seek(1024 + 56)
        ext4 = stream.read(2) == b'\x53\xef'
    if ext4:
        # debugfs reports some lookup errors with exit zero: require content below.
        run('debugfs', '-R', f'rdump / {destination}', image)
    else:
        run('fsck.erofs', f'--extract={destination}', image)
    if not any(p.is_file() and not p.is_symlink() for p in destination.rglob('*')):
        raise RuntimeError(f'Failed or empty filesystem extraction: {image}')
    return destination


def unpack_apex(package, directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(package) as outer:
        if 'original_apex' in outer.namelist():
            original = directory / 'original.apex'
            with outer.open('original_apex') as src, original.open('wb') as dst:
                shutil.copyfileobj(src, dst)
        else:
            original = Path(package)
    with zipfile.ZipFile(original) as archive:
        for name in ('apex_payload.img', 'apex_manifest.pb'):
            with archive.open(name) as src, (directory / name).open('wb') as dst:
                shutil.copyfileobj(src, dst)
    return original, directory / 'apex_payload.img', directory / 'apex_manifest.pb'


def dynamic_symbols(text):
    # Type names can contain spaces (current ARM64 IFUNC: '<OS specific>: 10').
    # Anchor binding/visibility/index/name instead of assuming fixed columns.
    records = []
    pattern = re.compile(r'^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+.+?\s+(GLOBAL|WEAK)\s+\S+\s+(\S+)\s+(\S+)(?:\s+\(\d+\))?\s*$')
    for line in text.splitlines():
        match = pattern.match(line)
        if match:
            records.append(match.groups())
    return records


def elf(path):
    with Path(path).open('rb') as stream:
        head = stream.read(20)
    if len(head) < 20 or head[:4] != b'\x7fELF':
        return None
    # Only ARM32/ARM64: unrelated packaged hosts are still recorded by caller.
    machine = int.from_bytes(head[18:20], 'little' if head[5] == 1 else 'big')
    abi = {40: 'arm32', 183: 'arm64'}.get(machine)
    if abi is None:
        return {'abi': None, 'exports': set(), 'imports': set(), 'needed': []}
    symbols = run('readelf', '--dyn-syms', '--wide', path)
    exports, imports, abi_imports = set(), set(), set()
    for binding, index, name in dynamic_symbols(symbols):
        if index == 'UND':
            abi_imports.add(name.replace('@@', '@'))
            if binding != 'WEAK':
                imports.add(name.replace('@@', '@'))
        else:
            exports.add(name.replace('@@', '@'))
            if '@@' in name:
                exports.add(name.split('@')[0])
    needed = re.findall(r'Shared library: \[([^]]+)\]', run('readelf', '-d', path))
    return {'abi': abi, 'exports': exports, 'imports': imports, 'abi_imports': abi_imports, 'source_path': str(path), 'needed': needed, 'symbol_table_text': symbols}


def protobuf_fields(data):
    """Read protobuf wire fields without guessing field meanings; reject corruption."""
    def varint(offset):
        value = 0
        for shift in range(0, 70, 7):
            if offset >= len(data):
                raise ValueError('Truncated protobuf')
            byte = data[offset]; offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError('Oversized protobuf varint')
    fields, pos = {}, 0
    while pos < len(data):
        key, pos = varint(pos); number, wire = key >> 3, key & 7
        if not number:
            raise ValueError('Invalid protobuf field')
        if wire == 0:
            value, pos = varint(pos)
        elif wire == 2:
            size, pos = varint(pos); value = data[pos:pos + size]; pos += size
            if len(value) != size:
                raise ValueError('Truncated protobuf field')
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value = data[pos:pos + size]; pos += size
            if len(value) != size:
                raise ValueError('Truncated protobuf field')
        else:
            raise ValueError('Unsupported protobuf wire type')
        fields.setdefault(number, []).append(value)
    return fields


def apex_manifest(path):
    # Authoritative system/apex/proto/apex_manifest.proto: name1/provide7/require8.
    fields = protobuf_fields(Path(path).read_bytes())
    return {'name': fields[1][0].decode(),
            'provide': [v.decode() for v in fields.get(7, [])],
            'require': [v.decode() for v in fields.get(8, [])]}
