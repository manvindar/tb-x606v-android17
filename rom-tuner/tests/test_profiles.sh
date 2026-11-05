#!/bin/sh
# Runs the saved profile code of device/tablet_tunerd.sh (profile_save, profile_apply, profile_delete, and the request handler for save_profile, update_profile, apply_profile, delete_profile, preset and plain edits) against temp files (sh, no device).
# Usage: sh tests/test_profiles.sh   (from rom-tuner/)
D=$(cd "$(dirname "$0")/.." && pwd); TD=$(mktemp -d); fail=0
ok() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got [$2] want [$3]"; fail=1; fi; }
log() { echo "$*" >> $TD/log; }
S=$TD/state; PF=$TD/pf; APP=$TD/app; LOCK=$TD/lock; mkdir -p $APP
sleep() { :; }; sync() { :; }; publish() { :; }; profile() { :; }; tune() { :; }; theme() { :; }; pq_apply() { :; }
ext() { :; }; oc_off() { :; }; reboot() { echo reboot >> $TD/bad; }; pkill() { echo pkill >> $TD/bad; }; OCM=$TD/oc_pending
eval "$(sed -n '/^oneof() /p;/^valid() /,/^}/p;/^putkey() /p;/^load() {/,/^}/p;/^pf_line() /p;/^PKEYS=/p;/^profile_save() /,/^}/p;/^profile_delete() /p;/^profile_apply() /,/^}/p;/^tweakset() /,/^}/p;/^presets() /,/^}/p;/^handle() {/,/^}/p;/^pq_prop() /p;/^pq_apply() {/,/^}/p' $D/device/tablet_tunerd.sh)"
st() { sed -n "s/^$1=//p" $S | tail -1; }
names() { cut -d'|' -f1 $PF | tr '\n' ' '; }
req() { printf '%s\n' "$@" > $APP/request; ( handle ); }   # handle() ends in exit and a trap, so it runs in a subshell
: > $S; load

# profile_save: new profiles go last, a second save under the same name replaces in place
perf_profile=balanced; governor=schedplus; oc_gpu=0
profile_save a; profile_save b; profile_save c
ok "save: three in order" "$(names)" "a b c "
governor=performance; oc_master=1; oc_cpu_uv=2; oc_gpu=730000
profile_save b
ok "replace: no duplicate, position kept" "$(names)" "a b c "
ok "replace: new values" "$(grep '^b|' $PF | grep -c 'governor=performance')" 1
ok "replace: old line gone" "$(grep -c '^b|' $PF)" 1
ok "replace: overclock keys saved" "$(grep '^b|' $PF | grep -c 'oc_master=1 oc_cpu_uv=2 oc_gpu=730000')" 1
ok "other profile untouched" "$(grep '^a|' $PF | grep -c 'governor=schedplus')" 1
printf 'x|governor=a \nb|governor=old \nb|governor=dup \n' > $PF; profile_save b
ok "replace: old duplicates collapse" "$(names)" "x b "
ok "replace: dots are not wildcards" "$(printf 'axb|k=1 \n' > $PF; profile_save a.b; names)" "axb a.b "
profile_delete a.b
ok "delete: exact name only" "$(names)" "axb "
rm -f $PF; profile_save only
ok "save: creates the file" "$(names)" "only "
rm -f $PF

# handler: lifecycle of profile_base
: > $S; load; rm -f $PF
req save_profile=one
ok "save: active" "$(st profile_active)" one; ok "save: base" "$(st profile_base)" one; ok "save: listed" "$(names)" "one "
req perf_profile=battery
ok "edit: active cleared" "$(st profile_active)" ""; ok "edit: base kept" "$(st profile_base)" one; ok "edit: preset custom" "$(st preset)" custom
req dirty=20
ok "second edit: base kept" "$(st profile_base)" one
req update_profile=one
ok "update: active" "$(st profile_active)" one; ok "update: base" "$(st profile_base)" one
ok "update: still one profile" "$(names)" "one "
ok "update: line has the edit" "$(grep -c 'perf_profile=battery .*dirty=20 ' $PF)" 1
req save_profile=two
ok "save two: base two" "$(st profile_base)" two; ok "save two: order" "$(names)" "one two "
req apply_profile=one
ok "apply: active" "$(st profile_active)" one; ok "apply: base" "$(st profile_base)" one
req swappiness=60
req apply_profile=two
ok "apply another: base" "$(st profile_base)" two; ok "apply another: active" "$(st profile_active)" two
req swappiness=60
req update_profile=one
ok "update other name: replaced in place" "$(names)" "one two "
ok "update other name: base follows" "$(st profile_base)" one
req swappiness=100
req delete_profile=two
ok "delete a different profile keeps base" "$(st profile_base)" one; ok "delete: listed" "$(names)" "one "
req delete_profile=one
ok "delete base: cleared" "$(st profile_base)" ""; ok "delete base: active cleared" "$(st profile_active)" ""; ok "delete: none left" "$(names)" ""
req save_profile=one
req dirty=5
req preset=smooth
ok "preset: base cleared" "$(st profile_base)" ""; ok "preset: active cleared" "$(st profile_active)" ""; ok "preset: set" "$(st preset)" smooth
req apply_profile=one
req oc_stock=1
ok "oc_stock edit: base kept" "$(st profile_base)" one; ok "oc_stock edit: active cleared" "$(st profile_active)" ""
req apply_profile=one
req oc_gpu=880000
ok "oc edit: active cleared (the profile in use no longer matches)" "$(st profile_active)" ""; ok "oc edit: base kept (Save changes is offered)" "$(st profile_base)" one
req apply_profile=one
req pq_contrast=5
ok "picture edit: active cleared" "$(st profile_active)" ""; ok "picture edit: base kept" "$(st profile_base)" one
req apply_profile=one
req oc_gpu=880000
req anim_scale=0.5
ok "anim edit: active cleared" "$(st profile_active)" ""
req update_profile=one
ok "update keeps the overclock and animation keys" "$(grep '^one|' $PF | grep -c 'oc_gpu=880000.*anim_scale=0.5')" 1
ok "update: profile in use again" "$(st profile_active)" one

