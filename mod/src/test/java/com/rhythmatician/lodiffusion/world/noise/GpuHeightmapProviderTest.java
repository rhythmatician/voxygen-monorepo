package com.rhythmatician.lodiffusion.world.noise;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GpuHeightmapProvider}.
 *
 * <p>Tests cover the pure-function core: {@link GpuHeightmapProvider#upsample1D},
 * {@link GpuHeightmapProvider#bilinearUpsample}, and
 * {@link GpuHeightmapProvider#computeHeightmaps}.  No Minecraft runtime or GL context
 * is required — all test inputs are synthetic {@link SectionNoiseData} instances.
 *
 * <p>The GPU dispatch path ({@code sampleHeightmaps}) is NOT tested here because it
 * requires an active {@link GpuNoiseDispatchQueue} instance on the render thread.
 */
class GpuHeightmapProviderTest {

    // ── Helpers ──────────────────────────────────────────────────────

    private static final int FLAT_LEN = SectionNoiseData.FLAT_LENGTH; // 480
    private static final int FD_BASE  = RouterField.FINAL_DENSITY.ordinal() * SectionNoiseData.CELLS_PER_FIELD; // 7*32=224

    /**
     * Build a full column of SectionNoiseData arrays for the overworld (-4..19).
     * All FINAL_DENSITY values default to -1.0f (air).
     */
    private static SectionNoiseData[] emptyColumn(int sectionX, int sectionZ) {
        int count = GpuHeightmapProvider.COLUMN_SECTIONS;
        SectionNoiseData[] col = new SectionNoiseData[count];
        for (int i = 0; i < count; i++) {
            int sectionY = GpuHeightmapProvider.MIN_SECTION_Y + i;
            float[] flat = new float[FLAT_LEN];
            Arrays.fill(flat, 0.0f);
            // Set all FINAL_DENSITY to -1 (air)
            for (int c = 0; c < SectionNoiseData.CELLS_PER_FIELD; c++) flat[FD_BASE + c] = -1.0f;
            col[i] = new SectionNoiseData(flat, sectionX, sectionY, sectionZ);
        }
        return col;
    }

    /**
     * Set FINAL_DENSITY at a specific (sectionY, qx, qy, qz) position to {@code value}.
     * qy=0..1, qx=0..3, qz=0..3.
     */
    private static void setDensity(SectionNoiseData[] column, int sectionY,
                                   int qx, int qy, int qz, float value) {
        int si = sectionY - GpuHeightmapProvider.MIN_SECTION_Y;
        float[] flat = column[si].flat().clone();
        flat[FD_BASE + qx * 8 + qy * 4 + qz] = value;
        column[si] = new SectionNoiseData(flat, column[si].sectionX(),
                column[si].sectionY(), column[si].sectionZ());
    }

    // ── Constants ────────────────────────────────────────────────────

    @Test
    void columnSectionsIs24() {
        assertEquals(24, GpuHeightmapProvider.COLUMN_SECTIONS);
    }

    @Test
    void minSectionYIsMinus4() {
        assertEquals(-4, GpuHeightmapProvider.MIN_SECTION_Y);
    }

    @Test
    void maxSectionYIs19() {
        assertEquals(19, GpuHeightmapProvider.MAX_SECTION_Y);
    }

    @Test
    void seaLevelIs63() {
        assertEquals(63, GpuHeightmapProvider.SEA_LEVEL);
    }

    @Test
    void backendNameIsGpuZeroCrossing() {
        assertEquals("gpu_zero_crossing", new GpuHeightmapProvider().backendName());
    }

    // ── upsample1D ───────────────────────────────────────────────────

    @Nested
    class Upsample1D {

        @Test
        void uniformInputProducesUniformOutput() {
            float[] in = {5.0f, 5.0f, 5.0f, 5.0f};
            float[] out = GpuHeightmapProvider.upsample1D(in);
            assertEquals(16, out.length);
            for (float v : out) assertEquals(5.0f, v, 0.001f);
        }

        @Test
        void outputLengthIs16() {
            float[] out = GpuHeightmapProvider.upsample1D(new float[]{1, 2, 3, 4});
            assertEquals(16, out.length);
        }

        @Test
        void quartreCentresPassThroughExact() {
            // Quart centres at block offsets 2, 6, 10, 14
            float[] in = {10.0f, 20.0f, 30.0f, 40.0f};
            float[] out = GpuHeightmapProvider.upsample1D(in);
            // At the exact quart centres, no interpolation — value should match
            assertEquals(10.0f, out[2], 0.001f, "Centre at idx=2");
            assertEquals(20.0f, out[6], 0.001f, "Centre at idx=6");
            assertEquals(30.0f, out[10], 0.001f, "Centre at idx=10");
            assertEquals(40.0f, out[14], 0.001f, "Centre at idx=14");
        }

        @Test
        void midpointBetweenCentresInterpolated() {
            // Between centre at bx=2 (value=0) and bx=6 (value=4)
            // midpoint bx=4: t=(4-2)/4=0.5 → value=2
            float[] in = {0.0f, 4.0f, 4.0f, 4.0f};
            float[] out = GpuHeightmapProvider.upsample1D(in);
            assertEquals(2.0f, out[4], 0.001f, "Midpoint between q0=0 and q1=4");
        }

        @Test
        void blocksBeyondLastCentreAreClamped() {
            // bx=15 is beyond quart centre at 14 — should return quart[3] value
            float[] in = {1.0f, 2.0f, 3.0f, 99.0f};
            float[] out = GpuHeightmapProvider.upsample1D(in);
            assertEquals(99.0f, out[15], 0.001f, "bx=15 clamped to last quart");
        }

        @Test
        void blocksBeforeFirstCentreAreClamped() {
            // bx=0 and bx=1 are before quart centre at 2 — should return quart[0] value
            float[] in = {77.0f, 2.0f, 3.0f, 4.0f};
            float[] out = GpuHeightmapProvider.upsample1D(in);
            assertEquals(77.0f, out[0], 0.001f, "bx=0 clamped to first quart");
            assertEquals(77.0f, out[1], 0.001f, "bx=1 clamped to first quart");
        }

        @Test
        void linearGradientInterpolation() {
            // Values at centres: 2→0, 6→4, 10→8, 14→12 (linear y=x-2)
            float[] in = {0.0f, 4.0f, 8.0f, 12.0f};
            float[] out = GpuHeightmapProvider.upsample1D(in);
            // bx=3: t=(3-2)/4=0.25 → 0 + 0.25*4 = 1
            assertEquals(1.0f, out[3], 0.001f, "Quarter-way between q0 and q1");
            // bx=8: t=(8-6)/4=0.5 → 4 + 0.5*4 = 6
            assertEquals(6.0f, out[8], 0.001f, "Halfway between q1 and q2");
        }
    }

    // ── bilinearUpsample ─────────────────────────────────────────────

    @Nested
    class BilinearUpsample {

        @Test
        void outputIs16x16() {
            float[][] in = new float[4][4];
            float[][] out = GpuHeightmapProvider.bilinearUpsample(in);
            assertEquals(16, out.length);
            for (float[] row : out) assertEquals(16, row.length);
        }

        @Test
        void uniformInputProducesUniformOutput() {
            float[][] in = new float[4][4];
            for (float[] row : in) Arrays.fill(row, 42.0f);
            float[][] out = GpuHeightmapProvider.bilinearUpsample(in);
            for (int bx = 0; bx < 16; bx++)
                for (int bz = 0; bz < 16; bz++)
                    assertEquals(42.0f, out[bx][bz], 0.001f,
                            "Uniform mismatch at [" + bx + "][" + bz + "]");
        }

        @Test
        void quartCentreValuesPreserved() {
            // Known values at quart centres (block offsets 2,6,10,14)
            float[][] in = {
                {10f, 20f, 30f, 40f},   // qx=0
                {11f, 21f, 31f, 41f},   // qx=1
                {12f, 22f, 32f, 42f},   // qx=2
                {13f, 23f, 33f, 43f}    // qx=3
            };
            float[][] out = GpuHeightmapProvider.bilinearUpsample(in);
            // At (bx=2, bz=2) → quart (qx=0, qz=0) → 10
            assertEquals(10.0f, out[2][2], 0.001f, "Corner quart [0][0]");
            // At (bx=14, bz=14) → quart (qx=3, qz=3) → 43
            assertEquals(43.0f, out[14][14], 0.001f, "Corner quart [3][3]");
            // At (bx=6, bz=10) → quart (qx=1, qz=2) → 31
            assertEquals(31.0f, out[6][10], 0.001f, "Inner quart [1][2]");
        }

        @Test
        void centerBlockInterpolatesBothAxes() {
            // qx=0 and qx=1 both at bz=2 level have values 0 and 4
            // midpoint bx=4 → 2; then at bz=2 (quart centre) → no Z interpolation needed
            float[][] in = {
                {0f, 0f, 0f, 0f},   // qx=0
                {4f, 4f, 4f, 4f},   // qx=1
                {4f, 4f, 4f, 4f},   // qx=2
                {4f, 4f, 4f, 4f}    // qx=3
            };
            float[][] out = GpuHeightmapProvider.bilinearUpsample(in);
            // bx=4: halfway between centre@2(val=0) and centre@6(val=4) → 2.0
            assertEquals(2.0f, out[4][2], 0.001f, "X midpoint at quart-Z centre");
        }
    }

    // ── computeHeightmaps ────────────────────────────────────────────

    @Nested
    class ComputeHeightmaps {

        @Test
        void allAirReturnsDefaultSurface() {
            SectionNoiseData[] column = emptyColumn(0, 0);
            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            float expected = GpuHeightmapProvider.MIN_SECTION_Y * 16;
            // All 16×16 values should be at the default bottom-of-world Y
            for (int bx = 0; bx < 16; bx++)
                for (int bz = 0; bz < 16; bz++)
                    assertEquals(expected, result.worldSurface()[bx][bz], 0.001f,
                            "Expected default surface at [" + bx + "][" + bz + "]");
        }

        @Test
        void solidAtTopSectionTopQy_returnsSurfaceNearTop() {
            // Place a single solid voxel at sectionY=19, qx=0, qy=1, qz=0
            SectionNoiseData[] column = emptyColumn(0, 0);
            setDensity(column, 19, 0, 1, 0, 1.0f);

            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            // Surface Y for quart column (qx=0, qz=0) should be: 19*16 + 1*8 + 4 = 316
            // After bilinear upsample, block (2,2) is the quart (0,0) centre
            assertEquals(316.0f, result.worldSurface()[2][2], 0.001f);
        }

        @Test
        void solidAtSpecificSectionAndQy() {
            // sectionY=5, qx=1, qy=1, qz=1 → surfaceY = 5*16 + 1*8 + 4 = 92
            SectionNoiseData[] column = emptyColumn(0, 0);
            setDensity(column, 5, 1, 1, 1, 0.5f);

            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            // Quart centre (qx=1, qz=1) → block (6, 6)
            assertEquals(92.0f, result.worldSurface()[6][6], 0.001f);
        }

        @Test
        void topSolidWinsOverLower() {
            // Place solid at sectionY=10 qy=0 AND sectionY=15 qy=1 for same (qx=2, qz=2)
            // The top one (sectionY=15) should win
            SectionNoiseData[] column = emptyColumn(0, 0);
            setDensity(column, 10, 2, 0, 2, 1.0f);   // lower
            setDensity(column, 15, 2, 1, 2, 1.0f);   // higher → should win

            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            // sectionY=15, qy=1: blockY = 15*16 + 1*8 + 4 = 252
            float expected = 15 * 16 + 1 * 8 + 4;
            // Quart centre (qx=2, qz=2) → block (10, 10)
            assertEquals(expected, result.worldSurface()[10][10], 0.001f);
        }

        @Test
        void outputIs16x16For_WorldSurface() {
            SectionNoiseData[] column = emptyColumn(0, 0);
            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            assertEquals(16, result.worldSurface().length);
            for (float[] row : result.worldSurface()) assertEquals(16, row.length);
        }

        @Test
        void outputIs16x16For_OceanFloor() {
            SectionNoiseData[] column = emptyColumn(0, 0);
            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            assertEquals(16, result.oceanFloor().length);
            for (float[] row : result.oceanFloor()) assertEquals(16, row.length);
        }

        @Test
        void oceanFloorEqualsSurfaceForSubmergedColumn() {
            // Surface Y=52 (below SEA_LEVEL=63) → oceanFloor should equal worldSurface
            SectionNoiseData[] column = emptyColumn(0, 0);
            // sectionY=3, qy=0: blockY = 3*16 + 0*8 + 4 = 52
            setDensity(column, 3, 0, 0, 0, 1.0f);

            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            // At quart (0,0) centre blocks (2,2)
            float surface = result.worldSurface()[2][2];
            float floor   = result.oceanFloor()[2][2];
            assertTrue(surface < GpuHeightmapProvider.SEA_LEVEL,
                    "Surface should be below sea level: " + surface);
            assertEquals(surface, floor, 0.001f, "Ocean floor should equal surface for submerged column");
        }

        @Test
        void differentColumnsAreIndependent() {
            // Solid at qx=0, qz=0 only; other columns stay at default
            SectionNoiseData[] column = emptyColumn(0, 0);
            setDensity(column, 10, 0, 1, 0, 1.0f);   // only (qx=0, qz=0)

            HeightmapData result = new GpuHeightmapProvider().computeHeightmaps(column);

            // (qx=3, qz=3) → centre blocks (14,14) should be at default
            float expected = GpuHeightmapProvider.MIN_SECTION_Y * 16;
            assertEquals(expected, result.worldSurface()[14][14], 0.001f);
        }
    }

    // ── SectionNoiseData.FINAL_DENSITY ordinal check ─────────────────

    @Test
    void finalDensityOrdinalIs7() {
        assertEquals(7, RouterField.FINAL_DENSITY.ordinal());
    }

    @Test
    void flatIndexForFinalDensityAt0_0_0() {
        // base = 7 * 32 = 224
        assertEquals(224, RouterField.FINAL_DENSITY.ordinal() * SectionNoiseData.CELLS_PER_FIELD);
    }
}
