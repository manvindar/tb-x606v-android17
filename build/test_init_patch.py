import importlib.util
import os
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('patch_init', Path(__file__).parents[1] / 'tools/libc/patch_init.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
class InitTest(unittest.TestCase):
    def test_unknown_input_rejected(self):
        with self.assertRaises(ValueError): module.patch(b'unknown init')
    @unittest.skipUnless(os.environ.get('BETA4_INIT'), 'exact extracted Beta 4 input required')
    def test_exact_beta4_and_corruption(self):
        raw = Path(os.environ['BETA4_INIT']).read_bytes()
        out = module.patch(raw)
        self.assertEqual(len(out), len(raw))
        self.assertEqual(out[:0x106de0], raw[:0x106de0])
        self.assertEqual(out[0x106de8:], raw[0x106de8:])
        self.assertEqual(out[0x106de0:0x106de8], bytes.fromhex('0101005400008052'))
        corrupt = bytearray(raw); corrupt[0x106de0] ^= 1
        with self.assertRaises(ValueError): module.patch(corrupt)
        with self.assertRaises(ValueError): module.patch(out)
if __name__ == '__main__': unittest.main()
