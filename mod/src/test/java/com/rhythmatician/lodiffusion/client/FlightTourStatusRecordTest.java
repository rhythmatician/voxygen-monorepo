package com.rhythmatician.lodiffusion.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rhythmatician.lodiffusion.voxy.VoxyNativeLodStats;
import org.junit.jupiter.api.Test;

class FlightTourStatusRecordTest {
    @Test
    void evidenceRecordCarriesFiveNativeLodCountsInLevelOrder() {
        String json = FlightTourStatusRecord.encode(
                "screenshot_requested", 2, "shot_before", "before", 123, "run-1",
                new VoxyNativeLodStats.Snapshot(1, 2, 3, 4, 5));

        assertTrue(json.contains("\"schemaVersion\":2"));
        assertTrue(json.contains("\"renderSections\":[1,2,3,4,5]"));
        assertTrue(json.contains("\"event\":\"screenshot_requested\""));
    }

    @Test
    void unavailableNativeStatisticsRemainAnExplicitOptionalField() {
        String json = FlightTourStatusRecord.encode(
                "screenshot_done", 2, "shot_after", "tour-waypoint-02-after.png", 124, "run-1", null);

        assertTrue(json.contains("\"renderSections\":null"));
        assertEquals(1, json.lines().count());
    }
}
