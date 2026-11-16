#!/usr/bin/env python3
"""Runs every binary patch tool of the builder against a GSI image, one by one, and prints which still apply. Needs only the raw GSI system.img (and debugfs); takes a minute.
Use it first on a new GSI build: it lists all the patches that have to be ported at once, instead of the one that stops the builder.
usage: check_patches.py SYSTEM.IMG [--e2fs DIR]"""
import argparse, glob, hashlib, json, os, shutil, subprocess, sys, tempfile, zipfile
HERE = os.path.dirname(os.path.abspath(__file__)); TOOLS = os.path.join(HERE, '..', 'tools')
ap = argparse.ArgumentParser(); ap.add_argument('img'); ap.add_argument('--e2fs'); ap.add_argument('--evidence-out', help='retain exact input files and manifest for offline patch porting; max 128 MiB')
a = ap.parse_args()
E2 = a.e2fs or next((d for d in ('/opt/homebrew/opt/e2fsprogs/sbin', '/usr/local/opt/e2fsprogs/sbin', '/sbin', '/usr/sbin') if os.path.exists(d + '/debugfs')), '')
W = tempfile.mkdtemp()
def dump(img, path, dst):
    r = subprocess.run([os.path.join(E2, 'debugfs'), '-R', f'dump {path} {dst}', img], capture_output=True, text=True)
    return os.path.exists(dst) and os.path.getsize(dst) > 0
def apex_payload(apex):
    dst = os.path.join(W, os.path.basename(apex) + '.payload')
    if os.path.exists(dst): return dst
    z = os.path.join(W, 'a.zip'); assert dump(a.img, apex, z), apex
    zf = zipfile.ZipFile(z)
    if 'original_apex' in zf.namelist(): open(z + '.o', 'wb').write(zf.read('original_apex')); zf = zipfile.ZipFile(z + '.o')
    open(dst, 'wb').write(zf.read('apex_payload.img')); return dst
def get(src):
    """src = /system/... or apex:/system/apex/X.capex:/inner/path"""
    out = os.path.join(W, 'in.bin')
    if os.path.exists(out): os.remove(out)
    if src.startswith('apex:'):
        _, apex, inner = src.split(':'); return out if dump(apex_payload(apex), inner, out) else None
    return out if dump(a.img, src, out) else None
T = '/system/apex/com.android.tethering.capex'; RT = '/system/apex/com.android.runtime.apex'
CHECKS = [
    ('framework/patch_services_jar.py', '/system/framework/services.jar'), ('libc/patch_libc.py', '/system/lib64/bootstrap/libc.so'), ('libc/patch_libc.py', '/system/lib/bootstrap/libc.so'),
    ('libc/patch_libc.py', '/system/bin/bootstrap/linker64'), ('libc/patch_libc.py', '/system/bin/bootstrap/linker'),
    ('libc/patch_libui.py', '/system/lib64/libui.so'), ('libc/patch_libui.py', '/system/lib/libui.so'), ('libc/patch_pgsetup.py', '/system/lib64/libprocessgroup_setup.so'),
    ('libc/patch_libmeminfo.py', '/system/lib64/libmeminfo.so'), ('libc/patch_libbinder.py', '/system/lib64/libbinder.so'), ('libc/patch_libandroid_runtime.py', '/system/lib64/libandroid_runtime.so'),
    ('libc/patch_libc.py', f'apex:{RT}:/lib64/bionic/libc.so'), ('libc/patch_libc.py', f'apex:{RT}:/lib/bionic/libc.so'),
    ('libc/patch_libc.py', f'apex:{RT}:/bin/linker64'), ('libc/patch_libc.py', f'apex:{RT}:/bin/linker'),
    ('bpf/patch_binaries.py netbpfload', f'apex:{T}:/bin/netbpfload'), ('bpf/patch_binaries.py netd_updatable', f'apex:{T}:/lib64/libnetd_updatable.so'),
    ('bpf/patch_binaries.py service_connectivity', f'apex:{T}:/lib64/libservice-connectivity.so'), ('framework/patch_service_connectivity_jar.py', f'apex:{T}:/javalib/service-connectivity.jar'),
    ('bpf/patch_bluetooth.py', 'apex:/system/apex/com.android.bt.capex:/lib64/libbluetooth_jni.so'),
    ('libc/patch_libui.py', 'apex:/system/apex/com.android.media.swcodec.capex:/lib64/libui.so'), ('libc/patch_libui.py', 'apex:/system/apex/com.android.media.swcodec.capex:/lib/libui.so'),
]
evidence = []; retained_bytes = 0
if a.evidence_out:
    os.makedirs(a.evidence_out, exist_ok=False)
def retain(src, path):
    global retained_bytes
    if not a.evidence_out or any(r['source'] == src for r in evidence): return
    if not path: raise RuntimeError('Required evidence input missing: ' + src)
    size = os.path.getsize(path)
    if not 0 < size <= 64 * 1024 * 1024 or retained_bytes + size > 128 * 1024 * 1024:
        raise RuntimeError('Evidence size cap exceeded: ' + src)
    name = '%02d-%s' % (len(evidence), os.path.basename(src))
    dst = os.path.join(a.evidence_out, name); shutil.copyfile(path, dst)
    with open(dst, 'rb') as f: sha = hashlib.file_digest(f, 'sha256').hexdigest()
    evidence.append({'source': src, 'file': name, 'bytes': size, 'sha256': sha})
    retained_bytes += size
if a.evidence_out:
    for src in ['/system/bin/init', '/system/build.prop']:
        retain(src, get(src))
fails = 0
for tool, src in CHECKS:
    t = tool.split(); f = get(src); name = f'{t[0]:48} {src.replace("/system/apex/", "")}'
    if not f: print(f'MISS {name}'); fails += 1; continue
    retain(src, f)
    out = os.path.join(W, 'out.bin')
    r = subprocess.run([sys.executable, '-I', os.path.join(TOOLS, t[0]), *t[1:], f, out], capture_output=True, text=True)
    if src.endswith('services.jar') and r.returncode == 0:    # the location patch is chained after it
        r2 = subprocess.run([sys.executable, '-I', os.path.join(TOOLS, 'framework/patch_location_provider.py'), out, out + '.2'], capture_output=True, text=True)
        if r2.returncode: r = r2; name += '  (location patch)'
    if r.returncode: fails += 1; print(f'FAIL {name}  ::  {(r.stderr.strip().splitlines() or ["?"])[-1][:140]}')
    else: print(f'ok   {name}')
if a.evidence_out:
    with open(os.path.join(a.evidence_out, 'manifest.json'), 'w') as f:
        json.dump({'scope': 'unmodified GSI inputs; no ROM/runtime acceptance', 'patch_failures': fails, 'files': evidence}, f, indent=2)
shutil.rmtree(W, ignore_errors=True)
print(f'\n{fails} of {len(CHECKS)} patches do not apply'); sys.exit(1 if fails else 0)
