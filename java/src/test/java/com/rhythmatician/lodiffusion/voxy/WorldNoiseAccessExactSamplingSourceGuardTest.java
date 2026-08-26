package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.chunk.ProtoChunk;
import org.junit.jupiter.api.Test;

/** Compile/access smoke test plus an explicitly source-level no-manager guard. */
class WorldNoiseAccessExactSamplingSourceGuardTest {
    @Test
    void pinnedProtoChunkPathKeepsExactSamplingIsolated() throws Exception {
        assertEquals(ProtoChunk.class.getName(),
                "net.minecraft.world.chunk.ProtoChunk");

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

    @Test
    void afkTourRunsAKnownMainIslandControlSample() throws Exception {
        String source = Files.readString(findSource("GenerationSession.java"));

        assertTrue(source.contains("Boolean.getBoolean(\"lodiffusion.flightTour.autoStart\")"));
        assertTrue(source.contains("probeExactEndL1(new SectionPos(0, 4, 0))"));
        assertTrue(source.contains("[LodGen][ExactL1Control]"));
    }

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
