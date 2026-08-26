package com.rhythmatician.lodiffusion.client;

import com.rhythmatician.lodiffusion.HelloTerrainMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.world.World;

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
    private static final int DEFAULT_DWELL_TICKS = 60;
    private static final float TOUR_YAW = 0.0F;
    private static final float TOUR_PITCH = 0.0F;
    private static final String STATUS_FILE_NAME = "flight-tour-status.jsonl";
    private static final String SHUTDOWN_SUCCESS_EVENT = "all_waypoints";
    private static final int[][] WAYPOINTS = {
            {0, 96, 512}, {0, 96, 768}, {0, 96, 896},
            {0, 96, 960}, {0, 96, 992}, {0, 96, 1008},
    };

    private enum Phase { TELEPORT, SHOT_BEFORE, DWELL, SHOT_AFTER }

    private static volatile boolean active;
    private static int waypointIndex;
    private static int ticksInPhase;
    private static int ticksTotal;
    private static Phase phase = Phase.TELEPORT;
    private static volatile boolean autoStartEnabled;
    private static int timeoutTicks = 20_000;
    private static int dwellTicks = DEFAULT_DWELL_TICKS;
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
        finalCompletion.cancel();
        enter(Phase.TELEPORT);
        log("Tour started — " + WAYPOINTS.length + " waypoints");
        emitStatus("start", "ready");
    }

    public static void stop() {
        if (!active) return;
        active = false;
        autoStartEnabled = false;
        finalCompletion.cancel();
        log("Tour stopped at waypoint " + (waypointIndex + 1) + "/" + WAYPOINTS.length);
        emitStatus("stop", "manual");
    }

    public static boolean isActive() {
        return active;
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
        testMode = true;
        machineReadableEnabled = false;
        for (int i = 0; i < maxTicks && active; i++) {
            runTick(null);
            if (!active) {
                break;
            }
        }
    }

    static void resetForTest() {
        active = false;
        waypointIndex = 0;
        ticksInPhase = 0;
        ticksTotal = 0;
        dwellTicks = DEFAULT_DWELL_TICKS;
        phase = Phase.TELEPORT;
        testMode = true;
        machineReadableEnabled = false;
        closeClientAtTerminal = false;
        shutdownRequested.set(false);
        finalCompletion.cancel();
        statusFilePath = null;
        testEvents.clear();
        testStatuses.clear();
    }

    static int waypointCountForTest() {
        return WAYPOINTS.length;
    }

    static int dwellTicksForTest() {
        return dwellTicks;
    }

    static int[] waypointForTest(int index) {
        return WAYPOINTS[index].clone();
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
                    int[] waypoint = WAYPOINTS[waypointIndex];
                    requestTeleport(client, waypoint);
                }
                enter(Phase.SHOT_BEFORE);
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
                    boolean isLastWaypoint = waypointIndex == WAYPOINTS.length - 1;
                    lockCamera(client);
                    requestScreenshot(client, waypointScreenshotName(true), isLastWaypoint);
                    emitStatus("screenshot_requested", "after");
                }
                if (waypointIndex < WAYPOINTS.length - 1) {
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

    private static void requestTeleport(MinecraftClient client, int[] waypoint) {
        String command = "tp @s " + waypoint[0] + " " + waypoint[1] + " " + waypoint[2]
                + " " + TOUR_YAW + " " + TOUR_PITCH;
        if (testMode) {
            testEvents.add("teleport:" + waypoint[0] + "," + waypoint[1] + "," + waypoint[2]
                    + "," + TOUR_YAW + "," + TOUR_PITCH);
        } else if (client != null && client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatCommand(command);
        }
        log("waypoint " + (waypointIndex + 1) + "/" + WAYPOINTS.length
                + " -> " + waypoint[0] + " " + waypoint[1] + " " + waypoint[2]);
        emitStatus("teleport_requested", command);
    }

    private static void lockCamera(MinecraftClient client) {
        if (testMode) {
            testEvents.add("camera-lock:" + TOUR_YAW + "," + TOUR_PITCH);
            return;
        }
        if (client == null || client.player == null) {
            return;
        }
        client.player.setYaw(TOUR_YAW);
        client.player.setPitch(TOUR_PITCH);
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
                "tour-waypoint-%02d-%s.png",
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

        if (client.world == null || client.player == null || client.player.networkHandler == null) {
            return false;
        }

        return isEndClientReady(client);
    }

    private static boolean isEndClientReady(MinecraftClient client) {
        return client.world != null && client.world.getRegistryKey().equals(World.END) && client.currentScreen == null;
    }

    private static void emitStatus(String event, String detail) {
        if (testMode) {
            testStatuses.add(event + ":" + detail);
        }
        if (!machineReadableEnabled || statusFilePath == null) {
            return;
        }

        String record = String.format(
                "{\"event\":\"%s\",\"waypoint\":%d,\"phase\":\"%s\",\"detail\":\"%s\",\"ticks\":%d,\"runId\":\"%s\"}%n",
                jsonEscape(event),
                waypointIndex + 1,
                phase.name().toLowerCase(),
                jsonEscape(detail),
                ticksTotal,
                jsonEscape(runId));
        try {
            Files.createDirectories(statusFilePath.getParent());
            Files.writeString(statusFilePath, record, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            HelloTerrainMod.LOGGER.warn("Failed to write flight-tour status file: {}", statusFilePath);
        }
    }

    private static String jsonEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
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
