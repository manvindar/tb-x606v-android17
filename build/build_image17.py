#!/usr/bin/env python3
"""Android 17 (Google aosp_arm64 GSI) -> TB-X606V system image. EXPERIMENTAL, does not boot to a UI yet (see README).

usage: build_image17.py --gsi system.img --a16 system-a16.img --out system-a17.img [--port-repo DIR] [--with-tuner] [--no-bootdiag]

Everything is done offline with debugfs (no mount, runs on macOS). Steps, each one fixes a failure seen on the tablet:
  1  copy, grow to the 0xf3800000-byte partition, drop ext4 shared_blocks
  2  init: boot SELinux permissive (the GSI is a *user* build, so the Android 16 patch does not apply)
  3  build.prop: debuggable, adb unauthenticated, Bluetooth MediaTek workaround
  4  VNDK v29 apex copied from the Android 16 image (the Android 10 vendor links against it; the GSI ships v31-v34)
  5  SELinux mapping 29.0 for the Android 10 vendor policy, copied from the Android 16 image and pruned of types that
     Android 17 removed (secilc fails otherwise)
  6  cgroups.json: no blkio controller (kernel has none), freezer on cgroup2 optional
  7  com.android.virt.apex removed (EROFS payload, kernel 4.9 has no EROFS)
  8  /system netbpfload.rc without reboot_on_failure. NOT ENOUGH: the tethering APEX ships its own bpfloader service with the
     same option, and netbpfload itself refuses kernels older than 4.14 ('Android U requires kernel 4.14'). This is the wall.
  9  lmkd replaced by an idle stub (needs PSI/memcg; it is a critical service that would reboot the tablet)
The vendor partition needs its own fix (tools/fix_vendor_a17.py)."""
import argparse, hashlib, os, re, shutil, subprocess, sys, tempfile

ap = argparse.ArgumentParser()
ap.add_argument('--gsi', required=True); ap.add_argument('--a16', required=True, help='Android 16 system image (raw ext4) that boots on the tablet')
ap.add_argument('--out', required=True)
ap.add_argument('--e2fs', default='/opt/homebrew/opt/e2fsprogs/sbin')
ap.add_argument('--port-repo', default=os.path.expanduser('~/Aistuff/tb-x606v-android16-port'), help='Android 16 port repo (ROM Tuner, boot diagnostics)')
ap.add_argument('--with-tuner', action='store_true'); ap.add_argument('--no-bootdiag', action='store_true')
a = ap.parse_args()
E = lambda t: os.path.join(a.e2fs, t)
PART = 0xf3800000
INIT_SHA = 'bb702f17e6660ef8f2a1fd9158c2de876fa0c8f9679a7a49f4a2d02e1119451f'   # DP11.261002.005 /system/bin/init
SYS, INIT_L, LMKD_L = 'u:object_r:system_file:s0', 'u:object_r:init_exec:s0', 'u:object_r:lmkd_exec:s0'

def run(*c, ok=(0,)):
    r = subprocess.run(c, capture_output=True, text=True)
    if r.returncode not in ok: sys.exit(f'{c[0]} failed ({r.returncode}):\n{r.stdout[-2000:]}{r.stderr[-2000:]}')
    return r.stdout
def cat(img, path): return subprocess.run([E('debugfs'), '-R', f'cat {path}', img], capture_output=True).stdout

# 1 ------------------------------------------------------------------------------------------------------------------
if open(a.gsi, 'rb').read(4) == bytes.fromhex('3aff26ed'): sys.exit('sparse image: convert to raw first (simg2img)')
shutil.copyfile(a.gsi, a.out)
with open(a.out, 'r+b') as f: f.truncate(PART)
run(E('e2fsck'), '-fy', a.out, ok=(0, 1)); run(E('resize2fs'), a.out)
run(E('e2fsck'), '-fy', '-E', 'unshare_blocks', a.out, ok=(0, 1)); run(E('e2fsck'), '-fn', a.out)

tmp = tempfile.mkdtemp(); cmds = []; lbl = {}
def label_file(ctx):
    if ctx not in lbl:
        lbl[ctx] = os.path.join(tmp, 'l%d' % len(lbl)); open(lbl[ctx], 'wb').write(ctx.encode() + b'\0')
    return lbl[ctx]
