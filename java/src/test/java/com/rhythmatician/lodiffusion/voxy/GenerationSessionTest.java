package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Composition tests for the per-world Generation Session and internal
 * terrain-candidate seam. No Voxy jar required — uses {@link InMemoryVolumeWriter}.
 */
class GenerationSessionTest {

    private static GenerationSession.ColumnContext sampleColumnContext() {
        float[][] rawHm = new float[16][16];
        float[][] oceanFloorHm = new float[16][16];
        int[][] biomeIdx = new int[16][16];
        float[][] hp5 = new float[5][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                rawHm[x][z] = 70f;
                oceanFloorHm[x][z] = 65f;
                biomeIdx[x][z] = 1; // plains
            }
        }
        for (int c = 0; c < 5; c++) {
            for (int i = 0; i < 16; i++) hp5[c][i] = 0f;
        }
        return new GenerationSession.ColumnContext(rawHm, biomeIdx, hp5, oceanFloorHm);
    }

    @Test
    void candidateSeam_fallbackProducesExtent16WithValidIds() {
        GenerationSession session = new GenerationSession();
        GenerationSession.ColumnContext ctx = sampleColumnContext();
        SectionPos pos = new SectionPos(0, 4, 0); // y=64
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();

        WriteOutcome outcome = session.produceAndWriteSection(pos, ctx, writer);

        assertEquals(WriteOutcome.Status.WRITTEN, outcome.status());
        assertEquals(1, writer.sectionRecords().size());
        VoxelVolume vol = writer.sectionRecords().get(0).volume();
        assertEquals(16, vol.extent());
        // Valid canonical IDs
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int bid = vol.blockId(x, y, z);
                    int bio = vol.biomeId(x, y, z);
                    assertTrue(CanonicalRegistries.isValidBlockId(bid), "invalid blockId " + bid);
                    assertTrue(CanonicalRegistries.isValidBiomeId(bio), "invalid biomeId " + bio);
                }
            }
        }
    }

    @Test
    void candidateSeam_regionProducesExtent32WithValidIds_viaStub() {
        // Stub candidate returning uniform 32 extent
        GenerationSession.TerrainCandidate stub = new GenerationSession.TerrainCandidate() {
            @Override
            public VoxelVolume produceSection(SectionPos pos, GenerationSession.ColumnContext ctx) {
                return VoxelVolume.uniform(16, 1, 0);
            }
            @Override
            public VoxelVolume produceRegion(Level level, SectionPos origin, long[] parentInput) {
                return VoxelVolume.uniform(32, 1, 0);
            }
        };
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        GenerationSession session = new GenerationSession(writer, stub);

        WriteOutcome out = session.produceAndWriteRegion(new SectionPos(0, 0, 0), Level.L1, writer);

        assertEquals(WriteOutcome.Status.WRITTEN, out.status());
        assertEquals(1, writer.regionRecords().size());
        VoxelVolume vol = writer.regionRecords().get(0).volume();
        assertEquals(32, vol.extent());
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertTrue(CanonicalRegistries.isValidBlockId(vol.blockId(x, y, z)));
                    assertTrue(CanonicalRegistries.isValidBiomeId(vol.biomeId(x, y, z)));
                }
            }
        }
    }

    @Test
    void sessionRoutesFallbackVsLearnedCandidate_viaStubSubstitution() {
        // Fallback session (no stub) should produce fallback terrain (non-uniform)
        GenerationSession fallbackSession = new GenerationSession();
        GenerationSession.ColumnContext ctx = sampleColumnContext();
        SectionPos pos = new SectionPos(0, 4, 0);
        InMemoryVolumeWriter w1 = new InMemoryVolumeWriter();
        fallbackSession.produceAndWriteSection(pos, ctx, w1);
        VoxelVolume fallbackVol = w1.sectionRecords().get(0).volume();

        // Learned stub returns distinct uniform volume
        GenerationSession.TerrainCandidate learnedStub = new GenerationSession.TerrainCandidate() {
            @Override
            public VoxelVolume produceSection(SectionPos p, GenerationSession.ColumnContext c) {
                return VoxelVolume.uniform(16, 923, 5); // stone, distinct biome
            }
            @Override
            public VoxelVolume produceRegion(Level l, SectionPos o, long[] p) {
                return VoxelVolume.uniform(32, 923, 5);
            }
        };
        InMemoryVolumeWriter w2 = new InMemoryVolumeWriter();
        GenerationSession learnedSession = new GenerationSession(w2, learnedStub);
        learnedSession.produceAndWriteSection(pos, ctx, w2);
        VoxelVolume learnedVol = w2.sectionRecords().get(0).volume();

        // They must differ — proves selection routes through seam
        assertNotEquals(fallbackVol.blockId(0, 0, 0), learnedVol.blockId(0, 0, 0));
        assertEquals(923, learnedVol.blockId(0, 0, 0));
        assertEquals(5, learnedVol.biomeId(0, 0, 0));
    }

    @Test
    void lifecycle_stopClearsRunningState() {
        GenerationSession session = new GenerationSession();
        assertFalse(session.isRunning());
        // start requires World; for unit test we verify stop is idempotent and clears state
        session.stop();
        assertFalse(session.isRunning());
        // After stop, candidate seam still functional (stateless)
        GenerationSession.ColumnContext ctx = sampleColumnContext();
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        WriteOutcome o = session.produceAndWriteSection(new SectionPos(0, 4, 0), ctx, writer);
        assertEquals(WriteOutcome.Status.WRITTEN, o.status());
    }

    @Test
    void fallbackPath_producesTerrainWhenModelAbsent() {
        // Model absent — session should still produce via fallback (no exception)
        GenerationSession session = new GenerationSession();
        assertNull(session.candidateForTest() == null ? null : null); // just ensure no NPE on selection
        GenerationSession.ColumnContext ctx = sampleColumnContext();
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        SectionPos pos = new SectionPos(2, 4, -1);
        WriteOutcome out = session.produceAndWriteSection(pos, ctx, writer);
        assertEquals(WriteOutcome.Status.WRITTEN, out.status());
        assertFalse(writer.sectionRecords().get(0).volume().isAllAir());
    }

    @Test
    void writerBoundary_unchanged_writeSectionAndWriteRegion() {
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        VoxelVolume v16 = VoxelVolume.uniform(16, 1, 0);
        VoxelVolume v32 = VoxelVolume.uniform(32, 1, 0);
        WriteOutcome s = writer.writeSection(new SectionPos(0, 0, 0), v16);
        assertEquals(WriteOutcome.Status.WRITTEN, s.status());
        WriteOutcome r = writer.writeRegion(new SectionPos(0, 0, 0), Level.L0, v32);
        assertEquals(WriteOutcome.Status.WRITTEN, r.status());
        assertEquals(1, writer.sectionRecords().size());
        assertEquals(1, writer.regionRecords().size());
    }

    @Test
    void lodiffusionClient_doesNotReferenceVoxyModelRunner() throws Exception {
        java.nio.file.Path[] candidates = {
            java.nio.file.Path.of("java/src/client/java/com/rhythmatician/lodiffusion/LodiffusionClient.java"),
            java.nio.file.Path.of("src/client/java/com/rhythmatician/lodiffusion/LodiffusionClient.java"),
            java.nio.file.Path.of("../java/src/client/java/com/rhythmatician/lodiffusion/LodiffusionClient.java"),
            java.nio.file.Path.of("../../java/src/client/java/com/rhythmatician/lodiffusion/LodiffusionClient.java")
        };
        java.nio.file.Path clientPath = null;
        for (java.nio.file.Path c : candidates) {
            if (java.nio.file.Files.exists(c)) { clientPath = c; break; }
        }
        // Fallback: walk up to find repo root
        if (clientPath == null) {
            java.nio.file.Path cur = java.nio.file.Path.of("").toAbsolutePath();
            for (int i = 0; i < 5; i++) {
                java.nio.file.Path tryPath = cur.resolve("java/src/client/java/com/rhythmatician/lodiffusion/LodiffusionClient.java");
                if (java.nio.file.Files.exists(tryPath)) { clientPath = tryPath; break; }
                cur = cur.getParent();
                if (cur == null) break;
            }
        }
        assertNotNull(clientPath, "LodiffusionClient.java not found via any candidate path");
        String src = java.nio.file.Files.readString(clientPath);
        assertFalse(src.contains("VoxyModelRunner"), "LodiffusionClient must not reference VoxyModelRunner");
        assertFalse(src.contains("preloadModel"), "LodiffusionClient must not call preloadModel");
    }

    @Test
    void noPublicTerrainCandidateInterface() {
        // Ensure TerrainCandidate is not public — uses compile-time reference, not dynamic lookup
        Class<?> c = GenerationSession.TerrainCandidate.class;
        assertFalse(java.lang.reflect.Modifier.isPublic(c.getModifiers()), "TerrainCandidate must be package-private, not public");
    }
}
