# HAG-43: ZUI SystemUI on the TB-X606V (plan, host only, no port yet)

Status: **blocked on donor firmware.** No ZUI bytes are in this repository or on the build host. Nothing below has been built or run.

## Donor (owner choice: Legion Tab 3), verified from OTA metadata read remotely (range requests, nothing downloaded in full)
- OTA: `https://ota-cdn.lenovo.com/firmware/20268269332765-8594.zip`, 3,450,675,045 bytes, Last-Modified 2026-08-26 (link from XDA thread 4799809).
- `post-build=Lenovo/TB321FU/TB321FU:17/CQ2A.260712.001-CP2A.260605.016/TB321FU_ROW_OPEN_USER_Q29300.0_A17_ZUI_18.5.02.063_DEV_260824:user/release-keys`, `post-sdk-level=37`, security patch 2026-08-05. So: TB321FU (Legion Tab 3 / Y700 2025), ROW, Android 17 (SDK 37), ZUI 18.5.02.063, a **DEV** build.
- payload.bin FILE_HASH (base64 SHA-256) `NGmztVUDSFf6EJSxfIN1P0VZt2E+H4HhqE7jmfw80hk=`, METADATA_HASH `FhxbjOfya6phFT9ZWuO2tqwi48T95mYZ4/FilkYnxoI=`.
- **It is an incremental OTA**, not a full image: the payload manifest uses SOURCE_COPY, BROTLI_BSDIFF and PUFFDIFF for system (5,988,515,840 B), system_ext (585,515,008 B) and product (4,996,440,064 B). Applying it needs the exact base, `pre-build` ZUI 17.5.10.272 ROW (Android 16).
- Base full firmware: `https://mirrors.lolinet.com/firmware/lenowow/2024/Y700_2025/TB321FU/TB321FU_ROW_OPEN_USER_Q00002.0_W_ZUI_17.5.10.272_ST_260625.zip` (7,123,941,403 bytes, QFIL layout; super split into `super_1..8.img` with `rawprogram*.x`). Not yet hashed.
- Target here: Android 17 GSI DP11.261030.003 (SDK 37, a preview build). The donor is a later CQ2A build; framework drift between them is expected and must be measured.

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

## Storage need and where to do it
Needed: base firmware (7.1 GB zip; the super chunks alone about 6.8 GB compressed, ~10.7 GB raw) plus the OTA (3.45 GB), then rebuilt A17 system, system_ext and product (~11.6 GB). Host free space: 3.9 GiB, so this cannot run on the build host. Plan: a GitHub Actions job on this branch (runner disk ~70 GB after cleanup) that downloads both files, checks sizes and the OTA FILE_HASH, unpacks super (lpunpack), applies the incremental payload to system, system_ext and product, and uploads only SystemUI.apk, framework jars, framework-res and build.prop files plus the compatibility report as a private-scope artifact. No ZUI binary is committed or published.

## Not authorized / not done
No firmware download, no device write, no flash. No ZUI binaries will be committed (not redistributable).
