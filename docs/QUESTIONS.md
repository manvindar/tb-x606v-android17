# Questions that came up on the TB-X606V (Android 17 build)

Short answers with the evidence behind them. Checked on the tablet unless marked.

## Battery always at 100%
`dumpsys battery` showed level 100, voltage 0, temperature -99. The kernel lists two battery supplies: `battery` (real) and `mm8013` (an external gauge chip that is not fitted; every value reads -99). The vendor health service read voltage and temperature from `mm8013`, and `battery/capacity` and `status` were stale (100 and Full) while the kernel's own gauge said about 66 percent and the tablet was charging. Fixed by an AIDL health shim (`tools/health`, see docs/ANALYSIS.md "Battery"): after the fix Android shows the real level, 4112 mV, 25.0 C, status charging. Behaviour after unplugging was not checked. Not in the r2 image (built before the shim).

## "Unknown" fields in CPU-Z
Battery voltage, temperature and health were the main ones (see above). Thermal sensors work (CPU, battery and skin temperatures come from the thermal service). The tablet has 14 sensors (accelerometer, light, proximity, step counter and gestures); it has no gyroscope, magnetometer or barometer, so those are missing for real.

## Bootloader shows "unknown"
The LK bootloader does not pass `androidboot.bootloader=` on the kernel command line, so init sets `ro.bootloader=unknown`. `build.prop` cannot override it (init sets it first, and it is read only). Cosmetic.

## Cast shows no devices
An mDNS browser run from the Ubuntu chroot (same network stack) found the Mac's AirPlay service but no `_googlecast._tcp` or `_googlezone._tcp` in 25 seconds, so no Cast receiver answers on 192.168.1.x. Check that the Chromecast or TV is on the same Wi-Fi and that client isolation is off.

## Theme packs say "coming soon"
After the picker kept Google's signature (docs/PIXEL.md item 11) the log shows `Successfully fetched PackWallpaperCategories`. The call works; the reply has no packs for this device. The pack list comes from a Google server and is gated by rollout or device eligibility. Not fixable from the image.

## Screen contrast boost and video contrast boost do nothing
The properties and the vendor PQ service are fine, but the display pipeline has no engine for them (the PQ HAL logs `not support video DRE` and `not support HDR` at every start; the kernel has `MTK_DRE30_SUPPORT` and `MTK_AAL_SUPPORT` off). ROM Tuner's Picture page now offers the controls that the HAL really applies in its custom picture mode: contrast, saturation, gamma and sharpness. See rom-tuner/README.md "Picture engine".

## No 4K in YouTube
Every hardware video decoder in the vendor codec list stops at 1920x1088 (H.264, HEVC, VP8, MPEG-2/4). 4K needs VP9, HEVC or AV1 decoding at that size. The software VP9 decoder stutters, so the image removes it (YouTube then uses hardware H.264, 1080p). The panel is 1200x1920.

## USB-C monitor output
Not possible: no DisplayPort or HDMI path in the kernel (`MTK_HDMI_SUPPORT`, `MTK_USB_TYPEC`, `TCPC_CLASS` are off), a single built-in display, and the port is USB 2.0. Use Cast or Miracast.

## Android 17 Linux terminal
Cannot run: no `/dev/kvm`, `CONFIG_VIRTUALIZATION` and `CONFIG_VSOCKETS` are off in the 4.9 kernel. Use the Ubuntu chroot in docs/LINUX.md.

## Windows 11 on ARM, native Linux boot
Windows: the Cortex-A53 cores have no ARMv8.1 atomics, there is 3.8 GB of RAM (4 GB minimum), no UEFI and no drivers. Native Linux: no mainline support for the MT8768 display, GPU, Wi-Fi and touch, and the 4.9.190 vendor kernel source is not public (the public Lenovo source is 4.9.117 for the X606F). A chroot on the Android kernel works (docs/LINUX.md).

## F1 TV "not compatible" in the Play Store
Likely the Play certification of the device (GSI build, unlocked bootloader, debuggable). Registering the Google Services Framework ID at google.com/android/uncertified lets uncertified devices sign in but usually does not change compatibility. Sideloading the APK avoids the Store check (not tried).

## Laggy after a live wallpaper
The "Kent" live wallpaper kept the CPU and GPU busy (about 11 percent CPU plus compositor load). A plain wallpaper fixed it.
