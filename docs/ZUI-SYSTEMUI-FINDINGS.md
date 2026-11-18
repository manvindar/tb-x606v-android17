# ZUI SystemUI port: donor findings (HAG-43, 2026-11-18)

Host analysis only. Nothing was installed or run on a tablet. The donor binaries stay private and local, and none are in this repo.

## Donor and extraction
- Donor: Legion Tab 3 (TB321FU) A17 OTA `ZUI_18.5.02.063_DEV`, applied to base `ZUI_17.5.10.272_ST` (both pinned by observed size and sha256; OTA `FILE_HASH` recomputed and equal).
- Base super layout: the QFIL `rawprogram*.x` files are encrypted. The LP metadata in `super_1.img` shows each `super_N.img` is one whole logical partition (super_3 product_a, super_4 system_a, super_6 system_ext_a, by exact size). Proof: the base sha256 of all three equals the OTA manifest's `old_partition_info`.
- `ota-apply`: whole-image sha256 of the three targets does **not** equal `new_partition_info`, because the tool does not write the verity hash tree or FEC. `tools/zui/verity_check.py` recomputes the dm-verity root over each target's data region and **matches the root digest in its AVB footer for all three**, so every data block up to `image_size` is the signed content. Not compared: the hash tree bytes, the FEC bytes, the footer vbmeta (algorithm NONE, signed by `vbmeta_system`) and the gaps between them.

## ZuiSystemUI against the GSI DP11.261030.003 framework
`/system_ext/priv-app/ZuiSystemUI/ZuiSystemUI.apk`: `com.android.systemui`, sdk 37, `sharedUserId android.uid.systemui`, persistent, coreApp; 3 dex files, no native libraries.

| Check (tool) | Result |
|---|---|
| Types (`check_dex_refs.py`, dex 041 aware) | 81 missing, all defined in Lenovo's modified `framework.jar` (Zui*/Ov* managers, embedding/desktop, Motorola dragdrop/screenhelper, lgsi, lightring, `IBiometricSysuiReceiver`) |
| Methods/fields on classes the GSI does have | 173 missing: 23 named for vendor features; the rest Lenovo additions (hanging mode, scale bounds, long screenshot) or A17 release vs DP11 drift |
| Binder services looked up by name and absent from the GSI | `zuisecurity`, `ov_desktop_service`, `lightring` (SystemUI); `zuimode`, `zuistandbymode`, `omsvc`/`onemodeservice`, `motoscreenhelper` (Lenovo framework managers). A lower bound: only string-constant lookups are seen. |
| Required library | `moto-settings` (`required=true`); the APK does not install without it |
| Signing | Lenovo platform cert; the GSI SystemUI uses the AOSP test platform key (`c8a2e9bc...`). It must be re-signed for the shared UID. |
| Permissions | 34 permissions only the donor requests (Lenovo/ZUI/Motorola custom plus privileged ones such as `FORCE_STOP_PACKAGES`, `KILL_UID`, `MANAGE_FINGERPRINT`) |
| Overlays | 3 static overlays targeting `com.android.systemui` (`SystemUIResCommon_Sys`, `KirbyRowZuiSystemUIOverlay`, `LgsiSepicalSystemUIOverlay`) |

## Why an APK-local shim cannot make it start
- **System-server contracts.** Missing methods sit on Binder interfaces that `system_server` implements: `IWindowSession` (`addToDisplay` has a different signature; Motorola drag/drop and touch explorer registration; `setZuiWallpaperZoom`), `IActivityTaskManager` (task scale bounds, remote transition delegate), `IApplicationThread` (long screenshot), `IWindowManager`. A class inside the APK cannot add a method to a remote service.
- **Fields on boot classes.** `ActivityManager.RunningTaskInfo`/`TaskInfo` are referenced with more than 20 Lenovo fields (`isHangingMode`, `scaleX`, `restoreBounds`, `freeformInitTaskBounds` and others). The boot classpath already defines these classes, so an app-local copy never loads, and the GSI `system_server` never fills the fields. The WM Shell bundled in SystemUI reads task info from boot onward, so these reads are on startup paths, not on optional UI.
- Stubbing the managers to return success would hide all of this, not fix it.

By the plan's decision rule (stop when ZUI-only framework contracts are on startup paths), **a drop-in ZuiSystemUI port onto the GSI is blocked.** The GSI SystemUI stays as the working default and fallback.

## Paths that remain
1. **ZUI look on the GSI SystemUI (recommended):** port resources (icons, colours, QS tiles and shade layout values, clock and fonts) as RRO overlays on the GSI SystemUI, and keep the GSI code. Doable on the host, then built in both editions.
2. **Full ZUI framework port:** replace `framework.jar`, `services.jar` and the boot image with Lenovo's, add their system services, and drop the GSI. That is a whole-ROM port from a Qualcomm SDK 37 release build onto an MT6765 vendor, far larger than a SystemUI port and outside this task.

## Tools
`tools/zui/check_dex_refs.py` (types plus resolved methods/fields, dex 041), `tools/zui/verity_check.py` (AVB hashtree root), `tools/zui/ota-apply/`, `tools/zui/donor_super.py`. Each has a `--demo` self-check (except ota-apply).
