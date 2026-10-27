# Android 17 on the Lenovo TB-X606V

Google's Android 17 GSI (`aosp_arm64`, build DP11.261002.005) running on the Lenovo Tab M10 FHD Plus (TB-X606V, MT8768), with the stock
Android 10 vendor image and the stock 4.9.190 kernel. It boots to a working UI. Wi-Fi, camera, brightness and the keyboard work, and
Settings opens in under a second once the apps are compiled. Audio output does not work yet.

This repository holds the patches, helper tools and notes. It is not a turnkey build: most of the fixes were applied to a live system
over adb and TWRP, and `build/build_image17.py` only reproduces the early part of that (see "Using this").

Companion repositories:
- [`tb-x606v-kernel`](https://github.com/manvindar/tb-x606v-kernel): the 4.9 kernel patches and CI build. The final setup does not use it (see below).
- `tb-x606v-android16-port`: the Android 16 build for this tablet, the known good fallback.

## Status (2026-10-27)

| Area | State |
|---|---|
| Boot, `init`, SELinux (permissive), APEXes, `zygote`, `system_server` | works |
| eBPF loader, `netd`, ConnectivityService | works (patched binaries and jars bind-mounted over the APEX copies) |
| GPU, SurfaceFlinger, SystemUI, launcher, Settings | works |
| Wi-Fi | connects and stays connected |
| Camera | works |
| Brightness | works, through a Java AIDL lights shim (`tools/lights`) |
| Keyboard (AOSP LatinIME) | works |
| Low memory killer | works, through a small replacement `lmkd` (`tools/lmkd`) |
| Bluetooth | works: turns on, stays up, scans and pairs (audio profiles not tried, since there is no audio output yet) |
| Audio output | not working: `audioserver` is stable but has no outputs (the HAL is 5.0, Android 17 expects newer) |
| Mobile data and telephony | not tried |

First boot is slow until the apps are compiled. The GSI ships no odex, so every app runs from its APK. Run `tools/scripts/dexopt_all.sh`
once (it takes a few minutes): Settings goes from about 50 s to under a second cold.

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
- **Audio.** Android 16's `libaudiohal@5.0` is copied in; a stub keeps `audioserver` from crashing, but there are still no outputs.
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
