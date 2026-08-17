---
name: gradle-troubleshooting
description: Gradle/Loom file-lock and Hephaistos resolution when CONFIGURE fails or imports cannot resolve
---

# Gradle Troubleshooting

Use when Gradle sync hangs, shows `ACQUIRED_PREVIOUS_OWNER_DISOWNED` file-lock, `Could not resolve: Hephaistos`, or imports stay unresolved after adding a dependency.

## Branches

**1. File-lock is normal — wait, don't kill.**
- Symptom: `Previous process has disowned the lock` during VS Code Java extension sync.
- Action: Wait for `CONFIGURE SUCCESSFUL in Xm Ys` in `Output → Gradle for Java`. Verify `Found X tasks`. Do not delete `.gradle` or restart daemon while `Building workspace` shows. Check `gradlew --status` only to observe.

**2. Hephaistos coordinate is stale.**
- Correct: `implementation 'com.github.Minestom.Hephaistos:common:2.1.2'` + `kotlinStdlib` (see `java/build.gradle:82`)
- Wrong (from old guide): `com.github.Minestom:Hephaistos:2.1.2` or `jglrxavpok` — repository moved. Update and re-sync; `java/src/.../ChunkDataExtractor.java` uses Hephaistos with raw-NBT fallback for 1.18+.

**3. Imports unresolved after successful sync.**
- Confirm sync succeeded first. If `CONFIGURE SUCCESSFUL`, restart Java language server (`Ctrl+Shift+P → Java: Reload Projects` / `Java: Restart Language Server`). Only then clean.

**Nuclear (last resort) — only after confirmed failure:**
```bash
./gradlew --stop
rm -rf .gradle build
rm -rf ~/.gradle/caches/fabric-loom
./gradlew clean build --refresh-dependencies
```
