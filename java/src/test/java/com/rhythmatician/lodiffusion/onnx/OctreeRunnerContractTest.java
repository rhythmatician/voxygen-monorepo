package com.rhythmatician.lodiffusion.onnx;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link OctreeModelRunner} helper methods.
 *
 * <p>These test the static contract helpers (sigmoidThreshold, computeArgmax)
 * without requiring ONNX models or DJL.  Package-private access lets us
 * call the methods directly.
 */
class OctreeRunnerContractTest {

    // ── sigmoidThreshold ────────────────────────────────────────────────

    @Test
    void sigmoidThreshold_allPositive_allBitsSet() {
        // Large positive logits → sigmoid ≈ 1.0 → all bits set
        float[] logits = {10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f};
        byte mask = OctreeModelRunner.sigmoidThreshold(logits);
        assertEquals((byte) 0xFF, mask,
                "All positive logits should set all 8 occupancy bits");
    }

    @Test
    void sigmoidThreshold_allNegative_noBitsSet() {
        // Large negative logits → sigmoid ≈ 0.0 → no bits set
        float[] logits = {-10f, -10f, -10f, -10f, -10f, -10f, -10f, -10f};
        byte mask = OctreeModelRunner.sigmoidThreshold(logits);
        assertEquals((byte) 0x00, mask,
                "All negative logits should clear all occupancy bits");
    }

    @Test
    void sigmoidThreshold_zeroLogit_bitSet() {
        // sigmoid(0) = 0.5 > occThreshold() (default 0.3) → bit IS set
        float[] logits = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        byte mask = OctreeModelRunner.sigmoidThreshold(logits);
        assertEquals((byte) 0xFF, mask,
                "sigmoid(0)=0.5 is > default threshold 0.3, so all bits set");
    }

    @Test
    void sigmoidThreshold_belowThreshold_noSet() {
        // sigmoid(-2) ≈ 0.119 < occThreshold() (default 0.3) → no bits set
        float[] logits = {-2f, -2f, -2f, -2f, -2f, -2f, -2f, -2f};
        byte mask = OctreeModelRunner.sigmoidThreshold(logits);
        assertEquals((byte) 0x00, mask,
                "sigmoid(-2)≈0.119 is not > default threshold 0.3, so no bits set");
    }

    @Test
    void sigmoidThreshold_singleOctant() {
        // Only octant 3 (bit index 3) occupied
        float[] logits = {-10f, -10f, -10f, 10f, -10f, -10f, -10f, -10f};
        byte mask = OctreeModelRunner.sigmoidThreshold(logits);
        assertEquals((byte) (1 << 3), mask,
                "Only bit 3 should be set");
    }

    @Test
    void sigmoidThreshold_alternatingBits() {
        // Even octants occupied, odd empty
        float[] logits = {10f, -10f, 10f, -10f, 10f, -10f, 10f, -10f};
        byte mask = OctreeModelRunner.sigmoidThreshold(logits);
        assertEquals((byte) 0b01010101, mask,
                "Even-indexed bits should be set");
    }

    @ParameterizedTest(name = "logit {0} → sigmoid {1} → bit {2}")
    @CsvSource({
        "0.1,  true",   // sigmoid(0.1) ≈ 0.525 > 0.3 (occThreshold default)
        "-1.0, false",  // sigmoid(-1.0) ≈ 0.269 < 0.3
        "5.0,  true",
        "-5.0, false"
    })
    void sigmoidThreshold_edgeCases(float logit, boolean expectSet) {
        float[] logits = {logit, -100f, -100f, -100f, -100f, -100f, -100f, -100f};
        byte mask = OctreeModelRunner.sigmoidThreshold(logits);
        if (expectSet) {
            assertNotEquals(0, mask & 1, "Bit 0 should be set for logit=" + logit);
        } else {
            assertEquals(0, mask & 1, "Bit 0 should be clear for logit=" + logit);
        }
    }

    // ── computeArgmax (deprecated 4D path) ────────────────────────────

    @SuppressWarnings("deprecation")
    @Test
    void computeArgmax_uniformClass1() {
        // 2 classes, all voxels have class 1 as winner
        int vocabSize = 2;
        float[][][][] logits = new float[vocabSize][32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++) {
                    logits[0][y][z][x] = 0.0f;  // air
                    logits[1][y][z][x] = 1.0f;  // solid
                }

        int[][][] argmax = OctreeModelRunner.computeArgmax(logits, vocabSize);

        assertEquals(32, argmax.length, "Y dimension");
        assertEquals(32, argmax[0].length, "Z dimension");
        assertEquals(32, argmax[0][0].length, "X dimension");

        // All voxels should be class 1
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    assertEquals(1, argmax[y][z][x],
                            "Voxel (" + y + "," + z + "," + x + ") should be class 1");
    }

    @SuppressWarnings("deprecation")
    @Test
    void computeArgmax_uniformAir() {
        // 4 classes, class 0 (air) wins everywhere
        int vocabSize = 4;
        float[][][][] logits = new float[vocabSize][32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++) {
                    logits[0][y][z][x] = 5.0f;  // air: high
                    for (int c = 1; c < vocabSize; c++)
                        logits[c][y][z][x] = -1.0f;
                }

        int[][][] argmax = OctreeModelRunner.computeArgmax(logits, vocabSize);

        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    assertEquals(0, argmax[y][z][x],
                            "All voxels should be air (class 0)");
    }

