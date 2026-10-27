#!/usr/bin/env python3
"""libui (Android 17): GraphicBufferMapper only falls back to the HIDL gralloc mappers 3.0/2.x when android_get_device_api_level() <= 35.
On this device the API level seen by libui is higher (GSI), so SurfaceFlinger aborts with "gralloc-mapper is missing" although the vendor has mapper 2.1.
NOP the 'b.gt' after `cmp w0,#0x23` (arm64) / `cmp r0,#0x23; bgt` (thumb), in the constructor and in preloadHal.
usage: patch_libui.py IN OUT"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read()); hits = []
if d[4] == 2:
    for i in range(0, len(d) - 8, 4):
        if struct.unpack_from('<I', d, i)[0] == 0x71008c1f and (struct.unpack_from('<I', d, i + 4)[0] & 0xff00001f) == 0x5400000c:  # cmp w0,#0x23 ; b.gt
            hits.append(i + 4)
    for a in hits: struct.pack_into('<I', d, a, 0xd503201f)
else:
    for i in range(0, len(d) - 4, 2):
        if d[i:i + 2] == b'\x23\x28' and d[i + 3] == 0xdc:   # cmp r0,#0x23 ; bgt <imm8>
            hits.append(i + 2)
    for a in hits: d[a:a + 2] = b'\x00\xbf'
assert len(hits) == 2, hits
open(sys.argv[2], 'wb').write(d); print('patched', [hex(h) for h in hits])
