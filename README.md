# Voxygen — Monorepo

Merged repository combining the former `LODiffusion` (Java/Fabric) and `VoxelTree` (Python/ML)
projects under `java/` and `python/` respectively.

## Layout

| Path | What | Build |
|------|------|-------|
| `java/` | Fabric mod (MC 1.21.11, Fabric Loom 1.13.6, Gradle 8.14, Java 21) | `java/gradlew` (projectDir `java/`) |
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
Root workflows are the source of truth:

- `.github/workflows/java-ci.yml` — lint / test+coverage / build / CodeQL (Windows + Ubuntu), runs with `working-directory: java`
- `.github/workflows/python-ci.yml` — lint/typecheck/test + ONNX export + DJL verify, runs with `working-directory: python`

The old `java/.github/workflows/ci.yml` and `python/.github/workflows/ci.yml` are retained for history but not executed by GitHub after the merge.

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
