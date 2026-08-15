## 🧪 VoxelTree Development Environment Setup (2025)

### ✅ Virtual Environment Summary

| Component           | Details                                                            |
| ------------------- | ------------------------------------------------------------------ |
| **Python Version**  | 3.13+ (fully tested with 3.13.1, 3.13.3 recommended)               |
| **Env Tool**        | Built-in `venv` (resides in project root)                          |
| **Package Manager** | `pip` (>=25.1.1 confirmed)                                         |
| **Core Libraries**  | `torch`, `numpy`, `scipy`, `PyYAML`, `tqdm`                        |
| **World Tools**     | `anvil-parser2`, `cubiomes` (built manually, CLI stored in tools/) |
| **Chunk Gen**       | Fabric server + Chunky mod (stored in `tools/`)                    |
| **Testing**         | `pytest`, `pytest-cov`                                             |
| **Linting/Type**    | `ruff` (format+lint) + `pyright` via `qgate`                       |
| **Pre-Commit**      | `husky` + `lint-staged` with staged formatting (ruff + prettier)   |
| **Visualization**   | `matplotlib`, `plotly`                                             |

---

### ⚙️ Pre-commit Hooks

Husky + lint-staged is the sole supported Git pre-commit mechanism (one obvious hook).

| Hook          | What It Does                                                                                                                                    |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `lint-staged` | Deterministic formatting only: `ruff check --fix` + `ruff format` for staged `*.py`, `prettier` for staged `*.{js,ts,mjs,mts,json,md,yml,yaml}` |

Formatting happens automatically on `git commit` via `lint-staged` and is auto-staged. No manual `git add` after formatting is needed.
No type checks or tests run at commit time. For current Muse 0.1.0-R708.1, no supported PostToolUse hook exists (proven via `muse plugins validate` and hook tests), so `scripts/muse-qgate-adapter.mjs` is retained for future Muse versions but not active. Instead, `scripts/qgate-watcher.mjs` (started via Sandcastle `onSandboxReady` after `uv sync`) provides immediate automatic `ruff check --fix-only` + `ruff format` (formatter-only, no Pyright, to avoid hidden CPU) for Python edits. Semantic diagnostics are at review/commit/CI where they can affect the agent.

Setup (reproducible via `npm run prepare`):

```bash
npm install   # installs husky + lint-staged via prepare hook
# hooks are at .husky/pre-commit -> npx lint-staged
```

---

### 🚀 Common Commands

```bash
# Activate virtual environment
source venv/Scripts/activate  # Windows
source venv/bin/activate      # macOS/Linux

# Install Python packages
uv sync --group dev

# Run tests
pytest

# Quality gate (changed files)
uv run qgate --fix

# Quality gate (full CI)
uv run --locked qgate --ci

# Deactivate
deactivate
```

---

### 📂 Requirements File Structure

- `pyproject.toml` + `uv.lock` → deps (qgate/ruff/pyright)
- `.husky/pre-commit` + `.lintstagedrc` → staged formatting (ruff + prettier)

---

### 🧠 Tips for Copilot Integration

- Keep `docs/PROJECT-OUTLINE.md` and `.github/copilot-instructions.md` up to date.
- Use consistent, minimal test output — it's Copilot’s reference point for next actions.

---

### ⚠️ Known Issues

| Issue                        | Workaround                                       |
| ---------------------------- | ------------------------------------------------ |
| Some hooks slow on first run | Cache builds automatically on subsequent commits |
| `cubiomes` is not on PyPI    | Clone & build from source in `tools/cubiomes/`   |
