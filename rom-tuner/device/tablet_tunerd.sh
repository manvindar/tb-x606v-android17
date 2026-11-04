#!/system/bin/sh
# ROM Tuner root daemon. The app writes "key=value" lines to <app files>/request; this applies them and keeps the
# master state in /data/local/tmp/tuner.state (survives "clear data"). Modes: no args = daemon, args = inotifyd handler.
S=/data/local/tmp/tuner.state
APP=/data/data/org.tablet.tuner/files
LOCK=/data/local/tmp/tuner.lock
M=/system/product/media
BLACK=org.lineageos.overlay.customization.blacktheme
DXS=/system/bin/dexopt_all.sh; DXL=/data/local/tmp/dexopt.log; DXP=/data/local/tmp/dexopt.pid
DXT=/data/local/tmp/dexopt.total; DXC=/data/local/tmp/dexopt.prog   # package count taken at run start; parsed progress of the log (cache)
log() { echo "$(date +%T) $*" >> /data/local/tmp/tuner.log; }

# ---------- app compile (one-shot, not stored state) ----------
cstat() {   # idle, running, done (the script prints DONE last) or missing; the pid is checked against the command line so a reused pid does not count
  p=$(cat $DXP 2>/dev/null)
  if [ -n "$p" ] && tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q dexopt_all; then echo running
  elif [ ! -f $DXS ]; then echo missing
  elif tail -n 1 $DXL 2>/dev/null | grep -q '^DONE'; then echo done
  else echo idle; fi
}
compile() {   # $1 = "" skips apps that are already compiled, -f recompiles all; refuses to start a second run
  [ "$(cstat)" = running ] && { log "compile already running"; return; }
  [ -d $QL ] && { log "app compile running"; return; }
  [ -f $DXS ] || { log "no $DXS"; return; }
  rm -f $DXC $DXT; echo "=== run $(date +%s) ===" > $DXL   # the marker tells this run's lines from an older log; compile_started comes from it
  # compile_total is an estimate: the installed packages, taken once. Apps without code are in it and apps named twice in the script count once.
  ( trap - EXIT; n=$(pm list packages | wc -l); echo $((n + 0)) > $DXT ) < /dev/null > /dev/null 2>&1 &
  DEXOPT_FORCE=$1 nohup sh $DXS >> $DXL 2>&1 < /dev/null &
  echo $! > $DXP; log "compile started ($1)"
}

# ---------- per app compile and the compile state list ----------
# Requests go into a queue file that one worker drains, one compile at a time. The worker is started by the request handler and flagged by the
# directory $QL. $RL is held while the state list is rebuilt. The app reads the list from files/apps_state ("package=filter" lines, the last line is
# apps_state_time=<epoch> so a half written file can be told from a complete one).
QF=/data/local/tmp/tuner.queue; QL=/data/local/tmp/tuner.q.lock; RL=/data/local/tmp/tuner.r.lock
PST=/data/local/tmp/tuner.pkgstat; QC=/data/local/tmp/tuner.qcount; AFD=/data/local/tmp/tuner.apps; AFAIL=/data/local/tmp/tuner.apps.fail
pkgok() { echo "$1" | grep -qE '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'; }
lock_wait() { while ! mkdir $LOCK 2>/dev/null; do sleep 0.3; done; }
pub_locked() { lock_wait; load; publish; rmdir $LOCK; }   # for background jobs: publish writes the app's state file, so it must not overlap handle
pubapps() {
  echo "apps_state_time=$(sed -n 's/^apps_state_time=//p' $AFD 2>/dev/null)"
  echo "apps_refresh=$([ -d $RL ] && echo running || { [ -f $AFAIL ] && echo failed || echo idle; })"
}
# stdin: "art dump" text ("[com.x]" header, then "path:" blocks with "arm64: [status=speed] ..."). Prints "package=filter" with the first status of each package
# (the base apk). The header may be indented (dumpsys package dexopt nests it under "Dexopt state:").
dexstates() {
  # the 64-bit status of the base apk wins over the 32-bit one (webview lists arm first and it stays verify); statuses after "known secondary dex files:" are ignored
  sed -n -e 's/^ *\[\([A-Za-z][A-Za-z0-9_.]*\)\] *$/P \1/p' -e 's/^ *known secondary dex files:.*/X x/p' -e 's/^ *arm64: \[status=\([A-Za-z0-9_-]*\)\].*/A \1/p' -e 's/^ *arm: \[status=\([A-Za-z0-9_-]*\)\].*/S \1/p' | {
    pk=; a=; s=; x=
    emit() { [ -n "$pk" ] || return 0; if [ -n "$a" ]; then echo "$pk=$a"; elif [ -n "$s" ]; then echo "$pk=$s"; fi; }
    while read t v; do
      case $t in
        P) emit; pk=$v; a=; s=; x=;;
        X) x=1;;
        A) [ -z "$x" ] && [ -z "$a" ] && a=$v;;
        S) [ -z "$x" ] && [ -z "$s" ] && s=$v;;
      esac
    done
    emit
  }
}
apps_refresh() {   # $1 empty: read every package (takes seconds); $1 package: read that one and replace its line. Run in the background only.
  if [ -n "$1" ]; then while ! mkdir $RL 2>/dev/null; do sleep 1; done; else mkdir $RL 2>/dev/null || return; fi
  pub_locked
  if [ -n "$1" ]; then
    qs=$(cmd package art dump "$1" 2>/dev/null | dexstates | head -1)
    [ -n "$qs" ] && [ -f $AFD ] && { { grep -v -e "^$1=" -e '^apps_state_time=' $AFD; echo "$qs"; echo "apps_state_time=$(date +%s)"; } > $AFD.t && mv $AFD.t $AFD; }
  else
    qs=$(cmd package art dump 2>/dev/null | dexstates)
    [ -z "$qs" ] && qs=$(dumpsys package dexopt 2>/dev/null | dexstates)
    if [ -n "$qs" ]; then { echo "$qs"; echo "apps_state_time=$(date +%s)"; } > $AFD.t && mv $AFD.t $AFD; rm -f $AFAIL
    else : > $AFAIL; log "could not read the compile states"; fi
  fi
  [ -f $AFD ] && [ -f $APP/apps_state ] && cat $AFD > $APP/apps_state
  rmdir $RL; pub_locked
}
apps_refresh_bg() { ( trap - EXIT; apps_refresh "$1" ) < /dev/null > /dev/null 2>&1 & }
fixpkg() {   # the same stale copy cleanup as fixcache in dexopt_all.sh, for one package
  pm path "$1" | sed 's/^package://' | while read qk; do
    case $qk in /system/*|/product/*|/system_ext/*) ;; *) continue;; esac   # not /vendor: its old odex is rejected by this ART, the dalvik-cache copy is the real one
    qd=${qk%/*}; qb=${qk##*/}; qb=${qb%.apk}
    [ -f "$qd/oat/arm64/$qb.odex" ] || continue
    rm -f /data/dalvik-cache/arm64/$(echo "${qk#/}" | sed 's|/|@|g')@classes.*
  done
}
pkg_job() {   # $1 speed|speed-profile|reset, $2 package
  echo "$2:running" > $PST; pub_locked
  qr=done; qo=
  if ! oneof "$1" speed speed-profile reset || ! pkgok "$2" || ! pm path "$2" > /dev/null 2>&1; then qr=failed
  elif [ "$1" = reset ]; then qo=$(cmd package compile --reset "$2" 2>&1) || qr=failed
  else qo=$(cmd package compile -m "$1" -f "$2" 2>&1) || qr=failed
  fi
  echo "$qo" | grep -qE '^(Failure|Error)|Exception|Unknown' && qr=failed
  if [ $qr = done ] && [ "$1" != reset ]; then
    fixpkg "$2"
    [ "$2" != org.tablet.tuner ] && am force-stop "$2"   # a running process keeps the old code; the tuner itself would close its own screen
  fi
  log "$1 $2 $qr: $(echo "$qo" | head -1)"
  apps_refresh "$2"
  qs=; [ -f $QC ] && read -r qd qf qs < $QC; [ -n "$qs" ] && echo "$((qd + 1)) $((qf + $([ $qr = failed ] && echo 1 || echo 0))) $qs" > $QC   # jobs of this queue burst, for the progress keys
  echo "$2:$qr" > $PST
}
qworker() {   # one job at a time; the empty check and the flag reset happen under $LOCK, so a request that arrives meanwhile is never stranded
  while :; do
    lock_wait
    qj=$(sed -n 1p $QF 2>/dev/null)
    if [ -z "$qj" ]; then rm -f $QC; rmdir $QL; rmdir $LOCK; break; fi
    sed 1d $QF > $QF.t; mv $QF.t $QF; rmdir $LOCK
    set -- $qj; pkg_job "$1" "$2"
  done
  pub_locked
}
pkg_enqueue() {   # $1 speed|speed-profile|reset, $2 package; called from handle, which holds $LOCK
  if [ "$(cstat)" = running ]; then log "refused $1 $2: system compile running"; echo "$2:failed" > $PST; return; fi
  pm path "$2" > /dev/null 2>&1 || { log "no package $2"; return; }
  grep -qx "$1 $2" $QF 2>/dev/null && return
  echo "$1 $2" >> $QF
  if mkdir $QL 2>/dev/null; then echo "0 0 $(date +%s)" > $QC; ( trap - EXIT; qworker ) < /dev/null > /dev/null 2>&1 & fi
}

