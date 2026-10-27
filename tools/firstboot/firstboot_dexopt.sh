#!/system/bin/sh
# Compiles the apps in speed mode by itself after a data wipe. Started once per boot by zz_firstboot_dexopt.rc; does nothing when /data/local/tmp/.dexopt_done exists.
# Pass 1 runs a few minutes after boot, pass 2 about 45 minutes later, after the Play Store has updated Play services, Maps and YouTube (an update resets them to
# interpreted code). Apps that are already compiled are skipped (DEXOPT_FORCE empty). Log: /data/local/tmp/dexopt.log
M=/data/local/tmp/.dexopt_done
[ -f $M ] && exit 0
export DEXOPT_FORCE=""
echo "$(date) firstboot dexopt pass 1" >> /data/local/tmp/dexopt.log
sleep 180
sh /system/bin/dexopt_all.sh >> /data/local/tmp/dexopt.log 2>&1
sleep 2700
echo "$(date) firstboot dexopt pass 2" >> /data/local/tmp/dexopt.log
sh /system/bin/dexopt_all.sh >> /data/local/tmp/dexopt.log 2>&1
touch $M
