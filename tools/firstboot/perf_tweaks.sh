#!/system/bin/sh
# Small MediaTek scheduler and touch boost settings found by reading the 4.9 kernel source (drivers/misc/mediatek/performance, kernel/sched/cpufreq_schedplus.c).
# Waits for ROM Tuner to set the governor first: the schedplus throttles only exist while schedplus is the active governor.
sleep 25
# touch boost: 100 ms at OPP 2 / boost 80 ended before a long fling or a shade pull did; now 400 ms at the top OPP / boost 100
U=/proc/perfmgr/tchbst/user/usrtch
[ -w $U ] && { echo "duration 400000000" > $U; echo "touch_opp 0" > $U; echo "init 100" > $U; }
# no per-wakeup scheduler statistics (small saving, nothing on the tablet reads them)
echo 0 > /proc/sys/kernel/sched_schedstats
# schedplus: keep the clock up 20 ms after load drops (default 4 ms) and ask for 1.5x headroom (default 1.25x)
for p in 0 4; do d=/sys/devices/system/cpu/cpufreq/policy$p/sched; [ -w $d/down_throttle_nsec ] && echo 20000000 > $d/down_throttle_nsec; done
[ -w /sys/devices/system/cpu/eas/cap_margin_dvfs ] && echo 1536 > /sys/devices/system/cpu/eas/cap_margin_dvfs
