#!/bin/bash
# Applies every vendor-partition change Android 17 needs on the TB-X606V's stock Android 10 vendor. Run once with the tablet in TWRP (Volume Up + Volume Down) and adb connected.
# Originals are kept next to the edited files (*.orig-a17 / *.orig-mp2 / *.orig-vp9). Nothing from the vendor image is stored in this repository.
#   1. fstab.mt6765 / fstab.mt8768: legacy flags Android 17 rejects, add /metadata        (tools/fix_vendor_a17.py)
#   2. /vendor/etc/ueventd.rc with /dev/ion 0666                                           (tools/vendor/make_vendor_ueventd.sh)
#   3. seccomp policies: syscalls the Android 17 media and configstore code uses            (tools/vendor/seccomp_add.sh)
#   4. media_codecs_mediatek_audio.xml without OMX.MTK.AUDIO.DECODER.MP2 (system_server aborts on audio/mpeg-L2)
#   5. media_codecs_mediatek_video.xml without the software-only OMX.MTK.VIDEO.DECODER.VP9 (YouTube then uses hardware H.264)
#   6. AIDL lights HAL declaration for the brightness shim (/vendor/etc/vintf/manifest/android.hardware.light-aidl.xml)
set -e
ADB=${ADB:-$(command -v adb || echo $HOME/Library/Android/sdk/platform-tools/adb)}; D=$(cd "$(dirname "$0")/.." && pwd)
$ADB devices | grep -q recovery || { echo "put the tablet in TWRP first"; exit 1; }
python3 $D/fix_vendor_a17.py "$ADB"
$ADB shell 'mkdir -p /mnt/v2; umount /mnt/v2 2>/dev/null; mount -t ext4 -o rw /dev/block/by-name/vendor /mnt/v2'
$ADB push $D/vendor/make_vendor_ueventd.sh /tmp/mk_ueventd.sh >/dev/null; $ADB shell 'sh /tmp/mk_ueventd.sh'
$ADB push $D/vendor/seccomp_add.sh /tmp/seccomp_add.sh >/dev/null; $ADB shell 'sh /tmp/seccomp_add.sh'
ADB=$ADB python3 $D/vendor/fix_vendor_codecs_mp2.py
python3 - "$ADB" <<'PY'
import re, subprocess, sys, tempfile
adb = sys.argv[1]; sh = lambda c: subprocess.run([adb, 'shell', c], capture_output=True, text=True).stdout
sh('mkdir -p /mnt/v; umount /mnt/v 2>/dev/null; mount -t ext4 -o rw /dev/block/by-name/vendor /mnt/v')
p = '/mnt/v/etc/media_codecs_mediatek_video.xml'; t = sh('cat ' + p)
n, k = re.subn(r'\n\s*<MediaCodec name="OMX\.MTK\.VIDEO\.DECODER\.VP9"[^>]*>.*?</MediaCodec>\n', '\n', t, flags=re.S)
if k:
    f = tempfile.NamedTemporaryFile('w', suffix='.xml', delete=False); f.write(n); f.close()
    subprocess.run([adb, 'push', f.name, '/tmp/video.xml'], capture_output=True)
    sh(f'cp {p} {p}.orig-vp9; cat /tmp/video.xml > {p}; sync'); print('removed VP9 decoder entry')
else: print('VP9 entry not present')
sh('umount /mnt/v')
PY
$ADB push $D/lights/vintf_light_aidl.xml /tmp/light.xml >/dev/null
$ADB shell 'mount -t ext4 -o rw /dev/block/by-name/vendor /mnt/v2 2>/dev/null; mkdir -p /mnt/v2/etc/vintf/manifest; cat /tmp/light.xml > /mnt/v2/etc/vintf/manifest/android.hardware.light-aidl.xml; chmod 644 /mnt/v2/etc/vintf/manifest/android.hardware.light-aidl.xml; chcon u:object_r:vendor_configs_file:s0 /mnt/v2/etc/vintf/manifest/android.hardware.light-aidl.xml; sync; umount /mnt/v2'
echo "vendor ready"
