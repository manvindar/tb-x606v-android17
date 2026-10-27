# Boot analysis, in the order the failures appeared

All evidence is from the kernel console (`/proc/last_kmsg`, `/sys/fs/pstore/console-ramoops`, read from TWRP after each failed boot)
and, once `/data` mounted, from `/data/local/tmp/bootdiag/{logcat,kmsg}.txt`. Timestamps are seconds since kernel start.

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 1 | Reboots at the Lenovo logo; PID 1 exits with status 127 about 15 ms after the vendor mount | `libc: arc4random data MADV_WIPEONFORK failed: Invalid argument`, then abort. Reproduced by running the GSI's linker from TWRP | kernel: backport `MADV_WIPEONFORK` |
| 2 | 4.9.117 kernel oopses at 0.59 s in `fiq_cache_init` | `ram_console: ioremap failed, [0x8000010dc00, 0xac000000001]`: the newer bootloader hands over a DT node the 4.9.117 driver misparses; `_pa` stays a garbage non-NULL value while the virtual mapping is NULL | kernel: clear `_pa` on failure; skip `fiq_cache_init` |
| 3 | `init` reboots to the bootloader | `secilc`: no `mapping/29.0.cil` (GSI starts at 31.0) | copy 29.0 mapping from Android 16 |
| 4 | same | `secilc`: `Failed to resolve typeattributeset` (types Android 17 removed), then `Invalid syntax` (empty lists) | prune undefined types, drop empty statements; keep `29.0.compat.cil` as shipped |
| 5 | kernel oops at 11 s in `mcdi_enter` / `mtk_enter_idle_state` | MediaTek deep-idle code vs newer firmware (same family as 2) | `cpuidle.off=1` via DT bootargs |
| 6 | `SetupCgroups` fails: `Failed to mount controller blkio` | no block-IO cgroup in this kernel; Android 17 treats it as fatal | `cgroups.json` without blkio |
| 7 | `cgroup2: unknown option "memory_recursiveprot"` | 4.9 rejects every cgroup2 option | kernel: ignore them |
| 8 | `Failed to mount controller cpuset: ENOENT` | Android 17 mounts cpuset with `noprefix,cpuset_v2_mode`; unknown token gives ENOENT in 4.9 | kernel: accept it as `clone_children` |
| 9 | `apexd-bootstrap` fails on `com.android.virt.apex` | the only EROFS APEX; no EROFS in 4.9 | remove it |
| 10 | `/data`, `/cache` never mounted | `forcefdeorfbe=` flag is a libfstab parse error; vendor rc uses `fstab.mt6765`, not only `fstab.mt8768` | `tools/fix_vendor_a17.py` |
| 11 | `lmkd` restarts every 5 s | it needs PSI or memcg; it is a `critical` service | idle stub; the in-kernel lowmemorykiller remains |
| 12 | **`bpfloader` exits 4, init reboots** (about 124 s in, after `apexd` finishes decompressing) | `NetBpfLoad: ... kver:4090075 ... Android U requires kernel 4.14.` | none found |

Why `init` was silent for a while: the kernel rate-limits userspace writes to `/dev/kmsg` (10 messages per 5 s by default), which hid the
`secilc` error. `printk.devkmsg=on` in the DT bootargs fixed that. The bootloader ignores the boot.img cmdline and takes `/chosen/bootargs`.

Things that did not work, so nobody repeats them: making the RAM console map its real SRAM window (0x10DC00, 0x800) or using DRAM mode
hangs the tablet in the first fraction of a second with no log; building from the stock 4.9.190 config does not compile against the
source drop (missing Microtrust TEE v300 headers); `fastboot boot` is ignored by the bootloader.

## On item 12

`netbpfload` carries per-release kernel minimums (U: 4.14, V: 4.19, then 5.4, 5.10, 5.15, 6.1 for later codenames). Even with the check
removed, the tethering BPF programs would have to load on 4.9, and `netd` and ConnectivityService open BPF maps at start-up. Possible
but unattempted routes: patch the version check and hope the programs load (unlikely), or create and pin the maps from a helper and attach
nothing (loses traffic accounting and the firewall, and is untested). Neither is small work.

