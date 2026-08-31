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
import com.rhythmatician.voxygen.generation.dimension.DimensionSynthesizers;
import com.rhythmatician.voxygen.generation.dimension.end.EndDimensionSynthesizer;
import com.rhythmatician.voxygen.generation.dimension.end.ExactEndL1Candidate;
import com.rhythmatician.voxygen.generation.TerrainPublicationRoute;
import com.rhythmatician.voxygen.worldgen.WorldNoiseAccess;
import com.rhythmatician.voxygen.generation.session.GenerationSession;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;

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
     *
     * <p>Pins the sampler-direct walk per ADR 0013: one ChunkNoiseSampler(4) per
     * chunk, no ProtoChunk materialization, band-restricted emission, and the
     * doFill interpolation call order preserved.
     */
    @Test
    void pinnedSamplerWalkPathKeepsExactSamplingIsolated() throws Exception {

        String source = Files.readString(findSource("WorldNoiseAccess.java"));
        int exactStart = source.indexOf("void sampleExactEndBaseTerrainChunk(");
        String exactMethod = source.substring(exactStart,
                source.indexOf("public ExactEndL1Probe probeExactEndL1", exactStart));

        // Sampler-direct: exactly one sampler via createNoiseSampler, no chunk objects.
        assertTrue(exactMethod.contains("createNoiseSampler("));
        assertTrue(exactMethod.contains("new ChunkNoiseSampler(") == false,
                "must build the sampler through createNoiseSampler, not inline");
        assertFalse(exactMethod.contains("new ProtoChunk("));
        assertFalse(exactMethod.contains("populateNoise("));
        assertFalse(exactMethod.contains("NoStructuresAccessor"));

        // Band restriction: clamp against vanilla domain, skip whole cells.
        assertTrue(exactMethod.contains("bandMin"), "must compute a clamped band minimum");
        assertTrue(exactMethod.contains("bandMax"), "must compute a clamped band maximum");
        assertTrue(exactMethod.contains("Math.max("), "band min must clamp against shape.minimumY()");
        assertTrue(exactMethod.contains("Math.min("), "band max must clamp against shape top");
        assertTrue(exactMethod.contains("cellIntersectsBand"),
                "cells fully outside the band must be skipped wholesale");

        // Interpolation state machine order preserved (mirrors doFill).
        assertTrue(exactMethod.contains("sampleStartDensity()"));
        assertTrue(exactMethod.contains("sampleEndDensity("));
        assertTrue(exactMethod.contains("onSampledCellCorners("));
        assertTrue(exactMethod.contains("interpolateY("));
        assertTrue(exactMethod.contains("interpolateX("));
        assertTrue(exactMethod.contains("interpolateZ("));
        assertTrue(exactMethod.contains("sampleBlockState()"));
        assertTrue(exactMethod.contains("stopInterpolation()"));

        // Isolation: no world access beyond construction inputs.
        assertFalse(exactMethod.contains("getChunkManager("));
        assertFalse(exactMethod.contains("getChunk("));
    }

    @Test
    void realWorldProbeRecordsNonemptyAndUnloadedTargetEvidence() throws Exception {
        String source = Files.readString(findSource("WorldNoiseAccess.java"));

        assertTrue(source.contains("public ExactEndL1Probe probeExactEndL1(SectionPos origin)"));
        assertTrue(source.contains("new ExactEndL1Candidate(this).produceExactL1(origin)"));
        assertTrue(source.contains("serverWorld.isChunkLoaded(chunkX, chunkZ)"));
        assertTrue(source.contains("nonAirVoxels > 0"));
    }

    /**
     * The tracer hot path samples final density up to 262k times per parent
     * refinement. Guard that the sampling lambda reuses one mutable position
     * object instead of allocating an UnblendedNoisePos per call — and that it
     * still assigns all three coordinates (no stale-state bug). Behavioral
     * verification needs a bootstrapped MC registry, which the unit-test
     * environment cannot provide; keep this narrow static guard until a real
     * NoiseConfig fixture exists.
     */
    @Test
    void pinnedFinalDensitySamplingReusesOneMutablePosition() throws Exception {
        String source = Files.readString(findSource("WorldNoiseAccess.java"));
        int ctorStart = source.indexOf("private WorldNoiseAccess(ServerWorld serverWorld");
        assertTrue(ctorStart >= 0, "world-bound constructor not found");
        int ctorEnd = source.indexOf("WorldNoiseAccess(DensitySample finalDensity)", ctorStart);
        String ctor = source.substring(ctorStart, ctorEnd);

        assertFalse(ctor.contains("new DensityFunction.UnblendedNoisePos(blockX, blockY, blockZ)"),
                "finalDensity lambda must not allocate a new UnblendedNoisePos per sample");
        assertTrue(ctor.contains("MutableNoisePos"),
                "finalDensity lambda must reuse a mutable NoisePos implementation");
        for (String coord : new String[] {"pos.x = blockX", "pos.y = blockY", "pos.z = blockZ"}) {
            assertTrue(ctor.contains(coord),
                    "position field must be assigned before each sample: " + coord);
        }
    }

    @Test
    void onlyL2ToL1UsesTheExactProducer() throws Exception {
        // Guard moved to EndDimensionSynthesizer per ADR 0014 dimension-partitioned seam
        String source = Files.readString(findSource("EndDimensionSynthesizer.java"));
        assertTrue(source.contains("level == Level.L1"));
        assertTrue(source.contains("produceExactL1"));
        assertTrue(source.contains("produceRegion"));
        // GenerationSession must delegate via DimensionSynthesizers, not branch on Level directly
        String sessionSource = Files.readString(findSource("GenerationSession.java"));
        assertTrue(sessionSource.contains("DimensionSynthesizers.forDimension"));
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
            // Voxygen migration: new canonical locations
            String[] voxygenPaths = {
                    "src/main/java/com/rhythmatician/voxygen/worldgen/" + fileName,
                    "java/src/main/java/com/rhythmatician/voxygen/worldgen/" + fileName,
                    "src/main/java/com/rhythmatician/voxygen/generation/dimension/end/" + fileName,
                    "java/src/main/java/com/rhythmatician/voxygen/generation/dimension/end/" + fileName,
                    "src/main/java/com/rhythmatician/voxygen/generation/session/" + fileName,
                    "java/src/main/java/com/rhythmatician/voxygen/generation/session/" + fileName
            };
            for (String p : voxygenPaths) {
                Path c = current.resolve(p);
                if (Files.exists(c)) return c;
            }
        }
        throw new IllegalStateException("source not found: " + fileName);
    }
}
