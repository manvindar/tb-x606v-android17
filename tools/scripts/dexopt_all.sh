# Runs on the tablet (sh). Compiles the apps in speed mode: Google apps first (Play services is the biggest background user; 'verify' leaves it
# interpreted), then the system apps, then everything else. About 10 to 15 minutes. DEXOPT_FORCE="" skips apps that are already compiled
# (the first-boot job uses that); the default -f recompiles.
F=${DEXOPT_FORCE--f}
for p in com.google.android.gms com.android.vending com.google.android.gsf com.google.android.apps.maps com.google.android.youtube com.google.android.apps.wallpaper com.google.android.apps.nexuslauncher com.google.android.contacts com.google.android.calendar com.google.android.calculator com.google.android.deskclock com.google.android.apps.nbu.files; do
  pm path $p >/dev/null 2>&1 && { echo $p; cmd package compile -m speed $F $p; }
done
for p in com.android.settings com.android.systemui com.android.launcher3 com.android.inputmethod.latin com.android.phone com.android.permissioncontroller com.android.providers.media.module com.android.providers.settings; do echo $p; cmd package compile -m speed $F $p; done
cmd package compile -m speed -a $F
echo DONE
