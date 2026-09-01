package com.rhythmatician.lodiffusion.world.noise;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link ParityConfig} — thresholds, defaults, and field mapping.
 */
class ParityConfigTest {

    // ── Defaults ─────────────────────────────────────────────────────

    @Test
    void defaults_returnsNonNullWithSaneValues() {
        ParityConfig cfg = ParityConfig.defaults();
        assertNotNull(cfg);
        assertTrue(cfg.samplingRate() > 0.0 && cfg.samplingRate() <= 1.0,
                "samplingRate should be in (0, 1]");
        assertTrue(cfg.aggregationWindow() > 0,
                "aggregationWindow must be positive");
        assertNotNull(cfg.logLevel());
    }

    @Test
    void defaults_densitySignAgreementMin_isHigh() {
        ParityConfig cfg = ParityConfig.defaults();
        assertTrue(cfg.densitySignAgreementMin() >= 0.99f,
                "Default density sign agreement should be ≥ 0.99");
    }

    // ── thresholdFor coverage ────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(RouterField.class)
    void thresholdFor_neverThrowsForAnyField(RouterField field) {
        ParityConfig cfg = ParityConfig.defaults();
        float t = cfg.thresholdFor(field);
        assertTrue(t > 0f, "Threshold for " + field + " must be positive");
    }

    @Test
    void thresholdFor_climateFields_returnClimateThreshold() {
        ParityConfig cfg = new ParityConfig(
                0.5, 100, ParityConfig.LogLevel.SUMMARY,
                0.01f, 0.05f, 0.10f, 0.05f, 0.05f, 0.99f);

        assertEquals(0.01f, cfg.thresholdFor(RouterField.TEMPERATURE));
        assertEquals(0.01f, cfg.thresholdFor(RouterField.VEGETATION));
        assertEquals(0.01f, cfg.thresholdFor(RouterField.CONTINENTS));
        assertEquals(0.01f, cfg.thresholdFor(RouterField.EROSION));
        assertEquals(0.01f, cfg.thresholdFor(RouterField.RIDGES));
    }

    @Test
    void thresholdFor_depthFields_returnDepthThreshold() {
        ParityConfig cfg = new ParityConfig(
                0.5, 100, ParityConfig.LogLevel.SUMMARY,
                0.01f, 0.77f, 0.10f, 0.05f, 0.05f, 0.99f);

        assertEquals(0.77f, cfg.thresholdFor(RouterField.DEPTH));
        assertEquals(0.77f, cfg.thresholdFor(RouterField.PRELIMINARY_SURFACE_LEVEL));
    }

    @Test
    void thresholdFor_densityField_returnDensityThreshold() {
        ParityConfig cfg = new ParityConfig(
                0.5, 100, ParityConfig.LogLevel.SUMMARY,
                0.01f, 0.05f, 0.42f, 0.05f, 0.05f, 0.99f);

        assertEquals(0.42f, cfg.thresholdFor(RouterField.FINAL_DENSITY));
    }

    @Test
    void thresholdFor_aquiferFields_returnAquiferThreshold() {
        ParityConfig cfg = new ParityConfig(
                0.5, 100, ParityConfig.LogLevel.SUMMARY,
                0.01f, 0.05f, 0.10f, 0.33f, 0.05f, 0.99f);

        assertEquals(0.33f, cfg.thresholdFor(RouterField.BARRIER));
        assertEquals(0.33f, cfg.thresholdFor(RouterField.FLUID_LEVEL_FLOODEDNESS));
        assertEquals(0.33f, cfg.thresholdFor(RouterField.FLUID_LEVEL_SPREAD));
        assertEquals(0.33f, cfg.thresholdFor(RouterField.LAVA));
    }

    @Test
    void thresholdFor_oreFields_returnOreThreshold() {
        ParityConfig cfg = new ParityConfig(
                0.5, 100, ParityConfig.LogLevel.SUMMARY,
                0.01f, 0.05f, 0.10f, 0.05f, 0.88f, 0.99f);

        assertEquals(0.88f, cfg.thresholdFor(RouterField.VEIN_TOGGLE));
        assertEquals(0.88f, cfg.thresholdFor(RouterField.VEIN_RIDGED));
        assertEquals(0.88f, cfg.thresholdFor(RouterField.VEIN_GAP));
    }

    // ── LogLevel enum ────────────────────────────────────────────────

    @Test
    void logLevel_allThreeValuesExist() {
        assertEquals(3, ParityConfig.LogLevel.values().length);
        assertNotNull(ParityConfig.LogLevel.valueOf("PER_SECTION"));
        assertNotNull(ParityConfig.LogLevel.valueOf("SUMMARY"));
        assertNotNull(ParityConfig.LogLevel.valueOf("QUIET"));
    }
}
