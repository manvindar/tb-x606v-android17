# TWRP flash steps (TB-X606V, Lenovo Tab M10 FHD Plus)

**Read first.** These steps put the files of this release on a tablet that is *already* on the proven setup (boot-r5b + r6g module + the earlier Android 17 system). They are written from the public repo's install scripts and the facts in `README-ROM.md`. **The system image and the new kernel in this release were never flashed or booted by us, so no step below is "tested with these exact files".** Flashing the new system image is at your own risk; the proven boot image is the safe choice.

## 0. What you can flash

| Piece | File | Partition | Status |
|---|---|---|---|
| Boot (proven) | `boot-r5b-proven.img` (sha256 414060fe...) | `boot` | Booted and ran on a tablet. Use this one. |
| Boot (experimental new kernel) | `boot-candidate.img` (ca321c47...) | `boot` | Failed CI guards, never booted. Optional. |
| System | `system-a17-tb-x606v-{nogapps,gapps}-nodexopt.img.xz` | `system` | Rebuilt, never booted on a tablet. |
| Graphics module (required) | `pvr113-stage-r6g.zip` (66f5a2d3...) | Magisk module in `/data` | Observed working on one tablet. |

Never flash anything else from here. `recovery`, `lk`, `nvram`, `vendor`, `vbmeta` are not touched by these steps.

## 1. Before you start (all of these are required)
1. Bootloader unlocked, TWRP installed in the recovery partition, USB debugging on.
2. The tablet already runs the Android 17 build with the **vendor edits** (fstab flags, `/metadata`, ion permissions, seccomp, codec entries). The vendor partition is *not* in this release. If the tablet is still on stock Android 10, stop here and follow `docs/TWRP_INSTALL.md` first.
3. Magisk 31.1 is in the boot image (the ramdisk is from boot-r5b) and the **r6g module is installed and enabled**. Check in Magisk: module `pvr113_stage_review` on. If it is not installed, see section 7.
4. `adb` and `fastboot` (Android platform-tools), `xz`, and a computer. On the tablet: battery at least 60 percent and **at least 4.5 GB free in internal storage** (the unpacked system image is 4,085,252,096 bytes).
5. Download the files from the release page into one empty folder and check them:
   ```sh
   shasum -a 256 -c SHA256SUMS      # every line must say OK
   xz -t system-a17-tb-x606v-nogapps-nodexopt.img.xz
   ```
   Pick **nogapps** or **gapps**, not both. Unpack: `xz -dk system-a17-tb-x606v-nogapps-nodexopt.img.xz`. The unpacked hash must equal the line in `raw-image.sha256` (nogapps `fc3ee90c...`, gapps `82307db4...`).
6. **Back up first.** In TWRP: Backup, tick **Boot** and **System** (and Vendor if you want), swipe. Also keep a copy of the proven boot image on the computer (`boot-r5b-proven.img`). A full partition dump is safer: `tools/scripts/backup_partitions.sh` in the repo.

## 2. Boot TWRP
- From Android: `adb reboot recovery`. From off: hold Power + Volume Up until the Lenovo logo, release when TWRP appears (or Power + Volume Down for the bootloader, then `fastboot reboot recovery`).
- If TWRP asks for a password, cancel it. If it offers read-only mode, swipe to **Allow modifications** (do not keep read only).
- Check the computer sees it: `adb devices` must show the tablet as `recovery`.

## 3. Flash the boot image (proven, recommended)
Pick **A** or **B**. Use the same file you checked in step 1.5.

**A. In TWRP (touch):** `adb push boot-r5b-proven.img /sdcard/` then Install, **Install Image** (bottom right), select the file, choose partition **Boot**, swipe to flash.

**B. From the computer, bootloader:** `adb reboot bootloader`, wait for `fastboot devices` to list the tablet, then
```sh
fastboot flash boot boot-r5b-proven.img
```
(this is how the proven setup was installed). Then `fastboot reboot recovery` to return to TWRP.

To use the experimental kernel instead, flash `boot-candidate.img` the same way. If it does not boot, reflash `boot-r5b-proven.img` (step 3) from TWRP or fastboot.

