#!/usr/bin/env python3
"""Rebuild the logical partitions of a Qualcomm QFIL firmware's split `super` and write chosen partitions as raw images.

usage: donor_super.py IMAGE_DIR OUT_DIR NAME... (e.g. system system_ext product)
- Reads every rawprogram*.x, takes the <program> rows whose filename starts with `super_`, checks they share one
  label/partition and sector size, and places each piece at (start_sector - partition_start) * sector_size.
  The partition start is the smallest start_sector among those rows; the piece placed at offset 0 must carry the
  LP geometry magic at byte 4096, otherwise it stops (no guessed layout, no plain concatenation).
- Android sparse pieces (magic 0xed26ff3a) are rejected: run simg2img first (the caller does).
- Parses LP metadata (slot 0), writes NAME (or NAME_a) from its linear extents, prints a JSON receipt."""
import hashlib, json, os, re, struct, sys
img, out, names = sys.argv[1], sys.argv[2], sys.argv[3:]
rows = []
for f in sorted(os.listdir(img)):
    if f.startswith('rawprogram') and f.endswith('.x'):
        for m in re.finditer(r'<program\s([^>]*)/>', open(os.path.join(img, f), errors='replace').read()):
            a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
            if a.get('filename', '').startswith('super_'): rows.append(dict(a, _file=f))
if not rows: sys.exit('no super_ rows in rawprogram files')
# the same piece may be listed in several rawprogram variants; keep identical duplicates once, refuse conflicts
pieces = {}
for r in rows:
    key = r['filename']; v = (int(r['start_sector']), int(r['SECTOR_SIZE_IN_BYTES']), r.get('label'), int(r.get('file_sector_offset', '0')), r.get('physical_partition_number'))
    if key in pieces and pieces[key] != v: sys.exit(f'conflicting rows for {key}: {pieces[key]} vs {v}')
    pieces[key] = v
if len({v[1] for v in pieces.values()}) != 1 or len({v[2] for v in pieces.values()}) != 1 or len({v[4] for v in pieces.values()}) != 1:
    sys.exit(f'super pieces disagree on sector size/label/LUN: {pieces}')
if any(v[3] for v in pieces.values()): sys.exit('file_sector_offset != 0 is not handled')
ss = next(iter(pieces.values()))[1]; start = min(v[0] for v in pieces.values())
layout = sorted((v[0] - start) * ss for v in pieces.values())
placed = []
for fn, v in sorted(pieces.items(), key=lambda kv: kv[1][0]):
    p = os.path.join(img, fn); size = os.path.getsize(p); off = (v[0] - start) * ss
    with open(p, 'rb') as f: head = f.read(4)
    if head == b'\x3a\xff\x26\xed': sys.exit(f'{fn} is an Android sparse image; convert with simg2img first')
    placed.append((off, size, p, fn))
for (o1, s1, _, f1), (o2, _, _, f2) in zip(placed, placed[1:]):
    if o1 + s1 > o2: sys.exit(f'{f1} overlaps {f2}')
first = placed[0]
if first[0] != 0: sys.exit('no piece at partition offset 0')
def read_super(off, n):
    """Read n bytes at super offset off from the pieces; gaps between pieces read as zeros (holes in the flashed partition are erased)."""
    buf = bytearray(n); end = off + n
    for o, s, p, _ in placed:
        lo, hi = max(off, o), min(end, o + s)
        if lo < hi:
            with open(p, 'rb') as f: f.seek(lo - o); buf[lo - off:hi - off] = f.read(hi - lo)
    return bytes(buf)
geo = read_super(4096, 52)
magic, gsz = struct.unpack('<II', geo[:8])
if magic != 0x616C4467: sys.exit(f'LP geometry magic missing at 4096 (got {magic:#x}): layout not proven')
mmax, slots, lbs = struct.unpack('<III', geo[40:52])
hdr = read_super(4096 * 3, 256)
hm, major, minor, hsize = struct.unpack('<IHHI', hdr[:12])
if hm != 0x414C5030: sys.exit(f'LP metadata header magic {hm:#x}')
tsize = struct.unpack('<I', hdr[44:48])[0]
desc = [struct.unpack('<III', hdr[80 + 12 * i:92 + 12 * i]) for i in range(4)]   # partitions, extents, groups, block_devices
tables = read_super(4096 * 3 + hsize, tsize)
assert hashlib.sha256(tables).digest() == hdr[48:80], 'LP tables checksum mismatch'
def table(i):
    o, n, es = desc[i]; return [tables[o + k * es:o + (k + 1) * es] for k in range(n)]
ext = [struct.unpack('<QIQI', e[:24]) for e in table(1)]
parts = {}
for e in table(0):
    nm = e[:36].rstrip(b'\0').decode(); attrs, fi, ne, gi = struct.unpack('<IIII', e[36:52]); parts[nm] = ext[fi:fi + ne]
os.makedirs(out, exist_ok=True); receipt = {'sector_size': ss, 'partition_start_sector': start, 'pieces': [(f, o, s) for o, s, _, f in placed],
                                            'lp_version': f'{major}.{minor}', 'logical_partitions': {k: sum(x[0] for x in v) * 512 for k, v in parts.items()}, 'written': {}}
for n in names:
    key = n if n in parts else n + '_a'
    if key not in parts: sys.exit(f'{n} not in LP metadata: {sorted(parts)}')
    dst = os.path.join(out, n + '.img')
    with open(dst, 'wb') as w:
        for nsec, ttype, tdata, tsrc in parts[key]:
            if ttype == 1: w.seek(nsec * 512, 1); continue        # ZERO extent
            if ttype != 0 or tsrc != 0: sys.exit(f'{key}: unsupported extent type {ttype} / block device {tsrc}')
            left, pos = nsec * 512, tdata * 512
            while left:
                k = min(left, 64 << 20); b = read_super(pos, k); w.write(b); left -= k; pos += k
        w.truncate()
    receipt['written'][n] = {'lp_name': key, 'bytes': os.path.getsize(dst)}
print(json.dumps(receipt, indent=1))
