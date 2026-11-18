#!/bin/bash
# HOST-ONLY. Applies 0001-bootanim-keep-root-ro.patch to the pinned tablet_tunerd.sh, extracts bootanim()/rootmode() and drives them with stub mount/cp.
# usage: test_bootanim.sh PATH/TO/pinned/tablet_tunerd.sh   (sha 8eb2114b...)
set -u; H=$(cd "$(dirname "$0")" && pwd); W=$(mktemp -d); fails=0
[ "$(shasum -a 256 "$1" | cut -c1-64)" = 8eb2114bbfc6f9eabc5dedb32f31b90da858e3c480aa9fd7ceb09b014a43a0e3 ] || { echo "FAIL pre-patch pin"; exit 1; }
mkdir -p $W/rom-tuner/device; cp "$1" $W/rom-tuner/device/; (cd $W && patch -s -p1 < $H/0001-bootanim-keep-root-ro.patch) || { echo "FAIL patch does not apply"; exit 1; }
[ "$(shasum -a 256 $W/rom-tuner/device/tablet_tunerd.sh | cut -c1-64)" = 6fa4c31952a8410d418eca5cf8dc6e752e1c281a6396752e66ba562b0600fc50 ] || { echo "FAIL post-patch pin"; exit 1; }
sh -n $W/rom-tuner/device/tablet_tunerd.sh || { echo "FAIL syntax"; exit 1; }
grep -c 'remount,rw /' $W/rom-tuner/device/tablet_tunerd.sh | grep -qx 1 || { echo "FAIL expected exactly one remount,rw"; exit 1; }
{ grep '^rootmode() ' $W/rom-tuner/device/tablet_tunerd.sh; sed -n '/^bootanim() /,/^}/p' $W/rom-tuner/device/tablet_tunerd.sh; } > $W/fn.sh
t() { # name, was(ro|rw), same(0|1), rw_ok(0|1), ro_sticks(0|1), expect_calls, expect_log_substr
  : > $W/calls; : > $W/logf; mode=$2; same=$3; rwok=$4; rosticks=$5
  out=$( ( M=$W/m; mkdir -p $M; echo a > $M/bootanimation-x.zip; [ $same = 1 ] && cp $M/bootanimation-x.zip $M/bootanimation.zip || echo b > $M/bootanimation.zip
    bootanim=x; log() { echo "$*" >> $W/logf; }; restorecon() { :; }; sync() { :; }
    mount() { echo "mount $*" >> $W/calls; if [ "$2" = remount,rw ]; then [ $rwok = 1 ] && mode=rw || return 1; elif [ "$2" = remount,ro ]; then [ $rosticks = 1 ] && mode=ro; fi; return 0; }
    . $W/fn.sh; rootmode() { echo $mode; }; bootanim; echo "rc=$?" ) 2>&1 )
  calls=$(tr '\n' ';' < $W/calls); ok=1
  [ "$calls" = "$6" ] || { echo "  calls '$calls' want '$6'"; ok=0; }
  [ -z "${7:-}" ] || grep -q "$7" $W/logf || { echo "  log missing '$7'"; ok=0; }
  if [ $ok = 1 ]; then echo "ok   $1"; else echo "FAIL $1 ($out)"; fails=$((fails+1)); fi
}
t unchanged-no-mount-touch  ro 1 1 1 ""
t was-ro-copy-and-restore   ro 0 1 1 "mount -o remount,rw /;mount -o remount,ro /;"
t was-rw-left-rw            rw 0 1 1 ""
t remount-rw-fails-no-copy  ro 0 0 1 "mount -o remount,rw /;" "remount rw failed"
t ro-does-not-stick-logged  ro 0 1 0 "mount -o remount,rw /;mount -o remount,ro /;" "still rw"
rm -rf $W; echo "$fails failing"; [ $fails = 0 ]
