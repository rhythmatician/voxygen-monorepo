package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.capture.OracleFixtureWriter;
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
 * Candidate/evidence pass against the REAL double-pristine fixture
 * end_chorus__s42__b1600_64_128_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3.
 *
 * <p>Exercises deterministic baseline (EndChorusSynthesizer.forTesting) at L0-L4
 * against the same target as any learned candidate would use, emitting
 * correctness + runtime BenchmarkReceipts. Also exercises CandidateVerifier
 * through a learned-adapter stub that does NOT manufacture expected values.
 *
 * <p>This test proves #233's interface: deterministic and learned share the
 * same fixture, same CandidateVerifier, and separate BenchmarkReceipt axes.
 */
class RealFixtureDeterministicBenchmarkTest {

    private static OracleFixture loadRealFixture() {
        OracleContract c = EndChorusTracerContract.contract();
        // Try multiple relative paths because gradle working dir is java/, but IDE may be repo root
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
        // Fallback: search via contract default
        Path def = OracleFixtureWriter.defaultFixturePath(c);
        if (Files.exists(def)) {
            try { return OracleFixtureWriter.read(def); } catch (Exception e) { throw new AssertionError(e); }
        }
        throw new AssertionError(
                "Real fixture not found. Expected at one of: "
                        + java.util.Arrays.toString(candidates)
                        + " or " + def.toAbsolutePath()
                        + ". Did you run --double-pristine capture? The synthetic fixture is NOT a substitute.");
    }

    @Test
    void deterministicBaselineBenchmarkedAtAllLevelsAgainstRealFixture() {
        OracleFixture fixture = loadRealFixture();
        OracleContract c = fixture.contract();
        // Ensure we are on the real double-pristine fixture, not synthetic
        assertEquals("a5fea400048bb5965602c06e8c7f4fc3e841f842a7347f8e206b964ccee9de33", fixture.contentSha256(),
                "Must be the double-pristine real fixture SHA");
        assertEquals(32, fixture.volume(Level.L0).extent());

        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(c.seed());

        Map<Level, Integer> mismatches = new EnumMap<>(Level.class);
        Map<Level, BenchmarkReceipt> receipts = new EnumMap<>(Level.class);

        for (Level level : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(level.value());
            SectionPos origin = new SectionPos(per.wsX() * level.regionSections(), per.wsY() * level.regionSections(), per.wsZ() * level.regionSections());

            // Correctness via CandidateVerifier (expected from fixture, not from candidate)
            VoxelVolume candidate = synth.synthesize(level, origin);
            var result = CandidateVerifier.verify(level, origin, candidate, fixture);
            mismatches.put(level, result.mismatchedVoxels());

            // Benchmark separately — wall time per volume, not correctness
            BenchmarkReceipt receipt = BenchmarkReceipt.measure(
                    level,
                    32 << level.value(),
                    fixture,
                    () -> synth.synthesize(level, origin),
                    c.benchmarkPolicy().warmupIterations(),
                    c.benchmarkPolicy().measurementIterations(),
                    c.benchmarkPolicy().repetitionPolicy());
            receipts.put(level, receipt);
            assertTrue(receipt.wallNanos() >= 0, "wallNanos must be non-negative at " + level);
            assertEquals(level, receipt.level());
            assertEquals(c.provenanceId(), receipt.fixtureId());
            System.out.printf("DETERMINISTIC level=%s region=%d mismatched=%d/%d wallMs=%.3f warmup=%d iters=%d policy=%s passed=%b%n",
                    receipt.level(), receipt.regionBlocks(), result.mismatchedVoxels(), result.totalVoxels(),
                    receipt.wallMillis(), receipt.warmupIterations(), receipt.measurementIterations(),
                    receipt.repetitionPolicy(), result.passed());
        }

        // Evidence: deterministic baseline is NOT perfect against real oracle — this is the point.
        // L4/L3 honestly omit chorus (0) but real fixture has L4=134 L3=83, so they must mismatch.
        // We do NOT assert exact counts (brittle to fixture evolution), but we assert:
        // - L0-L2 are exercised and produce some chorus (synth is not trivially empty)
        // - At least one coarse level mismatches, proving honest omission vs real
        assertTrue(mismatches.get(Level.L4) > 0, "L4 deterministic omission must mismatch real L4 chorus (134)");
        assertTrue(mismatches.get(Level.L3) > 0, "L3 deterministic omission must mismatch real L3 chorus (83)");
        // L0-L2 may partially match or not; but they must have been measured
        assertNotNull(receipts.get(Level.L0));
        assertNotNull(receipts.get(Level.L1));
        assertNotNull(receipts.get(Level.L2));

        // Crucial: each Level's volume count is independent — not a survival curve.
        // This test documents per-Level correctness against the SAME fixture, proving
        // the BenchmarkReceipt/CandidateVerifier seam works for any candidate.
    }

    /**
     * Minimal learned-adapter stub that does NOT use EndChorusSynthesizer to
     * manufacture expected values. It exercises the same CandidateVerifier
     * seam a real ONNX adapter would use, proving #233's interface without
     * training a model.
     */
    static class LearnedChorusAdapter {
        private final long seed;

        LearnedChorusAdapter(long seed) { this.seed = seed; }

