#!/usr/bin/env python3
"""Voxygen-side Muse/Codex PostToolUse adapter for qgate.

Reads the real Muse/Codex PostToolUse JSON payload from stdin, safely
identifies affected Python paths, and invokes the existing qgate
explicit-path behavior:

    uv run --project python qgate --fix <affected-python-files>

Behavior:
- Silent / zero context cost on success (no output, exit 0)
- Concise actionable diagnostics on failure (qgate output forwarded)
- Ignores non-Python / non-file / outside-worktree paths
- Rejects paths outside the worktree
- Avoids repo-wide scans for ordinary single-file edits (explicit paths only)
- Handles both Muse and Codex event schemas without modifying py-qgate

This keeps host-specific Muse event parsing outside qgate. We can upstream
the pattern later after it proves itself.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

# Match qgate's excluded directories (keep in sync with python/.venv/.../qgate/targeting.py)
_EXCLUDED_DIRS = {
    ".git",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    ".venv-pip-backup",
    "artifacts",
    "tmp",
    ".sandcastle",
    "node_modules",
    ".codex",
    "graphify-out",
}


def _is_excluded(candidate: Path, workspace: Path) -> bool:
    try:
        rel_parts = candidate.absolute().relative_to(workspace).parts
    except ValueError:
        return True
    if any(part in _EXCLUDED_DIRS for part in rel_parts):
        return True
    try:
        resolved = candidate.resolve()
    except OSError:
        return True
    # additional check: if resolved is inside excluded root
    for excl in _EXCLUDED_DIRS:
        excl_root = (workspace / excl).resolve()
        try:
            resolved.relative_to(excl_root)
            return True
        except ValueError:
            continue
    return False


def _is_within_workspace(candidate: Path, workspace: Path) -> bool:
    try:
        candidate.relative_to(workspace)
        return True
    except ValueError:
        return False


def _wsl_to_windows(path: str) -> str:
    # Translate WSL /mnt/<drive>/... to Windows <Drive>:\... when running under Windows Python in WSL
    if (
        path.startswith("/mnt/")
        and len(path) > 6
        and path[6] == "/"
        and path[5].isalpha()
    ):
        drive = path[5].upper()
        rest = path[7:].replace("/", "\\")
        return f"{drive}:\\{rest}"
    return path


def _working_directory(reported: str | None, workspace: Path) -> Path:
    if not reported:
        return workspace
    # Translate WSL path if needed (Windows Python in WSL)
    reported = _wsl_to_windows(reported)
    c = Path(reported)
    if not c.is_absolute():
        c = workspace / c
    try:
        resolved = c.resolve()
    except OSError:
        return workspace
    if not _is_within_workspace(resolved, workspace):
        return workspace
    return resolved if resolved.is_dir() else workspace


def _string_values(value) -> list[str]:
    """Recursively extract string values from JSON (like qgate codex.py)."""
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        out: list[str] = []
        for child in value:
            out.extend(_string_values(child))
        return out
    return []


def _payload_paths(payload: dict) -> list[str]:
    """Extract candidate paths from Muse/Codex payload, handling both schemas."""
    paths: list[str] = []

    # Codex style: payload.tool_input / payload.toolInput
    tool_input = (
        payload.get("tool_input")
        or payload.get("toolInput")
        or payload.get("tool_input".lower())
    )
    # Muse may use tool_input or tool_args or input
    if tool_input is None:
        # Try alternative keys Muse might use: toolArgs, input, args
        for alt in ("toolArgs", "tool_args", "input", "args", "params"):
            if alt in payload:
                tool_input = payload[alt]
                break
        # Also try nested under tool_result? No, only input matters

    if isinstance(tool_input, dict):
        for key in ("path", "file_path", "target_file", "paths", "file", "filename"):
            val = tool_input.get(key)
            if val is not None:
                paths.extend(_string_values(val))
        # Also check for 'command' containing apply_patch style
        cmd = tool_input.get("command")
        if isinstance(cmd, str) and "apply_patch" in payload.get("tool_name", ""):
            import re

            m = re.findall(
                r"^\*\*\*\s+(?:(?:Add|Delete|Update)\s+File|Move\s+to):\s*(.+?)\s*$",
                cmd,
                flags=re.MULTILINE,
            )
            paths.extend(m)
        # Muse edit_file may have 'path' and 'content' - path is the file edited
        # For write_file, path is target

    # Also check top-level tool_name / toolName to filter later
    # Some Muse payloads put tool info at top: tool_name, tool_input, cwd
    # Others nest under 'tool' or 'invocation'

    # Check for nested tool objects (Muse plugin hook style: tool_name, tool_input, cwd, session_id)
    # Already handled via tool_input above; also check payload directly for path keys
    for key in ("path", "file_path"):
        if key in payload and isinstance(payload[key], str):
            paths.append(payload[key])

    # Check for 'tool' object
    tool_obj = payload.get("tool")
    if isinstance(tool_obj, dict):
        for key in ("path", "file_path"):
            val = tool_obj.get(key)
            if isinstance(val, str):
                paths.append(val)

    return paths


def _interpret_payload(payload: dict) -> tuple[list[str], str | None]:
    raw_cwd = (
        payload.get("cwd")
        or payload.get("workdir")
        or payload.get("workspace")
        or payload.get("cwd".upper())
    )
    cwd = raw_cwd if isinstance(raw_cwd, str) else None
    # Also check tool_use style: payload may contain 'cwd' at top
    # Check for 'invocation' object
    invocation = payload.get("invocation")
    if isinstance(invocation, dict) and cwd is None:
        raw = invocation.get("cwd") or invocation.get("workspace")
        if isinstance(raw, str):
            cwd = raw
    return _payload_paths(payload), cwd


def select_targets(
    candidate_paths: list[str], workspace: Path, reported_cwd: str | None
) -> list[Path]:
    trusted = workspace.resolve()
    base = _working_directory(reported_cwd, trusted)
    targets: set[Path] = set()
    for raw in candidate_paths:
        txt = _wsl_to_windows(raw.strip().strip("\"'"))
        if not txt:
            continue
        p = Path(txt)
        if not p.is_absolute():
            p = base / p
        try:
            resolved = p.resolve()
        except OSError:
            continue
        if not _is_within_workspace(resolved, trusted):
            continue
        if _is_excluded(p, trusted):
            continue
        if resolved.suffix.lower() == ".py" and resolved.is_file():
            targets.add(resolved)
    return sorted(targets)


def main() -> int:
    # Workspace is git root (where this script lives: scripts/ -> ../)
    script_path = Path(__file__).resolve()
    # scripts/muse-qgate-adapter.py -> workspace is parent of scripts
    workspace = script_path.parent.parent.resolve()
    # Also handle if script is at .muse/hooks/muse-qgate.py
    # Search upwards for .git
    cur = workspace
    for _ in range(3):
        if (cur / ".git").exists():
            workspace = cur
            break
        if cur.parent == cur:
            break
        cur = cur.parent

    # Read stdin JSON payload (PostToolUse event)
    try:
        raw = sys.stdin.read()
        if not raw.strip():
            return 0
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            return 0
    except (json.JSONDecodeError, OSError, ValueError, TypeError):
        # No valid JSON -> no targets, silent exit
        return 0

    # Quick filter: only handle edit/write tools, ignore others
    tool_name = (
        payload.get("tool_name") or payload.get("toolName") or payload.get("tool") or ""
    )
    if isinstance(tool_name, dict):
        tool_name = tool_name.get("name", "")
    if not isinstance(tool_name, str):
        tool_name = str(tool_name)
    # If tool_name is present and not an edit/write, ignore
    if tool_name and not any(
        k in tool_name.lower()
        for k in ("edit", "write", "apply_patch", "create", "update")
    ):
        # Check if payload contains path anyway? But if tool is not file-editing, ignore
        # Still check for safety: if no path, ignore
        pass

    candidate_paths, reported_cwd = _interpret_payload(payload)
    if not candidate_paths:
        return 0

    targets = select_targets(candidate_paths, workspace, reported_cwd)
    if not targets:
        return 0

    # Invoke qgate with explicit paths, --fix mode (format + safe fixes + diagnostics)
    # Use uv run --project python qgate --fix <files>
    # Resolve uv binary: prefer .venv/Scripts/uv or PATH
    cmd = [
        "uv",
        "run",
        "--project",
        "python",
        "qgate",
        "--fix",
        *(str(p) for p in targets),
    ]
    try:
        result = subprocess.run(
            cmd,
            cwd=str(workspace),
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as e:
        print(f"[qgate-adapter] failed to run qgate: {e}", file=sys.stderr)
        return 1

    # Silent on success
    if result.returncode == 0:
        return 0

    # On failure, forward concise diagnostics (stderr + stdout) to agent
    # Preserve qgate's useful diagnostics, stay concise
    output = (
        (result.stdout or "")
        + ("\n" if result.stdout and result.stderr else "")
        + (result.stderr or "")
    )
    output = output.strip()
    if output:
        print(output, file=sys.stderr)
    else:
        print(
            f"[qgate] check failed for: {', '.join(str(p.relative_to(workspace)) for p in targets)}",
            file=sys.stderr,
        )
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
