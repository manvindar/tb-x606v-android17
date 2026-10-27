# Android 17 (DP11) for the Lenovo Tab M10 FHD Plus (TB-X606V): built by GitHub Actions

Built from public inputs by `.github/workflows/build-image.yml` with `build/build_image17.py`: Google's Android 17 GSI (DP11.261002.005, `aosp_arm64`), the LineageOS 23.2 GSI as donor for the Android 16 audio stack and VNDK 29,
and MindTheGapps for the gapps image. Every patch in `tools/` is applied by the script; nothing is edited by hand.

## Files
- `system-a17-tb-x606v-gapps-nodexopt.img.xz`: with MindTheGapps (Play services, Play Store, Services Framework, sync adapters). Google's apps, under Google's terms.
- `system-a17-tb-x606v-nogapps-nodexopt.img.xz`: the same image without them.
- `SHA256SUMS.txt`

Both unpack to a raw ext4 image of 4085252096 bytes (the partition size). Dolby Atmos is not included.

## Difference from the maintainer-built releases
These images have **no precompiled app code** (compiling needs a booted tablet, `dex2oat` does not run offline). The first boot is slow and Settings takes a long time to open until the apps are compiled.
After the first boot, with the tablet connected over adb, run `tools/scripts/dexopt_on_tablet.sh` (10 to 15 minutes, once). The `v17-dp11-r*` releases are dumps of a tested tablet and are already compiled.

## Install (wipes all data)
Needs stock Android 10 firmware (tested on V9.56), unlocked bootloader, TWRP in recovery, USB debugging on, platform-tools and `xz`.
```sh
git clone https://github.com/manvindar/tb-x606v-android17 && cd tb-x606v-android17
tools/scripts/backup_partitions.sh                                    # tablet in TWRP (Volume Up + Volume Down), keep the files
tools/scripts/install_a17.sh ~/Downloads/system-a17-tb-x606v-gapps-nodexopt.img.xz
```
`install_a17.sh` flashes a verification-disabled `vbmeta`, flashes `system`, formats `userdata`, erases `metadata` and `cache`, applies the vendor edits from TWRP and boots. `recovery`, `boot`, `lk` and `nvram` are never written.
Use at your own risk: a bad flash can need TWRP or a BROM tool to recover (`tools/restore_stock_boot.md`).