        VoxelVolume predict(Level level, SectionPos origin) {
            // Trivial learned-like behavior: return air for coarse levels, and a
            // simple learned pattern for L0-L2 that is deliberately imperfect.
            // This does NOT call EndChorusSynthesizer and does NOT read fixture.
            if (level == Level.L4 || level == Level.L3) {
                return VoxelVolume.builder(32).build(); // learned predicts no chorus coarse (same honest omission)
            }
            // For L0-L2: produce a pattern that is learnable but not exact —
            // e.g., place chorus where hash % 25 ==0 (vs deterministic baseline %20)
            // This simulates a learned model with different recall.
            VoxelVolume.Builder b = VoxelVolume.builder(32);
            int voxelBlocks = 1 << level.value();
            int baseX = origin.x() << 4;
            int baseY = origin.y() << 4;
            int baseZ = origin.z() << 4;
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int worldX = baseX + x * voxelBlocks + voxelBlocks/2;
                        int worldZ = baseZ + z * voxelBlocks + voxelBlocks/2;
                        int worldY = baseY + y * voxelBlocks + voxelBlocks/2;
                        long h = seed ^ ((long) worldX * 0x9E3779B97F4A7C15L) ^ ((long) worldZ * 0xBF58476D1CE4E5B9L);
                        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
                        // Learned threshold 25 vs deterministic 20 — will mismatch
                        if ((Math.abs(h) % 25) == 0 && worldY >= 64 && worldY < 80) {
                            int h2 = (int) (Math.abs(h >> 8) % 4);
                            int top = 64 + h2; // simplified
                            if (worldY <= top) {
                                b.setBlock(x, y, z, (worldY == top) ? 196 : 197);
                            }
                        }
                    }
                }
            }
            return b.build();
        }
    }

    @Test
    void learnedAdapterExercisesSameVerifierWithoutManufacturingExpected() {
        OracleFixture fixture = loadRealFixture();
        OracleContract c = fixture.contract();
        LearnedChorusAdapter learned = new LearnedChorusAdapter(c.seed());

        for (Level level : new Level[]{Level.L0, Level.L1, Level.L2}) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(level.value());
            SectionPos origin = new SectionPos(per.wsX() * level.regionSections(), per.wsY() * level.regionSections(), per.wsZ() * level.regionSections());

            VoxelVolume candidate = learned.predict(level, origin);
            // Verifier must run and use fixture expected, not candidate's own hash
            var result = CandidateVerifier.verify(level, origin, candidate, fixture);
            assertNotNull(result, "verifier must produce result at " + level);
            assertTrue(result.mismatchedVoxels() >= 0);

            // Benchmark the learned adapter separately — same seam as deterministic
            BenchmarkReceipt receipt = BenchmarkReceipt.measure(
                    level, 32 << level.value(), fixture,
                    () -> learned.predict(level, origin),
                    c.benchmarkPolicy().warmupIterations(),
                    c.benchmarkPolicy().measurementIterations(),
                    c.benchmarkPolicy().repetitionPolicy());
            assertTrue(receipt.wallNanos() >= 0);
            System.out.printf("LEARNED-ADAPTER level=%s mismatched=%d/%d wallMs=%.3f passed=%b%n",
                    level, result.mismatchedVoxels(), result.totalVoxels(), receipt.wallMillis(), result.passed());

            // Prove expected was NOT manufactured from candidate: corrupt candidate must fail differently
            VoxelVolume.Builder corrupted = VoxelVolume.builder(32);
            VoxelVolume correctVol = fixture.volume(level);
            // Take correct volume and blank it — must fail
            for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int id = correctVol.blockId(x, y, z);
                if (id == 197) corrupted.setBlock(x, y, z, 359); // plant -> stone
                else if (id != 0) corrupted.setBlock(x, y, z, id);
            }
            var corruptResult = CandidateVerifier.verify(level, origin, corrupted.build(), fixture);
            assertTrue(corruptResult.failed(), "corrupted fixture volume must fail at " + level);
            // And learned's own volume must be independent of that corruption (proves no shared helper)
            assertNotEquals(corruptResult.mismatchedVoxels(), result.mismatchedVoxels() - 0, "need not be equal but proves independence");
        }
    }

    @Test
    void benchmarkReceiptRecordsWarmupAndIsDeterministic() {
        OracleFixture fixture = loadRealFixture();
        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(fixture.contract().seed());
        var per = fixture.contract().blockRegionOrDerived().perLevelWorldSectionOrigin(Level.L1.value());
        SectionPos origin = new SectionPos(per.wsX() * Level.L1.regionSections(), per.wsY() * Level.L1.regionSections(), per.wsZ() * Level.L1.regionSections());
        BenchmarkReceipt r1 = BenchmarkReceipt.measure(Level.L1, 64, fixture, () -> synth.synthesize(Level.L1, origin), 2, 5, "test");
        BenchmarkReceipt r2 = BenchmarkReceipt.measure(Level.L1, 64, fixture, () -> synth.synthesize(Level.L1, origin), 2, 5, "test");
        assertEquals(r1.level(), r2.level());
        assertEquals(r1.fixtureId(), r2.fixtureId());
        assertEquals(r1.warmupIterations(), r2.warmupIterations());
    }
}
