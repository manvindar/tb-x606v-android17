#!/usr/bin/env python3
"""com.android.virt.apex is the only EROFS APEX in the GSI and kernel 4.9 has no EROFS, so apexd-bootstrap fails (and the framework still references its jar).
Convert its payload to ext4 and re-sign it exactly like repack_apex.py does (own AVB key, new apex_pubkey, page aligned zip, APK signature).
usage: virt_to_ext4.py IN.apex OUT.apex KEY.pem
needs: erofs-utils (fsck.erofs), e2fsprogs (mke2fs; E2FS env), zip/unzip, zipalign (ZIPALIGN env), apksigner (APKSIGNER) + keystore (APEX_SIGN_KS, password 'android')."""
import os, re, shutil, subprocess, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__))
E2 = os.environ.get('E2FS', '')
ZA = os.environ.get('ZIPALIGN', 'zipalign'); SIGNER = os.environ.get('APKSIGNER', 'apksigner'); ks = os.environ['APEX_SIGN_KS']
inp, outp, key = sys.argv[1:4]
tmp = tempfile.mkdtemp()
def run(*a, **k):
    r = subprocess.run(a, capture_output=True, text=True, **k)
    if r.returncode: sys.exit('failed: %s\n%s%s' % (' '.join(a), r.stdout[-500:], r.stderr[-1500:]))
    return r.stdout
run('unzip', '-o', '-q', inp, '-d', tmp + '/z')
pay = tmp + '/z/apex_payload.img'
info = run('python3', '-I', here + '/avbtool.py', 'info_image', '--image', pay)
apexkey = re.search(r"Prop: apex.key -> '([^']+)'", info).group(1)
orig = int(re.search(r'Original image size:\s+(\d+)', info).group(1))
with open(pay, 'r+b') as f: f.truncate(orig)
os.makedirs(tmp + '/tree')
run('fsck.erofs', '--extract=' + tmp + '/tree', pay)
size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(tmp + '/tree') for f in fs if not os.path.islink(os.path.join(d, f)))
size = (int(size * 1.25) + (24 << 20)) // 4096 * 4096
ext4 = tmp + '/payload.img'
run(os.path.join(E2, 'mke2fs'), '-q', '-t', 'ext4', '-b', '4096', '-O', '^has_journal', '-m', '0', '-L', '', '-E', 'root_owner=0:0', '-d', tmp + '/tree', ext4, str(size // 1024) + 'k')
shutil.move(ext4, pay)
total = (size + size // 64 + (1 << 20)) // 4096 * 4096   # hashtree is 1/128 of the data (+ vbmeta, footer)
run('python3', '-I', here + '/avbtool.py', 'add_hashtree_footer', '--image', pay, '--partition_size', str(total), '--partition_name', '',
    '--do_not_generate_fec', '--algorithm', 'SHA256_RSA4096', '--key', key, '--hash_algorithm', 'sha256', '--block_size', '4096',
    '--salt', os.urandom(32).hex(), '--prop', 'apex.key:' + apexkey)
run('python3', '-I', here + '/avbtool.py', 'extract_public_key', '--key', key, '--output', tmp + '/z/apex_pubkey')
names = [n for n in run('unzip', '-Z1', inp).split('\n') if n and not n.endswith('/')]
run('zip', '-q', '-0', '-X', tmp + '/o.zip', *names, cwd=tmp + '/z')
run(ZA, '-f', '4096', tmp + '/o.zip', tmp + '/aligned.apex')
if os.path.exists(outp): os.remove(outp)
run(SIGNER, 'sign', '--ks', ks, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--v1-signing-enabled', 'false',
    '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--alignment-preserved', 'true', '--out', outp, tmp + '/aligned.apex')
shutil.rmtree(tmp); print('wrote', outp, os.path.getsize(outp))
