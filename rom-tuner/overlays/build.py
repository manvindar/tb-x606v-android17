"""Builds the font overlays and the pure black overlay of ROM Tuner (no Gradle): python3 overlays/build.py [outdir]

The GSI is signed with the public AOSP platform test key, so overlays signed with it install with a plain `adb install` and need no reboot.
Fetch the key once:  curl -sL 'https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/platform.pk8?format=TEXT' | base64 -d > platform.pk8
                     (the same for platform.x509.pem), then set PLATFORM_KEY and PLATFORM_CERT.
The families named below already ship in /product/etc/fonts_customization.xml of the Pixel build; an overlay only points the font config at them.
The APKs next to this file are the result of running it.
"""
import os, shutil, subprocess, sys

BT = os.path.expanduser('~/Library/Android/sdk/build-tools/35.0.0')
PL = os.path.expanduser('~/Library/Android/sdk/platforms/android-34/android.jar')
KEY, CERT = os.environ['PLATFORM_KEY'], os.environ['PLATFORM_CERT']
out = sys.argv[1] if len(sys.argv) > 1 else 'apks'
os.makedirs(out, exist_ok=True)

FONTS = [  # id, label, body, body medium, headline, headline medium
    ('googlesans', 'Google Sans', 'google-sans-text', 'google-sans-text-medium', 'google-sans', 'google-sans-medium'),
    ('googlesansflex', 'Google Sans Flex', 'google-sans-flex', 'google-sans-flex', 'google-sans-flex', 'google-sans-flex'),
    ('lato', 'Lato', 'lato', 'lato-bold', 'lato', 'lato-bold'),
    ('rubik', 'Rubik', 'rubik', 'rubik-medium', 'rubik', 'rubik-medium'),
    ('barlow', 'Barlow', 'barlow', 'barlow-medium', 'barlow', 'barlow-medium'),
    ('karla', 'Karla', 'karla', 'karla', 'karla', 'karla')]

BLACK = {'system_background_dark': '#FF000000', 'system_surface_dark': '#FF000000', 'system_surface_dim_dark': '#FF000000',
         'system_surface_container_lowest_dark': '#FF000000', 'system_under_surface_dark': '#FF000000',
         'system_overview_background_dark': '#FF000000', 'system_widget_background_dark': '#FF000000',
         'system_surface_container_low_dark': '#FF0B0B0B', 'system_surface_container_dark': '#FF141414',
         'system_surface_container_high_dark': '#FF1E1E1E', 'system_surface_container_highest_dark': '#FF282828',
         'system_surface_bright_dark': '#FF2E2E2E', 'system_surface_variant_dark': '#FF1E1E1E'}


def build(name, pkg, category, label, resources, meta=''):
    d = f'work_{name}'
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(f'{d}/res/values')
    open(f'{d}/AndroidManifest.xml', 'w').write(f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}">
    <overlay android:targetPackage="android" android:category="{category}" android:priority="1"/>
    <application android:label="{label}" android:hasCode="false">{meta}</application>
</manifest>
''')
    open(f'{d}/res/values/values.xml', 'w').write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + resources + '</resources>\n')
    run = subprocess.check_call
    run([f'{BT}/aapt2', 'compile', '--dir', f'{d}/res', '-o', f'{d}/res.zip'])
    run([f'{BT}/aapt2', 'link', '-o', f'{d}/u.apk', '-I', PL, '--manifest', f'{d}/AndroidManifest.xml',
         '--min-sdk-version', '29', '--target-sdk-version', '34', f'{d}/res.zip'])
    run([f'{BT}/zipalign', '-f', '4', f'{d}/u.apk', f'{d}/a.apk'])
    run([f'{BT}/apksigner', 'sign', '--key', KEY, '--cert', CERT, '--out', f'{out}/{name}.apk', f'{d}/a.apk'], stderr=subprocess.DEVNULL)
    shutil.rmtree(d)
    idsig = f'{out}/{name}.apk.idsig'
    if os.path.exists(idsig):
        os.remove(idsig)


for fid, label, b, bm, h, hm in FONTS:
    req = ','.join(dict.fromkeys([b, bm, h, hm]))
    build(f'Font{fid}', f'com.android.theme.font.{fid}', 'android.theme.customization.font', f'@string/font_{fid}_overlay',
          f'    <string name="config_bodyFontFamily" translatable="false">{b}</string>\n'
          f'    <string name="config_bodyFontFamilyMedium" translatable="false">{bm}</string>\n'
          f'    <string name="config_headlineFontFamily" translatable="false">{h}</string>\n'
          f'    <string name="config_headlineFontFamilyMedium" translatable="false">{hm}</string>\n'
          f'    <string name="font_{fid}_overlay" translatable="false">{label}</string>\n',
          f'<meta-data android:name="android.theme.customization.REQUIRED_SYSTEM_FONTS" android:value="{req}"/>')

# the daemon enables this one and sets its priority to highest (it has to be above the dynamic palette overlay)
build('BlackTheme', 'org.lineageos.overlay.customization.blacktheme', 'android.theme.customization.system_palette', 'Pure black',
      ''.join(f'    <color name="{k}">{v}</color>\n' for k, v in BLACK.items()))
print(sorted(f for f in os.listdir(out) if f.endswith('.apk')))
