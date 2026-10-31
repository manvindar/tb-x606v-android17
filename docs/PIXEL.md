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

## Performance
Two things made the Pixel Launcher sluggish on the GE8320 (measured with `dumpsys gfxinfo`, 8 drawer open/close cycles):
- Window blur: 72% janky frames, median 57 ms. With `persist.sysui.disableBlur=1` (set in build.prop by the builder) it is 5 to 7% janky, median 19 ms. Setting `settings put global disable_window_blurs 1` has the same effect on a running system.
- The launcher APK ships without compiled code (`run-from-apk`), so it runs interpreted until JIT warms up. After flashing, run `tools/scripts/dexopt_on_tablet.sh` (or `cmd package compile -m speed -f com.google.android.apps.nexuslauncher`).

## Pixel Tablet layer (branch exp/pixel-tablet): what it took to boot, in order
Layer: SystemUI (SystemUITitan), Settings (SettingsGoogle), Pixel Launcher from the Pixel Tablet factory image of the same build as the GSI (DP11.261016.003), `tools/pixel/stage_pixel_tablet.py` + `build_image17.py --pixel-tablet`.
1. system_server aborted at boot with `Signature|privileged permissions not in privileged permission allowlist` for the launcher (24 permissions). Platform signing does not exempt an app, and the allowlist must be in the partition of the app (`system_ext/etc/permissions` for a system_ext priv-app): `privapp-permissions-nexuslauncher-pixeltablet-a17.xml`.
2. system_server aborted with `Call to getInstalledModules before metadata loaded`: the Pixel config overlay sets `config_defaultModuleMetadataProvider` to `com.google.android.modulemetadata`, which a GSI does not have. `ModuleMetadataGoogle` (48 KB, product/app) is taken from the Pixel image.
3. SystemUI crashed in a loop (`appComponentFactory ... PhoneSystemUIAppComponentFactory` not found in the new APK) because the package manager kept its parsed copy of the GSI's SystemUI in `/data/system/package_cache` after the system image was replaced without wiping data. Clear `/data/system/package_cache` (and `/data/resource-cache`) when flashing a different system over existing data.
4. The Pixel setup wizard (`com.google.android.pixel.setupwizard`, with the MindTheGapps `com.google.android.setupwizard`) opened but the Wi-Fi page crashed with `SecurityException: WifiService: Permission denied`. `NETWORK_SETUP_WIZARD` has protection level `signature|setup`, which PackageManager only grants to the one package that answers `MAIN` + `SETUP_WIZARD`. The GSI ships AOSP `Provision` as a second answer (`There should probably be exactly one setup wizard; found 2`), so no package was named. Disabling Provision with `pm` is not enough because the check ignores the disabled state. The builder deletes `/system/system_ext/priv-app/Provision/Provision.apk`.

Wizard "Not now" on the screen lock page: the wizard treated it as a cancel and returned to date and time forever (the wizard wrapper hands its manager result 0 although Settings reports a skip). `tools/pixel/wizard_lock_skip.sh` (started at boot until setup is complete) notices date and time showing again right after the lock page and marks setup complete, which lands on the Pixel launcher. Setting a PIN or pattern follows the normal wizard path. The wizard works offline ("Set up offline").

Later findings (October 31):
5. Boot animation squeezed: the Pixel Tablet zip declares a 2560x1600 canvas (landscape) and bootanimation stretches that canvas to the 1200x1920 panel. `fit_bootanimation.py` rewrites `desc.txt` to the panel size and shifts every `trim.txt` offset by the difference of the canvas centres (frames keep their pixel size; the zip entries stay stored, not deflated). The builder applies it to `bootanimation-pixel.zip`, `bootanimation-pixel-dark.zip` and the default slots. Checked by starting `bootanim` on the tablet and taking a screenshot: the G is round.
6. Setup wizard "Not now" loop: Settings logs `RESULT_SKIP` for the lock page, but the wizard manager gets result 0 from its lock screen wrapper and goes back one screen (date and time). `wizard_lock_skip.sh` (service `wizard_lock_skip`, started at boot until `user_setup_complete` is 1) polls the top activity: date and time shown again after `SetupChooseLock*` means the user skipped, so it sets `device_provisioned` and `user_setup_complete` to 1, stops both wizard packages and starts the home screen. Tested from a fresh flash. A PIN or pattern follows the normal wizard path (the PIN screens are `FLAG_SECURE`: screenshots of them are black).
7. Compiled code (`docs/PERFORMANCE.md`): SystemUI, Settings and the launcher ran interpreted on every earlier Pixel image. Fixed by the `/data/odex` bind mounts (the builder adds an `oat` directory to every app directory) and by deleting the stale dalvik-cache copies in `dexopt_all.sh`.
8. Camera: the tablet keeps AOSP Camera2 (`com.android.camera2`). Pixel Camera is about 500 MB and needs Pixel camera libraries.
9. Gboard draws its keyboard on this build (the note above that it did not is from the first layer, before the Pixel Tablet SystemUI).
10. Google sign in loop: after adding an account, `AccountAddedActivity` -> `WrapperControlledActivity` -> `GoogleServicesActivity` -> `com.google.android.apps.restore.backup.OPT_IN` (`GeneralOptIn`, `NavRootActivity`) -> `AccountAddedActivity` again, indefinitely, so the Google services options never stayed on screen. Disabling `com.google.android.apps.restore` (done once by `pixel_home.sh`) keeps `GoogleServicesActivity` up. Cost: the Google One backup opt-in is not offered. Cause of the loop in the backup opt-in itself not found.

