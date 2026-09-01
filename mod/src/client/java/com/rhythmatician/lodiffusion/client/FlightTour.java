package com.rhythmatician.lodiffusion.client;

import com.rhythmatician.lodiffusion.HelloTerrainMod;
import com.rhythmatician.lodiffusion.voxy.VoxyNativeLodStats;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-only, opt-in or auto-start tour for repeatable Stage 2 flight verification.
 */
public final class FlightTour {
    private static final String STATUS_FILE_NAME = "flight-tour-status.jsonl";
    private static final String SHUTDOWN_SUCCESS_EVENT = "all_waypoints";
    private static final FlightTourScenario END_SCENARIO = FlightTourScenario.endRefinement();

    private enum Phase { TELEPORT, AWAIT_FIRST_FRAME, SHOT_BEFORE, DWELL, SHOT_AFTER }

    private static volatile boolean active;
    private static int waypointIndex;
    private static int ticksInPhase;
    private static int ticksTotal;
    private static Phase phase = Phase.TELEPORT;
    private static volatile boolean renderedFrameObserved;
    private static volatile boolean autoStartEnabled;
    private static int timeoutTicks = 20_000;
    private static FlightTourScenario scenario = END_SCENARIO;
    private static int dwellTicks = scenario.defaultDwellTicks();
    private static String runId = "";
    private static Path statusFilePath;
    private static boolean machineReadableEnabled = true;
    private static boolean testMode;
    private static volatile boolean closeClientAtTerminal;
    private static final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private static final FlightTourCompletionGate finalCompletion = new FlightTourCompletionGate();
    private static final List<String> testEvents = new ArrayList<>();
    private static final List<String> testStatuses = new ArrayList<>();

    private FlightTour() {
    }

    public static void configureAutoStart(boolean enabled, int maxTicks, int configuredDwellTicks) {
        configureAutoStart(enabled, maxTicks, configuredDwellTicks, "");
    }

    public static void configureAutoStart(
            boolean enabled, int maxTicks, int configuredDwellTicks, String configuredRunId) {
        autoStartEnabled = enabled;
        if (maxTicks > 0) {
            timeoutTicks = maxTicks;
        }
        if (configuredDwellTicks > 0) {
            dwellTicks = configuredDwellTicks;
        }
        runId = configuredRunId == null ? "" : configuredRunId;
        statusFilePath = null;
        renderedFrameObserved = false;
        finalCompletion.cancel();
        shutdownRequested.set(false);
    }

    public static void start() {
        if (active) return;
        active = true;
        waypointIndex = 0;
        ticksInPhase = 0;
        ticksTotal = 0;
        closeClientAtTerminal = autoStartEnabled;
        shutdownRequested.set(false);
        renderedFrameObserved = false;
        finalCompletion.cancel();
        enter(Phase.TELEPORT);
        log("Tour started — " + scenario.waypoints().size() + " waypoints");
        emitStatus("start", "ready");
    }

