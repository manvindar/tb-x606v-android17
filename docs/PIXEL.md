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
