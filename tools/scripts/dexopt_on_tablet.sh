#!/bin/bash
# Run on the computer with the booted tablet connected over adb: compiles the main system apps (and Google's, in verify mode) on the tablet and waits until it is done (about 10 to 15 minutes).
# Only needed for images that are not precompiled, or for apps you installed later. Usage: tools/scripts/dexopt_on_tablet.sh
set -e
ADB=${ADB:-adb}
$ADB root >/dev/null 2>&1 || true; sleep 2
$ADB shell 'mount -o remount,rw /' >/dev/null
$ADB push "$(dirname "$0")/dexopt_all.sh" /data/local/tmp/dexopt_all.sh >/dev/null
$ADB shell 'rm -f /data/local/tmp/dexopt.log; nohup sh /data/local/tmp/dexopt_all.sh > /data/local/tmp/dexopt.log 2>&1 &'
echo "compiling on the tablet..."
until $ADB shell 'tail -1 /data/local/tmp/dexopt.log' 2>/dev/null | grep -q DONE; do sleep 15; done
echo "done"
