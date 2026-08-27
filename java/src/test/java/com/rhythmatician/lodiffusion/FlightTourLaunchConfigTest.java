package com.rhythmatician.lodiffusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class FlightTourLaunchConfigTest {
    @Test
    void processEnvironmentIsAuthoritativeOverJvmProperties() {
        var properties = new Properties();
        properties.setProperty(FlightTourLaunchConfig.AUTO_START_PROPERTY, "true");
        properties.setProperty(FlightTourLaunchConfig.TIMEOUT_TICKS_PROPERTY, "111");

        var result = FlightTourLaunchConfig.parse(Map.of(
                FlightTourLaunchConfig.AUTO_START_ENV, "false",
                FlightTourLaunchConfig.TIMEOUT_TICKS_ENV, "24000"), properties);

        assertFalse(result.config().autoStart());
        assertEquals(24_000, result.config().timeoutTicks());
        assertEquals(FlightTourLaunchConfig.DEFAULT_MANUAL_DWELL_TICKS, result.config().dwellTicks());
        assertNull(result.invalidTimeout());
    }

    @Test
    void jvmPropertiesRemainSupportedWhenEnvironmentIsAbsent() {
        var properties = new Properties();
        properties.setProperty(FlightTourLaunchConfig.AUTO_START_PROPERTY, "TRUE");
        properties.setProperty(FlightTourLaunchConfig.TIMEOUT_TICKS_PROPERTY, "321");

        var result = FlightTourLaunchConfig.parse(Map.of(), properties);

        assertTrue(result.config().autoStart());
        assertEquals(321, result.config().timeoutTicks());
        assertEquals(FlightTourLaunchConfig.DEFAULT_AFK_DWELL_TICKS, result.config().dwellTicks());
    }

    @Test
    void invalidAuthoritativeTimeoutUsesDefaultAndReportsTheBadValue() {
        var properties = new Properties();
        properties.setProperty(FlightTourLaunchConfig.TIMEOUT_TICKS_PROPERTY, "444");

        var result = FlightTourLaunchConfig.parse(
                Map.of(FlightTourLaunchConfig.TIMEOUT_TICKS_ENV, "not-a-number"), properties);

        assertEquals(FlightTourLaunchConfig.DEFAULT_TIMEOUT_TICKS, result.config().timeoutTicks());
        assertEquals("not-a-number", result.invalidTimeout());
    }

    @Test
    void nonPositiveTimeoutIsClampedToOneTick() {
        var result = FlightTourLaunchConfig.parse(
                Map.of(FlightTourLaunchConfig.TIMEOUT_TICKS_ENV, "0"), new Properties());

        assertEquals(1, result.config().timeoutTicks());
    }

    @Test
    void explicitDwellOverridesTheAfkDefault() {
        var result = FlightTourLaunchConfig.parse(Map.of(
                FlightTourLaunchConfig.AUTO_START_ENV, "true",
                FlightTourLaunchConfig.DWELL_TICKS_ENV, "137"), new Properties());

        assertEquals(137, result.config().dwellTicks());
    }

    @Test
    void gradleSuppliedJvmPropertiesResolveTheAfkLaunchContract() {
        var properties = new Properties();
        properties.setProperty(FlightTourLaunchConfig.AUTO_START_PROPERTY, "true");
        properties.setProperty(FlightTourLaunchConfig.TIMEOUT_TICKS_PROPERTY, "24000");
        properties.setProperty(FlightTourLaunchConfig.DWELL_TICKS_PROPERTY, "200");
        properties.setProperty(FlightTourLaunchConfig.RUN_ID_PROPERTY, "run-23-46");

        var result = FlightTourLaunchConfig.parse(Map.of(), properties);

        assertTrue(result.config().autoStart());
        assertEquals(24_000, result.config().timeoutTicks());
        assertEquals(200, result.config().dwellTicks());
        assertEquals("run-23-46", result.config().runId());
    }

    @Test
    void environmentRunIdIsAuthoritativeAndTrimmed() {
        var properties = new Properties();
        properties.setProperty(FlightTourLaunchConfig.RUN_ID_PROPERTY, "stale-property");

        var result = FlightTourLaunchConfig.parse(
                Map.of(FlightTourLaunchConfig.RUN_ID_ENV, "  current-run  "), properties);

        assertEquals("current-run", result.config().runId());
    }

    @Test
    void manualLaunchWithoutRunIdKeepsAnEmptyCorrelationId() {
        var result = FlightTourLaunchConfig.parse(Map.of(), new Properties());

        assertEquals("", result.config().runId());
    }
}