# ---------- compile progress ----------
# Bulk run: dexopt_all.sh prints "<package>" and then Success/Failure for its explicit lists, and "[<package>] Success|Failure" for the last step (-a),
# and ends with DONE. The daemon writes "=== run <epoch> ===" first. A line without its newline is not counted (the writer is still on it).
dxrun() {   # the newest run of the log: from the last marker on, the whole file when there is none
  n=$(grep -n '^=== run ' $DXL 2>/dev/null | tail -n 1 | cut -d: -f1)
  tail -n +${n:-1} $DXL 2>/dev/null
}
dxparse() {   # stdin: the run. Sets C_CUR (the package named last), C_KIND (compiling: no result yet; finished: it has one), C_DONE, C_FAIL, C_START, C_END
  C_CUR=; C_KIND=; C_DONE=0; C_FAIL=0; C_START=; C_END=; seen=" "; dp=
  while IFS= read -r dl; do
    case $dl in
      '=== run '*) C_START=${dl#=== run }; C_START=${C_START%% *}; case $C_START in ''|*[!0-9]*) C_START=;; esac;;
      DONE) C_END=1;;
      Success*|Failure*)   # the result of the package named above it (explicit lists)
        [ -z "$dp" ] && continue
        case $seen in *" $dp "*) ;; *) seen="$seen$dp "; C_DONE=$((C_DONE + 1)); case $dl in Failure*) C_FAIL=$((C_FAIL + 1));; esac;; esac
        dp=; C_KIND=finished;;
      \[*\]\ Success*|\[*\]\ Failure*)   # the -a step names the package in the result; one the lists already did is not counted again
        pk=${dl#\[}; pk=${pk%%\]*}; C_CUR=$pk; C_KIND=finished; dp=
        case $seen in *" $pk "*) ;; *) C_DONE=$((C_DONE + 1)); case ${dl#*\] } in Failure*) C_FAIL=$((C_FAIL + 1));; esac;; esac;;
      ''|[!A-Za-z]*|*[!A-Za-z0-9_.]*|*..*|*.) ;;
      *.*) C_CUR=$dl; dp=$dl; C_KIND=compiling;;
    esac
  done
}
dxprog() {   # sets the C_* values of $DXL; parses again only when the file size changed since the cached parse
  sz=; [ -f $DXL ] && sz=$(wc -c < $DXL); sz=$((${sz:-0} + 0)); C_SZ=
  [ -f $DXC ] && . $DXC
  if [ "$C_SZ" != "$sz" ]; then
    dxrun > $DXC.r; dxparse < $DXC.r; rm -f $DXC.r
    [ -n "$C_END" ] && C_END=$(stat -c %Y $DXL 2>/dev/null || date +%s)
    { for v in C_CUR C_KIND C_DONE C_FAIL C_START C_END; do eval echo $v=\$$v; done; echo C_SZ=$sz; } > $DXC.t && mv $DXC.t $DXC
  fi
}
mmss() { printf '%d:%02d' $(($1 / 60)) $(($1 % 60)); }
pubprog() {   # $1 = cstat. Progress keys of whatever is compiling; nothing when idle. A finished run leaves its summary.
  if [ "$1" = running ]; then
    dxprog; t=$(cat $DXT 2>/dev/null); t=$((${t:-0} + 0)); [ $t -gt 0 ] && [ $t -lt $C_DONE ] && t=$C_DONE   # 0: not counted yet
    echo "compile_current=$C_CUR"; echo "compile_current_state=$C_KIND"; echo "compile_done=$C_DONE"; echo "compile_total=$t"
    echo "compile_failed=$C_FAIL"; echo "compile_started=$C_START"
  elif [ -d $QL ] && [ -f $QC ]; then   # the per app queue: jobs done, the running one and the waiting ones are all known
    read -r qd qf qs < $QC; ps_=; [ -f $PST ] && read -r ps_ < $PST
    qw=0; [ -f $QF ] && qw=$(wc -l < $QF); qw=$((qw + 0)); qr_=0; qc_=; case $ps_ in *:running) qc_=${ps_%:*}; qr_=1;; esac
    echo "compile_current=$qc_"; echo "compile_current_state=$([ -n "$qc_" ] && echo compiling)"; echo "compile_done=$qd"
    echo "compile_total=$((qd + qr_ + qw))"; echo "compile_failed=$qf"; echo "compile_started=$qs"
  elif [ "$1" = done ]; then
    dxprog
    [ -n "$C_END" ] && echo "compile_summary=$C_DONE done, $C_FAIL failed$([ -n "$C_START" ] && echo " in $(mmss $((C_END - C_START)))")"
  fi
}
pubcompile() (   # a subshell: the parsing helpers use plain globals
  cs_=$(cstat)
  echo "compile_status=$cs_"
  echo "compile_pkg_status=$(cat $PST 2>/dev/null)"
  echo "compile_queue=$(cut -d' ' -f2 $QF 2>/dev/null | tr '\n' ,)"
  pubprog $cs_
)
# every 2 s from the daemon: while something compiles (and once more after it ends) only the compile_* lines of the app's state copy are rewritten, and
# only when they changed. The full publish costs a few seconds of cmd calls, this one a few small file reads.
PROG_SIG=; PROG_ON=
prog_pub() {
  { read -r pp_ < $DXP; } 2>/dev/null
  pr_=; [ -n "$pp_" ] && kill -0 $pp_ 2>/dev/null && pr_=1; [ -d $QL ] && pr_=1
  [ -z "$pr_" ] && [ -z "$PROG_ON" ] && return
  pn_=$(pubcompile)
  [ "$pn_" = "$PROG_SIG" ] && { PROG_ON=$pr_; return; }
  mkdir $LOCK 2>/dev/null || return
  [ -f $APP/state ] && { { grep -v '^compile_' $APP/state; echo "$pn_"; } > $S.pub; cat $S.pub > $APP/state; }
  rmdir $LOCK; PROG_SIG=$pn_; PROG_ON=$pr_
}

