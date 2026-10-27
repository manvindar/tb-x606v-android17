# Android 17 (DP11) for the Lenovo Tab M10 FHD Plus (TB-X606V): built by GitHub Actions

Built from public inputs by `.github/workflows/build-image.yml` with `build/build_image17.py`: Google's Android 17 GSI (DP11.261002.005, `aosp_arm64`), the LineageOS 23.2 GSI as donor for the Android 16 audio stack and VNDK 29,
and MindTheGapps for the gapps image. Every patch in `tools/` is applied by the script; nothing is edited by hand.

## Files
- `system-a17-tb-x606v-gapps-nodexopt.img.xz`: with MindTheGapps (Play services, Play Store, Services Framework, sync adapters). Google's apps, under Google's terms.
- `system-a17-tb-x606v-nogapps-nodexopt.img.xz`: the same image without them.
- `SHA256SUMS.txt`

Both unpack to a raw ext4 image of 4085252096 bytes (the partition size). Dolby Atmos is not included.

## What is in this build
Everything the tablet needed, found and fixed on the tablet itself: the Android 17 boot and BPF fixes, audio (Android 16 audioserver), Bluetooth, GPS, Maps, video, brightness, plus:
- **Speed:** window blur off (`persist.sysui.disableBlur`), the USAP process pool on, `traced` off, and a first-boot job that compiles the apps (below).
- **ROM Tuner, redesigned:** a preset dial (Battery to Extreme), live CPU, GPU, memory clock, free RAM and temperature, saved profiles, a Picture section for MediaTek MiraVision (sharpness is off by default, it made video look worse), and a Widevine readout under System.
- **Real device identity:** the tablet reports Lenovo TB-X606V instead of "AOSP on ARM64" (streaming apps choose their quality by model).
- **Network location:** the `services.jar` patch lets Play services' provider bind, and the first-boot job sets its location app ops and opt-in, so Wi-Fi positioning returns fixes (a fix indoors in 20 seconds on the tablet). GPS needs a clear sky.
- **Chrome works:** Android 17 starts Chrome's renderer through a native zygote that needs `clone3` and capability numbers kernel 4.9 lacks. `libc` now carries two small stubs for that (`tools/libc/patch_libc.py`, see the README).
- **Dolby support in the installer:** the vendor edits now also remove the `deep_buffer` audio output, which is what made Dolby inaudible (Dolby's effect sits on the primary output only). Dolby Atmos itself is not in the image.

## Compiled apps
These images carry **no precompiled app code** (compiling needs a booted tablet). Instead the image starts a one-time job three minutes after the first boot that compiles the Google apps and system apps in speed mode (10 to 15 minutes, background priority),
and again about 45 minutes later for apps the Play Store updated in between. Until it is done the tablet is slow. To do it by hand: `tools/scripts/dexopt_on_tablet.sh`. Log: `/data/local/tmp/dexopt.log`.
The `v17-dp11-r1` and `r2` releases are dumps of a tested tablet and are already compiled (Play services excepted).

## Install (wipes all data)
Needs stock Android 10 firmware (tested on V9.56), unlocked bootloader, TWRP in recovery, USB debugging on, platform-tools and `xz`.
```sh
git clone https://github.com/manvindar/tb-x606v-android17 && cd tb-x606v-android17
tools/scripts/backup_partitions.sh                                    # tablet in TWRP (Volume Up + Volume Down), keep the files
tools/scripts/install_a17.sh ~/Downloads/system-a17-tb-x606v-gapps-nodexopt.img.xz
```
`install_a17.sh` flashes a verification-disabled `vbmeta`, flashes `system`, formats `userdata`, erases `metadata` and `cache`, applies the vendor edits from TWRP and boots. `recovery`, `boot`, `lk` and `nvram` are never written.
Use at your own risk: a bad flash can need TWRP or a BROM tool to recover (`tools/restore_stock_boot.md`).
