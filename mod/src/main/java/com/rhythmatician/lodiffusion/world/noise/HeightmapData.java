package com.rhythmatician.lodiffusion.world.noise;

/**
 * Immutable heightmap pair for a single 16×16 chunk column.
 *
 * <p>Both fields contain block-Y values in {@code float[16][16]} indexed as
 * {@code [localX][localZ]}.  Values represent the Y coordinate of the highest
 * relevant block plus one (consistent with vanilla's
 * {@code Heightmap.Type.WORLD_SURFACE_WG} semantics).
 *
 * <p>This record is the <b>sole heightmap contract</b> between the upstream
 * noise provider and all downstream consumers (anchor sampling, column context,
 * generation scheduling).  Both the vanilla CPU and GPU shadow router backends
 * produce this exact format.
 *
 * @see HeightmapProvider
 */
public record HeightmapData(
        /**
         * World surface heightmap: highest solid block + 1 per column.
         * Always non-null, {@code float[16][16]}.
         */
        float[][] worldSurface,

        /**
         * Ocean floor heightmap: highest solid block below sea level + 1, or
         * same as {@code worldSurface} for dry columns.
         * Always non-null, {@code float[16][16]}.
         */
        float[][] oceanFloor
) {
    /** Expected grid size (one value per block column). */
    public static final int GRID = 16;

    /**
     * Validate array dimensions.
     */
    public HeightmapData {
        if (worldSurface == null || worldSurface.length != GRID)
            throw new IllegalArgumentException("worldSurface must be float[16][16]");
        if (oceanFloor == null || oceanFloor.length != GRID)
            throw new IllegalArgumentException("oceanFloor must be float[16][16]");
        for (int i = 0; i < GRID; i++) {
            if (worldSurface[i] == null || worldSurface[i].length != GRID)
                throw new IllegalArgumentException("worldSurface[" + i + "] must be float[16]");
            if (oceanFloor[i] == null || oceanFloor[i].length != GRID)
                throw new IllegalArgumentException("oceanFloor[" + i + "] must be float[16]");
        }
    }
}