# ---------- state ----------
ovl() { cmd overlay list --user 0 | grep -qE "[ .]$1\$"; }   # is this theme overlay installed on this ROM?
seed() {
cat > $S <<EOS
perf_profile=balanced
preset=balanced
gpu_freq=680000
governor=schedplus
oc=0
thermal=stock
io_sched=deadline
readahead=512
swappiness=100
tcp_cc=$(cat /proc/sys/net/ipv4/tcp_congestion_control)
cap_big=-1
cap_little=-1
night=yes
black_theme=1
palette_source=preset
accent=3E91FF
theme_style=TONAL_SPOT
font=$(ovl font.lato && echo lato || echo default)
icon_shape=$(ovl icon.squircle && echo squircle || echo default)
icon_pack=$(ovl icon_pack.rounded.android && echo rounded || echo default)
nav_mode=gestural
anim_scale=1.0
density=192
bootanim=gemini
logging=0
wifi_country=CA
cached_apps=default
EOS
  for kv in $(presets smooth); do putkey ${kv%%=*} ${kv#*=}; done; putkey preset smooth
}
load() {
  . $S
  [ -z "$gpu_freq" ] && gpu_freq=$([ "$gpu_lock" = 0 ] && echo 0 || echo 680000)
  : ${fg_boost:=auto} ${ta_boost:=0} ${big_rotation:=0} ${vfs_cache:=50} ${dirty:=1} ${extra_free:=49152} ${page_cluster:=0}
  : ${touch_duration_ms:=400} ${touch_opp:=0} ${touch_boost:=100} ${schedstats:=0} ${down_throttle:=20} ${up_throttle:=0} ${cap_margin:=1536} ${mcdi:=1} ${watermark:=10} ${wifi_country:=CA} ${cached_apps:=default}
  : ${oc_master:=0} ${oc_cpu_uv:=0} ${oc_gpu:=0} ${oc_status:=off}
  : ${phh_effects:=0} ${governor:=schedplus} ${oc:=0} ${thermal:=stock} ${io_sched:=deadline} ${readahead:=512} ${swappiness:=100} ${tcp_cc:=bic}
}
putkey() { grep -v "^$1=" $S > $S.t; echo "$1=$2" >> $S.t; mv $S.t $S; }
publish() {   # copy state (+ a status line) to the app; the file is pre-created with the app's owner/context
  [ -f $APP/state ] || return
  gpu_eff=0; [ "$gpu_freq" != 0 ] && [ "$perf_profile" != battery ] && gpu_eff=1
  { cat $S; echo "profiles=$(cut -d"|" -f1 $PF 2>/dev/null | tr "\n" ,)"; echo "govs=$(tr ' ' , < /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors)"; echo "gpu_opps=$(sed -n 's/.*freq = \([0-9]*\),.*/\1/p' /proc/gpufreq/gpufreq_opp_dump | tr '\n' ,)"; echo "io_scheds=$(tr -d '[]' < /sys/block/mmcblk0/queue/scheduler | tr ' ' ,)"; echo "tcps=$(tr ' ' , < /proc/sys/net/ipv4/tcp_available_congestion_control)"; echo "fonts=$(cmd overlay list --user 0 | grep -oE "[A-Za-z0-9_.]*\\.font\\.[a-z0-9]+\$" | sed "s/.*font\\.//" | sort -u | tr "\\n" ,)"; ovl "icon\\.squircle" && echo has_shapes=1 || echo has_shapes=0; ovl "icon_pack\\.rounded\\.android" && echo has_packs=1 || echo has_packs=0; pubcompile; pubapps; echo "pq_live=$(pq_live)"; echo "status=$perf_profile, GPU $([ "$gpu_eff" = 1 ] && echo locked $((gpu_freq/1000)) MHz || echo auto)"; } > $S.pub
  cat $S.pub > $APP/state   # building takes seconds (cmd overlay list), so the app's file is written in one go and never read half empty
}

# ---------- appliers ----------
w() { echo "$2" > "$1" 2>/dev/null || log "FAIL $1"; }
calc() {
  case $perf_profile in
    battery)     pm=1 fg=0  minb=-1      minl=-1;;
    performance) pm=3 fg=40 minb=2009000 minl=1491000;;
    *)           pm=0 fg=20 minb=1484000 minl=1050000;;
  esac
  set -- $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies); topb=$1
  set -- $(cat /sys/devices/system/cpu/cpu4/cpufreq/scaling_available_frequencies); topl=$1
  [ "$oc" = 1 ] && { minb=$topb; minl=$topl; }
  [ "$fg_boost" != auto ] && fg=$fg_boost
  gpu_eff=0; [ "$gpu_freq" != 0 ] && [ "$perf_profile" != battery ] && gpu_eff=1
  [ "$cap_big" != -1 ]    && [ "$minb" -gt "$cap_big" ]    && minb=$cap_big
  [ "$cap_little" != -1 ] && [ "$minl" -gt "$cap_little" ] && minl=$cap_little
}
profile() {
  calc
  w /proc/cpufreq/cpufreq_power_mode $pm
  w /proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_fg_boost $fg
  w /proc/ppm/policy/userlimit_min_cpu_freq "0 -1"; w /proc/ppm/policy/userlimit_min_cpu_freq "1 -1"
  w /proc/ppm/policy/userlimit_max_cpu_freq "0 $cap_big"; w /proc/ppm/policy/userlimit_max_cpu_freq "1 $cap_little"
  w /proc/ppm/policy/userlimit_min_cpu_freq "0 $minb"; w /proc/ppm/policy/userlimit_min_cpu_freq "1 $minl"
  if [ "$gpu_eff" = 1 ]; then w /proc/gpufreq/gpufreq_opp_freq $gpu_freq; else w /proc/gpufreq/gpufreq_opp_freq 0; fi
}
ext() {   # $1 key, loaded state
  case $1 in
    screen_timeout) settings put system screen_off_timeout $screen_timeout;;
    font_scale) settings put system font_scale $font_scale;;
    font_weight) settings put secure font_weight_adjustment $font_weight;;
    text_contrast) settings put secure high_text_contrast_enabled $text_contrast;;
    stay_awake) settings put global stay_on_while_plugged_in $stay_awake;;
    auto_rotate) settings put system accelerometer_rotation $auto_rotate;;
    show_taps) settings put system show_touches $show_taps;;
    window_blur) settings put global disable_window_blurs $window_blur;;
    dns) case $dns in
           off) settings put global private_dns_mode off;;
           auto) settings put global private_dns_mode opportunistic;;
           *) case $dns in google) h=dns.google;; cloudflare) h=one.one.one.one;; adguard) h=dns.adguard-dns.com;; quad9) h=dns.quad9.net;; esac
              settings put global private_dns_specifier $h; settings put global private_dns_mode hostname;;
         esac;;
    bg_procs) if [ "$bg_procs" = default ]; then device_config delete activity_manager max_cached_processes; else device_config put activity_manager max_cached_processes $bg_procs; fi;;
    cached_apps) cur=$(settings get global activity_manager_constants); [ "$cur" = null ] && cur=
                 rest=$(echo "$cur" | tr , '\n' | grep -v '^max_cached_processes=' | tr '\n' , | sed 's/,$//')   # keep other constants
                 [ "$cached_apps" != default ] && rest="max_cached_processes=$cached_apps${rest:+,$rest}"
                 if [ -n "$rest" ]; then settings put global activity_manager_constants "$rest"; else settings delete global activity_manager_constants; fi;;
    wifi_country) ( trap - EXIT; wifi_apply ) &;;
    adb_tcp) if [ "$adb_tcp" = 1 ]; then setprop service.adb.tcp.port 5555; else setprop service.adb.tcp.port -1; fi; stop adbd; start adbd;;
    serial_console) if [ "$serial_console" = 1 ]; then setprop persist.sys.console 1; start console; else setprop persist.sys.console 0; stop console; fi;;
    bt_work) if [ "$bt_work" = 1 ]; then setprop persist.sys.bt.unsupported.commands 182; else setprop persist.sys.bt.unsupported.commands ""; fi
             svc bluetooth disable; sleep 2; svc bluetooth enable;;
  esac
}
# first run: take the current system values as the state, so the app shows what is really set
seedlive() {
  has() { grep -q "^$1=" $S; }
  v() { [ "$1" = null ] || [ -z "$1" ] && echo "$2" || echo "$1"; }
  has screen_timeout || putkey screen_timeout "$(v "$(settings get system screen_off_timeout)" 60000)"
  has carplay_boost || putkey carplay_boost 0
  has font_weight || putkey font_weight "$(v "$(settings get secure font_weight_adjustment)" 0)"
  has text_contrast || putkey text_contrast "$(v "$(settings get secure high_text_contrast_enabled)" 0)"
  has font_scale || putkey font_scale "$(v "$(settings get system font_scale)" 1.0)"
  has stay_awake || putkey stay_awake "$(v "$(settings get global stay_on_while_plugged_in)" 0)"
  has auto_rotate || putkey auto_rotate "$(v "$(settings get system accelerometer_rotation)" 1)"
  has show_taps || putkey show_taps "$(v "$(settings get system show_touches)" 0)"
  has window_blur || putkey window_blur "$(v "$(settings get global disable_window_blurs)" 0)"
  has dns || { m=$(settings get global private_dns_mode); case $m in off) d=off;; hostname) d=auto;; *) d=auto;; esac; putkey dns $d; }
  has bg_procs || putkey bg_procs "$(v "$(device_config get activity_manager max_cached_processes)" default)"
  has adb_tcp || putkey adb_tcp "$([ "$(getprop service.adb.tcp.port)" -gt 0 ] 2>/dev/null && echo 1 || echo 0)"
  has serial_console || putkey serial_console "$([ "$(getprop init.svc.console)" = running ] && echo 1 || echo 0)"
  for k in pq_mode pq_contrast pq_sat pq_gamma pq_dre pq_vp_dre pq_hdr pq_ultrares pq_shp pq_bri pq_color; do
    has $k || { pv=$(getprop persist.vendor.sys.pq.$(pq_prop $k)); [ -n "$pv" ] && putkey $k $pv; }
  done
  # keys added later: an existing install takes the values of its current preset (stock for battery and balanced)
  for kv in $(tweakset $(sed -n 's/^preset=//p' $S)); do has ${kv%%=*} || putkey ${kv%%=*} ${kv#*=}; done
  has wifi_country || putkey wifi_country CA
  has cached_apps || putkey cached_apps default
  has bt_work || putkey bt_work "$([ -n "$(getprop persist.sys.bt.unsupported.commands)" ] && echo 1 || echo 0)"
}
# MediaTek picture quality (MiraVision): the PQ HAL reads these persist props only when it starts, so it is restarted when one changes.
# What the HAL does with them (vendor.mediatek.hardware.pq@2.3-impl.so): picmode 0 = factory tables, 2 = "user defined", where contrastidx, gsatidx (0..9, 4 neutral), gamma.index (0..14, 7 neutral) and shp.idx (0..11, 2 neutral) are read at start and programmed into the display colour and gamma blocks. Outside mode 2 those four are ignored. The DRE, HDR and ultra resolution props only reach shared memory for engines this SoC/kernel does not have (no AAL/DRE30 in the kernel, no DRE engine in libdpframework), so they change nothing; they are still accepted so old profiles load.
pq_prop() { case $1 in pq_mode) echo picmode;; pq_contrast) echo contrastidx;; pq_sat) echo gsatidx;; pq_gamma) echo gamma.index;; pq_dre) echo mdp.dre.en;; pq_vp_dre) echo mdp.vp.dre.en;; pq_hdr) echo hdr.en;; pq_ultrares) echo ultrares.en;; pq_bri) echo brightidx;; pq_shp) echo shp.idx;; pq_color) echo mdp.color.idx;; esac; }
pq_apply() {
  ch=0
  for k in pq_mode pq_contrast pq_sat pq_gamma pq_dre pq_vp_dre pq_hdr pq_ultrares pq_shp pq_bri pq_color; do
    eval v=\$$k; [ -z "$v" ] && continue
    valid $k "$v" || { log "pq: skip bad $k=$v"; continue; }
    p=persist.vendor.sys.pq.$(pq_prop $k)
    [ "$(getprop $p)" != "$v" ] && { setprop $p $v; ch=1; }
  done
  [ $ch = 1 ] || return 0
  stop pq-2-2; sleep 1; start pq-2-2; sleep 3
  if [ "$(getprop init.svc.pq-2-2)" = running ]; then log "picture quality applied"
  else   # the HAL did not come back with these values: go back to the factory picture so the display keeps its colour tables
    log "picture quality FAILED, back to factory mode"
    setprop persist.vendor.sys.pq.picmode 0; putkey pq_mode 0; stop pq-2-2; sleep 1; start pq-2-2
  fi
}
# one word for the app: service state, then the values the HAL logged when it last programmed the display in user mode (the log may be off)
pq_live() {
  l=$(logcat -d -s PQ:D 2>/dev/null | grep 'Init_PQ_Userdef, gsat' | tail -n 1 | sed -n 's/.*gsat\[\([0-9]*\)\], cont\[\([0-9]*\)\], bri\[\([0-9]*\)\].*/gsat\1,cont\2,bri\3/p')
  echo "$(getprop init.svc.pq-2-2),mode$(getprop persist.vendor.sys.pq.picmode | grep . || echo 0),${l:-nolog}"
}
# saved profiles: one line per profile in $PF, "name|key=value key=value ...". They hold the performance, memory, picture and overclock keys (oc_master, oc_cpu_uv, oc_gpu), not the look. A profile saved before the overclock keys existed leaves the overclock as it is when applied; one that has them switches it on or off and the crash and thermal guards still apply.
PF=/data/local/tmp/tuner.profiles
PKEYS="perf_profile gpu_freq oc governor thermal fg_boost ta_boost big_rotation cap_big cap_little dirty swappiness vfs_cache extra_free page_cluster io_sched readahead tcp_cc touch_duration_ms touch_opp touch_boost schedstats down_throttle up_throttle cap_margin mcdi watermark pq_mode pq_contrast pq_sat pq_gamma pq_dre pq_vp_dre pq_hdr pq_ultrares pq_shp pq_bri pq_color oc_master oc_cpu_uv oc_gpu"
# a bare | in a ${%%} pattern is alternation in the device's mksh (it matched the whole line), so it is escaped below
# a copy of the current wallpaper for the picture preview in the app (the file is the system's, the app cannot read it)
preview_photo() {
  w=/data/system/users/0/wallpaper_orig; [ -f $w ] || w=/data/system/users/0/wallpaper; [ -f $w ] || return 0
  cat $w > $APP/preview.jpg && chown $(stat -c %u:%g $APP/state) $APP/preview.jpg && chmod 660 $APP/preview.jpg && log "preview photo copied"
}
pf_line() { while IFS= read -r l_; do [ "${l_%%\|*}" = "$1" ] && { echo "$l_"; return 0; }; done < $PF 2>/dev/null; return 1; }   # exact name match (no regex), prints the line
profile_save() {   # $1 name; an existing profile of that name is replaced in place, a new one goes last
  line="$1|"; for k in $PKEYS; do eval x=\$$k; [ -n "$x" ] && line="$line$k=$x "; done
  : > $PF.t; d=0
  if [ -f $PF ]; then while IFS= read -r l_; do
    [ -z "$l_" ] && continue
    if [ "${l_%%\|*}" = "$1" ]; then [ $d = 0 ] && echo "$line" >> $PF.t; d=1; else echo "$l_" >> $PF.t; fi
  done < $PF; fi
  [ $d = 1 ] || echo "$line" >> $PF.t
  mv $PF.t $PF; log "saved profile $1"
}
profile_delete() { [ -f $PF ] && { while IFS= read -r l_; do [ -n "$l_" ] && [ "${l_%%\|*}" != "$1" ] && echo "$l_"; done < $PF > $PF.t; mv $PF.t $PF; log "deleted profile $1"; }; }
profile_apply() {  # $1 name -> state keys. profile_base remembers where the settings came from; it survives edits (profile_active is cleared by those)
  l=$(pf_line "$1"); [ -z "$l" ] && { log "no profile $1"; return 1; }
  for kv in ${l#*\|}; do putkey ${kv%%=*} ${kv#*=}; done
  putkey preset custom; putkey profile_active "$1"; putkey profile_base "$1"; log "applied profile $1"
}
cpm() { case $1 in 0) echo 'Default(Normal) mode';; 1) echo 'Low Power mode';; 2) echo 'Just Make mode';; 3) echo 'Performance(Sports) mode';; esac; }
# validation: only these values reach a node or a command. Keys without a rule pass (they were checked by the character set).
oneof() { v_=$1; shift; for x_ in "$@"; do [ "$v_" = "$x_" ] && return 0; done; return 1; }
valid() {   # $1 key, $2 value
  case $1 in
    wifi_country) oneof "$2" auto CA US GB DE FR IN AU JP MX BR;;
    touch_duration_ms) oneof "$2" 100 200 400 800;;
    touch_opp) oneof "$2" 0 1 2;;
    touch_boost) case $2 in 0|[1-9]|[1-9][0-9]|100) return 0;; esac; return 1;;
    schedstats|mcdi) oneof "$2" 0 1;;
    down_throttle) oneof "$2" 4 10 20 40;;
    up_throttle) oneof "$2" 0 2 5;;
    cap_margin) oneof "$2" 1024 1280 1536 1792;;
    watermark) oneof "$2" 10 20 30;;
    cached_apps) oneof "$2" default 16 24 32 48;;
    compile_pkg|compile_pkg_profile|reset_pkg) pkgok "$2";;
    refresh_apps|preview_photo|hub_mode) oneof "$2" 1;;
    oc_master) oneof "$2" 0 1;;
    pq_mode) oneof "$2" 0 2;;
    pq_contrast|pq_sat) oneof "$2" 0 1 2 3 4 5 6 7 8 9;;
    pq_gamma) oneof "$2" 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14;;
    pq_shp) oneof "$2" 0 1 2 3 4 5 6 7 8 9 10 11;;
    pq_bri) oneof "$2" 0 1 2 3 4 5 6 7 8 9;;
    font_weight) oneof "$2" 0 100 200 300;;
    text_contrast) oneof "$2" 0 1;;
    carplay_boost) oneof "$2" 0 1;;
    density) case $2 in reset|1[2-9][0-9]|2[0-9][0-9]|3[0-9][0-9]|400) return 0;; esac; return 1;;
    pq_dre|pq_vp_dre|pq_ultrares) oneof "$2" 0 1 2;;
    pq_hdr) oneof "$2" 0 1;;
    pq_color) oneof "$2" 0 1 2 3;;
    oc_cpu_uv) oneof "$2" 0 1 2 4;;
    oc_gpu) oneof "$2" 0 730000 760000 800000 840000 880000 920000 940000;;
    oc_profile) oneof "$2" maxstable;;
    oc_stock) oneof "$2" 1;;
    *) return 0;;
  esac
}
tweakset() {   # $1 preset -> touch boost, clock behaviour, idle, scheduler and watermark keys: stock for battery and balanced, the tuned defaults otherwise
  case $1 in
    battery|balanced) echo "touch_duration_ms=100 touch_opp=2 touch_boost=80 schedstats=0 down_throttle=4 up_throttle=0 cap_margin=1280 mcdi=1 watermark=10";;
    *)                echo "touch_duration_ms=400 touch_opp=0 touch_boost=100 schedstats=0 down_throttle=20 up_throttle=0 cap_margin=1536 mcdi=1 watermark=10";;
  esac
}
presets() {   # $1 name -> state keys
  case $1 in
    battery)     echo "perf_profile=battery gpu_freq=0 oc=0 governor=schedplus thermal=stock fg_boost=auto ta_boost=0 big_rotation=0 cap_big=1484000 cap_little=1050000 dirty=5 swappiness=100 vfs_cache=100 extra_free=24576 $(tweakset battery)";;
    balanced)    echo "perf_profile=balanced gpu_freq=680000 oc=0 governor=schedplus thermal=stock fg_boost=auto ta_boost=0 big_rotation=0 cap_big=-1 cap_little=-1 dirty=1 swappiness=100 vfs_cache=50 extra_free=49152 $(tweakset balanced)";;
    smooth)      echo "perf_profile=performance gpu_freq=680000 oc=0 governor=schedplus thermal=stock fg_boost=60 ta_boost=30 big_rotation=0 cap_big=-1 cap_little=-1 dirty=10 swappiness=100 vfs_cache=50 extra_free=98304 $(tweakset smooth)";;
    performance) echo "perf_profile=performance gpu_freq=680000 oc=0 governor=schedplus thermal=stock fg_boost=100 ta_boost=70 big_rotation=0 cap_big=-1 cap_little=-1 dirty=20 swappiness=60 vfs_cache=50 extra_free=98304 $(tweakset performance)";;
    extreme)     echo "perf_profile=performance gpu_freq=680000 oc=1 governor=performance thermal=off fg_boost=100 ta_boost=70 big_rotation=0 cap_big=-1 cap_little=-1 dirty=20 swappiness=60 vfs_cache=50 extra_free=98304 readahead=1024 $(tweakset extreme)";;
  esac
}
# w for nodes that may be missing (schedplus nodes only exist while that governor runs): skipped quietly when absent
wn() { [ -w "$1" ] && w "$1" "$2"; }
sane() { eval sv=\$$1; valid $1 "$sv" || eval $1=$2; }   # a bad value in the state file falls back to the default instead of reaching a node
sched_clocks() {   # the schedplus governor forgets these when the governor is set again
  sane down_throttle 20; sane up_throttle 0
  for dd in 0 4; do
    wn /sys/devices/system/cpu/cpufreq/policy$dd/sched/down_throttle_nsec $((down_throttle * 1000000))
    wn /sys/devices/system/cpu/cpufreq/policy$dd/sched/up_throttle_nsec $((up_throttle * 1000000))
  done
}
newtune() {   # touch boost, schedplus clocks, idle, scheduler statistics, watermark
  sane touch_duration_ms 400; sane touch_opp 0; sane touch_boost 100; sane schedstats 0; sane down_throttle 20
  sane up_throttle 0; sane cap_margin 1536; sane mcdi 1; sane watermark 10
  TB=/proc/perfmgr/tchbst/user/usrtch
  wn $TB "duration $((touch_duration_ms * 1000000))"; wn $TB "touch_opp $touch_opp"; wn $TB "init $touch_boost"
  wn /proc/sys/kernel/sched_schedstats $schedstats
  wn /proc/sys/vm/watermark_scale_factor $watermark
  wn /sys/devices/system/cpu/eas/cap_margin_dvfs $cap_margin
  for ff in /sys/devices/system/cpu/cpu[0-9]*/cpuidle/state1/disable; do wn $ff $((1 - mcdi)); done   # state1 is MCDI; 1 disables it
  sched_clocks
}
wifi_apply() {   # the country override lives in memory, so it is set again after every boot or system_server restart; Wi-Fi may not be ready yet
  n=0
  while [ $n -lt 12 ]; do
    load; valid wifi_country "$wifi_country" || return
    if [ "$wifi_country" = auto ]; then o=$(cmd wifi force-country-code disabled 2>&1); else o=$(cmd wifi force-country-code enabled $wifi_country 2>&1); fi
    [ $? = 0 ] && ! echo "$o" | grep -qiE "can't find|exception|error|unknown|usage" && { log "wifi country $wifi_country"; return; }
    n=$((n + 1)); sleep 5
  done
  log "wifi country not applied: $o"
}
tune() {   # governor, overclock, thermal, I/O, memory, network
  g=$governor; [ "$oc" = 1 ] && g=performance
  for p in 0 4; do w /sys/devices/system/cpu/cpu$p/cpufreq/scaling_governor $g; done
  w /sys/block/mmcblk0/queue/scheduler $io_sched
  w /sys/block/mmcblk0/queue/read_ahead_kb $readahead
  w /proc/sys/vm/swappiness $((swappiness > 100 ? 100 : swappiness))   # kernel 4.9 maximum
  w /proc/sys/net/ipv4/tcp_congestion_control $tcp_cc
  E=/proc/perfmgr/boost_ctrl/eas_ctrl
  w $E/perfserv_ta_boost $ta_boost; w $E/sched_big_task_rotation $big_rotation
  w /proc/sys/vm/vfs_cache_pressure $vfs_cache
  w /proc/sys/vm/dirty_ratio $dirty; w /proc/sys/vm/dirty_background_ratio $((dirty > 1 ? dirty / 2 : 1))
  w /proc/sys/vm/extra_free_kbytes $extra_free; w /proc/sys/vm/page-cluster $page_cluster
  if [ "$thermal" = off ]; then w /proc/ppm/policy_status "4 0"; else w /proc/ppm/policy_status "4 1"; fi
  newtune
}
theme() {
  K=android.theme.customization
  if [ "$palette_source" = home_wallpaper ]; then
    J="{\"$K.color_both\":\"1\",\"$K.color_source\":\"home_wallpaper\""
  else
    J="{\"$K.color_both\":\"0\",\"$K.color_source\":\"preset\",\"$K.system_palette\":\"$accent\",\"$K.accent_color\":\"$accent\""
  fi
  J="$J,\"$K.theme_style\":\"$theme_style\""
  fp=
  if [ "$font" != default ]; then
    fp=$(cmd overlay list --user 0 | grep -oE "[A-Za-z0-9_.]*\\.font\\.$font\$" | head -1)
    [ -n "$fp" ] && J="$J,\"$K.font\":\"$fp\""
  fi
  [ "$icon_shape" != default ] && ovl "icon\\.$icon_shape" && J="$J,\"$K.adaptive_icon_shape\":\"com.android.theme.icon.$icon_shape\""
  if [ "$icon_pack" != default ] && ovl "icon_pack\\.$icon_pack\\.android"; then
    for t in android launcher settings systemui; do J="$J,\"$K.icon_pack.$t\":\"com.android.theme.icon_pack.$icon_pack.$t\""; done
  fi
  settings put secure theme_customization_overlay_packages "$J,\"_applied_timestamp\":$(date +%s)000}"
  # the theme controller of this SystemUI does not switch font overlays by itself, so do it here (--category: disable the others of that category)
  if [ "$font" = default ]; then
    for o in $(cmd overlay list --user 0 | grep -oE "[A-Za-z0-9_.]*\\.font\\.[a-z0-9]+\$"); do cmd overlay disable --user 0 $o; done
  elif [ -n "$fp" ]; then cmd overlay enable-exclusive --user 0 --category $fp; fi
  # the black overlay shares the palette category and gets dropped when the theme is rewritten
  ( sleep 6; black ) &
}
# set-priority highest: the dynamic palette overlay (android:dynamic_0) is above a freshly enabled overlay and would keep the surfaces dark blue instead of black
black() { if [ "$black_theme" = 1 ]; then cmd overlay enable --user 0 $BLACK; cmd overlay set-priority $BLACK highest; else cmd overlay disable --user 0 $BLACK; fi; }
# --category is a flag (disable the other overlays of the package's category), not an option with a value: the old call passed the category name as the package and failed
nav() { cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.$([ "$nav_mode" = threebutton ] && echo threebutton || echo gestural); }
anim() { for k in window_animation_scale transition_animation_scale animator_duration_scale; do settings put global $k $anim_scale; done; }
density_() { if [ "$density" = reset ]; then wm density reset; else wm density $density; fi; }
bootanim() {
  mount -o remount,rw / 2>/dev/null
  cmp -s $M/bootanimation-$bootanim.zip $M/bootanimation.zip || { cp $M/bootanimation-$bootanim.zip $M/bootanimation.zip; chmod 644 $M/bootanimation.zip; restorecon $M/bootanimation.zip; }
}
logging() {
  if [ "$logging" = 1 ]; then setprop persist.log.tag ""; logcat -b all -G 1M
  else setprop persist.log.tag S; logcat -b all -G 64K; fi
}
night_() { cmd uimode night $([ "$night" = yes ] && echo yes || { [ "$night" = no ] && echo no || echo auto; }); }