## eBPF wall: passed (2026-10-27)

The netbpfload gate was not the only one. Everything below is applied by files under `tools/bpf/` (all apex files are signed, so patched copies
are bind-mounted over them from `00_bpf_bind.rc`, which runs in `on load-bpf-programs` before `exec_start bpfloader`).

1. `netbpfload`: kernel-version gates, fatal sysctl writes (`bpf_jit_kallsyms` does not exist, `unprivileged_bpf_disabled` rejects "0\n"),
   the "no programs loaded yet" check (`BPF_PROG_GET_NEXT_ID` is 4.13+) and the same-device check for the object files (`patch_binaries.py netbpfload`).
2. `netd.o`: kernel 4.9 has no `LRU_HASH`. The map type lives in two places, the BTF map definition and the loader's own `.android_maps`
   table (180-byte entries, type first); both are rewritten to `HASH`. The 4.9 stats programs (`ingress/stats$4_9_t`, `egress/stats$4_9_t`)
   are limited to bpfloader versions < 0xdac (pre-U), so their loader max version is raised (`patch_netd_o.py`). The verifier accepts them on 4.9.
3. `libnetd_updatable.so`: three kernel-version gates plus the flag that forces the sockops program (`patch_binaries.py netd_updatable`).
4. The platform bpfloader's final `netbpfload done` re-exec fails (ENOENT), so `bpf.progs_loaded` is never set. The ptrace wrapper
   `netbpfload_tracer.c` (static, built with `python -m ziglang cc -target aarch64-linux-musl -static`) runs the patched netbpfload, logs every
   `bpf()` call to the kernel log as `strace:` lines, and runs `setprop bpf.progs_loaded 1` after a clean exit.
5. The vendor `aee_aedv` daemon makes the 4.9 kernel oops in `kobj_attr_show` on some boots; `00_aee_off.rc` defines the service first so init ignores the vendor one.

Result: `netd1shot` exits 0, `netd`, `statsd` and both zygotes start.

### Next wall: vendor HALs against the Lenovo OSS kernel
The OSS kernel (4.9.117) does not match the stock vendor userland (built for 4.9.190):
- `hwcomposer.mt6765.so` calls `DISP_IOCTL_GET_DISPLAY_CAPS` as `0x409c4fdb` (156-byte struct); the OSS `mtk_disp_mgr` rejects it ("ioctl not supported"),
  so the composer HAL aborts, SurfaceFlinger dies and init restarts zygote in a loop.
- `audioserver` crashes in `AudioFlinger::onFirstRef` (null read) and `vendor.mediatek.hardware.pq@2.2-service` crashes in `PQAshmemProxy::initMapMemory`
  (null IMemory from the hidl allocator). Not yet analysed.
Android 16 worked because it ran on the stock kernel. Each of these needs an ABI shim in the kernel or a switch back to the stock kernel with the
MADV_WIPEONFORK / cgroup2 / cpuset_v2_mode backports applied some other way.

## Switch to the stock kernel (2026-10-27)

The Lenovo OSS kernel (4.9.117) cannot drive this tablet's GPU: its PowerVR driver rejects the vendor `rgx.fw` (`BVNC_MISMATCH`, compat struct 2 vs
16777216), because kernel driver, firmware and userspace come from one PowerVR release and the OSS tree has an older one. So Android 17 now runs on the
stock 4.9.190 boot image (`work/kernel/stock/boot.img`, flashed to `boot` only) and the userspace is patched for it.

Kernel-side gaps of the stock kernel and their userspace fixes (scripts under `tools/`):
- `MADV_WIPEONFORK`: bionic's arc4random aborts when madvise(18) fails. It exists in libc **and statically in the linkers** (bootstrap `linker`/`linker64`
  and the runtime apex ones). `tools/libc/patch_libc.py` NOPs the failure branch in all of them (arm64 and thumb-2, found by pattern).
