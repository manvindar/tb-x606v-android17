# Google apps first, in speed mode (Play services is the biggest background user; 'verify' leaves it interpreted). About 10 to 15 minutes in total on this tablet.
for p in com.google.android.gms com.android.vending com.google.android.gsf com.google.android.apps.maps com.google.android.youtube com.google.android.apps.wallpaper com.google.android.apps.nexuslauncher com.google.android.contacts com.google.android.calendar com.google.android.calculator com.google.android.deskclock com.google.android.apps.nbu.files; do
  pm path $p >/dev/null 2>&1 && { echo $p; cmd package compile -m speed -f $p; }
done
for p in com.android.settings com.android.systemui com.android.launcher3 com.android.inputmethod.latin com.android.phone com.android.permissioncontroller com.android.providers.media.module com.android.providers.settings; do echo $p; cmd package compile -m speed -f $p; done
cmd package compile -m speed -a
echo DONE
