#!/usr/bin/env python3
"""Binary patches that let the Android 17 tethering apex run on kernel 4.9 (apex files are signed, so patched copies are bind-mounted over them).
usage: patch_binaries.py netbpfload IN OUT | netd_updatable IN OUT
Each patch asserts the original instruction, so it only applies to the Android 17 DP11.261002.005 build (tethering 374399999).
Instruction encodings: NOP 0xd503201f, B imm26 0x14000000."""
import struct, sys
NOP = 0xd503201f
def b(a, t): return 0x14000000 | (((t - a) >> 2) & 0x3ffffff)
def patch(d, a, kind, target=None):
    o = struct.unpack_from('<I', d, a)[0]
    if kind == 'nop-any':   # conditional branch / tbz / tbnz / cbz / cbnz
        assert (o >> 24) in (0x34, 0x35, 0x36, 0x37) or (o >> 24) == 0x54, hex(o)
        struct.pack_into('<I', d, a, NOP)
    elif kind == 'to-b':    # tbz/cbz -> unconditional branch to the existing target
        assert (o >> 24) in (0x34, 0x35, 0x36, 0x37), hex(o)
        struct.pack_into('<I', d, a, b(a, target))
# netbpfload: (addr, kind, target)
NETBPFLOAD = [
    # kernel-version gates (U 4.14, V 4.19, 25Q2 5.4, ...): make the "flag set" branch unconditional-skip
    (0x90c8, 'to-b', 0x9244), (0x9254, 'to-b', 0x9278), (0x928c, 'to-b', 0x92b0),
    (0x92c0, 'to-b', 0x92d8), (0x92e8, 'to-b', 0x9300), (0x9310, 'to-b', 0x9334),
    # sysctl writes that do not exist on 4.9 (bpf_jit_kallsyms ...) must not be fatal
    (0x97a8, 'nop-any'), (0x97c4, 'nop-any'), (0x97d4, 'nop-any'), (0x97e4, 'nop-any'),
    (0x9804, 'nop-any'), (0x9814, 'nop-any'), (0x9824, 'nop-any'), (0x9834, 'nop-any'),
    # "no bpf programs loaded yet" sanity check uses BPF_PROG_GET_NEXT_ID (4.13+)
    (0x9968, 'nop-any'), (0x9970, 'nop-any'), (0x9988, 'nop-any'), (0x9990, 'nop-any'),
    # object file must be on the same device as the binary (the patched copy lives on /system)
    (0xaf4c, 'nop-any'),
]
NETD_UPDATABLE = [
    (0x9094, 'nop-any'),                 # U gate: kernel < 4.14
    (0x94f8, 'to-b', 0x9600),            # V gate: kernel < 4.19
    (0x9614, 'to-b', 0x998c),            # 25Q2 gate: kernel < 5.4
    (0xaae0, 'nop-any'), (0xb004, 'nop-any'),  # newest-platform flag forces the sockops program (5.15+) to exist
]
def main():
    which, src, dst = sys.argv[1:4]
    d = bytearray(open(src, 'rb').read())
    for p in {'netbpfload': NETBPFLOAD, 'netd_updatable': NETD_UPDATABLE}[which]:
        patch(d, p[0], p[1], p[2] if len(p) > 2 else None)
    open(dst, 'wb').write(d); print('patched', len(NETBPFLOAD if which == 'netbpfload' else NETD_UPDATABLE), 'sites ->', dst)
main()
