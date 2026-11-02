#!/system/bin/sh
# Stability test for one overclock step. Runs ON the tablet, as root, in the background:
#   adb push tests/oc_stress.sh /data/local/tmp/ && adb shell 'nohup sh /data/local/tmp/oc_stress.sh 10 > /dev/null 2>&1 &'
#   adb shell cat /data/local/tmp/oc_stress.log        (or pull it)
# $1 = minutes (default 10). Keep the screen on and the tablet unplugged from heavy loads; start the GPU load (see GPU_URL) yourself if there is no network.
# It only reads nodes and starts CPU load; it never writes to /proc or /sys. Temperatures are millidegrees.
M=${1:-10}; L=/data/local/tmp/oc_stress.log; GPU_URL=${GPU_URL:-https://webglsamples.org/aquarium/aquarium.html}
T=$(( $(date +%s) + M * 60 ))
n0=$(dmesg | wc -l)
{
echo "== start $(date) for $M min; $(grep -E '^(oc_|gpu_freq|oc=)' /data/local/tmp/tuner.state | tr '\n' ' ')"
echo "== eem offsets: $(cat /proc/eem/EEM_DET_L/eem_offset /proc/eem/EEM_DET_2L/eem_offset | tr '\n' ' ')  bank L opp0: $(sed -n 2p /proc/eem/EEM_DET_L/eem_cur_volt)"
} > $L
am start -a android.intent.action.VIEW -d "$GPU_URL" > /dev/null 2>&1   # a WebGL scene for the GPU load; replace by any GPU benchmark
PIDS=
i=0; while [ $i -lt 8 ]; do ( while :; do dd if=/dev/zero bs=1M count=32 2> /dev/null | md5sum > /dev/null; done ) & PIDS="$PIDS $!"; i=$((i + 1)); done
trap 'kill $PIDS 2>/dev/null' EXIT INT TERM
fmax=0; tpk=0
while [ "$(date +%s)" -lt $T ]; do
  c0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq); c4=$(cat /sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq)
  g=$(sed -n 's/^real freq = \([0-9]*\),.*/\1/p' /proc/gpufreq/gpufreq_var_dump)
  t=$(cat /sys/class/thermal/thermal_zone*/temp 2> /dev/null | sort -n | tail -n 1)
  tc=$(cat /sys/class/thermal/thermal_zone3/temp)
  v=$(sed -n 's/^Vproc: \([0-9]*\) uV/\1/p' /proc/cpufreq/MT_CPU_DVFS_L/cpufreq_volt)
  s=$(sed -n 's/^oc_status=//p' /data/local/tmp/tuner.state)
  echo "$(date +%T) cpu0=$c0 cpu4=$c4 gpu=$g vproc=${v}uV cpuT=$tc maxT=$t oc=$s" >> $L
  [ "${t:-0}" -gt $tpk ] && tpk=$t
  sleep 5
done
kill $PIDS 2> /dev/null
{
echo "== end $(date); peak temperature $tpk"
echo "== dmesg lines since start that look like trouble:"
dmesg | tail -n +$((n0 + 1)) | grep -iE 'oops|panic|bug:|watchdog|wdt|serror|hung|rcu.*stall|gpufreq.*(err|fail)|eem.*(err|fail)|dvfs.*(err|fail)|ged.*(err|fail)|pvr.*(err|fail|lost|hang)|thermal.*(shutdown|critical)|mmc.*(error|timeout)|ext4.*error' | head -40
echo "== tuner.log tail:"; tail -n 8 /data/local/tmp/tuner.log
echo "== oc_pending present (means it is still in the 3 minute test or the system just restarted): $([ -f /data/local/tmp/oc_pending ] && echo yes || echo no)"
echo "== PASS if: no trouble lines, oc_status stayed testing/active, gpu= stayed at the set clock, peak temperature under 85000, the tablet never restarted"
} >> $L