    public static void stop() {
        if (!active) return;
        active = false;
        autoStartEnabled = false;
        finalCompletion.cancel();
        log("Tour stopped at waypoint " + (waypointIndex + 1) + "/" + scenario.waypoints().size());
        emitStatus("stop", "manual");
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Records that the client has rendered at least one world frame. The tour
     * gates waypoint 1's baseline capture on this so the "before" screenshot
     * never records the loading screen.
     */
    public static void noteRenderedFrame() {
        renderedFrameObserved = true;
    }

    public static boolean isAutoStartEnabled() {
        return autoStartEnabled;
    }

    /** Called from the existing end-of-client-tick callback. */
    public static void tick(MinecraftClient client) {
        if (active || autoStartEnabled) {
            if (!canRun(client)) {
                ticksTotal++;
                checkTimeout(client, false);
                return;
            }

            if (statusFilePath == null && client != null && client.runDirectory != null) {
                statusFilePath = client.runDirectory.toPath().resolve(STATUS_FILE_NAME);
            }

            if (!active && autoStartEnabled) {
                start();
            }

            if (active) {
                runTick(client);
            }
        }
    }

    static void tickForTest(int maxTicks) {
        tickForTest(maxTicks, true);
    }

    /** Test hook; when {@code firstFrameObserved} is false the tour stalls in AWAIT_FIRST_FRAME. */
    static void tickForTest(int maxTicks, boolean firstFrameObserved) {
        testMode = true;
        machineReadableEnabled = false;
        renderedFrameObserved = renderedFrameObserved || firstFrameObserved;
        for (int i = 0; i < maxTicks && active; i++) {
            runTick(null);
            if (!active) {
                break;
            }
        }
    }

    static void resetForTest() {
        resetForTest(END_SCENARIO);
    }

    static void resetForTest(FlightTourScenario testScenario) {
        active = false;
        waypointIndex = 0;
        ticksInPhase = 0;
        ticksTotal = 0;
        scenario = testScenario;
        dwellTicks = scenario.defaultDwellTicks();
        phase = Phase.TELEPORT;
        testMode = true;
        machineReadableEnabled = false;
        closeClientAtTerminal = false;
        shutdownRequested.set(false);
        renderedFrameObserved = false;
        finalCompletion.cancel();
        statusFilePath = null;
        testEvents.clear();
        testStatuses.clear();
    }

    static int waypointCountForTest() {
        return scenario.waypoints().size();
    }

    static int dwellTicksForTest() {
        return dwellTicks;
    }

    static int[] waypointForTest(int index) {
        FlightTourScenario.Waypoint waypoint = scenario.waypoints().get(index);
        return new int[] {waypoint.x(), waypoint.y(), waypoint.z()};
    }

    static List<String> testEventsForTest() {
        return List.copyOf(testEvents);
    }

    static List<String> testStatusesForTest() {
        return List.copyOf(testStatuses);
    }

    static boolean shutdownRequestedForTest() {
        return shutdownRequested.get();
    }

    private static void runTick(MinecraftClient client) {
        if (!active) {
            return;
        }

        ticksTotal++;
        ticksInPhase++;

        switch (phase) {
            case TELEPORT -> {
                if (ticksInPhase == 1) {
                    FlightTourScenario.Waypoint waypoint = scenario.waypoints().get(waypointIndex);
                    requestTeleport(client, waypoint);
                }
                if (waypointIndex == 0 && !renderedFrameObserved) {
                    // The first waypoint's baseline must capture a rendered
                    // world frame, not the loading screen. Wait for the
                    // harness to observe one before shooting.
                    enter(Phase.AWAIT_FIRST_FRAME);
                    break;
                }
                enter(Phase.SHOT_BEFORE);
            }
            case AWAIT_FIRST_FRAME -> {
                if (renderedFrameObserved) {
                    enter(Phase.SHOT_BEFORE);
                }
            }
            case SHOT_BEFORE -> {
                lockCamera(client);
                requestScreenshot(client, waypointScreenshotName(false), false);
                emitStatus("screenshot_requested", "before");
                enter(Phase.DWELL);
            }
            case DWELL -> {
                if (ticksInPhase >= dwellTicks) {
                    enter(Phase.SHOT_AFTER);
                }
            }
            case SHOT_AFTER -> {
                if (ticksInPhase == 1) {
                    boolean isLastWaypoint = waypointIndex == scenario.waypoints().size() - 1;
                    lockCamera(client);
                    requestScreenshot(client, waypointScreenshotName(true), isLastWaypoint);
                    emitStatus("screenshot_requested", "after");
                }
                if (waypointIndex < scenario.waypoints().size() - 1) {
                    waypointIndex++;
                    enter(Phase.TELEPORT);
                }
            }
        }

        checkTimeout(client, true);
    }

    private static void checkTimeout(MinecraftClient client, boolean running) {
        if (!running || !active) {
            return;
        }
        if (ticksTotal < timeoutTicks) {
            return;
        }
        active = false;
        autoStartEnabled = false;
        finalCompletion.cancel();
        log("Tour timeout after " + ticksTotal + " ticks");
        emitStatus("failed", "timeout");
        if (statusFilePath != null) {
            emitStatus("complete", "failure");
        }
        if (closeClientAtTerminal) {
            requestShutdown(client);
        }
    }

    private static void requestTeleport(MinecraftClient client, FlightTourScenario.Waypoint waypoint) {
        String command = "tp @s " + waypoint.x() + " " + waypoint.y() + " " + waypoint.z()
                + " " + waypoint.yaw() + " " + waypoint.pitch();
        if (testMode) {
            testEvents.add("teleport:" + waypoint.x() + "," + waypoint.y() + "," + waypoint.z()
                    + "," + waypoint.yaw() + "," + waypoint.pitch());
        } else if (client != null) {
            var p = client.player;
            if (p != null && p.networkHandler != null) {
                p.networkHandler.sendChatCommand(command);
            }
        }
        log("waypoint " + (waypointIndex + 1) + "/" + scenario.waypoints().size()
                + " -> " + waypoint.x() + " " + waypoint.y() + " " + waypoint.z());
        emitStatus("teleport_requested", command);
    }

    private static void lockCamera(MinecraftClient client) {
        FlightTourScenario.Waypoint waypoint = scenario.waypoints().get(waypointIndex);
        if (testMode) {
            testEvents.add("camera-lock:" + waypoint.yaw() + "," + waypoint.pitch());
            return;
        }
        if (client == null) {
            return;
        }
        var p2 = client.player;
        if (p2 == null) {
            return;
        }
        p2.setYaw(waypoint.yaw());
        p2.setPitch(waypoint.pitch());
    }

    private static void requestScreenshot(MinecraftClient client, String name, boolean isFinalWaypointAfterShot) {
        if (testMode) {
            testEvents.add("screenshot:" + name);
            FlightTourCompletionGate.Request request = isFinalWaypointAfterShot
                    ? finalCompletion.arm(closeClientAtTerminal)
                    : null;
            screenshotCompleted(client, name, request);
            return;
        }

        if (client == null || client.getFramebuffer() == null) {
            return;
        }

        if (statusFilePath == null && client.runDirectory != null) {
            statusFilePath = client.runDirectory.toPath().resolve(STATUS_FILE_NAME);
        }

        FlightTourCompletionGate.Request request = isFinalWaypointAfterShot
                ? finalCompletion.arm(closeClientAtTerminal)
                : null;
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(), 1, ignored -> {
            screenshotCompleted(client, name, request);
        });
    }

