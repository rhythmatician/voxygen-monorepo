"""Worker entrypoint for isolated continue-train level execution.

Reads JSON payload from stdin:
  {
    "profile": {...},
    "level": int,
    "additional_epochs": int
  }

Runs exactly one level and streams logs to stdout.
"""

from __future__ import annotations

import json
import os
import sys
import traceback
from pathlib import Path


def main() -> None:
    payload = json.loads(sys.stdin.read() or "{}")
    profile = payload.get("profile")
    level = int(payload.get("level"))
    additional = int(payload.get("additional_epochs", 5))

    if not isinstance(profile, dict):
        raise ValueError("Payload missing 'profile' object.")

    repo_root = Path(__file__).resolve().parents[2]
    os.chdir(repo_root)

    from voxel_tree.gui.step_definitions import _continue_train_voxy_level_run  # noqa: PLC0415

    _continue_train_voxy_level_run(profile, level, additional)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)
