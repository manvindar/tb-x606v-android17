#!/bin/sh
# Runs the per app compile block of device/tablet_tunerd.sh against stubs of cmd, pm, am and dumpsys (sh, no device needed).
# Usage: sh tests/test_apps.sh   (from rom-tuner/)
D=$(cd "$(dirname "$0")/.." && pwd); T=$(mktemp -d); fail=0
ok() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got [$2] want [$3]"; fail=1; fi; }
log() { echo "$*" >> $T/log; }
load() { :; }
publish() { { cat $PST 2>/dev/null; cut -d' ' -f2 $QF 2>/dev/null | tr '\n' ,; echo; } >> $T/pub; pubprog idle | tr '\n' ' ' >> $T/pubp; echo >> $T/pubp; }
oneof() { v_=$1; shift; for x_ in "$@"; do [ "$v_" = "$x_" ] && return 0; done; return 1; }
cstat() { cat $T/cstat 2>/dev/null || echo idle; }
APP=$T/app; mkdir -p $APP; LOCK=$T/lock
sleep() { command sleep 0.05; }
rm() { case "$*" in */data/dalvik-cache/*) echo "$*" >> $T/rm;; *) command rm "$@";; esac; }
pm() { case "$2" in *missing*) return 1;; packages) printf 'package:a\npackage:b\npackage:c\n'; return;; esac; echo "package:/system/app/X/X.apk"; }
am() { echo "$*" >> $T/am; }
dumpsys() { :; }
cmd() {   # package compile ... | package art dump [pkg]
  case "$2" in
    compile) echo "$*" >> $T/compile; command sleep 0.3; case "$*" in *bad.app*) echo "Failure"; return 1;; esac; echo Success;;
    art) cat <<'DUMP'
[com.example.one]
  path: /data/app/~~x/com.example.one-y/base.apk
    arm64: [status=speed-profile] [reason=bg-dexopt] [primary-abi]
      [location is /data/app/~~x/com.example.one-y/oat/arm64/base.odex]
  path: /data/app/~~x/com.example.one-y/split_config.apk
    arm64: [status=verify] [reason=install] [primary-abi]
[com.example.two]
  path: /system/app/Two/Two.apk
    arm64: [status=verify] [reason=first-boot] [primary-abi]
      [location is /data/dalvik-cache/arm64/system@app@Two@Two.apk@classes.vdex]
[com.example.nocode]
  path: /system/app/N/N.apk
[com.example.three]
    arm64: [status=run-from-apk] [reason=unknown] [primary-abi]
DUMP
    ;;
  esac
}
stat() { echo $((${STAT_END:-0} + 0)); }   # only used as "stat -c %Y <log>"
eval "$(sed -n '/^# ---------- app compile/,/^# ---------- state ----------/p' $D/device/tablet_tunerd.sh)"
cstat() { cat $T/cstat 2>/dev/null || echo idle; }
QF=$T/q; QL=$T/ql; RL=$T/rl; PST=$T/pst; AFD=$T/afd; AFAIL=$T/afail; QC=$T/qc
DXS=$T/dexopt_all.sh; DXL=$T/dexopt.log; DXP=$T/dexopt.pid; DXT=$T/dexopt.total; DXC=$T/dexopt.prog; S=$T/state

ok "parse: first status per package, no code skipped" "$(cmd package art dump | dexstates | tr '\n' ' ')" "com.example.one=speed-profile com.example.two=verify com.example.three=run-from-apk "
ok "parse: indented header (dumpsys form)" "$(cmd package art dump | sed 's/^/  /' | dexstates | head -1)" "com.example.one=speed-profile"
ok "parse: 64-bit status wins over the 32-bit one" "$(printf '[com.w]\n  path: /a.apk\n    arm: [status=verify] [reason=first-boot]\n    arm64: [status=speed] [reason=cmdline]\n' | dexstates)" "com.w=speed"
ok "parse: 32-bit only" "$(printf '[com.o]\n  path: /a.apk\n    arm: [status=speed]\n' | dexstates)" "com.o=speed"
ok "parse: secondary dex ignored" "$(printf '[com.s]\n  path: /a.apk\n  known secondary dex files:\n    /x/pcam.jar\n      arm64: [status=verify]\n[com.t]\n  path: /b.apk\n    arm64: [status=speed]\n' | dexstates | tr '\n' ' ')" "com.t=speed "
eval "$(sed -n '/^valid() {/,/^}/p' $D/device/tablet_tunerd.sh)"
for d in 160 192 reset 400; do valid density $d && r=yes || r=no; ok "density accepted [$d]" $r yes; done
for d in 192\;reboot 99 401 '$(reboot)' '' 19.2; do valid density "$d" && r=yes || r=no; ok "density rejected [$d]" $r no; done
ok "parse: empty input" "$(echo 'Unknown command: art' | dexstates)" ""
pkgok com.example.one && r=yes || r=no; ok "name ok" $r yes
for n in com.x\;reboot -rf a com..x .com.x com.x. 1abc.def 'a b.c' ''; do pkgok "$n" && r=yes || r=no; ok "name rejected [$n]" $r no; done

: > $APP/apps_state
apps_refresh
ok "full refresh file" "$(tr '\n' ' ' < $AFD | sed 's/apps_state_time=[0-9]* $//')" "com.example.one=speed-profile com.example.two=verify com.example.three=run-from-apk "
ok "last line is the time marker" "$(tail -1 $AFD | cut -d= -f1)" apps_state_time
ok "copy for the app" "$(cmp -s $AFD $APP/apps_state && echo same)" same
ok "refresh lock released" "$([ -d $RL ] && echo held || echo free)" free

pkg_enqueue speed com.example.two; pkg_enqueue speed-profile com.example.one; pkg_enqueue speed-profile com.example.one
pkg_enqueue speed bad.app; pkg_enqueue reset com.example.missing
command sleep 3
ok "jobs ran in order, duplicate and missing skipped" "$(tr '\n' ';' < $T/compile)" "package compile -m speed -f com.example.two;package compile -m speed-profile -f com.example.one;package compile -m speed -f bad.app;"
ok "failure is reported" "$(cat $PST)" "bad.app:failed"
ok "force-stop only after a good compile" "$(tr '\n' ';' < $T/am)" "force-stop com.example.two;force-stop com.example.one;"
ok "queue empty, worker flag cleared" "$([ -s $QF ] && echo left || echo empty)/$([ -d $QL ] && echo flag || echo clear)" empty/clear
echo running > $T/cstat; pkg_enqueue speed com.example.two
ok "refused while a system compile runs" "$(cat $PST)/$(wc -l < $T/compile | tr -d ' ')" "com.example.two:failed/3"
ok "queue progress while the third job runs" "$(grep 'current=bad.app' $T/pubp | head -1)" "compile_current=bad.app compile_current_state=compiling compile_done=2 compile_total=3 compile_failed=0 compile_started=$(sed -n 's/.*compile_started=\([0-9]*\).*/\1/p' $T/pubp | head -1) "
ok "queue progress cleared when the worker ends" "$(tail -n 1 $T/pubp)" ""
ok "queue counters file removed" "$([ -f $QC ] && echo left || echo gone)" gone
# ---- bulk run progress: dxprog parses $DXL into C_CUR C_KIND C_DONE C_FAIL C_START C_END ----
prog() { command rm -f $DXC; dxprog; echo "$C_CUR/$C_KIND/$C_DONE/$C_FAIL/$C_START/$C_END"; }
: > $DXL
ok "empty log" "$(prog)" "//0/0//"
command rm -f $DXL
ok "no log" "$(prog)" "//0/0//"
printf '=== run 1000 ===\ncom.android.systemui\nSuccess\ncom.android.settings\nFailure\ncom.google.android.gms\n' > $DXL
ok "explicit list: started one has no result yet" "$(prog)" "com.google.android.gms/compiling/2/1/1000/"
printf 'com.google.android.gms\nSuccess\ncom.android.vending\nError: not installed\n' >> $DXL
ok "error line is not a result, the package stays current" "$(prog)" "com.android.vending/compiling/3/1/1000/"
printf '[com.android.systemui] Success\n[com.x.y] Success\n[com.z.w] Failure\n' >> $DXL
ok "[pkg] form: lists' packages not counted twice" "$(prog)" "com.z.w/finished/5/2/1000/"
printf 'DONE\n' >> $DXL
STAT_END=1305
ok "DONE sets the end time" "$(prog)" "com.z.w/finished/5/2/1000/1305"
ok "summary after the run" "$(pubprog done)" "compile_summary=5 done, 2 failed in 5:05"
printf '[com.q.r] Succ' >> $DXL
ok "partial last line is ignored" "$(prog)" "com.z.w/finished/5/2/1000/1305"
printf '=== run 1000 ===\ncom.a.b\nSuccess\ncom.goo' > $DXL
ok "partial package name is ignored" "$(prog)" "com.a.b/finished/1/0/1000/"
printf 'com.old.app\nSuccess\n[com.old.two] Failure\nDONE\n=== run 2000 ===\ncom.new.app\n' > $DXL
ok "older run before the marker is ignored" "$(prog)" "com.new.app/compiling/0/0/2000/"
printf 'com.old.app\nSuccess\ncom.old.b\n' > $DXL
ok "no marker: the whole log counts, no start time" "$(prog)" "com.old.b/compiling/1/0//"
printf '=== run 1 ===\nSuccess\ncom.a.b\nSuccess\n' > $DXL
ok "result without a package is ignored" "$(prog)" "com.a.b/finished/1/0/1/"
# the cache: same size = no parse, a bigger log = parse again
printf '=== run 5 ===\ncom.a.b\n' > $DXL; dxprog
sed -i.bak 's/^C_CUR=.*/C_CUR=cached.pkg/' $DXC; dxprog
ok "unchanged log uses the cache" "$C_CUR" cached.pkg
printf 'Success\n' >> $DXL; dxprog
ok "grown log is parsed again" "$C_CUR/$C_DONE" "com.a.b/1"
# keys while running: total from the start-time count, clamped up to done, 0 = not counted yet
printf '=== run 7 ===\ncom.a.b\nSuccess\ncom.c.d\n' > $DXL; command rm -f $DXC; echo 40 > $DXT
ok "running keys" "$(pubprog running | tr '\n' ' ')" "compile_current=com.c.d compile_current_state=compiling compile_done=1 compile_total=40 compile_failed=0 compile_started=7 "
echo 1 > $DXT; command rm -f $DXC
ok "total never below done" "$(pubprog running | sed -n 4p)" "compile_total=1"
printf '[com.e.f] Success\n' >> $DXL; command rm -f $DXC
ok "total clamped to done" "$(pubprog running | sed -n 4p)" "compile_total=2"
command rm -f $DXT
ok "total unknown before the count is taken" "$(pubprog running | sed -n 4p)" "compile_total=0"
ok "idle: no keys" "$(pubprog idle)" ""
ok "done without DONE line: no summary" "$(pubprog done)" ""
# per app queue keys
mkdir $QL; echo "1 0 500" > $QC; echo "com.b.b:running" > $PST; printf 'speed com.q.one\nreset com.q.two\n' > $QF
ok "queue keys" "$(pubprog idle | tr '\n' ' ')" "compile_current=com.b.b compile_current_state=compiling compile_done=1 compile_total=4 compile_failed=0 compile_started=500 "
echo "com.b.b:done" > $PST
ok "queue between jobs: no current" "$(pubprog idle | sed -n 1,2p | tr '\n' ' ')" "compile_current= compile_current_state= "
rmdir $QL; command rm -f $QC $PST $QF
# compile(): marker first, the script's output after it, the package count taken in the background
cat > $DXS <<'EOS'
echo com.android.systemui; echo Success; echo '[com.x.y] Failure'; echo DONE
EOS
echo idle > $T/cstat; echo stale > $DXL; echo 99 > $DXT; echo old > $DXC
compile ""
command sleep 1
ok "compile: marker is the first line" "$(sed -n 1p $DXL | sed 's/[0-9]\{9,\}/EPOCH/')" "=== run EPOCH ==="
ok "compile: script output follows, stale text gone" "$(sed 1d $DXL | tr '\n' ' ')" "com.android.systemui Success [com.x.y] Failure DONE "
ok "compile: package count stored" "$(cat $DXT)" 3
ok "compile: old cache dropped" "$([ -f $DXC ] && echo kept || echo gone)" gone
ok "compile: the log parses to this run" "$(prog | cut -d/ -f1-4)" "com.x.y/finished/2/1"
echo running > $T/cstat; : > $T/log; compile -f
ok "compile: refused while running" "$(cat $T/log)" "compile already running"
echo idle > $T/cstat
# light publish: only compile_* lines change, and only when the keys changed
printf 'preset=smooth\ncompile_status=idle\ncompile_done=9\napps_state_time=5\nstatus=x\n' > $APP/state
printf '=== run 7 ===\ncom.c.d\n' > $DXL; command rm -f $DXC; echo 40 > $DXT; echo running > $T/cstat; echo $$ > $DXP
prog_pub
ok "light publish replaces compile keys, keeps the rest" "$(cat $APP/state | tr '\n' ' ')" "preset=smooth apps_state_time=5 status=x compile_status=running compile_pkg_status= compile_queue= compile_current=com.c.d compile_current_state=compiling compile_done=0 compile_total=40 compile_failed=0 compile_started=7 "
echo compile_zz=1 >> $APP/state; prog_pub
ok "unchanged keys: no rewrite" "$(tail -n 1 $APP/state)" compile_zz=1
printf 'Success\n' >> $DXL; prog_pub
ok "changed keys: rewrite" "$(grep -c compile_zz= $APP/state)/$(grep compile_done= $APP/state)" "0/compile_done=1"
echo compile_zz=1 >> $APP/state; echo 999999 > $DXP; echo done > $T/cstat; printf 'DONE\n' >> $DXL; STAT_END=17
prog_pub
ok "after the run: one more publish with the summary" "$(grep -c compile_zz= $APP/state)/$(grep -e compile_status= -e compile_summary= -e compile_current= $APP/state | tr '\n' ' ')" "0/compile_status=done compile_summary=1 done, 0 failed in 0:10 "
echo compile_zz=1 >> $APP/state; prog_pub
ok "then nothing while idle" "$(tail -n 1 $APP/state)" compile_zz=1
mkdir $LOCK; printf 'x\n' >> $DXL; echo $$ > $DXP; echo running > $T/cstat; prog_pub
ok "lock held: skipped" "$(tail -n 1 $APP/state)" compile_zz=1
rmdir $LOCK; echo idle > $T/cstat
# the app side parsing (Prog.java has no Android classes)
if command -v javac > /dev/null; then
  J=$T/jvm; mkdir -p $J
  javac -d $J $D/src/org/tablet/tuner/Prog.java $D/tests/ProgTest.java 2> $T/javac.log || { cat $T/javac.log; fail=1; }
  java -cp $J org.tablet.tuner.ProgTest || fail=1
  javac -d $J $D/src/org/tablet/tuner/Layouts.java $D/tests/LayoutTest.java 2> $T/javac2.log || { cat $T/javac2.log; fail=1; }
  java -cp $J org.tablet.tuner.LayoutTest || fail=1
  javac -d $J $D/src/org/tablet/tuner/Profiles.java $D/tests/ProfilesTest.java 2> $T/javac3.log || { cat $T/javac3.log; fail=1; }
  java -cp $J org.tablet.tuner.ProfilesTest || fail=1
  javac -d $J $D/src/org/tablet/tuner/Search.java $D/tests/SearchTest.java 2> $T/javac4.log || { cat $T/javac4.log; fail=1; }
  java -cp $J org.tablet.tuner.SearchTest || fail=1
  javac -d $J $D/src/org/tablet/tuner/Hist.java $D/tests/HistTest.java 2> $T/javac5.log || { cat $T/javac5.log; fail=1; }
  java -cp $J org.tablet.tuner.HistTest || fail=1
  javac -d $J $D/src/org/tablet/tuner/Pending.java $D/src/org/tablet/tuner/PicMath.java $D/tests/PendingTest.java 2> $T/javac6.log || { cat $T/javac6.log; fail=1; }
  java -cp $J org.tablet.tuner.PendingTest || fail=1
else echo "skip ProgTest, LayoutTest, ProfilesTest, SearchTest, HistTest and PendingTest (no javac)"; fi
command rm -rf $T
[ $fail = 0 ] && echo "all passed"; exit $fail
