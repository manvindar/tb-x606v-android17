#!/usr/bin/env python3
"""Replace files inside an APEX's ext4 payload and re-sign it with our own key (apexd accepts it: the public key only has to match the
one embedded in the apex, which we replace; /system apexes are trusted as-is). Same-size replacements are written in place (keeps inode + SELinux xattr).
needs also: apksigner + a keystore (APEX_SIGN_KS, password 'android').
usage: repack_apex.py IN.apex OUT.apex KEY.pem /path/in/payload=local_file [...] [+/new/path=local_file@/reference/path ...]
A '+' entry adds a file (or a symlink: '+/path=->target@ref') that the payload does not have (parent directories are created); it takes mode, uid, gid and xattrs
(SELinux label) from the reference path in the same payload. Adding needs room: the payload is unshared and grown first.
needs: e2fsprogs debugfs (E2FS env or /opt/homebrew/opt/e2fsprogs/sbin), zip/unzip, zipalign (ZIPALIGN env), avbtool.py next to this script."""
import os, re, struct, subprocess, sys, tempfile, shutil
here = os.path.dirname(os.path.abspath(__file__))
E2 = os.environ.get('E2FS', '/opt/homebrew/opt/e2fsprogs/sbin')
ZA = os.environ.get('ZIPALIGN', os.path.expanduser('~/Library/Android/sdk/build-tools/35.0.0/zipalign'))
inp, outp, key = sys.argv[1:4]
repl = dict(a.split('=', 1) for a in sys.argv[4:] if not a.startswith('+'))
adds = [(a[1:].split('=', 1)[0],) + tuple(a[1:].split('=', 1)[1].rsplit('@', 1)) for a in sys.argv[4:] if a.startswith('+')]
tmp = tempfile.mkdtemp()
def run(*a, **k):
    r = subprocess.run(a, capture_output=True, text=True, **k)
    if r.returncode: sys.exit('failed: %s\n%s' % (' '.join(a), r.stderr))
    return r.stdout
