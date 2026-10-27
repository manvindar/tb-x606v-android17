# Pixel Launcher on the TB-X606V (Android 17 GSI)

Status: the Pixel Launcher runs as the home app with the 3-button bar (app drawer with blur, "Search your tablet and more", Pixel taskbar, widgets). Recents uses AOSP Launcher3. Gesture navigation does not work yet.

Source APK: the Android 17 build (versionCode 907, API 37) from APKMirror, Google-signed (cert SHA-256 `a86bdb05...`). The Android 16 build in NikGapps cannot start.
It needs a rebuilt copy because this GSI is newer than the APK's framework (`tools/pixel/build_pixel_launcher.sh`):
1. two `DesktopExperienceFlags` enum constants no longer exist -> pointed at an existing one (desktop mode is unused here)
2. `TaskSnapshotManager.takeTaskSnapshot(IZ)` was renamed `takeSystemTaskSnapshot(IZ)`
3. no `SearchUiManager` service on a GSI -> the all-apps search session is skipped (`patch_search_session.py`)
4. five staging framework resource ids moved (`remap_res_ids.py`; staging resources are numbered alphabetically)
5. signed with the public AOSP platform test key (the GSI's platform certificate is that key) so signature permissions (`INTERNAL_SYSTEM_WINDOW`, `MANAGE_ACTIVITY_TASKS`, `READ_FRAME_BUFFER`) are granted
6. `patch_desktop_mode.py`: never keep the shell's `IDesktopMode` binder (`setTaskbarIconPositionLocator` was rejected by this SystemUI)
7. eight more privileged permissions go into `privapp-permissions-nexuslauncher-a17.xml`. Without that file system_server aborts at boot and the tablet sits on the boot animation.

Not working: gesture navigation and the Pixel recents screen. The NikGapps recents overlay (`PixelLauncherOverlay.apk`) makes the Pixel Launcher the recents component.
With it the launcher starts, but the first app launch crashes SystemUI's shell (`RecentsTransitionHandler`: `detachNavigationBarFromApp` gets a null `IRemoteCallback`),
because the launcher's recents/transition calls into the shell no longer match this SystemUI build. `tools/pixel/compare_aidl.py` compares AIDL codes and signatures between the two apps (SystemUI's R8-merged stubs defeat it for ISystemUiProxy).
Expect more mismatches behind that one; each one crashes SystemUI, so it was not pursued. The overlay is therefore not installed.

Install: `tools/pixel/install_pixel.sh PIXEL.apk NikGapps-full-arm64-16-*.zip`, then `cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.nexuslauncher`.
Revert: `cmd role add-role-holder --user 0 android.app.role.HOME com.android.launcher3`, delete `/system/product/priv-app/NexusLauncherRelease` and the three nexuslauncher xml files.

## Other Pixel/Google apps (live install, from the NikGapps Android 16 full zip)
`tools/pixel/stage_pixel.py ZIP OUT_DIR Name...` unpacks packages into a /system/product layout (static overlays and offline-speech data are left out), then
`tools/pixel/check_privapp.py OUT_DIR` dry-runs the boot-time privileged-permission allowlist check against the attached tablet. Run it before pushing anything into a priv-app dir.
Installed and booting: Google Contacts, Calculator, Files, Calendar, Clock, Emoji wallpaper, Wallpaper & style, Sounds.
Google Clock registers under original-package names (`com.android.deskclock`, `com.android.alarmclock`); the first attempt hung the boot until `privapp-permissions-clock-a17.xml` allowlisted its three privileged permissions under all three names (`check_privapp.py` now checks every candidate name).
Gboard did NOT work: it replaced AOSP LatinIME, started, but drew no keyboard (its dynamic feature modules come from Play and are not in the NikGapps base APK: `Module ngi is not available`). Removed; AOSP keyboard restored.
Left out: Dialer and Messages (no telephony), DocumentsUI/StorageManager (framework-critical), At a Glance / Weather (need Device Personalization Services).

## Persistence: what a fresh image has

`build/build_image17.py --pixel NIKGAPPS_ZIP --pixel-launcher-apk PIXEL.apk` rebuilds everything above into the image (the files are laid out exactly as on the tested tablet; `verify_image.py` against the plain image shows only these additions):
- Pixel Launcher (priv-app, platform signed), its two allowlist files and `privapp-permissions-nexuslauncher-a17.xml`, the hidden API whitelist; no recents overlay.
- Wallpaper & style, PixelThemes stub, sound picker (with the two overlays NikGapps ships for them), Google Contacts, Calculator, Files, Calendar, Clock (with `privapp-permissions-clock-a17.xml`), Emoji wallpaper.
- `/system/bin/pixel_home.sh` and `zz_pixel_home.rc`: after `sys.boot_completed`, once per `/data` (marker `/data/local/tmp/.pixel_home_done`), set the HOME role to the Pixel Launcher and disable `com.android.contacts` and the DocumentsUI launcher activity.
  AOSP Launcher3 stays: it is the recents provider. Pick another home app later and the marker keeps this from undoing it.
- The Pixel Launcher APK itself is not in the repository: download the Android 17 build (versionCode 907) from APKMirror and run `tools/pixel/build_pixel_launcher.sh IN.apk OUT.apk`.
- Before the image is written the builder runs `tools/pixel/check_privapp_offline.py` over every priv-app it adds (permissions read from framework-res and SystemUI in the image, allowlists from the image plus the staged xml files; platform signed apps are exempt) and stops on a violation.

Boot hang rule: `/vendor/build.prop` has `ro.control_privapp_permissions=enforce`. A priv-app (anything under `priv-app/`) that requests a `privileged` permission that no `privapp-permissions*.xml` grants it makes system_server throw at boot
(`Signature|privileged permissions not in privileged permission allowlist`), and the tablet stays on the boot animation. adb never comes up. Recovery: TWRP, delete the app, reboot. The failed boot's logcat is in `/data/local/tmp/bootdiag/` when the boot diagnostics service is on.
An app can register under its `original-package` name (Google Clock: `com.android.deskclock`), so the allowlist needs every candidate name.

Not persistent: Gboard (draws no keyboard), gesture navigation (see above), At a Glance, Pixel Weather and the Pixel clock widget (no smartspace service on the GSI), network location (see the README, the Play services APK does not request `INSTALL_LOCATION_PROVIDER`).