# ---------- request handling ----------
handle() {
  while ! mkdir $LOCK 2>/dev/null; do sleep 0.3; done
  trap 'rmdir $LOCK' EXIT
  [ -f $APP/request ] || exit 0
  mv $APP/request $APP/request.work
  load; T=0; P=0; U=0; Q=0
  while IFS== read -r k v; do
    case $v in ''|*[!A-Za-z0-9._-]*) log "reject $k"; continue;; esac
    valid "$k" "$v" || { log "reject $k=$v"; continue; }
    log "req $k=$v"
    case $k in preset|restart_ui|reboot|compile_now|compile_all|compile_pkg|compile_pkg_profile|reset_pkg|refresh_apps|preview_photo|hub_mode|night|black_theme|phh_effects|nav_mode|anim_scale|density|bootanim|logging|palette_source|accent|theme_style|font|icon_shape|icon_pack|screen_timeout|font_scale|font_weight|text_contrast|carplay_boost|stay_awake|auto_rotate|show_taps|window_blur|dns|bg_procs|cached_apps|wifi_country|adb_tcp|bt_work|serial_console|pq_mode|pq_contrast|pq_sat|pq_gamma|pq_dre|pq_vp_dre|pq_hdr|pq_ultrares|pq_shp|pq_bri|pq_color|save_profile|update_profile|apply_profile|delete_profile|profile_active|profile_base|oc_master|oc_cpu_uv|oc_gpu|oc_profile|oc_stock) ;; *) putkey preset custom; putkey profile_active "";; esac
    case $k in
      perf_profile|gpu_lock|gpu_freq|oc|fg_boost|cap_big|cap_little) P=1; U=1; putkey $k $v;;
      palette_source|accent|theme_style|font|icon_shape|icon_pack) T=1; putkey $k $v;;
      governor|thermal|io_sched|readahead|swappiness|tcp_cc|ta_boost|big_rotation|vfs_cache|dirty|extra_free|page_cluster|touch_duration_ms|touch_opp|touch_boost|schedstats|down_throttle|up_throttle|cap_margin|mcdi|watermark) U=1; putkey $k $v;;
      screen_timeout|font_scale|font_weight|text_contrast|carplay_boost|stay_awake|auto_rotate|show_taps|window_blur|dns|bg_procs|cached_apps|wifi_country|adb_tcp|bt_work|serial_console) putkey $k $v; load; ext $k; continue;;
      night|black_theme|nav_mode|anim_scale|density|bootanim|logging) putkey $k $v;;
      pq_mode|pq_contrast|pq_sat|pq_gamma|pq_dre|pq_vp_dre|pq_hdr|pq_ultrares|pq_shp|pq_bri|pq_color) putkey $k $v; Q=1; load; continue;;
      oc_master|oc_cpu_uv|oc_gpu) putkey $k $v;;
      oc_profile) for kv in oc_master=1 oc=1 oc_cpu_uv=0 oc_gpu=730000 preset=custom profile_active=; do putkey ${kv%%=*} ${kv#*=}; done; P=1; U=1; load; continue;;
      oc_stock) for kv in oc_master=0 oc=0 oc_cpu_uv=0 oc_gpu=0 oc_status=off preset=custom profile_active=; do putkey ${kv%%=*} ${kv#*=}; done; rm -f $OCM; load; oc_off; P=1; U=1; continue;;
      save_profile) profile_save $v; putkey profile_active $v; putkey profile_base $v; load; continue;;
      update_profile) if pf_line "$v" > /dev/null; then profile_save $v; putkey profile_active $v; putkey profile_base $v; else log "reject update_profile $v: no such profile"; fi; load; continue;;
      delete_profile) profile_delete $v; [ "$profile_active" = "$v" ] && putkey profile_active ""; [ "$profile_base" = "$v" ] && putkey profile_base ""; load; continue;;
      apply_profile) profile_apply $v && { P=1; U=1; Q=1; }; load; continue;;
      preset) for kv in $(presets $v); do putkey ${kv%%=*} ${kv#*=}; done; putkey preset $v; putkey profile_active ""; putkey profile_base ""; P=1; U=1; load; continue;;
      restart_ui) pkill -f com.android.systemui; continue;;
      reboot) sync; reboot; continue;;
      compile_now) compile ""; continue;;
      compile_all) compile -f; continue;;
      compile_pkg) pkg_enqueue speed "$v"; continue;;
      compile_pkg_profile) pkg_enqueue speed-profile "$v"; continue;;
      reset_pkg) pkg_enqueue reset "$v"; continue;;
      refresh_apps) apps_refresh_bg; continue;;
      hub_mode) service call dreams 1 >/dev/null 2>&1; continue;;   # DreamManager.dream(): the screen saver (Hub mode) starts now

      preview_photo) preview_photo; continue;;   # the app cannot read the wallpaper itself
      *) log "unknown $k"; continue;;
    esac
    load
    case $k in
      night) night_;; black_theme) black;; nav_mode) nav;; anim_scale) anim;; density) density_;;
      bootanim) bootanim;; logging) logging;;
    esac
  done < $APP/request.work
  rm -f $APP/request.work
  [ $P = 1 ] && profile
  [ $U = 1 ] && tune
  [ $T = 1 ] && theme
  load; [ $Q = 1 ] && pq_apply
  publish
}

