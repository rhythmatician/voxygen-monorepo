package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the production {@link RealVoxyVolumeWriter}.
 *
 * <p>Exercises the pure, Voxy-independent surface of the real adapter: constructor
 * contract validation, the YZX index math (the transpose bug the seam was built to
 * localize), and the canonical -&gt; Voxy fallback block map.  The reflection-backed
 * write path itself is guarded by {@code VoxyCompat.isAvailable()}, so without the
 * Voxy jar on the classpath {@code writeSection}/{@code writeRegion} must fail fast
 * with {@link VolumeUnavailableException} rather than NPE or silently no-op.
 */
class RealVoxyVolumeWriterTest {

    private static final int[] BIOME_MAP = new int[CanonicalRegistries.BIOME_COUNT];
    private static final int[] BLOCK_MAP = new int[CanonicalRegistries.BLOCK_COUNT];

    private static RealVoxyVolumeWriter writer() {
        return new RealVoxyVolumeWriter(new Object(), new Object(), BIOME_MAP, BLOCK_MAP);
    }

    // ------------------------------------------------------------------
    // Constructor contract
    // ------------------------------------------------------------------

    @Test
    void constructor_rejectsNullArgs() {
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(null, new Object(), BIOME_MAP, BLOCK_MAP));
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(new Object(), null, BIOME_MAP, BLOCK_MAP));
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(), null, BLOCK_MAP));
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(), BIOME_MAP, null));
    }

    @Test
    void constructor_rejectsWrongArraySizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        new int[CanonicalRegistries.BIOME_COUNT - 1], BLOCK_MAP));
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        new int[CanonicalRegistries.BIOME_COUNT + 1], BLOCK_MAP));
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        BIOME_MAP, new int[CanonicalRegistries.BLOCK_COUNT - 1]));
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        BIOME_MAP, new int[CanonicalRegistries.BLOCK_COUNT + 1]));
    }

    @Test
    void constructor_acceptsExactSizes() {
        // Should not throw
        writer();
    }

    // ------------------------------------------------------------------
    // YZX index math — the transpose bug the seam localizes
    // ------------------------------------------------------------------

    @Test
    void yzxIndex_isYMajorThenZThenX() {
        // index = (y<<10)|(z<<5)|x for a 32^3 WorldSection
        assertEquals(0, RealVoxyVolumeWriter.yzxIndex(0, 0, 0));
        assertEquals(1, RealVoxyVolumeWriter.yzxIndex(1, 0, 0));
        assertEquals(1 << 5, RealVoxyVolumeWriter.yzxIndex(0, 0, 1));
        assertEquals(1 << 10, RealVoxyVolumeWriter.yzxIndex(0, 1, 0));
        // Asymmetric sentinel: swapping axes must change the index
        assertTrue(RealVoxyVolumeWriter.yzxIndex(1, 2, 3) != RealVoxyVolumeWriter.yzxIndex(3, 2, 1));
        assertTrue(RealVoxyVolumeWriter.yzxIndex(1, 2, 3) != RealVoxyVolumeWriter.yzxIndex(2, 1, 3));
    }

    @Test
    void yzxIndex_coversFull32Cube() {
        // Max index for (31,31,31) must be 32^3 - 1
        assertEquals(32 * 32 * 32 - 1, RealVoxyVolumeWriter.yzxIndex(31, 31, 31));
        // Every index in [0, 32^3) is reachable exactly once (bijective)
        boolean[] seen = new boolean[32 * 32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int idx = RealVoxyVolumeWriter.yzxIndex(x, y, z);
                    assertTrue(idx >= 0 && idx < seen.length, "index out of range: " + idx);
                    assertTrue(!seen[idx], "duplicate index " + idx);
                    seen[idx] = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Fallback block map — canonical IDs must match FallbackPalette
    // ------------------------------------------------------------------

    @Test
    void buildFallbackBlockMap_mapsCanonicalIdsFromFallbackPalette() {
        HeightmapFallbackGenerator.FallbackBlockIds voxyIds =
                new HeightmapFallbackGenerator.FallbackBlockIds(
                        0, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111);
        int[] map = RealVoxyVolumeWriter.buildFallbackBlockMap(voxyIds);

        VoxelPredictionDecoder.FallbackPalette palette = VoxelPredictionDecoder.FallbackPalette.defaults();
        assertEquals(voxyIds.air(), map[palette.air()]);
        assertEquals(voxyIds.stone(), map[palette.stone()]);
        assertEquals(voxyIds.deepslate(), map[palette.deepslate()]);
        assertEquals(voxyIds.dirt(), map[palette.dirt()]);
        assertEquals(voxyIds.grassBlock(), map[palette.grassBlock()]);
        assertEquals(voxyIds.sand(), map[palette.sand()]);
        assertEquals(voxyIds.water(), map[palette.water()]);
        assertEquals(voxyIds.redSand(), map[palette.redSand()]);
        assertEquals(voxyIds.gravel(), map[palette.gravel()]);
        assertEquals(voxyIds.snowLayer(), map[palette.snowLayer()]);
        assertEquals(voxyIds.podzol(), map[palette.podzol()]);
        assertEquals(voxyIds.mycelium(), map[palette.mycelium()]);
    }

    @Test
    void buildFallbackBlockMap_unmappedEntriesStayAir() {
        HeightmapFallbackGenerator.FallbackBlockIds voxyIds =
                new HeightmapFallbackGenerator.FallbackBlockIds(
                        0, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111);
        int[] map = RealVoxyVolumeWriter.buildFallbackBlockMap(voxyIds);
        assertEquals(CanonicalRegistries.BLOCK_COUNT, map.length);
        // A canonical ID not in the fallback palette must map to 0 (air)
        assertEquals(0, map[1]);
        assertEquals(0, map[500]);
        assertEquals(0, map[CanonicalRegistries.BLOCK_ID_MAX]);
    }

    // ------------------------------------------------------------------
    // Unavailable backend — fail fast, never NPE
    // ------------------------------------------------------------------

    @Test
    void writeSection_whenVoxyUnavailable_throwsVolumeUnavailable() {
        RealVoxyVolumeWriter w = writer();
        VoxelVolume v = VoxelVolume.uniform(16, 1, 0);
        assertThrows(VolumeUnavailableException.class,
                () -> w.writeSection(new SectionPos(0, 0, 0), v));
    }

    @Test
    void writeRegion_whenVoxyUnavailable_throwsVolumeUnavailable() {
        RealVoxyVolumeWriter w = writer();
        VoxelVolume v = VoxelVolume.uniform(32, 1, 0);
        assertThrows(VolumeUnavailableException.class,
                () -> w.writeRegion(new SectionPos(0, 0, 0), Level.L0, v));
    }

    @Test
    void writeSection_rejectsWrongExtent_beforeAvailabilityCheck() {
        RealVoxyVolumeWriter w = writer();
        // Extent validation happens before the availability guard
        assertThrows(IllegalArgumentException.class,
                () -> w.writeSection(new SectionPos(0, 0, 0), VoxelVolume.uniform(32, 1, 0)));
    }

    @Test
    void writeRegion_rejectsMisaligned_beforeAvailabilityCheck() {
        RealVoxyVolumeWriter w = writer();
        assertThrows(IllegalArgumentException.class,
                () -> w.writeRegion(new SectionPos(1, 0, 0), Level.L1, VoxelVolume.uniform(32, 1, 0)));
    }
}
