#!/usr/bin/env python3
"""Fold a device build's system, system_ext and product images into one GSI-layout system image.

usage: compose_system.py --system S.img --system-ext SE.img --product P.img --exclude LIST --size BYTES --out OUT.img
                         [--e2fs DIR] [--stage DIR] [--like GSI.img]

Layout: /system/system_ext and /system/product become directories holding those images' trees, and
/system_ext and /product at the root become symlinks to them (the GSI layout the builder expects).
Every file, directory and symlink keeps its mode, uid, gid and all xattrs (SELinux label, capabilities).
LIST has one path per line in the output layout (e.g. /system/preinstall, /system/product/app);
a listed path and everything under it is left out. Hard links become separate copies.
The output is a fresh ext4 filesystem, so the donor's inode limit does not apply. --like copies
the ext4 feature list from a reference image (the GSI) so the old kernel can mount it.
"""
import argparse, os, re, shutil, subprocess, sys, tempfile

S_IFMT, S_IFDIR, S_IFLNK, S_IFREG = 0o170000, 0o040000, 0o120000, 0o100000


def run(cmd, **kw):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kw)
    return r.returncode, r.stdout


class Img:
    def __init__(s, e2, path):
        s.e2, s.path = e2, path

    def batch(s, cmds, write=False, tmp=None):
        f = os.path.join(tmp, 'cmds')
        with open(f, 'w') as fh:
            fh.write('\n'.join(cmds) + '\n')
        rc, out = run([os.path.join(s.e2, 'debugfs')] + (['-w'] if write else []) + ['-f', f, s.path])
        if rc:
            sys.exit(f'debugfs failed on {s.path}:\n{out[-2000:]}')
        return out

    def walk(s, tmp):
        """Every entry below / as {path: (mode, uid, gid)}."""
        ents, level = {}, ['/']
        while level:
            out = s.batch([f'ls -p "{p}"' for p in level], tmp=tmp)
            nxt, cur = [], None
            for line in out.splitlines():
                m = re.match(r'debugfs: ls -p "(.*)"$', line)
                if m:
                    cur = m.group(1); continue
                f = line.split('/')
                if cur is None or len(f) < 7 or f[5] in ('.', '..', '') or f[1] == '0':
                    continue
                mode, uid, gid = int(f[2], 8), int(f[3]), int(f[4])
                p = cur.rstrip('/') + '/' + f[5]
                ents[p] = (mode, uid, gid)
                if mode & S_IFMT == S_IFDIR:
                    nxt.append(p)
            level = nxt
        return ents

    def xattrs(s, paths, tmp):
        """{path: [(name, bytes)]} read with ea_get -f into files (exact bytes, no text decoding)."""
        names = {}
        out = s.batch([f'ea_list "{p}"' for p in paths], tmp=tmp)
        cur = None
        for line in out.splitlines():
            m = re.match(r'debugfs: ea_list "(.*)"$', line)
            if m:
                cur = m.group(1); continue
            m = re.match(r'\s+([a-z]+\.[A-Za-z0-9_.]+) \(\d+\)', line)
            if cur and m:
                names.setdefault(cur, []).append(m.group(1))
        jobs, files = [], {}
        for i, (p, ns) in enumerate(names.items()):
            for j, n in enumerate(ns):
                fn = os.path.join(tmp, 'xa', f'{i}_{j}')
                jobs.append(f'ea_get -f "{fn}" "{p}" {n}')
                files.setdefault(p, []).append((n, fn))
        os.makedirs(os.path.join(tmp, 'xa'), exist_ok=True)
        if jobs:
            s.batch(jobs, tmp=tmp)
        return {p: [(n, open(fn, 'rb').read()) for n, fn in v] for p, v in files.items()}

    def readlinks(s, paths, tmp):
        out = s.batch([f'stat "{p}"' for p in paths], tmp=tmp)
        res, cur = {}, None
        for line in out.splitlines():
            m = re.match(r'debugfs: stat "(.*)"$', line)
            if m:
                cur = m.group(1); continue
            m = re.search(r'Fast link dest: "(.*)"$', line)
            if cur and m:
                res[cur] = m.group(1)
        slow = [p for p in paths if p not in res]  # long targets live in a data block
        if slow:
            d = os.path.join(tmp, 'lnk'); os.makedirs(d, exist_ok=True)
            s.batch([f'dump "{p}" "{d}/{i}"' for i, p in enumerate(slow)], tmp=tmp)
            for i, p in enumerate(slow):
                res[p] = open(f'{d}/{i}', 'rb').read().decode()
        return res


