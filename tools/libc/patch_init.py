#!/usr/bin/env python3
"""Patch only the two independently pinned Google init builds; unknown bytes reject."""
import hashlib
import struct
import sys

SITES = {
    'bb702f17e6660ef8f2a1fd9158c2de876fa0c8f9679a7a49f4a2d02e1119451f': 0x103d0c,
    '17677a186f9eda858790682fbb14e591b1d79fa8359d46dd4c6ca81686f0ad7e': 0x106de0,
    # Lenovo TB321FU ZUI 18.5.02.063 init (HAG-43 ZUI port): the only match of the guard sequence below; the
    # same whole-binary search returns exactly the reviewed 0x106de0 for the GSI init. Pattern-derived, not hand-reviewed.
    '53e1c59a9a1122e7cda3407b3ed7ad2ee2aa8f9cb72692a73bdcba1568cd298b': 0x120518,
}

def patch(data):
    identity = hashlib.sha256(data).hexdigest()
    if identity not in SITES: raise ValueError('Unreviewed init identity: ' + identity)
    site = SITES[identity]
    cmp_, branch, mov, call = struct.unpack_from('<IIII', data, site - 4)
    if cmp_ != 0x7100041f or branch & 0xff00001f != 0x54000000 or mov != 0x52800020 or call >> 26 != 0x25:
        raise ValueError('Pinned init instruction guard mismatch')
    out = bytearray(data)
    struct.pack_into('<II', out, site, branch | 1, 0x52800000)
    return bytes(out)

if __name__ == '__main__':
    from pathlib import Path
    Path(sys.argv[2]).write_bytes(patch(Path(sys.argv[1]).read_bytes()))
