#!/bin/bash
# Flash the Android 17 system image (raw ext4; unpack the .xz first) and wipe userdata. Never writes recovery or boot.
#   flash_a17.sh system-a17-tb-x606v-gapps.img
# Before the first flash: unlocked bootloader, stock Android 10 firmware on the tablet, and (if the tablet refuses to boot a modified system) verification off:
#   fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img      (the tablet's own vbmeta.img)
# After flashing: boot TWRP (Volume Up + Volume Down) and run apply_vendor_a17.sh once, then reboot to system. The first boot takes several minutes; then run dexopt_all.sh.
set -e
FB=${FASTBOOT:-$HOME/Library/Android/sdk/platform-tools/fastboot}; IMG=${1:?usage: flash_a17.sh system.img}
$FB devices | grep -q fastboot || { echo "tablet not in fastboot (Power + Volume Down from off)"; exit 1; }
$FB flash system "$IMG"
$FB format:ext4 userdata
$FB erase metadata; $FB erase cache
echo "done: now TWRP -> apply_vendor_a17.sh -> reboot"
