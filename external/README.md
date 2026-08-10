# External Reference Codebases (`/external`)

This directory contains external reference codebases used by AI agents and developers for API definitions, memory layouts, and class signatures.

> [!CAUTION]
> **READ-ONLY AUTHORITY**: Files inside `/external` are **strictly immutable**. AI agents and automated scripts must treat these folders as read-only reference targets. Do NOT edit or commit changes inside these directories.

---

## Expected Reference Repositories

| Folder | Source / Purpose | Method |
| :--- | :--- | :--- |
| `external/voxy` | Voxy core mod source (Octree structures, JNI C++ buffers) | `git clone` or Symlink |
| `external/fabric-api` | Fabric API interfaces and hooks | `git clone` or Symlink |
| `external/minecraft-src` | Decompiled & mapped Minecraft source (Loom/Architectury) | Local Symlink |
| `external/ogn` | Reference Octree Generating Network implementation | `git clone` or Symlink |

---

## Setting Up `/external` in a New Environment

### Option A: Automatic Setup Script (Local Symlinks)
If you already have a parent `reference-code/` directory (e.g., `C:\Users\<user>\git\MC\reference-code`), run the PowerShell setup script from the root of the monorepo:

```powershell
.\dev\setup-external.ps1

```

### Option B: Manual Git Clones (For Clean Environments)

For public open-source reference repos in a fresh environment, run:

```powershell
cd external
git clone [https://github.com/voxy-mod/voxy.git](https://github.com/voxy-mod/voxy.git) voxy
git clone [https://github.com/FabricMC/fabric.git](https://github.com/FabricMC/fabric.git) fabric-api

```

> **Note on Minecraft Source (`minecraft-src`)**:
> Minecraft source code cannot be cloned directly from Git due to licensing. In a fresh environment, run `./gradlew genSources` via Fabric Loom to generate mapped source code, then symlink the resulting demapped `.jar` or source directory to `external/minecraft-src`.
