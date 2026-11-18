# Changelog: kernel and ROM (TB-X606V, Android 17)

Dates are 2026-11. "Observed" means seen on one tablet by the owner or by read-only device checks. Nothing here is a validated release.

## What changed in this release (2026-11-18)
- New system images (nogapps, gapps) built by CI from the pinned builder (commit 85b11cde) and the ROM-Tuner fix below.
- `boot-candidate.img`: new CI-built kernel repacked into the pinned boot-r5b layout. **Failed guards, never booted.**
- `boot-r5b-proven.img` (414060fe) included as the boot image to use.
- `pvr113-stage-r6g.zip` / `payload-r6g.psv` included as the required graphics module.
- Guard receipts, provenance, checksums and this documentation added.

## Kernel

Base: a pinned third-party MT6765 4.9.190 tree (the stock-based kernel of the tablet) rebuilt by GitHub Actions with the PowerVR DDK 1.13 GPU driver, a board transplant for the TB-X606V, and private patches. The shipping kernel is 4.9.190 with the GPU 1.11 driver.

### r4 kernel (`boot-r4-v2`, observed booting, 2026-11-18)
- PowerVR DDK 1.13 GPU kernel driver integrated; with the matching userspace module the tablet reports GLES 1.13. Stock 1.11 userspace on this kernel is the zygote-loop state.
- Board and peripheral transplant: camera and board sources.
- Touch (FT8201): second-revision patch (`40`) and init reset through the reset GPIO (`50`).
- Proximity (SX9310): register table and power-management fix (`10`), probe register 0x10 (`20`), hardware-info naming (`30`), shipping key codes (`60`). The SX9310 register table follows the donor source; 13 of 25 registers differ from the shipping kernel's table.

### r5 / r5b kernel (observed running, 2026-11-18)
- Patch `70`: MDP CMDQ extended ioctls 20 to 24 (async execute, wait, readback slots), ported from the OPPO A15 4.9 MT6765 source. Reason: SurfaceFlinger's GPU path needs CMDQ ioctl 22; r4 rejected it, which left YouTube video black.
- Patch `71` (r5b): legacy CMDQ EXEC, ASYNC_JOB and WRITE_ADDRESS ioctls are rejected the way the stock kernel rejects them (native and compat paths), plus an out-of-memory guard.
- Result with the r6f/r6g module: video visible (owner, one tablet).

### CI candidate kernel (this release, `boot-candidate.img`, NOT booted)
- Same sources and config as r5b (kernel.config 39337860 and Module.symvers 5584d327 equal the r5b run), built by CI run 44383296067 from the same pinned kernel commit (213b300e).
- `Image.gz` 6fd46760 is **not byte-identical** to the r5b CI `Image.gz` (96e6c5ce). Cause unknown (embedded build metadata is possible; not established).
- Boot image assembled by `docs/rom-full/assemble_boot_ci.py`: the new Image.gz in the pinned boot-r5b header, ramdisk (Magisk 31.1), DTBs and AVB blob. Fed the r5b run's Image.gz it reproduces boot-r5b exactly (414060fe).
- **Guard results (retained, not passes):** sensor-contract 1, config-gate 3, export-accounting 1, private-compare 1. Passed: ac107-compare, board-transplant, gpu113-assert, gpu113-integrate, lcm-registration, panel-compare, private-apply. The DTB build step failed (`achilles6_row_call.dtb`); the DTBs are the carrier's.
- Open source gaps: sensor `s5k4h7yxqtech1_mipi_raw` (no source; removed from the build, so the link is not stock-equivalent) and touch `NT36523W` (no code in the tree, dropped).
- Export accounting: 90 CRC mismatches and 7 absent exports against the shipping kernel (pinned baseline 82 and 6; new: 8 `RGXHWPerf*` exports and `MTKSaveFrame`). The module gate passed 8 of 8 modules, which is necessary, not sufficient.

### Older public kernel work (not in this kernel)
The public 4.9.117 patches (F_SEAL_FUTURE_WRITE, MADV_WIPEONFORK, cgroup option fixes and others) belong to the earlier line, which failed on its GPU driver. This kernel is the stock-based 4.9.190 line.

## ROM (system image)

Builder: `tb-x606v-android17` commit 85b11cde, the same pinned inputs as release `v17-qpr3-beta4-nonpixel-r1`: Android 17 GSI DP11.261030.003, LineageOS 23.2 donor graphics files, legacy graphics mapper provider on, no Pixel layer. MindTheGapps is the newest release at build time (gapps image only). Images are `nodexopt` (no pre-compiled app code).

### Added or fixed since the previous non-Pixel release
- **ROM-Tuner bootanim fix.** `tablet_tunerd.sh` (8eb2114b to 6fa4c319): `bootanim()` remounted `/` read-write on every boot and never restored it. With `/` writable, ART wrote dexopt output beside the APKs on the nearly full system partition (16 MB free) and `cmd package compile` died with SIGBUS. It now remounts rw only when `/` was ro, copies, syncs and restores ro. CI runs a 5-case host test (stub `mount`). Not run on a tablet.
- **CI checks added:** `e2fsck` of the image, the image verifier, the tuner readback, and a check that no generic `build.prop` mentions `vendor.debug.hwc.bypassMDP`.
- **Property placement:** `vendor.debug.hwc.bypassMDP=0` comes only from the graphics module's `system.prop`.

### Fixes in the graphics module (separate from the image, required)
- **r6f:** `vendor.debug.hwc.bypassMDP=0` (with the r5b kernel the video layer then reaches the panel). Observed: video visible.
- **r6g (66f5a2d3):** fixes green video while the volume overlay is up. Cause: stock `libgpu_aux` bound `gralloc_extra_*` to the donor `libgralloc_extra_pvr13`, which answers the secure-buffer query in donor numbering, so unprotected video was refused as secure. Fix: 12 libraries get 3 same-length, same-hash `.dynstr` renames (`perform`, `query`, `sf_set_status`). No code change. Observed: green fixed (owner), jankiness slightly better.

### Performance and graphics notes
- **Compiled app code:** with `/` read-only, `cmd package compile -m speed -f com.android.systemui` (and the launcher) writes to `/data/dalvik-cache`. Done once on the tablet: slight subjective gain, no measured claim, persistence unverified. Not part of the image.
- **Vulkan:** the driver supports Vulkan 1.1 only (apiVersion 1.1.131, 59 extensions). The "No" rows in DevCheck are Vulkan 1.2, 1.3 and 1.4 features; nothing was changed to advertise them.
- **Known open:** notification shade lag (app-side GPU completion about 32 ms per frame; cause between workload, driver signalling and compositor pacing is unresolved; the trace is held).

### Not in this release
Pixel edition (repo policy asks for both per Android release; only non-Pixel was built), vendor partition, recovery, compiled app code, the installer and activation scripts.
