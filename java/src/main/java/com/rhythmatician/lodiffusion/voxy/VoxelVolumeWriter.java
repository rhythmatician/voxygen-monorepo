package com.rhythmatician.lodiffusion.voxy;

/**
 * Deep module seam between generation and storage.
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
}
