"""Exercise the builder's actual apply function against real ext4 writes."""
import ast
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

class ImageWrites(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.e2 = next((p for p in ['/opt/homebrew/opt/e2fsprogs/sbin', '/usr/sbin', '/sbin'] if Path(p, 'debugfs').exists() and Path(p, 'mke2fs').exists()), None)
        if not self.e2: self.skipTest('host ext4 tools unavailable')
        self.image = self.root / 'test.img'
        with self.image.open('wb') as f: f.truncate(8 * 1024 * 1024)
        subprocess.run([self.e2+'/mke2fs', '-q', '-F', '-t', 'ext4', str(self.image)], check=True)
        tree = ast.parse(Path(__file__).with_name('build_image17.py').read_text())
        selected = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in ('dbg', 'cat', 'tmpf', 'apply')]
        self.ns = dict(subprocess=subprocess, os=os, re=re, sys=sys, hashlib=hashlib,
                       E2=self.e2, W=str(self.root), cmds=[], readback_sources={})
        self.ns['run'] = lambda *a: subprocess.run(a, capture_output=True, check=True)
        exec(compile(ast.Module(body=selected, type_ignores=[]), '<builder-write-functions>', 'exec'), self.ns)

    def write(self, written, expected):
        self.ns['cmds'] = ['write '+str(written)+' /payload']
        self.ns['readback_sources'] = {'/payload': str(expected)}
        self.ns['apply'](str(self.image))

    def test_exact_write_passes(self):
        f = self.root/'good';f.write_bytes(b'verified bytes')
        self.write(f, f)
        self.assertEqual({}, self.ns['readback_sources'])
        self.assertTrue((self.root/'debugfs-test.img.stdout.log').exists())
        self.assertTrue((self.root/'debugfs-test.img.stderr.log').exists())

    def test_empty_written_file_passes(self):
        f = self.root/'empty';f.write_bytes(b'')
        self.write(f, f)
        self.assertEqual({}, self.ns['readback_sources'])

    def test_missing_empty_file_rejects(self):
        f = self.root/'empty';f.write_bytes(b'')
        self.ns['readback_sources'] = {'/missing': str(f)}
        with self.assertRaisesRegex(SystemExit, 'Written file missing'):
            self.ns['apply'](str(self.image))

    def test_different_written_bytes_reject(self):
        good = self.root/'good';good.write_bytes(b'expected content')
        bad = self.root/'bad';bad.write_bytes(b'different content')
        with self.assertRaisesRegex(SystemExit, 'readback mismatch'):
            self.write(bad, good)

    def test_out_of_space_rejects_even_if_debugfs_returns_zero(self):
        f = self.root/'large';f.write_bytes(b'x' * (12*1024*1024))
        with self.assertRaisesRegex(SystemExit, 'allocate|readback mismatch'):
            self.write(f, f)

if __name__ == '__main__': unittest.main()