# update_profile validation
rm -f $PF; : > $S; load; req save_profile=keep
cp $PF $TD/pf.ok; cp $S $TD/state.ok
req update_profile=nosuch
ok "unknown name: profiles unchanged" "$(cmp -s $PF $TD/pf.ok && echo same)" same
ok "unknown name: base unchanged" "$(st profile_base)" keep
ok "unknown name: no new base" "$(grep -c nosuch $S)" 0
req "update_profile=a;reboot"; req 'update_profile=$(reboot)'; req 'update_profile=ke ep'; req "update_profile=keep|x"; req "update_profile="; req update_profile
ok "injection and empty: profiles unchanged" "$(cmp -s $PF $TD/pf.ok && echo same)" same
ok "injection and empty: nothing run" "$([ -f $TD/bad ] && echo ran || echo none)" none
ok "injection and empty: base unchanged" "$(st profile_base)" keep
req update_profile=Keep
ok "case matters" "$(names)" "keep "
req update_profile=kee
ok "prefix is not a match" "$(names)" "keep "
req "profile_base=hacked"
ok "profile_base cannot be requested" "$(st profile_base)" keep
req update_profile=keep
ok "valid update still works" "$(st profile_active)" keep

# picture quality: validation of the new keys and what pq_apply writes (getprop, setprop, stop and start are stubs)
: > $S; load; PQ=$TD/props; : > $PQ; svc=running
getprop() { case $1 in init.svc.pq-2-2) echo $svc;; *) sed -n "s/^$1=//p" $PQ | tail -1;; esac; }
setprop() { grep -v "^$1=" $PQ > $PQ.t; echo "$1=$2" >> $PQ.t; mv $PQ.t $PQ; }
stop() { echo stop >> $TD/pqev; }; start() { echo start >> $TD/pqev; }
for kv in pq_mode=1 pq_mode=3 pq_contrast=10 pq_sat=-1 pq_gamma=15 pq_shp=12 pq_color=4 pq_dre=3 pq_hdr=2; do valid ${kv%%=*} ${kv#*=} && echo "FAIL accepted $kv" && fail=1; done
for kv in pq_mode=2 pq_contrast=9 pq_sat=0 pq_gamma=14 pq_shp=11 pq_color=3 pq_dre=2 pq_hdr=1; do valid ${kv%%=*} ${kv#*=} || { echo "FAIL rejected $kv"; fail=1; }; done
eval "$(sed -n '/^pq_apply() {/,/^}/p;/^pq_prop() /p' $D/device/tablet_tunerd.sh)"
pq_mode=2; pq_contrast=7; pq_sat=; pq_gamma=7; pq_shp=; pq_color=; pq_dre=; pq_vp_dre=; pq_hdr=; pq_ultrares=
pq_apply
ok "pq: mode prop" "$(getprop persist.vendor.sys.pq.picmode)" 2
ok "pq: contrast prop" "$(getprop persist.vendor.sys.pq.contrastidx)" 7
ok "pq: gamma prop" "$(getprop persist.vendor.sys.pq.gamma.index)" 7
ok "pq: unset key leaves its prop alone" "$(getprop persist.vendor.sys.pq.gsatidx)" ""
ok "pq: one restart" "$(tr '\n' ' ' < $TD/pqev)" "stop start "
rm $TD/pqev; pq_apply
ok "pq: same values, no restart" "$([ -f $TD/pqev ] && echo restarted || echo none)" none
pq_contrast=99; pq_apply
ok "pq: bad value never reaches a prop" "$(getprop persist.vendor.sys.pq.contrastidx)" 7
pq_contrast=3; svc=stopped; pq_apply
ok "pq: failed start falls back to factory mode" "$(getprop persist.vendor.sys.pq.picmode)" 0
ok "pq: failed start is saved" "$(st pq_mode)" 0

rm -rf $TD; exit $fail
