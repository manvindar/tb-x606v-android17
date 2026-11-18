# TB-X606V full-ROM CANDIDATE (prerelease): guard-failed kernel, NOT device-validated

Lenovo TB-X606V (MT6765), Android 17 GSI. Built by GitHub Actions. **Nothing here was flashed or run on a tablet as a whole, and nothing is claimed flashing-ready.** CI overall conclusion was FAILURE: the kernel guards failed (below). Packaging was allowed because the kernel config and symbol-table receipts equal the earlier r5b CI run; that is a packaging gate, not a validation.

## Read first
1. **The kernel is a guard-failed, runtime-untested candidate.** It lives in `boot-candidate.img`, not in `system.img`.
2. **The graphics module is required.** The system image is not standalone: the kernel carries the DDK 1.13 GPU kernel module, and the userspace must be the r6g module (`pvr113-stage-r6g.zip`, Magisk module `pvr113_stage_review`). The stock 1.11 userspace on that kernel is a known zygote-loop state.
3. No successful whole-ROM device test exists. Only the older `boot-r5b` (414060fe) plus the r6g module was observed working on one unit (with the system image it already had, not the one in this release).
4. Remaining lag, persistence and kernel risks are listed below. Graphics and lag work is not complete.

## Assets (hashes in `SHA256SUMS`)
| File | What it is |
|---|---|
| `system-a17-tb-x606v-{nogapps,gapps}-nodexopt.img.xz` | Full system image: Android 17 DP11.261030.003 GSI, built by this repo's builder at commit 85b11cde with the inputs of `v17-qpr3-beta4-nonpixel-r1` (legacy graphics mapper provider ON, no Pixel layer), plus the ROM-Tuner fix. `raw-image.sha256` has the unpacked hashes. Both pass `xz -t`; CI ran e2fsck and the image verifier. |
| `boot-candidate.img` | **CANDIDATE.** Image.gz built by CI, repacked into the pinned proven boot-r5b header, ramdisk, DTBs and AVB blob (`docs/rom-full/assemble_boot_ci.py`; fed the r5b run's Image.gz it reproduces boot-r5b byte for byte). Receipt: `boot-candidate-receipt.json`. Never booted. |
| `boot-r5b-proven.img` | boot-r5b, sha256 414060fe. **The boot image to use.** It is the one that booted and ran on the unit with GPU 1.13 plus the r6g module. It is not the new kernel. |
| `pvr113-stage-r6g.zip` (66f5a2d3), `payload-r6g.psv` (5c8e454b) | The required graphics module. r6g = r6f plus 12 `.dynstr`-renamed donor libraries so stock `libgpu_aux` binds the stock `gralloc_extra`; fixes the green video under the volume overlay (owner observation, one unit). |
| `Image.gz`, `kernel.config`, `Module.symvers`, `build-exit.json` | Kernel build outputs. kernel.config 39337860 and Module.symvers 5584d327 equal the r5b run. Image.gz (6fd46760) is **not** byte-identical to the r5b Image.gz (96e6c5ce); the cause (build metadata or other) was not established. |
| `guard-receipts.json` | Every raw guard exit code, the export-accounting numbers, config gate, link deviation, DTB failure. |
| `graphics-{gapps,nogapps}.json` | Builder graphics verification reports (CI runner paths redacted). `device_validation` is false. |
| `provenance.json` | Source pins: kit, builder, port repo, GSI. |

## Guard failures (kept, not hidden)
Eleven guard exit codes exist. The workflow's "Require the guards" step checks nine; `private-apply` (0) and `private-compare` (1) are recorded alongside.
- Exit 0: ac107-compare, board-transplant, gpu113-assert, gpu113-integrate, lcm-registration, panel-compare, private-apply.
- **Non-zero: sensor-contract 1, config-gate 3, export-accounting 1, private-compare 1.**
- Config gate BLOCKED: `s5k4h7yxqtech1_mipi_raw` (no source; removed from the build, so the link deviates from stock) and `TOUCHSCREEN_MTK_NT36523W` (no code in the tree, dropped).
- Export accounting: 90 CRC differences and 7 absent exports against the shipping kernel. The pinned baseline was 82 and 6; the 8 `RGXHWPerf*` exports and `MTKSaveFrame` are new relative to that pin. `candidate_only_exports` 33.
- DTB build step failed (`achilles6_row_call.dtb`, exit 2). The candidate's DTBs are the pinned carrier's.
- The same nine-step exit-code pattern appeared in the r5b CI run 44240302936. That is not equivalence: a matching exit-code pattern, kernel.config or Module.symvers does not show that the kernel behaves the same, or that any guard passes. Differences found: export accounting is 90/7 (above the pinned 82/6), `private-compare` fails, and Image.gz differs from r5b's with the cause unknown. This note supersedes any earlier "identical / no regression" summary.
- `link-deviation.json`: the resulting Image is not stock-equivalent.

## Installation notes (information only)
- Intended order, proven on the unit with boot-r5b: stage the module disabled, flash the kernel, enable the module, reboot once. The installer is not part of this release; its checks pin boot-r5b's hash and build string and would stop for `boot-candidate.img` (different build string). A new kernel needs its own reviewed pin.
- The installer's step S8 has a recorded flow flaw and was never run end to end on a device. The repin to r6g was validated on the host only.
- Flashing the system image replaces `/system` only. `/data` (Magisk modules, ART caches) is kept. The boot ramdisk carries Magisk.
- Use `boot-r5b-proven.img` with the r6g module. `boot-candidate.img` is optional and experimental; if it fails to boot, reflash `boot-r5b-proven.img`.

## Property placement
`vendor.debug.hwc.bypassMDP=0` is set only by the module's `system.prop`. CI fails if the image's generic build.prop files mention it. The vendor partition is stock and not included.

## ROM-Tuner fix
`tablet_tunerd.sh` `bootanim()` remounted `/` read-write on every boot and never restored it. A writable `/` makes ART write dexopt output beside the APKs on the nearly full system partition (16 MB free), so `cmd package compile` died with SIGBUS. The patch (`docs/rom-full/tuner/`) remounts rw only if `/` was ro, then restores ro. A 5-case host test runs in CI. Not run on a tablet.

## Compiled app code
Not included (`nodexopt`). First-boot plan, untested on this image: with `/` read-only, run `cmd package compile -m speed -f com.android.systemui` (and the launcher); output goes to `/data/dalvik-cache` and loads after those processes restart. Done once on the unit (slight subjective gain, no measured claim). Persistence against background dexopt is unverified.

## Remaining risks
- **Kernel:** guards above; sensor, touch (NT36523W) and export gaps; never booted.
- **Lag:** the notification shade still lags (app-side GPU completion about 32 ms per frame, cause unresolved).
- **Persistence:** module and ART state live in `/data` and can diverge from a freshly flashed system.
- **Inputs:** GSI, donor and legacy GSI are pinned by SHA-256. MindTheGapps is the newest release at build time (tag in `gapps-tag.txt`, zip hash in `gapps-zip.sha256`).
- Only the non-Pixel edition was built. This repo's policy asks for both editions per Android release; no Pixel image is included.

## What was verified (host, from the CI artifact)
SHA256SUMS, `xz -t` on both images, boot image headers (v2, page 2048, gzip kernel), pins of r6g, boot-r5b, kernel.config and Module.symvers. **Not verified:** any on-device behaviour of the candidate boot or the system image.
