#!/bin/sh
# Runs the overclock block of device/tablet_tunerd.sh (crash guard, thermal guard, 30 s delay, 3 minute marker) against fake /proc nodes (sh, no device).
# Usage: sh tests/test_oc.sh   (from rom-tuner/)
D=$(cd "$(dirname "$0")/.." && pwd); T=$(mktemp -d); fail=0
ok() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got [$2] want [$3]"; fail=1; fi; }
log() { echo "$*" >> $T/log; }
S=$T/state; LOCK=$T/lock; PF=$T/pf
lock_wait() { :; }
rmdir() { :; }
sleep() { :; }
sync() { :; }
publish() { :; }
calc() { gpu_eff=0; }
w() { echo "$2" > "$1"; }
wn() { [ -w "$1" ] && w "$1" "$2"; }
eval "$(sed -n '/^oneof() /p;/^valid() /,/^}/p;/^sane() /p;/^putkey() /p;/^load() {/,/^}/p' $D/device/tablet_tunerd.sh)"
profile() { :; }
eval "$(sed -n '/^# ---------- overclock/,/^# ---------- end overclock/p' $D/device/tablet_tunerd.sh | sed "s#/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies#$T/f0#g")"
OCM=$T/oc_pending; OCG=$T/gpu; OCE=$T/eem; OCT=$T/thermal; OCB=$T/boot_id
mkdir -p $OCG $OCT/thermal_zone0 $OCT/thermal_zone1
for b in L L_HI 2L 2L_HI; do mkdir -p $OCE/EEM_DET_$b; echo 0 > $OCE/EEM_DET_$b/eem_offset; done
echo boot-A > $OCB; echo 40000 > $OCT/thermal_zone0/temp; echo -127000 > $OCT/thermal_zone1/temp
echo "[0] freq = 680000, volt = 80000, vsram = 87500, gpufreq_power = 746" > $OCG/gpufreq_opp_dump
gpu() { echo "real freq = $1, real volt = 0, real vsram_volt = 0" > $OCG/gpufreq_var_dump; }
: > $OCG/gpufreq_fixed_freq_volt; : > $OCG/gpufreq_opp_freq
st() { sed -n "s/^$1=//p" $S; }
fresh() { printf 'oc_master=%s\noc_cpu_uv=%s\noc_gpu=%s\noc_status=off\ngpu_freq=680000\n' $1 $2 $3 > $S; rm -f $OCM; OC_ON=0; OC_CUR=; load; }

# validation: only the list reaches a node
valid oc_gpu 730000 && a=y || a=n; ok "valid 730000" $a y
valid oc_gpu 900000 && a=y || a=n; ok "reject 900000" $a n
valid oc_gpu 940000 && a=y || a=n; ok "accept 940000" $a y
valid oc_gpu 960000 && a=y || a=n; ok "reject 960000 (kernel panic on the tablet)" $a n
valid oc_gpu 1 && a=y || a=n; ok "reject 1" $a n
valid oc_cpu_uv 3 && a=y || a=n; ok "reject uv 3" $a n
valid oc_cpu_uv 4 && a=y || a=n; ok "valid uv 4" $a y
valid oc_master 2 && a=y || a=n; ok "reject master 2" $a n

# crash guard: marker from another boot resets the keys and publishes reverted_after_crash
fresh 1 2 730000; printf 'boot-OLD\n1\n' > $OCM; oc_boot; load
ok "crash: marker removed" "$([ -f $OCM ] && echo y || echo n)" n
ok "crash: master" "$oc_master" 0; ok "crash: gpu" "$oc_gpu" 0; ok "crash: uv" "$oc_cpu_uv" 0
ok "crash: status" "$oc_status" reverted_after_crash
fresh 1 2 730000; printf 'boot-A\n1\n' > $OCM; oc_boot; load
ok "same boot: marker kept" "$([ -f $OCM ] && echo y || echo n)" y; ok "same boot: master kept" "$oc_master" 1

# 30 s delay: nothing is applied before it, then marker + nodes + testing
fresh 1 2 730000; DSTART=$(date +%s); oc_watch; load
ok "delay: waiting" "$oc_status" waiting; ok "delay: no marker" "$([ -f $OCM ] && echo y || echo n)" n
DSTART=$(( $(date +%s) - 60 )); gpu 730000; oc_watch; load
ok "apply: testing" "$oc_status" testing; ok "apply: marker" "$([ -f $OCM ] && echo y || echo n)" y
ok "apply: eem offset" "$(cat $OCE/EEM_DET_2L/eem_offset)" -2
ok "apply: gpu write" "$(cat $OCG/gpufreq_fixed_freq_volt)" "730000 80000"

# 3 minutes stable: marker goes, status active
OC_T0=$(( $(date +%s) - 200 )); oc_watch; load
ok "stable: active" "$oc_status" active; ok "stable: marker gone" "$([ -f $OCM ] && echo y || echo n)" n

