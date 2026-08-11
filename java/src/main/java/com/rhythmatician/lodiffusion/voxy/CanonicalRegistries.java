package com.rhythmatician.lodiffusion.voxy;

/**
 * Current canonical registry bounds, verified against the checked-in mappings.
 *
 * <p>Block and biome IDs used on the {@link VoxelVolumeWriter} seam are canonical
 * and versioned, shared between Python training and Java runtime. The authoritative
 * mappings live in contract metadata (Python: config/voxy_vocab.json for blocks;
 * biome list: BiomeMapping / scripts/biome_mapping.py). Until an explicit
 * version/hash gate exists, these constants are <em>current pinned bounds</em>
 * verified against the checked-in mappings, not dynamically derived values.
 * A future slice will add the version/hash gate that makes drift impossible
 * and then derive or verify these bounds from the authoritative artifact.
 *
 * <p>Provenance: block mapping size 1104 verified against python/config/voxy_vocab.json
 * (keys minecraft:air=0 .. last entry inclusive at time of pinning). Biome mapping:
 * 54 entries alphabetically ordered, 0..53 plus 255=unknown, verified against
 * BiomeMapping / scripts/biome_mapping.py.
 */
public final class CanonicalRegistries {
    private CanonicalRegistries() {}

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
        return id >= 0 && id < BIOME_COUNT || id == BIOME_UNKNOWN;
    }
}
