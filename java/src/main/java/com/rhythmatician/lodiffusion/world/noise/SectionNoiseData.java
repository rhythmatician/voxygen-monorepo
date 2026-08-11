package com.rhythmatician.lodiffusion.world.noise;

/**
 * Immutable snapshot of all 15 {@link RouterField NoiseRouter fields} sampled
 * at <b>quart resolution</b> for a single 16³-block Voxy section.
 *
 * <p>Vanilla uses cellWidth=4 (4-block quart spacing on X/Z) and cellHeight=8
 * (8-block cell spacing on Y), yielding 4×2×4 cells per 16-block section.
 *
 * <h2>Tensor layout</h2>
 * <pre>
 *   shape:   float[15][4][2][4]          (480 floats)
 *   order:   [field][qx][qy][qz]        channel-outermost, C-contiguous
 *   spacing: 4 blocks on X/Z, 8 blocks on Y
 * </pre>
 *
 * <p>The flat array is indexed as:
 * <pre>
 *   flatIndex = field * 32 + qx * 8 + qy * 4 + qz
 * </pre>
 *
 * <p>This is the <b>sole data contract</b> between the noise source (vanilla CPU
 * or shadow router GPU) and the downstream sparse octree model.  Both
 * {@link VanillaNoiseRouterSampler} and {@code GpuNoiseRouterSampler} produce
 * this exact format.
 *
 * @see RouterField
 * @see NoiseRouterSampler
 */
public record SectionNoiseData(
        /** Flat tensor: {@code float[15 * 4 * 2 * 4]} = 480 floats. */
        float[] flat,
        /** Section X in chunk coordinates. */
        int sectionX,
        /** Section Y in section coordinates (range [-4, 19] for overworld). */
        int sectionY,
        /** Section Z in chunk coordinates. */
        int sectionZ
) {

    /** Floats per spatial cell: 4 × 2 × 4. */
    public static final int CELLS_PER_FIELD = 4 * 2 * 4;  // 32

    /** Total floats in the flat tensor: 15 fields × 32 cells. */
    public static final int FLAT_LENGTH = RouterField.COUNT * CELLS_PER_FIELD;  // 480

    /**
     * Read a single value by field + spatial position.
     *
     * @param field the NoiseRouter field
     * @param qx    quart-X within the section [0, 3]
     * @param qy    quart-Y within the section [0, 1]
     * @param qz    quart-Z within the section [0, 3]
     * @return the sampled density value
     */
    public float get(RouterField field, int qx, int qy, int qz) {
        return flat[field.ordinal() * CELLS_PER_FIELD + qx * 8 + qy * 4 + qz];
    }

    /**
     * Convenience: extract a single field's 4×2×4 sub-array.
     *
     * @param field the NoiseRouter field
     * @return new {@code float[4][2][4]} in [qx][qy][qz] order
     */
    public float[][][] getField(RouterField field) {
        float[][][] out = new float[4][2][4];
        int base = field.ordinal() * CELLS_PER_FIELD;
        for (int qx = 0; qx < 4; qx++)
            for (int qy = 0; qy < 2; qy++)
                for (int qz = 0; qz < 4; qz++)
                    out[qx][qy][qz] = flat[base + qx * 8 + qy * 4 + qz];
        return out;
    }

    /**
     * Validate tensor length.
     *
     * @throws IllegalArgumentException if flat.length != FLAT_LENGTH
     */
    public SectionNoiseData {
        if (flat.length != FLAT_LENGTH) {
            throw new IllegalArgumentException(
                    "Expected " + FLAT_LENGTH + " floats, got " + flat.length);
        }
    }
}
