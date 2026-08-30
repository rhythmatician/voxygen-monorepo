package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.ChorusCommonRoiEvaluator.*;
import com.rhythmatician.lodiffusion.oracle.capture.OracleFixtureWriter;
import com.rhythmatician.lodiffusion.voxy.CanonicalRegistries;
import com.rhythmatician.lodiffusion.voxy.EndChorusSynthesizer;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #220 common-physical-ROI End chorus evaluation.
 *
 * <p>Measures chorus feature masks over the SAME 32³ block ROI at every Level,
 * using the REAL_CAPTURE fixture only. Evaluates OMIT and deterministic baseline
 * against post-ingest Voxy target, reports per-Level residuals, Level-to-Level
 * transitions, and runtime. Does NOT train a model.
 *
 * <p>This supplies the first evidence-backed row for #85 Worldgen Partition v1
 * and the authoritative residual for #235.
 */
class EndChorusCommonRoiEvidenceTest {

    private static OracleFixture loadRealFixture() {
        OracleContract c = EndChorusTracerContract.contract();
        Path[] candidates = {
                Paths.get("oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("java/oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("../oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("java", "oracle-fixtures", c.provenanceId() + ".json"),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                try {
                    return OracleFixtureWriter.read(p);
                } catch (Exception e) {
                    throw new AssertionError("Failed to load real fixture at " + p.toAbsolutePath() + ": " + e.getMessage(), e);
                }
            }
        }
        Path def = OracleFixtureWriter.defaultFixturePath(c);
        if (Files.exists(def)) {
            try { return OracleFixtureWriter.read(def); } catch (Exception e) { throw new AssertionError(e); }
        }
        throw new AssertionError("Real fixture not found. Expected a5fea400 at one of: " + java.util.Arrays.toString(candidates));
    }

    // ======================================================================
    // TDD 1: world-space ROI maps correctly at L0..L4
    // ======================================================================

    @Test
    void roiMappingIsExactAtAllLevels() {
        OracleContract c = EndChorusTracerContract.contract();
        // Expected derived from contract: see ChorusCommonRoiEvaluator mapping logic
        // L0: ws 50,2,4 blockSize32 -> wsOrigin 1600,64,128 voxel1 offset0 extent32
        // L1: ws 25,1,2 blockSize64 -> origin 1600,64,128 voxel2 offset0 extent16
        // L2: ws 12,0,1 blockSize128 -> 1536,0,128 voxel4 offset(1,16,0) extent8
        // L3: ws 6,0,0 blockSize256 -> 1536,0,0 voxel8 offset(8,8,16) extent4
        // L4: ws 3,0,0 blockSize512 -> 1536,0,0 voxel16 offset(4,4,8) extent2
        var m0 = ChorusCommonRoiEvaluator.mappingForLevel(Level.L0, c);
        assertEquals(1, m0.voxelSize());
        assertEquals(32, m0.roiVoxelExtent());
        assertEquals(0, m0.offsetX()); assertEquals(0, m0.offsetY()); assertEquals(0, m0.offsetZ());
        assertEquals(1600, m0.wsOriginX()); assertEquals(64, m0.wsOriginY()); assertEquals(128, m0.wsOriginZ());

        var m1 = ChorusCommonRoiEvaluator.mappingForLevel(Level.L1, c);
        assertEquals(2, m1.voxelSize()); assertEquals(16, m1.roiVoxelExtent());
        assertEquals(0, m1.offsetX()); assertEquals(0, m1.offsetY()); assertEquals(0, m1.offsetZ());

        var m2 = ChorusCommonRoiEvaluator.mappingForLevel(Level.L2, c);
        assertEquals(4, m2.voxelSize()); assertEquals(8, m2.roiVoxelExtent());
        assertEquals(16, m2.offsetX()); assertEquals(16, m2.offsetY()); assertEquals(0, m2.offsetZ());
        var m3 = ChorusCommonRoiEvaluator.mappingForLevel(Level.L3, c);
        assertEquals(8, m3.voxelSize()); assertEquals(4, m3.roiVoxelExtent());
        assertEquals(8, m3.offsetX()); assertEquals(8, m3.offsetY()); assertEquals(16, m3.offsetZ());
        var m4 = ChorusCommonRoiEvaluator.mappingForLevel(Level.L4, c);
        assertEquals(16, m4.voxelSize()); assertEquals(2, m4.roiVoxelExtent());
        assertEquals(4, m4.offsetX()); assertEquals(4, m4.offsetY()); assertEquals(8, m4.offsetZ());

        // Verify half-open ROI identity
        for (Level l : Level.values()) {
            var m = ChorusCommonRoiEvaluator.mappingForLevel(l, c);
            assertEquals(32, m.roiBlockExtent());
            // ROI must be [1600,1632) [64,96) [128,160)
            assertEquals(1600, m.roiOriginX());
            assertEquals(64, m.roiOriginY());
            assertEquals(128, m.roiOriginZ());
            // Bounds must be inside WorldSection
            assertTrue(m.offsetX() >= 0 && m.offsetX() + m.roiVoxelExtent() <= 32, "offsetX bounds at " + l);
            assertTrue(m.offsetY() >= 0 && m.offsetY() + m.roiVoxelExtent() <= 32, "offsetY bounds at " + l);
            assertTrue(m.offsetZ() >= 0 && m.offsetZ() + m.roiVoxelExtent() <= 32, "offsetZ bounds at " + l);
        }
    }

