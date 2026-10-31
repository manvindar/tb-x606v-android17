#!/system/bin/sh
# Boot diagnostics (builder option --dev-tools): logcat of the whole boot into /data/local/tmp/bootdiag (readable from TWRP), the netbpfload tracer log, process list and properties.
D=/data/local/tmp/bootdiag; mkdir -p $D; chmod 777 $D; rm -f $D/logcat.txt*
logcat -b all -v threadtime -f $D/logcat.txt -r 16384 -n 6 &
( while :; do cp /dev/bpftrace.log /cache/bootdiag/bpftrace.log 2>/dev/null; sleep 5; done ) &
while :; do ps -A -o PID,PPID,NAME > $D/ps.txt 2>/dev/null; getprop > $D/props.txt 2>/dev/null; sleep 10; done