# ---------- overclock ----------
# What the kernel really accepts (see README, "Overclock"): CPU clocks cannot go above the table, so the CPU part is a voltage trim (EEM offset, in
# 6.25 mV steps, removed from the voltage the kernel computed). The GPU takes any frequency through gpufreq_fixed_freq_volt; only values from the
# valid list are used, always at the top stock voltage (0.80 V). The marker $OCM is written before anything is applied and removed after 3 minutes
# of stable running; a marker from an earlier boot means that boot died while overclocked.
OCM=/data/local/tmp/oc_pending
OCG=/proc/gpufreq; OCE=/proc/eem; OCT=/sys/class/thermal; OCB=/proc/sys/kernel/random/boot_id
OCMAXT=85000; OC_ON=0; OC_CUR=; OC_T0=0
ocst() { lock_wait; putkey oc_status "$1"; load; publish; rmdir $LOCK; }   # publishes oc_status=off|waiting|testing|active|reverted_after_crash|thermal_revert|gpu_failed|cpu_failed|unsupported
ockeys() { lock_wait; putkey oc_master 0; [ "$1" = all ] && { putkey oc_cpu_uv 0; putkey oc_gpu 0; }; rmdir $LOCK; }
ocwant() { [ "$oc_master" = 1 ] && { [ "$oc_cpu_uv" != 0 ] || [ "$oc_gpu" != 0 ]; }; }
tmax() { cat $OCT/thermal_zone*/temp 2>/dev/null | sort -n | tail -n 1; }   # millidegrees, the hottest zone
ocgtop() { sed -n '1s/.*freq = \([0-9]*\), volt = \([0-9]*\).*/\1 \2/p' $OCG/gpufreq_opp_dump; }   # top stock GPU OPP: "kHz 10uV"
oc_off() {   # idempotent: stock voltage trim, GPU back on the OPP table, the saved GPU clock setting again
  for b in L L_HI 2L 2L_HI; do wn $OCE/EEM_DET_$b/eem_offset 0; done
  w $OCG/gpufreq_fixed_freq_volt "0 0"
  set -- $(ocgtop); [ -n "$1" ] && w $OCG/gpufreq_opp_freq $1
  calc; if [ "$gpu_eff" = 1 ]; then w $OCG/gpufreq_opp_freq $gpu_freq; else w $OCG/gpufreq_opp_freq 0; fi
}
ocfail() { oc_off; rm -f $OCM; OC_ON=0; ockeys all; ocst $1; log "overclock $1"; }
oc_on() {
  { cat $OCB; date +%s; } > $OCM; sync
  OC_ON=1; OC_CUR="$oc_gpu/$oc_cpu_uv"; OC_T0=$(date +%s)
  if [ "$oc_cpu_uv" != 0 ]; then
    for b in L L_HI 2L 2L_HI; do wn $OCE/EEM_DET_$b/eem_offset -$oc_cpu_uv; done
    [ "$(cat $OCE/EEM_DET_L/eem_offset 2>/dev/null)" = "-$oc_cpu_uv" ] || { ocfail cpu_failed; return; }
  fi
  if [ "$oc_gpu" != 0 ]; then
    set -- $(ocgtop)
    [ "$1/$2" = 680000/80000 ] || { ocfail unsupported; return; }   # only the known table: 680 MHz at 0.80 V
    w $OCG/gpufreq_opp_freq 680000       # keeps the Vcore request at its top step, which is where the fixed voltage comes from
    w $OCG/gpufreq_fixed_freq_volt "$oc_gpu 80000"; sleep 1
    rf=$(sed -n 's/^real freq = \([0-9]*\),.*/\1/p' $OCG/gpufreq_var_dump); case $rf in ''|*[!0-9]*) rf=0;; esac
    d=$((rf - oc_gpu)); [ $d -lt 0 ] && d=$((-d))
    [ $d -gt $((oc_gpu / 50)) ] && { ocfail gpu_failed; return; }   # the PLL did not take the value (2 percent tolerance)
  fi
  ocst testing; log "overclock on gpu=$oc_gpu cpu_uv=$oc_cpu_uv"
}
oc_boot() {   # at daemon start: a marker from another boot means that boot ended while overclocked
  [ -f $OCM ] || return
  [ "$(sed -n 1p $OCM)" = "$(cat $OCB)" ] && return   # same boot: only the daemon restarted
  rm -f $OCM; ockeys all; putkey oc_status reverted_after_crash; log "overclock reverted after a crash or reboot while overclocked"
}
oc_watch() {   # every 2 s from the daemon
  load; sane oc_master 0; sane oc_cpu_uv 0; sane oc_gpu 0
  t=$(tmax); t=${t:-0}
  if [ "$OC_ON" = 1 ]; then
    if [ "$t" -gt $OCMAXT ]; then oc_off; rm -f $OCM; OC_ON=0; ockeys; ocst thermal_revert; log "overclock thermal revert at $t"; return; fi
    if ! ocwant; then oc_off; rm -f $OCM; OC_ON=0; ocst off; return; fi
    [ "$OC_CUR" != "$oc_gpu/$oc_cpu_uv" ] && { oc_off; oc_on; return; }
    [ -f $OCM ] && [ $(( $(date +%s) - OC_T0 )) -ge 180 ] && { rm -f $OCM; ocst active; }
  elif ocwant; then
    [ "$oc_status" != waiting ] && ocst waiting
    [ $(( $(date +%s) - DSTART )) -lt 30 ] && return    # 30 s after boot_completed
    if [ "$t" -gt $OCMAXT ]; then ockeys; ocst thermal_revert; return; fi
    oc_on
  elif [ "$oc_status" = waiting ]; then ocst off
  fi
}
# ---------- end overclock ----------

