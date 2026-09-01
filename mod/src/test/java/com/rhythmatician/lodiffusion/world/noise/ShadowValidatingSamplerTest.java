package com.rhythmatician.lodiffusion.world.noise;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ShadowValidatingSampler}.
 *
 * <p>Uses Mockito to verify that both reference and candidate samplers are
 * always invoked, the reference result is returned, and the parity reporter
 * receives comparisons according to the configured sampling rate.
 */
@ExtendWith(MockitoExtension.class)
class ShadowValidatingSamplerTest {

    // ── Helpers ──────────────────────────────────────────────────────

    private static SectionNoiseData makeData(float value, int sx, int sy, int sz) {
        float[] flat = new float[SectionNoiseData.FLAT_LENGTH];
        Arrays.fill(flat, value);
        return new SectionNoiseData(flat, sx, sy, sz);
    }

    private static ParityConfig alwaysSample() {
        return new ParityConfig(
                1.0, 1000, ParityConfig.LogLevel.SUMMARY,
                0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);
    }

    private static ParityConfig neverSample() {
        return new ParityConfig(
                0.0, 1000, ParityConfig.LogLevel.SUMMARY,
                0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);
    }

    @Mock NoiseRouterSampler reference;
    @Mock NoiseRouterSampler candidate;

    @BeforeEach
    void setUpBackendNames() {
        lenient().when(reference.backendName()).thenReturn("vanilla_cpu");
        lenient().when(candidate.backendName()).thenReturn("gpu");
    }

    // ── sampleSection behaviour ──────────────────────────────────────

    @Nested
    class SampleSection {

        @Test
        void callsBothSamplers() {
            SectionNoiseData refData = makeData(1.0f, 5, 3, 7);
            SectionNoiseData candData = makeData(2.0f, 5, 3, 7);
            when(reference.sampleSection(5, 3, 7)).thenReturn(refData);
            when(candidate.sampleSection(5, 3, 7)).thenReturn(candData);

            ShadowValidatingSampler shadow =
                    new ShadowValidatingSampler(reference, candidate, alwaysSample());
            shadow.sampleSection(5, 3, 7);

            verify(reference).sampleSection(5, 3, 7);
            verify(candidate).sampleSection(5, 3, 7);
        }

        @Test
        void alwaysReturnsReferenceResult() {
            SectionNoiseData refData = makeData(42.0f, 0, 0, 0);
            SectionNoiseData candData = makeData(99.0f, 0, 0, 0);
            when(reference.sampleSection(0, 0, 0)).thenReturn(refData);
            when(candidate.sampleSection(0, 0, 0)).thenReturn(candData);

            ShadowValidatingSampler shadow =
                    new ShadowValidatingSampler(reference, candidate, alwaysSample());
            SectionNoiseData result = shadow.sampleSection(0, 0, 0);

            assertSame(refData, result, "Must always return reference data");
        }

        @Test
        void candidateSampled_evenWhenParityComparisonSkipped() {
            // neverSample → shouldSample()=false always, but candidate still called
            SectionNoiseData refData = makeData(1.0f, 0, 0, 0);
            SectionNoiseData candData = makeData(2.0f, 0, 0, 0);
            when(reference.sampleSection(0, 0, 0)).thenReturn(refData);
            when(candidate.sampleSection(0, 0, 0)).thenReturn(candData);

            ShadowValidatingSampler shadow =
                    new ShadowValidatingSampler(reference, candidate, neverSample());
            shadow.sampleSection(0, 0, 0);

            verify(reference).sampleSection(0, 0, 0);
            verify(candidate).sampleSection(0, 0, 0);
        }
    }

    // ── Reporter interaction ─────────────────────────────────────────

    @Nested
    class ReporterInteraction {

        @Test
        void alwaysSample_comparesEverySection() {
            SectionNoiseData refData = makeData(1.0f, 0, 0, 0);
            SectionNoiseData candData = makeData(2.0f, 0, 0, 0);
            when(reference.sampleSection(anyInt(), anyInt(), anyInt())).thenReturn(refData);
            when(candidate.sampleSection(anyInt(), anyInt(), anyInt())).thenReturn(candData);

            ShadowValidatingSampler shadow =
                    new ShadowValidatingSampler(reference, candidate, alwaysSample());

            for (int i = 0; i < 10; i++) {
                shadow.sampleSection(i, 0, 0);
            }

            assertEquals(10, shadow.reporter().sectionsInWindow(),
                    "All 10 sections should be compared when rate=1.0");
        }

        @Test
        void neverSample_comparesNoSections() {
            SectionNoiseData data = makeData(1.0f, 0, 0, 0);
            when(reference.sampleSection(anyInt(), anyInt(), anyInt())).thenReturn(data);
            when(candidate.sampleSection(anyInt(), anyInt(), anyInt())).thenReturn(data);

            ShadowValidatingSampler shadow =
                    new ShadowValidatingSampler(reference, candidate, neverSample());

            for (int i = 0; i < 100; i++) {
                shadow.sampleSection(i, 0, 0);
            }

            assertEquals(0, shadow.reporter().sectionsInWindow(),
                    "No sections should be compared when rate=0.0");
        }
    }

    // ── backendName ──────────────────────────────────────────────────

    @Test
    void backendName_containsBothNames() {
        when(reference.backendName()).thenReturn("vanilla_cpu");
        when(candidate.backendName()).thenReturn("gpu");

        ShadowValidatingSampler shadow =
                new ShadowValidatingSampler(reference, candidate, alwaysSample());
        String name = shadow.backendName();

        assertTrue(name.contains("vanilla_cpu"), "Should contain reference name");
        assertTrue(name.contains("gpu"), "Should contain candidate name");
        assertTrue(name.startsWith("shadow("), "Should be wrapped in shadow(...)");
    }

    // ── Accessors ────────────────────────────────────────────────────

    @Test
    void reference_returnsInjectedSampler() {
        ShadowValidatingSampler shadow =
                new ShadowValidatingSampler(reference, candidate, alwaysSample());
        assertSame(reference, shadow.reference());
    }

    @Test
    void candidate_returnsInjectedSampler() {
        ShadowValidatingSampler shadow =
                new ShadowValidatingSampler(reference, candidate, alwaysSample());
        assertSame(candidate, shadow.candidate());
    }

    @Test
    void reporter_returnsNonNull() {
        ShadowValidatingSampler shadow =
                new ShadowValidatingSampler(reference, candidate, alwaysSample());
        assertNotNull(shadow.reporter());
    }

    // ── close() lifecycle ────────────────────────────────────────────

    @Nested
    class Close {

        @Test
        void close_callsCloseOnBothSamplers() {
            ShadowValidatingSampler shadow =
                    new ShadowValidatingSampler(reference, candidate, alwaysSample());
            shadow.close();

            verify(reference).close();
            verify(candidate).close();
        }

        @Test
        void close_flushesReporterWindow() {
            SectionNoiseData data = makeData(1.0f, 0, 0, 0);
            when(reference.sampleSection(0, 0, 0)).thenReturn(data);
            when(candidate.sampleSection(0, 0, 0)).thenReturn(data);

            ShadowValidatingSampler shadow =
                    new ShadowValidatingSampler(reference, candidate, alwaysSample());
            shadow.sampleSection(0, 0, 0);
            assertEquals(1, shadow.reporter().sectionsInWindow());

            shadow.close();
            assertEquals(0, shadow.reporter().sectionsInWindow(),
                    "Window should be flushed on close");
        }
    }
}
