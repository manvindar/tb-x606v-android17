# TWRP installer (one flash does everything)

`tools/twrp/make_twrp_zip.sh` turns a system image into a zip that TWRP installs by itself, with no computer commands:

```sh
tools/twrp/make_twrp_zip.sh system-a17-tb-x606v-gapps.img.xz a17-gapps-twrp.zip     # also accepts a raw .img
tools/twrp/make_twrp_zip.sh image.img.xz out.zip --no-wipe                           # keep userdata (only when coming from the same Android 17 build)
tools/twrp/make_twrp_zip.sh /dev/null test.zip --vendor-only                         # small zip: vendor edits and first-boot job only, no system write, no wipe
```

Copy the zip to the tablet (or adb sideload it), then in TWRP: Install, pick the zip, swipe. It needs the same starting point as the other install path: stock Android 10 firmware (V9.56), unlocked bootloader, TWRP. Take a backup first (`tools/scripts/backup_partitions.sh`).

What it does, in order (the screen prints each step):

1. writes the verification-disabled `vbmeta` (the stock one already has verification off, so this changes nothing on a stock tablet)
2. writes the system image in 1.5 GB chunks and checks the result against the image's sha256
3. applies the vendor edits (`tools/twrp/vendor_edits.sh`, the busybox version of `apply_vendor_a17.sh`): fstab flags and `/metadata`, `/dev/ion` permissions, seccomp syscalls, MP2 and VP9 codec entries, the AIDL lights declaration. Originals stay next to the files as `*.orig-a17`, `*.orig-mp2`, `*.orig-vp9`. Running it twice changes nothing.
4. installs the first-boot compile job into `/system` if the image does not have it already
5. erases userdata, metadata and cache (zeroes their first 16 MB; Android formats them on the first boot because the fstab marks them `formattable`)

It never writes `recovery`, `boot`, `lk`, `nvram` or any other partition.

## Compiling by itself

Compiling needs a running Android (the ART compiler runs on the tablet), so TWRP cannot do it. Instead every image now carries a one-shot job, `firstboot_dexopt.sh` with `zz_firstboot_dexopt.rc`: three minutes after the first boot it compiles the Google apps and the system apps in speed mode (`dexopt_all.sh`, 10 to 15 minutes, runs at background priority), and again 45 minutes later to catch apps the Play Store updated in between (an update resets them to interpreted code). Apps that are already compiled are skipped. It stops after the second pass (`/data/local/tmp/.dexopt_done`). Log: `/data/local/tmp/dexopt.log`.
To run it by hand from the computer: `tools/scripts/dexopt_on_tablet.sh`.

## Status

The vendor edit script was checked on this computer against the files of the working tablet: its output matches byte for byte for the ueventd file and the four seccomp policies, and line for line for both fstabs (whitespace aside). The audio codec file differs by one blank line. The `update-binary` itself was not yet run inside TWRP.
