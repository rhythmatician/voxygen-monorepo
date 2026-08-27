package com.rhythmatician.lodiffusion.voxy;

import java.io.Serializable;

/**
 * A snapshot of a VoxelizedSection captured after mipping but before insertion into Voxy's
 * world engine.
 *
 * <p>This immutable snapshot contains:
 * <ul>
 *   <li>The multi-level voxel data (long[] array)</li>
 *   <li>Chunk coordinates (cx, cy, cz in 16×16×16 section units)</li>
 *   <li>World identifier for tracking which world the data came from</li>
 * </ul>
 *
 * <p><b>Data Layout:</b>
 * The {@code section} long[] array contains multiple LOD levels concatenated:
 * <ul>
 *   <li>L0: 4096 longs (16×16×16 blocks, each long encodes: block ID 20b + biome ID 9b + light 8b +
 *       reserved 27b)</li>
 *   <li>L1: 512 longs (8×8×8 blocks)</li>
 *   <li>L2: 64 longs (4×4×4 blocks)</li>
 *   <li>L3: 8 longs (2×2×2 blocks)</li>
 *   <li>L4: 1 long (1×1×1 block)</li>
 * </ul>
 * Total: 4681 longs (37,448 bytes uncompressed)
 *
 * <p><b>Coordinate System:</b>
 * <ul>
 *   <li>cx, cz: Chunk coordinates in the horizontal plane (chunk ÷ chunk width)</li>
 *   <li>cy: Vertical section index, where cy 0 = y 0 to 15</li>
 *   <li>Voxy's hierarchical system treats each 16×16×16 section as a node, with parent levels
 *       merging 2×2×2 child sections</li>
 * </ul>
 */
public final class VoxelizedSectionSnapshot implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Chunk X coordinate (in section units) */
    public final int cx;
    /** Chunk Y coordinate (section index, 0 = blocks 0-15) */
    public final int cy;
    /** Chunk Z coordinate (in section units) */
    public final int cz;

    /**
     * The voxel data array (4681 longs total across all LOD levels). This is a direct reference
     * to the underlying long[] and should be treated as immutable.
     */
    public final long[] section;

    /** World identifier string (e.g., "minecraft:overworld" or custom identifier) */
    public final String worldId;

    /** Timestamp (milliseconds since epoch) when this snapshot was captured */
    public final long captureTimestamp;

    /**
     * Create a snapshot of a VoxelizedSection.
     *
     * @param cx Chunk X coordinate
     * @param cy Chunk Y coordinate (section index)
     * @param cz Chunk Z coordinate
     * @param section The voxel data array (will be copied to ensure immutability)
     * @param worldId World identifier string
     */
    public VoxelizedSectionSnapshot(int cx, int cy, int cz, long[] section, String worldId) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.section = section.clone(); // Defensive copy to prevent external modification
        this.worldId = worldId;
        this.captureTimestamp = System.currentTimeMillis();
    }

    /**
     * Create a snapshot without copying the section data (direct reference). Use with caution.
     *
     * @param cx Chunk X coordinate
     * @param cy Chunk Y coordinate (section index)
     * @param cz Chunk Z coordinate
     * @param section The voxel data array (used as-is without copying)
     * @param worldId World identifier string
     * @param defensive If true, the section array is cloned; if false, no copy is made
     */
    static VoxelizedSectionSnapshot createDirect(int cx, int cy, int cz, long[] section,
            String worldId, boolean defensive) {
        return new VoxelizedSectionSnapshot(cx, cy, cz, defensive ? section.clone() : section,
                worldId);
    }

    /**
     * Get the size of the voxel data in longs. Should always be 4681 for a complete VoxelizedSection.
     *
     * @return The length of the section array
     */
    public int getSectionLength() {
        return section.length;
    }

    /**
     * Get the size of the voxel data in bytes.
     *
     * @return section.length * 8
     */
    public long getSectionSizeBytes() {
        return (long) section.length * 8L;
    }

    /**
     * Get a human-readable description of the snapshot.
     *
     * @return String describing coordinates, size, and world
     */
    @Override
    public String toString() {
        return String.format(
                "VoxelizedSectionSnapshot{cx=%d, cy=%d, cz=%d, worldId='%s', sectionLength=%d, "
                        + "sizeBytes=%d, capturedAt=%d}",
                cx, cy, cz, worldId, section.length, getSectionSizeBytes(), captureTimestamp);
    }

    /**
     * Get a voxel value at a specific LOD level and position.
     *
     * <p><b>Note:</b> This is a utility method for testing/inspection. For bulk data export, access
     * {@code section} directly.
     *
     * @param lodLevel The LOD level (0-4)
     * @param x Local X inside the level (0-15 for L0, 0-7 for L1, etc.)
     * @param y Local Y inside the level
     * @param z Local Z inside the level
     * @return The encoded voxel value (long)
     */
    public long getVoxelAt(int lodLevel, int x, int y, int z) {
        if (lodLevel < 0 || lodLevel > 4) {
            throw new IllegalArgumentException("Invalid LOD level: " + lodLevel);
        }

        int baseIndex = getBaseIndexForLevel(lodLevel);
        int size = 16 >> lodLevel; // Size of each dimension at this LOD level
        if (x < 0 || x >= size || y < 0 || y >= size || z < 0 || z >= size) {
            throw new IllegalArgumentException(
                    String.format(
                            "Invalid voxel coordinates for LOD %d: (%d, %d, %d), valid range is "
                                    + "[0, %d)",
                            lodLevel, x, y, z, size));
        }

        int index = baseIndex + x + y * size * size + z * size;
        return section[index];
    }

    /**
     * Calculate the base index in the section array for a given LOD level.
     *
     * @param level The LOD level (0-4)
     * @return The starting index in the section array for that level
     */
    private static int getBaseIndexForLevel(int level) {
        // L0: 4096 (16^3)
        // L1: 512 (8^3)
        // L2: 64 (4^3)
        // L3: 8 (2^3)
        // L4: 1 (1^3)
        return switch (level) {
            case 0 -> 0;
            case 1 -> 4096;
            case 2 -> 4096 + 512;
            case 3 -> 4096 + 512 + 64;
            case 4 -> 4096 + 512 + 64 + 8;
            default -> throw new IllegalArgumentException("Invalid LOD level: " + level);
        };
    }

    /**
     * Get the size (number of blocks per dimension) at a given LOD level.
     *
     * @param level The LOD level (0-4)
     * @return Size: L0=16, L1=8, L2=4, L3=2, L4=1
     */
    public static int getSizeForLevel(int level) {
        return 16 >> level;
    }

    /**
     * Get the number of longs for a given LOD level.
     *
     * @param level The LOD level (0-4)
     * @return Count: L0=4096, L1=512, L2=64, L3=8, L4=1
     */
    public static int getLongsForLevel(int level) {
        int size = getSizeForLevel(level);
        return size * size * size;
    }
}
