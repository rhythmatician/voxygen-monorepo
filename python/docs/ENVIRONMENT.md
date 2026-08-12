## 🧪 VoxelTree Development Environment Setup (2025)

### ✅ Virtual Environment Summary

| Component           | Details                                                |
| ------------------- | ------------------------------------------------------ |
| **Python Version**  | 3.13+ (fully tested with 3.13.1, 3.13.3 recommended)   |
| **Env Tool**        | Built-in `venv` (resides in project root)              |
| **Package Manager** | `pip` (>=25.1.1 confirmed)                             |
| **Core Libraries**  | `torch`, `numpy`, `scipy`, `PyYAML`, `tqdm`            |
| **World Tools**     | `anvil-parser2`, `cubiomes` (built manually, CLI stored in tools/) |
| **Chunk Gen**       | Fabric server + Chunky mod (stored in `tools/`)        |
| **Testing**         | `pytest`, `pytest-cov`                                 |
| **Linting/Type**    | `ruff` (format+lint) + `pyright` via `qgate`            |
| **Pre-Commit**      | `pre-commit` with automated formatting and cleanup     |
| **Visualization**   | `matplotlib`, `plotly`                                 |

---

### ⚙️ Pre-commit Hooks

These are **run automatically before each commit**:

| Hook        | What It Does                              |
| ----------- | ----------------------------------------- |
| `qgate` | Format+lint+type (ruff+pyright via dmypy, line-length 100, changed Gate Targets) |


To set it up:

```bash
pre-commit install
pre-commit run --all-files
```

---

### 🚀 Common Commands

```bash
# Activate virtual environment
source venv/Scripts/activate  # Windows
source venv/bin/activate      # macOS/Linux

# Install Python packages
pip install -r requirements.txt

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

* `pyproject.toml` + `uv.lock` → deps (qgate/ruff/pyright)
* `.pre-commit-config.yaml` → qgate hook (changed files)


---

### 🧠 Tips for Copilot Integration

* Keep `docs/PROJECT-OUTLINE.md` and `.github/copilot-instructions.md` up to date.
* Use consistent, minimal test output — it's Copilot’s reference point for next actions.

---

### ⚠️ Known Issues

| Issue                         | Workaround                                       |
| ----------------------------- | ------------------------------------------------ |
| Some hooks slow on first run  | Cache builds automatically on subsequent commits |
| `cubiomes` is not on PyPI     | Clone & build from source in `tools/cubiomes/`   |