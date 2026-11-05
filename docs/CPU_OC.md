# CPU overclock kernel (TB-X606V)

The stock kernel stops the fast cluster (cpu0 to cpu3) at 2.30 GHz. The same kernel carries OPP tables for the faster bins of the chip with 2.50 GHz as the top step (same top
voltage, 1.11875 V in the table). `tools/kernel/patch_cpu_opp.py` copies one of them over the table this tablet uses (24 bytes in the kernel image). Nothing else changes, the
efficiency cluster (cpu4 to cpu7) stays at 1.80 GHz.

Why this works: `cpuhvfs_pvt_tbl_create` builds the frequency, voltage and divider table that the DVFS firmware reads from the kernel's OPP table at boot, and the EEM (PTPOD)
voltage logic scales the voltage with the frequency (it gives 1.125 V, its maximum, for the 2.50 GHz step). The README of ROM Tuner (`rom-tuner/README.md`, "What the kernel allows")
lists why a clock above the table cannot be set at run time.

## Result (November 4, 2026, this tablet)
- Boots, all four fast cores at 2.50 GHz for 3 minutes of full load: stable, chip temperature 63 to 78 C (thermal policy off).
- Compute loop on cpu0: 10.2 to 10.5 s at 2.50 GHz against 11.1 to 11.4 s at the 2.28 to 2.0 GHz steps (about 8 percent).
- Not tested: hours of load, summer heat, 2.50 GHz with the voltage trim below -12 mV, the second variant of the table (2397000 as second step).

## Build and flash
```sh
# boot image (the Magisk patched one), unpack on the tablet with Magisk's own tool
adb shell su -c 'dd if=/dev/block/by-name/boot of=/data/local/tmp/bt/boot_cur.img && cd /data/local/tmp/bt && /data/adb/magisk/magiskboot unpack boot_cur.img'
adb pull /data/local/tmp/bt/kernel kernel
python3 tools/kernel/patch_cpu_opp.py kernel kernel.oc
adb push kernel.oc /data/local/tmp/bt/kernel
adb shell su -c 'cd /data/local/tmp/bt && /data/adb/magisk/magiskboot repack boot_cur.img boot_oc.img'
adb pull /data/local/tmp/bt/boot_oc.img
adb reboot bootloader && fastboot flash boot boot_oc.img && fastboot reboot      # never recovery
```
`fastboot boot boot_oc.img` does not work on this tablet (the kernel panics: root file system missing); flash it. Restore: `fastboot flash boot` with the stock or the Magisk
boot image (`work/backup-a17/boot-stock-2026-11-02.img`, `boot-magisk-v31.1-2026-11-02.img`), or `dd` it from TWRP (Volume Up + Volume Down).

`tools/magisk/cpu-oc-guard` caps the fast cluster at the stock top step as early as Magisk can (post-fs-data), harmless on the stock kernel. ROM Tuner's Overclock page has the
CPU overclock list (stock, 2.38 GHz, 2.50 GHz) that lifts the cap again; without a choice there the kernel with the 2.50 GHz table behaves like the stock one.
