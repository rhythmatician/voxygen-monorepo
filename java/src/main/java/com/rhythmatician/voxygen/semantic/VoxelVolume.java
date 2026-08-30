package com.rhythmatician.voxygen.semantic;

import java.util.Arrays;

/**
 * Semantic dense XYZ cube of canonical (blockId, biomeId).
 *
 * <p>Extent is 16 or 32. Backing representation is private primitive arrays,
 * not part of the contract. Access is by natural XYZ order (x, y, z) in
 * [0, extent). blockId 0 = air, biomeId in 0..53 or 255 = unknown.
 * Registry identity and version proving Python and Java share the same mapping
 * lives in contract metadata, not per instance.
 */
public final class VoxelVolume {
    private final int extent;
    private final int[] blocks;
    private final int[] biomes;

    private VoxelVolume(int extent, int[] blocks, int[] biomes) {
        this.extent = extent;
        this.blocks = blocks;
        this.biomes = biomes;
    }

    public int extent() {
        return extent;
    }

    public int blockId(int x, int y, int z) {
        checkBounds(x, y, z);
        return blocks[index(x, y, z)];
    }

    public int biomeId(int x, int y, int z) {
        checkBounds(x, y, z);
        return biomes[index(x, y, z)];
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    public static VoxelVolume uniform(int extent, int blockId, int biomeId) {
        validateExtent(extent);
        validateBlockId(blockId);
        validateBiomeId(biomeId);
        int n = extent * extent * extent;
        int[] b = new int[n];
        int[] m = new int[n];
        Arrays.fill(b, blockId);
        Arrays.fill(m, biomeId);
        return new VoxelVolume(extent, b, m);
    }

    public static Builder builder(int extent) {
        validateExtent(extent);
        return new Builder(extent);
    }

    /** Defensive deep copy. */
    public VoxelVolume copy() {
        return new VoxelVolume(extent, blocks.clone(), biomes.clone());
    }

    /** Returns true if every blockId is air (0). Centralized to deduplicate writer loops. */
    public boolean isAllAir() {
        for (int v : blocks) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    /** Counts non-air blockIds (blockId != 0). Centralized to deduplicate writer loops. */
    public int countNonAir() {
        int c = 0;
        for (int v : blocks) {
            if (v != 0) {
                c++;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------
    // Builder -- mutable only before build()
    // ------------------------------------------------------------------

    public static final class Builder {
        private final int extent;
        private final int[] blocks;
        private final int[] biomes;

        private Builder(int extent) {
            this.extent = extent;
            int n = extent * extent * extent;
            this.blocks = new int[n];
            this.biomes = new int[n];
            Arrays.fill(biomes, CanonicalRegistries.BIOME_UNKNOWN);
        }

        public Builder setBlock(int x, int y, int z, int blockId) {
            checkBuilderBounds(extent, x, y, z);
            validateBlockId(blockId);
            blocks[(y * extent + z) * extent + x] = blockId;
            return this;
        }

        public Builder setBiome(int x, int y, int z, int biomeId) {
            checkBuilderBounds(extent, x, y, z);
            validateBiomeId(biomeId);
            biomes[(y * extent + z) * extent + x] = biomeId;
            return this;
        }

        public Builder fill(int blockId, int biomeId) {
            validateBlockId(blockId);
            validateBiomeId(biomeId);
            Arrays.fill(blocks, blockId);
            Arrays.fill(biomes, biomeId);
            return this;
        }

        public VoxelVolume build() {
            return new VoxelVolume(extent, blocks.clone(), biomes.clone());
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    public static void validateBlockId(int id) {
        if (!CanonicalRegistries.isValidBlockId(id)) {
            throw new IllegalArgumentException(
                    "invalid canonical blockId " + id + " (valid 0.." + CanonicalRegistries.BLOCK_ID_MAX + ")");
        }
    }

    public static void validateBiomeId(int id) {
        if (!CanonicalRegistries.isValidBiomeId(id)) {
            throw new IllegalArgumentException(
                    "invalid canonical biomeId " + id + " (valid 0..53 or 255)");
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private int index(int x, int y, int z) {
        return (y * extent + z) * extent + x;
    }

    private void checkBounds(int x, int y, int z) {
        if (x < 0 || x >= extent || y < 0 || y >= extent || z < 0 || z >= extent) {
            throw new IndexOutOfBoundsException(
                    "coords out of bounds extent=" + extent + " got (" + x + "," + y + "," + z + ")");
        }
    }

    private static void checkBuilderBounds(int extent, int x, int y, int z) {
        if (x < 0 || x >= extent || y < 0 || y >= extent || z < 0 || z >= extent) {
            throw new IndexOutOfBoundsException(
                    "coords out of bounds extent=" + extent + " got (" + x + "," + y + "," + z + ")");
        }
    }

    public static void validateExtent(int extent) {
        if (extent != 16 && extent != 32) {
            throw new IllegalArgumentException("VoxelVolume extent must be 16 or 32, got " + extent);
        }
    }
}
