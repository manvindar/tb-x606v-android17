#!/usr/bin/env python3
"""Select exact Beta 4 measured patches; retain earlier guarded patch support."""
import hashlib
from pathlib import Path
import subprocess
import sys
from patch_tethering import SPEC
if __name__ == '__main__':
    tool, src, dst = sys.argv[1:]
    sha = hashlib.sha256(Path(src).read_bytes()).hexdigest()
    name = 'patch_tethering.py' if sha == SPEC[tool]['sha256'] else 'patch_binaries_legacy.py'
    subprocess.run([sys.executable, str(Path(__file__).with_name(name)), tool, src, dst], check=True)
