
![LODiffusionLogo](https://github.com/user-attachments/assets/ac6158c9-06ba-4874-9c14-1e80ef2135d5)

# LODiffusion: AI-Powered LOD Terrain Generation for Minecraft

![CI](https://github.com/rhythmatician/LODiffusion/actions/workflows/ci.yml/badge.svg)

LODiffusion is a Minecraft mod that integrates AI-driven terrain generation using a progressive Level of Detail (LOD) system. It is inspired by Distant Horizons and uses a multi-resolution diffusion model to generate terrain dynamically based on the distance from the player.

---

## Project Structure

* `src/` — Main mod source code (Fabric 1.20+)
* `train/` — PyTorch scripts and ONNX export logic
* `test/` — Unit tests and CI checks
* `dh/` — (Git-ignored) Local clone of Distant Horizons source code

---

## Repopulating `dh/`

The `dh/` directory contains the source code for the [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons) mod. It is `.gitignore`d to avoid accidental commits of large external codebases. To repopulate it:

```bash
git clone https://gitlab.com/distant-horizons-team/distant-horizons dh
chmod -R a-w dh
```

This will:

* Clone the repository into `dh/`
* Make the contents read-only to prevent accidental edits

> Tip: If you're on Windows, use Git Bash or WSL to run the `chmod` command. On native Windows, you can use:
>
> ```powershell
> attrib +R /S /D dh\*
> ```

## Repopulating `fabric/`

The `fabric` directory contains the source code for the MC [Fabric API](https://github.com/FabricMC/fabric)

```bash
git clone https://github.com/FabricMC/fabric
chmod -R a-w fabric
```

## Repopulating `Hephaistos/`

The `Hephaistos` directory contains [Hephaistos](https://github.com/Minestom/Hephaistos)

```bash
git clone https://github.com/Minestom/Hephaistos
chmod -R a-w Hephaistos
```

---

## Getting Started

1. Ensure JDK 17+ and Gradle are installed.
2. Run:

   ```bash
   ./gradlew runClient
   ```
3. To execute unit tests:

   ```bash
   ./gradlew clean test jacocoTestReport
   ```

## Development Workflow

This project follows a **micro-commit strategy** for efficient development:

### Branch Management
- Create focused branches: `test/add-xyz-test`, `feat/implement-abc`, `docs/update-def`
- One logical change per branch (max 200 lines changed)
- PRs should be reviewable in < 10 minutes

### Commit Strategy  
- Commit every 15-20 minutes during active development
- Use conventional prefixes: `test:`, `feat:`, `fix:`, `docs:`
- One logical change per commit
- Push frequently for backup and CI validation

### Getting Started
1. **Before starting work**: `git fetch && git checkout main && git pull`
2. **Create branch**: `git checkout -b test/add-single-method-test`
3. **Development cycle**: Write test → Commit → Implement → Commit → Repeat
4. **Complete feature**: Push branch → Create PR → Auto-merge

See `docs/instructions.md` for detailed workflow guidance.

---

## CI and Test Discipline

Follow a strict test-first development cycle:

* Write JUnit tests first (under `src/test/java/...`)
* Use `@Tag("ci")` or `@Tag("inference")` for test targeting
* Coverage threshold: **70% minimum**

### CI Pipeline
The GitHub Actions CI runs three separate jobs for optimal feedback:

1. **Lint Job** (`./gradlew lint`) - Fast code quality checks
2. **Test Job** (`./gradlew test jacocoTestReport`) - Unit tests + coverage  
3. **Build Job** (`./gradlew build`) - Final mod JAR (only if lint + test pass)

Run locally before pushing: `./gradlew clean lint test jacocoTestReport build`

---

## Integration Points

* Injects into Fabric chunk generation pipeline
* Interfaces with Distant Horizons for LOD state
* Uses ONNX runtime (via DJL) for terrain prediction

---

## Contributors

* @rhythmatician — Project lead, integration, AI model design
* Copilot — Guided code scaffolding and unit test generation

---
