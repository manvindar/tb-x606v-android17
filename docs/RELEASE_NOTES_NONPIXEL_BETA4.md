# Android 17 QPR3 Beta 4 for Lenovo TB-X606V — non-Pixel, experimental

This release updates the non-Pixel Lenovo port to Google's Android 17 QPR3 Beta 4 GSI, build `DP11.261030.003`, published November 12, 2026, with the October 2026 security patch level. It uses the GSI interface rather than the Pixel Tablet SystemUI, Settings, launcher and app layer. Dolby Atmos is not included.

## Downloads

- `system-a17-tb-x606v-gapps-nodexopt.img.xz`: includes MindTheGapps, Play Store and Google services.
- `system-a17-tb-x606v-nogapps-nodexopt.img.xz`: without MindTheGapps.
- `SHA256SUMS.txt`: checksums of the compressed images.
- `build-receipt.json`: exact source, input hashes and validation status.
- Graphics verification reports and image writer diagnostics: build evidence.

Both images unpack to 4,085,252,096 bytes. Apps compile after first boot; performance will be slow while that job runs.

## Validation and limitations

This is an experimental prerelease. These exact non-Pixel images have not been booted on a tablet. Boot, camera, video, network operation and performance are not claimed as verified. Switching to this image is not a verified fix for lag.

Beta 4 removed the legacy graphics mapper required by this tablet's Android 10 vendor. The port includes a checksum-pinned compatibility layer from the earlier Android 17 GSI and a source-pinned synchronization provider. The build runs regression tests, filesystem checks, image size checks, both-architecture graphics linkage checks and final image readback. Unknown graphics callers remain recorded, and `graphics_acceptance` and `device_validation` remain false in the receipt. Publication of this experimental candidate was explicitly requested by the repository owner; it does not change those validation results.

Target: Lenovo TB-X606V with its stock Android 10 vendor. TB-X606F is not validated.

## Installation

The repository's `tools/scripts/install_a17.sh` performs a clean install that wipes userdata, metadata and cache. Back up tablet data first. Use the installer and vendor instructions at the source commit linked in `build-receipt.json`; this release does not establish a safe upgrade that preserves data. No tablet was flashed or rebooted to publish this release.

Google apps included in the gapps image remain subject to Google's terms.

Build run: https://github.com/manvindar/tb-x606v-android17/actions/runs/43941641695
