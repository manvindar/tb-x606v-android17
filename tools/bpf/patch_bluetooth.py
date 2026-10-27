#!/usr/bin/env python3
"""com.android.bt libbluetooth_jni.so (Android 17): the vendor controller answers READ_DEFAULT_ERRONEOUS_DATA_REPORTING (0x0c5a) with
UNKNOWN_HCI_COMMAND as a Command Status; HciLayer::impl::on_hci_event then log::fatal()s "was not expecting status event" (two template instances).
Replace the two `tbz w20,#0,<fatal block>` (0xc24528, 0xc248f0) by `b <normal exit>` (0xc24554, 0xc249ac): the status event is ignored. Bind-mount the result over the apex lib64 copy.
usage: patch_bluetooth.py IN OUT"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
for a, t, x in ((0xc24528, 0xc25738, 0xc24554), (0xc248f0, 0xc25770, 0xc249ac)):
    o = struct.unpack_from('<I', d, a)[0]
    assert (o & 0xfff80000) == 0x36000000 and (o & 0x1f) == 20 and a + ((o >> 5) & 0x3fff) * 4 == t, hex(o)
    struct.pack_into('<I', d, a, 0x14000000 | (((x - a) >> 2) & 0x3ffffff))
open(sys.argv[2], 'wb').write(d); print('patched ->', sys.argv[2])
