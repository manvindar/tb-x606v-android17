#!/system/bin/sh
# One shot after the first boot with the Pixel layer: the Pixel Launcher becomes the home app and the AOSP Contacts and Files launcher icons are hidden
# (the Google versions are installed next to them). Launcher3 stays installed: it is the recents provider. The marker keeps a later choice of home app from being overridden;
# wiping /data runs this again.
M=/data/local/tmp/.pixel_home_done
[ -e $M ] && exit 0
P=com.google.android.apps.nexuslauncher
i=0
while [ $i -lt 60 ]; do
  if cmd package list packages 2>/dev/null | grep -q "package:$P\$"; then
    cmd role add-role-holder --user 0 android.app.role.HOME $P >/dev/null 2>&1
    cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | grep -q "$P" && break
  fi
  i=$((i + 1)); sleep 5
done
[ $i -ge 60 ] && exit 1
pm list packages 2>/dev/null | grep -q package:com.google.android.contacts && pm disable-user --user 0 com.android.contacts >/dev/null 2>&1
pm list packages 2>/dev/null | grep -q package:com.google.android.apps.nbu.files && pm disable --user 0 com.android.documentsui/com.android.documentsui.LauncherActivity >/dev/null 2>&1
# The Pixel Launcher puts a search bar on the home screen. Without the Google app it picks AOSP's old QuickSearchBox widget (serif "Google" logo in a flat frame); with that package disabled
# it draws its own rounded Pixel search pill instead.
pm disable-user --user 0 com.android.quicksearchbox >/dev/null 2>&1
touch $M