    private static void screenshotCompleted(
            MinecraftClient client, String name, FlightTourCompletionGate.Request finalRequest) {
        if (finalRequest == null) {
            emitStatus("screenshot_done", name);
            return;
        }

        FlightTourCompletionGate.Completion completion = finalCompletion.complete(finalRequest);
        if (!completion.accepted()) {
            return;
        }

        emitStatus("screenshot_done", name);
        Runnable terminalWork = () -> finalizeSuccessfulTour(client, completion.shutdownClient());
        if (testMode || client == null) {
            terminalWork.run();
        } else {
            FlightTourTerminalFinalizer.dispatch(client::execute, terminalWork);
        }
    }

    private static void finalizeSuccessfulTour(MinecraftClient client, boolean closeClient) {
        RuntimeException feedbackFailure = FlightTourTerminalFinalizer.finish(
                () -> {
                    autoStartEnabled = false;
                    active = false;
                },
                () -> emitStatus("complete", SHUTDOWN_SUCCESS_EVENT),
                () -> log("Tour complete — screenshots are in run/screenshots/"),
                closeClient ? () -> requestShutdown(client) : () -> { });
        if (feedbackFailure != null) {
            HelloTerrainMod.LOGGER.warn("[FlightTour] Completion feedback failed after status publication",
                    feedbackFailure);
        }
    }

    private static String waypointScreenshotName(boolean after) {
        return String.format(
                scenario.evidencePrefix() + "-%02d-%s.png",
                waypointIndex + 1,
                after ? "after" : "before");
    }

    private static void enter(Phase next) {
        phase = next;
        ticksInPhase = 0;
    }

    private static boolean canRun(MinecraftClient client) {
        if (!autoStartEnabled && !active) {
            return false;
        }

        if (client == null) {
            return false;
        }

        var w = client.world;
        var pl = client.player;
        if (w == null || pl == null || pl.networkHandler == null) {
            return false;
        }

        return isEndClientReady(client);
    }

    private static boolean isEndClientReady(MinecraftClient client) {
        var w2 = client.world;
        return w2 != null
                && w2.getRegistryKey().getValue().toString().equals(scenario.expectedDimensionId())
                && client.currentScreen == null;
    }

    private static void emitStatus(String event, String detail) {
        if (testMode) {
            testStatuses.add(event + ":" + detail);
        }
        if (!machineReadableEnabled || statusFilePath == null) {
            return;
        }

        String record = FlightTourStatusRecord.encode(
                event,
                waypointIndex + 1,
                phase.name().toLowerCase(),
                detail,
                ticksTotal,
                runId,
                VoxyNativeLodStats.snapshot().orElse(null));
        try {
            Files.createDirectories(statusFilePath.getParent());
            Files.writeString(statusFilePath, record, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            HelloTerrainMod.LOGGER.warn("Failed to write flight-tour status file: {}", statusFilePath);
        }
    }

    private static void requestShutdown(MinecraftClient client) {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        if (client == null) {
            return;
        }
        try {
            Method stop = client.getClass().getMethod("scheduleStop");
            stop.invoke(client);
            return;
        } catch (Exception ignored) {
            // Fall through to no-op if the method name differs on this MC mapping.
        }

        try {
            Method stop = client.getClass().getMethod("stop");
            stop.invoke(client);
        } catch (Exception ignored) {
            log("Unable to request client shutdown programmatically");
        }
    }

    private static void log(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Tour] " + message), false);
        }
        HelloTerrainMod.LOGGER.info("[FlightTour] {}", message);
    }
}
