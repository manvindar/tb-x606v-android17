# Android 17 (DP11.261016.003) with the Pixel Tablet layer and Dolby Atmos for the Lenovo Tab M10 FHD Plus (TB-X606V), r6

Sixth Pixel Tablet build. This one was built by GitHub Actions (workflow `Build image`, edition `pixel`) from Google's public downloads (the GSI, the Pixel Tablet factory image, MindTheGapps) and the Dolby Atmos files of the r5 release. Experimental.

## This image has not been booted as a whole
Each change was checked live on the running tablet (the image `r1` plus live changes). `r1` booted and was used for days; `r2` to `r6` have not been booted as complete images. If a boot hangs, the boot watchdog reboots into TWRP after about 10 minutes and the boot log is in `/data/local/tmp/bootdiag/logcat.txt` (readable from TWRP).

## What changed since r5
- **The cellular radio and the IMEI work.** The Android 10 vendor declares only `android.hardware.telephony` and `.gsm`. Android 17 also needs `android.hardware.telephony.radio.access`; without it every radio service (modem, SIM, network, voice, data) was reported as not available, so the vendor's HIDL radio 1.4 was never opened and Settings had no IMEI. The image now declares `radio.access`, `calling`, `data` and `messaging` (`tools/telephony`). With that the phone process binds the vendor radio, powers the modem on and reads the IMEI. Tested without a SIM: calls, SMS and mobile data are not confirmed. VoLTE and Wi-Fi calling have no Android 17 service.

Everything else is r5: hotspot fix, WebView Beta as a selectable WebView provider, and the current ROM Tuner with the Hub mode tile.

## Read this first
- The Pixel apps and MindTheGapps are Google's binaries, under Google's terms, included at my own decision as a convenience; the repository has only the scripts to build this from your own download (branch `exp/pixel-tablet`, `docs/PIXEL.md`). Remove the release if that is a problem for you.
- The system partition has almost no free space (about 3 MB, plus ext4's reserve): do not add system apps.
- Known: the voice recognition microphone source returns clipped noise at 48 kHz (16 kHz is fine); Hub mode (the Pixel dock feature) and Device Health Services are not confirmed; theme packs show "coming soon"; no 4K in YouTube; no USB-C video; USB mode switching and DRM playback do not work. More in `docs/QUESTIONS.md`.

## Install (wipes all data)
Needs stock Android 10 firmware (tested on V9.56), unlocked bootloader, TWRP in recovery, USB debugging on, platform-tools and `xz`. Never flash `recovery`.
```sh
git clone https://github.com/manvindar/tb-x606v-android17 && cd tb-x606v-android17
git checkout exp/pixel-tablet
tools/scripts/backup_partitions.sh                                    # tablet in TWRP (Volume Up + Volume Down), keep the files
tools/scripts/install_a17.sh ~/Downloads/system-a17-tb-x606v-pixel-tablet-dp11-261016-r6.img.xz
```
`install_a17.sh` flashes a verification-disabled `vbmeta`, flashes `system`, formats `userdata`, erases `metadata` and `cache`, applies the vendor edits from TWRP and boots. `recovery`, `boot`, `lk` and `nvram` are never written. The file unpacks to a raw ext4 image of 4085252096 bytes. Use at your own risk. The SHA-256 is in `SHA256SUMS.txt`.
