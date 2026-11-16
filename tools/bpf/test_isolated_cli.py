import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

class IsolatedCliTest(unittest.TestCase):
    def test_unknown_input_rejects_without_import_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / 'input'; dst = Path(tmp) / 'output'
            src.write_bytes(bytes(64))
            result = subprocess.run([sys.executable, '-I', str(Path(__file__).with_name('patch_binaries.py')), 'netbpfload', str(src), str(dst)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('ModuleNotFoundError', result.stderr)
            self.assertFalse(dst.exists())
    @unittest.skipUnless(os.environ.get('BETA4_NETBPFLOAD'), 'exact extracted input required')
    def test_exact_input_in_isolated_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            dst = Path(tmp) / 'output'
            subprocess.run([sys.executable, '-I', str(Path(__file__).with_name('patch_binaries.py')), 'netbpfload', os.environ['BETA4_NETBPFLOAD'], str(dst)], check=True, capture_output=True)
            self.assertTrue(dst.is_file())
            self.assertTrue(Path(str(dst) + '.tethering-receipt.json').is_file())
if __name__ == '__main__': unittest.main()
