# Android 17 on the Lenovo TB-X606V

Google's Android 17 GSI (`aosp_arm64`, build DP11.261002.005) running on the Lenovo Tab M10 FHD Plus (TB-X606V, MT8768), with the stock
Android 10 vendor image and the stock 4.9.190 kernel. It boots to a working UI. Wi-Fi, Bluetooth, audio, camera, brightness and the keyboard work, and
Settings opens in under a second once the apps are compiled. Cellular, USB mode switching and DRM playback do not work (see "Known issues").

This repository holds the patches, helper tools and notes. It is not a turnkey build: most of the fixes were applied to a live system
over adb and TWRP, and `build/build_image17.py` only reproduces the early part of that (see "Using this").

Companion repositories:
- [`tb-x606v-kernel`](https://github.com/manvindar/tb-x606v-kernel): the 4.9 kernel patches and CI build. The final setup does not use it (see below).
- [`tb-x606v-android16-port`](https://github.com/manvindar/tb-x606v-android16-port): the Android 16 build for this tablet, the known good fallback. It uses the same Gemini boot animation by default.

## Status (2026-10-27)

| Area | State |
|---|---|
| Boot, `init`, SELinux (permissive), APEXes, `zygote`, `system_server` | works |
| eBPF loader, `netd`, ConnectivityService | works (patched binaries and jars bind-mounted over the APEX copies) |
| GPU, SurfaceFlinger, SystemUI, launcher, Settings | works |
| Wi-Fi | connects and stays connected |
| Camera | works (video encoding untested; the MediaTek encoders log "unsupported parameter") |
| Brightness | works, through a Java AIDL lights shim (`tools/lights`) |
| Keyboard (AOSP LatinIME) | works |
| Low memory killer | works, through a small replacement `lmkd` (`tools/lmkd`) |
| Bluetooth | works: turns on, stays up, scans and pairs (A2DP headphones not tried) |
| Audio output | works through Android 16's `audioserver` and library closure run privately (`tools/audio`); a 440 Hz test tone plays, other apps not yet checked |
| Boot animation | Pixel 10 Gemini animation fitted to the panel (`tools/boot-animation`); see below |
| Cellular (calls, SMS, mobile data) | not working, see below |
| USB file transfer | works (checked with libmtp); tethering untested |
| DRM (Widevine) | not provisioned |

First boot is slow until the apps are compiled. The GSI ships no odex, so every app runs from its APK. Run `tools/scripts/dexopt_all.sh`
once (it takes a few minutes): Settings goes from about 50 s to under a second cold.

## Known issues

From a read of the logs after a clean boot (no crashes, ANRs or service restarts):

- **Cellular.** The phone process reports "out of service" with an unknown radio HAL version, and Settings throws `UnsupportedOperationException` for the IMEI. Android 17's telephony does not
  accept the Android 10 vendor radio HAL (`mtkfusionrild`). Needs a SIM to confirm; fixing it is a project of its own.
- **USB.** `UsbDeviceManager` logs "usb gadget hidl hal service not found" (the vendor only has `android.hardware.usb@1.1`, port roles) and falls back to its legacy handler, which drives `sys.usb.config`.
  Checked: switching to file transfer works, and `libmtp` on the Mac lists the tablet's storage and folders. USB tethering is untested.
- **Widevine** fails to provision (`ERROR_DRM_CANNOT_HANDLE`), so protected streaming will probably not play.
- **Harmless noise.** fs-verity setup fails on every permissions write (the kernel has no verity), the GPU memory BPF map and `bpf_system_stats` ringbuf iterator are missing, and fingerprint, face and a
  few other managers are absent on this hardware. The Wi-Fi data-stall monitor logs "ignore this poll" constantly.
- **Load average** sits around 25. It is MediaTek kernel threads in uninterruptible sleep, not CPU use (the cores are about 95% idle).
- `dumpsys bluetooth_manager` crashes the Bluetooth process (`ControllerImpl::Dump`); debug only.
- **A2DP.** The audio policy asks for an `a2dp` module that the 5.0 HAL does not have, so Bluetooth headphones are untested.

## Boot animation

The default is the Pixel 10 Gemini animation from [docbt/GeminiBootAnimation](https://github.com/docbt/GeminiBootAnimation) (MIT, Copyright (c) 2026 docbt). The original is 1080x2340;
`tools/boot-animation/make_gemini_fit.py` scales it to the 1200x1920 panel, centres it on black and reduces it to a 128-colour palette (13 MB, stored zip). Install it as
`/system/product/media/bootanimation.zip`. Other candidates that were previewed: ArrowOS (the leftover from the Android 16 customisation), a generated galaxy, the Lineage original,
and Pixel-style and Lenovo Yoga Smart Tab animations from the [Android Boot Animation Collection](https://github.com/tntapple219/Android-Boot-Animation-Collection) (unlicensed, not included).

## Serial console warning

The GSI image is built debuggable here, so `init.rc` started the UART `console` shell and ActivityManager posted "Serial console enabled, performance is impacted" at every boot
(it checks `init.svc.console`). `init.rc` now starts the console only when `persist.sys.console=1`. The ROM Tuner (Behaviour > Serial console shell) switches it on and off at runtime;
`build/build_image17.py` applies the same `init.rc` edit.

## Why the stock kernel

The Lenovo open-source 4.9.117 kernel was tried first and dropped: its PowerVR driver rejects the vendor GPU firmware, so the display never comes
up. The stock 4.9.190 boot image works, so Android 17 userspace is patched to run on it instead. The kernel repo records the earlier path.

## What had to be fixed

Each item came from a real failure. `docs/ANALYSIS.md` has the evidence and the order they showed up in.

- **libc and the linker** abort at startup without `MADV_WIPEONFORK`; the madvise failure branch is patched out (`tools/libc/patch_libc.py`). The same folder
  patches `libui` (an API-level gate on the HIDL mapper fallback), `libmeminfo` (aborts on a missing gpuMem BPF map) and `libbinder`
  (the frozen-state callback ioctl does not exist on 4.9). `patch_pgsetup.py` fixes the cgroup2 mount options.
- **BPF.** `netbpfload`, `netd` and `libservice-connectivity` check for kernel 4.14 and newer map types. `tools/bpf` patches them, converts the LRU maps in
  `netd.o`, wraps the loader in a ptrace shim that sets `bpf.progs_loaded`, and bind-mounts everything over the APEX paths from `00_bpf_bind.rc`.
- **`service-connectivity.jar`.** The local-net and loopback BPF maps cannot exist on 4.9. `tools/framework/patch_service_connectivity_jar.py` skips them and turns the
  methods that touch them into no-ops. The first version only wrote the first code unit of each method and failed verification; `return-void` plus a `nop` is right.
- **`services.jar`** (`patch_services_jar.py`): a NullPointerException in `HintManagerService` with the old power HAL, a camera event subscription, and the
  `Build.isBuildConsistent()` dialog that the old vendor can never satisfy.
- **APEXes** that had to be edited are re-signed with a local AVB key and APK-signed (`tools/apex`).
- **Timeouts.** `ro.hw_timeout_multiplier=5` stops the activity manager and SystemUI from timing out on this CPU.
- **Vendor.** `/dev/ion` permissions, seccomp policies, ueventd (`tools/vendor`) and the fstab flags Android 17 rejects (`tools/fix_vendor_a17.py`).
- **Audio.** The vendor audio HAL is HIDL 5.0, which Android 17 no longer supports, and a 5.0 client from Android 16 does not fit A17's vtables (the 6.0 client has a different wire format: the offload info struct grew and methods were inserted). So `audioserver` and its roughly 164 libraries come from Android 16 and run from `/system/lib64/a16audio` through `LD_LIBRARY_PATH`. A17's clients differ in a few AIDL transaction numbers, so `tools/audio/fix_aidl_codes.py` rewrites them in the two A17 AIDL libs.
- **Low memory killer.** The Android 17 `lmkd` needs PSI or memcg, which 4.9 does not have. The idle stub that replaced it never listened on its socket, and the
  activity manager then slept and retried on every process change, which blocked it for seconds and made the watchdog restart `system_server`. The replacement
  speaks the lmkd protocol and drives the old in-kernel killer.
- **Brightness.** Android 17 only talks to the AIDL lights HAL and the vendor only has HIDL. The shim registers `android.hardware.light.ILights/default`
  (declared in a vendor VINTF fragment) and writes `/sys/class/leds/lcd-backlight/brightness`.
- **Bluetooth** (`tools/bpf/patch_bluetooth.py`): the controller answers an optional command with an unknown-command status that the stack treats as fatal, and
  then a check for Secure Simple Pairing aborts. Both branches are patched.

## Using this

You need a TB-X606V with an unlocked bootloader, TWRP in `recovery`, `adb` and `fastboot`, e2fsprogs, and **a backup of the stock `boot` partition**.
Flashing wipes userdata. Do not touch `recovery`.

`build/build_image17.py` builds the patched system image from the GSI and an Android 16 image (for the VNDK 29 APEX and SELinux mapping). The later fixes were
applied live: after the image boots, run the scripts and patchers in `tools/` (the bind-mounted files go to `/system/etc/bpf.a17/`, the init rc files to
`/system/etc/init/`), then `tools/scripts/dexopt_all.sh`. If you try this, expect to read `docs/ANALYSIS.md`.

`tools/restore_stock_boot.md` explains how to recover a tablet that no longer reaches TWRP or fastboot (MediaTek BROM with mtkclient).

## Not in this repository

Google's GSI, the Lenovo stock images and the vendor files are not included, and the patchers read them from your own copies. The Android and Lenovo files belong to their owners.
This is a personal experiment with no warranty: it can leave the tablet unbootable.
