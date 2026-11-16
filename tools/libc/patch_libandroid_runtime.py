#!/usr/bin/env python3
"""Use JNI-anchored budget watcher selection; reject ambiguous/mismatched targets."""
from pathlib import Path
import subprocess
import sys
if __name__ == '__main__':
    subprocess.run([sys.executable, str(Path(__file__).with_name('patch_budget_watchers.py')), *sys.argv[1:]], check=True)
