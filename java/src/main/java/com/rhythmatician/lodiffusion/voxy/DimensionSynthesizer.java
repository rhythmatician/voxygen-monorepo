package com.rhythmatician.lodiffusion.voxy;

/**
 * Deep module seam per {@link CONTEXT.md#generation} Dimension.
 * Produces semantic 32³ {@link VoxelVolume} at the requested {@link Level} without materializing a vanilla chunk
 * (A→C without B), owning Mipper rule and L4/L3 honest omission per Fidelity Profile.
 */
public interface DimensionSynthesizer {
    VoxelVolume synthesize(Level level, SectionPos origin);
}
