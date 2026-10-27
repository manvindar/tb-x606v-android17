# Android 17 (DP11) for the Lenovo Tab M10 FHD Plus (TB-X606V), first image release

Patched Google Android 17 GSI (build DP11.261002.005, `aosp_arm64`) on the tablet's stock Android 10 vendor and stock 4.9.190 kernel. Only the `system` partition is replaced.
The image is a dump of the tested system partition: every patch in this repository is already in it.

## Files
- `system-a17-tb-x606v-gapps.img.xz`: with MindTheGapps (Android 16 build): Play services, Play Store, Services Framework, sync adapters. About 290 MB larger once unpacked into the same partition.
- `system-a17-tb-x606v-nogapps.img.xz`: the same image with exactly those Google files removed (`tools/scripts/strip_gapps_image.py`).
- `SHA256SUMS.txt`

Both unpack to a raw ext4 image of 4085252096 bytes (the partition size). The Google apps are Google's proprietary software and are not covered by this repository's licence.

## Install (wipes userdata; never writes recovery or boot)
1. Tablet on stock Android 10 firmware with an unlocked bootloader and TWRP in recovery. If it refuses to boot a modified system: `fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img` with the tablet's own vbmeta.
2. `xz -d system-a17-tb-x606v-gapps.img.xz`, then in the bootloader (Power + Volume Down from off): `tools/scripts/flash_a17.sh system-a17-tb-x606v-gapps.img`.
3. Boot TWRP (Volume Up + Volume Down) and run `tools/scripts/apply_vendor_a17.sh` once: it edits the vendor partition (fstab flags, ueventd, seccomp, codec lists, AIDL lights declaration).
4. Reboot to system. The first boot takes several minutes. Then run `tools/scripts/dexopt_all.sh` once with adb, otherwise apps run in the interpreter and everything is slow.

## What works
Boot, GPU, Wi-Fi, Bluetooth (pairing, A2DP, HFP), audio output including apps, camera and video recording, gallery playback, YouTube with hardware H.264 and sound, brightness, keyboard, USB file transfer, Google Maps,
Play Store (gapps image), root adb, GPS (with sky in view).

## What does not
Cellular (calls, SMS, mobile data: the vendor radio HAL is 1.1), USB tethering and Widevine playback are untested, SELinux boots permissive and the image is debuggable.

## Notes
Android 16 audio libraries run privately for the audio HAL (`/system/lib64/a16audio`). ROM Tuner (performance profiles, theme, display, serial console switch) and its root daemon are included; the Gemini boot animation is the default.
Use at your own risk: a bad flash can need TWRP or a BROM tool to recover. See the README for every fix and its reason.