run('unzip', '-o', '-q', inp, '-d', tmp + '/z')
pay = tmp + '/z/apex_payload.img'
info = run('python3', '-I', here + '/avbtool.py', 'info_image', '--image', pay)
orig = int(re.search(r'Original image size:\s+(\d+)', info).group(1)); total = None
block = int(re.search(r'Data Block Size:\s+(\d+)', info).group(1)); apexkey = re.search(r"Prop: apex.key -> '([^']+)'", info).group(1)
total = (orig + orig // 64 + (1 << 20)) // block * block   # room for hashtree (1/128 of the data) + vbmeta + footer
with open(pay, 'r+b') as f: f.truncate(orig)                       # drop hashtree + vbmeta + footer
if adds:
    # rebuild the payload as a fresh ext4 (APEX payloads are built with no spare inodes, and the shared_blocks feature cannot be
    # grown in place): every entry keeps its full mode, uid, gid, xattrs and link target; the added files take theirs from a reference
    sys.path.insert(0, os.path.join(here, '..', 'zui'))
    from compose_system import Img, S_IFMT, S_IFDIR, S_IFLNK, S_IFREG
    src = Img(E2, pay); ents = src.walk(tmp)
    ents = {p: v for p, v in ents.items() if not (p == '/lost+found' or p.startswith('/lost+found/'))}
    xa = src.xattrs(['/'] + list(ents), tmp)
    links = src.readlinks([p for p, v in ents.items() if v[0] & S_IFMT == S_IFLNK], tmp)
    files = tmp + '/files'; os.makedirs(files)
    regs = [p for p, v in ents.items() if v[0] & S_IFMT == S_IFREG]
    src.batch([f'dump "{q}" "{files}/{i}"' for i, q in enumerate(regs)], tmp=tmp)
    local = {q: f'{files}/{i}' for i, q in enumerate(regs)}
    for path, loc, ref in adds:
        mode, uid, gid = ents[ref]
        parts = path.strip('/').split('/')
        for i in range(1, len(parts)):
            d = '/' + '/'.join(parts[:i])
            if d not in ents:
                ents[d] = ents[os.path.dirname(ref)]; xa[d] = xa.get(os.path.dirname(ref), [])
        if loc.startswith('->'):
            ents[path] = (S_IFLNK | 0o777, uid, gid); links[path] = loc[2:]
        else:
            ents[path] = (S_IFREG | (mode & 0o7777), uid, gid); local[path] = loc
        xa[path] = xa.get(ref, [])
    feats = re.search(r'Filesystem features:\s+(.*)', run(E2 + '/dumpe2fs', '-h', pay)).group(1).split()
    feats = [f for f in feats if f not in ('shared_blocks', 'needs_recovery')]
    need = sum(os.path.getsize(l) for l in local.values())
    orig = (need + need // 4 + (4 << 20) + block - 1) // block * block
    os.remove(pay)
    with open(pay, 'wb') as f: f.truncate(orig)
    run(E2 + '/mke2fs', '-F', '-q', '-t', 'ext4', '-b', str(block), '-N', str(max(256, 2 * len(ents))), '-O', 'none,' + ','.join(feats),
        '-E', 'root_owner=0:0', pay)
    cmds, k = [], 0
    def setx(q):
        global k
        for n, val in xa.get(q, []):
            fn = f'{tmp}/xn{k}'; k += 1; open(fn, 'wb').write(val); cmds.append(f'ea_set -f "{fn}" "{q}" {n}')
    setx('/')
    for q in sorted(ents, key=lambda p: (p.count('/'), p)):
        mode, uid, gid = ents[q]; t = mode & S_IFMT; parent, name = os.path.split(q)
        if t == S_IFDIR: cmds.append(f'mkdir "{q}"')
        elif t == S_IFLNK: cmds += [f'cd "{parent}"', f'symlink "{name}" "{links[q]}"', 'cd /']
        else: cmds += [f'cd "{parent}"', f'write "{local[q]}" "{name}"', 'cd /']
        cmds += [f'sif "{q}" mode 0{mode:o}', f'sif "{q}" uid {uid}', f'sif "{q}" gid {gid}']
        setx(q)
    out = Img(E2, pay).batch(cmds, write=True, tmp=tmp)
    bad = [l for l in out.split('\n') if l and not l.startswith('debugfs') and re.search(r'(?i)(error|could not|no space|not found)', l)]
    if bad: sys.exit('debugfs rebuild: ' + '\n'.join(bad[:10]))
    r = subprocess.run([E2 + '/e2fsck', '-fn', pay], capture_output=True, text=True)
    if r.returncode: sys.exit('payload fsck after rebuild failed\n' + r.stdout[-1500:])
    print('rebuilt payload with', len(adds), 'added entries;', len(ents), 'entries')
    total = (orig + orig // 64 + (1 << 20)) // block * block
for path, local in repl.items():
    new = open(local, 'rb').read()
    st = run(E2 + '/debugfs', '-R', 'stat ' + path, pay)
    size = int(re.search(r'Size: (\d+)', st).group(1)); assert size == len(new), (path, size, len(new))
    extents = []   # (logical start, physical start, length) from "debugfs ex" leaf lines (holes are simply not listed)
    for line in run(E2 + '/debugfs', '-R', 'ex ' + path, pay).split('\n'):
        m = re.match(r'\s*(\d+)/\s*(\d+)\s+\d+/\s*\d+\s+(\d+)\s*-\s*(\d+)\s+(\d+)\s*-\s*(\d+)\s+(\d+)', line)
        if m and m.group(1) == m.group(2): extents.append((int(m.group(3)), int(m.group(5)), int(m.group(7))))
    assert extents, path
    with open(pay, 'r+b') as f:
        for lbn, pbn, n in extents:
            f.seek(pbn * block); f.write(new[lbn * block:(lbn + n) * block].ljust(n * block, b'\0'))
    blocks = extents
    print('replaced', path, len(blocks), 'blocks')
run('python3', '-I', here + '/avbtool.py', 'add_hashtree_footer', '--image', pay, '--partition_size', str(total), '--partition_name', '',
    '--do_not_generate_fec', '--algorithm', 'SHA256_RSA4096', '--key', key, '--hash_algorithm', 'sha256', '--block_size', str(block),
    '--salt', os.urandom(32).hex(), '--prop', 'apex.key:' + apexkey)
run('python3', '-I', here + '/avbtool.py', 'extract_public_key', '--key', key, '--output', tmp + '/z/apex_pubkey')
names = [n for n in run('unzip', '-Z1', inp).split('\n') if n and not n.endswith('/')]
if os.path.exists(tmp + '/o.zip'): os.remove(tmp + '/o.zip')
run('zip', '-q', '-0', '-X', tmp + '/o.zip', *names, cwd=tmp + '/z')
if os.path.exists(outp): os.remove(outp)
run(ZA, '-f', '4096', tmp + '/o.zip', tmp + '/aligned.apex')   # every stored entry (incl. apex_payload.img) page aligned
# PackageManager also verifies the apex zip as an APK (v2 signing block); sign with any key, alignment is untouched
ks = os.environ.get('APEX_SIGN_KS', os.path.expanduser('~/Aistuff/android-aosp/work/a17/apex_sign.jks'))
SIGNER = os.environ.get('APKSIGNER', os.path.expanduser('~/Library/Android/sdk/build-tools/35.0.0/apksigner'))
run(SIGNER, 'sign', '--ks', ks, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--v1-signing-enabled', 'false',
    '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--alignment-preserved', 'true', '--out', outp, tmp + '/aligned.apex')
shutil.rmtree(tmp); print('wrote', outp, os.path.getsize(outp))
