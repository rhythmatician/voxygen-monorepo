# Upstream References

Version-bound, source-grounded references for external systems.

These documents are **historical truth for the named upstream version** — not current Voxygen truth.

* Newer upstream versions get **separately verified and separately versioned** artifacts. Do not silently edit a versioned file to describe a different upstream.
* Architectural decisions belong in `docs/adr/`.
* Current implementation truth belongs in executable artifacts (code, tests, schemas, contracts).
* Planning and work state belong in GitHub Issues / Wayfinder.

## Current references

* `minecraft-1.21.11-worldgen-seams.md` — Minecraft 1.21.11 worldgen seams (NoiseRouter, DensityFunction, NoiseChunk, NoiseSettings, RandomState, noise stack, Climate/BiomeSource, Aquifer, Beardifier, Blender, SurfaceSystem, Carvers, etc.)
* `voxy-0.2.11-alpha-storage-and-lod-seams.md` — Voxy 0.2.11-alpha storage and LOD seams (WorldSection geometry, key encoding, per-voxel packing, Mapper, VoxelizedSection, Mipper, nonEmptyChildren, WorldEngine lifecycle, RocksDB/ZSTD serialization)

Both carry a provenance header with status, upstream project/version, commit/jar hash, inspected corpus, research date, scope, and invalidation rule.
