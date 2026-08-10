#!/usr/bin/env python3
# This script has moved to VoxelTree/data-cli.py
# Run it from the VoxelTree directory:
#   python data-cli.py --help
import pathlib
import subprocess
import sys

voxeltree = pathlib.Path(__file__).parent.parent / "VoxelTree" / "data-cli.py"
if voxeltree.exists():
    sys.exit(subprocess.call([sys.executable, str(voxeltree)] + sys.argv[1:]))
else:
    print(f"data-cli.py has moved to {voxeltree}  (not found — run from VoxelTree/)")
    sys.exit(1)
