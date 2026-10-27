# Making it fast

Measured on the tablet (TB-X606V, Android 17 GSI) with `dumpsys gfxinfo`, `simpleperf` and app launch timing. Numbers are from this tablet only.

## What helped

1. **Compile everything in speed mode, Google apps included.** A GSI has no precompiled code for the apps it gets later, and Play services, Maps, YouTube and the Play Store are updated by the Play Store at first boot, which resets them to `verify` (interpreted, JIT only). The owner reported a large improvement after compiling.
   `tools/scripts/dexopt_on_tablet.sh` does it (about 10 to 15 minutes, tablet connected over adb). Check with `dumpsys package <pkg> | grep "arm64: \\[status"`: you want `speed`.
   Redo it after the Play Store updates Play services, Maps or YouTube. Compiled Pixel Launcher and Google apps are not part of the public images (the code is built on the device), so every fresh flash needs this once.
2. **Window blur off.** The Pixel Launcher drawer with blur on: 72% janky frames, median 57 ms. With `persist.sysui.disableBlur=1` (set by the builder; `settings put global disable_window_blurs 1` also works on a running system): 5 to 7% janky, median 19 ms.
3. **USAP pool on.** GSIs ship `dalvik.vm.usap_pool_enabled=false`. The builder sets `true` (pre-forked app processes, faster app start). Needs a reboot.
4. **Launcher3 as the home app.** Median frame 12 ms in the same drawer test, against 18 ms for Lawnchair 15 beta 3 and 19 ms for the Pixel Launcher. Launcher3 is also the recents provider.
5. **ROM Tuner profile.** The `smooth` preset pins the CPU governor and boost values; all eight cores sit at 2.3 and 1.8 GHz, the GPU at 680 MHz, thermal limiting off. Animation speed 0.5 (a tuner setting) makes transitions feel quicker.
6. **No tracing and no logging overhead.** `persist.traced.enable=0` (Perfetto's `traced` crash loops on kernel 4.9 and retriggers a flags script every 5 seconds).

## What did not help

- **Forcing DDR to its top step.** During UI work DVFSRC sits at 1534 MHz and 650 mV. Forcing 3200 MHz and 800 mV (`dvfsrc_force_vcore_dvfs_opp` = 0) left the notification shade at 121 ms per frame. Not memory bandwidth.
- **A different kernel compiler.** The notification shade used under one core's worth of CPU across eight cores while it was slow; kernel time was 12% of the SystemUI profile. The waiting is not compute that a better compiler removes.
- **Vulkan UI rendering** (`debug.hwui.renderer=skiavk`): worse than the default (4.4% vs 2.7% janky in the launcher test).
- **Newer PowerVR driver.** The updatable developer driver (1.14) refuses to talk to the stock kernel's 1.11 module (`DDK_VERSION_MISMATCH` strings in its `libsrv_um.so`).
- **Magisk or PerfMTK.** The build is already rootable over `adb root`; PerfMTK writes sysfs values the tuner already owns, for Mali GPUs.
- **PHH Treble "GED KPI" and backpressure toggles.** They only exist in phh's patched SurfaceFlinger. Google's GSI has no such code.

## Known slow spot

The Android 17 notification shade (Compose scene container) takes about 110 ms per frame on this CPU; one thread, CPU bound. `aflags` cannot turn the flag off and the legacy shade is gone from the build.

## Quick check after a flash

```sh
adb shell 'dumpsys package com.google.android.gms | grep -m1 "arm64: \\[status"'       # speed
adb shell 'getprop persist.sysui.disableBlur; getprop dalvik.vm.usap_pool_enabled'     # 1 and true
adb shell 'dumpsys gfxinfo com.android.launcher3 reset; ...swipe the drawer...; dumpsys gfxinfo com.android.launcher3 | grep -E "Janky|50th|99th"'
```