## Hub mode, clocks, theme packs, Device Health Services (added after the last boot test, nothing here has been booted yet)
All from the same Pixel Tablet image as the rest of the layer (`stage_pixel_tablet.py`; `--only TEXT` stages just some entries into an existing stage dir). About 93 MB of raw APK; the system image (`a17p13-gapps.img`) has 8.2 MB free after it, so nothing more fits without dropping something.
- Hub mode: `product/priv-app/SmartDisplayPrebuilt` (`com.google.assistant.hubui`, 36.7 MB, dreams: clocks, weather clock, personal and curated photos, Froggy) and the overlays `GlanceableHubConfigOverlay`, `GlanceableHubSettingsConfigOverlay`, `GlanceableHubSysuiConfigOverlay`, `TitanSettingsOverlay`, `TitanSysuiConfigOverlay`.
  All five are static overlays with no property gate (no `requiredSystemProperty`), so they load on their own. Privileged permissions `BIND_APPWIDGET` and `MEDIA_CONTENT_CONTROL`: the Pixel's `com.google.assistant.hubui.xml` (copied with the product permissions) already grants them, `privapp-permissions-hub-a17.xml` repeats them so a release build does not depend on that copy.
- Not taken: `TitanConfigOverlay` (forces `config_navBarInteractionMode=2`, gesture navigation, which crashes on this build, plus pinner files and user types), `TitanSettingsProviderOverlay`, `TitanNexusLauncherOverlay`. Its Hub defaults are set by `tools/pixel/pixel_home.sh` instead, once per `/data` (marker `/data/local/tmp/.pixel_hub_done`, separate from the home marker so a flash over old data still gets it):
  `glanceable_hub_enabled=1`, `screensaver_enabled=1`, `screensaver_components=com.google.assistant.hubui/com.google.assistant.hubui.dream.ClockFacesDreamService`, `screensaver_activate_on_sleep=1`, `screensaver_activate_on_dock=1` (secure settings). The component names come from the hubui manifest and from `config_preinstalled_dream_components` in the Settings overlay. There is no dock on this tablet, so Hub mode starts while charging.
- Lock screen clocks: `system_ext/priv-app/SystemUIClocks-{BigNum,Calligraphy,Growth,Inflate,Metro,NumOverlap,Weather}` (17.6 MB). SystemUI plugins, they ask only for `com.android.systemui.permission.PLUGIN`, no privileged permission.
- Theme packs: `CustomizationBundlePrebuilt` (priv-app, 14.2 MB) and `WallpaperEmojiPrebuilt` (app, 12.4 MB).
- Device Health Services: `TurboPrebuilt` (product priv-app, 14.6 MB) and `TurboAdapter` (system_ext priv-app, 0.26 MB). TurboAdapter asks for `BATTERY_STATS` and `DUMP`; the Pixel keeps that entry in `system_ext/etc/permissions`, which is not staged, so `privapp-permissions-turbo-a17.xml` goes into `system_ext/etc/permissions`. Its JNI library `libpowerstatshaldataprovider.so` (`system_ext/lib64`) is linked from `lib/arm64` of the app as on the Pixel.
Unverified: whether SystemUI accepts the clock plugins (Google platform signature, not the AOSP key), whether the Hub dream starts (it needs the SystemUI Hub support of the Pixel build and widgets from Google apps), whether Turbo works without a Pixel power stats HAL (`android.hardware.power.stats` may be missing from the MediaTek vendor), the dock service components named in `TitanSysuiConfigOverlay` (Google dock apps are not installed), and whether the image booted at all.
