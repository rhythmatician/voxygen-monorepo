package com.rhythmatician.lodiffusion.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlightTourTerminalFinalizerTest {
    @Test
    void dispatchDefersTerminalWorkToTheProvidedClientExecutor() {
        var queued = new ArrayDeque<Runnable>();
        var finalized = new boolean[] {false};

        FlightTourTerminalFinalizer.dispatch(queued::add, () -> finalized[0] = true);

        assertFalse(finalized[0], "screenshot callback must not finalize inline");
        assertEquals(1, queued.size());
        queued.remove().run();
        assertTrue(finalized[0]);
    }

    @Test
    void completionMarkerPrecedesFeedbackAndFeedbackFailureCannotBlockShutdown() {
        List<String> order = new ArrayList<>();

        RuntimeException failure = FlightTourTerminalFinalizer.finish(
                () -> order.add("deactivate"),
                () -> order.add("complete/all_waypoints"),
                () -> {
                    order.add("feedback");
                    throw new IllegalStateException("chat unavailable");
                },
                () -> order.add("shutdown"));

        assertEquals(List.of("deactivate", "complete/all_waypoints", "feedback", "shutdown"), order);
        assertNotNull(failure);
        assertEquals("chat unavailable", failure.getMessage());
    }
}