## 4. Flash the system image (only if you want the rebuilt system)
Skip this section to keep your current system and only change boot.

1. In TWRP, do not wipe anything. Do **not** run Format Data.
2. Copy the raw image to the tablet: `adb push system-a17-tb-x606v-nogapps-nodexopt.img /sdcard/system.img` (about 4 GB, a few minutes; `/data` must stay mounted).
3. In TWRP: Install, **Install Image**, select `system.img`, choose partition **System**, swipe. Do not choose Data, Vendor or Boot.
4. Alternative from the computer, bootloader (the repo's tested path for system images): `fastboot flash system system-a17-tb-x606v-nogapps-nodexopt.img`. This takes several minutes with no progress bar on some builds; do not unplug.
5. Verify the written bytes before rebooting. In TWRP, on the computer:
   ```sh
   adb shell 'dd if=/dev/block/by-name/system bs=1048576 count=3896 2>/dev/null | sha256sum'
   ```
   The result must equal the unpacked image hash from step 1.5 (the image is exactly 3,896 MiB). If it differs, flash again; do not boot.

Keeping `/data` means this is a **dirty flash**: apps, the Magisk module and ART caches from the old system stay. That is what the graphics module needs, but a first boot with a leftover cache can be slow or fail. If the boot loops, do the wipe in section 6.

## 5. Reboot and first checks
1. TWRP: Reboot, **System**. If TWRP offers to install itself or root, decline.
2. First boot takes several minutes (the image is not pre-compiled, so Settings and apps are slower at first).
3. Do not reboot again during the first 5 minutes. After it reaches the lock screen check, from the computer:
   ```sh
   adb shell getprop ro.build.fingerprint
   adb shell getprop vendor.debug.hwc.bypassMDP        # expect 0 when the r6g module is active
   adb shell dumpsys SurfaceFlinger | grep -i "GLES"   # expect a PowerVR 1.13 GLES string
   adb shell uname -a                                  # candidate kernel has a different build date than r5b
   ```
4. Play a video and press volume: the video must stay visible (no green) under the overlay.

## 6. If it does not boot (recovery order)
1. Hold Power + Volume Up into TWRP again.
2. Reflash `boot-r5b-proven.img` (section 3). A new kernel that fails to boot is the most likely reason.
3. Still failing: restore the TWRP **System** backup from step 1.6 (Restore, select the System and Boot backup, swipe) or reflash your previous system image.
4. Still looping after a system flash with the old `/data`: in TWRP **Wipe, Advanced Wipe, Dalvik/ART cache + Cache** only. Last resort is Format Data, which **erases the tablet's user data and the Magisk module**; you then need to reinstall the module (section 7).
5. If a boot loop disables Magisk modules (safe mode), the stock 1.11 graphics userspace runs on the 1.13 kernel: the known zygote-loop state. The fix is the r6g module (section 7) plus the proven boot.

## 7. The graphics module (required) on a tablet that does not have it
`pvr113-stage-r6g.zip` is a Magisk module archive (id `pvr113_stage_review`, 12 renamed libraries plus `system.prop`). The route that worked used a private root script that is **not part of this release**. Two things matter, and neither has a tested TWRP procedure we can give you:
- The module must be installed with the **stored (uncompressed) symlinks preserved**; Magisk's busybox `unzip` once left a partial module on a tablet and caused a boot loop.
- It must be in place and **enabled before the first boot of the 1.13 kernel**; the proven order was: stage the module disabled, flash the kernel, enable the module, reboot once.

If you want to try it: Magisk app, Modules, Install from storage, pick the zip, do not reboot yet, flash the kernel, then reboot once. This is untested here.

## 8. What was and was not tested
- Tested on the host: file checksums, `xz -t`, boot image headers, pins, the fake-device tests of an older installer.
- Tested on a tablet: only `boot-r5b` + the r6g module with the earlier system image.
- **Not tested on a tablet:** these exact TWRP steps, `boot-candidate.img`, the rebuilt system image, the ROM-Tuner fix.
