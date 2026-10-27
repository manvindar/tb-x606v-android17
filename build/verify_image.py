#!/usr/bin/env python3
"""Compare two raw ext4 system images file by file: type, mode, uid, gid, SELinux label and content sha256 (symlink target for links).
APEX files are compared by the files inside their payload, because every repack gets a new AVB salt and signature. Compiled app code
(oat/, *.odex, *.vdex, *.art, *.oat) is skipped by default: it comes from dex2oat on a booted tablet (tools/scripts/dexopt_on_tablet.sh).

usage: verify_image.py A.img B.img [--e2fs DIR] [--keep-oat] [--ignore REGEX ...]
       verify_image.py A.img --save A.json         (write the manifest; either side of a comparison may then be given as FILE.json)
exit 0 when nothing differs (after the ignores), 1 otherwise. Needs a few GB of free disk in $TMPDIR (one image is rdumped at a time)."""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, zipfile

ap = argparse.ArgumentParser()
ap.add_argument('a'); ap.add_argument('b', nargs='?'); ap.add_argument('--save')
ap.add_argument('--e2fs', default=None); ap.add_argument('--keep-oat', action='store_true'); ap.add_argument('--ignore', action='append', default=[])
args = ap.parse_args()
E2 = args.e2fs or next((d for d in ('/opt/homebrew/opt/e2fsprogs/sbin', '/sbin', '/usr/sbin') if os.path.exists(d + '/debugfs')), '')
DEBUGFS = os.path.join(E2, 'debugfs') if E2 else 'debugfs'
def dbg(*c, input=None): return subprocess.run([DEBUGFS, *c], capture_output=True, input=input).stdout.decode('latin1')
OAT = re.compile(r'(/oat/|\.odex$|\.vdex$|\.art$|\.oat$)')

def listing(img):
    """BFS over the directories, one debugfs session per level: {path: [type, mode, uid, gid, size]} (`ls -p` prints /inode/mode/uid/gid/name/size/)"""
    out = {}; level = ['/']
    while level:
        txt = dbg('-f', '/dev/stdin', img, input=''.join('ls -p %s\n' % d for d in level).encode()).split('\n')
        nxt = []; cur = None
        for line in txt:
            if line.startswith('debugfs: ls -p '): cur = line[len('debugfs: ls -p '):]; continue
            f = line.split('/')
            if cur is None or len(f) < 7 or f[5] in ('.', '..', ''): continue
            mode = int(f[2], 8); p = cur.rstrip('/') + '/' + f[5]
            t = 'd' if mode & 0o170000 == 0o040000 else 'l' if mode & 0o170000 == 0o120000 else 'f'
            out[p] = [t, oct(mode & 0o7777), int(f[3]), int(f[4]), int(f[6] or 0)]
            if t == 'd': nxt.append(p)
        level = nxt
    return out

def labels(img, paths):
    res = {}
    for i in range(0, len(paths), 20000):
        txt = dbg('-f', '/dev/stdin', img, input=''.join('ea_get -V %s security.selinux\n' % p for p in paths[i:i + 20000]).encode())
        cur = None
        for line in txt.split('\n'):
            m = re.match(r'debugfs: ea_get -V (.*) security\.selinux$', line)
            if m: cur = m.group(1); res[cur] = ''; continue
            if cur is not None and line and not line.startswith('debugfs:'): res[cur] = line.strip()
    return res

def hash_tree(root):
    h = {}
    for d, _, fs in os.walk(root):
        for n in fs:
            p = os.path.join(d, n); rel = '/' + os.path.relpath(p, root)
            if os.path.islink(p): h[rel] = 'link:' + os.readlink(p)
            else:
                s = hashlib.sha256()
                with open(p, 'rb') as f:
                    for c in iter(lambda: f.read(1 << 20), b''): s.update(c)
                h[rel] = s.hexdigest()
    return h

def content_hashes(img, m):
    """rdump every top-level directory (chown errors as non-root are harmless) and hash the tree"""
    tmp = tempfile.mkdtemp(prefix='rdv')
    try:
        top = [(p, v[0]) for p, v in m.items() if p.count('/') == 1 and p != '/lost+found' and v[0] != 'l']
        dbg('-f', '/dev/stdin', img, input=''.join(('rdump %s %s\n' if t == 'd' else 'dump %s %s%s\n') % ((p, tmp) if t == 'd' else (p, tmp, p)) for p, t in top).encode())
        return hash_tree(tmp)
    finally: shutil.rmtree(tmp, ignore_errors=True)

def apex_inner(img, path):
    """{file: sha256} of the payload of an apex (or capex) stored in the image; the AVB footer behind the filesystem is ignored by debugfs"""
    t = tempfile.mkdtemp(prefix='apx')
    try:
        open(t + '/a', 'wb').write(subprocess.run([DEBUGFS, '-R', 'cat ' + path, img], capture_output=True).stdout)
        z = zipfile.ZipFile(t + '/a')
        if 'original_apex' in z.namelist(): open(t + '/o', 'wb').write(z.read('original_apex')); z = zipfile.ZipFile(t + '/o')
        open(t + '/p.img', 'wb').write(z.read('apex_payload.img'))
        pm = listing(t + '/p.img')
        h = content_hashes(t + '/p.img', pm)
        return {p: [v[1], v[2], v[3], h.get(p, '')] for p, v in pm.items() if v[0] != 'd' and not OAT.search(p)}
    except Exception as e: return {'unreadable': str(e)}
    finally: shutil.rmtree(t, ignore_errors=True)

def manifest(img):
    m = listing(img)
    if not args.keep_oat: m = {p: v for p, v in m.items() if not OAT.search(p + ('/' if v[0] == 'd' else ''))}
    hs = content_hashes(img, m); lb = labels(img, list(m))
    for p, v in m.items(): v += [lb.get(p, ''), hs.get(p, '') if v[0] != 'd' else '']
    for p in [p for p, v in m.items() if v[0] == 'f' and re.search(r'\.c?apex$', p)]:
        m[p][6] = 'apex:' + json.dumps(apex_inner(img, p), sort_keys=True)
    return m

def load(x): return json.load(open(x)) if x.endswith('.json') else manifest(x)

if args.save: json.dump(manifest(args.a), open(args.save, 'w')); sys.exit(0)
A, B = load(args.a), load(args.b); ign = [re.compile(r) for r in args.ignore]; bad = 0
for p in sorted(set(A) | set(B)):
    if any(r.search(p) for r in ign): continue
    a, b = A.get(p), B.get(p)
    if a is None or b is None: print('only in %s: %s' % ('B' if a is None else 'A', p)); bad += 1; continue
    what = [n for n, x, y in zip(('type', 'mode', 'uid', 'gid', 'size', 'label', 'content'), a, b) if x != y and not (n == 'size' and a[0] != 'f')]
    if a[6].startswith('apex:'): what = [w for w in what if w != 'size']
    if not what: continue
    bad += 1
    if what == ['content'] and a[6].startswith('apex:'):
        ia, ib = json.loads(a[6][5:]), json.loads(b[6][5:])
        print('apex differs: %s: %s' % (p, [k for k in sorted(set(ia) | set(ib)) if ia.get(k) != ib.get(k)][:10]))
    else: print('differs (%s): %s  %s -> %s' % (','.join(what), p, [str(x)[:60] for x in a[:6]], [str(x)[:60] for x in b[:6]]))
print('%d difference(s); %d vs %d entries' % (bad, len(A), len(B)))
sys.exit(1 if bad else 0)
