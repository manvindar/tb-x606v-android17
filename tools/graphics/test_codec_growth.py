import os
from pathlib import Path
import shutil
import tempfile
import unittest
from common import run
from repack_codec import filesystem_geometry, growth_bytes


class CodecGrowthTests(unittest.TestCase):
    def test_exhausted_group_requires_new_inode_table(self):
        g = dict(block_bytes=4096, blocks_per_group=32768, blocks=20000,
                 first_block=0, free_inodes=0, inodes_per_group=32)
        self.assertEqual(growth_bytes(g, 20000 * 4096, 32), 65536 * 4096)

    def test_existing_capacity_keeps_small_growth(self):
        g = dict(block_bytes=4096, blocks_per_group=32768, blocks=20000,
                 first_block=0, free_inodes=100, inodes_per_group=32)
        self.assertEqual(growth_bytes(g, 20000 * 4096, 32), 20000 * 4096 + 16 * 1024 * 1024)

    def test_oversized_growth_rejected(self):
        g = dict(block_bytes=65536, blocks_per_group=524288, blocks=10,
                 first_block=0, free_inodes=0, inodes_per_group=32)
        with self.assertRaisesRegex(RuntimeError, 'bounded'):
            growth_bytes(g, 655360, 32)

    @unittest.skipUnless(shutil.which('mke2fs') and shutil.which('resize2fs'), 'e2fsprogs required')
    def test_real_low_inode_filesystem_grows_and_reads_back(self):
        with tempfile.TemporaryDirectory() as scratch:
            image = Path(scratch) / 'payload.img'
            with image.open('wb') as stream:
                stream.truncate(4 * 1024 * 1024)
            run('mke2fs', '-q', '-t', 'ext4', '-b', '1024', '-g', '1024', '-N', '16', image)
            before = filesystem_geometry(image)
            required = 32
            self.assertLess(before['free_inodes'], required)
            target = growth_bytes(before, image.stat().st_size, required)
            with image.open('r+b') as stream:
                stream.truncate(target)
            run('resize2fs', image)
            run('e2fsck', '-fn', image)
            self.assertGreaterEqual(filesystem_geometry(image)['free_inodes'], required)
            source = Path(scratch) / 'source'; source.write_bytes(b'codec regression')
            run('debugfs', '-w', '-R', f'write {source} /added', image)
            output = Path(scratch) / 'readback'
            run('debugfs', '-R', f'dump /added {output}', image)
            self.assertEqual(output.read_bytes(), source.read_bytes())
            run('e2fsck', '-fn', image)


if __name__ == '__main__':
    unittest.main()
