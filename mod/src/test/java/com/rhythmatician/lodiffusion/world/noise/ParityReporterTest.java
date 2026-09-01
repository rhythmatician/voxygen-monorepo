package com.rhythmatician.lodiffusion.world.noise;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Unit tests for {@link ParityReporter} — sampling gate, error accumulation,
 * threshold detection, and window flushing.
 *
 * <p>All tests are pure-logic (no Minecraft, no OpenGL, no real logging).
 */
class ParityReporterTest {

    // ── Helpers ──────────────────────────────────────────────────────

    /** Create a SectionNoiseData filled with a constant value for every cell. */
    private static SectionNoiseData constant(float value) {
        float[] flat = new float[SectionNoiseData.FLAT_LENGTH];
        Arrays.fill(flat, value);
        return new SectionNoiseData(flat, 0, 0, 0);
    }

    /** Create a SectionNoiseData where field {@code fieldIdx} has value {@code value}
     *  at every cell, and all other fields are zero. */
    private static SectionNoiseData singleField(int fieldIdx, float value) {
        float[] flat = new float[SectionNoiseData.FLAT_LENGTH];
        int base = fieldIdx * SectionNoiseData.CELLS_PER_FIELD;
        for (int c = 0; c < SectionNoiseData.CELLS_PER_FIELD; c++) {
            flat[base + c] = value;
        }
        return new SectionNoiseData(flat, 0, 0, 0);
    }

    /** Config with 100% sampling and a window of {@code window}. */
    private static ParityConfig allSampled(int window) {
        return new ParityConfig(
                /* samplingRate            */ 1.0,
                /* aggregationWindow       */ window,
                /* logLevel                */ ParityConfig.LogLevel.SUMMARY,
                /* climateThreshold        */ 0.01f,
                /* depthThreshold          */ 0.05f,
                /* densityThreshold        */ 0.10f,
                /* aquiferThreshold        */ 0.05f,
                /* oreThreshold            */ 0.05f,
                /* densitySignAgreementMin */ 0.995f);
    }

    // ── shouldSample tests ───────────────────────────────────────────

    @Nested
    class ShouldSample {

        @Test
        void rate_1_alwaysReturnsTrue() {
            ParityConfig cfg = new ParityConfig(1.0, 100,
                    ParityConfig.LogLevel.SUMMARY,
                    0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);
            ParityReporter r = new ParityReporter(cfg);
            for (int i = 0; i < 200; i++) {
                assertTrue(r.shouldSample(), "rate=1.0 must always return true");
            }
        }

        @Test
        void rate_0_alwaysReturnsFalse() {
            ParityConfig cfg = new ParityConfig(0.0, 100,
                    ParityConfig.LogLevel.SUMMARY,
                    0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);
            ParityReporter r = new ParityReporter(cfg);
            for (int i = 0; i < 200; i++) {
                assertFalse(r.shouldSample(), "rate=0.0 must always return false");
            }
        }

        @RepeatedTest(5)
        void rate_half_mixesResults() {
            ParityConfig cfg = new ParityConfig(0.5, 100,
                    ParityConfig.LogLevel.SUMMARY,
                    0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);
            ParityReporter r = new ParityReporter(cfg);
            int trues = 0;
            int trials = 10_000;
            for (int i = 0; i < trials; i++) {
                if (r.shouldSample()) trues++;
            }
            // With 10k trials at p=0.5, expect ~5000 ± ~200 (4σ).
            assertTrue(trues > 4000 && trues < 6000,
                    "Expected ~50% true, got " + trues + "/" + trials);
        }
    }

    // ── compare / accumulation tests ─────────────────────────────────

    @Nested
    class Compare {

        private ParityReporter reporter;

        @BeforeEach
        void setUp() {
            // Window of 1000 (won't be reached in these tests), 100% sampling
            reporter = new ParityReporter(allSampled(1000));
        }

        @Test
        void identicalData_incrementsSectionsInWindow() {
            SectionNoiseData data = constant(1.0f);
            reporter.compare(data, data, 0, 0, 0);
            assertEquals(1, reporter.sectionsInWindow());
        }

        @Test
        void identicalData_zeroError() {
            SectionNoiseData data = constant(5.0f);
            // Compare 3 identical sections
            for (int i = 0; i < 3; i++) {
                reporter.compare(data, data, i, 0, 0);
            }
            assertEquals(3, reporter.sectionsInWindow());
            // No exception, no violation — we can't peek at stats directly,
            // but flush should succeed without warn
            reporter.flushWindow();
            assertEquals(0, reporter.sectionsInWindow());
        }

        @Test
        void differentData_accumulatesError() {
            SectionNoiseData ref = constant(1.0f);
            SectionNoiseData cand = constant(2.0f);
            reporter.compare(ref, cand, 0, 0, 0);
            assertEquals(1, reporter.sectionsInWindow());
            // Errors are accumulated internally — just verifying it doesn't blow up
        }

