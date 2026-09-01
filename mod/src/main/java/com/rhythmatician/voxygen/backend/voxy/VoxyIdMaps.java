package com.rhythmatician.voxygen.backend.voxy;

import java.util.Arrays;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;

/**
 * Data-clump fix: groups two canonical-to-Voxy ID maps that always travel
 * together. Previously {@code int[] canonicalBiomeToVoxy} and
 * {@code int[] canonicalBlockToVoxy} were passed as two loose parameters &mdash;
 * classic Data Clumps.
 *
 * <p>Arrays are defensively cloned on construction and on access to preserve
 * immutability. Package-private raw accessors avoid clone on hot write path.
 */
public record VoxyIdMaps(int[] biomeMap, int[] blockMap) {
    /**
     * Compact canonical constructor validates sizes and clones inputs.
     */
    public VoxyIdMaps {
        if (biomeMap == null || blockMap == null) {
            throw new NullPointerException("VoxyIdMaps maps must not be null");
        }
        if (biomeMap.length != CanonicalRegistries.BIOME_COUNT) {
            throw new IllegalArgumentException(
                    "biomeMap length must be " + CanonicalRegistries.BIOME_COUNT + " got " + biomeMap.length);
        }
        if (blockMap.length != CanonicalRegistries.BLOCK_COUNT) {
            throw new IllegalArgumentException(
                    "blockMap length must be " + CanonicalRegistries.BLOCK_COUNT + " got " + blockMap.length);
        }
        biomeMap = biomeMap.clone();
        blockMap = blockMap.clone();
    }

    @Override
    public int[] biomeMap() {
        return biomeMap.clone();
    }

    @Override
    public int[] blockMap() {
        return blockMap.clone();
    }

    /** Package-private raw access without clone for internal hot-path use. */
    int[] biomeMapRaw() {
        return biomeMap;
    }

    int[] blockMapRaw() {
        return blockMap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VoxyIdMaps other)) return false;
        return Arrays.equals(biomeMap, other.biomeMap) && Arrays.equals(blockMap, other.blockMap);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(biomeMap) + Arrays.hashCode(blockMap);
    }

    @Override
    public String toString() {
        return "VoxyIdMaps[biomeMap=" + biomeMap.length + ", blockMap=" + blockMap.length + "]";
    }
}
