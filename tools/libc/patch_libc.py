#!/usr/bin/env python3
"""Make bionic's arc4random MADV_WIPEONFORK failure non-fatal (kernel 4.9 has no MADV_WIPEONFORK).
Android 17's libc calls madvise(p, n, 18) while setting up its random state and aborts on -1.
The patch turns the failure branch into a NOP. A forked child then shares the random state (a security weakening, acceptable here).
usage: patch_libc.py IN OUT   (auto-detects arm64 / arm32)"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
hits = []
if d[4] == 2:  # aarch64
    MOV_W2_18, CMN_W0_1 = 0x52800242, 0x3100041f
    for i in range(0, len(d) - 24, 4):
        if struct.unpack_from('<I', d, i)[0] == MOV_W2_18:
            for j in range(i + 4, i + 20, 4):   # bl madvise, cmn w0,#1, b.eq
                if struct.unpack_from('<I', d, j)[0] == CMN_W0_1:
                    o = struct.unpack_from('<I', d, j + 4)[0]
                    if (o & 0xff00001f) == 0x54000000:   # b.eq
                        hits.append(j + 4)
    for a in hits: struct.pack_into('<I', d, a, 0xd503201f)
else:          # thumb-2
    for i in range(0, len(d) - 12, 2):
        if d[i:i + 2] == b'\x12\x22' and d[i + 6:i + 8] == b'\x01\x30' and d[i + 9] == 0xd0:   # movs r2,#0x12 ; blx ; adds r0,#1 ; beq
            hits.append(i + 8)
    for a in hits: d[a:a + 2] = b'\x00\xbf'
assert len(hits) == 1, hits
open(sys.argv[2], 'wb').write(d); print('patched', hex(hits[0]), '->', sys.argv[2])
