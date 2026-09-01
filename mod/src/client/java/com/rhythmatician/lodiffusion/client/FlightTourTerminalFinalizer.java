package com.rhythmatician.lodiffusion.client;

import java.util.concurrent.Executor;

/** Keeps screenshot callbacks separate from client-thread terminal work. */
final class FlightTourTerminalFinalizer {
    private FlightTourTerminalFinalizer() {
    }

    static void dispatch(Executor clientExecutor, Runnable terminalWork) {
        clientExecutor.execute(terminalWork);
    }

    /**
     * Runs terminal actions in contract order. Optional feedback may fail, but
     * it cannot prevent the shutdown action from running.
     */
    static RuntimeException finish(
            Runnable deactivate,
            Runnable publishCompletion,
            Runnable optionalFeedback,
            Runnable shutdown) {
        deactivate.run();
        publishCompletion.run();
        RuntimeException feedbackFailure = null;
        try {
            optionalFeedback.run();
        } catch (RuntimeException failure) {
            feedbackFailure = failure;
        } finally {
            shutdown.run();
        }
        return feedbackFailure;
    }
}
