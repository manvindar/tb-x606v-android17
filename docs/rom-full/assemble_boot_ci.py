#!/usr/bin/env python3
"""PRIVATE. CI/host boot assembler for the ROM build: new Image.gz + the PINNED proven boot-r5b.img parts (header, ramdisk, DTBs, AVB vbmeta blob).
Same recipe as boot-review/assemble_boot.py + cmdq-contract/r5b/boot/make_stale_avb_param.py (the recipe that made boot-r5b.img 414060fe), with the
appended DTB taken from the carrier's own kernel blob and every input pinned. The output is a CANDIDATE: kernel untested on a device.
usage: assemble_boot_ci.py CARRIER(boot-r5b.img) IMAGE.gz OUT.img OUT_RECEIPT.json"""
import hashlib, json, struct, sys, zlib
carrier_p, gz_p, out_p, rec_p = sys.argv[1:5]
PIN = dict(carrier='414060fe6d07c883b3b8a5baeea5ced2188e5e628161b241a38315cf63fdb7a0', ramdisk='9adb1be20bfbb227dd0e7aed6cd149d859e2062e9d62d8d942ac54c652b8f504',
           header_dtb='fe9fbd1bd013412f819814a6b4f3b126bd54609e799bfff314828286627c96d8', appended_dtb='3df1c6005184cf6fa16d4919b453a26acb26c44dd1b3278b987b6199e7687e56',
           vbmeta='62c2c0637ee958ec98e380833668fd3c217a45faa575832b0bf4c4d72b39e008')
sha = lambda b: hashlib.sha256(b).hexdigest()
b = open(carrier_p, 'rb').read(); gz = open(gz_p, 'rb').read()
assert sha(b) == PIN['carrier'], 'carrier is not the pinned boot-r5b.img'
assert gz[:2] == b'\x1f\x8b'
d = zlib.decompressobj(31); raw = d.decompress(gz); assert d.eof and not d.unused_data, 'Image.gz must be exactly one gzip stream'
assert raw[56:60] == b'ARMd', 'not an arm64 Image (magic at 0x38)'
ks, ka, rs, ra, ss, sa, ta, ps, hv, osv = struct.unpack_from('<10I', b, 8)
rdso, rdsoff, hsz = struct.unpack_from('<IQI', b, 1632); dsz, dtba = struct.unpack_from('<IQ', b, 1648)
assert (hv, ps, ss, rdso, hsz) == (2, 2048, 0, 0, 1660) and b[:8] == b'ANDROID!'
assert b[64:576].rstrip(b'\0') + b[608:1632].rstrip(b'\0') == b'bootopt=64S3,32N2,64N2 buildvariant=user'
pg = lambda n: (n + ps - 1) // ps * ps
o = ps; kern = b[o:o + ks]; o += pg(ks); ram = b[o:o + rs]; o += pg(rs); o += pg(ss) + pg(rdso); dtb = b[o:o + dsz]
kd = zlib.decompressobj(31); kd.decompress(kern); appdtb = kd.unused_data   # DTB appended after the carrier kernel's gzip stream
assert sha(appdtb) == PIN['appended_dtb'] and sha(ram) == PIN['ramdisk'] and sha(dtb) == PIN['header_dtb']
def ident(k, r, dt):  # header v2 id = sha1(kernel,len,ramdisk,len,second,0,recovery_dtbo,0,dtb,len): variant verified by the self-test
    h = hashlib.sha1()
    for part in (k, struct.pack('<I', len(k)), r, struct.pack('<I', len(r)), b'', struct.pack('<I', 0), b'', struct.pack('<I', 0), dt, struct.pack('<I', len(dt))): h.update(part)
    return h.digest().ljust(32, b'\0')
def assemble(k, r, dt):
    hdr = bytearray(b[:ps]); struct.pack_into('<I', hdr, 8, len(k)); struct.pack_into('<I', hdr, 16, len(r)); hdr[576:608] = ident(k, r, dt); struct.pack_into('<I', hdr, 1648, len(dt))
    return bytes(hdr) + k.ljust(pg(len(k)), b'\0') + r.ljust(pg(len(r)), b'\0') + dt.ljust(pg(len(dt)), b'\0')
old_end = ps + pg(ks) + pg(rs) + pg(dsz)
assert assemble(kern, ram, dtb) == b[:old_end], 'self-test failed: carrier does not rebuild from its parts'
newk = gz + appdtb; content = assemble(newk, ram, dtb); end = len(content)
f = b[-64:]; assert f[:4] == b'AVBf'; maj, mnr, orig, voff, vsz = struct.unpack('>IIQQQ', f[4:36])
vb = b[voff:voff + vsz]; assert vb[:4] == b'AVB0' and sha(vb) == PIN['vbmeta']
img = bytearray(content.ljust(len(b), b'\0')); off = -(-end // 4096) * 4096; assert off + len(vb) <= len(b) - 64
img[off:off + len(vb)] = vb; img[-64:] = b'AVBf' + struct.pack('>IIQQQ', 1, 0, end, off, len(vb)) + bytes(28)
assert len(img) == 33554432 and bytes(img[:end]) == content
open(out_p, 'wb').write(img)
rec = dict(artifact='boot-candidate.img', bytes=len(img), sha256=sha(img), content_bytes=end, kernel_blob_bytes=len(newk), image_gz_sha256=sha(gz), kernel_raw_sha256=sha(raw),
           pinned_carrier=PIN['carrier'], ramdisk_sha256=PIN['ramdisk'], header_dtb_sha256=PIN['header_dtb'], appended_dtb_sha256=PIN['appended_dtb'], vbmeta_sha256=PIN['vbmeta'],
           avb_footer=dict(original_image_size=end, vbmeta_offset=off, vbmeta_size=len(vb), note='stale/non-matching digest as in the booted r4/r5b recipe (carrier descriptor digest does not match its own content yet boots)'),
           status='CANDIDATE: kernel built by CI, not run on a device')
json.dump(rec, open(rec_p, 'w'), indent=1); print(json.dumps(rec, indent=1))
