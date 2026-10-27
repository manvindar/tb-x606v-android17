#!/bin/bash
# Install the patched Pixel Launcher on a running tablet (adb root, no wipe) and make it the home app. Needs the APK from build_pixel_launcher.sh.
#   install_pixel.sh PixelLauncher-a17-tb.apk path/to/NikGapps-full-arm64-16-*.zip
# From the NikGapps zip it takes only the permission/sysconfig files; the Android 16 app inside is NOT used.
# The extra privapp whitelist (privapp-permissions-nexuslauncher-a17.xml) is REQUIRED: without it system_server aborts at boot
# ("Signature|privileged permissions not in privileged permission allowlist") and the tablet hangs on the boot animation (fix from TWRP: delete the apk).
set -e
D=$(cd "$(dirname "$0")" && pwd); ADB=${ADB:-adb}; APK=$1; NIK=$2; W=$(mktemp -d)
python3 "$D/stage_pixel.py" "$NIK" $W PixelLauncher >/dev/null
P=/system/product
$ADB root >/dev/null; sleep 3; $ADB shell "mount -o remount,rw /; mkdir -p $P/priv-app/NexusLauncherRelease"
$ADB push "$APK" $P/priv-app/NexusLauncherRelease/NexusLauncherRelease.apk >/dev/null
for f in $W/etc/permissions/*.xml; do $ADB push $f $P/etc/permissions/ >/dev/null; done
$ADB push $W/etc/sysconfig/hiddenapi-whitelist-com.google.android.apps.nexuslauncher.xml $P/etc/sysconfig/ >/dev/null
$ADB push "$D/privapp-permissions-nexuslauncher-a17.xml" $P/etc/permissions/ >/dev/null
$ADB shell "chmod 755 $P/priv-app/NexusLauncherRelease; chmod 644 $P/priv-app/NexusLauncherRelease/*.apk $P/etc/permissions/*nexuslauncher* $P/etc/sysconfig/*nexuslauncher*; chown -R root:root $P/priv-app/NexusLauncherRelease; restorecon -R $P/priv-app/NexusLauncherRelease $P/etc >/dev/null 2>&1; sync"
# no recents overlay: gesture navigation does not work yet (SystemUI AIDL mismatch), the 3-button bar uses AOSP's Launcher3 recents
$ADB reboot; echo "rebooting; then run: $ADB shell cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.nexuslauncher"
rm -rf $W
