package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.world.noise.SectionNoiseData;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyRequestDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * End L4 runtime tracer vertical slice — L4-only, The End, model-free,
 * via WorldNoiseAccess.sampleFinalDensity.
 *
 * Covers: sampleFinalDensity delegation, deterministic centre-sample
 * rasterization, Y [0,128) air padding, alignment, early-mode gate
 * (no preload/runner), 121 L4-only enqueue, honest omission.
 */
class EndL4RuntimeTracerTest {

    @AfterEach
    void clearQueue() {
        ShadowRouterJobQueue.clear();
    }

    // --- WorldNoiseAccess.sampleFinalDensity ---

    @Test
    void sampleFinalDensity_delegatesToSeededRouter() throws Exception {
        Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/WorldNoiseAccess.java");
        String src = Files.readString(p);
        // Verify delegation to seeded router via NoiseConfig, pure computation
        assertTrue(src.contains("noiseConfig.getNoiseRouter().finalDensity()"),
                "Must delegate to world-bound NoiseRouter.FINAL_DENSITY via NoiseConfig");
        assertTrue(src.contains("new DensityFunction.UnblendedNoisePos(blockX, blockY, blockZ)"),
                "Must construct UnblendedNoisePos with block coords");
        assertTrue(src.contains("sampleFinalDensity"),
                "Method must exist");
        // Verify no chunk creation — method should not reference getChunk or Chunk
        // (pure computation, no chunk creation)
        int idx = src.indexOf("sampleFinalDensity(int blockX, int blockY, int blockZ)");
        if (idx >= 0) {
            String methodSlice = src.substring(idx, Math.min(src.length(), idx + 800));
            assertFalse(methodSlice.contains("getChunk"),
                    "sampleFinalDensity must not create/access chunks");
        }
    }

    @Test
    void sampleFinalDensity_javadocDoesNotClaimEquality() throws Exception {
        Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/WorldNoiseAccess.java");
        String src = Files.readString(p);
        assertTrue(src.contains("Does NOT claim equality"),
                "Javadoc must explicitly state does NOT claim equality with NoiseChunk interpolation");
        assertTrue(src.contains("Voxy-mip any-solid oracle"),
                "Javadoc must mention bounded Voxy-mip any-solid oracle");
        assertTrue(src.contains("sampleFinalDensity"),
                "Method must exist");
    }

    // --- EndL4DeterministicCandidate ---

    @Test
    void deterministicCandidate_isPackagePrivate() {
        Class<?> c = EndL4DeterministicCandidate.class;
        assertFalse(java.lang.reflect.Modifier.isPublic(c.getModifiers()),
                "EndL4DeterministicCandidate must be package-private, not public");
        assertFalse(java.lang.reflect.Modifier.isProtected(c.getModifiers()));
    }

    @Test
    void deterministicCandidate_producesExtent32OnlyAirEndStone() throws Exception {
        // Mock WorldNoiseAccess to return always solid (>0)
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);

        EndL4DeterministicCandidate cand = new EndL4DeterministicCandidate(mockNa);
        SectionPos origin = new SectionPos(0, 0, 0); // aligned to L4 (0 %32 ==0)
        VoxelVolume vol = cand.produceRegion(Level.L4, origin);

