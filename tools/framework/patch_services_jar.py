#!/usr/bin/env python3
"""services.jar (Android 17 DP11.261002.005): HintManagerService assumes an AIDL IPower HAL exists.
`mPowerHal = injector.createIPower()` is null on this device (vendor has only the HIDL power HAL), `mSupportInfo` stays null and
the constructor dies on `mSupportInfo.headroom` (NPE) -> system_server restart loop.
Patch (bytecode, in the dex that holds HintManagerService; classes.dex is 3 dex files back to back):
  1. constructor: NOP the `if-eqz v7, :skip` so getSupportInfo() is always called
  2. getSupportInfo(): catch NullPointerException (was RemoteException) and, in the handler, `goto` the code that builds a default SupportInfo
     (all features off) instead of returning null
then fix the dex checksum + SHA-1 signature and rewrite the jar. Offsets are asserted.
usage: patch_services_jar.py IN.jar OUT.jar"""
import hashlib, re, struct, sys, zipfile, zlib
src, dst = sys.argv[1:3]
zin = zipfile.ZipFile(src); raw = bytearray(zin.read('classes.dex'))
heads = [m.start() for m in re.finditer(rb'dex\n041\x00', raw)]
# find the HintManagerService.<init>/getSupportInfo bytecode by its instruction pattern (so no hard-coded offsets)
CTOR = bytes.fromhex('38071000' + '6e10092d0600' + '0c07' + '5b67ad2a')          # if-eqz v7,+0x10 ; invoke-virtual {v6} getSupportInfo ; move-result-object v7 ; iput-object v7,v6,mSupportInfo
HANDLER = bytes.fromhex('0d06' + '1a00f1a0' + '7120' + '1e24' + '6000' + '1206' + '1106')   # move-exception v6 ; const-string v0,"Could not contact PowerHAL!" ; invoke-static ; const/4 v6,0 ; return-object v6
c = raw.find(CTOR); assert c > 0 and raw.find(CTOR, c + 1) < 0, 'ctor pattern'
h = raw.find(HANDLER); assert h > 0 and raw.find(HANDLER, h + 1) < 0, 'handler pattern'
base = max(x for x in heads if x <= h)
# the try/catch item right after the insns of getSupportInfo: insns end after `return-object v6` of the handler (unit 0x61), then pad, tries
insn_end = h + len(HANDLER)
tries = insn_end + 2   # 97 units is odd -> 2 bytes padding
assert raw[tries:tries + 4] == b'\0\0\0\0' and raw[tries + 4:tries + 6] == b'\x11\x00', raw[tries:tries + 12].hex()
lst = tries + 8; assert raw[lst] == 1 and raw[lst + 1] == 1, raw[lst:lst + 6].hex()
tidx_off = lst + 2
# decode the current type_idx (uleb128) and look up RemoteException / NullPointerException in this dex's type table
def uleb(b, o):
    v = s = 0
    while True:
        x = b[o]; o += 1; v |= (x & 0x7f) << s; s += 7
        if not x & 0x80: return v, o
# dex 041 container: every offset in the header is absolute within classes.dex
type_size, type_off = struct.unpack_from('<II', raw, base + 0x40); str_ids = struct.unpack_from('<I', raw, base + 0x3c)[0]
def tname(t):
    sidx = struct.unpack_from('<I', raw, type_off + 4 * t)[0]
    o = struct.unpack_from('<I', raw, str_ids + 4 * sidx)[0]; _, o = uleb(raw, o)
    return bytes(raw[o:raw.index(b'\0', o)]).decode('latin1')
cur, nxt = uleb(raw, tidx_off)
assert tname(cur) == 'Landroid/os/RemoteException;', tname(cur)
npe = next(t for t in range(type_size) if tname(t) == 'Ljava/lang/NullPointerException;')
enc = bytearray(); v = npe
while True:
    b7 = v & 0x7f; v >>= 7
    enc.append(b7 | (0x80 if v else 0))
    if not v: break
assert len(enc) == nxt - tidx_off, (len(enc), nxt - tidx_off)
raw[tidx_off:nxt] = enc
# the verifier merges register types over the whole try range: v6 (`this`) must not be clobbered inside it, or the handler's jump to the
# default-SupportInfo code sees v6 as Object ("'this' argument ... not instance of HintManagerService"). Rewrite units 0xb..0x11 to use v1/v0:
#   iget-object v1,v6,mPowerHal ; invoke-interface {v1},IPower.getSupportInfo ; move-result-object v0 ; return-object v0
t = h - 0x59 * 2 + 0xb * 2            # insns start + unit 0xb
assert raw[t:t + 14] == bytes.fromhex('5466a62a' + '7210a6090600' + '0c06' + '1106')[:14], raw[t:t + 14].hex()
raw[t:t + 14] = bytes.fromhex('5461a62a' + '7210a6090100' + '0c00' + '1100')
raw[c:c + 4] = b'\0\0\0\0'                                  # 1. nop the null check on mPowerHal
raw[h:h + 6] = bytes.fromhex('2900b9ff' + '0000')           # 2. goto/16 -0x47 (to the default-SupportInfo code at unit 0x12) ; nop (kills the orphaned const-string operand)
# fix header: signature then checksum, of the dex that was edited (each dex's file_size is in its own header)
fs = struct.unpack_from('<I', raw, base + 0x20)[0]
raw[base + 12:base + 32] = hashlib.sha1(bytes(raw[base + 32:base + fs])).digest()
struct.pack_into('<I', raw, base + 8, zlib.adler32(bytes(raw[base + 12:base + fs])) & 0xffffffff)
zout = zipfile.ZipFile(dst, 'w')
for i in zin.infolist():
    data = bytes(raw) if i.filename == 'classes.dex' else zin.read(i.filename)
    zi = zipfile.ZipInfo(i.filename, i.date_time); zi.compress_type = i.compress_type; zi.external_attr = i.external_attr
    zout.writestr(zi, data)
zout.close(); print('patched dex at', hex(base), 'catch type', cur, '->', npe, '; wrote', dst)
