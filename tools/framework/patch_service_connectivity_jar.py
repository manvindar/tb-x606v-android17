#!/usr/bin/env python3
"""service-connectivity.jar (tethering apex), three patches. (1) BpfNetMaps.initBpfMaps() opens the local_net_* maps when SdkLevel.isAtLeastC().
Those maps (LPM_TRIE, ringbuf) cannot exist on kernel 4.9, so NetworkStatsServiceInitializer (and with it system_server) dies with
"Cannot open local_net_access map". Make that branch unconditional-skip: `if-eqz v9, :skip` -> `goto/16 :skip`.
Then fix the dex checksum + SHA-1 and rewrite the jar. The patched jar is bind-mounted over the apex copy.
usage: patch_service_connectivity_jar.py IN.jar OUT.jar"""
import hashlib, struct, sys, zipfile, zlib
src, dst = sys.argv[1:3]
zin = zipfile.ZipFile(src); raw = bytearray(zin.read('classes.dex'))
PAT = bytes.fromhex('7100bc0b0000' + '0a09' + '3809' + '8300')   # invoke-static SdkLevel.isAtLeastC ; move-result v9 ; if-eqz v9,+0x83
n = raw.count(PAT); assert n >= 1, 'pattern not found'
i = raw.find(PAT)
# initBpfMaps is the one whose skip target (+0x83) lands on `sget-boolean v9, sL4sSupported`: check the unit 0x83 later starts with 6309
assert raw[i + 8 + 0x83 * 2:i + 8 + 0x83 * 2 + 2] == b'\x63\x09', raw[i + 8 + 0x83 * 2 - 4:i + 8 + 0x83 * 2 + 4].hex()
raw[i + 6 + 2:i + 6 + 2 + 4] = bytes.fromhex('2900' + '8300')           # goto/16 +0x83 (same unit count as the 2-unit if-eqz)
# ConnectivityService.<init>: `if (deps.isAtLeast26Q2()) mLocalNetEventListener = deps.getLocalNetEventListener(..)` -> LocalNetEventListener's
# native init abort()s because the local_net_note_op ringbuf does not exist. Take the null branch (the code already handles null).
PAT2 = bytes.fromhex('6e1071100800' + '0a00' + '3800' + '1900')   # invoke-virtual {v8} isAtLeast26Q2 ; move-result v0 ; if-eqz v0,+0x19
assert raw.count(PAT2) == 1, raw.count(PAT2)
j = raw.find(PAT2); raw[j + 8:j + 12] = bytes.fromhex('2900' + '1900')       # goto/16 +0x19
# (3) ConnectivityService$Dependencies.initLoopbackEventConsumer(): its native side (LoopbackEventHandler::Start) abort()s because the loopback ringbuf
#     map does not exist on kernel 4.9. nop out the invoke-static (3 units).
PAT3 = bytes.fromhex('7100141900000e00')   # invoke-static BpfEventPoller.nativeInitLoopbackEventConsumer ; return-void
assert raw.count(PAT3) == 1, raw.count(PAT3)
raw[raw.find(PAT3):raw.find(PAT3) + 6] = bytes(6)
# (4) kernel 4.9 has none of the local_net_* maps (see 1), so every BpfNetMaps method that writes them throws NPE on the ConnectivityServiceThread when
#     wifi connects (updateLocalNetworkAddresses -> addLocalNetAccess -> incrementLnpGenerationId) and kills system_server. Make them `return-void`.
#     Offsets are the code_item of each method (dexdump -d of this classes.dex); the first instruction is 16 bytes in.
for off, first in ((0x08fb9c, '5410'), (0x091124, None), (0x0911c8, None), (0x08f1f0, None), (0x08f2ec, None), (0x08f390, None),
                   (0x090194, None), (0x090298, None), (0x09031c, None)):
    raw[off + 16:off + 20] = bytes.fromhex('0e000000')   # return-void + nop: the first insn of each is 2 code units
fs = struct.unpack_from('<I', raw, 0x20)[0]
raw[12:32] = hashlib.sha1(bytes(raw[32:fs])).digest()
struct.pack_into('<I', raw, 8, zlib.adler32(bytes(raw[12:fs])) & 0xffffffff)
zout = zipfile.ZipFile(dst, 'w')
for zi in zin.infolist():
    data = bytes(raw) if zi.filename == 'classes.dex' else zin.read(zi.filename)
    o = zipfile.ZipInfo(zi.filename, zi.date_time); o.compress_type = zi.compress_type; o.external_attr = zi.external_attr
    zout.writestr(o, data)
zout.close(); print('patched at', hex(i + 8), '; matches', n, '; wrote', dst)
