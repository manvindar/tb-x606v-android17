# Lenovo TB-X606V Android 17 QPR3 Beta 4 build candidate

Exact Google base: DP11.261030.003. Input identities are in BETA4-INPUTS.json.
The older public builder failed init plus 14/22 patch checks on this input.
The scoped port restores existing constructor, cgroup, binder and memory patches
using named/guarded sites and selects budget watchers from actual JNI entries.
Networking/Bluetooth patches require measured whole-file and instruction-window hashes.
Init permits only two exact reviewed binary identities; unknown/corrupt/already-patched inputs reject.

Beta 4 removed legacy HIDL mapper2/3 implementation. Compatibility staging uses only
prior Android 17 libui system/codec variants and missing HIDL interfaces from the
checksum-pinned DP11.261016.003 GSI. The codec package is repacked and signed, with
a real source-pinned LIBSYNC implementation and complete payload readback.
The MediaTek 0x2000 video-usage correction remains in the older libui provider.
Final verification runs separately on Google-apps and no-Google-apps images.
Hash/ABI/version/dependency/removed-export/flag-provider/AVB/readback/geometry guards remain mandatory.

Use experimental_graphics=true and publish=false for this unaccepted build candidate.
The option defers only unknown graphics caller coverage. Every such caller is recorded,
acceptance=false remains in receipts, and no M30s caller recognition is assigned to the tablet.
The standard experimental build route cannot publish a release. On November 16, 2026, the owner explicitly requested publication of the newest non-Pixel edition. The artifact verification workflow may publish that candidate as an experimental prerelease after verifying both uploaded images, retaining acceptance=false and device_validation=false. This exception does not mark graphics as accepted. It does not authorize a device write.
Actual tablet startup, graphics receiver layouts, namespace/mapper endpoint bindings and
runtime behavior remain unverified. This port is for TB-X606V; TB-X606F is not accepted.

Reusable generic graphics checks and measured Beta 4 native patches are derived from
manvindar/m30s-pixel-port commit 3c744d80c638a51a901471055c6a242ede3166e7.
No Samsung health, kernel, vendor, tuner or device-runtime changes are copied.
