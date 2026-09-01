package com.rhythmatician.lodiffusion.world.noise;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for shadow mode, exercising the complete chain
 * without any Minecraft server context:
 *
 * <ol>
 *   <li>Inline stub {@link NoiseRouterSampler} implementations (reference + candidate)</li>
 *   <li>{@link ShadowValidatingSampler} wraps them and feeds {@link ParityReporter}</li>
 *   <li>Assembled into {@link UpstreamNoiseContext} with stub {@link HeightmapProvider}
 *       and {@link BiomeProvider}</li>
 *   <li>Full {@code sampleSection} loop → reporter stats and window-flush verified</li>
 *   <li>{@code close()} lifecycle tracked with {@link AtomicBoolean} flags</li>
 * </ol>
 *
 * <p>{@link NoiseRouterSamplerFactory#resolveBackendKey} is package-private; this
 * test lives in the same package and can call it directly.
 */
class ShadowModeIntegrationTest {

    // ── Inline stub helpers ──────────────────────────────────────────────────

    private static SectionNoiseData makeData(float value, int sx, int sy, int sz) {
        float[] flat = new float[SectionNoiseData.FLAT_LENGTH];
        Arrays.fill(flat, value);
        return new SectionNoiseData(flat, sx, sy, sz);
    }

    /** A sampler that returns a constant value for every cell and every section. */
    private static NoiseRouterSampler constantSampler(float value, String name) {
        return new NoiseRouterSampler() {
            @Override
            public SectionNoiseData sampleSection(int x, int y, int z) {
                return makeData(value, x, y, z);
            }
            @Override
            public String backendName() { return name; }
        };
    }

    /** A sampler whose {@link NoiseRouterSampler#close()} sets a tracking flag. */
    private static NoiseRouterSampler trackingCloseSampler(float value, String name,
                                                            AtomicBoolean closedFlag) {
        return new NoiseRouterSampler() {
            @Override
            public SectionNoiseData sampleSection(int x, int y, int z) {
                return makeData(value, x, y, z);
            }
            @Override
            public String backendName() { return name; }
            @Override
            public void close() { closedFlag.set(true); }
        };
    }

    private static HeightmapProvider stubHeightmap(String name) {
        return new HeightmapProvider() {
            @Override
            public HeightmapData sampleHeightmaps(int x, int z) {
                return new HeightmapData(new float[HeightmapData.GRID][HeightmapData.GRID],
                        new float[HeightmapData.GRID][HeightmapData.GRID]);
            }
            @Override
            public String backendName() { return name; }
        };
    }

    private static HeightmapProvider trackingHeightmap(AtomicBoolean closedFlag) {
        return new HeightmapProvider() {
            @Override
            public HeightmapData sampleHeightmaps(int x, int z) {
                return new HeightmapData(new float[HeightmapData.GRID][HeightmapData.GRID],
                        new float[HeightmapData.GRID][HeightmapData.GRID]);
            }
            @Override
            public String backendName() { return "hm_tracking"; }
            @Override
            public void close() { closedFlag.set(true); }
        };
    }

    private static BiomeProvider stubBiomes(String name) {
        return new BiomeProvider() {
            @Override
            public int[][][] classifyBiomes(int x, int y, int z, SectionNoiseData data) {
                return new int[4][2][4];
            }
            @Override
            public String backendName() { return name; }
        };
    }

    private static BiomeProvider trackingBiomes(AtomicBoolean closedFlag) {
        return new BiomeProvider() {
            @Override
            public int[][][] classifyBiomes(int x, int y, int z, SectionNoiseData data) {
                return new int[4][2][4];
            }
            @Override
            public String backendName() { return "bio_tracking"; }
            @Override
            public void close() { closedFlag.set(true); }
        };
    }

    /** rate=1.0 (compare every section) with the provided window size. */
    private static ParityConfig alwaysCompare(int window) {
        return new ParityConfig(1.0, window, ParityConfig.LogLevel.QUIET,
                0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.995f);
    }

    /** rate=0.0 (never compare). */
    private static ParityConfig neverCompare() {
        return new ParityConfig(0.0, 1000, ParityConfig.LogLevel.QUIET,
                0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.995f);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Nested
    class FullShadowPipeline {

        @Test
        void identicalSamplers_sectionsAccumulate_errorIsZero() {
            // Both ref and cand produce the same flat value → reporter counts sections
            var ref  = constantSampler(1.0f, "ref");
            var cand = constantSampler(1.0f, "cand");

            var shadow = new ShadowValidatingSampler(ref, cand, alwaysCompare(1000));
            for (int i = 0; i < 10; i++) {
                shadow.sampleSection(i, 0, 0);
            }
            assertEquals(10, shadow.reporter().sectionsInWindow(),
                    "all 10 sections must be counted in the reporter window");
        }

        @Test
        void differentSamplers_reporterCountsSection() {
            // ref=1.0, cand=2.0 − reporter still records the section (errors are non-fatal)
            var ref  = constantSampler(1.0f, "ref");
            var cand = constantSampler(2.0f, "cand");

            var shadow = new ShadowValidatingSampler(ref, cand, alwaysCompare(1000));
            shadow.sampleSection(0, 0, 0);
            assertEquals(1, shadow.reporter().sectionsInWindow());
        }

        @Test
        void shadowSampler_alwaysReturnsReferenceResult() {
            // cand = 99.0 but output must be from ref = 5.0
            var ref  = constantSampler(5.0f, "ref");
            var cand = constantSampler(99.0f, "cand");

            var shadow = new ShadowValidatingSampler(ref, cand, alwaysCompare(1000));
            SectionNoiseData result = shadow.sampleSection(2, 3, 4);

            assertEquals(5.0f, result.flat()[0], 1e-6f,
                    "result must come from reference sampler, not candidate");
            assertEquals(2, result.sectionX());
            assertEquals(3, result.sectionY());
            assertEquals(4, result.sectionZ());
        }

        @Test
        void windowAutoFlush_resetsCounterAtBoundary() {
            // window=5: after the 5th compare the reporter flushes and resets
            var ref  = constantSampler(0.0f, "ref");
            var cand = constantSampler(0.0f, "cand");

            var shadow = new ShadowValidatingSampler(ref, cand, alwaysCompare(5));
            for (int i = 0; i < 5; i++) {
                shadow.sampleSection(i, 0, 0);
            }
            assertEquals(0, shadow.reporter().sectionsInWindow(),
                    "counter must reset to 0 immediately after window flush");

            // 3 more sections in the next window
            for (int i = 0; i < 3; i++) {
                shadow.sampleSection(i, 1, 0);
            }
            assertEquals(3, shadow.reporter().sectionsInWindow());
        }

        @Test
        void samplingRateZero_neverComparesEvenWithDivergentSamplers() {
            // rate=0.0 → reporter.shouldSample() always false; no sections recorded
            var ref  = constantSampler(0.0f, "ref");
            var cand = constantSampler(100.0f, "cand");

            var shadow = new ShadowValidatingSampler(ref, cand, neverCompare());
            for (int i = 0; i < 50; i++) {
                shadow.sampleSection(i, 0, 0);
            }
            assertEquals(0, shadow.reporter().sectionsInWindow(),
                    "no sections should be recorded when samplingRate=0.0");
        }

        @Test
        void close_callsCloseOnBothSamplers() {
            var refClosed  = new AtomicBoolean(false);
            var candClosed = new AtomicBoolean(false);

            var ref  = trackingCloseSampler(0.0f, "ref",  refClosed);
            var cand = trackingCloseSampler(0.0f, "cand", candClosed);

            var shadow = new ShadowValidatingSampler(ref, cand, alwaysCompare(1000));
            shadow.sampleSection(0, 0, 0); // 1 pending section
            shadow.close();

            assertTrue(refClosed.get(),  "reference.close() must be called on shadow.close()");
            assertTrue(candClosed.get(), "candidate.close() must be called on shadow.close()");
        }

        @Test
        void multipleWindowCycles_eachCycleResetsCleanly() {
            var ref  = constantSampler(0.0f, "ref");
            var cand = constantSampler(0.0f, "cand");

            var shadow = new ShadowValidatingSampler(ref, cand, alwaysCompare(3));
            // 3 cycles of 3 sections each
            for (int cycle = 0; cycle < 3; cycle++) {
                for (int i = 0; i < 3; i++) {
                    shadow.sampleSection(i, cycle, 0);
                }
                assertEquals(0, shadow.reporter().sectionsInWindow(),
                        "window must flush at end of cycle " + cycle);
            }
        }
    }

    @Nested
    class UpstreamContextIntegration {

        @Test
        void context_backendName_containsShadowKeyword() {
            var shadow = new ShadowValidatingSampler(
                    constantSampler(0f, "ref"), constantSampler(0f, "cand"), neverCompare());
            try (var ctx = new UpstreamNoiseContext(shadow, stubHeightmap("hm"), stubBiomes("bio"))) {

                String name = ctx.backendName();
                assertTrue(name.contains("shadow"), "backendName must mention shadow, got: " + name);
                assertTrue(name.contains("ref"),    "backendName must mention ref, got: "    + name);
                assertTrue(name.contains("cand"),   "backendName must mention cand, got: "   + name);
            }
        }

        @Test
        void context_fullPipeline_samplesThenClosesPropagatesAll() {
            var refClosed  = new AtomicBoolean(false);
            var candClosed = new AtomicBoolean(false);
            var hmClosed   = new AtomicBoolean(false);
            var bioClosed  = new AtomicBoolean(false);

            var ref    = trackingCloseSampler(1.0f, "ref",  refClosed);
            var cand   = trackingCloseSampler(1.0f, "cand", candClosed);
            var shadow = new ShadowValidatingSampler(ref, cand, alwaysCompare(100));

            try (var ctx = new UpstreamNoiseContext(
                    shadow,
                    trackingHeightmap(hmClosed),
                    trackingBiomes(bioClosed))) {

                // Exercise the full sampling path via context
                for (int sx = 0; sx < 4; sx++) {
                    for (int sy = 0; sy < 3; sy++) {
                        SectionNoiseData data = ctx.noiseSampler().sampleSection(sx, sy, 0);
                        assertNotNull(data);
                        assertEquals(sx, data.sectionX());
                        assertEquals(sy, data.sectionY());
                    }
                }
                assertEquals(12, shadow.reporter().sectionsInWindow(),
                        "reporter must record all 12 sampleSection calls");

            } // ctx.close() called here via try-with-resources

            assertTrue(refClosed.get(),  "reference sampler must be closed via context.close()");
            assertTrue(candClosed.get(), "candidate sampler must be closed via context.close()");
            assertTrue(hmClosed.get(),   "heightmap provider must be closed via context.close()");
            assertTrue(bioClosed.get(),  "biome provider must be closed via context.close()");
        }

        @Test
        void context_rejectsNullNoiseSampler() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UpstreamNoiseContext(null, stubHeightmap("hm"), stubBiomes("bio")));
        }

        @Test
        void context_rejectsNullHeightmapProvider() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UpstreamNoiseContext(constantSampler(0f, "x"), null, stubBiomes("bio")));
        }

        @Test
        void context_rejectsNullBiomeProvider() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UpstreamNoiseContext(constantSampler(0f, "x"), stubHeightmap("hm"), null));
        }

        @Test
        void context_noiseSampler_returnsTheShadowSampler() {
            var shadow = new ShadowValidatingSampler(
                    constantSampler(0f, "ref"), constantSampler(0f, "cand"), neverCompare());
            try (var ctx = new UpstreamNoiseContext(shadow, stubHeightmap("hm"), stubBiomes("bio"))) {
                assertSame(shadow, ctx.noiseSampler(),
                        "noiseSampler() must return the exact sampler passed at construction");
            }
        }

        @Test
        void context_sampleSection_coordinatesPreservedEndToEnd() {
            // Verify section coordinates survive the full context → sampler → SectionNoiseData chain
            var shadow = new ShadowValidatingSampler(
                    constantSampler(7f, "ref"), constantSampler(7f, "cand"), alwaysCompare(100));
            try (var ctx = new UpstreamNoiseContext(shadow, stubHeightmap("hm"), stubBiomes("bio"))) {

                SectionNoiseData result = ctx.noiseSampler().sampleSection(-3, 5, 12);
                assertEquals(-3, result.sectionX());
                assertEquals( 5, result.sectionY());
                assertEquals(12, result.sectionZ());
                assertEquals(7f, result.flat()[0], 1e-6f);
            }
        }
    }

    @Nested
    class BackendKeyResolution {

        @Test
        void shadow_resolves_to_shadow() {
            assertEquals("shadow", NoiseRouterSamplerFactory.resolveBackendKey("shadow"));
        }

        @Test
        void auto_resolves_to_vanilla() {
            assertEquals("vanilla", NoiseRouterSamplerFactory.resolveBackendKey("auto"));
        }

        @Test
        void gpu_resolves_to_gpu() {
            assertEquals("gpu", NoiseRouterSamplerFactory.resolveBackendKey("gpu"));
        }

        @Test
        void vanilla_resolves_to_vanilla() {
            assertEquals("vanilla", NoiseRouterSamplerFactory.resolveBackendKey("vanilla"));
        }

        @Test
        void unknown_string_resolves_to_vanilla() {
            assertEquals("vanilla", NoiseRouterSamplerFactory.resolveBackendKey("bogusBackend"));
        }

        @Test
        void null_resolves_to_vanilla() {
            assertEquals("vanilla", NoiseRouterSamplerFactory.resolveBackendKey(null));
        }
    }
}
