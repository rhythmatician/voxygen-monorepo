# Distant Horizons integration — external reference (sanitized)

> **Status:** historical — does not describe current Voxygen architecture
> doc-type: external-reference
> source-revision: Distant Horizons v2.3.0b + API 2.1.0–4.0.0 (GitLab `distant-horizons-team/distant-horizons`, `distant-horizons-core` at 2024-08) + Minecraft 1.21.11 context
> upstream: https://gitlab.com/distant-horizons-team/distant-horizons
> sanitized-from: `java/.github/copilot-instructions/distant-horizons-integration.md` (347 lines, 37KB, 2026-08-13)
> research date: 2026-08-17
> successor: none — Voxygen does not integrate DH; correct distant terrain is defined in `CONTEXT.md` + `docs/adr/0003-correct-distant-terrain.md`; LOD store is Voxy (`docs/reference/upstream/VOXY-FORMAT.md`, `external/voxy`)
> scope: Describes only DH's LOD system, storage, and API as external facts. Not a Voxygen requirement or architecture truth.
> invalidation rule: If DH API or Voxygen LOD store changes, re-verify against pinned GitLab revision; do not edit to describe different revision.

## TL;DR

DH renders simplified chunk meshes behind vanilla render distance, pre-computing LODs to `DistantHorizons.sqlite` (LZ4). Since 2.3 it supports server-side generation/distribution. API is mod-loader agnostic but has frequent breaking changes (Vec3f→DhApiVec3f, method removals). Retained here because it was the only file in the corpus with non-Voxygen-specific upstream value, and reconstructing the version delta (2.1→4.0) is expensive.

## Key facts (grounded to pinned revision)

- **Modular architecture:** `distant-horizons-core` isolates version-agnostic code (porting seam).
- **Storage:** Single-player `saves/WORLD/data/DistantHorizons.sqlite`, server `Distant_Horizons_server_data/SERVER/`; compressed LZ4; tens-of-GB growth; backup tools must exclude `DistantHorizons*`.
- **API:** `IDhApiWorldProxy`, `IDhApiWorldGenerator` (`runApiValidation()`), `IDhApiTerrainDataRepo` (`getSingleDataPointAtBlockPos()` …), `IDhApiFogConfig`, `IDhApiLevelWrapper`; events `DhApiWorldLoad/Unload` with cloned params.
- **Build:** `./gradlew assemble` / `mergeJars` (Forgix) / `genSources` / `fabric:runClient`; JDK 17+, `clone --recurse-submodules`.
- **Breaking changes:** Documented renames/removals between 2.1→3.0 and 3.0.1→4.0; consumers must track release notes manually.

## Why not current truth

Voxygen's LOD path is `WorldNoiseAccess → AnchorSampler → SparseOctreeModelRunner → VoxelVolumeWriter → Voxy` (see `L1-availability-contract.md`). DH is not a dependency (`java/build.gradle` has no DH coordinate). No inbound links from `java/src` (verified `grep -rn distant.horizons java/src` = 0).

## Historical header

Date: 2024-08 (DH 2.3.0b era). Scope: external LOD alternative. Successor: none. Retrieval risk: low if this header is kept; otherwise agents may conflate Voxy with DH.

## Sanitization notes

Original 37KB contained duplicated Gradle/IDE cache advice, SQLite backup war-stories, and speculation about "Open to LAN" support. Removed per `docs/agents/documentation.md` A/H (cache, duplication). Retained only version-pinned API/storage facts.