- cgroup2 rejects any mount data on 4.9, cpuset has no `cpuset_v2_mode`: `tools/libc/patch_pgsetup.py` passes NULL data and uses `clone_children`.
- the runtime apex lives in the signed apex, so `tools/apex/repack_apex.py` replaces files in the ext4 payload (extent-aware, in place), re-signs the
  AVB hashtree with our key (new `apex_pubkey`), page-aligns the zip entries and signs the zip as an APK (PackageManager checks it). **Alignment matters:**
  `apksigner` without `--alignment-preserved true` moves `apex_payload.img` off its 4096 boundary and the tablet restarts before the boot animation.
- `com.android.virt.apex` is EROFS (no EROFS in 4.9) but the framework still references its jar: converted to ext4 and re-signed the same way.
- Android 17's ueventd ignores `/vendor/ueventd.rc`: `tools/vendor/make_vendor_ueventd.sh`.
- Android 17's `libui` only falls back to the HIDL gralloc mappers when `android_get_device_api_level() <= 35`: `tools/libc/patch_libui.py`.
- vendor seccomp policies lack `gettid` (Android 17 libc/libhidl call it): `tools/vendor/seccomp_add.sh`.
- no AIDL IPower HAL: `HintManagerService` dies on a null `SupportInfo`. `tools/framework/patch_services_jar.py` patches the bytecode (catch NPE, build the
  default all-unsupported SupportInfo). The patch must keep register `v6` (`this`) unclobbered inside the try range or ART's verifier rejects the class.

Debugging aids: `tools/bootdiag.sh` (logs to /data/local/tmp/bootdiag; probes go to logcat tag DIAG, because redirecting toolbox output to files gave
empty files), `tools/pulllog.sh N`, `tools/bpf/netbpfload_tracer.c` (also still wraps netbpfload), zig for cross-compiling test binaries.

### State at the end of this session
Boots to the boot animation with `system_server` running (PackageManager scans 164 packages, services start). Open: the vendor audio HAL is 5.0 and
Android 17 has no `libaudiohal@5.0` (`audioserver` crashes in `AudioFlinger::onFirstRef`), `pq-2-2` crashes on the HIDL shared-memory mapper, and the
first full boot with the fixed services.jar has not been observed yet.

### Progress log (2026-10-27, later)
Boot now reaches `boot_progress_ams_ready` (ActivityManager ready); system_server lives ~20 s per cycle. Walls passed since the stock-kernel switch:
- tethering `libservice-connectivity.so` aborts in ClatCoordinator JNI_OnLoad when `/sys/fs/bpf/*` contexts are `fs_bpf` (stock 4.9 has no per-directory bpf labels):
  NOP the abort (`tools/bpf/patch_binaries.py service_connectivity`).
- `BpfNetMaps.initBpfMaps`, `ConnectivityService` (LocalNetEventListener) and `initLoopbackEventConsumer` all need the local_net / loopback ringbuf maps (kernel 5.x):
  three bytecode edits in `tools/framework/patch_service_connectivity_jar.py` (the jar is bind-mounted over the apex copy by `tools/bpf/00_bpf_bind.rc`).
- audio: Android 17 has no HIDL audio 5.0 client. `tools/audio/` pulls the Android 16 factory + 5.0 libs and stubs the one function whose ABI differs;
  audioserver now stays up (no audio output yet), which stops the AudioService watchdog kill.
- `BiometricCameraManagerImpl.subscribeToCameraEvents()` no-op (camera service cannot init its listener on this vendor): third edit in `patch_services_jar.py`.
Still open: audio outputs (needs a real 5.0 client or a 6.0/7.0 HAL shim), camera, pq-2-2 crash, lmkd/memevents.

### Milestone (2026-10-27): Android 17 UI reached
After the loopback consumer no-op, system_server stays up and the UI comes up on the tablet: Android 17 shows the system dialog
"There's an internal problem with your device. Contact your manufacturer for details" (the build-consistency check in ActivityManagerService.systemReady,
`Build.isBuildConsistent()`). Then the tablet powered off (cause not yet read from the logs).