# ---------- daemon ----------
[ -n "$1" ] && { case $1 in *w*) [ "$3" = request ] && handle;; esac; exit 0; }
rmdir $LOCK $QL $RL 2>/dev/null; rm -f $QF $PST $AFAIL $QC
[ -f $S ] || seed
until [ -d $(dirname $APP) ]; do sleep 2; done; mkdir -p $APP; chown $(stat -c %u $(dirname $APP)):$(stat -c %u $(dirname $APP)) $APP; chmod 771 $APP; restorecon $APP
UID_=$(stat -c %u $APP)
for f in state request apps_state; do [ -f $APP/$f ] || touch $APP/$f; chown $UID_:$UID_ $APP/$f; chmod 660 $APP/$f; restorecon $APP/$f; done
rm -f $APP/request                          # the app recreates it; inotifyd watches the directory
[ -f $AFD ] && cat $AFD > $APP/apps_state   # the last compile state list, until the app asks for a new one
w /proc/ppm/policy_status "2 0"; w /proc/ppm/policy_status "3 0"   # FORCE_LIMIT and PWR_THRO off
w /proc/sys/kernel/printk "1 4 1 7"
seedlive; load; oc_boot; load; profile; tune; [ "$cached_apps" != default ] && ext cached_apps; night_; nav; anim; density_; bootanim; pq_apply; [ "$logging" = 1 ] && logging
( sleep 12; load; black; sleep 25; black ) &
# Treble Settings > Audio effects attaches DAP, Dynamics Processing, Loudness Enhancer, Visualizer and Downmix to every playback session; on this tablet
# that cuts app audio after a few ms. Keep them off unless the user allows them (phh_effects=1).
phhfx() {
  [ "$phh_effects" = 1 ] && return
  D=/data/data/me.phh.treble.app; [ -d $D ] || return
  P=$D/shared_prefs/me.phh.treble.app_preferences.xml
  if [ ! -f $P ]; then
    mkdir -p $D/shared_prefs
    { echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"; echo "<map>"
      for u in 93f04452-e4fe-41cc-91f9-e475b6d1d69f e0e6539b-1781-7261-676f-6d7573696340 d069d9e0-8329-11df-9168-0002a5d5c51b 9d4921da-8225-4f29-aefa-39537a04bcaa fa415329-2034-4bea-b5dc-5b381c8d1e2c; do
        echo "    <boolean name=\"audio_effect_$u\" value=\"false\" />"; done; echo "</map>"; } > $P
    o=$(stat -c %u $D); chown -R $o:$o $D/shared_prefs; chmod 660 $P; restorecon -R $D/shared_prefs
  fi
  grep -q 'audio_effect_[0-9a-f-]*" value="true"' $P && { am force-stop me.phh.treble.app; sed -i 's/\(audio_effect_[0-9a-f-]*" value=\)"true"/\1"false"/' $P; log "turned Treble audio effects off"; }
}
# The MTK power HAL rewrites the power mode, boosts and clock limits on touch boosts and screen on/off. Put ours back whenever they drift.
enforce() {
  load; calc; phhfx
  [ "$(cat /proc/cpufreq/cpufreq_power_mode)" != "$(cpm $pm)" ] && w /proc/cpufreq/cpufreq_power_mode $pm
  [ "$(cat /proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_fg_boost)" != "$fg" ] && w /proc/perfmgr/boost_ctrl/eas_ctrl/perfserv_fg_boost $fg
  g=$governor; [ "$oc" = 1 ] && g=performance
  gc=; for p in 0 4; do [ "$(cat /sys/devices/system/cpu/cpu$p/cpufreq/scaling_governor)" != "$g" ] && { w /sys/devices/system/cpu/cpu$p/cpufreq/scaling_governor $g; gc=1; }; done
  [ -n "$gc" ] && sched_clocks
  sp=$(pidof system_server); [ -n "$sp" ] && [ "$sp" != "$SSPID" ] && { SSPID=$sp; ( trap - EXIT; wifi_apply ) & }   # also covers the first run after boot
  [ "$perf_profile" = battery ] && return
  grep -q "^0: min_freq_idx = -1" /proc/ppm/policy/userlimit_min_cpu_freq && { w /proc/ppm/policy/userlimit_min_cpu_freq "0 $minb"; w /proc/ppm/policy/userlimit_min_cpu_freq "1 $minl"; }
  [ "$gpu_eff" = 1 ] && head -1 /proc/gpufreq/gpufreq_opp_freq | grep -q disabled && w /proc/gpufreq/gpufreq_opp_freq $gpu_freq
}
# Boost while a CarPlay receiver is on screen: when DiPlay (or its fork Pillion) is the top app and the preset is one of the lighter named ones (Battery,
# Balanced, Smooth), run the Performance preset, then put the snapshot back when it leaves. Custom settings, profiles, Performance and Extreme are never touched.
BOOST=/data/local/tmp/tuner.boost
carplay_top() {   # the top app has oom_score_adj 0; a running session that is not on screen has a higher one
  for p in org.tablet.pillion com.shihab.diplay; do for pid in $(pidof $p); do [ "$(cat /proc/$pid/oom_score_adj 2>/dev/null)" = 0 ] && return 0; done; done
  return 1
}
boost_off() {   # the snapshot is only put back while the boost is still what is set: if the user picked something else meanwhile, theirs wins
  load
  if [ "$preset" = performance ] && [ -z "$profile_active" ]; then
    while IFS== read -r k v; do [ -n "$k" ] && putkey "$k" "$v"; done < $BOOST; rm -f $BOOST
    load; calc; profile; tune; publish; log "carplay boost off"
  else rm -f $BOOST; log "carplay boost dropped, settings were changed meanwhile"; fi
}
boostwatch() {
  load
  if [ "$carplay_boost" != 1 ]; then [ -f $BOOST ] && { mkdir $LOCK 2>/dev/null || return; boost_off; rmdir $LOCK; }; return; fi
  if carplay_top; then
    [ -f $BOOST ] && return
    case $preset in battery|balanced|smooth) ;; *) return;; esac
    mkdir $LOCK 2>/dev/null || return
    { for kv in $(presets performance); do k=${kv%%=*}; eval x=\$$k; [ -n "$x" ] && echo "$k=$x"; done; echo "preset=$preset"; echo "profile_active=$profile_active"; } > $BOOST
    for kv in $(presets performance); do putkey ${kv%%=*} ${kv#*=}; done; putkey preset performance; putkey profile_active ""
    load; calc; profile; tune; publish; log "carplay boost on"; rmdir $LOCK
  else
    [ -f $BOOST ] && { mkdir $LOCK 2>/dev/null || return; boost_off; rmdir $LOCK; }
  fi
}
# the compile run ends on its own, so tell the app when its status changes
CS=
cstat_pub() { n=$(cstat); [ "$n" = "$CS" ] && return; mkdir $LOCK 2>/dev/null || return; [ "$CS" = running ] && apps_refresh_bg; CS=$n; load; publish; rmdir $LOCK; }
( while sleep 5; do enforce; cstat_pub; done ) &
DSTART=$(date +%s)
( while sleep 2; do oc_watch; prog_pub; done ) &
( while sleep 3; do boostwatch; done ) &
publish; log "daemon ready"
exec inotifyd /system/bin/tablet_tunerd.sh $APP:nw   # toybox mask parsing is quirky: single letters are rejected, "wn" drops w
