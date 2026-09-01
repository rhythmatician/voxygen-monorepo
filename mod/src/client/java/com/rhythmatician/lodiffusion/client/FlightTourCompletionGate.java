package com.rhythmatician.lodiffusion.client;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One pending final screenshot request, completed at most once. */
final class FlightTourCompletionGate {
    record Request(long id, boolean shutdownClient) {
    }

    record Completion(boolean accepted, boolean shutdownClient) {
        private static final Completion DUPLICATE = new Completion(false, false);
    }

    private final AtomicLong nextId = new AtomicLong();
    private final AtomicReference<Request> pending = new AtomicReference<>();

    Request arm(boolean shutdownClient) {
        Request request = new Request(nextId.incrementAndGet(), shutdownClient);
        if (!pending.compareAndSet(null, request)) {
            throw new IllegalStateException("a final screenshot request is already pending");
        }
        return request;
    }

    Completion complete(Request request) {
        if (request != null && pending.compareAndSet(request, null)) {
            return new Completion(true, request.shutdownClient());
        }
        return Completion.DUPLICATE;
    }

    void cancel() {
        pending.set(null);
    }
}
