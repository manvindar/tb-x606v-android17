# HAG-43: ZUI SystemUI on the TB-X606V (plan, host only, no port yet)

Status: **blocked on donor firmware.** No ZUI bytes are in this repository or on the build host. Nothing below has been built or run.

## Donor: must be verified before selection
Candidates from public pages only (not verified: no firmware downloaded, no build.prop read):
| Candidate | Claimed build | Source of the claim | Verified? |
|---|---|---|---|
| TB520FU ("lapis") | ZUI 18.5.10.037, said to be Android 17 | PixelOS release notes, github.com/lenovo-sm8650/android_device_lenovo_lapis, 2026-11-17 | No |
| TB321FU (Legion Tab 3) | A17_ZUI_18.5.02.063_DEV_260824 OTA | XDA thread 4799809 title (page returned 403) | No |

A ZUI version number does not establish the Android level. Before choosing, read from the donor's `system/build.prop` and `SystemUI.apk`: `ro.build.version.sdk`, `ro.build.version.release_or_codename`, `ro.build.fingerprint`, `ro.product.model`, the APK's `compileSdkVersion`/`targetSdkVersion` and its signing certificate.
Target framework here: Android 17 GSI DP11.261030.003 (SDK 37, preview). A donor on a different API level or a ZUI-modified framework is expected to fail.

## Why this is harder than the Pixel Launcher
SystemUI is not a standalone app: it is platform signed, persistent, and calls hidden framework APIs and the WM Shell. `docs/PIXEL.md` already records a launcher/shell mismatch crashing this SystemUI. ZUI SystemUI is built against ZUI's modified framework (Lenovo services, `com.zui.*` / `lenovo.*` classes and permissions, ZUI resources), none of which exist on the GSI.

## Compatibility checks (host, once donor bytes exist)
1. Identity: the build properties above; donor model and region recorded with SHA-256 of every input.
2. Framework references: `tools/pixel/check_dex_refs.py` (dex method/field references against this GSI's framework jars) to list missing classes/methods; count `com.zui`, `lenovo`, `zui` references.
3. Shared libraries and `uses-library` entries the APK needs; ZUI framework jars (if any) and whether they depend on ZUI services.
4. Permissions: `tools/pixel/check_privapp_offline.py` against the image (boot-hang rule in `docs/PIXEL.md`).
5. Framework resource ids: `tools/pixel/remap_res_ids.py` approach for staging ids; ZUI `framework-res` overlays it may need.
6. AIDL/binder interfaces between SystemUI, WM Shell and the launcher (`tools/pixel/compare_aidl.py`).
Decision rule: if (2) shows ZUI-only framework classes on startup paths, stop and report; do not stub a framework.

## Integration approach (only if the checks pass)
- Replace `/system_ext/priv-app/SystemUI` in an image built by `build/build_image17.py` (both editions per AGENTS.md: non-Pixel and Pixel), platform-signed with the AOSP test key like the Pixel Launcher, with an allowlist xml.
- Keep the AOSP SystemUI APK in the repository flow as the revert path; host checks before any image.

## Storage need
Full ZUI firmware: several GB (size unknown until a source is chosen); extraction of system/system_ext: several GB more. Host free space at planning time: 3.9 GiB, not enough. Only SystemUI.apk, framework jars and framework-res are needed; an OTA payload can be extracted partition by partition (`payload-dumper` style) to keep it small.

## Not authorized / not done
No firmware download, no device write, no flash. No ZUI binaries will be committed (not redistributable).
