#!/usr/bin/env python3
"""libbinder (Android 17, arm64): IBinder::addFrozenStateChangeCallback needs the binder-freeze-notification ioctl of a 6.x kernel and returns -38
(INVALID_OPERATION) on kernel 4.9. CameraService::addListener, SensorService, AudioFlinger::registerClient ... all treat that as fatal
("Failed to register a camera service listener", "linkToDeath returned -38"), which crashed system_server. Make it succeed without doing anything
(`mov w0,wzr ; ret` at the function entry 0x91b80, asserted). usage: patch_libbinder.py IN OUT"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
assert struct.unpack_from('<I', d, 0x91b80)[0] == 0xa9be7bfd, 'unexpected libbinder build'
struct.pack_into('<II', d, 0x91b80, 0x2a1f03e0, 0xd65f03c0)
open(sys.argv[2], 'wb').write(d); print('patched ->', sys.argv[2])
