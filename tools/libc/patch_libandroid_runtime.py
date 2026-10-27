#!/usr/bin/env python3
"""libandroid_runtime.so (Android 17, arm64): MemoryBudgetManager.nativeRegisterPackageBudgetWatcher / nativeRegisterProcessBudgetWatcher return 0 when the kernel
cannot deliver memory budget events (no memcg/BPF support on 4.9), and Java then throws IllegalStateException("Failed to register package budget listener"):
Google Maps (and any app that registers an over-budget listener) crashes at start. Make both natives return a dummy non-zero handle; no event is ever delivered,
which is what these apps tolerate. The unregister native looks the handle up in a hash map and ignores unknown ones.
Offsets are for build DP11.261002.005 (checked against the expected trampolines `mov x1,x2; mov w2,#1|2; b register`).
usage: patch_libandroid_runtime.py IN OUT"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
for off, kind in ((0x266dac, 1), (0x266db8, 2)):
    assert struct.unpack_from('<III', d, off)[:2] == (0xaa0203e1, 0x52800002 | (kind << 5)), hex(off)   # mov x1,x2 ; mov w2,#kind
    struct.pack_into('<III', d, off, 0xd2800020, 0xd65f03c0, 0xd503201f)                               # mov x0,#1 ; ret ; nop
open(sys.argv[2], 'wb').write(d); print('patched')