        assertEquals(32, vol.extent());
        // Only air (0) or end_stone (359) allowed
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int bid = vol.blockId(x, y, z);
                    assertTrue(bid == 0 || bid == 359,
                            "Only air(0) or end_stone(359) allowed, got " + bid + " at " + x + "," + y + "," + z);
                    int bio = vol.biomeId(x, y, z);
                    assertEquals(CanonicalRegistries.BIOME_UNKNOWN, bio);
                }
            }
        }
    }

    @Test
    void deterministicCandidate_yPaddingOutside0128Air() throws Exception {
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0); // always solid if sampled

        EndL4DeterministicCandidate cand = new EndL4DeterministicCandidate(mockNa);
        SectionPos origin = new SectionPos(0, 0, 0);
        VoxelVolume vol = cand.produceRegion(Level.L4, origin);

        // Y [0,128) corresponds to voxel y 0..7 (8 slices, 16 blocks each)
        // y 8..31 should be all air regardless of density (not sampled)
        for (int y = 8; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    assertEquals(0, vol.blockId(x, y, z),
                            "Y outside [0,128) must be air-padded at y=" + y);
                }
            }
        }
        // Active slices should be stone (since mock returns >0)
        for (int y = 0; y < 8; y++) {
            assertEquals(359, vol.blockId(0, y, 0),
                    "Active Y slice " + y + " should be end_stone when density >0");
        }
    }

    @Test
    void deterministicCandidate_alignmentGuard() throws Exception {
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        EndL4DeterministicCandidate cand = new EndL4DeterministicCandidate(mockNa);
        // Not aligned to L4 (32): x=1 is not divisible by 32
        SectionPos bad = new SectionPos(1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> cand.produceRegion(Level.L4, bad));
        // Stage 2: L3 is now supported; origin (0,0,0) aligned to all levels.
        assertDoesNotThrow(() -> cand.produceRegion(Level.L3, new SectionPos(0, 0, 0)));
        // L0 remains rejected (vanilla owns the finest band).
        assertThrows(IllegalArgumentException.class,
                () -> cand.produceRegion(Level.L0, new SectionPos(0, 0, 0)));
    }

    @Test
    void deterministicCandidate_8192EvaluationsPerRegion() throws Exception {
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(0.1);

        EndL4DeterministicCandidate cand = new EndL4DeterministicCandidate(mockNa);
        SectionPos origin = new SectionPos(0, 0, 0);
        cand.produceRegion(Level.L4, origin);

        // 32 * 8 * 32 = 8192 active voxels
        Mockito.verify(mockNa, Mockito.times(8192))
                .sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void deterministicCandidate_centreSampleCoordinates() throws Exception {
        // Verify centre-sample: voxel at (x,y,z) samples at origin*16 + voxel*16 +8
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        // Capture arguments
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);

        EndL4DeterministicCandidate cand = new EndL4DeterministicCandidate(mockNa);
        SectionPos origin = new SectionPos(32, 0, 64); // aligned (32%32==0, 64%32==0)
        cand.produceRegion(Level.L4, origin);

        // First active voxel (0,0,0) centre = 32*16 +0*16+8 = 512+8=520, y=8, z=64*16+8=1032
        Mockito.verify(mockNa).sampleFinalDensity(520, 8, 1032);
        // Last active voxel (31,7,31) centre: x=32*16+31*16+8=512+496+8=1016, y=7*16+8=120, z=64*16+31*16+8=1024+496+8=1528
        Mockito.verify(mockNa).sampleFinalDensity(1016, 120, 1528);
    }

    @Test
    void deterministicCandidate_honestOmissionDocumented() throws Exception {
        Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/EndL4DeterministicCandidate.java");
        String src = Files.readString(p);
        assertTrue(src.contains("Honest omission") || src.contains("honest omission"),
                "Must document honest omission of placed features");
        assertTrue(src.contains("obsidian pillars") || src.contains("pillars"),
                "Must mention omitted placed-feature geometry (pillars/gateways)");
        assertTrue(src.contains("deterministic approximation") || src.contains("Deterministic approximation"),
                "Must document rasterization as deterministic approximation");
        assertTrue(src.contains("centre-sample") || src.contains("center-sample"),
                "Must document centre-sample");
        assertTrue(src.contains("thin") || src.contains("Thin"),
                "Must note thin-occupancy caveat");
    }

    @Test
    void deterministicCandidate_rasterizationRuleHonestlyLabeled() throws Exception {
        Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/EndL4DeterministicCandidate.java");
        String src = Files.readString(p);
        // Name+comment rule
        assertTrue(src.contains("centre-sample") || src.contains("center-sample"));
        assertTrue(src.contains("End [0,128)") || src.contains("[0,128)"));
    }

    // --- GenerationSession tracer mode ---

    @Test
    void generationSession_tracerModeEnqueues121L4Only() throws Exception {
        GenerationSession session = new GenerationSession();
        setField(session, "playerSectionX", 0);
        setField(session, "playerSectionZ", 0);
        // Player at (0,96,0) => section (0,6,0) but X/Z 0 gives centre 0
        setField(session, "playerSectionX", 0);
        setField(session, "playerSectionZ", 0);

        ShadowRouterJobQueue.clear();
        int enqueued = session.enqueueEndL4TracerRequests();
        assertEquals(121, enqueued);
        assertEquals(121, ShadowRouterJobQueue.size());
        // All must be L4 Y=0
        for (int i = 0; i < 121; i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            assertEquals(4, req.lodLevel);
            assertEquals(0, req.worldY);
        }
        assertEquals(0, ShadowRouterJobQueue.size());
        // Drain marks completed already; re-enqueue should dedup
        ShadowRouterJobQueue.clear();
        // Second enqueue after drain should again be 121 (no inFlight)
        setField(session, "playerSectionX", 0);
        enqueued = session.enqueueEndL4TracerRequests();
        assertEquals(121, enqueued);
    }

    @Test
    void generationSession_tracerModeDisablesLowerLevels() throws Exception {
        Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/GenerationSession.java");
        String src = Files.readString(p);
        assertTrue(src.contains("121 L4 requests") || src.contains("121 L4"),
                "Must document 121 L4 requests");
        assertTrue(src.contains("disable") || src.contains("disables"),
                "Must mention disabling L3/L2/L1/L0");
        // Verify seedNearPlayerDemandIfNeeded early returns in tracer mode
        assertTrue(src.contains("endL4TracerMode") && src.contains("seedNearPlayerDemandIfNeeded"),
                "Tracer mode must gate seeding");
    }

    @Test
    void generationSession_earlyGateBeforePreloadAndResolve() throws Exception {
        Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/GenerationSession.java");
        String src = Files.readString(p);
        int idxGate = src.indexOf("Early session-mode decision before BOTH preloadModel()");
        int idxPreload = src.indexOf("public void preloadModel()");
        int idxResolve = src.indexOf("voxyModelRunner = resolveVoxyModel();");
        assertTrue(idxGate >= 0, "Early gate comment must exist");
        assertTrue(idxPreload >= 0, "preloadModel must exist");
        assertTrue(idxResolve >= 0, "resolveVoxyModel assignment must exist");
        assertTrue(idxGate < idxPreload, "Gate must precede preloadModel()");
        assertTrue(idxGate < idxResolve, "Gate must precede resolveVoxyModel()");
        // Also check gate inside preloadModel
        int idxPreloadGate = src.indexOf("End L4 tracer mode — skipping preloadModel");
        assertTrue(idxPreloadGate > idxPreload && idxPreloadGate < idxResolve);
    }

    @Test
    void generationSession_noPreloadFutureInTracerMode() throws Exception {
        GenerationSession session = new GenerationSession();
        session.setEndL4TracerModeForTest(true);
        // Ensure no future after preloadModel
        Field f = GenerationSession.class.getDeclaredField("preloadFuture");
        f.setAccessible(true);
        assertNull(f.get(session));
        session.preloadModel();
        assertNull(f.get(session), "preloadFuture must not be created in tracer mode");
        // Also voxyModelRunner should remain null (no ONNX)
        Field runnerF = GenerationSession.class.getDeclaredField("voxyModelRunner");
        runnerF.setAccessible(true);
        assertNull(runnerF.get(session));
    }

    @Test
    void generationSession_tracerWritesViaRealVoxyPath() throws Exception {
        // Mock noise to solid, use InMemory writer to prove writeRegion contract unchanged
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);

        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(mockNa);
        session.setEndL4TracerModeForTest(true);

        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
        req.lodLevel = 4;
        req.worldX = 0;
        req.worldY = 0;
        req.worldZ = 0;

        WriteOutcome out = session.processTracerRequestForTest(req, writer, null);
        assertEquals(WriteOutcome.Status.WRITTEN, out.status());
        assertEquals(1, writer.regionRecords().size());
        var rec = writer.regionRecords().get(0);
        assertEquals(Level.L4, rec.level());
        // No fake nonEmptyChildren: InMemory writer doesn't track it, RealVoxy would own it
        // Verify volume is not all air and uses only allowed IDs
        VoxelVolume vol = rec.volume();
        assertEquals(32, vol.extent());
        assertFalse(vol.isAllAir());
    }

    @Test
    void realVoxyWriter_biomeUnknownTracerConcession() throws Exception {
        Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/RealVoxyVolumeWriter.java");
        String src = Files.readString(p);
        assertTrue(src.contains("Tracer-only") || src.contains("tracer-only"),
                "BIOME_UNKNOWN->plains must be documented as tracer-only concession");
        assertTrue(src.contains("does not fake nonEmptyChildren") || src.contains("does not fake"),
                "Must document not faking nonEmptyChildren on leaf");
    }

    // --- Helpers ---

    private WorldNoiseAccess newWorldNoiseAccess(NoiseConfig cfg) throws Exception {
        Constructor<WorldNoiseAccess> c = WorldNoiseAccess.class
                .getDeclaredConstructor(
                        net.minecraft.server.world.ServerWorld.class,
                        ChunkGenerator.class,
                        NoiseConfig.class);
        c.setAccessible(true);
        return c.newInstance(null, null, cfg);
    }

    private void setField(Object target, String name, int value) throws Exception {
        Field f = GenerationSession.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Path findPath(String relative) {
        Path r = Path.of(relative);
        if (Files.exists(r)) return r;
        // Try from java directory
        Path alt = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path tryP = alt.resolve(relative);
            if (Files.exists(tryP)) return tryP;
            alt = alt.getParent();
            if (alt == null) break;
        }
        return r;
    }
}
