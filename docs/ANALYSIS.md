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
