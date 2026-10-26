#!/bin/bash
# usage: flashboot.sh KERNEL_RUN_ID [dtb]  -> download the CI kernel, repack it onto the stock boot.img, flash `boot`, reboot.
# Only `boot` is written. Recovery (TWRP) is never touched. Needs the stock boot.img at $STOCK (backup first!).
set -e
RUN=$1; DTB=${2:--}; STOCK=${STOCK:?set STOCK to the stock boot.img}; REPO=${KERNEL_REPO:-manvindar/tb-x606v-kernel}
FB=${FB:-~/Library/Android/sdk/platform-tools/fastboot}; ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
D=$(mktemp -d); cd $D; gh run download $RUN -R $REPO -n kernel >/dev/null
python3 -I "${REPACK:?set REPACK to repack_boot.py from the kernel repo}" "$STOCK" Image.gz-dtb boot.img - "$DTB"
if $ADB devices | grep -q recovery; then $ADB reboot bootloader; fi
for i in $(seq 1 25); do $FB devices | grep -q fastboot && break; sleep 3; done
$FB flash boot boot.img; $FB reboot
