# Android 17 (DP11) for the Lenovo Tab M10 FHD Plus (TB-X606V)

Patched Google Android 17 GSI (build DP11.261002.005, `aosp_arm64`) on the tablet's stock Android 10 vendor and stock 4.9.190 kernel. Only the `system` partition is replaced (plus a few vendor-file edits, below).
The image is a dump of the tested system partition: every patch in the repository is already in it, and the main system apps are already compiled (no slow first boot).

## Files
- `system-a17-tb-x606v-gapps.img.xz`: with MindTheGapps (Android 16 build): Play services, Play Store, Services Framework, sync adapters. Google's proprietary apps, under Google's terms.
- `system-a17-tb-x606v-nogapps.img.xz`: the same image without those Google files.
- `SHA256SUMS.txt`

Both unpack to a raw ext4 image of 4085252096 bytes (the partition size). Dolby Atmos is not included.

## Install: one command (wipes all data)
Needs: stock Android 10 firmware on the tablet (tested on V9.56, `TB-X606V_USR_S300665_2206300345_V9.56_BMP_ROW`), unlocked bootloader, TWRP in recovery, USB debugging on, platform-tools and `xz`.
```sh
git clone https://github.com/manvindar/tb-x606v-android17 && cd tb-x606v-android17
tools/scripts/backup_partitions.sh                                    # tablet in TWRP (Volume Up + Volume Down), keep the files
tools/scripts/install_a17.sh ~/Downloads/system-a17-tb-x606v-gapps.img.xz
```
The script flashes a verification-disabled `vbmeta` (`tools/vbmeta/vbmeta-disabled.img`; this tablet's stock vbmeta already has verification disabled, so it changes nothing there), flashes `system`, formats `userdata`,
erases `metadata` and `cache`, reboots into TWRP by itself, applies the vendor edits once (`apply_vendor_a17.sh`), and boots. `recovery`, `boot`, `lk`, `nvram` and the rest are never written. The first boot takes a few minutes.
The manual commands are listed in the README ("Install the prebuilt image").

## What works
Boot, GPU, Wi-Fi, Bluetooth (pairing, A2DP, HFP), audio including apps, camera and video recording, gallery playback, YouTube (hardware H.264, sound), brightness, keyboard, USB file transfer, Google Maps and Play Store
(gapps image), GPS with sky in view, ROM Tuner, Gemini boot animation, root adb.

## What does not
Cellular (calls, SMS, mobile data: the vendor radio HAL is 1.1), and USB tethering are untested; Netflix plays (Widevine level and resolution not measured), SELinux boots permissive and the image is debuggable.

Use at your own risk: a bad flash can need TWRP or a BROM tool to recover (`tools/restore_stock_boot.md`).

Not in these images: Dolby Atmos and the Pixel layer (both are optional builder inputs, see the README). There is no network (Wi-Fi) location; GPS needs the sky in view.
