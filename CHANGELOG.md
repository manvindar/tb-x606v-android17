# Changelog

Release history for **Lenovo TB-X606V**. Build checks and device observations are distinct; no entry establishes daily-driver stability.

## 2026-11-18 · Full ROM candidate

[Release: v17-rom-full-candidate-20261118](https://github.com/manvindar/tb-x606v-android17/releases/tag/v17-rom-full-candidate-20261118)

- Built non-Pixel QPR3 Beta 4 System images with and without Google apps from pinned inputs.
- Added the ROM-Tuner boot-animation change to restore a read-only root mount after its work; host-tested, not tested on the tablet in this rebuilt image.
- Included the new CI-built `boot-candidate.img`, the distinct `boot-r5b-proven.img` recovery reference and required r6g graphics module.
- Kept `vendor.debug.hwc.bypassMDP=0` in the module's `system.prop`, outside generic System build properties.
- Published checksums, provenance, guard receipts and release-specific flash instructions.
- Refreshed the repository overview and preserved the earlier README as historical setup notes.

**Kernel limits:** sensor-contract, config-gate, export-accounting and private-compare failed; the DTB build step failed. Export mismatches are 90/7 against the pinned 82/6 baseline. The new kernel binary differs from r5b; the cause is unresolved. Matching configuration/symbols does not establish equivalence. Neither the new Boot nor rebuilt System was boot-tested.

**Separate device observations:** r5b's CMDQ changes plus the matching module made video visible on the maintained tablet. r6g corrected green video under the volume overlay according to the owner. Runtime app compilation gave a slight subjective improvement; no measured gain or persistence is established. These observations do not validate the new bundle.

## 2026-11-16 · QPR3 Beta 4 non-Pixel r1

[Release: v17-qpr3-beta4-nonpixel-r1](https://github.com/manvindar/tb-x606v-android17/releases/tag/v17-qpr3-beta4-nonpixel-r1)

- Adopted Android 17 QPR3 Beta 4 `DP11.261030.003` for the non-Pixel System build.
- Recorded pinned inputs and legacy graphics compatibility requirements.
- Published as experimental. A matching Pixel edition for this base remains outstanding.

## 2026-11-04 · Earlier Pixel Tablet releases

[Pixel r5](https://github.com/manvindar/tb-x606v-android17/releases/tag/v17-dp11-261016-pixel-r5) · [Pixel r6](https://github.com/manvindar/tb-x606v-android17/releases/tag/v17-dp11-261016-pixel-r6)

- Published the Pixel Tablet edition on the older `DP11.261016.003` base.
- r5 release scope included hotspot fixes, WebView Beta and Hub mode; r6 was built through GitHub Actions.

These older releases and their hardware observations do not validate QPR3 Beta 4 or the November 18 kernel/System candidate. See individual release notes for exact component scope.
