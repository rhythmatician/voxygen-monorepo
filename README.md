# Voxygen — Monorepo

Merged repository combining the former `LODiffusion` (Java/Fabric) and `VoxelTree` (Python/ML)
projects under `java/` and `python/` respectively.

## Layout

| Path | What | Build |
|------|------|-------|
| `java/` | Fabric mod (MC 1.21.11, Fabric Loom 1.13.6, Gradle 8.14, Java 25) | `java/gradlew` (projectDir `java/`) |
| `python/` | VoxelTree ML pipelines (pyproject.toml, PySide6 GUI, ONNX export) | `pip install -e python` / `uv pip` |
| `external/` | Symlinks to local reference clones (Voxy, minecraft-src, fabric-api, ogn) | `dev/setup-external.ps1` |
| `dev/` | Workspace harnesses | — |

## Gradle from the monorepo root

The Gradle build lives in `java/` (`java/settings.gradle`, `java/build.gradle`, `java/gradle.properties`).
Running `gradlew` from the monorepo root now forwards to `java/` via the delegation wrappers:

```powershell
# Preferred — from repo root (delegates to java/gradlew -p java)
rtk proxy .\gradlew.bat :compileJava
rtk proxy .\gradlew.bat test jacocoTestReport build   # full gate (lint must pass first)
.\gradlew.bat runClient                                 # launch client

# Direct — explicitly set project dir
rtk proxy java\gradlew.bat -p java :compileJava
Push-Location java; rtk proxy .\gradlew.bat :compileJava; Pop-Location

# Legacy nested path without -p WILL FAIL:
#   rtk proxy java\gradlew.bat :compileJava
# -> Directory '...\voxygen-monorepo' does not contain a Gradle build.
```

`projectDir` stays `java/` so all relative `file(...)` paths (`mods/`, `src/main/resources/…`,
`config/checkstyle/checkstyle.xml`, `run/mods/`) remain correct.

## CI

GitHub only discovers workflows in `.github/workflows/` at the repository root.
The single authoritative workflow is:

- `.github/workflows/factory-ci.yml` — `Factory / Merge Oracle` is the authoritative automated-evidence check; see `.ci/checks.json` for evidence and human-approval path policy and `.ci/README.md` for the trust-boundary invariant.

## Python from the monorepo root

```powershell
pip install -e python          # editable install from pyproject.toml (or: uv pip install -e python)
pytest python -k "not integration"
python python/scripts/build_voxy_pairs.py --help
```

## Setup

```powershell
# Symlink reference clones into external/
.\dev\setup-external.ps1

# Run either pipeline
rtk proxy .\gradlew.bat clean test
python -m voxel_tree --help
```
