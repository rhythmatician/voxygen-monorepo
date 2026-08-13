# Voxygen

Voxygen generates distant Minecraft terrain via learned octree diffusion and writes semantic voxel volumes into a pluggable LOD store (today Voxy).

## Language

### Coordinates & Levels

**SectionPos**: Section-grid coordinate where one unit = 16 Minecraft blocks (sectionX = blockX >> 4); canonical position for all generation and writing. _Avoid_: chunk section, chunk pos, WorldSection coord, wsX, voxel section.

**Level**: LOD refinement level L0..L4 where L0 is finest; validated volume dimensions, never inferred. Level is not storage -- Voxy WorldSection (32^3) remains a private consolidation detail, never a Level. _Avoid_: FULL32, storage level, WorldSection level, Voxy level.

### Semantic Volume

**Canonical Block Registry**: Versioned mapping of block identities to stable canonical IDs (0 = air) shared between Python training and Java runtime; must be proved identical via explicit version/hash in contract metadata, not per-volume. _Avoid_: vocab index, Voxy block ID, packed ID.

**Canonical Biome Registry**: Alphabetically-ordered 54-entry mapping of overworld biomes to canonical IDs 0..53 (255 = unknown) shared between Python and Java; must be proved identical via explicit version/hash in contract metadata. _Avoid_: Voxy biome ID, biome registry entry.

**VoxelVolume**: Semantic dense XYZ cube of canonical (blockId, biomeId) accessible by x/y/z behind an opaque API; valid extents 16 and 32; backing representation (primitive arrays or otherwise) stays private and not frozen. _Avoid_: long[] yzx, packed voxel, Voxy voxel, 32768.

**VoxelPredictionDecoder**: Inference-boundary module that decodes model outputs (logits/argmax) into semantic VoxelVolume; the only place that understands model output layout. _Avoid_: writer argmax, logits in writer.

### Writing

**VoxelVolumeWriter**: Deep module seam between generation and storage with two explicit operations: writeSection(SectionPos, VoxelVolume[16]) for L0 and writeRegion(SectionPos origin, Level, VoxelVolume[32]) for 32^3 regions. YZX transpose, VarHandle/CAS, and WorldSection lifecycle remain private behind the adapter. _Avoid_: VoxySectionWriter, VoxyCompat, VoxyEngine, VoxyWorldBinding, FULL32.

**WriteOutcome**: Result of a writer operation: WRITTEN, SKIPPED_AIR, SKIPPED_EXISTS; invalid non-null values throw IllegalArgumentException and null references throw NullPointerException; missing backend throws unchecked VolumeUnavailableException (extends IllegalStateException). _Avoid_: SKIPPED_BOUNDS, SKIPPED_INVALID.
