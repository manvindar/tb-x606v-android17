#!/system/bin/sh
# Enters the Ubuntu 22.04 arm64 root file system in /data/local/ubuntu (chroot on Android's own kernel). Run as root (adb root). Usage: ubuntu.sh [command...]
R=/data/local/ubuntu
m() { grep -q " $R/$2 " /proc/mounts || mount $1 $R/$2 2>/dev/null; }
m "-t proc proc" proc; m "-t sysfs sysfs" sys; m "-o bind /dev" dev; m "-t devpts devpts" dev/pts
mkdir -p $R/tmp $R/run; chmod 1777 $R/tmp
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > $R/etc/resolv.conf
echo 'APT::Sandbox::User "root";' > $R/etc/apt/apt.conf.d/99android-root     # _apt loses the Android network group
echo 'Acquire::ForceIPv4 "true";' > $R/etc/apt/apt.conf.d/99ipv4
unset TMPDIR; export HOME=/root TMPDIR=/tmp TERM=${TERM:-xterm} LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
[ $# -gt 0 ] && exec chroot $R /bin/bash -c "$*"
exec chroot $R /bin/bash -l