# thermal guard: above 85 C drops to stock and keeps the master off
echo 90000 > $OCT/thermal_zone0/temp; oc_watch; load
ok "thermal: status" "$oc_status" thermal_revert; ok "thermal: master off" "$oc_master" 0
ok "thermal: offset 0" "$(cat $OCE/EEM_DET_L/eem_offset)" 0; ok "thermal: gpu stock" "$(cat $OCG/gpufreq_fixed_freq_volt)" "0 0"
echo 40000 > $OCT/thermal_zone0/temp

# the PLL does not take the value: reverted, keys reset
fresh 1 0 760000; DSTART=$(( $(date +%s) - 60 )); gpu 680000; oc_watch; load
ok "gpu_failed: status" "$oc_status" gpu_failed; ok "gpu_failed: gpu key" "$oc_gpu" 0; ok "gpu_failed: marker" "$([ -f $OCM ] && echo y || echo n)" n

# unknown OPP table: refuses
fresh 1 0 730000; echo "[0] freq = 730000, volt = 80000, vsram = 87500" > $OCG/gpufreq_opp_dump; DSTART=$(( $(date +%s) - 60 )); gpu 730000; oc_watch; load
ok "unsupported" "$oc_status" unsupported
echo "[0] freq = 680000, volt = 80000, vsram = 87500, gpufreq_power = 746" > $OCG/gpufreq_opp_dump

# switching the master off while on: back to stock
fresh 1 1 0; DSTART=$(( $(date +%s) - 60 )); oc_watch; putkey oc_master 0; oc_watch; load
ok "off: status" "$oc_status" off; ok "off: offset" "$(cat $OCE/EEM_DET_L/eem_offset)" 0; ok "off: marker" "$([ -f $OCM ] && echo y || echo n)" n

# CPU overclock: the overclock kernel's table has the 2.50 GHz and 2.38 GHz steps; the fast cores stay at the stock top step until it is applied
eval "$(sed -n '/^calc() {/,/^}/p' $D/device/tablet_tunerd.sh | sed "s/^calc()/rcalc()/; s#/sys/devices/system/cpu/cpu\([04]\)/cpufreq/scaling_available_frequencies#$T/f\1#g")"
profile() { rcalc; echo "$capb" > $T/capb; }
perf_profile=balanced; oc=0; fg_boost=auto; gpu_freq=680000; cap_big=-1; cap_little=-1
printf '2501000 2383000 2280000 2191000\n' > $T/f0; printf '1800000 1682000\n' > $T/f4
valid oc_cpu 2501000 && a=y || a=n; ok "accept oc_cpu 2501000" $a y
valid oc_cpu 2400000 && a=y || a=n; ok "reject oc_cpu 2400000 (not a step)" $a n
fresh 0 0 0; profile; ok "cpu: stock cap on the overclock kernel" "$(cat $T/capb)" 2301000
fresh 1 0 0; putkey oc_cpu 2501000; load; DSTART=$(( $(date +%s) - 60 )); oc_watch; load
ok "cpu: testing" "$oc_status" testing; ok "cpu: cap lifted to 2.50 GHz" "$(cat $T/capb)" 2501000
OC_T0=$(( $(date +%s) - 200 )); oc_watch; load
ok "cpu: active" "$oc_status" active; ok "cpu: cap stays" "$(cat $T/capb)" 2501000
putkey oc_cpu 2383000; load; oc_watch; load
ok "cpu: another step" "$(cat $T/capb)" 2383000
echo 90000 > $OCT/thermal_zone0/temp; oc_watch; load
ok "cpu: thermal revert drops the cap" "$(cat $T/capb)" 2301000; echo 40000 > $OCT/thermal_zone0/temp
fresh 1 0 0; putkey oc_cpu 2501000; load; DSTART=$(( $(date +%s) - 60 )); oc_watch; load; putkey oc_master 0; oc_watch; load
ok "cpu: master off drops the cap" "$(cat $T/capb)" 2301000; ok "cpu: status off" "$oc_status" off
fresh 1 0 0; printf '2301000 2215000\n' > $T/f0; putkey oc_cpu 2501000; load; DSTART=$(( $(date +%s) - 60 )); oc_watch; load
ok "cpu: stock kernel refuses" "$oc_status" cpu_failed; ok "cpu: key reset" "$oc_cpu" 0
profile; ok "cpu: stock kernel, no cap" "$(cat $T/capb)" -1
printf '2501000 2383000 2280000 2191000\n' > $T/f0

rm -rf $T; [ $fail = 0 ] && echo "all ok"; exit $fail

# saved profiles keep the overclock keys
grep -q "^PKEYS=.*oc_master oc_cpu_uv oc_gpu" device/tablet_tunerd.sh && echo "ok profiles include oc keys" || { echo "FAIL profiles miss oc keys"; exit 1; }
