package com.rhythmatician.lodiffusion.world.noise;

/**
 * Abstraction over the heightmap source used for column-level conditioning.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link VanillaHeightmapProvider} — derives heightmaps via the vanilla
 *       {@code ChunkNoiseSampler} path (CPU, bit-exact with vanilla MC).</li>
 *   <li>{@code GpuHeightmapProvider} (future) — extracts heightmaps from the
 *       GPU density zero-crossings computed by the shadow router.</li>
 * </ul>
 *
 * <p>No downstream code may call vanilla heightmap APIs directly.  All
 * heightmap access flows through this interface.
 *
 * @see HeightmapData
 * @see NoiseRouterSampler
 */
public interface HeightmapProvider {

    /**
     * Sample world-surface and ocean-floor heightmaps for a single chunk column.
     *
     * <p>Both heightmaps are returned as {@code float[16][16]} in block-Y
     * coordinates, indexed by {@code [localX][localZ]}.  The semantics match
     * vanilla's {@code Heightmap.Type.WORLD_SURFACE_WG} and
     * {@code OCEAN_FLOOR_WG}: the value at each column is the Y of the highest
     * relevant block plus one.
     *
     * <p>The implementation must be <b>thread-safe</b>: multiple generation
     * threads may call this concurrently for different columns.
     *
     * @param sectionX chunk-X coordinate
     * @param sectionZ chunk-Z coordinate
     * @return heightmap pair, never {@code null}
     */
    HeightmapData sampleHeightmaps(int sectionX, int sectionZ);

    /**
     * Human-readable name for logging (e.g. {@code "vanilla_cpu"},
     * {@code "gpu_zero_crossing"}).
     */
    String backendName();

    /**
     * Release any resources held by this provider.
     */
    default void close() { }
}
