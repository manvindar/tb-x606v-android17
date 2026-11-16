#!/usr/bin/env python3
"""Extract only pinned prior Android17 libui variants and legacy HIDL interfaces."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile
from common import digest, dump, unpack_apex

LEGACY = ['android.hardware.graphics.allocator@2.0.so', 'android.hardware.graphics.allocator@3.0.so',
          'android.hardware.graphics.mapper@2.0.so', 'android.hardware.graphics.mapper@2.1.so',
          'android.hardware.graphics.mapper@3.0.so']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('pins'); parser.add_argument('out')
    parser.add_argument('--archive-cache')
    args = parser.parse_args()
    pins = json.loads(Path(args.pins).read_text())['legacy_graphics']
    out = Path(args.out).resolve(); out.mkdir(parents=True, exist_ok=False)
    records = []
    with tempfile.TemporaryDirectory(prefix='legacy-graphics-') as scratch:
        tmp = Path(scratch)
        archive = Path(args.archive_cache).resolve() if args.archive_cache else tmp / 'gsi.zip'
        if args.archive_cache:
            import sys
            sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'downloads'))
            from verified_input import obtain
            obtain(pins['url'], pins['sha256'], archive)
        else:
            urllib.request.urlretrieve(pins['url'], archive)
        if digest(archive) != pins['sha256']:
            raise RuntimeError('Legacy graphics GSI hash mismatch')
        image = tmp / 'system.img'
        with zipfile.ZipFile(archive) as z:
            members = [n for n in z.namelist() if Path(n).name == 'system.img']
            if len(members) != 1:
                raise RuntimeError('Ambiguous donor system image')
            with z.open(members[0]) as src, image.open('wb') as dst:
                shutil.copyfileobj(src, dst)
        with image.open('rb') as stream:
            if stream.read(4) == bytes.fromhex('3aff26ed'):
                raise RuntimeError('Unexpected sparse graphics donor')
        codec = dump(image, '/system/apex/com.android.media.swcodec.capex', tmp / 'codec.capex')
        _, payload, _ = unpack_apex(codec, tmp / 'codec')
        for abi, directory in [('arm64', 'lib64'), ('arm32', 'lib')]:
            for context, source_image, base in [('system', image, '/system'), ('codec', payload, '')]:
                source = base + '/' + directory + '/libui.so'
                target = dump(source_image, source, out / context / directory / 'libui.so')
                records.append({'context': context, 'abi': abi, 'source': source,
                                'relative_path': str(target.relative_to(out)), 'sha256': digest(target)})
            for name in LEGACY:
                target = dump(image, '/system/' + directory + '/' + name, out / 'interfaces' / directory / name)
                records.append({'context': 'interfaces', 'abi': abi, 'source': '/system/' + directory + '/' + name,
                                'relative_path': str(target.relative_to(out)), 'sha256': digest(target)})
    (out / 'donor-receipt.json').write_text(json.dumps({'input': pins, 'files': records,
        'purpose': 'Legacy gralloc2/3 support only; latest base and Pixel apps stay unchanged',
        'validation': 'Payloads must pass unchanged mapper/video patch guards and final native/namespace verification',
        'device_validation': False}, indent=2) + '\n')


if __name__ == '__main__':
    main()
