---
name: implement
description: "Implement a piece of work based on a spec or set of tickets."
disable-model-invocation: true
---

Implement the work described by the user in the spec or tickets.

Use /tdd where possible, at pre-agreed seams.

Run typechecking regularly, single test files regularly, and the full test suite once at the end.

Once done, use /code-review to review the work.

Commit your work to the current branch.

Preserve authoritative sources. Before creating or materially expanding Markdown, follow `docs/agents/documentation.md`. Do not create implementation, status, plan, TODO, handoff, deliverables, or summary docs to explain code — improve code, types, interfaces, or point to authoritative artifacts instead. Authoritative source: code/tests/contracts/config or GitHub Issue/PR.

Preserve formatting. After editing any Python file, run `uv run --project python qgate --fix <file>` (via `~/.local/bin/uv` shim in WSL) so the working tree stays formatted like VS Code's `formatOnSave`. The pre-commit hook (`uv run --project python qgate --fix`, `types: [python]`, `require_serial: true`) is a safety net at commit, not a replacement for post-edit formatting.
