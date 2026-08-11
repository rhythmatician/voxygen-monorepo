package com.rhythmatician.lodiffusion.onnx;

/**
 * TODO: Data generation for biome_ids channel in sparse_octree training npz files.
 *
 * <h3>Scope</h3>
 * The sparse_octree.py model now requires a third input: biome_ids [B, 4, 2, 4].
 * All existing training npz files must be regenerated with this key:
 *  - VoxelTree/noise_training_data/sparse_octree_pairs.npz (296 samples)
 *  - Any future batches collected via data-cli.py
 *
 * <h3>Biome ID Shape and Layout</h3>
 * {@code biome_ids: int32[N, 4, 2, 4]}
 *  - [N]: batch dimension (sample count)
 *  - [4, 2, 4]: spatial cells matching noise_3d resolution
 *    - X-cells: 0-3 (quartile resolution in X)
 *    - Y-cells: 0-1 (2 vanilla noise cells per 16-block section)
 *    - Z-cells: 0-3 (quartile resolution in Z)
 *
 * <h3>Data Source: BiomePaletteSSBO</h3>
 * The GPU biome classifier (Pass 0 in terrain_compute.comp) produces a
 * 4x4x96 lattice of biome IDs. To extract a section:
 *
 * <pre>
 *   1. Read GPU output: {@link com.rhythmatician.lodiffusion.gpu.BiomePaletteSSBO#readBiomeOutput()}
 *   2. For a section at sectionY [-4, 19]:
 *      - cyStart = (sectionY + 4) * 2  (vanilla noise cell coordinate)
 *      - qy_0 = cyStart * 2 + 1
 *      - qy_1 = cyStart * 2 + 3
 *      - Extract indices [qx in 0-3][qz in 0-3][qy in {qy_0, qy_1}]
 * </pre>
 *
 * <h3>Implementation Checklist</h3>
 * - [ ] Add BiomePaletteSSBO.sampleBiomeIdsForSection(int chunkX, int chunkZ, int sectionY)
 *       or similar public method to extract [4][2][4] biome array
 * - [ ] Update VoxelTree/data-cli.py or generation script to call biome sampler
 * - [ ] Regenerate sparse_octree_pairs.npz with biome_ids key
 * - [ ] Remove this stub file once implementation is live
 *
 * @see com.rhythmatician.lodiffusion.gpu.BiomePaletteSSBO
 * @see com.rhythmatician.lodiffusion.voxy.WorldNoiseAccess#sampleNoise3DForSection
 */
public final class SparseOctreeBiomeDataGeneration {
    private SparseOctreeBiomeDataGeneration() {
        throw new UnsupportedOperationException("Stub class for documentation only");
    }
}
