#!/usr/bin/env python3
"""com.android.bt libbluetooth_jni.so (Android 17): the vendor controller answers READ_DEFAULT_ERRONEOUS_DATA_REPORTING (0x0c5a) with
UNKNOWN_HCI_COMMAND as a Command Status; HciLayer::impl::on_hci_event then log::fatal()s "was not expecting status event" (two template instances).
Retarget the two `tbz w20,#0,<fatal block>` (0xc24528, 0xc248f0) to the normal exit (0xc24554, 0xc249ac): an event that does not match the queued command is ignored, a matching one is handled as before (an unconditional `b` skipped the handling of every event). Bind-mount the result over the apex lib64 copy.
usage: patch_bluetooth.py IN OUT"""
import struct, sys
d = bytearray(open(sys.argv[1], 'rb').read())
for a, t, x in ((0xc24528, 0xc25738, 0xc24554), (0xc248f0, 0xc25770, 0xc249ac)):
    o = struct.unpack_from('<I', d, a)[0]
    assert (o & 0xfff80000) == 0x36000000 and (o & 0x1f) == 20 and a + ((o >> 5) & 0x3fff) * 4 == t, hex(o)
    struct.pack_into('<I', d, a, (o & ~(0x3fff << 5)) | ((((x - a) >> 2) & 0x3fff) << 5))   # same tbz, new target
# btm_sec_dev_reset (0xabaedc): `tbz w0,#0,<fatal "only controllers with SSP is supported">` after Controller::SupportsSimplePairing(); the check fails
# with the features read through the ignored status, so accept it (the chip is BT 5.0 and does support SSP).
assert struct.unpack_from('<I', d, 0xabaf10)[0] == 0x36000000 | (((0xabafec - 0xabaf10) >> 2) << 5) | 0
struct.pack_into('<I', d, 0xabaf10, 0xd503201f)
open(sys.argv[2], 'wb').write(d); print('patched ->', sys.argv[2])