def put(src, dst, mode, gid=0, ctx=SYS):
    cmds.extend([f'rm {dst}', f'write {src} {dst}', f'set_inode_field {dst} mode 0100{mode}', f'set_inode_field {dst} uid 0',
                 f'set_inode_field {dst} gid {gid}', f'ea_set -f {label_file(ctx)} {dst} security.selinux'])
def mkdir(path):
    cmds.extend([f'mkdir {path}', f'set_inode_field {path} mode 040755', f'set_inode_field {path} uid 0', f'set_inode_field {path} gid 0',
                 f'ea_set -f {label_file(SYS)} {path} security.selinux'])
def tmpfile(name, data):
    p = os.path.join(tmp, name); open(p, 'wb' if isinstance(data, bytes) else 'w').write(data); return p

# 2 init (user build: init hard-codes "if getenforce != 1 then setenforce(1)")
init = cat(a.out, '/system/bin/init')
assert hashlib.sha256(init).hexdigest() == INIT_SHA, 'unexpected init build: the patch offsets would be wrong'
d = bytearray(init)
assert d[0x103d0c:0x103d14].hex() == '00010054' + '20008052'          # b.eq skip ; mov w0,#1
d[0x103d0c:0x103d10] = bytes.fromhex('01010054')                      # b.ne skip
d[0x103d10:0x103d14] = bytes.fromhex('00008052')                      # mov w0,#0   -> setenforce(0) when the kernel is enforcing
put(tmpfile('init.orig', init), '/system/bin/init.enforcing', '755'); put(tmpfile('init.p', bytes(d)), '/system/bin/init', '755', 2000, INIT_L)

# 3 build.prop
bp = cat(a.out, '/system/build.prop').decode()
for x, y in (('ro.secure=1', 'ro.secure=0'), ('ro.debuggable=0', 'ro.debuggable=1'), ('ro.force.debuggable=0', 'ro.force.debuggable=1'), ('ro.adb.secure=1', 'ro.adb.secure=0')):
    assert x in bp, x; bp = bp.replace(x, y)
bp += '\npersist.sys.bt.unsupported.commands=182\npersist.sys.usb.config=adb\n'
put(tmpfile('build.prop', bp), '/system/build.prop', '600')

# 4 VNDK v29
vndk = cat(a.a16, '/system/system_ext/apex/com.android.vndk.v29.apex'); assert len(vndk) > 50_000_000
put(tmpfile('vndk29.apex', vndk), '/system/system_ext/apex/com.android.vndk.v29.apex', '644')

# 5 SELinux mapping 29.0
defined = set()
for p in ('/system/etc/selinux/plat_sepolicy.cil', '/system/system_ext/etc/selinux/system_ext_sepolicy.cil', '/system/product/etc/selinux/product_sepolicy.cil'):
    t = cat(a.out, p).decode('latin1')
    defined |= set(re.findall(r'\((?:type|typeattribute|typealias) ([A-Za-z0-9_.]+)\)', t))
KW = {'and', 'or', 'not', 'xor', 'all'}
def prune(text):
    out, dropped, stripped = [], 0, 0
    for l in text.split('\n'):
        m = re.match(r'^\(typeattributeset (\S+) \((.*)\)\)\s*$', l)
        if m and not re.search(r'\((and|or|not|xor)\b', m.group(2)):    # single-line plain lists only; nested expressions are left alone
            toks = m.group(2).split(); keep = [t for t in toks if t in defined or t in KW]
            if not keep: dropped += 1; continue                          # an empty list is a secilc syntax error
            if len(keep) != len(toks): stripped += 1; l = '(typeattributeset %s (%s))' % (m.group(1), ' '.join(keep))
        out.append(l)
    print(f'mapping 29.0: {stripped} statements pruned, {dropped} dropped'); return '\n'.join(out)
