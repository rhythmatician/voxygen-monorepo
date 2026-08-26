package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

/** Behavioral exact-L1 checks plus guards for unobservable world-bound details. */
class WorldNoiseAccessExactSamplingSourceGuardTest {
    @Test
    void endUsesTheTopDownRouteInsteadOfCompatibilityPublication() {
        assertTrue(TerrainPublicationRoute.forDimensionId(
                Identifier.of("minecraft", "the_end")).usesTopDownEndRoute());
        assertFalse(TerrainPublicationRoute.forDimensionId(
                Identifier.of("minecraft", "overworld")).usesTopDownEndRoute());
        assertTrue(TerrainPublicationRoute.forDimensionId(
                Identifier.of("minecraft", "overworld")).allowsCompatibilityTerrainPublication());
    }

    @Test
    void exactL1MapsAllSixteenChunkColumnsIntoTheRequestedRegion() {
        List<ChunkColumn> requested = new ArrayList<>();
        ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                (chunkX, chunkZ, minY, maxY, consumer) -> {
                    requested.add(new ChunkColumn(chunkX, chunkZ, minY, maxY));
                    consumer.accept((chunkX << 4) + 15, minY + 1, (chunkZ << 4) + 15, true);
                    consumer.accept(chunkX << 4, maxY, chunkZ << 4, true);
                });

        VoxelVolume volume = candidate.produceExactL1(new SectionPos(-4, 0, 8));

        assertEquals(16, requested.size());
        assertEquals(16, new HashSet<>(requested).size());
        assertEquals(Set.of(-4, -3, -2, -1), values(requested, true));
        assertEquals(Set.of(8, 9, 10, 11), values(requested, false));
        assertTrue(requested.stream().allMatch(column -> column.minY == 0 && column.maxY == 64));
        assertEquals(16, volume.countNonAir());
    }

    /**
     * WorldNoiseAccess owns concrete server dependencies and exposes no construction
     * seam for a real ServerWorld, NoiseChunkGenerator, and NoiseConfig test fixture.
     * Keep this narrow static guard until that real-world fixture exists.
     */
    @Test
    void pinnedProtoChunkPathKeepsExactSamplingIsolated() throws Exception {

        String source = Files.readString(findSource("WorldNoiseAccess.java"));
        int exactStart = source.indexOf("void sampleExactEndBaseTerrainChunk(");
        String exactMethod = source.substring(exactStart,
                source.indexOf("public ExactEndL1Probe probeExactEndL1", exactStart));

        assertTrue(exactMethod.contains("new ProtoChunk("));
        assertTrue(exactMethod.contains("populateNoise("));
        assertTrue(exactMethod.contains("Blender.getNoBlending()"));
        assertTrue(exactMethod.contains("new NoStructuresAccessor"));
        assertFalse(exactMethod.contains("new ChunkNoiseSampler("));
        assertFalse(exactMethod.contains("sampleBlockState()"));
        assertFalse(exactMethod.contains("getChunkManager("));
        assertFalse(exactMethod.contains("getChunk("));

        String noStructures = source.substring(source.indexOf("private static final class NoStructuresAccessor"));
        assertTrue(noStructures.contains("getStructureStarts("));
        assertTrue(noStructures.contains("return List.of()"));
        assertFalse(exactMethod.contains("getStructureAccessor("));
    }

    @Test
    void realWorldProbeRecordsNonemptyAndUnloadedTargetEvidence() throws Exception {
        String source = Files.readString(findSource("WorldNoiseAccess.java"));

        assertTrue(source.contains("public ExactEndL1Probe probeExactEndL1(SectionPos origin)"));
        assertTrue(source.contains("new ExactEndL1Candidate(this).produceExactL1(origin)"));
        assertTrue(source.contains("serverWorld.isChunkLoaded(chunkX, chunkZ)"));
        assertTrue(source.contains("nonAirVoxels > 0"));
    }

    @Test
    void onlyL2ToL1UsesTheExactProducer() throws Exception {
        String source = Files.readString(findSource("GenerationSession.java"));

        assertTrue(source.contains("childLevel == Level.L1"));
        assertTrue(source.contains("exactL1.produceExactL1(childOrigin)"));
        assertTrue(source.contains("candidate.produceRegion(childLevel, childOrigin)"));
    }

    private static Set<Integer> values(List<ChunkColumn> requested, boolean x) {
        Set<Integer> values = new HashSet<>();
        for (ChunkColumn column : requested) {
            values.add(x ? column.chunkX : column.chunkZ);
        }
        return values;
    }

    private record ChunkColumn(int chunkX, int chunkZ, int minY, int maxY) {}

    private static Path findSource(String fileName) {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && current != null; i++, current = current.getParent()) {
            Path candidate = current.resolve(
                    "src/main/java/com/rhythmatician/lodiffusion/voxy/" + fileName);
            if (Files.exists(candidate)) return candidate;
            candidate = current.resolve(
                    "java/src/main/java/com/rhythmatician/lodiffusion/voxy/" + fileName);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("source not found: " + fileName);
    }
}
