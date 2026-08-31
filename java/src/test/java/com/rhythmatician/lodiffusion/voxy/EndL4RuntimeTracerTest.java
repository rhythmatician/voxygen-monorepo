package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.rhythmatician.voxygen.generation.refinement.DefaultEndRefinement;
import com.rhythmatician.voxygen.generation.dimension.end.EndL4DeterministicCandidate;
import com.rhythmatician.voxygen.generation.refinement.EndRefinement;
import com.rhythmatician.voxygen.worldgen.WorldNoiseAccess;
import com.rhythmatician.voxygen.generation.session.GenerationSession;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.output.InMemoryVolumeWriter;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult;

/** Runtime behavior retained by the extracted End-refinement seam. */
class EndL4RuntimeTracerTest {
    @Test
    void sampleFinalDensityDelegatesToTheSeededFinalDensityFunction() throws Exception {
        int[] sampled = new int[3];
        WorldNoiseAccess access = new WorldNoiseAccess((blockX, blockY, blockZ) -> {
            sampled[0] = blockX;
            sampled[1] = blockY;
            sampled[2] = blockZ;
            return 0.375;
        });

        assertEquals(0.375, access.sampleFinalDensity(12, 34, -56));
        assertEquals(12, sampled[0]);
        assertEquals(34, sampled[1]);
        assertEquals(-56, sampled[2]);
    }

    @Test
    void deterministicCandidateUsesOnlyEndTerrainVocabularyAndUnknownBiome() {
        EndL4DeterministicCandidate candidate = candidateReturning(1.0);
        VoxelVolume volume = candidate.produceRegion(Level.L4, new SectionPos(0, 0, 0));

        assertEquals(32, volume.extent());
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertTrue(volume.blockId(x, y, z) == CanonicalRegistries.BLOCK_AIR
                            || volume.blockId(x, y, z)
                            == EndL4DeterministicCandidate.BLOCK_END_STONE);
                    assertEquals(CanonicalRegistries.BIOME_UNKNOWN, volume.biomeId(x, y, z));
                }
            }
        }
    }

    @Test
    void deterministicCandidatePadsOutsideEndHeightWithAir() {
        VoxelVolume volume = candidateReturning(1.0)
                .produceRegion(Level.L4, new SectionPos(0, 0, 0));

        for (int y = 0; y < 8; y++) {
            assertEquals(EndL4DeterministicCandidate.BLOCK_END_STONE,
                    volume.blockId(0, y, 0));
        }
        for (int y = 8; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertEquals(CanonicalRegistries.BLOCK_AIR, volume.blockId(x, y, z));
                }
            }
        }
    }

    @Test
    void deterministicCandidateEnforcesAlignmentAcrossSupportedLevels() {
        EndL4DeterministicCandidate candidate = candidateReturning(1.0);

        assertThrows(IllegalArgumentException.class,
                () -> candidate.produceRegion(Level.L4, new SectionPos(1, 0, 0)));
        assertDoesNotThrow(() -> candidate.produceRegion(Level.L4, new SectionPos(0, 0, 0)));
        assertDoesNotThrow(() -> candidate.produceRegion(Level.L3, new SectionPos(0, 0, 0)));
        assertDoesNotThrow(() -> candidate.produceRegion(Level.L0, new SectionPos(0, 0, 0)));
    }

    @Test
    void l4CandidateSamplesExactly8192ActiveVoxelsAtVoxelCenters() {
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(access.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        EndL4DeterministicCandidate candidate = new EndL4DeterministicCandidate(access);

        candidate.produceRegion(Level.L4, new SectionPos(32, 0, 64));

        Mockito.verify(access, Mockito.times(8192)).sampleFinalDensity(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());
        Mockito.verify(access).sampleFinalDensity(520, 8, 1032);
        Mockito.verify(access).sampleFinalDensity(1016, 120, 1528);
    }

    @Test
    void endRouteDoesNotStartOnnxPreload() throws Exception {
        GenerationSession session = new GenerationSession();
        session.setEndL4TracerModeForTest(true);

        session.preloadModel();

        assertNull(field(session, "preloadFuture").get(session));
        assertNull(field(session, "voxyModelRunner").get(session));
    }

    @Test
    void extractedHorizonWritesCandidateVolumeThroughWriterSeam() {
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(access.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        EndL4DeterministicCandidate candidate = new EndL4DeterministicCandidate(access);
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        SectionPos origin = new SectionPos(0, 0, 0);
        DefaultEndRefinement module = new DefaultEndRefinement(
                new DefaultEndRefinement.Config(1000, 64, 0, 1, 8192, 0),
                intent -> ParentRefinementResult.parentMissing(),
                (level, childOrigin) -> candidate.produceRegion(level, childOrigin),
                l4Origin -> writer.writeRegion(
                        l4Origin, Level.L4, candidate.produceRegion(Level.L4, l4Origin)));

        EndRefinement.StepResult result = module.advance(new EndRefinement.Frame(
                1, new SectionPos(0, 6, 0), List.of(origin), false));

        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, result.status());
        assertTrue(result.terrainChanged());
        assertEquals(1, writer.regionRecords().size());
        assertEquals(Level.L4, writer.regionRecords().getFirst().level());
        VoxelVolume written = writer.regionRecords().getFirst().volume();
        assertFalse(written.isAllAir());
        assertEquals(CanonicalRegistries.BIOME_UNKNOWN, written.biomeId(0, 0, 0));
    }

    private static EndL4DeterministicCandidate candidateReturning(double density) {
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(access.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(density);
        return new EndL4DeterministicCandidate(access);
    }

    @SuppressWarnings("unused")
    private static WorldNoiseAccess newWorldNoiseAccess(
            ChunkGenerator generator, NoiseConfig config) throws Exception {
        Constructor<WorldNoiseAccess> constructor = WorldNoiseAccess.class.getDeclaredConstructor(
                net.minecraft.server.world.ServerWorld.class, ChunkGenerator.class, NoiseConfig.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, generator, config);
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