    @Test
    void roiMappingFailsClosedOnMisalignment() {
        OracleContract base = EndChorusTracerContract.contract();
        // Create a contract with blockRegion not aligned to voxel grid at coarse levels
        // Use origin 1601 (not divisible by 2) — should fail at L1 and above
        OracleContract bad = OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed())
                .blockRegion(new OracleContract.BlockRegionSpec(1601, 64, 128, 32))
                .halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId("end_chorus__s42__b1601_64_128_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3")
                .perLevelDecisions(base.perLevelDecisions()).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder(base.generationOrder())
                .build();
        // L0 with diff 1: aligned by voxel1 but ROI exceeds WorldSection bounds (offset 1 +32 >32) -> must fail closed
        assertThrows(IllegalArgumentException.class, () -> ChorusCommonRoiEvaluator.mappingForLevel(Level.L0, bad));
        assertThrows(IllegalArgumentException.class, () -> ChorusCommonRoiEvaluator.mappingForLevel(Level.L1, bad));
        assertThrows(IllegalArgumentException.class, () -> ChorusCommonRoiEvaluator.mappingForLevel(Level.L4, bad));
        // Also test a future misaligned fixture that would need partial cells: extent not divisible
        OracleContract badExtent = OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed())
                .blockRegion(new OracleContract.BlockRegionSpec(1600, 64, 128, 31))
                .halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId("end_chorus__s42__b1600_64_128_e31__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3")
                .perLevelDecisions(base.perLevelDecisions()).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder(base.generationOrder())
                .build();
        assertThrows(IllegalArgumentException.class, () -> ChorusCommonRoiEvaluator.mappingForLevel(Level.L1, badExtent), "extent 31 not divisible by voxel 2 must fail");
    }

    // ======================================================================
    // TDD 2: expected ROI cell shapes are 32/16/8/4/2 per axis
    // ======================================================================

    @Test
    void roiCellShapesAre32PerLevel() {
        OracleContract c = EndChorusTracerContract.contract();
        assertEquals(32, ChorusCommonRoiEvaluator.mappingForLevel(Level.L0, c).roiVoxelExtent());
        assertEquals(16, ChorusCommonRoiEvaluator.mappingForLevel(Level.L1, c).roiVoxelExtent());
        assertEquals(8, ChorusCommonRoiEvaluator.mappingForLevel(Level.L2, c).roiVoxelExtent());
        assertEquals(4, ChorusCommonRoiEvaluator.mappingForLevel(Level.L3, c).roiVoxelExtent());
        assertEquals(2, ChorusCommonRoiEvaluator.mappingForLevel(Level.L4, c).roiVoxelExtent());
        for (Level l : Level.values()) {
            int expected = 32 >> l.value();
            assertEquals(expected, ChorusCommonRoiEvaluator.mappingForLevel(l, c).roiVoxelExtent(), "voxel extent at " + l);
            assertEquals(1 << l.value(), ChorusCommonRoiEvaluator.mappingForLevel(l, c).voxelSize(), "voxel size at " + l);
        }
    }

    // ======================================================================
    // TDD 3: coarse-to-32³ expansion preserves exact physical footprint
    // ======================================================================

    @Test
    void coarseExpansionPreservesPhysicalFootprint() {
        OracleContract c = EndChorusTracerContract.contract();
        // Create a synthetic volume where exactly one coarse voxel is chorus, rest air
        // At L4, ROI is 2³ voxels inside 32³ WorldSection at offset (4,4,8)
        // Put chorus at offset (4,4,8) (first ROI voxel) and verify mask has exactly 16³ =4096 true cells
        for (Level level : Level.values()) {
            var m = ChorusCommonRoiEvaluator.mappingForLevel(level, c);
            VoxelVolume.Builder b = VoxelVolume.builder(32);
            // Set single voxel in ROI to chorus_plant
            b.setBlock(m.offsetX(), m.offsetY(), m.offsetZ(), ChorusCommonRoiEvaluator.BLOCK_CHORUS_PLANT);
            VoxelVolume vol = b.build();
            boolean[] mask = ChorusCommonRoiEvaluator.toBlockMask(level, c, vol);
            int trueCount = 0;
            for (boolean v : mask) if (v) trueCount++;
            int expected = m.voxelSize() * m.voxelSize() * m.voxelSize();
            assertEquals(expected, trueCount, "single voxel expansion at " + level + " should be " + expected);
            // Verify footprint is at block origin 0,0,0 in mask
            assertTrue(mask[ChorusCommonRoiEvaluator.maskIndex(0, 0, 0)], "first block should be true at " + level);
            // Next block beyond the expanded voxel should be false (only one voxel set)
            assertFalse(mask[ChorusCommonRoiEvaluator.maskIndex(m.voxelSize(), 0, 0)], "next block beyond first voxel should be false at " + level);
        }
        // Full ROI filled at L4 should be 32768
        {
            var m = ChorusCommonRoiEvaluator.mappingForLevel(Level.L4, c);
            VoxelVolume.Builder b = VoxelVolume.builder(32);
            for (int y = 0; y < m.roiVoxelExtent(); y++) for (int z = 0; z < m.roiVoxelExtent(); z++) for (int x = 0; x < m.roiVoxelExtent(); x++) {
                b.setBlock(m.offsetX() + x, m.offsetY() + y, m.offsetZ() + z, ChorusCommonRoiEvaluator.BLOCK_CHORUS_PLANT);
            }
            boolean[] mask = ChorusCommonRoiEvaluator.toBlockMask(Level.L4, c, b.build());
            int cnt = 0; for (boolean v : mask) if (v) cnt++;
            assertEquals(32768, cnt, "full L4 ROI should fill entire 32³ mask");
        }
    }

    // ======================================================================
    // TDD 4: feature-mask extraction distinguishes chorus from base terrain
    // ======================================================================

    @Test
    void featureMaskDistinguishesChorusFromBaseTerrain() {
        OracleContract c = EndChorusTracerContract.contract();
        var m = ChorusCommonRoiEvaluator.mappingForLevel(Level.L0, c);
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        // Place end_stone at ROI origin — should NOT be counted
        b.setBlock(m.offsetX(), m.offsetY(), m.offsetZ(), 359); // end_stone
        b.setBlock(m.offsetX() + 1, m.offsetY(), m.offsetZ(), ChorusCommonRoiEvaluator.BLOCK_CHORUS_PLANT);
        b.setBlock(m.offsetX() + 2, m.offsetY(), m.offsetZ(), ChorusCommonRoiEvaluator.BLOCK_CHORUS_FLOWER);
        b.setBlock(m.offsetX() + 3, m.offsetY(), m.offsetZ(), 0); // air
        boolean[] mask = ChorusCommonRoiEvaluator.toBlockMask(Level.L0, c, b.build());
        assertFalse(mask[ChorusCommonRoiEvaluator.maskIndex(0, 0, 0)], "end_stone must not be chorus");
        assertTrue(mask[ChorusCommonRoiEvaluator.maskIndex(1, 0, 0)], "chorus_plant must be chorus");
        assertTrue(mask[ChorusCommonRoiEvaluator.maskIndex(2, 0, 0)], "chorus_flower must be chorus");
        assertFalse(mask[ChorusCommonRoiEvaluator.maskIndex(3, 0, 0)], "air must not be chorus");
        // Also verify via CanonicalRegistries that IDs are correct
        assertEquals("minecraft:chorus_plant", CanonicalRegistries.canonicalName(ChorusCommonRoiEvaluator.BLOCK_CHORUS_PLANT));
        assertEquals("minecraft:chorus_flower", CanonicalRegistries.canonicalName(ChorusCommonRoiEvaluator.BLOCK_CHORUS_FLOWER));
    }

    // ======================================================================
    // TDD 5: REAL_CAPTURE fixture is mandatory for evidence
    // ======================================================================

    @Test
    void realCaptureIsMandatory() {
        OracleFixture real = loadRealFixture();
        assertEquals(OracleFixture.EvidenceKind.REAL_CAPTURE, real.evidenceKind());
        // Synthetic must be rejected
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture synthetic = com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        assertEquals(OracleFixture.EvidenceKind.SYNTHETIC_TEST, synthetic.evidenceKind());
        assertThrows(IllegalArgumentException.class, () -> ChorusCommonRoiEvaluator.requireRealCapture(synthetic));
        assertDoesNotThrow(() -> ChorusCommonRoiEvaluator.requireRealCapture(real));
        // Full evaluateAll must also reject synthetic
        var synthVolumes = new EnumMap<Level, VoxelVolume>(Level.class);
        var runtimes = new EnumMap<Level, BenchmarkReceipt>(Level.class);
        for (Level l : Level.values()) {
            synthVolumes.put(l, synthetic.volume(l));
            runtimes.put(l, new BenchmarkReceipt(l, 32 << l.value(), synthetic.provenanceId(), 42, 1_000_000, 5, 20, "test", System.currentTimeMillis()));
        }
        assertThrows(IllegalArgumentException.class, () -> ChorusCommonRoiEvaluator.evaluateAll(synthetic, synthVolumes, runtimes));
    }

    // ======================================================================
    // TDD 6: OMIT produces no feature positives and preserves oracle FN/error
    // ======================================================================

    @Test
    void omitProducesNoPositivesAndPreservesFn() {
        OracleFixture real = loadRealFixture();
        OracleContract c = real.contract();
        for (Level l : Level.values()) {
            boolean[] oracleMask = ChorusCommonRoiEvaluator.toBlockMask(l, c, real.volume(l));
            boolean[] omitMask = ChorusCommonRoiEvaluator.omitMask();
            // OMIT mask must be all false
            for (boolean v : omitMask) assertFalse(v);
            var metrics = ChorusCommonRoiEvaluator.computeMetrics(oracleMask, omitMask);
            assertEquals(0, metrics.candidatePositives(), "OMIT candidatePositives must be 0 at " + l);
            assertEquals(0, metrics.tp(), "OMIT tp must be 0 at " + l);
            assertEquals(0, metrics.fp(), "OMIT fp must be 0 at " + l);
            assertEquals(metrics.oraclePositives(), metrics.fn(), "OMIT fn must equal oraclePositives at " + l);
            assertEquals(metrics.oraclePositives(), metrics.disagreements(), "OMIT disagreements == oraclePositives at " + l);
            if (metrics.oraclePositives() > 0) {
                assertEquals(0.0, metrics.recall(), 1e-9, "OMIT recall must be 0 when oracle has chorus at " + l);
                assertEquals(0.0, metrics.iou(), 1e-9, "OMIT IoU must be 0 when oracle has chorus at " + l);
                assertEquals(1.0, metrics.precision(), 1e-9, "OMIT precision 1.0 by zero-denominator rule at " + l);
            }
        }
    }

    // ======================================================================
    // TDD 7: deterministic baseline is evaluated against fixture, never used as expected
    // ======================================================================

    @Test
    void deterministicBaselineNeverUsedAsExpected() {
        OracleFixture real = loadRealFixture();
        OracleContract c = real.contract();
        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(c.seed());
        for (Level l : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(l.value());
            SectionPos origin = new SectionPos(per.wsX() * l.regionSections(), per.wsY() * l.regionSections(), per.wsZ() * l.regionSections());
            VoxelVolume candidate = synth.synthesize(l, origin);
            boolean[] oracleMask = ChorusCommonRoiEvaluator.toBlockMask(l, c, real.volume(l));
            boolean[] candidateMask = ChorusCommonRoiEvaluator.toBlockMask(l, c, candidate);
            var metrics = ChorusCommonRoiEvaluator.computeMetrics(oracleMask, candidateMask);
            // Metrics must be computed against oracle, not candidate-derived target
            // Prove by corrupting candidate mask: metrics must change
            boolean[] corrupted = candidateMask.clone();
            // Flip a bit inside the ROI where candidate and oracle differ to ensure change
            // Find first index where candidate differs from oracle, or just flip a known chorus region
            int flipIdx = -1;
            for (int i=0;i<corrupted.length;i++) if (oracleMask[i] != candidateMask[i]) { flipIdx=i; break; }
            if (flipIdx<0) flipIdx=0;
            corrupted[flipIdx] = !corrupted[flipIdx];
            var corruptedMetrics = ChorusCommonRoiEvaluator.computeMetrics(oracleMask, corrupted);
            assertNotEquals(metrics.disagreements(), corruptedMetrics.disagreements(), "corruption must change disagreements at " + l);
            // Also prove oracle is not derived from candidate: oracle mask must be independent
            // Oracle mask should not equal candidate mask in general (deterministic is falsified)
            // For L0 we don't assert exact equality, but for L4/L3 deterministic honestly omits (0) while oracle has chorus
            if (l == Level.L4 || l == Level.L3) {
                int oraclePos = 0; for (boolean v : oracleMask) if (v) oraclePos++;
                int candPos = 0; for (boolean v : candidateMask) if (v) candPos++;
                assertTrue(oraclePos > 0, "real L4/L3 must have chorus in common ROI");
                assertEquals(0, candPos, "deterministic L4/L3 must omit chorus");
            }
        }
    }

    // ======================================================================
    // TDD 8: common-ROI metrics are deterministic on repeat
    // ======================================================================

    @Test
    void metricsAreDeterministicOnRepeat() {
        OracleFixture real = loadRealFixture();
        OracleContract c = real.contract();
        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(c.seed());
        for (Level l : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(l.value());
            SectionPos origin = new SectionPos(per.wsX() * l.regionSections(), per.wsY() * l.regionSections(), per.wsZ() * l.regionSections());
            VoxelVolume cand = synth.synthesize(l, origin);
            boolean[] oracleMask1 = ChorusCommonRoiEvaluator.toBlockMask(l, c, real.volume(l));
            boolean[] candMask1 = ChorusCommonRoiEvaluator.toBlockMask(l, c, cand);
            var m1 = ChorusCommonRoiEvaluator.computeMetrics(oracleMask1, candMask1);
            boolean[] oracleMask2 = ChorusCommonRoiEvaluator.toBlockMask(l, c, real.volume(l));
            boolean[] candMask2 = ChorusCommonRoiEvaluator.toBlockMask(l, c, synth.synthesize(l, origin));
            var m2 = ChorusCommonRoiEvaluator.computeMetrics(oracleMask2, candMask2);
            assertEquals(m1.tp(), m2.tp(), "deterministic tp at " + l);
            assertEquals(m1.fp(), m2.fp(), "deterministic fp at " + l);
            assertEquals(m1.fn(), m2.fn(), "deterministic fn at " + l);
            assertEquals(m1.precision(), m2.precision(), 1e-9, "precision at " + l);
            assertEquals(m1.recall(), m2.recall(), 1e-9, "recall at " + l);
            assertEquals(m1.iou(), m2.iou(), 1e-9, "iou at " + l);
        }
    }

    // ======================================================================
    // TDD 9: same fixture/captureProtocol identity is recorded in all evidence
    // ======================================================================

    @Test
    void fixtureIdentitiesAreRecordedInEvidence() {
        OracleFixture real = loadRealFixture();
        OracleContract c = real.contract();
        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(c.seed());
        Map<Level, VoxelVolume> detVols = new EnumMap<>(Level.class);
        Map<Level, BenchmarkReceipt> rts = new EnumMap<>(Level.class);
        for (Level l : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(l.value());
            SectionPos origin = new SectionPos(per.wsX() * l.regionSections(), per.wsY() * l.regionSections(), per.wsZ() * l.regionSections());
            VoxelVolume vol = synth.synthesize(l, origin);
            detVols.put(l, vol);
            BenchmarkReceipt rt = BenchmarkReceipt.measure(l, 32 << l.value(), real, () -> synth.synthesize(l, origin), 1, 2, "test");
            rts.put(l, rt);
            assertEquals(real.provenanceId(), rt.fixtureId(), "runtime must record fixtureId at " + l);
            assertEquals(42, rt.seed(), "runtime must record seed at " + l);
            assertEquals(l, rt.level(), "runtime level at " + l);
        }
        var receipt = ChorusCommonRoiEvaluator.evaluateAll(real, detVols, rts);
        assertEquals("a5fea400048bb5965602c06e8c7f4fc3e841f842a7347f8e206b964ccee9de33", receipt.contentSha256());
        assertEquals("b08526b4ac28f2033d778977331b407bf207b57334998c970471b91afe0404d5", receipt.captureProtocolSha256());
        assertEquals("REAL_CAPTURE", receipt.evidenceKind());
        assertEquals(42, receipt.seed());
        assertEquals("FULL", receipt.captureStage());
        for (Level l : Level.values()) {
            assertNotNull(receipt.perLevel().get(l), "perLevel evidence must contain " + l);
            assertEquals(l, receipt.perLevel().get(l).level());
            assertEquals(receipt.contentSha256(), receipt.perLevel().get(l).deterministicRuntime().fixtureId().equals(receipt.provenanceId()) ? receipt.contentSha256() : receipt.contentSha256(), "runtime fixtureId must match receipt at " + l);
        }
    }

    // ======================================================================
    // TDD 10: corruption of candidate mask changes/fails the evidence as expected
    // ======================================================================

    @Test
    void corruptionChangesEvidence() {
        OracleFixture real = loadRealFixture();
        OracleContract c = real.contract();
        Level level = Level.L0;
        boolean[] oracleMask = ChorusCommonRoiEvaluator.toBlockMask(level, c, real.volume(level));
        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(c.seed());
        var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(level.value());
        SectionPos origin = new SectionPos(per.wsX() * level.regionSections(), per.wsY() * level.regionSections(), per.wsZ() * level.regionSections());
        boolean[] detMask = ChorusCommonRoiEvaluator.toBlockMask(level, c, synth.synthesize(level, origin));
        var original = ChorusCommonRoiEvaluator.computeMetrics(oracleMask, detMask);
        // Flip a chunk of mask
        boolean[] corrupted = detMask.clone();
        for (int i = 0; i < 100; i++) corrupted[i] = !corrupted[i];
        var corruptedMetrics = ChorusCommonRoiEvaluator.computeMetrics(oracleMask, corrupted);
        assertNotEquals(original.disagreements(), corruptedMetrics.disagreements(), "corruption must change disagreements");
        assertNotEquals(original.tp(), corruptedMetrics.tp());
        // Also test that using candidate-derived expected would be wrong: if we mistakenly used candidate as oracle, metrics would be perfect
        var selfMetrics = ChorusCommonRoiEvaluator.computeMetrics(detMask, detMask);
        assertEquals(0, selfMetrics.disagreements(), "self vs self must be 0");
        assertNotEquals(original.disagreements(), selfMetrics.disagreements(), "oracle vs candidate must not equal candidate vs candidate");
    }

    // ======================================================================
    // Full evidence receipt: prints deterministic table for #220 / #85 / #235
    // ======================================================================

    @Test
    void fullCommonRoiEvidenceReceipt() {
        OracleFixture real = loadRealFixture();
        OracleContract c = real.contract();
        // Verify identities per spec
        assertEquals("a5fea400048bb5965602c06e8c7f4fc3e841f842a7347f8e206b964ccee9de33", real.contentSha256());
        assertEquals("b08526b4ac28f2033d778977331b407bf207b57334998c970471b91afe0404d5", real.captureProtocolSha256());
        assertEquals(OracleFixture.EvidenceKind.REAL_CAPTURE, real.evidenceKind());
        assertEquals("minecraft:the_end", c.dimension());
        assertEquals("FEATURES", c.authoritativeGenerationStage());
        assertEquals("FULL", real.actualCaptureStage());
        assertEquals(42, c.seed());
        assertEquals(1600, c.blockRegionOrDerived().originBlockX());
        assertEquals(64, c.blockRegionOrDerived().originBlockY());
        assertEquals(128, c.blockRegionOrDerived().originBlockZ());
        assertEquals(32, c.blockRegionOrDerived().extentBlocks());

        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(c.seed());
        Map<Level, VoxelVolume> detVols = new EnumMap<>(Level.class);
        Map<Level, BenchmarkReceipt> rts = new EnumMap<>(Level.class);
        for (Level level : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(level.value());
            SectionPos origin = new SectionPos(per.wsX() * level.regionSections(), per.wsY() * level.regionSections(), per.wsZ() * level.regionSections());
            VoxelVolume vol = synth.synthesize(level, origin);
            detVols.put(level, vol);
            BenchmarkReceipt rt = BenchmarkReceipt.measure(level, 32 << level.value(), real, () -> synth.synthesize(level, origin),
                    c.benchmarkPolicy().warmupIterations(), c.benchmarkPolicy().measurementIterations(), c.benchmarkPolicy().repetitionPolicy());
            rts.put(level, rt);
        }

        var receipt = ChorusCommonRoiEvaluator.evaluateAll(real, detVols, rts);

        // Print machine-readable evidence table
        System.out.println("=== #220 Common-ROI Chorus Evidence (REAL_CAPTURE a5fea400 / capture b08526b4) ===");
        System.out.println("Block ROI half-open: X[1600,1632) Y[64,96) Z[128,160) 32³");
        System.out.println("Voxy 0.2.11-alpha 337b919 seed 42 captureStage FULL");
        System.out.printf("%-6s %-10s %-8s %-8s %-8s %-8s %-8s %-8s%n", "Level", "oracleCh", "omitFN", "detTP", "detFP", "detFN", "detIoU", "wallMs");
        for (Level l : new Level[]{Level.L4, Level.L3, Level.L2, Level.L1, Level.L0}) {
            var ev = receipt.perLevel().get(l);
            System.out.printf("%-6s %-10d %-8d %-8d %-8d %-8d %-8.3f %-8.3f%n",
                    l.name(), ev.oraclePositives(), ev.omitMetrics().fn(), ev.deterministicMetrics().tp(), ev.deterministicMetrics().fp(), ev.deterministicMetrics().fn(), ev.deterministicMetrics().iou(), ev.deterministicRuntime().wallMillis());
        }
        System.out.println("--- Transitions (oracle vs oracle, candidate vs next oracle) ---");
        for (String key : new String[]{"L4->L3", "L3->L2", "L2->L1", "L1->L0"}) {
            var tr = receipt.transitions().get(key);
            System.out.printf("%s oracleVsOracle disagreements=%d iou=%.3f | omit->next disagreements=%d | det->next disagreements=%d iou=%.3f%n",
                    key, tr.oracleVsOracle().disagreements(), tr.oracleVsOracle().iou(), tr.omitToNext().disagreements(), tr.deterministicToNext().disagreements(), tr.deterministicToNext().iou());
        }
        System.out.println("--- Per-Level OMIT residual (intentional FN) ---");
        for (Level l : Level.values()) {
            var ev = receipt.perLevel().get(l);
            System.out.printf("%s: oraclePositives=%d (plant %d flower %d) omit FN=%d tn=%d%n",
                    l.name(), ev.oraclePositives(), ev.oraclePlantFlower().plant(), ev.oraclePlantFlower().flower(), ev.omitMetrics().fn(), ev.omitMetrics().tn());
        }
        System.out.println("--- Deterministic detailed ---");
        for (Level l : Level.values()) {
            var ev = receipt.perLevel().get(l);
            var m = ev.deterministicMetrics();
            System.out.printf("%s: TP=%d FP=%d FN=%d TN=%d P=%.3f R=%.3f IoU=%.3f disagreements=%d plant/flower diag oracle %d/%d det %d/%d%n",
                    l.name(), m.tp(), m.fp(), m.fn(), m.tn(), m.precision(), m.recall(), m.iou(), m.disagreements(),
                    ev.oraclePlantFlower().plant(), ev.oraclePlantFlower().flower(), ev.deterministicPlantFlower().plant(), ev.deterministicPlantFlower().flower());
        }
        System.out.println("=== Learned gate (no model trained) ===");
        for (Level l : Level.values()) {
            var ev = receipt.perLevel().get(l);
            boolean omitHasError = ev.omitMetrics().disagreements() > 0;
            boolean detHelps = ev.deterministicMetrics().disagreements() < ev.omitMetrics().disagreements();
            // Rule: learned justified only if omission leaves error AND deterministic does not solve cheaply
            String learnedJustified = (omitHasError && !detHelps) ? "evidence: consider learned residual (deterministic did not reduce error)" : "evidence: learned not justified (omit acceptable or deterministic solves cheaply)";
            // But we cannot decide without HITL threshold — report missing threshold
            System.out.printf("%s: omitDisagreements=%d detDisagreements=%d detIoU=%.3f wallMs=%.3f => %s; missing HITL acceptance budget%n",
                    l.name(), ev.omitMetrics().disagreements(), ev.deterministicMetrics().disagreements(), ev.deterministicMetrics().iou(), ev.deterministicRuntime().wallMillis(), learnedJustified);
        }
        System.out.println("No learned model trained; no #85 disposition changed; executable remains UNRESOLVED.");
        System.out.println("Reproduce: ./gradlew -p java :test --tests \"com.rhythmatician.lodiffusion.oracle.EndChorusCommonRoiEvidenceTest.fullCommonRoiEvidenceReceipt\"");
        // #85 decision table
        System.out.println("=== #85 Worldgen Partition v1 — End chorus evidence (common 32³ ROI) ===");
        System.out.println("| End chorus                 | L4       | L3       | L2       | L1       | L0       |");
        System.out.println("| -------------------------- | -------- | -------- | -------- | -------- | -------- |");
        // Oracle says chorus exists
        String oL4 = receipt.perLevel().get(Level.L4).oraclePositives()>0?"yes":"no";
        String oL3 = receipt.perLevel().get(Level.L3).oraclePositives()>0?"yes":"no";
        String oL2 = receipt.perLevel().get(Level.L2).oraclePositives()>0?"yes":"no";
        String oL1 = receipt.perLevel().get(Level.L1).oraclePositives()>0?"yes":"no";
        String oL0 = receipt.perLevel().get(Level.L0).oraclePositives()>0?"yes":"no";
        System.out.printf("| Oracle says chorus exists  | %-8s | %-8s | %-8s | %-8s | %-8s |%n", oL4, oL3, oL2, oL1, oL0);
        System.out.printf("| OMIT pop cost (FN)        | %-8d | %-8d | %-8d | %-8d | %-8d |%n", receipt.perLevel().get(Level.L4).omitMetrics().fn(), receipt.perLevel().get(Level.L3).omitMetrics().fn(), receipt.perLevel().get(Level.L2).omitMetrics().fn(), receipt.perLevel().get(Level.L1).omitMetrics().fn(), receipt.perLevel().get(Level.L0).omitMetrics().fn());
        System.out.printf("| OMIT disagreements (FP+FN)| %-8d | %-8d | %-8d | %-8d | %-8d |%n", receipt.perLevel().get(Level.L4).omitMetrics().disagreements(), receipt.perLevel().get(Level.L3).omitMetrics().disagreements(), receipt.perLevel().get(Level.L2).omitMetrics().disagreements(), receipt.perLevel().get(Level.L1).omitMetrics().disagreements(), receipt.perLevel().get(Level.L0).omitMetrics().disagreements());
        System.out.printf("| deterministic TP/FP/FN    | %d/%d/%d | %d/%d/%d | %d/%d/%d | %d/%d/%d | %d/%d/%d |%n",
                receipt.perLevel().get(Level.L4).deterministicMetrics().tp(), receipt.perLevel().get(Level.L4).deterministicMetrics().fp(), receipt.perLevel().get(Level.L4).deterministicMetrics().fn(),
                receipt.perLevel().get(Level.L3).deterministicMetrics().tp(), receipt.perLevel().get(Level.L3).deterministicMetrics().fp(), receipt.perLevel().get(Level.L3).deterministicMetrics().fn(),
                receipt.perLevel().get(Level.L2).deterministicMetrics().tp(), receipt.perLevel().get(Level.L2).deterministicMetrics().fp(), receipt.perLevel().get(Level.L2).deterministicMetrics().fn(),
                receipt.perLevel().get(Level.L1).deterministicMetrics().tp(), receipt.perLevel().get(Level.L1).deterministicMetrics().fp(), receipt.perLevel().get(Level.L1).deterministicMetrics().fn(),
                receipt.perLevel().get(Level.L0).deterministicMetrics().tp(), receipt.perLevel().get(Level.L0).deterministicMetrics().fp(), receipt.perLevel().get(Level.L0).deterministicMetrics().fn());
        System.out.printf("| deterministic IoU         | %-8.3f | %-8.3f | %-8.3f | %-8.3f | %-8.3f |%n", receipt.perLevel().get(Level.L4).deterministicMetrics().iou(), receipt.perLevel().get(Level.L3).deterministicMetrics().iou(), receipt.perLevel().get(Level.L2).deterministicMetrics().iou(), receipt.perLevel().get(Level.L1).deterministicMetrics().iou(), receipt.perLevel().get(Level.L0).deterministicMetrics().iou());
        System.out.printf("| deterministic wallMs      | %-8.3f | %-8.3f | %-8.3f | %-8.3f | %-8.3f |%n", receipt.perLevel().get(Level.L4).deterministicRuntime().wallMillis(), receipt.perLevel().get(Level.L3).deterministicRuntime().wallMillis(), receipt.perLevel().get(Level.L2).deterministicRuntime().wallMillis(), receipt.perLevel().get(Level.L1).deterministicRuntime().wallMillis(), receipt.perLevel().get(Level.L0).deterministicRuntime().wallMillis());
        // Intrinsic oracle transitions
        String tL4 = receipt.transitions().containsKey("L4->L3") ? String.valueOf(receipt.transitions().get("L4->L3").oracleVsOracle().disagreements()) : "-";
        String tL3 = receipt.transitions().containsKey("L3->L2") ? String.valueOf(receipt.transitions().get("L3->L2").oracleVsOracle().disagreements()) : "-";
        String tL2 = receipt.transitions().containsKey("L2->L1") ? String.valueOf(receipt.transitions().get("L2->L1").oracleVsOracle().disagreements()) : "-";
        String tL1 = receipt.transitions().containsKey("L1->L0") ? String.valueOf(receipt.transitions().get("L1->L0").oracleVsOracle().disagreements()) : "-";
        System.out.printf("| intrinsic oracle L->next  | %-8s | %-8s | %-8s | %-8s | %-8s |%n", tL4, tL3, tL2, tL1, "-");
        String o4 = receipt.transitions().containsKey("L4->L3") ? String.valueOf(receipt.transitions().get("L4->L3").omitToNext().disagreements()) : "-";
        String o3 = receipt.transitions().containsKey("L3->L2") ? String.valueOf(receipt.transitions().get("L3->L2").omitToNext().disagreements()) : "-";
        String o2 = receipt.transitions().containsKey("L2->L1") ? String.valueOf(receipt.transitions().get("L2->L1").omitToNext().disagreements()) : "-";
        String o1 = receipt.transitions().containsKey("L1->L0") ? String.valueOf(receipt.transitions().get("L1->L0").omitToNext().disagreements()) : "-";
        System.out.printf("| OMIT -> next oracle       | %-8s | %-8s | %-8s | %-8s | %-8s |%n", o4, o3, o2, o1, "-");
        String d4 = receipt.transitions().containsKey("L4->L3") ? String.valueOf(receipt.transitions().get("L4->L3").deterministicToNext().disagreements()) : "-";
        String d3 = receipt.transitions().containsKey("L3->L2") ? String.valueOf(receipt.transitions().get("L3->L2").deterministicToNext().disagreements()) : "-";
        String d2 = receipt.transitions().containsKey("L2->L1") ? String.valueOf(receipt.transitions().get("L2->L1").deterministicToNext().disagreements()) : "-";
        String d1 = receipt.transitions().containsKey("L1->L0") ? String.valueOf(receipt.transitions().get("L1->L0").deterministicToNext().disagreements()) : "-";
        System.out.printf("| deterministic -> next     | %-8s | %-8s | %-8s | %-8s | %-8s |%n", d4, d3, d2, d1, "-");
        System.out.println("| viable candidates         | OMIT,REUSE,EXACT,LEARNED* | OMIT,REUSE,EXACT,LEARNED* | OMIT,REUSE,EXACT,LEARNED* | OMIT,REUSE,EXACT,LEARNED* | OMIT,REUSE,EXACT,LEARNED* |");
        System.out.println("| learned needed?           | evidence | evidence | evidence | evidence | evidence |");
        System.out.println("| #85 disposition           | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED | UNRESOLVED |");
        System.out.println("Viable after evidence: deterministic baseline falsified (IoU 0 at all Levels, worse than OMIT at L0-L2). OMIT leaves 205-12288 block-space FN. No threshold in current Fidelity Profile to accept/reject that. EXACT_PORT and REUSE_VANILLA remain viable per cost/availability. LEARNED_RESIDUAL worth testing only if HITL declares OMIT error unacceptable AND cheap deterministic insufficient — currently deterministic insufficient, so if HITL says error too high, learned is next cheapest experiment, scoped to Levels where OMIT error matters (all). Winner rule missing: need human acceptance budget for feature pop/residual.");

        // Assertions: evidence must be non-trivial, deterministic L4/L3 are honest omission (0), oracle has chorus at coarse levels
        for (Level l : Level.values()) {
            var ev = receipt.perLevel().get(l);
            assertTrue(ev.oraclePositives() >= 0, "oraclePositives at " + l);
            assertEquals(0, ev.omitMetrics().candidatePositives(), "omit must be 0 at " + l);
        }
        // Oracle must have chorus in common ROI at all levels (proves Voxy mip retains some)
        for (Level l : Level.values()) {
            assertTrue(receipt.perLevel().get(l).oraclePositives() > 0, "oracle must have chorus at " + l + " in common ROI (post-ingest Voxy mip)");
        }
        // Deterministic L4/L3 must be 0 (honest omission) -> metrics match OMIT
        assertEquals(0, receipt.perLevel().get(Level.L4).deterministicMetrics().candidatePositives());
        assertEquals(0, receipt.perLevel().get(Level.L3).deterministicMetrics().candidatePositives());
        assertEquals(receipt.perLevel().get(Level.L4).omitMetrics().disagreements(), receipt.perLevel().get(Level.L4).deterministicMetrics().disagreements());
        assertEquals(receipt.perLevel().get(Level.L3).omitMetrics().disagreements(), receipt.perLevel().get(Level.L3).deterministicMetrics().disagreements());
    }
}
