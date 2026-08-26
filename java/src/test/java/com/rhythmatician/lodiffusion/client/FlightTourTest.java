package com.rhythmatician.lodiffusion.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlightTourTest {
    @BeforeEach
    void resetTour() {
        FlightTour.resetForTest();
    }

    @Test
    void tourKeepsTheConfiguredCorridorAndThreeSecondObservationDwell() {
        assertEquals(5, FlightTour.waypointCountForTest());
        assertEquals(60, FlightTour.dwellTicksForTest());
        assertArrayEquals(new int[] {0, 100, 100}, FlightTour.waypointForTest(0));
        assertArrayEquals(new int[] {0, 100, 950}, FlightTour.waypointForTest(4));
    }

    @Test
    void phaseFlowTeleportsThroughAllWaypointsInOrder() {
        FlightTour.resetForTest();
        FlightTour.configureAutoStart(true, 20_000, 60);
        FlightTour.start();
        FlightTour.tickForTest(500);
        assertFalse(FlightTour.isAutoStartEnabled());

        List<String> events = FlightTour.testEventsForTest();
        assertEquals(25, events.size());

        for (int i = 0; i < FlightTour.waypointCountForTest(); i++) {
            int waypointIndex = i + 1;
            int[] waypoint = FlightTour.waypointForTest(i);

            int expectedTeleportEvent = (i * 5);
            int expectedBeforeCameraLock = expectedTeleportEvent + 1;
            int expectedBeforeShot = expectedTeleportEvent + 2;
            int expectedAfterCameraLock = expectedTeleportEvent + 3;
            int expectedAfterShot = expectedTeleportEvent + 4;

            assertEquals("teleport:" + waypoint[0] + "," + waypoint[1] + "," + waypoint[2] + ",0.0,0.0", events.get(expectedTeleportEvent));
            assertEquals("camera-lock:0.0,0.0", events.get(expectedBeforeCameraLock));
            assertEquals("screenshot:tour-waypoint-" + String.format("%02d", waypointIndex) + "-before.png", events.get(expectedBeforeShot));
            assertEquals("camera-lock:0.0,0.0", events.get(expectedAfterCameraLock));
            assertEquals("screenshot:tour-waypoint-" + String.format("%02d", waypointIndex) + "-after.png", events.get(expectedAfterShot));
        }

        String finalStatus = events.get(events.size() - 1);
        assertTrue(finalStatus.startsWith("screenshot:tour-waypoint-05-after.png"));
    }

    @Test
    void lifecycleUsesScenarioWaypointsPoseAndEvidencePrefix() {
        FlightTour.resetForTest(new FlightTourScenario(
                "synthetic",
                "Synthetic contract tour",
                "test:dimension",
                "SyntheticWorld",
                "synthetic-template",
                2,
                "synthetic-evidence",
                List.of(
                        new FlightTourScenario.Waypoint(11, 22, 33, 45.0F, -10.0F),
                        new FlightTourScenario.Waypoint(-4, 5, -6, 90.0F, 15.0F))));
        FlightTour.configureAutoStart(false, 100, 2);
        FlightTour.start();
        FlightTour.tickForTest(100);

        assertEquals(List.of(
                "teleport:11,22,33,45.0,-10.0",
                "camera-lock:45.0,-10.0",
                "screenshot:synthetic-evidence-01-before.png",
                "camera-lock:45.0,-10.0",
                "screenshot:synthetic-evidence-01-after.png",
                "teleport:-4,5,-6,90.0,15.0",
                "camera-lock:90.0,15.0",
                "screenshot:synthetic-evidence-02-before.png",
                "camera-lock:90.0,15.0",
                "screenshot:synthetic-evidence-02-after.png"),
                FlightTour.testEventsForTest());
        assertEquals(1, FlightTour.testStatusesForTest().stream()
                .filter("complete:all_waypoints"::equals)
                .count());
    }

    @Test
    void autoStartIsDisabledAfterTourCompletion() {
        FlightTour.resetForTest();
        FlightTour.configureAutoStart(true, 20_000, 60);
        FlightTour.start();
        FlightTour.tickForTest(500);

        assertFalse(FlightTour.isAutoStartEnabled());
        assertTrue(FlightTour.shutdownRequestedForTest());
        assertEquals(1, FlightTour.testStatusesForTest().stream()
                .filter("start:ready"::equals)
                .count());
        assertEquals(1, FlightTour.testStatusesForTest().stream()
                .filter("complete:all_waypoints"::equals)
                .count());
    }

    @Test
    void manuallyStartedTourCompletesWithoutClosingTheClient() {
        FlightTour.resetForTest();
        FlightTour.configureAutoStart(false, 20_000, 60);
        FlightTour.start();
        FlightTour.tickForTest(500);

        assertFalse(FlightTour.isActive());
        assertFalse(FlightTour.isAutoStartEnabled());
        assertFalse(FlightTour.shutdownRequestedForTest());
        assertEquals(1, FlightTour.testStatusesForTest().stream()
                .filter("complete:all_waypoints"::equals)
                .count());
    }

    @Test
    void autoStartIsDisabledAfterTimeoutFailure() {
        FlightTour.resetForTest();
        FlightTour.configureAutoStart(true, 1, 60);
        FlightTour.start();
        FlightTour.tickForTest(10);

        assertFalse(FlightTour.isAutoStartEnabled());
        assertTrue(FlightTour.shutdownRequestedForTest());
        assertEquals(0, FlightTour.testStatusesForTest().stream()
                .filter("complete:all_waypoints"::equals)
                .count());
        assertEquals(1, FlightTour.testStatusesForTest().stream()
                .filter("failed:timeout"::equals)
                .count());
    }

    @Test
    void manualTimeoutFailureDoesNotCloseTheClient() {
        FlightTour.resetForTest();
        FlightTour.configureAutoStart(false, 1, 60);
        FlightTour.start();
        FlightTour.tickForTest(10);

        assertFalse(FlightTour.isAutoStartEnabled());
        assertFalse(FlightTour.shutdownRequestedForTest());
    }

    @Test
    void stoppedTourReleasesTheCameraForManualControl() {
        FlightTour.resetForTest();
        FlightTour.configureAutoStart(false, 20_000, 60);
        FlightTour.start();
        FlightTour.stop();
        FlightTour.tickForTest(10);

        assertTrue(FlightTour.testEventsForTest().isEmpty());
    }
}
