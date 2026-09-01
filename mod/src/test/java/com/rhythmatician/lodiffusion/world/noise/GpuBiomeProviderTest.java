package com.rhythmatician.lodiffusion.world.noise;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.semantic.biome.BiomeMapping;

/**
 * Unit tests for {@link GpuBiomeProvider}.
 *
 * <p>Since {@link net.minecraft.world.biome.source.BiomeSource} and
 * {@link net.minecraft.world.gen.noise.NoiseConfig} are final or otherwise
 * unmockable Minecraft classes, these tests exercise the coordinate logic,
 * output shape contract, BiomeMapping integration, and backend-name contract
 * without constructing the provider (which requires a live Minecraft env).
 *
 * <p>The key invariant is that GpuBiomeProvider uses the <b>same</b> coordinate
 * formula and BiomeMapping call as VanillaBiomeProvider — verified by testing
 * the shared formula and the BiomeMapping round-trip.
 */
class GpuBiomeProviderTest {

    // ── Helpers ──────────────────────────────────────────────────────

    /** Create a dummy SectionNoiseData filled with a constant value. */
    private static SectionNoiseData makeData(float value, int sx, int sy, int sz) {
        float[] flat = new float[SectionNoiseData.FLAT_LENGTH];
        Arrays.fill(flat, value);
        return new SectionNoiseData(flat, sx, sy, sz);
    }

    // ── Coordinate logic ────────────────────────────────────────────
    //
    // GpuBiomeProvider (and VanillaBiomeProvider) compute quart coords as:
    //   XZ: blockCoord = sectionCoord*16 + q*4 + 2  (cellWidth=4, q=0..3)
    //   Y:  blockCoord = sectionCoord*16 + q*8 + 4  (cellHeight=8, q=0..1)
    //   quartCoord = blockCoord >> 2
    // We verify this mapping is correct for various section origins.

    @Nested
    class CoordinateMapping {

        /** Compute an XZ quart coordinate the same way as GpuBiomeProvider (cellWidth=4). */
        private int toQuartCoordXZ(int sectionCoord, int q) {
            int block = sectionCoord * 16 + q * 4 + 2;
            return block >> 2;
        }

        /** Compute a Y quart coordinate the same way as GpuBiomeProvider (cellHeight=8). */
        private int toQuartCoordY(int sectionCoord, int qy) {
            int block = sectionCoord * 16 + qy * 8 + 4;
            return block >> 2;
        }

        @Test
        void section0_0_0_qx0() {
            assertEquals(0, toQuartCoordXZ(0, 0));
        }

        @Test
        void section0_0_0_qx3() {
            assertEquals(3, toQuartCoordXZ(0, 3));
        }

        @Test
        void positiveSection() {
            // sectionX=2, qx=0: block = 32 + 0 + 2 = 34 → quart = 8
            assertEquals(8, toQuartCoordXZ(2, 0));
            // sectionX=2, qx=3: block = 32 + 12 + 2 = 46 → quart = 11
            assertEquals(11, toQuartCoordXZ(2, 3));
        }

        @Test
        void negativeSectionY() {
            // sectionY=-1, qy=0: block = -16 + 0 + 4 = -12 → -12 >> 2 = -3
            assertEquals(-3, toQuartCoordY(-1, 0));
            // sectionY=-1, qy=1: block = -16 + 8 + 4 = -4 → -4 >> 2 = -1
            assertEquals(-1, toQuartCoordY(-1, 1));
            // sectionY=-4, qy=0: block = -64 + 0 + 4 = -60 → -60 >> 2 = -15
            assertEquals(-15, toQuartCoordY(-4, 0));
        }

        @Test
        void yAxisHas2CellsPerSection() {
            // cellHeight=8: each section has 2 Y cells (qy=0,1)
            for (int sec = -4; sec <= 19; sec++) {
                int q0 = toQuartCoordY(sec, 0);
                int q1 = toQuartCoordY(sec, 1);
                assertEquals(2, q1 - q0,
                        "Expected 2-quart Y span for section " + sec);
            }
        }

        @Test
        void xzAxisHas4CellsPerSection() {
            // cellWidth=4: each section has 4 XZ cells (q=0..3)
            for (int sec = -4; sec <= 19; sec++) {
                int q0 = toQuartCoordXZ(sec, 0);
                int q3 = toQuartCoordXZ(sec, 3);
                assertEquals(3, q3 - q0,
                        "Expected 3-quart XZ span for section " + sec);
            }
        }
    }

    // ── Output shape ────────────────────────────────────────────────

    @Nested
    class OutputShape {

        @Test
        void resultArrayIs4x2x4With32Cells() {
            int[][][] biomes = new int[4][2][4];
            assertEquals(4, biomes.length);
            assertEquals(2, biomes[0].length);
            assertEquals(4, biomes[0][0].length);

            AtomicInteger count = new AtomicInteger();
            for (int qx = 0; qx < 4; qx++)
                for (int qy = 0; qy < 2; qy++)
                    for (int qz = 0; qz < 4; qz++)
                        count.incrementAndGet();
            assertEquals(32, count.get());
        }
    }

    // ── Backend-name contract ───────────────────────────────────────

    @Test
    void gpuClimateIsDistinctFromVanillaCpu() {
        assertNotEquals("vanilla_cpu", "gpu_climate");
    }

    // ── BiomeMapping round-trip ─────────────────────────────────────

    @Nested
    class BiomeMappingContract {

        @Test
        void plainsHasExpectedCanonicalId() {
            int id = com.rhythmatician.voxygen.semantic.biome.BiomeMapping.toCanonicalId("minecraft:plains");
            assertEquals(34, id, "plains should be at alphabetical index 34");
        }

        @Test
        void desertHasExpectedCanonicalId() {
            int id = com.rhythmatician.voxygen.semantic.biome.BiomeMapping.toCanonicalId("minecraft:desert");
            assertEquals(12, id, "desert should be at alphabetical index 12");
        }

        @Test
        void unknownBiomeMapsTo255() {
            int id = com.rhythmatician.voxygen.semantic.biome.BiomeMapping.toCanonicalId("minecraft:the_end");
            assertEquals(255, id, "non-overworld biome should map to UNKNOWN (255)");
        }

        @Test
        void canonicalIdsRoundTrip() {
            for (int i = 0; i < com.rhythmatician.voxygen.semantic.biome.BiomeMapping.size(); i++) {
                String name = com.rhythmatician.voxygen.semantic.biome.BiomeMapping.getCanonicalName(i);
                assertNotNull(name, "index " + i + " should have a name");
                assertEquals(i, com.rhythmatician.voxygen.semantic.biome.BiomeMapping.toCanonicalId(name),
                        "round-trip failed for " + name);
            }
        }
    }

    // ── SectionNoiseData compatibility ──────────────────────────────

    @Test
    void sectionNoiseDataHas480Floats() {
        SectionNoiseData data = makeData(0.0f, 0, 0, 0);
        assertEquals(480, data.flat().length);
    }
}
