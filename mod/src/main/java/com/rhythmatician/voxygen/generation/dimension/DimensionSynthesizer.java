package com.rhythmatician.voxygen.generation.dimension;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;

/**
 * Deep module seam per {@link CONTEXT.md#generation} Dimension.
 * Produces semantic 32³ {@link VoxelVolume} at the requested {@link Level} without materializing a vanilla chunk
 * (A→C without B), owning Mipper rule and L4/L3 honest omission per Fidelity Profile.
 */
public interface DimensionSynthesizer {
    public VoxelVolume synthesize(Level level, SectionPos origin);
}
