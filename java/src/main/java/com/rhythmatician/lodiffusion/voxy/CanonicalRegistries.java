package com.rhythmatician.lodiffusion.voxy;

/**
 * Current canonical registry bounds, verified against the checked-in mappings.
 *
 * <p>Block and biome IDs used on the {@link VoxelVolumeWriter} seam are canonical
 * and versioned, shared between Python training and Java runtime. The authoritative
 * mappings live in contract metadata (Python: config/voxy_vocab.json for blocks;
 * biome list: BiomeMapping / scripts/biome_mapping.py). Until an explicit
 * deployment contracts identify the active 513-entry model vocabulary with
 * the version and hash below. Its IDs retain their canonical meanings inside
 * the runtime's 1104-entry superset. Sidecar consumers compare both values
 * before accepting model output; equality proves that Python export and Java
 * runtime expect the same model-registry artifact.
 *
 * <p>Provenance: block mapping size 1104 verified against python/config/voxy_vocab.json
 * (keys minecraft:air=0 .. last entry inclusive at time of pinning). Biome mapping:
 * 54 entries alphabetically ordered, 0..53 plus 255=unknown, verified against
 * BiomeMapping / scripts/biome_mapping.py.
 */
public final class CanonicalRegistries {
    private CanonicalRegistries() {}

    /** Version written to {@code canonical_block_registry.version} in model sidecars. */
    public static final String BLOCK_REGISTRY_VERSION = "voxygen.blocks.v1";

    /** SHA-256 expected for Python {@code config/voxy_vocab.json}. */
    public static final String BLOCK_REGISTRY_SHA256 =
            "9b034f2f7a5caa9c5d9e0c2674107f8b33c482bd6d6f887a165b0432981cf5af";

    /** Canonical block ID for air. */
    public static final int BLOCK_AIR = 0;

    /**
     * Current pinned number of canonical block IDs (0..BLOCK_COUNT-1),
     * verified against the checked-in block mapping.
     */
    public static final int BLOCK_COUNT = 1104;

    /** Maximum valid canonical block ID (inclusive). */
    public static final int BLOCK_ID_MAX = BLOCK_COUNT - 1;

    /** Current pinned number of canonical biomes (0..53), verified against checked-in mapping. */
    public static final int BIOME_COUNT = 54;

    /** Canonical biome ID for unknown/unmapped. */
    public static final int BIOME_UNKNOWN = 255;

    public static boolean isValidBlockId(int id) {
        return id >= 0 && id < BLOCK_COUNT;
    }

    public static boolean isValidBiomeId(int id) {
        return (id >= 0 && id < BIOME_COUNT) || id == BIOME_UNKNOWN;
    }
}
