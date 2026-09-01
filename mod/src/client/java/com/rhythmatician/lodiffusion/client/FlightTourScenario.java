package com.rhythmatician.lodiffusion.client;

import java.util.List;
import java.util.Objects;

/** Immutable evidence route consumed by the FlightTour lifecycle. */
record FlightTourScenario(
        String id,
        String name,
        String expectedDimensionId,
        String expectedWorldName,
        String templateIdentity,
        int defaultDwellTicks,
        String evidencePrefix,
        List<Waypoint> waypoints) {

    FlightTourScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expectedDimensionId, "expectedDimensionId");
        Objects.requireNonNull(expectedWorldName, "expectedWorldName");
        Objects.requireNonNull(templateIdentity, "templateIdentity");
        Objects.requireNonNull(evidencePrefix, "evidencePrefix");
        waypoints = List.copyOf(waypoints);
        if (defaultDwellTicks < 1 || waypoints.isEmpty()) {
            throw new IllegalArgumentException("A flight scenario needs a positive dwell and at least one waypoint");
        }
    }

    static FlightTourScenario endRefinement() {
        return new FlightTourScenario(
                "end-refinement-corridor",
                "End refinement corridor",
                "minecraft:the_end",
                "FlightTest",
                "flight-template",
                60,
                "tour-waypoint",
                List.of(
                        new Waypoint(0, 100, 100, 0.0F, 0.0F),
                        new Waypoint(0, 100, 600, 0.0F, 0.0F),
                        new Waypoint(0, 100, 800, 0.0F, 0.0F),
                        new Waypoint(0, 100, 900, 0.0F, 0.0F),
                        new Waypoint(0, 100, 950, 0.0F, 0.0F)));
    }

    record Waypoint(int x, int y, int z, float yaw, float pitch) {
    }
}
