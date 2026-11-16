import ast
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest

class RetentionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        tree = ast.parse(Path(__file__).with_name('check_patches.py').read_text())
        fn = next(x for x in tree.body if isinstance(x, ast.FunctionDef) and x.name == 'retain')
        self.ns = dict(a=SimpleNamespace(evidence_out=str(self.root)), evidence=[], retained_bytes=0,
                       os=os, shutil=shutil, hashlib=hashlib)
        exec(compile(ast.Module(body=[fn], type_ignores=[]), '<retain>', 'exec'), self.ns)
        self.src = self.root / 'input'; self.src.write_bytes(b'actual-input')
    def test_exact_bytes_hash_and_duplicate_source(self):
        self.ns['retain']('/system/lib/x.so', str(self.src))
        self.ns['retain']('/system/lib/x.so', str(self.src))
        row, = self.ns['evidence']
        self.assertEqual((self.root / row['file']).read_bytes(), b'actual-input')
        self.assertEqual(row['sha256'], hashlib.sha256(b'actual-input').hexdigest())
    def test_same_basename_distinct_contexts(self):
        for context in ['system', 'codec']: self.ns['retain'](context + '/x.so', str(self.src))
        self.assertEqual(len({r['file'] for r in self.ns['evidence']}), 2)
    def test_missing_input_rejected(self):
        with self.assertRaises(RuntimeError): self.ns['retain']('missing', None)
    def test_empty_input_rejected(self):
        self.src.write_bytes(b'')
        with self.assertRaises(RuntimeError): self.ns['retain']('empty', str(self.src))
    def test_total_cap_rejected(self):
        self.ns['retained_bytes'] = 128 * 1024 * 1024
        with self.assertRaises(RuntimeError): self.ns['retain']('overflow', str(self.src))
    def test_per_file_cap_rejected(self):
        with self.src.open('wb') as f: f.truncate(64 * 1024 * 1024 + 1)
        with self.assertRaises(RuntimeError): self.ns['retain']('oversize', str(self.src))

if __name__ == '__main__': unittest.main()
