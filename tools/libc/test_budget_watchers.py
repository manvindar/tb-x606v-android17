import struct
import unittest
from patch_budget_watchers import BTI_C, METHODS, SIGNATURE, patch


def fixture(separate_target=False, duplicate=False, wrong_signature=False):
    data = bytearray(0x1000)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HH', data, 16, 3, 183)
    struct.pack_into('<QQ', data, 32, 64, 0x800)
    struct.pack_into('<HHHHH', data, 54, 56, 1, 64, 3, 2)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0x1000, 0, len(data), len(data), 4096)
    names = b'\0.data.rel.ro\0.shstrtab\0'
    data[0x900:0x900 + len(names)] = names
    struct.pack_into('<IIQQQQIIQQ', data, 0x840, 1, 1, 3, 0x1600, 0x600, 0x60, 0, 0, 8, 0)
    struct.pack_into('<IIQQQQIIQQ', data, 0x880, 14, 3, 0, 0x1900, 0x900, len(names), 0, 0, 1, 0)
    for i, (method, kind) in enumerate(METHODS.items()):
        string = method.encode() + b'\0'; name_offset = 0x400 + i * 0x40
        data[name_offset:name_offset + len(string)] = string
        # Package is distant from process, as in the real hot/cold code layout.
        function = 0x300 if kind == 1 else 0x200
        target = 0x240 if not (separate_target and kind == 1) else 0x260
        instruction = function + 4
        branch = 0x14000000 | (((target - (instruction + 8)) // 4) & 0x3ffffff)
        struct.pack_into('<IIII', data, function, BTI_C, 0xaa0203e1, 0x52800002 | (kind << 5), branch)
        signature_offset = 0x500
        signature = ('wrong' if wrong_signature else SIGNATURE).encode() + b'\0'
        data[signature_offset:signature_offset + len(signature)] = signature
        struct.pack_into('<QQQ', data, 0x600 + i * 24, 0x1000 + name_offset, 0x1000 + signature_offset, 0x1000 + function)
    if duplicate:
        data[0x630:0x648] = data[0x600:0x618]
    # Deliberately unrelated adjacent reversed-order trampolines must stay untouched.
    struct.pack_into('<IIIIII', data, 0x700, 0xaa0203e1, 0x52800042, 0x14000004,
                     0xaa0203e1, 0x52800022, 0x14000001)
    return bytes(data)


class BudgetWatcherTests(unittest.TestCase):
    def test_exact_registered_hot_cold_methods_only(self):
        source = fixture(); output, records = patch(source)
        self.assertEqual(len(records), 2)
        self.assertEqual(output[0x700:0x718], source[0x700:0x718])
        for offset in (0x200, 0x300):
            self.assertEqual(struct.unpack_from('<I', output, offset)[0], BTI_C)
            self.assertEqual(struct.unpack_from('<III', output, offset + 4), (0xd2800020, 0xd65f03c0, 0xd503201f))
        changed = {i for i, (a, b) in enumerate(zip(source, output)) if a != b}
        self.assertTrue(changed <= set(range(0x204, 0x210)) | set(range(0x304, 0x310)))

    def test_different_registration_targets_rejected(self):
        with self.assertRaisesRegex(ValueError, 'one unique registration routine'):
            patch(fixture(separate_target=True))

    def test_duplicate_jni_registration_rejected(self):
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            patch(fixture(duplicate=True))

    def test_changed_jni_signature_rejected(self):
        with self.assertRaisesRegex(ValueError, 'signature changed'):
            patch(fixture(wrong_signature=True))


if __name__ == '__main__':
    unittest.main()
