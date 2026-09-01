package com.rhythmatician.lodiffusion.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FlightTourCompletionGateTest {
    @Test
    void duplicateFinalCallbacksCompleteAndRequestAfkShutdownExactlyOnce() throws Exception {
        var gate = new FlightTourCompletionGate();
        var request = gate.arm(true);
        var accepted = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);

        try (var callbacks = Executors.newFixedThreadPool(2)) {
            for (int callback = 0; callback < 2; callback++) {
                callbacks.submit(() -> {
                    ready.countDown();
                    release.await();
                    var completion = gate.complete(request);
                    if (completion.accepted()) {
                        accepted.incrementAndGet();
                    }
                    if (completion.shutdownClient()) {
                        shutdowns.incrementAndGet();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            callbacks.shutdown();
            assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, accepted.get());
        assertEquals(1, shutdowns.get());
    }

    @Test
    void manualCompletionNeverRequestsClientShutdown() {
        var gate = new FlightTourCompletionGate();
        var request = gate.arm(false);

        var first = gate.complete(request);
        var duplicate = gate.complete(request);

        assertTrue(first.accepted());
        assertFalse(first.shutdownClient());
        assertFalse(duplicate.accepted());
        assertFalse(duplicate.shutdownClient());
    }
}