put(tmpfile('map29.cil', prune(cat(a.a16, '/system/etc/selinux/mapping/29.0.cil').decode('latin1'))), '/system/etc/selinux/mapping/29.0.cil', '644')
# 29.0.compat.cil defines its own "vendordomain": copy unchanged. system_ext/product mappings for 29.0 are empty files.
put(tmpfile('map29c.cil', cat(a.a16, '/system/etc/selinux/mapping/29.0.compat.cil')), '/system/etc/selinux/mapping/29.0.compat.cil', '644')
for p in ('/system/system_ext/etc/selinux/mapping/29.0.cil', '/system/system_ext/etc/selinux/mapping/29.0.compat.cil', '/system/product/etc/selinux/mapping/29.0.cil'):
    put(tmpfile('empty', ''), p, '644')

# 6 cgroups.json
import json
cg = json.loads(cat(a.out, '/system/etc/cgroups.json'))
cg['Cgroups'] = [c for c in cg['Cgroups'] if c['Controller'] != 'blkio']
for c in cg['Cgroups2']['Controllers']:
    if c['Controller'] == 'freezer': c['Optional'] = True
put(tmpfile('cgroups.json', json.dumps(cg, indent=2)), '/system/etc/cgroups.json', '644')

# 7 EROFS APEX
cmds.append('rm /system/apex/com.android.virt.apex')   # (debugfs has no rename; the original stays in the GSI zip)

# 8 netbpfload
rc = cat(a.out, '/system/etc/init/netbpfload.rc').decode()
assert 'reboot_on_failure reboot,netbpfload-missing' in rc
put(tmpfile('netbpfload.rc', '\n'.join(l for l in rc.split('\n') if 'reboot_on_failure reboot,netbpfload-missing' not in l)), '/system/etc/init/netbpfload.rc', '644')

# 9 lmkd stub
put(tmpfile('lmkd', '#!/system/bin/sh\n# Android 17 lmkd needs PSI/memcg (absent in this 4.9 kernel); it exits at once and, being critical, would reboot the tablet.\n'
                    '# The in-kernel lowmemorykiller does the work. This stub only keeps the service and its socket alive.\nexec /system/bin/toybox sleep 2000000000\n'),
    '/system/bin/lmkd', '755', 0, LMKD_L)

# optional: ROM Tuner and boot diagnostics from the Android 16 port repo. The 'su' SELinux domain does not exist in a user-build policy.
R = os.path.join(a.port_repo, 'rom-tuner'); media = '/system/product/media'
def rc_fix(path): return open(path).read().replace('u:r:su:s0', 'u:r:init:s0')
if a.with_tuner:
    mkdir('/system/app/RomTuner'); put(os.path.join(R, 'RomTuner.apk'), '/system/app/RomTuner/RomTuner.apk', '644')
    put(os.path.join(R, 'device', 'tablet_tunerd.sh'), '/system/bin/tablet_tunerd.sh', '755')
    put(tmpfile('tablet_tunerd.rc', rc_fix(os.path.join(R, 'device', 'tablet_tunerd.rc'))), '/system/etc/init/tablet_tunerd.rc', '644')
    arrow = os.path.join(R, 'boot-animation', 'bootanimation-arrow.zip')
    put(arrow, media + '/bootanimation-arrow.zip', '644'); put(arrow, media + '/bootanimation.zip', '644')
if not a.no_bootdiag:
    put(os.path.join(a.port_repo, 'debug', 'bootdiag.sh'), '/system/bin/bootdiag.sh', '755')
    put(tmpfile('zz_bootdiag.rc', rc_fix(os.path.join(a.port_repo, 'debug', 'zz_bootdiag.rc'))), '/system/etc/init/zz_bootdiag.rc', '644')

open(os.path.join(tmp, 'cmds'), 'w').write('\n'.join(cmds) + '\n')
out = run(E('debugfs'), '-w', '-f', os.path.join(tmp, 'cmds'), a.out)
if re.search(r'(?i)(error|not found|no such)', out): print('debugfs warnings:\n' + '\n'.join(l for l in out.split('\n') if re.search(r'(?i)(error|not found|no such)', l)))
run(E('e2fsck'), '-fn', a.out)
print('image:', a.out, os.path.getsize(a.out), 'bytes, sha256', hashlib.sha256(open(a.out, 'rb').read()).hexdigest())
