# ROM Tuner source

A copy of the ROM Tuner app and its root daemon (`device/tablet_tunerd.sh`) as used by the Android 17 image. The original home is the Android 16 port repo
(github.com/manvindar/tb-x606v-android16-port, `rom-tuner/`), where the boot animations also live. `build/build_image17.py --port-repo DIR` reads the app, the daemon and the boot animations from that repo.

Build the APK: `sh build.sh` here (aapt2, javac, d8, zipalign, apksigner; a signing key is generated on the first build and is not committed).
Open a page directly: `adb shell am start -n org.tablet.tuner/.MainActivity --es page picture` (pages: perf, memory, picture, look, display, connect, system).
