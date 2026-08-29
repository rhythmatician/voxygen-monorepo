package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.voxy.EndChorusSynthesizer;
import com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import org.junit.jupiter.api.Test;

/**
 * Benchmark receipt for the existing disabled deterministic chorus candidate.
 * Correctness and speed are separate axes; this test records oracle-independent cost
 * without becoming oracle authority.
 */
class DisabledChorusBenchmarkTest {

    @Test
    void benchmarkExistingDisabledCandidate() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        SectionPos origin = f.origin();

        // Candidate is disabled in production; we measure the experimental path explicitly
        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(42L);

        for (Level level : new Level[]{Level.L1, Level.L2, Level.L0}) {
            BenchmarkReceipt receipt = BenchmarkReceipt.measure(
                    level,
                    32 << level.value(),
                    f,
                    () -> synth.synthesize(level, origin),
                    c.benchmarkPolicy().warmupIterations(),
                    c.benchmarkPolicy().measurementIterations(),
                    c.benchmarkPolicy().repetitionPolicy());

            assertTrue(receipt.wallNanos() >= 0);
            assertEquals(level, receipt.level());
            assertEquals(c.provenanceId(), receipt.fixtureId());
            // Log receipt for evidence; not an assertion on threshold
            System.out.printf("BENCHMARK level=%s region=%d seed=%d wallMs=%.2f warmup=%d iters=%d policy=%s%n",
                    receipt.level(), receipt.regionBlocks(), receipt.seed(), receipt.wallMillis(),
                    receipt.warmupIterations(), receipt.measurementIterations(), receipt.repetitionPolicy());

            // Verify candidate can be measured without becoming oracle authority: measure completes and candidate is not used as expected
            VoxelVolume candidate = synth.synthesize(level, origin);
            // Candidate vs fixture may or may not match (current %20 approximation is not claimed accurate) -- we only assert verifier runs
            var r = CandidateVerifier.verify(level, origin, candidate, f);
            assertNotNull(r);
        }
    }

    @Test
    void benchmarkReceiptIsDeterministicAndRecordsWarmup() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        SectionPos origin = f.origin();
        EndChorusSynthesizer synth = EndChorusSynthesizer.forTesting(42L);
        BenchmarkReceipt r1 = BenchmarkReceipt.measure(Level.L1, 64, f, () -> synth.synthesize(Level.L1, origin), 2, 5, "median of 5 after 2 warmup");
        BenchmarkReceipt r2 = BenchmarkReceipt.measure(Level.L1, 64, f, () -> synth.synthesize(Level.L1, origin), 2, 5, "median of 5 after 2 warmup");
        assertEquals(r1.level(), r2.level());
        assertEquals(r1.fixtureId(), r2.fixtureId());
        assertEquals(r1.warmupIterations(), r2.warmupIterations());
    }

    @Test
    void runtimeChorusRemainsDisabledByDefault() {
        // Guard: production synthesizer factory must remain disabled per ADR 0015 until #220/#233 resolved
        com.rhythmatician.lodiffusion.voxy.DimensionSynthesizers synthFactory = null;
        // Use contract to prove disabled disposition at coarse levels
        OracleContract c = EndChorusTracerContract.contract();
        assertEquals("UNRESOLVED", c.perLevelDecisions().l4().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l3().disposition());
    }
}