    @SuppressWarnings("deprecation")
    @Test
    void computeArgmax_checkerboard() {
        // Alternate between class 0 and class 1 in a known pattern
        int vocabSize = 2;
        float[][][][] logits = new float[vocabSize][32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++) {
                    boolean isOdd = ((x + y + z) & 1) == 1;
                    logits[0][y][z][x] = isOdd ? 1.0f : -1.0f;
                    logits[1][y][z][x] = isOdd ? -1.0f : 1.0f;
                }

        int[][][] argmax = OctreeModelRunner.computeArgmax(logits, vocabSize);

        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++) {
                    int expected = ((x + y + z) & 1) == 1 ? 0 : 1;
                    assertEquals(expected, argmax[y][z][x],
                            "Checkerboard mismatch at (" + y + "," + z + "," + x + ")");
                }
    }

    @SuppressWarnings("deprecation")
    @Test
    void computeArgmax_multiClass() {
        // 8 classes; class = (y % 8) wins at each Y slice
        int vocabSize = 8;
        float[][][][] logits = new float[vocabSize][32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    for (int c = 0; c < vocabSize; c++)
                        logits[c][y][z][x] = (c == (y % vocabSize)) ? 10.0f : -10.0f;

        int[][][] argmax = OctreeModelRunner.computeArgmax(logits, vocabSize);

        for (int y = 0; y < 32; y++)
            assertEquals(y % vocabSize, argmax[y][0][0],
                    "Y=" + y + " should have argmax class " + (y % vocabSize));
    }

    // ── Output shape sanity ─────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    @Test
    void argmax_shape_is_32cubed() {
        int vocabSize = 4;
        float[][][][] logits = new float[vocabSize][32][32][32];
        int[][][] argmax = OctreeModelRunner.computeArgmax(logits, vocabSize);

        assertEquals(32, argmax.length);
        assertEquals(32, argmax[0].length);
        assertEquals(32, argmax[0][0].length);
    }

    // ── computeArgmaxDirect (fused flat-array path) ─────────────────────

    @Test
    void computeArgmaxDirect_uniformClass1() {
        int vocabSize = 2;
        int S = 32;
        float[] flat = new float[vocabSize * S * S * S];
        // Channel 0 = 0.0f (default), channel 1 = 1.0f
        int offset1 = S * S * S;
        for (int i = offset1; i < vocabSize * S * S * S; i++) {
            flat[i] = 1.0f;
        }

        int[][][] argmax = OctreeModelRunner.computeArgmaxDirect(flat, vocabSize);

        for (int y = 0; y < S; y++)
            for (int z = 0; z < S; z++)
                for (int x = 0; x < S; x++)
                    assertEquals(1, argmax[y][z][x],
                            "Voxel (" + y + "," + z + "," + x + ") should be class 1");
    }

    @Test
    void computeArgmaxDirect_matchesDeprecated() {
        // Verify fused path produces identical results to the deprecated path
        int vocabSize = 4;
        int S = 32;
        float[][][][] logits = new float[vocabSize][S][S][S];
        float[] flat = new float[vocabSize * S * S * S];

        // Fill with a deterministic pattern
        int idx = 0;
        for (int c = 0; c < vocabSize; c++)
            for (int y = 0; y < S; y++)
                for (int z = 0; z < S; z++)
                    for (int x = 0; x < S; x++) {
                        float v = (float) Math.sin(c * 100 + y * 31 + z * 7 + x);
                        logits[c][y][z][x] = v;
                        flat[idx++] = v;
                    }

        @SuppressWarnings("deprecation")
        int[][][] oldResult = OctreeModelRunner.computeArgmax(logits, vocabSize);
        int[][][] newResult = OctreeModelRunner.computeArgmaxDirect(flat, vocabSize);

        for (int y = 0; y < S; y++)
            for (int z = 0; z < S; z++)
                for (int x = 0; x < S; x++)
                    assertEquals(oldResult[y][z][x], newResult[y][z][x],
                            "Mismatch at (" + y + "," + z + "," + x + ")");
    }

    @Test
    void computeArgmaxDirect_withOffset() {
        // Test the offset overload for batched slicing
        int vocabSize = 2;
        int S = 32;
        int sampleSize = vocabSize * S * S * S;
        float[] flat = new float[2 * sampleSize]; // 2 samples

        // Sample 0: class 0 wins (all zeros, default)
        // Sample 1: class 1 wins
        for (int i = sampleSize + S * S * S; i < 2 * sampleSize; i++) {
            flat[i] = 5.0f;
        }

        int[][][] argmax0 = OctreeModelRunner.computeArgmaxDirect(flat, 0, vocabSize);
        int[][][] argmax1 = OctreeModelRunner.computeArgmaxDirect(flat, sampleSize, vocabSize);

        assertEquals(0, argmax0[0][0][0], "Sample 0 should be class 0");
        assertEquals(1, argmax1[0][0][0], "Sample 1 should be class 1");
    }
}
