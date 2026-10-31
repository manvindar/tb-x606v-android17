#!/usr/bin/env python3
"""Pixel Tablet (tangorpro) system_ext and product pieces for the GSI of the same build: SystemUI (SystemUITitan), Settings (SettingsGoogle), Pixel Launcher,
the Pixel config overlays and fonts. Taken from Google's factory image of the matching Android 17 build (DP11.261016.003 for the GSI DP11.261016.003); nothing is redistributable,
run it on your own download.
usage: stage_pixel_tablet.py FACTORY.zip OUT_DIR [--keys DIR] [--build-tools DIR] [--e2fs DIR]
OUT_DIR gets system_ext/... and product/... (paths under /system/system_ext and /system/product). The apps are re-signed with the public AOSP platform test key (the GSI's platform
certificate), so their signature permissions are granted and they can replace SystemUI and Settings; zipalign comes first. oat/ and *.prof are left out (compiled for another boot image)."""
import argparse, glob, os, shutil, subprocess, sys, tempfile, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser(); ap.add_argument('factory'); ap.add_argument('out'); ap.add_argument('--keys', default=HERE); ap.add_argument('--build-tools'); ap.add_argument('--e2fs')
a = ap.parse_args()
E2 = a.e2fs or next((d for d in ('/opt/homebrew/opt/e2fsprogs/sbin', '/usr/local/opt/e2fsprogs/sbin', '/sbin', '/usr/sbin') if os.path.exists(d + '/debugfs')), '')
BT = a.build_tools or sorted(glob.glob(os.path.expanduser('~/Library/Android/sdk/build-tools/*')) + glob.glob(os.environ.get('ANDROID_HOME', '/x') + '/build-tools/*'))[-1]
# (partition, path in the Pixel image) -> destination under /system/<partition>/ ; apps are re-signed
APPS = [('system_ext', '/priv-app/SystemUITitan/SystemUITitan.apk', 'priv-app/SystemUI/SystemUI.apk'),          # replaces the GSI's SystemUI (same package, com.android.systemui)
        ('system_ext', '/priv-app/SettingsGoogle/SettingsGoogle.apk', 'priv-app/Settings/Settings.apk'),         # replaces the GSI's Settings (com.android.settings)
        ('system_ext', '/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk', 'priv-app/NexusLauncherRelease/NexusLauncherRelease.apk')]
PLAIN = [('product', '/overlay/PixelConfigOverlayCommon.apk', 'overlay/PixelConfigOverlayCommon.apk'),          # recents component = Pixel Launcher, fonts, corner radii, Google services
         ('product', '/overlay/LargeScreenConfigOverlay.apk', 'overlay/LargeScreenConfigOverlay.apk'),
         # PixelConfigOverlayCommon points config_defaultModuleMetadataProvider at this package; without it ModuleInfoProvider never loads and system_server dies in
         # AppRestrictionController ("Call to getInstalledModules before metadata loaded")
         ('product', '/app/ModuleMetadataGoogle/ModuleMetadataGoogle.apk', 'app/ModuleMetadataGoogle/ModuleMetadataGoogle.apk'),
         ('product', '/etc/fonts_customization.xml', 'etc/fonts_customization.xml'),
         ('product', '/etc/sysconfig/google-hiddenapi-package-whitelist.xml', 'etc/sysconfig/google-hiddenapi-package-whitelist.xml')]
FONT_DIR = ('product', '/fonts')
def dump(img, src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    subprocess.run([os.path.join(E2, 'debugfs'), '-R', f'dump {src} {dst}', img], capture_output=True)
    assert os.path.exists(dst) and os.path.getsize(dst) > 0, f'{src} not found in {img}'
def lsnames(img, d):
    out = subprocess.run([os.path.join(E2, 'debugfs'), '-R', f'ls -p {d}', img], capture_output=True, text=True).stdout
    return [l.split('/')[5] for l in out.splitlines() if l.count('/') >= 6 and l.split('/')[5] not in ('.', '..')]
tmp = tempfile.mkdtemp(); out = os.path.abspath(a.out); shutil.rmtree(out, ignore_errors=True)
try:
    z = zipfile.ZipFile(a.factory); inner = next(n for n in z.namelist() if n.endswith('.zip') and '/image-' in n or n.startswith('image-'))
    print('inner zip', inner); z.extract(inner, tmp); iz = zipfile.ZipFile(os.path.join(tmp, inner)); img = {}
    for p in ('system_ext', 'product'): iz.extract(p + '.img', tmp); img[p] = os.path.join(tmp, p + '.img')
    os.remove(os.path.join(tmp, inner))
    for part, src, dst in APPS + PLAIN: dump(img[part], src, os.path.join(out, part, dst))
    for n in lsnames(img[FONT_DIR[0]], FONT_DIR[1]): dump(img[FONT_DIR[0]], FONT_DIR[1] + '/' + n, os.path.join(out, FONT_DIR[0], 'fonts', n))
    for part, src, dst in APPS:
        f = os.path.join(out, part, dst); al = f + '.al'
        subprocess.run([os.path.join(BT, 'zipalign'), '-p', '-f', '4', f, al], check=True)
        subprocess.run([os.path.join(BT, 'apksigner'), 'sign', '--key', os.path.join(a.keys, 'platform.pk8'), '--cert', os.path.join(a.keys, 'platform.x509.pem'), '--out', f, al], check=True)
        os.remove(al); os.remove(f + '.idsig') if os.path.exists(f + '.idsig') else None
        print('staged', part, dst, os.path.getsize(f) >> 20, 'MB')
finally:
    shutil.rmtree(tmp, ignore_errors=True)
