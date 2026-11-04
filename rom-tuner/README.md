# ROM Tuner

ROM Tuner is a settings app for the TB-X606V ROMs (Android 14 and 16). It groups the performance, memory, display and behaviour options of this tablet in one place and applies them through a small root daemon. It builds without Gradle.

<img src="docs/icon-light.png" width="96"> <img src="docs/icon-dark.png" width="96">

The launcher icon is a flat performance dial with a light and a dark variant. The dark variant (`res/drawable-night`) is used when the system is in dark mode, and a monochrome layer supports themed icons. The sources are `assets/icon.svg` and `assets/icon-dark.svg`.

## Features

The app opens from the launcher and from the top of the Settings homepage. A "Performance" quick settings tile cycles through the battery, balanced and performance profiles.

| Section | Options |
|---|---|
| Presets | Battery saver, Balanced, Smooth, Performance, Extreme |
| Performance | Power mode (battery, balanced, performance), GPU clock, maximum clock for the fast and the efficiency cores |
| Tuning | Maximum clocks (pins the top stock CPU clocks), CPU governor, thermal limits, I/O scheduler, read-ahead, swappiness, TCP congestion control, foreground and top-app boost, big-task rotation, cache pressure, write-back threshold, free memory reserve, swap read-ahead, memory watermark |
| Touch and clocks | Touch boost length, clock step and strength, clock drop and rise delay and clock headroom of the schedplus governor, deep idle (MCDI), scheduler statistics |
| Wi-Fi | Country code (Automatic, CA, US, GB, DE, FR, IN, AU, JP, MX, BR) |
| Appearance | Dark mode, pure black, accent colour, palette style, font, icon shape and icon pack (the last three only when the ROM ships those overlays) |
| Display and system UI | Navigation mode, animation speed, display size, boot animation |
| Picture quality | MediaTek MiraVision through the PQ HAL: a Factory or Custom picture mode, and in Custom mode contrast, saturation, gamma and sharpness (see Picture engine below). The dynamic contrast (DRE), HDR and ultra resolution switches were removed from the page: this tablet has no hardware or kernel support for them |
| Behaviour | Screen timeout, font size, stay awake while charging, auto-rotate, show taps, window blur, private DNS, background apps kept, cached app limit, Treble audio effects, ADB over network, serial console shell (off by default; it triggers Android's "Serial console enabled, performance is impacted" warning), Bluetooth MediaTek workaround |
| Overclock page | Master switch, "Max stable", CPU voltage trim, GPU clock above 680 MHz, status line, Back to stock (see Overclock below). Off by default and in every preset |
| Actions | Restart System UI, reboot, compile apps now, per app compile (System, App speed), shortcuts to LineageOS settings screens and Dolby Atmos |

A fresh install starts on the Smooth preset.

## Presets

The presets were measured on the tablet with a scripted fling through the Settings app (janky frames from `dumpsys gfxinfo`) and an eight-thread compute loop.

| Preset | Janky frames | 95th percentile frame time |
|---|---|---|
| Battery saver | 7.4% | 34 ms |
| Balanced | 2.8% | 19 ms |
| Performance profile alone | 2.6% | 23 ms |
| Smooth (foreground boost 60, top-app boost 30, larger free-memory reserve) | 1.7% | 18 ms |
| Performance (boosts 100 and 70, larger write buffer) | 1.1% | 22 ms |

Extreme adds pinned top clocks, the performance governor and no thermal limit. It gave no smoother UI than Performance in these tests and runs hotter. Big-task rotation made the compute loop 20 to 30 percent slower, so no preset turns it on.

## How it works

The app never writes to the system itself. It appends `key=value` lines to `files/request` in its own data directory. The daemon in `device/tablet_tunerd.sh` watches that directory with `inotifyd`, checks each value against a strict character set, applies it, and keeps the master state in `/data/local/tmp/tuner.state`. A copy of the state and the lists of available governors, GPU clocks, schedulers and fonts go back to the app. The log is `/data/local/tmp/tuner.log`.

The MediaTek power HAL rewrites the power mode, the boosts and the minimum clock limits on touch boosts and when the screen changes state. The daemon compares those values every five seconds and writes its own back when they differ. When it sets a profile it writes `cpufreq_power_mode` before the clock limits, because the sports mode resets them.

The daemon also keeps the Treble Settings audio effects switched off. Those effects attach to every playback session and cut app audio after a few milliseconds on this tablet. The "Treble audio effects" switch lets you leave them alone.

Compile apps (System page, "App speed"): the request keys `compile_now` and `compile_all` are one-shot actions, not stored state. The daemon starts `/system/bin/dexopt_all.sh` in the background with `DEXOPT_FORCE=""` (skips apps that are already compiled) or `-f` (recompiles all), writes its output to `/data/local/tmp/dexopt.log` and its pid to `/data/local/tmp/dexopt.pid`, and ignores a second request while one run is alive. The script is not part of this repository or the image builder: it comes from the Android 17 tools and must be installed on the tablet. The daemon publishes `compile_status=idle|running|done|missing` in the state copy (`done` when the log ends with `DONE`, `missing` when the script is not installed) and republishes within five seconds when it changes. A run takes 10 to 15 minutes. After the first apps are compiled the non-forced run restarts SystemUI and the launcher.

Per app compile (System page, "App speed", "Per app compile"): the screen lists every enabled installed app, user and system, with its compile state, a search box and the filter All, Not compiled, Compiled. The app list comes from `PackageManager` (the manifest asks for `QUERY_ALL_PACKAGES`). The compile states cannot be read by the app, so the daemon writes them to `files/apps_state`: one `package=filter` line per package and a last line `apps_state_time=<epoch>`. The app treats a file without that last line as broken (partly written), keeps the list it had, and shows "Loading compile states" while it sends `refresh_apps=1`. Sorting is not compiled first, then compiled, then apps without code, each by label. "Compiled" means a native code filter: speed, speed-profile, everything, space and their `-profile` forms. Verify, quicken, run-from-apk, error, unknown and a package missing from the dump count as not compiled. "Compile all not compiled" sends `compile_now`, which only skips apps that are already at speed or better, so apps at speed-profile are compiled again.

Tapping an app opens a dialog with Compile (speed), Compile (speed profile), Reset and Cancel. The request keys are one-shot like `compile_now`:

| Key | Value | Does |
|---|---|---|
| `compile_pkg` | package name | `cmd package compile -m speed -f <pkg>`, then the stale copy cleanup and `am force-stop <pkg>` |
| `compile_pkg_profile` | package name | the same with `-m speed-profile` |
| `reset_pkg` | package name | `cmd package compile --reset <pkg>` (no cleanup or force-stop) |
| `refresh_apps` | `1` | rebuilds `apps_state` in the background (several seconds) |

The package name must match `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$` (checked in `pkgok`, on top of the general character set check) and `pm path <pkg>` must succeed, both when the request arrives and again when the job starts. Jobs go into `/data/local/tmp/tuner.queue` and one background worker runs them one at a time; the directory `/data/local/tmp/tuner.q.lock` marks a live worker. A request while `compile_now` or `compile_all` runs is refused (status `failed`), and `compile_now` or `compile_all` is refused while the worker runs. The state copy gets these keys:

| Key | Values |
|---|---|
| `compile_pkg_status` | `<package>:running`, `<package>:done` or `<package>:failed` for the latest job only |
| `compile_queue` | comma separated packages that are waiting (the running one is not in it) |
| `apps_state_time` | epoch seconds of the last time `apps_state` was written |
| `apps_refresh` | `running`, `failed` (neither dump command gave output) or `idle` |

Compile progress (live): while a bulk run or the per app queue works, the daemon publishes these keys in the state copy and the app shows them on the System page ("App speed") and on the Per app compile page as a block with `Compiling <label> (<package>)`, a progress bar, `<done> of about <total>`, the elapsed time and the failure count. The row of the app being compiled says "Compiling…", queued apps say "Queued". The keys are absent when nothing compiles, and an old daemon never writes them: the app then shows the pages as before.

| Key | Meaning |
|---|---|
| `compile_current` | package being compiled (bulk: the last package named in the log; queue: the running job). Empty between queue jobs |
| `compile_current_state` | `compiling`, or `finished` in the bulk `-a` step (see below) |
| `compile_done`, `compile_failed` | packages with a Success (done) or Failure (failed and done) result in this run, or jobs finished in this queue burst |
| `compile_total` | bulk: an estimate, see below; queue: done + running + waiting, exact. 0 = not known yet |
| `compile_started` | epoch seconds of the start (bulk: the run marker; queue: first job of the burst); the app computes the elapsed time from it |
| `compile_summary` | only after a bulk run, while `compile_status=done`: `<done> done, <failed> failed in <mm:ss>` (the app shows it as "Last run") |

How the bulk numbers are derived (`dxrun`, `dxparse`, `dxprog` in `tablet_tunerd.sh`): the daemon writes `=== run <epoch> ===` as the first line of `dexopt.log` when it starts a run, and only the lines after the last marker count (a log without a marker counts whole). `dexopt_all.sh` prints `<package>` and then `Success` or `Failure` for its explicit lists, then `[<package>] Success|Failure` per package for the final `cmd package compile -a` step, then `DONE`. A bare package line is "started" until its result line comes; a result counts once per package, so the apps named in the lists and again by `-a` are not counted twice; a line without its newline yet is ignored; an `Error:` line is neither a result nor a start. The log is parsed only when its size changed since the last parse (the result is cached in `/data/local/tmp/dexopt.prog`), by a shell loop over a few hundred lines, with no `pm` call. Limits:

- `compile_total` is `pm list packages | wc -l`, taken once in the background when the run starts and stored in `/data/local/tmp/dexopt.total`. It is an estimate: it counts apps without code, which the script never prints, so the bar usually ends below 100 percent until `DONE` (the app then shows the summary instead). It is raised to `compile_done` if the count turns out lower.
- In the `-a` step an app is named only after it is compiled, so there `compile_current` is the last finished app and `compile_current_state=finished`. The app shows "Last finished: ..." there and marks no list row. Only the explicit lists (SystemUI, launcher, Settings, Google apps and so on) show a live "Compiling" app.
- Not-installed packages in the third list print an `Error:` or similar line and are not counted; a package whose compile prints `Failure` counts as failed.
- The queue counters live in `/data/local/tmp/tuner.qcount` (`done failed started`), created when a worker starts and removed when the queue is empty.

Publishing: the full `publish` runs several `cmd overlay list` calls and takes a second or two, so a second loop (the 2 second one that also runs the overclock watch) only rewrites the `compile_*` lines of the state copy, under the same lock, and only when they changed. It also runs once after the run ended to publish `compile_status=done` and the summary. `publish` itself now builds the file first and writes it in one go, so the app never reads it half empty; the app also skips a copy without its last `status=` line. When a bulk run ends the daemon rebuilds `apps_state` (as before) and the app redraws when `apps_state_time` changes.

The cleanup after a compile is the `fixcache` step of `dexopt_all.sh` for one package: when an odex sits next to a system apk, `/data/dalvik-cache/arm64/<apk path with / as @>@classes.*` is deleted so the next start uses the odex. The tuner app itself is not force-stopped, because that would close its own screen. After each job the daemon re-reads that one package and replaces its line, and it rebuilds the whole list when a `compile_now` or `compile_all` run ends and when the app asks.

The list is parsed from `cmd package art dump` (`dumpsys package dexopt` if that prints nothing): a `[com.x]` header line, then `path:` blocks with `arm64: [status=speed-profile] [reason=bg-dexopt] [primary-abi]`. The status on the first such line after a header is taken (the base apk). The header may be indented. `sh tests/test_apps.sh` runs the parser, the name check, the queue worker and the state file against stubs of `cmd`, `pm` and `am`, with no device. It also covers the compile progress: log parsing (marker, bare and `[pkg]` results, a partial last line, an empty or missing log, a stale log before the marker, the cache), the queue keys, `compile()` writing the marker, and the light publish; and, when `javac` is found, `tests/ProgTest.java` runs the app side parsing (`src/org/tablet/tuner/Prog.java`, no Android classes) on a plain JVM.

Touch and clocks, idle, memory watermark (Performance and Memory pages) are stored with the preset and in saved profiles. Each has a fixed list of allowed values, checked in `valid` when a request arrives and again by `sane` before a write, so a bad value in the state file falls back to its default. Writes go through `wn`, which skips a node that does not exist.

| Key | Allowed values | Default | Stock | Written to |
|---|---|---|---|---|
| `touch_duration_ms` | 100, 200, 400, 800 | 400 | 100 | `/proc/perfmgr/tchbst/user/usrtch`, line `duration <ns>` |
| `touch_opp` | 0, 1, 2 (0 is the top clock) | 0 | 2 | same file, line `touch_opp` |
| `touch_boost` | 0 to 100 | 100 | 80 | same file, line `init` |
| `schedstats` | 0, 1 | 0 | 0 | `/proc/sys/kernel/sched_schedstats` |
| `down_throttle` | 4, 10, 20, 40 (ms) | 20 | 4 | `/sys/devices/system/cpu/cpufreq/policy{0,4}/sched/down_throttle_nsec` |
| `up_throttle` | 0, 2, 5 (ms) | 0 | 0 | same directory, `up_throttle_nsec` |
| `cap_margin` | 1024, 1280, 1536, 1792 | 1536 | 1280 (1.25x) | `/sys/devices/system/cpu/eas/cap_margin_dvfs` |
| `mcdi` | 1 (on), 0 (off) | 1 | 1 | `cpu*/cpuidle/state1/disable`, written as 1 minus the value |
| `watermark` | 10, 20, 30 | 10 | 10 | `/proc/sys/vm/watermark_scale_factor` |

The two throttle files only exist while the governor is schedplus, and the governor forgets them when it is set again, so `tune` writes them after the governor and `enforce` writes them again whenever it has to put the governor back. Battery and Balanced use the stock column, Smooth, Performance and Extreme use the default column. `min_free_kbytes` is left alone.

Two settings are not in presets or profiles because they are about the place and the Android framework, not the speed:

- `wifi_country`: Automatic or one of CA, US, GB, DE, FR, IN, AU, JP, MX, BR, default CA. The daemon runs `cmd wifi force-country-code enabled XX` (`disabled` for Automatic). The override is only kept in memory, so the daemon sets it again whenever it sees a new `system_server` pid (the first run after boot included), retrying every 5 seconds for a minute while the Wi-Fi service starts, and when the value changes.
- `cached_apps`: Default, 16, 24, 32 or 48, written to `settings put global activity_manager_constants max_cached_processes=N` (other constants in that string are kept; Default removes only this one). This is separate from `bg_procs` (Background apps kept), which uses `device_config put activity_manager max_cached_processes`. They are two stores for the same limit and Android may prefer the device config one, so set only one of them. At boot the daemon applies it only when it is not Default.

An existing install gets the new keys on the next daemon start, with the values of its current preset.

The daemon runs as `u:r:su:s0`, so it needs a userdebug ROM or permissive SELinux.

## Overclock

The Overclock page is off by default, no preset turns it on, and its keys are not stored in saved profiles. It exists because one part of it is real and one part is not. This section says which, and where each claim comes from.

Sources: **measured** is a read-only read on the tablet (kernel 4.9.190, Lenovo build, MT8768CT, adb `cat`/`ls`/`zcat /proc/config.gz`). **Source** is the public Lenovo TB-X606F kernel (`github.com/Yahoo-Mike/android_kernel_lenovo_X606F`, 4.9.117, same SoC family, `CONFIG_MTK_PLATFORM="mt6765"`, `drivers/misc/mediatek/base/power/...`). It is not the exact source of this tablet's kernel, so every source claim is unverified until it is tried on the tablet. **Guess** is an estimate.

### What the kernel allows

CPU (cpufreq_v1 plus the hybrid DVFS firmware, `CONFIG_MTK_CPU_FREQ`; `/proc/cpuhvfs` exists and `cpufreq_sched_disable` reads 0, measured, which means the hybrid build)

| Question | Answer | Evidence |
|---|---|---|
| Clock above the top stock step | **No.** `cpufreq_freq` only accepts a kHz value that is in the OPP table, `cpufreq_oppidx` only an index 0 to 15 (`cpufreq_freq_proc_write`, `cpufreq_oppidx_proc_write` in `cpufreq_v1/src/mtk_cpufreq_interface.c`). In the hybrid build the actual set calls `cpuhvfs_set_freq` and `cpuhvfs_set_volt` are `#if 0` stubs (`mtk_cpufreq_hybrid.c`), the DVFS firmware reads a 16 entry frequency, voltage and divider table from SRAM that the kernel fills once at boot (`cpuhvfs_pvt_tbl_create`), and `dbg_repo` is read-only. A frequency above the table is clamped by PPM (`ppm_main_freq_to_idx` in `ppm_v3/src/mtk_ppm_main.c`: above the maximum, use entry 0) | source |
| Turbo or hidden OPPs | **No.** `turbo_flag = 0` is set in `_mt_cpufreq_get_cpu_level` and `mt_cpufreq_turbo_config` returns 1 without doing anything (`mach/mt6765/mtk_cpufreq_platform.c`); `cpufreq_turbo_mode` reads `0, 0, 0` on the tablet (measured). The writable turbo node only turns turbo off | source, measured |
| Arbitrary PLL frequency | **Not safely.** `/proc/freqhopping/freqhopping_debug` takes raw DDS commands, but on this build they go to the SSPM by IPI (`CONFIG_MTK_TINYSYS_SSPM_SUPPORT=y`, `freqhopping/mt6765/mt_freqhopping_sspm.c`) while the DVFS firmware keeps rewriting the CPU PLLs and the voltage. Not used | source, unverified |
| Per OPP voltage | `cpufreq_volt` does **not** set a voltage in the hybrid build (the write is commented out in `cpufreq_volt_proc_write` and it only sets `dvfs_disable_by_procfs`, so the Tuner never writes it). What works is `/proc/eem/EEM_DET_{L,L_HI,2L,2L_HI}/eem_offset`: a signed number of 6.25 mV steps added to the voltage the EEM (PTPOD) calculated for every step, then clamped to the EEM minimum and maximum (`eem_set_eem_volt`, `eem_offset_proc_write` in `eem_v2/mt6765/mtk_eem.c`; step 6.25 mV from `CPU_PMIC_STEP`, base 0.5 V from `EEM_V_BASE`). The clamps are VMAX `0x64` (1.125 V), VMIN `0x10` and at most the table voltage plus 62.5 mV (`MARGIN_ADD_OFF`, `eem_config.h`). The nodes exist and the code `eem = 0x5e`, `pmic = 0x5b`, 1.0875 V in `eem_cur_volt` fits that arithmetic exactly (measured) | source, measured |
| Overvolt to make a higher clock stable | Moot: there is no higher clock. The EEM already runs the top step at 1.0875 V instead of the 1.11875 V table value (measured), so a positive offset can only give back up to 37.5 mV | source, measured |
| Little cluster to the 2.3 GHz entries | **No.** Each cluster has its own 16 entry table and its own PLL: cluster 1 tops at 1.80 GHz in `dump_cluster_1_dvfs_table` and `MT_CPU_DVFS_LL` (measured). The tables are chosen by an efuse (`_mt_cpufreq_get_cpu_level`, devinfo index 30). The same kernel family carries faster tables: 65T and SB bins reach 2.50 GHz (and 1.90 GHz for the little cluster in SB) at the same 1.11875 V top voltage, this tablet's die gets the 65 table (2.30 GHz, 1.80 GHz, 1.05 GHz CCI) which matches the live tables | source, measured |

GPU (`mt6765/mtk_gpufreq_core.c`, PowerVR GE8320, segment 3 = MT6765, measured `g_segment_id = 3`)

| Question | Answer | Evidence |
|---|---|---|
| Frequency between or above 400, 500, 680 | **Yes, in principle.** `gpufreq_fixed_freq_volt` takes `"<kHz> <10 uV>"` and `mt_gpufreq_fixed_freq_volt_proc_write` does not compare either number with the OPP table. `__mt_gpufreq_set_fixed_freq` then programs the MFGPLL: `__mt_gpufreq_calculate_dds` gives the divider word, post divider 4 for 375 to 950 MHz. Outside 93.75 MHz to 950 MHz the same function logs an error and returns 0, and 0 would be written to the PLL, so only whitelisted values are ever sent | source |
| Is 730 MHz reasonable | The same kernel has an OPP table for the speed binned part (`MT6765T_SEGMENT`, `SEG4_GPU_DVFS_FREQ0 = 730000`) at the same 0.80 V as our 680 MHz step. Our die is the plain MT6765 bin, so 730 MHz at 0.80 V is the family's own rated point on a lower bin | source |
| Voltage above 0.80 V | **Not offered.** In normal DVFS the GPU voltage is a request for a Vcore step (`__mt_gpufreq_vcore_volt_switch`: above 0.70 V is step 0, which is 0.80 V). The fixed voltage in the node also calls `regulator_set_voltage` on `vcore` directly, but Vcore is the shared SoC and DRAM rail, owned by the DVFSRC (`/sys/devices/platform/10012000.dvfsrc`), so a higher value would reach more than the GPU and may be undone by it. The regulator constraints of the PMIC buck are 0.519 to 1.3125 V (measured), nothing in the kernel limits the node itself. The Tuner always sends the top stock voltage (80000) | source, measured |
| What stays active | In fixed mode the thermal, battery and PBM GPU limits of the driver are skipped (`mt_gpufreq_target` applies the fixed values after them), so the Tuner's own 85 C guard is the only protection besides the 117 C `mtktscpu-sysrst` shutdown (`/proc/driver/thermal/tzcpu`, measured) | source, measured |
| Community results | One report on a Galaxy Tab A7 Lite (MT8768T, the same GE8320): `echo "680000 80000" > /proc/gpufreq/gpufreq_fixed_freq_volt` accepted, 870 MHz stable and 900 MHz frozen on that one unit, about 100 points of 3DMark Sling Shot per 50 MHz, CPU overclock did not work because `/proc/cpufreq` rejects values outside the table. The thread (xdaforums.com, "Recovering a soft-bricked Galaxy Tab A7 Lite ... overclocking the PowerVR GE8320 GPU") returned 403 when fetched, so this is read from a search summary, not first hand | web, unverified |

Rated clocks: MediaTek lists the Helio P35 (MT6765) at 2.3 GHz and 1.8 GHz Cortex-A53 with a GE8320 up to 680 MHz, and the Helio P22 (MT8768) at 2.0 GHz with up to 650 MHz (product pages, mediatek.com). This tablet's tables are the P35 class values (2.30, 1.80, 680), so stock is already at the rated maximum. No community data was found for a CPU clock above the table on this family.

Risks. A hard hang or a PMIC fault does not shut the tablet down cleanly: the MediaTek watchdog resets it, and eMMC writes in flight can be lost (ext4 journal replay usually repairs it, user data can still be damaged). The CPU voltage trim is the dangerous one: an undervolt fails at random, usually under a sudden load, and the first symptom is a reset. The GPU clock fails as a freeze or a GPU hang. Thermal: with `thermal=off` the MediaTek thermal policy is out and the only limits left are the Tuner's guard and the 117 C hardware shutdown (`trip_0` of `mtktscpu`, measured; the `cpu_adaptive` trips at 54 and 55 C most likely act through the PPM thermal policy, which `thermal=off` disables, and through the GPU limits that fixed mode skips; not verified). The stock thermal manager is untouched while the Overclock page is off.

### What the Tuner does

| Key | Allowed values | Default | Does |
|---|---|---|---|
| `oc_master` | 0, 1 | 0 | Allows the two settings below |
| `oc_cpu_uv` | 0, 1, 2, 4 | 0 | Writes `-N` to the `eem_offset` of the four CPU banks: 0, -6.25, -12.5, -25 mV on every step |
| `oc_gpu` | 0, 730000, 760000, 800000, 840000, 880000, 920000, 940000 | 0 | `gpufreq_opp_freq 680000` (keeps the Vcore request at step 0), then `gpufreq_fixed_freq_volt "<kHz> 80000"`. Only when the first OPP line of `gpufreq_opp_dump` is 680000 at 80000, otherwise status `unsupported`. After a second the PLL is read back (`real freq` in `gpufreq_var_dump`); more than 2 percent off means `gpu_failed` and a revert |
| `oc_profile` | `maxstable` | one-shot | `oc_master=1`, `oc=1`, `oc_cpu_uv=0`, `oc_gpu=730000` |
| `oc_stock` | `1` | one-shot | "Back to stock": master 0, `oc=0`, trim 0, GPU 0, offsets 0, `gpufreq_fixed_freq_volt "0 0"`, GPU clock setting restored |
| `oc` | 0, 1 | 0 | The old key, shown as "Maximum clocks": pins both clusters at the top stock step with the performance governor. Not an overclock, not part of the crash guard |

Safety, all in `device/tablet_tunerd.sh` (block "overclock"):

- Nothing is applied until 30 seconds after the daemon started (it starts at `sys.boot_completed`).
- Crash guard: before applying, the daemon writes `/data/local/tmp/oc_pending` (the boot id and the time) and runs `sync`. It removes the file after 3 minutes of stable running, meaning the daemon itself kept running with no thermal revert. If the daemon starts and the file holds another boot id, the previous boot ended while overclocked or while still in its first 3 minutes (a reboot you asked for inside those 3 minutes counts too): the keys `oc_master`, `oc_cpu_uv` and `oc_gpu` go to 0, the file is removed and the state copy gets `oc_status=reverted_after_crash`. The same boot id means only the daemon restarted, so nothing is reverted.
- Thermal guard: every 2 seconds the hottest of `/sys/class/thermal/thermal_zone*/temp` is read; above 85000 while overclocked, everything goes back to stock at once, `oc_master` becomes 0 and the status is `thermal_revert`. The same check runs before applying.
- Values come from a fixed list in `valid` and again in `sane`, and `oc_status` cannot be set by a request.
- `oc_status` values: `off`, `waiting`, `testing` (applied, under 3 minutes), `active`, `reverted_after_crash`, `thermal_revert`, `gpu_failed`, `cpu_failed`, `unsupported`.

`sh tests/test_oc.sh` runs this block against fake nodes (validation, crash guard with another and the same boot id, the 30 second delay, the 3 minute marker, the thermal guard, a PLL that does not take the value, an unknown table, switching off).

### Recommended stable settings

Measured: the stock tables above, the live EEM voltages, the regulator ranges, the thermal trips. From sources: the 730 MHz bin and the clamps. Guessed: the gains and the power numbers, and the step 760 and 800 MHz limits.

| Step | CPU | GPU | Gain (guess) | Cost (guess) | Applicable here |
|---|---|---|---|---|---|
| Stock | 2.30 GHz fast, 1.80 GHz slow, EEM voltages | 680 MHz at 0.80 V | none | none | yes |
| Mild, "Max stable" | top clocks pinned (same clocks, no step down), no voltage change | 730 MHz at 0.80 V | GPU +7 percent clock, about 3 to 6 percent frames in GPU bound apps; CPU none beyond less ramp delay | GPU dynamic power about +7 percent (driver model, power is proportional to clock at fixed voltage), a few degrees; battery lower from the pinned CPU clocks | yes, `oc_profile=maxstable` |
| Medium | pinned, trim -12 mV | 760 MHz at 0.80 V | GPU +12 percent clock | GPU power +12 percent; trim saves about 2 percent CPU dynamic power; trim can crash | yes, `oc_cpu_uv=2`, `oc_gpu=760000` |
| Aggressive | pinned, trim -25 mV | 800 MHz at 0.80 V | GPU +18 percent clock | GPU power +18 percent; -25 mV is about -4.5 percent CPU dynamic power but is the likeliest to hang | yes, `oc_cpu_uv=4`, `oc_gpu=800000` |
| Not offered | CPU above 2.30 and 1.80 GHz, CPU over voltage, GPU above 0.80 V, GPU above 940 MHz | | | | no interface (CPU), shared rail (GPU), one unit froze at 900 MHz elsewhere; this tablet: 920 MHz held for about 90 s, 960 MHz was a kernel panic (November 4), 940 untested |

800 MHz is 70 MHz below the 870 MHz that one A7 Lite reportedly held, and the 0.80 V is the same on both, but that is one unit of another model, so the aggressive step may fail on this tablet. Silicon differs from unit to unit. Test each step before trusting it.

PMIC: MT6357 (`CONFIG_MTK_PMIC_CHIP_MT6357=y`, regulators `mt-pmic-vproc`, `vcore`, `vsram_proc`; the kernel also has `CONFIG_REGULATOR_RT5738` but no external GPU regulator is registered on this tablet, measured). The regulator constraints are 0.51875 to 1.3125 V for all of them (measured). The CPU voltage that can be requested through the kernel is limited by the EEM clamp (1.125 V), the GPU voltage by the Vcore step (0.80 V). The PMIC itself would accept more, nothing in the kernel lets the Tuner ask for it.

### Stability test

Run it for each step you want to keep, from Mild up, 10 minutes per step. The script does not write to `/proc` or `/sys`: it starts eight CPU loops (`dd | md5sum`), opens a WebGL scene (override with `GPU_URL=...`, or start a GPU benchmark yourself), and logs every 5 seconds the two CPU clocks, the GPU clock read back from the PLL, Vproc, the CPU and hottest temperatures and `oc_status`. At the end it prints the dmesg lines since the start that look like errors.

```sh
# 1. set the step on the Overclock page
# 2. wait until the status says "On and stable" (3 minutes after the 30 s delay), then:
adb push tests/oc_stress.sh /data/local/tmp/
adb shell 'svc power stayon usb; nohup sh /data/local/tmp/oc_stress.sh 10 > /dev/null 2>&1 &'
# 3. after 10 minutes:
adb shell cat /data/local/tmp/oc_stress.log
adb shell dmesg | grep -iE 'oops|panic|watchdog|serror|hung|gpufreq|eem'
adb shell cat /data/local/tmp/tuner.log | tail -20
```

Pass: no trouble lines, `gpu=` stays at the set clock, `oc=` stays `testing` or `active`, the peak temperature stays under 85000, the tablet never restarted and the Overclock page does not show a revert. A fail at one step: go back one step, do not retry the same step.

Read-back commands for a single check: CPU clocks `cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq` and `.../cpu4/...`, `cat /proc/cpufreq/MT_CPU_DVFS_L/cpufreq_freq`, CPU voltage `cat /proc/cpufreq/MT_CPU_DVFS_L/cpufreq_volt` and `cat /proc/eem/EEM_DET_L/eem_cur_volt`, trim `cat /proc/eem/EEM_DET_L/eem_offset`, GPU `cat /proc/gpufreq/gpufreq_var_dump` (`real freq`, `g_cur_opp_freq`), `cat /proc/gpufreq/gpufreq_fixed_freq_volt` (shows `g_fixed_freq` and `g_fixed_volt` while on).

Not verifiable without writing to the tablet (so untested): that this kernel's `eem_offset` and `gpufreq_fixed_freq_volt` behave as in the 4.9.117 source; whether the Vcore stays at its top step while the GPU runs fixed (the daemon keeps the OPP lock to request it, but `vcore` read 0.65 V when the GPU was idle, measured, and 0.80 V is only read while the GPU is on); whether the GPU thermal limits are really skipped; how the 800 MHz step behaves on this die; whether a trim of -25 mV is stable; whether the 3 minute marker survives a watchdog reset (the file is written and synced before applying, so it should).

### What a kernel change would take

Not possible here: no source for this exact build and no rebuild. With the source of the 4.9.190 build and a rebuilt `boot.img` (never the recovery partition): for the CPU, make `_mt_cpufreq_get_cpu_level` return the 65T level, or add a row to `c65Tbl` in `mtk_cpufreq_opp_pv_table.h` and the matching `opp_tbl_method` in `mtk_cpufreq_opp_table.h` (frequency, Vproc, post divider, clock divider), which gives 2.50 GHz at 1.11875 V on the fast cluster, if this die passes it (the efuse says it was not binned for it); raise `VMAX_VAL` in `eem_config.h` for any voltage above 1.125 V. For the GPU, change `SEG3_GPU_DVFS_FREQ0` in `mt6765/mtk_gpufreq_core.h` to make a higher clock a normal OPP with the driver's own thermal limits, and for a voltage above 0.80 V the Vcore table in `drivers/devfreq/helio-dvfsrc-opp-mt6765.c`. The vbmeta and boot image signing of this tablet would also have to be dealt with.

## Picture engine

How the Picture page works, and why the old contrast boost switches did nothing (checked on the tablet, Android 17 GSI on the Android 10 vendor, kernel 4.9.190):

- The PQ service runs (`pq-2-2`, `vendor.mediatek.hardware.pq@2.3::IPictureQuality/default`, answers `lshal`). Its HAL, `/vendor/lib64/hw/vendor.mediatek.hardware.pq@2.3-impl.so`, reads the `persist.vendor.sys.pq.*` props once, when the service starts, which is why the daemon restarts `pq-2-2`.
- `pq_dre` (`mdp.dre.en`), `pq_vp_dre` (`mdp.vp.dre.en`), `pq_hdr` and `pq_ultrares` are only copied into a shared memory block for the MDP video engine. Nothing on this tablet consumes them: the kernel has `# CONFIG_MTK_DRE30_SUPPORT is not set` and `# CONFIG_MTK_AAL_SUPPORT is not set`, there is no `mdp_aal` block among the platform devices (only `mdp_rdma0`, `mdp_ccorr0`, `mdp_rsz0/1`, `mdp_wdma0`, `mdp_wrot0`, `mdp_tdshp0`), `libdpframework.so` has only DRE and HDR readback stubs and no DRE engine, and the HAL logs `loadDRETable: not support video DRE` and `loadHDRTable: not support HDR` every start. There is no AAL or MiraVision user space daemon either. These four keys are still accepted by the daemon so old saved profiles load, but the page no longer shows them.
- What the HAL really does is the picture mode in `persist.vendor.sys.pq.picmode`. In mode 0 it uses the factory tables and ignores the index props. In mode 2 ("user defined", what MiraVision's own Picture mode page uses) it reads `contrastidx`, `gsatidx` (both 0 to 9, default 4), `gamma.index` (0 to 14, default 7) and `shp.idx` (0 to 11, default 2) at start and programs them into the display colour and gamma blocks through `/dev/mtk_disp_mgr`. `persist.vendor.sys.pq.shp.idx` was also ignored outside mode 2 before this change, so the old Sharpness slider had no effect in factory mode.
- Keys: `pq_mode` (0 factory, 2 custom), `pq_contrast`, `pq_sat`, `pq_gamma`, `pq_shp`, `pq_color` (MDP video colour index, effect not verified). Values are checked against whitelists in `valid()` before they reach a prop. Nothing is written until a key is set, so an untouched install behaves as before.
- Crash safety: after the restart the daemon waits 3 seconds. If `init.svc.pq-2-2` is not `running` it sets `picmode` back to 0, saves `pq_mode=0` and starts the service again.
- Readback: the daemon publishes `pq_live` (service state, picture mode, and the `gsat/cont/bri` values the HAL logged when it last programmed the display in user mode, taken from `logcat -s PQ`; the values show only while logging is on). The Picture page shows it in the Engine card. There is no node that reports the display colour block state, so the real check is a grey ramp on screen.
- Not implemented, kept as a fallback: a SurfaceFlinger colour matrix. The Android 17 `surfaceflinger` still handles debug transaction 1015 (4x4 colour matrix, `i32 1` then 16 floats, column major, last row 0 0 0 1) and 1022 (saturation, one float from 0 to 2), and `service call SurfaceFlinger` reaches it from a root shell. The MediaTek HWC forwards the matrix as 16 integers (value x 1024) to `/dev/mtk_disp_mgr` (`PqDevice::setColorTransformViaIoctl`), so it runs in the display colour correction block and costs no GPU time. Whether the kernel honours the offset column, which a contrast curve needs, is not known, and the framework overwrites the matrix when Night light, colour correction or invert colours change. Saturation through 1022 has no such problem.

## Layout

The app picks its layout from the window size, and rebuilds the view tree when the size changes (the manifest handles `orientation|screenSize|screenLayout|smallestScreenSize|density` itself, so a rotation does not restart the activity). The rules are plain functions in `src/org/tablet/tuner/Layouts.java`, tested by `tests/LayoutTest.java` (run by `sh tests/test_apps.sh`).

### Search and grouping

The home screen and the section list are grouped by task: Speed (performance details, overclock, profiles, app speed), Screen (picture, look, display and touch) and Device (memory and storage, connections, system). A search box sits under the title (top of the left pane in two panes). It matches every word of the query against the title, the one line description, the page name and the group name of each setting, and lists the hits with their page and group. Tapping a hit opens the page, scrolls to the row and flashes it. In two panes the results fill the right pane, and clearing the box brings the section back.

The search index is not a hand written list: the first search builds every page (except the app list) into a scratch column and records each row, so it follows the pages as they change. The matching and ranking are in `src/org/tablet/tuner/Search.java`, tested by `tests/SearchTest.java` (run by `sh tests/test_apps.sh`). Rows within a card are now separated by a 2 dp gap in the page colour instead of a hairline, and the cards and titles are a little larger.

### Home (Overview)

The home screen is the Overview. Top to bottom: the search box, a live graph of the last 90 seconds (CPU fast and slow cores, GPU, chip temperature), "How should it run?" with six cards, a panel listing what the chosen mode changes compared with Balanced, a Fine tune grid of area tiles, and a bar at the bottom. In two panes the Overview is the first section and the grid is not shown (the left list does that job).

- The five mode cards (Battery, Balanced, Smooth, Performance, Extreme) have a drawn icon (`GlyphView`, no image files) and Speed, Battery and Heat meters. The sixth card, Custom, is lit when the settings are not one of the modes. It shows the profile in use; tapping it opens a sheet with the saved profiles (tap to apply, Delete, Save current settings, and Update when the profile in use was changed).
- The bottom bar says which profile is in use and whether it changed (the app cannot read the profile values, only whether the daemon cleared `profile_active`, so it cannot count the changes). The Save as profile button is only there when there is something to save.
- Picture edits wait for Apply. A preview shows the wallpaper in two halves: the left as the display looks now, the right with the extra contrast and saturation the pending sliders would add (an estimate, `PicMath.java`; gamma and sharpness are not shown). The daemon copies the wallpaper to `files/preview.jpg` on the `preview_photo` request, because the app cannot read the system's wallpaper file; until then a drawn scene is used.
- A value changed in the app is kept for 12 seconds over the published state (`Pending.java`). Before, a read 2.5 seconds after a change brought back the old number, because the picture engine needs about 4 seconds to restart and publish.

### Layout

A screen at least 760 dp wide (the tablet in portrait is 800 dp, in landscape 1280 dp) gets the rail layout in both orientations: a narrow rail of section icons on the left, and on the right a header (section name, status, search box) over the section. Narrower screens (phones) keep the single column with the Fine tune grid. In the Overview the six mode cards sit in one row when the right pane is at least 1000 dp wide (landscape) and in two rows of three otherwise. The save bar floats over the right pane and is not there when the profile in use matches the settings.

### App speed list

The state of each app comes from `cmd package art dump`. Three fixes: the 64-bit status of the base apk is used (the 32-bit line of webview stayed `verify` and showed it as not compiled); packages the dump has no entry for (overlays, resource packages) show as No code instead of Not compiled; and the cleanup that removes stale copies in `/data/dalvik-cache` (`fixpkg`, and `fixcache` in `dexopt_all.sh`) no longer touches `/vendor`. The vendor image still carries Android 10 odex files that this ART rejects, so the cleanup took them for good copies and deleted the fresh compile of apps such as EmCamera, which then fell back to run-from-apk and showed Not compiled again after every run.

### Boost while CarPlay is open

Performance details has a switch, "Boost while CarPlay is open" (key `carplay_boost`). The daemon checks every 3 seconds whether DiPlay (`com.shihab.diplay`) or its fork Pillion (`org.tablet.pillion`) is the top app (a running session that is not on screen has a higher `oom_score_adj`). If the preset is Battery, Balanced or Smooth it runs the Performance preset and keeps a snapshot in `/data/local/tmp/tuner.boost`; when the app leaves the screen it puts the snapshot back, unless the preset was changed meanwhile (then the user's choice wins and the snapshot is dropped). Custom settings, saved profiles, Performance and Extreme are never touched.

### Fonts, pure black, navigation and other fixes (2026-11-01)

- **Fonts.** The Pixel build already ships Google Sans, Google Sans Flex, Lato, Rubik, Barlow and Karla with their families in `/product/etc/fonts_customization.xml`, but only one font overlay (Noto Serif) was installed, so the Font choice had almost nothing to pick from. `overlays/` holds an overlay per family (`overlays/build.py` rebuilds them). The GSI is signed with the public AOSP platform test key, so overlays signed with it install with `adb install` and need no reboot. The daemon also had to enable the chosen font itself (`cmd overlay enable-exclusive --category`): this SystemUI never switched font overlays from the theme setting, so the Font row only changed the setting and never the font.
- **Pure black.** `org.lineageos.overlay.customization.blacktheme` came with LineageOS and is not in the AOSP GSI, so the switch did nothing. `overlays/BlackTheme.apk` is a replacement (black surfaces, slightly lifted containers). It must sit above the dynamic palette overlay, so the daemon runs `cmd overlay set-priority ... highest` after enabling it. The tuner itself uses `system_surface_dark` and `system_surface_container_high_dark`, so it turns black with the rest.
- **Navigation.** The daemon called `cmd overlay enable-exclusive --category <category name> <package>`, but `--category` is a flag without a value, so the call failed and swipe gestures never switched on. Fixed; the three button and gesture modes both apply, and the gesture from the bottom edge reaches the launcher.
- **Display size.** Ten steps from 160 to 320 and Stock. The daemon rejects values outside 120 to 400.
- **Text.** Text weight (Normal to Extra bold, `font_weight_adjustment`) and High contrast text (`high_text_contrast_enabled`) on the Display page. There is no ClearType style subpixel text on Android.
- **Picture brightness.** `persist.vendor.sys.pq.brightidx`, 0 to 9. The PQ service maps the steps unevenly (1 to 0, 2 to 1, 3 to 2, 4 to 3, 5 and 6 to 4, 7 to 5, 8 to 6, 9 to 7), and 4 gives the factory step. It joins the Picture sliders and waits for Apply.

### Look (Material 3)

Lists are segmented: each setting is its own tile on a `surface container` tone, with large (28 dp) corners on the outside of a group and small (6 dp) corners between tiles, 2 dp apart, and a ripple on press. In two panes the selected section is a secondary container tile. The preset dial, its name and the clocks sit in one hero card. Colours are the system palette (`system_accent*`, `system_neutral*`), so they follow the wallpaper and light or dark mode. The search box and the Graphs and State tabs are fully rounded; the back button is a 48 dp circle. Row descriptions are 14 sp.

### Live page and the clocks in the dial

The half ring of the preset dial shows the current readings inside it: CPU clocks (fast and slow cores) large, then GPU clock, chip temperature and free RAM. They update once a second. The separate readout card is gone from the home screen and from the left pane.

The Live section (first in the Speed group) has two tabs. Graphs shows the last 90 seconds of CPU, GPU, chip temperature and free memory as step plots, with faint rules at the chip's real clock steps and a dashed line at the 85 C guard. State lists what is set (preset, profile, overclock, thermal limit) and what the hardware is doing now (GPU clock, battery, memory). The readings are sampled every second while the app is open, by `sample()` in `MainActivity`; the history is `Hist.java` (tested by `tests/HistTest.java`) and the plot is `LiveGraph.java`.

- Narrow (width under 840 dp, or portrait): one centred column, the home page with a dial and a list, each area a full page with a back arrow. This is what a portrait tablet shows, including the 1000 dp wide portrait of the TB-X606V (`WIDE_IN_PORTRAIT` in `Layouts.java` turns two panes on there too).
- Wide (width 840 dp or more and wider than tall, so the tablet in landscape, 1600 x 1000 dp): two panes. The left pane is 360 to 420 dp wide and holds the title, a compact preset dial, the live readout as a 2 x 2 card and the list of areas (Performance details, Overclock, Profiles, Picture, Look, Display and touch, Memory and storage, Connections, System, App speed). Tapping an area swaps the right pane with a 150 ms crossfade (none when animations are off) and the choice is remembered. On a screen 720 dp high or more the dial and readout stay put and only the list scrolls; on a shorter one the whole left pane scrolls.
- The right pane shows the area's page. From about 720 dp of content width (landscape on this tablet) the cards of settings sit in two columns, each card going to the shorter column. Hints and warnings span both. The App speed page puts the progress block, compile button, search and filter on top and lists the apps in two columns (from 640 dp) or three (from 1000 dp), still 60 at a time with Show more.
- A rebuild keeps the selected area, the scroll position of the same page, the search text, the filter and the one second status tick. An open dialog may close.

## Build

Requirements: the Android SDK (build-tools 35, platform 34) and a JDK.

```sh
sh build.sh
```

The script runs aapt2, javac, d8, zipalign and apksigner and writes `RomTuner.apk`. It generates a throwaway signing key, `tuner.keystore`, on the first run and does not commit it. An APK signed with a different key must replace the system copy, or the old app must be uninstalled first.

## Install as a system app

```sh
adb root
adb shell mount -o remount,rw /
adb shell mkdir -p /system/app/RomTuner
adb push RomTuner.apk /system/app/RomTuner/
adb push device/tablet_tunerd.sh /system/bin/
adb push device/tablet_tunerd.rc /system/etc/init/
adb shell 'chmod 755 /system/bin/tablet_tunerd.sh; chmod 644 /system/app/RomTuner/RomTuner.apk /system/etc/init/tablet_tunerd.rc'
adb shell 'restorecon -R /system/app/RomTuner /system/bin/tablet_tunerd.sh /system/etc/init/tablet_tunerd.rc'
adb reboot
```

The boot animation option copies one of `bootanimation-gemini.zip` (the default), `bootanimation-arrow.zip`, `bootanimation-galaxy.zip`, `bootanimation-aurora.zip` or `bootanimation-stock.zip` from `/system/product/media` over `bootanimation.zip`. The image builder installs the gemini, arrow, stock and (with `--galaxy`) galaxy files; the aurora file is not in this repository. `boot-animation/bootanimation-gemini.zip` is the Pixel 10 Gemini animation (see `NOTICE-gemini.md`; `make_gemini_fit.py` rebuilds it from the original), `boot-animation/bootanimation-arrow.zip` is the ArrowOS animation, and `boot-animation/make_galaxy.py` renders the galaxy one. `build/build_image.py` in the parent directory installs all of this into a system image, so a manual install is only needed on a running ROM.

Saved profiles also store the overclock keys (`oc_master`, `oc_cpu_uv`, `oc_gpu`). Applying a profile that has them switches the overclock on or off as saved (the crash and thermal guards still apply); a profile saved earlier leaves the overclock unchanged.

Saving over a profile. The daemon remembers which saved profile the settings came from in the state key `profile_base`. `profile_active` is the profile while the settings still match it and is cleared by any edit; `profile_base` survives edits. It is set by `apply_profile`, `save_profile` and `update_profile`, and cleared when that profile is deleted or a dial preset (`preset`) is applied. It is published in the state copy for the app, and cannot be requested. An old daemon publishes no `profile_base`, and the app then shows no update row.

| Request | Value | Does |
|---|---|---|
| `update_profile` | name of an existing profile | Overwrites that profile with the current settings (all keys in `PKEYS`, including `oc_master`, `oc_cpu_uv`, `oc_gpu`), keeping its place in the list, then sets `profile_active` and `profile_base` to it. An unknown name (exact match, case matters) is logged and ignored. Names follow the same character rules as every request value (`A-Z a-z 0-9 . _ -`) |

`save_profile` with a name that already exists also overwrites it in place (one line per name, a duplicate line is collapsed), and sets `profile_active` and `profile_base`. Names are compared as whole strings, never as patterns.

In the app, after an edit the home screen shows a row `Save changes to "<name>"` (Profiles list, and under the dial caption `Based on <name>, changed. Save the changes or pick a preset.`). It asks Overwrite or Cancel, then sends `update_profile`. No row is shown while the profile is unmodified, when the base was deleted, or when the daemon sends no `profile_base`. The rule is `Profiles.canUpdate` in `src/org/tablet/tuner/Profiles.java`, tested by `tests/ProfilesTest.java` (run by `sh tests/test_apps.sh`). `sh tests/test_profiles.sh` runs `profile_save`, `profile_apply`, `profile_delete` and the request handler against temp files: replace not duplicate, `update_profile` validation (unknown name, `a;reboot`, `$(reboot)`, spaces, `|`, empty), and the `profile_base` lifecycle.
