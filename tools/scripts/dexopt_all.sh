for p in com.google.android.gms com.android.vending com.google.android.gsf; do echo $p; cmd package compile -m verify -f $p 2>/dev/null; done
for p in com.android.settings com.android.systemui com.android.launcher3 com.android.inputmethod.latin com.android.phone com.android.permissioncontroller com.android.providers.media.module com.android.providers.settings; do echo $p; cmd package compile -m speed -f $p; done
cmd package compile -m speed -a
echo DONE