### Milestone: Android 17 booted with adb (2026-10-27)
`libmeminfo` abort neutralised (`tools/libc/patch_libmeminfo.py`). Now `sys.boot_completed=1`, `ro.build.version.release=17`, kernel 4.9.190 (stock), adb works after a normal boot,
system_server stable (pid unchanged for minutes), SurfaceFlinger composes, SystemUI/Launcher3/Settings run, screenshots work (`screencap`). Remaining visible issues:
the "There's an internal problem with your device" dialog (Build.isBuildConsistent, Treble => VINTF verifyBuildAtBoot), SystemUI start slow/ANR before dexopt
(`cmd package compile -m speed -f com.android.systemui com.android.launcher3` fixes it), black wallpaper/home, lmkd is a stub (AMS spams "Failed to connect to lmkd").
Unattended loop from here: adb root, `mount -o remount,rw /` (and /vendor), edit, `adb reboot`.

### Milestone: Settings and Home reachable (2026-10-27, adb loop)
With adb available after each boot the fixes became a live loop (adb root, `mount -o remount,rw /`, edit, `adb reboot`):
- build-consistency dialog: `Build.isBuildConsistent()` forced true in services.jar (`tools/framework/patch_services_jar.py`, edit 4). `vintf` on the device says why it fails
  (sepolicy 29.0, VNDK 29, kernel 4.9 vs the Android 17 compatibility matrix).
- SystemUI/phone "failed to complete startup" crash loop (black screen): app startup on this SoC is slower than Android's 15 s `BIND_APPLICATION_TIMEOUT`;
  `ro.hw_timeout_multiplier=5` scales all AMS/ANR/Watchdog timeouts (`tools/system_fixups.sh`). `cmd package compile -m speed -f com.android.systemui` helps too.
- Camera/Sensor/Audio "-38" (system_server crash on BOOT_COMPLETED): `IBinder::addFrozenStateChangeCallback` needs a 6.x kernel binder ioctl; patched to succeed (`tools/libc/patch_libbinder.py`).
- pq@2.2 HAL crash loop: the vendor libhidlbase searches `/system/lib64/vndk-sp-29/hw` for `android.hidl.memory@1.0-impl.so`; the A17 image lacks that link (A16 had it).
- the screen is off after the display timeout and `screencap` is black then: `svc power stayon true`.
Open: Bluetooth (com.android.bluetooth native crash), audio outputs, camera, lmkd stub, ArrowOS boot animation file (leftover from the Android 16 customization).

## 2026-10-27 08:20: slowness, lmkd, Wi-Fi crash, brightness

- **No compiled system apps.** The GSI ships no odex, so Settings, SystemUI and the IME ran from the APK (`status=run-from-apk`). `tools/scripts/dexopt_all.sh` (`cmd package compile -m speed`) fixes it: Settings cold start 50 s -> 0.9 s. It must be rerun after a userdata wipe.
- **lmkd.** The A17 lmkd only supports PSI/memcg ("Failed to initialize PSI monitors"). The earlier idle stub never listened on the socket, so AMS slept 3 x 1 s per failed `writeLmkd` while holding locks, which blocked binder calls for seconds and finally tripped the watchdog (zygote restart). `tools/lmkd/lmkd_inkernel.c` (static, zig) accepts the socket and implements TARGET (minfree/adj sysfs), PROCPRIO and PROCS_PRIO (`/proc/pid/oom_score_adj`), GETKILLCNT, UPDATE_PROPS and BOOT_COMPLETED replies, so the in-kernel lowmemorykiller works again. `critical` is removed from `lmkd.rc`.
- **Wi-Fi connect killed system_server**: `BpfNetMaps.addLocalNetAccess -> incrementLnpGenerationId` NPE (maps skipped earlier). Nine BpfNetMaps methods are now `return-void`. Overwriting only the first code unit gave a VerifyError (the rest of the 2-unit instruction decoded as garbage); `return-void` + `nop` is right.
- **Brightness**: A17 LightsService only talks to the AIDL `android.hardware.light.ILights`; the vendor only has HIDL light@2.0. `tools/lights/` has a Java shim (app_process, hand-parsed AIDL parcels, writes `/sys/class/leds/lcd-backlight/brightness`), its rc, and the vintf fragment `/vendor/etc/vintf/manifest/android.hardware.light-aidl.xml`.
- **Bluetooth**: after the HCI 0x0c5a status is ignored, `btm_sec_dev_reset` aborts with "only controllers with SSP is supported"; the `tbz` at 0xabaf10 is nopped.
