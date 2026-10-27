#!/usr/bin/env python3
"""libprocessgroup_setup.so (Android 17) for the stock 4.9.190 kernel:
 - cgroup2 is mounted with data "memory_recursiveprot"; kernel 4.9 rejects any non-NULL data for cgroup2 (-EINVAL), so pass NULL.
 - cpuset (v1) gets the mount option ",noprefix,cpuset_v2_mode"; 4.9 has no such option. "clone_children" has the same length and gives new cpusets
   their parent's cpus/mems, which is what cpuset_v2_mode does.
Offsets are for the DP11.261002.005 build (asserted).
usage: patch_pgsetup.py IN OUT"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
a = 0x4730
o = struct.unpack_from('<I', d, a)[0]; assert (o & 0x9f00001f) == 0x90000004, hex(o)            # adrp x4, ...
p = struct.unpack_from('<I', d, a + 4)[0]; assert (p & 0xffc003ff) == (0x91000000 | (4 << 5) | 4), hex(p)  # add x4, x4, #imm
struct.pack_into('<II', d, a, 0xaa1f03e4, 0xd503201f)                                         # mov x4, xzr ; nop
s = b',noprefix,cpuset_v2_mode'; i = d.find(s); assert i > 0 and d.find(s, i + 1) < 0
d[i:i + len(s)] = b',noprefix,clone_children'
open(sys.argv[2], 'wb').write(d); print('patched ->', sys.argv[2])
