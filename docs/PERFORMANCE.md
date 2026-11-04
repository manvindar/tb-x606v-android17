# Making it fast

Measured on the tablet (TB-X606V, Android 17 GSI) with `dumpsys gfxinfo`, `simpleperf` and app launch timing. Numbers are from this tablet only.

## What helped

1. **Compile everything in speed mode, Google apps included.** A GSI has no precompiled code for the apps it gets later, and Play services, Maps, YouTube and the Play Store are updated by the Play Store at first boot, which resets them to `verify` (interpreted, JIT only). The owner reported a large improvement after compiling.
   The image now does this by itself one minute after the first boot (`firstboot_dexopt.sh`); `tools/scripts/dexopt_on_tablet.sh`, `dexopt_all.sh` and the ROM Tuner "Compile apps now" button run it by hand (about 10 to 15 minutes). Check with `dumpsys package <pkg> | grep "arm64: \\[status"`: you want `speed`.
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

The Android 17 notification shade (Compose scene container) takes about 100 to 115 ms per frame on this GPU (PowerVR GE8320), 95 to 100% janky frames in a pull test. Findings:
- It is the render thread (issuing draw commands for a full screen layer of rounded translucent cards), not the app's own code: compiling SystemUI moved the median from about 117 ms to about 101 ms, no more.
- The GPU is already at its top clock (680 MHz). Turning the shade blur and the new shade flags off (`notification_shade_blur`, `scene_container`, edited in `/system/etc/aconfig/flag.val`, they read back as disabled after a reboot) changed nothing.
- Making it faster means drawing less in the shade. Not attempted.

## Compiled code: what went wrong and how it was found
- `cmd package compile -m speed` reported Success for SystemUI but the process still ran interpreted. `/proc/<pid>/maps` showed the verify-only `.vdex` from `/data/dalvik-cache`, not the compiled `.odex`. Deleting the stale dalvik-cache copy made ART use the odex (`cmd package art dump <pkg>` then says `status=speed`). `dexopt_all.sh` does that for every app (`fixcache`).
- Compiles of SystemUI and Settings failed on the full `/system` (ENOSPC in `oat/arm64`); the bind mounts from `tools/firstboot/odex_bind.sh` fix that (see the README).
- The mount points must exist in the image: `/` is read only when `odex_bind.sh` runs in `post-fs-data`, so `mkdir` there fails. The builder creates `oat` in every app directory.

## Other settings checked (MT8768, kernel 4.9.190), read from the kernel source and the live tablet
Applied and exposed in ROM Tuner (Performance and Memory pages, all with stock values selectable; measured effect on the shade and drawer was within noise):
- Touch boost (`/proc/perfmgr/tchbst/user/usrtch`): length 100/200/400/800 ms (default 400), clock step (default top), strength (default 100). Stock is 100 ms, step 2, 80.
- `schedplus` clock behaviour: clock drop delay (`down_throttle_nsec`, default 20 ms, stock 4), rise delay (`up_throttle_nsec`, default 0) and headroom (`/sys/devices/system/cpu/eas/cap_margin_dvfs`, default 1536, stock 1280). They exist only while `schedplus` is the governor, so ROM Tuner writes them after every governor change.
- Scheduler statistics off (`sched_schedstats`), deep idle (MCDI, `cpuidle/state1/disable`) as a switch (on by default), `watermark_scale_factor` (10/20/30), cached app limit (`max_cached_processes`), Wi-Fi country code (default CA, applied with `cmd wifi force-country-code` at every boot).
Already optimal or not worth changing (checked on the tablet): eMMC 5.1 HS400 with command queueing and 1 MB read ahead, zram lz4 (about 3:1, half still free), `page-cluster` 0, GPU table (680/500/400 MHz, 680 locked), 60 Hz only display, `debug.sf.*` backpressure settings, FPSGO idle, ART dex2oat threads. `CONFIG_SLUB_DEBUG`, PSI, KSM and schedutil are not in the kernel; the cpuctl `uclamp` files are missing, so Android's uclamp task profiles are no-ops and the MediaTek `perfserv_*` boost nodes are the only boost path.
New governors cannot be added without a kernel rebuild: the governors are built in, a `.ko` needs the exact 4.9.190 symbol versions (Lenovo's public source is 4.9.117 for the X606F), and a bad module panics the boot.

## Quick check after a flash

```sh
adb shell 'dumpsys package com.google.android.gms | grep -m1 "arm64: \\[status"'       # speed
adb shell 'getprop persist.sysui.disableBlur; getprop dalvik.vm.usap_pool_enabled'     # 1 and true
adb shell 'dumpsys gfxinfo com.android.launcher3 reset; ...swipe the drawer...; dumpsys gfxinfo com.android.launcher3 | grep -E "Janky|50th|99th"'
```

## App drawer analysis (November 4, 2026, r5 plus live changes)
Test: 6 drawer open/close swipes and 4 scroll flings on the Pixel Launcher (landscape), `dumpsys gfxinfo` and an `atrace` (gfx view input sched freq binder_driver ss) of 12 s, parsed per thread.
- The CPUs and the GPU are already at their maximum (performance governor, 2.3 and 1.8 GHz, GPU 730 MHz). The launcher's RenderThread spends about 19 ms CPU per frame (libhwui 43%, the MediaTek GL driver 18%, kernel 9%); GPU time is only 6 to 9 ms per frame. The main thread is not starved (runnable wait 25 ms in 12 s).
- 17% of the frames had a 20 to 40 ms stall in `flush commands` (the GL submit). 91% of those overlapped SurfaceFlinger drawing the wallpaper on the GPU (5% of the fast frames did): the wallpaper layer is rotated 90 degrees and scaled, which the Android 10 vendor HWC cannot compose, so it is a CLIENT layer, and the launcher zooms it on every drawer frame. `tools/magisk/wallpaper-nozoom` removes the zoom; the stall count dropped from 43 to 25 but the benchmark did not improve, because the remaining stalls come from SurfaceFlinger region sampling (a 292x4 sample for the gesture handle, about 5 screenshots per second, each drawing the wallpaper and the launcher on the GPU) and from HWC present.
- Skia Vulkan (`debug.hwui.renderer=skiavk`) works on this driver but was not better (22 to 27% janky with GL, 50% with Vulkan in the first matched runs).
- The `--dev-tools` boot logger (`bootdiag.sh`) ran `logcat -f` forever (about 4% of a core plus flash writes); it now stops 3 minutes after boot.
- The Google app's "Hey Google & Voice Match" had "While driving" switched on: the on-device hotword process used 8 to 20% of a core and kept the microphone open. Off now (Google app, Hey Google & Voice Match).
- Run to run noise is large (16 to 62% janky for the same build), so judge a change on at least 4 runs; a reboot also changes the ROM Tuner profile that is active.