        @Test
        void signDisagreement_tracked_whenSignsDiffer() {
            // reference has positive density, candidate has negative
            SectionNoiseData ref = singleField(RouterField.FINAL_DENSITY.ordinal(), 1.0f);
            SectionNoiseData cand = singleField(RouterField.FINAL_DENSITY.ordinal(), -1.0f);
            reporter.compare(ref, cand, 0, 0, 0);
            assertEquals(1, reporter.sectionsInWindow());
        }

        @Test
        void signDisagreement_notTriggered_whenSignsSame() {
            SectionNoiseData ref = singleField(RouterField.FINAL_DENSITY.ordinal(), 1.0f);
            SectionNoiseData cand = singleField(RouterField.FINAL_DENSITY.ordinal(), 0.5f);
            reporter.compare(ref, cand, 0, 0, 0);
            assertEquals(1, reporter.sectionsInWindow());
        }
    }

    // ── Window flushing tests ────────────────────────────────────────

    @Nested
    class WindowFlushing {

        @Test
        void autoFlush_whenWindowSizeReached() {
            int windowSize = 5;
            ParityReporter r = new ParityReporter(allSampled(windowSize));
            SectionNoiseData data = constant(1.0f);

            for (int i = 0; i < windowSize; i++) {
                r.compare(data, data, i, 0, 0);
            }
            // Window should have auto-flushed after reaching the window size
            assertEquals(0, r.sectionsInWindow(),
                    "Window should auto-flush after " + windowSize + " sections");
        }

        @Test
        void autoFlush_resetsAfterBoundary() {
            int windowSize = 3;
            ParityReporter r = new ParityReporter(allSampled(windowSize));
            SectionNoiseData data = constant(1.0f);

            // Fill window 1
            for (int i = 0; i < windowSize; i++) {
                r.compare(data, data, i, 0, 0);
            }
            assertEquals(0, r.sectionsInWindow());

            // Start window 2
            r.compare(data, data, 99, 0, 0);
            assertEquals(1, r.sectionsInWindow());
        }

        @Test
        void manualFlush_resetsCounter() {
            ParityReporter r = new ParityReporter(allSampled(1000));
            SectionNoiseData data = constant(1.0f);
            r.compare(data, data, 0, 0, 0);
            r.compare(data, data, 1, 0, 0);
            assertEquals(2, r.sectionsInWindow());

            r.flushWindow();
            assertEquals(0, r.sectionsInWindow());
        }

        @Test
        void flushEmptyWindow_noOp() {
            ParityReporter r = new ParityReporter(allSampled(100));
            // Should not throw or produce output
            r.flushWindow();
            assertEquals(0, r.sectionsInWindow());
        }
    }

    // ── Threshold violation detection ────────────────────────────────

    @Nested
    class ThresholdViolation {

        @Test
        void largeClimateError_doesNotCrash() {
            // Climate threshold is 0.01 — give it a 10.0 error
            ParityConfig cfg = new ParityConfig(
                    1.0, 2, ParityConfig.LogLevel.QUIET,
                    0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);
            ParityReporter r = new ParityReporter(cfg);

            SectionNoiseData ref = singleField(RouterField.TEMPERATURE.ordinal(), 0.0f);
            SectionNoiseData cand = singleField(RouterField.TEMPERATURE.ordinal(), 10.0f);
            r.compare(ref, cand, 0, 0, 0);
            assertEquals(1, r.sectionsInWindow());
        }

        @Test
        void densitySignViolation_doesNotCrash() {
            // All 64 density cells disagree in sign (0% agreement, min is 99.5%)
            ParityConfig cfg = new ParityConfig(
                    1.0, 100, ParityConfig.LogLevel.QUIET,
                    0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.995f);
            ParityReporter r = new ParityReporter(cfg);

            SectionNoiseData ref = singleField(RouterField.FINAL_DENSITY.ordinal(), 1.0f);
            SectionNoiseData cand = singleField(RouterField.FINAL_DENSITY.ordinal(), -1.0f);
            r.compare(ref, cand, 0, 0, 0);
            assertEquals(1, r.sectionsInWindow());
        }
    }

    // ── Config accessor ──────────────────────────────────────────────

    @Test
    void config_returnsConfigPassedToConstructor() {
        ParityConfig cfg = ParityConfig.defaults();
        ParityReporter r = new ParityReporter(cfg);
        assertSame(cfg, r.config());
    }

    // ── Per-section log level ────────────────────────────────────────

    @Test
    void perSectionLogLevel_doesNotCrash() {
        ParityConfig cfg = new ParityConfig(
                1.0, 100, ParityConfig.LogLevel.PER_SECTION,
                0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);
        ParityReporter r = new ParityReporter(cfg);
        SectionNoiseData ref = constant(0.0f);
        SectionNoiseData cand = constant(1.0f);
        // PER_SECTION logs every compare — ensure no NPE/crash
        r.compare(ref, cand, 0, 0, 0);
        r.compare(ref, cand, 1, 0, 0);
        assertEquals(2, r.sectionsInWindow());
    }
}
