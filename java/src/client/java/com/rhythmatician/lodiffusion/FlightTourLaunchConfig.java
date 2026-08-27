package com.rhythmatician.lodiffusion;

import java.util.Map;
import java.util.Properties;

/** Process launch contract for the optional AFK flight tour. */
record FlightTourLaunchConfig(boolean autoStart, int timeoutTicks, int dwellTicks, String runId) {
    static final String AUTO_START_ENV = "LODIFFUSION_FLIGHT_TOUR_AUTO_START";
    static final String TIMEOUT_TICKS_ENV = "LODIFFUSION_FLIGHT_TOUR_TIMEOUT_TICKS";
    static final String DWELL_TICKS_ENV = "LODIFFUSION_FLIGHT_TOUR_DWELL_TICKS";
    static final String RUN_ID_ENV = "LODIFFUSION_FLIGHT_TOUR_RUN_ID";
    static final String AUTO_START_PROPERTY = "lodiffusion.flightTour.autoStart";
    static final String TIMEOUT_TICKS_PROPERTY = "lodiffusion.flightTour.timeoutTicks";
    static final String DWELL_TICKS_PROPERTY = "lodiffusion.flightTour.dwellTicks";
    static final String RUN_ID_PROPERTY = "lodiffusion.flightTour.runId";
    static final int DEFAULT_TIMEOUT_TICKS = 20_000;
    static final int DEFAULT_MANUAL_DWELL_TICKS = 60;
    static final int DEFAULT_AFK_DWELL_TICKS = 200;

    record ParseResult(FlightTourLaunchConfig config, String invalidTimeout, String invalidDwell) {
    }

    static ParseResult parse(Map<String, String> environment, Properties properties) {
        String autoStartText = authoritativeValue(
                environment.get(AUTO_START_ENV), properties.getProperty(AUTO_START_PROPERTY));
        String timeoutText = authoritativeValue(
                environment.get(TIMEOUT_TICKS_ENV), properties.getProperty(TIMEOUT_TICKS_PROPERTY));
        String dwellText = authoritativeValue(
                environment.get(DWELL_TICKS_ENV), properties.getProperty(DWELL_TICKS_PROPERTY));
        String runId = authoritativeValue(
                environment.get(RUN_ID_ENV), properties.getProperty(RUN_ID_PROPERTY));

        boolean autoStart = "true".equalsIgnoreCase(autoStartText);
        int timeoutTicks = DEFAULT_TIMEOUT_TICKS;
        String invalidTimeout = null;
        if (timeoutText != null) {
            try {
                timeoutTicks = Math.max(1, Integer.parseInt(timeoutText));
            } catch (NumberFormatException ignored) {
                invalidTimeout = timeoutText;
            }
        }
        int dwellTicks = autoStart ? DEFAULT_AFK_DWELL_TICKS : DEFAULT_MANUAL_DWELL_TICKS;
        String invalidDwell = null;
        if (dwellText != null) {
            try {
                dwellTicks = Math.max(1, Integer.parseInt(dwellText));
            } catch (NumberFormatException ignored) {
                invalidDwell = dwellText;
            }
        }
        return new ParseResult(new FlightTourLaunchConfig(
                autoStart, timeoutTicks, dwellTicks, runId == null ? "" : runId),
                invalidTimeout, invalidDwell);
    }

    private static String authoritativeValue(String environmentValue, String propertyValue) {
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue.trim();
        }
        return null;
    }
}
