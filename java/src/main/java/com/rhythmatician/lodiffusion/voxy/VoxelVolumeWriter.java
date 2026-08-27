package com.rhythmatician.lodiffusion.voxy;

/**
 * Deep module seam between generation and storage — the test surface for all Voxy writes.
 *
 * <p><b>Spec seam:</b> No caller sees reflection. Implementations are two adapters:
 * <ul>
 *   <li>{@code RealVoxyVolumeWriter} — production, reflection-backed (MethodHandle/VarHandle where
 * feasible; see its class Javadoc for reflection vs MethodHandle trade-off);</li>
 *   <li>{@code InMemoryVolumeWriter} — tests, no Voxy jar required.</li>
 * </ul>
 *
 * <p><b>Hidden details:</b> All Voxy-specific mechanics are owned by the writer
 * implementation and never leak through this interface: axis order (YZX ↔ model XYZ
 * transpose), scale clamp ({@code Math.min(coord/scale, nativeRes-1)} / {@code clampToNativeRes}),
 * packed 64-bit voxel layout (block/biome/light bits), light defaults,
 * {@code WorldSection} 32&sup3; mapping, CAS via {@link java.lang.invoke.VarHandle}
 * on {@code nonEmptyChildren}, {@code markDirty} lifecycle, mip/update handling.
 * Callers work only with semantic types {@link SectionPos}, {@link Level},
 * {@link VoxelVolume}, {@link WriteOutcome}, {@link CanonicalRegistries},
 * {@link WorldSectionCoord}.
 *
 * <p><b>Spec extension &mdash; new types not in original scope:</b> The 4 shallow modules
 * ({@code VoxyCompat}, {@code VoxyEngine}, and {@code VoxyWorldBinding})
 * are retained as a migration facade (package-private after follow-up PR). The new
 * semantic types added alongside this seam ({@link VoxelVolume}, {@link SectionPos},
 * {@link Level}, {@link WriteOutcome}, {@link CanonicalRegistries},
 * {@link WorldSectionCoord}) are a spec extension that makes the seam type-safe and
 * testable without Voxy on the classpath.
 *
 * <p>
 *
 * <p>Two explicit operations:
 * <ul>
 *   <li>{@link #writeSection(SectionPos, VoxelVolume)} - L0 section write, volume extent must be 16.</li>
 *   <li>{@link #writeRegion(SectionPos, Level, VoxelVolume)} - 32^3 octree region write,
 *       volume extent must be 32, origin is an L0 SectionPos aligned to the level's region grid.</li>
 * </ul>
 *
 * <p>No operation infers extent. Contract violations for invalid non-null values throw
 * {@code IllegalArgumentException}; null references throw {@code NullPointerException};
 * binding unavailability throws unchecked {@link VolumeUnavailableException}.
 */
public interface VoxelVolumeWriter {
    /**
     * Write one L0 16^3 section.
     *
     * @throws NullPointerException if pos or volume is null
     * @throws IllegalArgumentException if volume extent != 16 or ids invalid
     * @throws VolumeUnavailableException if backend is not available
     */
    WriteOutcome writeSection(SectionPos pos, VoxelVolume volume);

    /**
     * Write one 32^3 octree region at the given level.
     *
     * @param origin L0 SectionPos of the region's minimum corner, must be
     *               aligned: each coordinate divisible by {@code level.regionSections()}
     * @param level  L0..L4, controls voxel scale (1 &lt;&lt; level blocks per voxel)
     * @throws NullPointerException if origin, level, or volume is null
     * @throws IllegalArgumentException if volume extent != 32, origin not aligned to level,
     *                                  or ids invalid
     * @throws VolumeUnavailableException if backend is not available
     */
    WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume);

    /**
     * Region write carrying a caller-computed vanilla-preserve octant mask.
     * Octants named in the mask are protected from candidate overwrite by the
     * storage backend so loaded vanilla terrain survives coarse writes.
     * Default ignores the mask for writers that do not support preservation.
     */
    default WriteOutcome writeRegion(
            SectionPos origin, Level level, VoxelVolume volume, byte preserveOctantsMask) {
        return writeRegion(origin, level, volume);
    }

    /**
     * Write all children of one parent, then publish the exact non-empty child
     * mask once every child outcome is terminal. No partial mask may escape.
     */
    default ParentRefinementResult refineParent(ParentRefinementIntent intent) {
        throw new UnsupportedOperationException("parent refinement transactions are not supported");
    }
    /**
     * Returns save-queue depth for backpressure, or 0 if unavailable.
     * Default returns 0 (no backpressure signal).
     *
     * <p><b>YAGNI note (Fowler Speculative Generality):</b> only
     * {@link RealVoxyVolumeWriter} overrides this; {@link InMemoryVolumeWriter}
     * keeps the default. The default is intentional — callers that do not
     * need backpressure should not be forced to implement it.
     */
    default int saveQueueDepth() {
        return 0;
    }

    /**
     * True if region already fully populated (0xFF nonEmptyChildren).
     * Origin is the L0 SectionPos aligned to level. Default returns false.     *
     * <p><b>YAGNI note (Fowler Speculative Generality):</b> only
     * {@link RealVoxyVolumeWriter} overrides this. The default keeps the
     * seam minimal for test doubles; adding complexity here would be
     * speculative until a second production writer needs it.     */
    default boolean isRegionFullyPopulated(SectionPos origin, Level level) {
        return false;
    }

    /**
     * True when coarse fallback coverage exists at this exact region.
     * Refinement callers use this as an admission rule; renderer publication
     * remains an implementation obligation of {@link #writeRegion}.
     */
    default boolean hasRegionCoverage(SectionPos origin, Level level) {
        return false;
    }

}
