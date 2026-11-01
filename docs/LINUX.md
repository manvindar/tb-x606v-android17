# Ubuntu on the tablet (chroot)

A plain Ubuntu 22.04 arm64 root file system runs on Android's own kernel (4.9.190) through `chroot`. No second kernel, no virtualization (the kernel has none), no Termux needed. Needs `adb root` (the images are debuggable builds).

```sh
curl -LO https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz
adb root
adb push ubuntu-base-22.04-base-arm64.tar.gz /data/local/tmp/ubuntu-base.tar.gz
adb shell 'mkdir -p /data/local/ubuntu && cd /data/local/ubuntu && tar -xzf /data/local/tmp/ubuntu-base.tar.gz'
adb push tools/linux/ubuntu.sh /data/local/tmp/ubuntu.sh
adb shell sh /data/local/tmp/ubuntu.sh "apt-get update && apt-get install -y --no-install-recommends ca-certificates curl nano htop"
adb shell -t sh /data/local/tmp/ubuntu.sh            # an interactive shell inside Ubuntu
```

What `tools/linux/ubuntu.sh` takes care of (each of these broke the first attempt):
- mounts `/proc`, `/sys`, `/dev` and `/dev/pts` inside the root file system;
- writes a `resolv.conf` (Android's resolver does not exist in the chroot) and forces IPv4 for apt;
- tells apt to run as root: the `_apt` user is not in Android's network group (3003), so downloads fail otherwise;
- unsets `TMPDIR` (adb's shell sets `/data/local/tmp`, which does not exist inside, and `ca-certificates` failed on it).

Checked on the TB-X606V (Android 17 build, kernel 4.9.190): `uname`, `apt-get update`, package installs, HTTPS with curl and `neofetch` work. Not working or not tried: systemd (not started), a desktop (needs a VNC or X server; nothing installed), GPU acceleration (none), x86 programs (no `binfmt_misc` in the kernel), containers (no `VETH`, no `SQUASHFS`). Memory is shared with Android (3.8 GB in total).

Not a native boot: booting a Linux kernel on this tablet is blocked by missing mainline support for the MT8768 display, GPU, Wi-Fi and touch, and by the vendor kernel source for 4.9.190 not being public (see the notes in the README).