def excluded(p, rules):
    return any(p == r or p.startswith(r.rstrip('/') + '/') for r in rules)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--system', required=True); ap.add_argument('--system-ext', required=True)
    ap.add_argument('--product', required=True); ap.add_argument('--exclude', required=True)
    ap.add_argument('--size', type=int, required=True); ap.add_argument('--out', required=True)
    ap.add_argument('--e2fs', default='/opt/homebrew/opt/e2fsprogs/sbin'); ap.add_argument('--stage')
    ap.add_argument('--like', help='reference image whose ext4 features the output copies')
    ap.add_argument('--add', action='append', default=[], metavar='IMG:PATH',
                    help='also copy PATH (file or subtree, same path in the output) from IMG; entries the main images already provide win')
    a = ap.parse_args()
    rules = [l.strip() for l in open(a.exclude) if l.strip() and not l.startswith('#')]
    tmp = tempfile.mkdtemp(dir=a.stage)
    srcs = [(Img(a.e2fs, a.system), ''), (Img(a.e2fs, a.system_ext), '/system/system_ext'),
            (Img(a.e2fs, a.product), '/system/product')]
    plan = {}  # out path -> (img, src path, mode, uid, gid)
    for img, prefix in srcs:
        for p, (mode, uid, gid) in img.walk(tmp).items():
            if p == '/lost+found' or p.startswith('/lost+found/'):
                continue
            q = prefix + p
            if prefix == '' and q in ('/system/system_ext', '/system/product', '/system_ext', '/product'):
                continue  # replaced by the GSI layout below
            if not excluded(q, rules):
                plan[q] = (img, p, mode, uid, gid)
    extra = []
    for spec in a.add:
        img_path, sub = spec.rsplit(':', 1)
        img = Img(a.e2fs, img_path); extra.append(img)
        ents = img.walk(tmp)
        for p, (mode, uid, gid) in ents.items():
            if (p == sub or p.startswith(sub.rstrip('/') + '/')) and p not in plan and not excluded(p, rules):
                plan[p] = (img, p, mode, uid, gid)
        d = os.path.dirname(sub)  # parents must already exist in the output
        assert d in plan or d in ('', '/'), f'--add {spec}: parent {d} is not in the output'
    srcs += [(img, None) for img in extra]
    # the GSI layout: real directories inside /system, symlinks at the root
    for img, prefix in srcs[1:3]:
        plan[prefix] = (img, '/', 0o040755, 0, 0)
    plan['/system_ext'] = (None, '/system/system_ext', 0o120777, 0, 0)
    plan['/product'] = (None, '/system/product', 0o120777, 0, 0)

    # xattrs and link targets, per source image
    xa, links = {}, {}
    for img, _ in srcs:
        mine = [(q, v) for q, v in plan.items() if v[0] is img]
        x = img.xattrs([v[1] for _, v in mine], tmp)
        lk = img.readlinks([v[1] for _, v in mine if v[2] & S_IFMT == S_IFLNK], tmp)
        for q, v in mine:
            xa[q] = x.get(v[1], [])
            if v[1] in lk:
                links[q] = lk[v[1]]
    # the new root symlinks take the label of the donor's root mountpoint dirs
    for q in ('/system_ext', '/product'):
        links[q] = plan[q][1]
        xa[q] = [(n, v) for n, v in xa.get('/system' + q, []) if n == 'security.selinux']

    # stage regular files
    stage = os.path.join(tmp, 'files'); os.makedirs(stage)
    for img, _ in srcs:
        regs = [(q, v[1]) for q, v in plan.items() if v[0] is img and v[2] & S_IFMT == S_IFREG]
        img.batch([f'dump "{s}" "{stage}/{i}"' for i, (q, s) in enumerate(regs)], tmp=tmp)
        for i, (q, s) in enumerate(regs):
            os.rename(f'{stage}/{i}', f'{stage}/f{abs(hash(q))}_{i}')
            plan[q] = plan[q] + (f'{stage}/f{abs(hash(q))}_{i}',)

    # fresh filesystem
    feats = []
    if a.like:
        _, out = run([os.path.join(a.e2fs, 'dumpe2fs'), '-h', a.like])
        m = re.search(r'Filesystem features:\s+(.*)', out)
        # start from no features so mke2fs defaults (metadata_csum, 64bit) do not creep in; shared_blocks is read-only, not creatable
        feats = ['-O', 'none,' + ','.join(f for f in m.group(1).split() if f != 'shared_blocks')] if m else []
    if os.path.exists(a.out):
        sys.exit(f'{a.out} exists; refusing to overwrite')
    with open(a.out, 'wb') as fh:
        fh.truncate(a.size)
    n_inodes = max(16384, 2 * len(plan))
    rc, out = run([os.path.join(a.e2fs, 'mke2fs'), '-F', '-q', '-t', 'ext4', '-b', '4096', '-N', str(n_inodes),
                   '-L', '/', '-E', 'root_owner=0:0'] + feats + [a.out])
    if rc:
        sys.exit('mke2fs failed:\n' + out)

    cmds = []
    xadir = os.path.join(tmp, 'xo'); os.makedirs(xadir)
    k = 0
    for q in sorted(plan, key=lambda p: (p.count('/'), p)):
        v = plan[q]; mode = v[2]; t = mode & S_IFMT
        parent, name = os.path.split(q)
        if t == S_IFDIR:
            cmds.append(f'mkdir "{q}"')
        elif t == S_IFLNK:
            cmds += [f'cd "{parent}"', f'symlink "{name}" "{links[q]}"', 'cd /']
        elif t == S_IFREG:
            cmds += [f'cd "{parent}"', f'write "{v[5]}" "{name}"', 'cd /']
        else:
            continue  # device nodes and fifos do not occur in system images
        cmds += [f'sif "{q}" mode 0{mode:o}', f'sif "{q}" uid {v[3]}', f'sif "{q}" gid {v[4]}']
        for n, val in xa.get(q, []):
            fn = os.path.join(xadir, str(k)); k += 1
            open(fn, 'wb').write(val)
            cmds.append(f'ea_set -f "{fn}" "{q}" {n}')
    # root and lost+found (created by mke2fs) take the system image's attributes
    for d, xs in Img(a.e2fs, a.system).xattrs(['/', '/lost+found'], tmp).items():
        for n, val in xs:
            fn = os.path.join(xadir, str(k)); k += 1
            open(fn, 'wb').write(val); cmds.append(f'ea_set -f "{fn}" "{d}" {n}')
    out = Img(a.e2fs, a.out).batch(cmds, write=True, tmp=tmp)
    bad = [l for l in out.splitlines() if not l.startswith('debugfs') and re.search(r'(?i)(error|not found|could not|failed|no space)', l)]
    if bad:
        sys.exit('debugfs reported:\n' + '\n'.join(bad[:30]))
    rc, out = run([os.path.join(a.e2fs, 'e2fsck'), '-fn', a.out])
    print(out.strip().splitlines()[-1])
    shutil.rmtree(tmp)
    print(f'entries {len(plan)}  out {a.out}')
    return rc


if __name__ == '__main__':
    sys.exit(main())
