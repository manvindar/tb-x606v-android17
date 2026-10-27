# Android 17 on the Lenovo TB-X606V: how far it gets

An experiment to run Google's Android 17 GSI (`aosp_arm64`, build DP11.261002.005) on the Lenovo Tab M10 FHD Plus (TB-X606V,
MT8768) with its stock Android 10 vendor image and the stock 4.9.190 kernel. **It reaches the UI, with open issues listed below.** This repository holds the build
recipe, the patches and the findings, so the work can be picked up or abandoned with the facts in hand.

Companion repositories:
- [`tb-x606v-kernel`](https://github.com/manvindar/tb-x606v-kernel): the 4.9 kernel patches and CI build used here.
- `tb-x606v-android16-port`: the Android 16 build that does run on this tablet (the known good fallback).

## Status

As of 2026-10-27 it boots to the UI on the stock 4.9.190 kernel with a patched Android 17 userspace. adb works with root, `sys.boot_completed=1`,
and Settings and the launcher open. It is slow (load average ~25 right after boot, long first-boot delays) and not usable day to day yet.

| Stage | State |
|---|---|
| Stock kernel 4.9.190 boot image, first-stage `init`, SELinux (permissive), APEXes | works |
| eBPF loader, `netd`, ConnectivityService (bind-mounted patched binaries and jars) | works |
| `zygote`, `system_server` (stable for minutes), SurfaceFlinger, PowerVR GPU, SystemUI, Launcher3, Settings | works |
| Power HAL, PQ HAL, camera/sensor service startup (libbinder frozen-callback patch) | works |
| Bluetooth | in progress: HCI 0x0c5a status ignored and the SSP check nopped; not yet verified that the stack turns on |
| Audio output | not working: `audioserver` is stable but has no outputs (needs a 5.0 client/HAL shim) |
| Camera capture | not verified |
| lmkd | replaced by a small in-kernel-LMK daemon (`tools/lmkd`); works |
| Brightness | works through a Java AIDL lights shim (`tools/lights`) |
| Wi-Fi | connects (BpfNetMaps local-net methods stubbed) |
| Speed | needs `tools/scripts/dexopt_all.sh` once; Settings then opens in under a second |

The earlier attempt on the OSS 4.9.117 kernel is abandoned: its PowerVR driver rejects the vendor GPU firmware. Details of every
patch and the order they were found are in `docs/ANALYSIS.md`.

## What was needed to get this far

Each item is a failure seen in a real boot log; `docs/ANALYSIS.md` has the evidence and the order they appeared in.

Kernel (see the kernel repo):
1. `F_SEAL_FUTURE_WRITE` backport (needed by Android 16's libhwui already).
2. `MADV_WIPEONFORK` backport: Android 17's libc aborts at startup without it, so every process died.
3. `mtk_ram_console`: clear the garbage `_pa` pointer when the SRAM mapping fails, and `fiq_cache_init` skipped, or the boot oopses at 0.6 s.
4. cgroup2: ignore mount options (`memory_recursiveprot`); v1 cpuset: accept `cpuset_v2_mode` (as `clone_children`).
5. Device-tree bootargs: `cpuidle.off=1` (NULL deref in MediaTek MCDI idle after ~10 s) and `printk.devkmsg=on` (the kernel drops `init`'s messages after ten otherwise).

System image (`build/build_image17.py`):
1. `init` patched to boot permissive (the GSI is a user build, so the Android 16 patch does not apply).
2. VNDK v29 APEX copied from Android 16 (the GSI ships v31 to v34 only; the Android 10 vendor links against v29).
3. SELinux `mapping/29.0.cil` for the Android 10 vendor policy, copied from Android 16 and pruned of types Android 17 removed.
4. `cgroups.json` without `blkio`; `com.android.virt.apex` removed (EROFS, no kernel support); `lmkd` replaced by an idle stub (needs PSI).

Vendor partition (`tools/fix_vendor_a17.py`): the legacy `forcefdeorfbe=` and `resize` fstab flags are a parse error in Android 17,
so `/data` never mounted. Android 16 ignored them, so the edit is neutral there.

## Using this

You need: a TB-X606V with an unlocked bootloader, TWRP in `recovery`, `adb`/`fastboot`, e2fsprogs, and **a backup of the stock `boot`
partition** (this experiment writes `boot` on every kernel test). Flashing wipes userdata.

```sh
python3 build/build_image17.py --gsi system.img --a16 system-a16.img --out system-a17.img   # GSI from developer.android.com
fastboot flash system system-a17.img
python3 tools/fix_vendor_a17.py                                  # from TWRP
STOCK=stock-boot.img REPACK=../tb-x606v-kernel/scripts/repack_boot.py tools/flashboot.sh <kernel-run-id> dtb.bin
tools/readlog.sh                                                 # after a failed boot: kernel console + init messages
```

`tools/restore_stock_boot.md` explains how to recover a tablet that no longer reaches TWRP or fastboot (MediaTek BROM with mtkclient).
`fastboot boot` is ignored by this bootloader, so there is no RAM-only way to test a kernel.

## Not in this repository

Google's GSI zip (its licence forbids redistribution), the Lenovo stock images and the vendor `fstab` are not included; the scripts
derive what they need from your own copies. Both the Android 17 image and the Lenovo files are the property of their owners.
