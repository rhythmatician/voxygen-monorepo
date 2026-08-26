package com.rhythmatician.lodiffusion.client;

import com.rhythmatician.lodiffusion.voxy.VoxyNativeLodStats;

/** Stable machine-readable evidence record emitted only at flight-tour lifecycle events. */
final class FlightTourStatusRecord {
    private FlightTourStatusRecord() {
    }

    static String encode(
            String event,
            int waypoint,
            String phase,
            String detail,
            int ticks,
            String runId,
            VoxyNativeLodStats.Snapshot renderSections) {
        String sections = renderSections == null ? "null" : renderSections.asJsonArray();
        return String.format(
                "{\"schemaVersion\":2,\"event\":\"%s\",\"waypoint\":%d,\"phase\":\"%s\",\"detail\":\"%s\",\"ticks\":%d,\"runId\":\"%s\",\"renderSections\":%s}%n",
                jsonEscape(event),
                waypoint,
                jsonEscape(phase),
                jsonEscape(detail),
                ticks,
                jsonEscape(runId),
                sections);
    }

    private static String jsonEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
