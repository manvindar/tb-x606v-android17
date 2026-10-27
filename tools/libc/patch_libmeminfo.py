#!/usr/bin/env python3
"""libmeminfo (Android 17, arm64): BpfMapRO<unsigned long,unsigned long>::abortOnMismatch() abort()s when a pinned bpf map is missing.
system_server calls it via Debug.getGpuTotalUsageKb -> ReadProcessGpuUsageKb (the gpuMem bpf program cannot load on kernel 4.9), killing system_server
every ~75 s. Make the function return at its entry (0x1d1e8, `sub sp,sp,#0x150` asserted).  usage: patch_libmeminfo.py IN OUT"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
assert struct.unpack_from('<I', d, 0x1d1e8)[0] == 0xd10543ff, 'unexpected libmeminfo build'
struct.pack_into('<I', d, 0x1d1e8, 0xd65f03c0)
open(sys.argv[2], 'wb').write(d); print('patched ->', sys.argv[2])
