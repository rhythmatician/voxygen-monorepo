package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.HelloTerrainMod;
import com.rhythmatician.lodiffusion.onnx.VoxyModelRunner;
import com.rhythmatician.lodiffusion.world.noise.BiomeProvider;
import com.rhythmatician.lodiffusion.world.noise.RouterField;
import com.rhythmatician.lodiffusion.world.noise.HeightmapData;
import com.rhythmatician.lodiffusion.world.noise.NoiseRouterSampler;
import com.rhythmatician.lodiffusion.world.noise.NoiseRouterSamplerFactory;
import com.rhythmatician.lodiffusion.world.noise.SectionNoiseData;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyDemandKind;
import net.lodiffusion.shadow.VoxyDemandSource;
import net.lodiffusion.shadow.VoxyRequestDecoder;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

/**
 * Background service that generates terrain around the player using the
 * Voxy ONNX model set and pushes results into Voxy for distant rendering.
 *
 * <h3>Architecture — Demand-driven Voxy Pipeline</h3>
 * <p>Generates sections from demand requests around the player. Results are
 * written to Voxy through {@link VoxelVolumeWriter}.
 *
 * <p>Sections are prioritised by Manhattan distance from the player.
 */
@SuppressWarnings("deprecation")
public final class GenerationSession {

    /** How many sections of Y range to generate (from y=-64 upward). */
    static final int Y_SECTIONS = 16;  // y sections -4..11 → blocks -64..191
    static final int Y_BASE_SECTION = -4;  // start at y=-64

    /**
     * Extra margin (in sections) above and below the surface to generate.
     * Ensures caves near the surface, tree canopies, and hilly terrain are
     * captured.  Sections outside surface ± margin are skipped entirely.
     */
    private static final int SURFACE_MARGIN = 1;  // 1 section = 16 blocks

    /** Minimum block Y in the Minecraft world (floor of bedrock). */
    static final int MIN_WORLD_BLOCK_Y = Y_BASE_SECTION * 16;  // -64
    /** Maximum block Y in the Minecraft world (exclusive). */
    static final int MAX_WORLD_BLOCK_Y = (Y_BASE_SECTION + Y_SECTIONS) * 16;  // 192

    /**
     * Returns {@code true} if the entire world-section at the given level and
     * Y coordinate falls outside the Minecraft world Y range
     * [{@value #MIN_WORLD_BLOCK_Y}, {@value #MAX_WORLD_BLOCK_Y}).
     */
    static boolean isOutOfWorldY(int level, int wsY) {
        int blockYMin = WorldSectionCoord.worldSectionToBlockMin(wsY, level);
        // exclusive upper bound: one past the last block in this world section
        int blockYMaxExcl = WorldSectionCoord.worldSectionToBlockMax(wsY, level) + 1;
        return blockYMaxExcl <= MIN_WORLD_BLOCK_Y || blockYMin >= MAX_WORLD_BLOCK_Y;
    }

    /**
     * Generation radius (in sections).  All sections within this Manhattan
     * distance from the player are generated, closest first.
     */
    static final int GENERATION_RADIUS =
            Config.getInt("generationRadius", 32);

        /** Demand-driven queue poll sleep when there is no pending job. */
        private static final int DEMAND_IDLE_SLEEP_MS =
            Config.getInt("demandIdleSleepMs", 25);

            /** Throttle interval for demand-pipeline progress diagnostics. */
            private static final int DEMAND_PROGRESS_LOG_MS =
                Config.getInt("demandProgressLogMs", 5000);

            /** Periodic near-player seed to avoid local LOD starvation on zoom/FOV changes. */
            private static final int NEAR_SEED_INTERVAL_MS =
                Config.getInt("nearSeedIntervalMs", 1000);

            /** Avoid flooding demand queue with seed traffic when backlog already exists. */
            private static final int NEAR_SEED_MAX_QUEUE_DEPTH =
                Config.getInt("nearSeedMaxQueueDepth", 384);

            /** Minimum time between re-seeding the exact same (lod, wsX, wsY, wsZ). */
            private static final int NEAR_SEED_REENQUEUE_MS =
                Config.getInt("nearSeedReenqueueMs", 8000);

            /** Horizontal ring radius in world-sections for L4 seed requests. */
            private static final int NEAR_SEED_L4_RADIUS =
                Config.getInt("nearSeedL4Radius", 4);

            /**
             * Regular seeding uses the same world-section count per LOD by default.
             * Because finer levels have smaller world-sections, this naturally shrinks
             * the block radius at each finer level without creating giant gaps.
             */
    /**
     * Mysterious-Name fix: {@code +4} is the voxel-center offset.
     * One WorldSection is 32 blocks; L0 is 16 blocks. Centering the sample
     * avoids seam bias. Named to replace magic {@code +4} in yPosition calc.
     */
    private static final int VOXEL_CENTER_OFFSET = 4;

            private static final int[] L2_CLIMATE_CHANNELS = {
                RouterField.TEMPERATURE.ordinal(),
                RouterField.VEGETATION.ordinal(),
                RouterField.CONTINENTS.ordinal(),
                RouterField.EROSION.ordinal(),
                RouterField.DEPTH.ordinal(),
                RouterField.RIDGES.ordinal(),
                RouterField.FINAL_DENSITY.ordinal(),
            };

            private static final int[] L3_L4_CLIMATE_CHANNELS = {
                RouterField.TEMPERATURE.ordinal(),
                RouterField.VEGETATION.ordinal(),
                RouterField.CONTINENTS.ordinal(),
                RouterField.EROSION.ordinal(),
                RouterField.DEPTH.ordinal(),
                RouterField.RIDGES.ordinal(),
            };

    // --- End L4 deterministic tracer (model-free, The End, L4-only) ---
    // Tracer mode is an explicit early session-mode decision before BOTH
    // preloadModel() and resolveVoxyModel()/worker entry. Single decision,
    // no scattered if(isEnd). Proven by no preloadFuture / no VoxyModelRunner
    // / no ONNX file in tracer mode. L4 only; disables L3/L2/L1/L0 seeding
    // and partial-fill descent. Demand is 121 L4 requests (X/Z -5..5, Y=0)
    // around player at (0,96,0) in The End — horizon 2048+512 = 2560 blocks.
    private volatile boolean endL4TracerMode = false;
    static final int END_L4_TRACER_RADIUS = 5;
    static final int END_L4_TRACER_WS_Y = 0;
    static final int END_L4_TRACER_TOTAL = 121; // 11*11

    /** Finite completion telemetry for the End L4 tracer (121/121). */
    record TracerCompletion(
            String status,
            int written,
            int skipped,
            int failed,
            long elapsedMs,
            String atIsoInstant) {}

    private volatile long tracerStartMs = 0;
    private final AtomicInteger tracerWritten = new AtomicInteger(0);
    private final AtomicInteger tracerSkipped = new AtomicInteger(0);
    private final AtomicInteger tracerFailed = new AtomicInteger(0);
    /** Stage 2: L3..L0 refinement regions written this session. */
    private final AtomicInteger refinementWritten = new AtomicInteger(0);
    private final AtomicInteger refinementFailed = new AtomicInteger(0);
    private final RefinementLifecycleTelemetry refinementLifecycle =
            new RefinementLifecycleTelemetry();
    private final ExactL1SamplingTelemetry exactL1Sampling =
            new ExactL1SamplingTelemetry();
    private final AtomicBoolean tracerTerminalEmitted = new AtomicBoolean(false);
    private volatile TracerCompletion tracerCompletion = null;
    /** Stage 2: last selection-pass time, throttles refinement demand passes. */
    private volatile long lastSelectionPassMs = 0;
    private int lastSelectionPlayerSectionX = Integer.MIN_VALUE;
    private int lastSelectionPlayerSectionZ = Integer.MIN_VALUE;

    boolean isEndL4TracerMode() {
        return endL4TracerMode;
    }

    void setEndL4TracerModeForTest(boolean enabled) {
        this.endL4TracerMode = enabled;
    }

    void setRunningForTest(boolean value) {
        this.running.set(value);
    }

    void setStopRequestedForTest(boolean value) {
        this.stopRequested.set(value);
    }

    void forceRunningForTest() {
        this.running.set(true);
        this.stopRequested.set(false);
    }

    TracerCompletion tracerCompletion() {
        return tracerCompletion;
    }

    void resetTracerCompletionForTest() {
        tracerStartMs = 0;
        tracerWritten.set(0);
        tracerSkipped.set(0);
        tracerFailed.set(0);
        tracerTerminalEmitted.set(false);
        tracerCompletion = null;
    }

    private boolean decideEndL4TracerMode(World world) {
        if (world == null) {
            return false;
        }
        try {
            net.minecraft.registry.RegistryKey<World> key = world.getRegistryKey();
            if (key == null || key.getValue() == null) return false;
            // Avoid direct World.END reference to keep test bootstrap-free; compare identifier
            return key.getValue().equals(net.minecraft.util.Identifier.of("minecraft", "the_end"));
        } catch (Exception e) {
            return false;
        }
    }

    static int endL4TracerTotalRequests() {
        return END_L4_TRACER_TOTAL;
    }

    // --- Stage 2: screen-space-error refinement demand (ADR 0011) ---

    /** Default focal length (px) for CPU-side screen-space selection. */
    static final int REFINEMENT_FOCAL_PX =
            Config.getInt("endRefinementFocalPx", 1000);
    /** Subdivision threshold (px): descend iff child projects larger. */
    static final int REFINEMENT_SUB_DIV_PX =
            Config.getInt("endRefinementSubDivPx", 64);
    /** Max refinement requests emitted per selection pass (budget). */
    static final int REFINEMENT_BUDGET_PER_PASS =
            Config.getInt("endRefinementBudgetPerPass", 256);
    /** Small refillable set of nearest visual transactions awaiting service. */
    static final int VISUAL_WORKING_SET_TARGET = Math.max(1,
            Config.getInt("endRefinementVisualOutstandingTarget", 16));
    /** XZ render distance (blocks) for refinement culling; matches Voxy's
     *  cylindrical test scaled to our slice until fly-around evidence tunes it. */
    static final double DEFAULT_REFINEMENT_RENDER_DISTANCE =
            Config.getInt("endRefinementRenderDistanceBlocks", 8192);

    /**
     * Dedup key for already-emitted refinements: level + region coords.
     * Cleared when coverage changes; keyed on node, not camera, so a moved
     * camera inside covered regions re-emits only genuinely new nodes.
     */
    private final ConcurrentHashMap<Long, Integer> emittedRefinementMasks =
            new ConcurrentHashMap<>();

    private static long refinementKey(int level, int x, int y, int z) {
        long l = ((long) level) & 0x7L;
        long xx = ((long) x) & 0x1FFFFFL;
        long yy = ((long) y) & 0x1FFFFFL;
        long zz = ((long) z) & 0x1FFFFFL;
        // Disjoint-bit packing (NOT XOR): XOR aliases across levels when
        // coordinates are small (e.g. L1(0,0,1) == L3(0,0,1) under XOR).
        return (l << 61) | (xx << 42) | (yy << 21) | zz;
    }

    /**
     * Run one screen-space-error selection pass over the given covered L4
     * regions and enqueue L3..L0 refinement requests (nearest-first, budget-
     * capped, deduplicated). Pure demand — production happens in the
     * pipeline loop exactly as for ring requests.
     *
     * <p>Test-visible seam mirroring {@link #enqueueEndL4TracerRequests()};
     * the worker calls this with live player-derived inputs.
     *
     * @param coveredL4Regions covered L4 regions in player-section coords
     *                         ({@code SectionPos} components are section
     *                         indices; region index = component &gt;&gt; 5)
     * @param camX camY camZ   camera position in blocks
     * @param finestLevelValue finest Level to refine down to (1..3 here)
     * @param renderDistanceBlocks XZ-cylindrical cull distance in blocks
     * @param budget max emissions this pass
     * @return number of requests enqueued
     */
    synchronized int enqueueEndRefinementsForTest(List<SectionPos> coveredL4Regions,
                                     double camX, double camY, double camZ,
                                     int finestLevelValue,
                                     double renderDistanceBlocks, int budget) {
        var selections = RefinementDemandSelector.select(
                new RefinementDemandSelector.Params(
                        camX, camY, camZ,
                        REFINEMENT_FOCAL_PX, REFINEMENT_SUB_DIV_PX,
                        finestLevelValue, renderDistanceBlocks, budget,
                        coveredL4Regions));
        List<RefinementDemandSelector.Emission> frontier = new ArrayList<>();
        List<RefinementDemandSelector.Emission> ordinary = new ArrayList<>();
        int considered = 0;
        int newlyScheduled = 0;
        int alreadyRepresented = 0;
        int rejected = 0;
        for (var emission : selections) {
            var request = emission.request();
            var cell = new VanillaOccupancyPyramid.Cell(
                    request.level(), request.wsX(), request.wsY(), request.wsZ());
            switch (vanillaOccupancy.relation(cell)) {
                case FULL -> {
                    emittedRefinementMasks.put(refinementKey(
                            request.level(), request.wsX(), request.wsY(), request.wsZ()), 0xFF);
                    alreadyRepresented++;
                }
                case FRONTIER -> frontier.add(emission);
                case ORDINARY -> ordinary.add(emission);
            }
        }
        List<RefinementDemandSelector.Emission> prioritized =
                new ArrayList<>(frontier.size() + ordinary.size());
        prioritized.addAll(frontier);
        prioritized.addAll(ordinary);
        int schedulingBudget = Math.max(0, budget);
        for (var e : prioritized) {
            var nr = e.request();
            long key = refinementKey(nr.level(), nr.wsX(), nr.wsY(), nr.wsZ());
            int representedMask = emittedRefinementMasks.getOrDefault(key, 0);
            int newDemandMask = e.demandedChildMask() & ~representedMask;
            if (newDemandMask == 0) {
                considered++;
                alreadyRepresented++;
                continue;
            }
            if (newlyScheduled >= schedulingBudget) {
                break;
            }
            considered++;
            VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
            req.lodLevel = nr.level();
            req.worldX = nr.wsX();
            req.worldY = nr.wsY();
            req.worldZ = nr.wsZ();
            var cell = new VanillaOccupancyPyramid.Cell(
                    nr.level(), nr.wsX(), nr.wsY(), nr.wsZ());
            req.demandKind = vanillaOccupancy.relation(cell)
                    == VanillaOccupancyPyramid.Relation.FRONTIER
                    ? VoxyDemandKind.VANILLA_FRONTIER_GUARD
                    : VoxyDemandKind.VISUAL_REFINEMENT;
            req.workKind = net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT;
            req.demandSource = VoxyDemandSource.SCREEN_SPACE_SELECTOR;
            req.demandedChildMask = newDemandMask
                    & vanillaOccupancy.missingChildOctants(cell);
            if (req.demandedChildMask == 0) {
                emittedRefinementMasks.merge(key, newDemandMask, (left, right) -> left | right);
                alreadyRepresented++;
                continue;
            }
            ShadowRouterJobQueue.EnqueueResult result = ShadowRouterJobQueue.enqueue(req);
            switch (result) {
                case QUEUED, UPGRADED -> {
                    emittedRefinementMasks.merge(
                            key, req.demandedChildMask, (left, right) -> left | right);
                    newlyScheduled++;
                }
                case DUPLICATE -> {
                    emittedRefinementMasks.merge(
                            key, req.demandedChildMask, (left, right) -> left | right);
                    alreadyRepresented++;
                }
                case IN_FLIGHT -> alreadyRepresented++;
                case REJECTED -> rejected++;
            }
        }
        if (considered > 0) {
            HelloTerrainMod.LOGGER.info(
                    "[LodGen][Refine] pass considered={} newlyScheduled={} alreadyRepresented={} rejected={} outstanding={}",
                    considered, newlyScheduled, alreadyRepresented, rejected,
                    visualOutstanding());
        }
        return newlyScheduled;
    }

    /**
     * Run one selection pass with player-derived inputs: camera at the
     * player's block position, covered regions = the 11×11 L4 ring in
     * player-section coords (matching {@link #enqueueEndL4TracerRequests()}),
     * finest level and budget from config defaults.
     *
     * @return number of refinement requests enqueued this pass
     */
    int runEndSelectionPassForTest(int finestLevelValue,
                                   double renderDistanceBlocks, int budget) {
        // Ring centres: same math as enqueueEndL4TracerRequests.
        int centerWsX = WorldSectionCoord.sectionToWorldSection(playerSectionX, 4);
        int centerWsZ = WorldSectionCoord.sectionToWorldSection(playerSectionZ, 4);
        List<SectionPos> covered = new ArrayList<>(END_L4_TRACER_TOTAL);
        for (int dz = -END_L4_TRACER_RADIUS; dz <= END_L4_TRACER_RADIUS; dz++) {
            for (int dx = -END_L4_TRACER_RADIUS; dx <= END_L4_TRACER_RADIUS; dx++) {
                // World-section-at-L4 coord -> player-section coord (<< (4+1)).
                covered.add(new SectionPos(
                        (centerWsX + dx) << 5,
                        0,
                        (centerWsZ + dz) << 5));
            }
        }
        double camX = WorldSectionCoord.sectionToBlockMin(playerSectionX) + 8.0;
        double camY = WorldSectionCoord.sectionToBlockMin(playerSectionY) + 8.0;
        double camZ = WorldSectionCoord.sectionToBlockMin(playerSectionZ) + 8.0;
        return enqueueEndRefinementsForTest(
                covered, camX, camY, camZ, finestLevelValue, renderDistanceBlocks, budget);
    }

    /**
     * Admits one bounded refinement pass when its timer expires or the player enters a new
     * section. The worker calls this before every dequeue, so admission does not depend on an
     * empty H/G queue. Repeated worker iterations in the same epoch are no-ops.
     */
    private synchronized int runScheduledEndSelection(long nowMs, int budget) {
        if (!positionReady.get()) {
            return 0;
        }
        boolean movedSection = playerSectionX != lastSelectionPlayerSectionX
                || playerSectionZ != lastSelectionPlayerSectionZ;
        boolean timerElapsed = lastSelectionPassMs == 0
                || nowMs - lastSelectionPassMs >= NEAR_SEED_INTERVAL_MS;
        if (!movedSection && !timerElapsed) {
            return 0;
        }
        lastSelectionPassMs = nowMs;
        lastSelectionPlayerSectionX = playerSectionX;
        lastSelectionPlayerSectionZ = playerSectionZ;
        int remainingCapacity = VISUAL_WORKING_SET_TARGET - visualOutstanding();
        if (remainingCapacity <= 0) {
            return 0;
        }
        return runEndSelectionPassForTest(
                Level.L0.value(), DEFAULT_REFINEMENT_RENDER_DISTANCE,
                Math.min(Math.max(0, budget), remainingCapacity));
    }

    int runScheduledEndSelectionForTest(long nowMs, int budget) {
        return runScheduledEndSelection(nowMs, budget);
    }

    static int selectionIntervalMsForTest() {
        return NEAR_SEED_INTERVAL_MS;
    }

    static int visualWorkingSetTargetForTest() {
        return VISUAL_WORKING_SET_TARGET;
    }

    private static int visualOutstanding() {
        ShadowRouterJobQueue.DemandMetrics visual =
                ShadowRouterJobQueue.demandMetrics().visual();
        return visual.queuedDepth() + visual.inFlightDepth();
    }

    /**
     * Service one L3..L0 refinement request via the multi-level End scaffold
     * and {@link VoxelVolumeWriter#commitParentRefinement}. Package-private single-request
     * seam mirroring {@link #processTracerRequestForTest}; the pipeline loop
     * routes non-L4 tracer-mode requests here.
     *
     * @return refinement status; non-refinement requests are reported as failed
     */
    RefinementOutcome processEndRefinementRequest(VoxyRequestDecoder.VoxyNodeRequest req,
                                                  VoxelVolumeWriter writer) {
        if (req == null || writer == null || noiseAccess == null) {
            return RefinementOutcome.failed();
        }
        if (!RefinementAdmissionGate.allows(req.workKind)) {
            return RefinementOutcome.alreadyCovered();
        }
        if (req.workKind == net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT) {
            return processParentRefinement(req, writer);
        }
        int lvl = req.lodLevel;
        if (lvl < Level.L0.value() || lvl > Level.L3.value()) {
            return RefinementOutcome.failed();
        }
        VoxyRequestDecoder.VoxyNodeRequest parent = new VoxyRequestDecoder.VoxyNodeRequest();
        parent.lodLevel = lvl + 1;
        parent.worldX = req.worldX >> 1;
        parent.worldY = req.worldY >> 1;
        parent.worldZ = req.worldZ >> 1;
        parent.demandKind = VoxyDemandKind.VISUAL_REFINEMENT;
        parent.workKind = net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT;
        parent.demandSource = VoxyDemandSource.PARENT_DEPENDENCY;
        parent.demandedChildMask = childOctantMask(req.worldX, req.worldY, req.worldZ);
        return processParentRefinement(parent, writer);
    }

    private void recordRefinementAttempt(
            VoxyRequestDecoder.VoxyNodeRequest request, RefinementOutcome outcome) {
        if (request != null
                && request.workKind == net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT) {
            refinementLifecycle.recordAttempt(request.lodLevel, outcome);
            if (outcome.status() == RefinementOutcome.Status.BLOCKED_PARENT
                    || outcome.status() == RefinementOutcome.Status.FAILED) {
                clearEmittedRefinementBits(request);
            }
        }
    }

    private void clearEmittedRefinementBits(VoxyRequestDecoder.VoxyNodeRequest request) {
        long key = refinementKey(
                request.lodLevel, request.worldX, request.worldY, request.worldZ);
        emittedRefinementMasks.computeIfPresent(key, (ignored, represented) -> {
            int remaining = represented & ~request.demandedChildMask;
            return remaining == 0 ? null : remaining;
        });
    }

    String refinementLifecycleSummaryForTest() {
        return refinementLifecycle.compact();
    }

    private RefinementOutcome processParentRefinement(
            VoxyRequestDecoder.VoxyNodeRequest req, VoxelVolumeWriter writer) {
        int parentLevelValue = req.lodLevel;
        if (parentLevelValue < Level.L1.value() || parentLevelValue > Level.L4.value()) {
            return RefinementOutcome.failed();
        }
        Level parentLevel = Level.values()[parentLevelValue];
        if (isOutOfWorldY(parentLevelValue, req.worldY)) {
            return RefinementOutcome.alreadyCovered();
        }
        SectionPos parentOrigin = new SectionPos(
                WorldSectionCoord.worldSectionToBlockMin(req.worldX, parentLevelValue) >> 4,
                WorldSectionCoord.worldSectionToBlockMin(req.worldY, parentLevelValue) >> 4,
                WorldSectionCoord.worldSectionToBlockMin(req.worldZ, parentLevelValue) >> 4);
        int childLevelValue = parentLevelValue - 1;
        var occupancyCell = new VanillaOccupancyPyramid.Cell(
                parentLevelValue, req.worldX, req.worldY, req.worldZ);
        int demandedChildMask;
        synchronized (this) {
            demandedChildMask = req.demandedChildMask
                    & vanillaOccupancy.missingChildOctants(occupancyCell);
        }
        if (demandedChildMask == 0) {
            return RefinementOutcome.alreadyCovered();
        }
        EndL4DeterministicCandidate candidate = new EndL4DeterministicCandidate(noiseAccess);
        ExactEndL1Candidate exactL1 = new ExactEndL1Candidate(noiseAccess, exactL1Sampling);
        try {
            ParentRefinementResult result = writer.refineParent(new ParentRefinementIntent(
                    parentOrigin, parentLevel, demandedChildMask, (childLevel, childOrigin) -> {
                        int childWsY = WorldSectionCoord.sectionToWorldSection(
                                childOrigin.y(), childLevelValue);
                        return isOutOfWorldY(childLevelValue, childWsY)
                                ? VoxelVolume.uniform(
                                        32, EndL4DeterministicCandidate.BLOCK_AIR, 0)
                                : childLevel == Level.L1
                                        ? exactL1.produceExactL1(childOrigin)
                                        : candidate.produceRegion(childLevel, childOrigin);
                    }));
            if (result.status() == ParentRefinementResult.Status.PARENT_MISSING) {
                enqueueParentPrerequisite(req);
                return RefinementOutcome.blockedParent(parentOrigin);
            }
            return RefinementOutcome.published(result.writeOutcome());
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn(
                    "[LodGen][Refine] parent transaction failed lvl={} ws=({},{},{}): {}",
                    parentLevelValue, req.worldX, req.worldY, req.worldZ, e.getMessage());
            return RefinementOutcome.failed();
        }
    }

    private static void enqueueParentPrerequisite(VoxyRequestDecoder.VoxyNodeRequest req) {
        int parentLevelValue = req.lodLevel;
        VoxyRequestDecoder.VoxyNodeRequest prerequisite = new VoxyRequestDecoder.VoxyNodeRequest();
        boolean guardUrgency = req.demandKind == VoxyDemandKind.VANILLA_FRONTIER_GUARD;
        if (parentLevelValue == Level.L4.value()) {
            prerequisite.lodLevel = Level.L4.value();
            prerequisite.worldX = req.worldX;
            prerequisite.worldY = req.worldY;
            prerequisite.worldZ = req.worldZ;
            prerequisite.demandKind = guardUrgency
                    ? VoxyDemandKind.VANILLA_FRONTIER_GUARD
                    : VoxyDemandKind.HORIZON_COVERAGE;
            prerequisite.workKind = net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF;
        } else {
            prerequisite.lodLevel = parentLevelValue + 1;
            prerequisite.worldX = req.worldX >> 1;
            prerequisite.worldY = req.worldY >> 1;
            prerequisite.worldZ = req.worldZ >> 1;
            prerequisite.demandKind = guardUrgency
                    ? VoxyDemandKind.VANILLA_FRONTIER_GUARD
                    : VoxyDemandKind.VISUAL_REFINEMENT;
            prerequisite.workKind = net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT;
            prerequisite.demandedChildMask = childOctantMask(
                    req.worldX, req.worldY, req.worldZ);
        }
        prerequisite.demandSource = VoxyDemandSource.PARENT_DEPENDENCY;
        ShadowRouterJobQueue.enqueue(prerequisite);
    }

    /**
     * Enqueue 121 L4 tracer requests (X/Z -5..5, Y=0) around the player's
     * L4 WorldSection centre. Reuses dedup/backpressure via
     * ShadowRouterJobQueue; no synchronous blocking barrier.
     */
    int enqueueEndL4TracerRequests() {
        tracerStartMs = System.currentTimeMillis();
        tracerWritten.set(0);
        tracerSkipped.set(0);
        tracerFailed.set(0);
        tracerTerminalEmitted.set(false);
        tracerCompletion = null;
        int centerX = WorldSectionCoord.sectionToWorldSection(playerSectionX, 4);
        int centerZ = WorldSectionCoord.sectionToWorldSection(playerSectionZ, 4);
        int enqueued = 0;
        for (int dz = -END_L4_TRACER_RADIUS; dz <= END_L4_TRACER_RADIUS; dz++) {
            for (int dx = -END_L4_TRACER_RADIUS; dx <= END_L4_TRACER_RADIUS; dx++) {
                int wsX = centerX + dx;
                int wsZ = centerZ + dz;
                int wsY = END_L4_TRACER_WS_Y;
                VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
                req.lodLevel = 4;
                req.worldX = wsX;
                req.worldY = wsY;
                req.worldZ = wsZ;
                req.demandKind = VoxyDemandKind.HORIZON_COVERAGE;
                req.workKind = net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF;
                req.demandSource = VoxyDemandSource.HORIZON_SEED;
                ShadowRouterJobQueue.enqueue(req);
                enqueued++;
            }
        }
        return enqueued;
    }

    private void maybeEmitTracerTerminal() {
        int written = tracerWritten.get();
        int skipped = tracerSkipped.get();
        int failed = tracerFailed.get();
        int processed = written + skipped + failed;
        if (processed != END_L4_TRACER_TOTAL) {
            return;
        }
        if (!tracerTerminalEmitted.compareAndSet(false, true)) {
            return;
        }
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - tracerStartMs);
        String at = java.time.Instant.now().toString();
        String status = (written + skipped == END_L4_TRACER_TOTAL && failed == 0) ? "SUCCESS" : "FAILED";
        tracerCompletion = new TracerCompletion(status, written, skipped, failed, elapsedMs, at);
        HelloTerrainMod.LOGGER.info(
                "[LodGen][Tracer] terminal 121/121 status={} written={} skipped={} failed={} elapsedMs={} at={}",
                status, written, skipped, failed, elapsedMs, at);
        // Also publish to PerformanceMonitor for harness observation without log parsing
        com.rhythmatician.lodiffusion.util.PerformanceMonitor.setCounter(
                com.rhythmatician.lodiffusion.util.PerformanceMonitor.TRACER_HORIZON_WRITTEN, written);
        com.rhythmatician.lodiffusion.util.PerformanceMonitor.setCounter(
                com.rhythmatician.lodiffusion.util.PerformanceMonitor.TRACER_HORIZON_SKIPPED, skipped);
        com.rhythmatician.lodiffusion.util.PerformanceMonitor.setCounter(
                com.rhythmatician.lodiffusion.util.PerformanceMonitor.TRACER_HORIZON_FAILED, failed);
        com.rhythmatician.lodiffusion.util.PerformanceMonitor.setCounter(
                com.rhythmatician.lodiffusion.util.PerformanceMonitor.TRACER_HORIZON_ELAPSED_MS, elapsedMs);
        com.rhythmatician.lodiffusion.util.PerformanceMonitor.setCounter(
                com.rhythmatician.lodiffusion.util.PerformanceMonitor.TRACER_HORIZON_STATUS_SUCCESS,
                "SUCCESS".equals(status) ? 1 : 0);
    }

    /**
     * Internal terrain-candidate seam — package-private, not a public SPI.
     * Result is a semantic {@link VoxelVolume} (extent 16 for L0 sections,
     * 32 for regions). Demand/scheduling variation stays internal to the
     * session; no public scheduler strategy is exposed.
     *
     * <p>Two production adapters exist: fallback (heightmap) and learned
     * (ONNX via {@link VoxelPredictionDecoder}). Tests may supply a double
     * via {@link #GenerationSession(VoxelVolumeWriter, TerrainCandidate)}.
     */
    interface TerrainCandidate {
        VoxelVolume produceSection(SectionPos pos, ColumnContext ctx);
        VoxelVolume produceRegion(Level level, SectionPos origin, long[] parentInput);
    }

    /** Fallback candidate — stateless heightmap path. */
    private static final class FallbackCandidate implements TerrainCandidate {
        @Override
        public VoxelVolume produceSection(SectionPos pos, ColumnContext ctx) {
            return VoxelPredictionDecoder.fromFallback(
                    pos.y(), ctx.rawHm(), ctx.oceanFloorHm(), ctx.biomeIdx());
        }
        @Override
        public VoxelVolume produceRegion(Level level, SectionPos origin, long[] parentInput) {
            throw new UnsupportedOperationException("Fallback candidate does not produce regions");
        }
    }

    /** Test-visible accessor for candidate seam verification. */
    TerrainCandidate candidateForTest() {
        if (candidateOverride != null) return candidateOverride;
        return selectCandidate();
    }

    private TerrainCandidate selectCandidate() {
        if (voxyModelRunner != null && noiseAccess != null && voxyModelRunner.vocabulary() != null) {
            return learnedCandidate;
        }
        return fallbackCandidate;
    }

    private final TerrainCandidate fallbackCandidate = new FallbackCandidate();
    private final TerrainCandidate learnedCandidate = new TerrainCandidate() {
        @Override
        public VoxelVolume produceSection(SectionPos pos, ColumnContext ctx) {
            // L0 heightmap path is shared between fallback and learned for the simple
            // produceSection seam (ColumnContext conditioning). True learned inference
            // for L0-L4 with 3D noise/climate and parentInput is exercised via the
            // demand pipeline: processDemandRequest -> voxyModelRunner.runL* ->
            // VoxelPredictionDecoder.fromOctreeArgmax, which writes regions (extent 32)
            // through VoxelVolumeWriter.writeRegion. Keeping produceSection on the
            // fallback decoder here preserves the internal seam shape without broadening
            // its conditioning inputs; the demand pipeline remains the ONNX entry point.
            return VoxelPredictionDecoder.fromFallback(
                    pos.y(), ctx.rawHm(), ctx.oceanFloorHm(), ctx.biomeIdx());
        }
        @Override
        public VoxelVolume produceRegion(Level level, SectionPos origin, long[] parentInput) {
            throw new UnsupportedOperationException("Learned region via demand pipeline");
        }
    };

    private final VoxelVolumeWriter writerOverride;
    private final TerrainCandidate candidateOverride;

    /** Production constructor. */
    public GenerationSession() {
        this(null, null);
    }

    /** Package-private test constructor with injected writer/candidate. */
    GenerationSession(VoxelVolumeWriter writerOverride, TerrainCandidate candidateOverride) {
        this.writerOverride = writerOverride;
        this.candidateOverride = candidateOverride;
        RefinementAdmissionGate.logResolvedModeOnce();
    }

    /**
     * Compose helper for tests: route a section produce through the internal
     * candidate seam and write via the provided writer. No Voxy jar required.
     */
    WriteOutcome produceAndWriteSection(SectionPos pos, ColumnContext ctx, VoxelVolumeWriter writer) {
        TerrainCandidate c = candidateOverride != null ? candidateOverride : selectCandidate();
        VoxelVolume vol = c.produceSection(pos, ctx);
        if (vol.extent() != 16) {
            throw new IllegalArgumentException("Section candidate must return extent 16");
        }
        return writer.writeSection(pos, vol);
    }

    /**
     * Compose helper for tests: produce a region via candidate and write.
     */
    WriteOutcome produceAndWriteRegion(SectionPos origin, Level level, VoxelVolumeWriter writer) {
        TerrainCandidate c = candidateOverride != null ? candidateOverride : selectCandidate();
        VoxelVolume vol;
        try {
            vol = c.produceRegion(level, origin, null);
        } catch (UnsupportedOperationException e) {
            VoxelVolume.Builder b = VoxelVolume.builder(32);
            b.fill(CanonicalRegistries.BLOCK_AIR, 0);
            vol = b.build();
        }
        if (vol.extent() != 32) {
            throw new IllegalArgumentException("Region candidate must return extent 32");
        }
        return writer.writeRegion(origin, level, vol);
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean positionReady = new AtomicBoolean(false);
    private volatile Thread workerThread;

    /**
     * Optional pre-loaded model future started via {@link #preloadModel()}
     * before the world fully joins (e.g. during "Loading terrain...").
     * {@code null} if pre-loading was not requested.
     */
    private volatile CompletableFuture<VoxyModelRunner> preloadFuture;

    /** New 5-model hierarchical runner (L4→L0). */
    private volatile VoxyModelRunner voxyModelRunner;

    /** Updated each tick from the client thread. */
    private volatile int playerSectionX;
    private volatile int playerSectionY;
    private volatile int playerSectionZ;

    /** Local near-player demand seed throttling state. */
    private volatile long lastNearSeedMs;
    private volatile int lastSeedPlayerSectionX = Integer.MIN_VALUE;
    private volatile int lastSeedPlayerSectionZ = Integer.MIN_VALUE;
    private final ConcurrentHashMap<Long, Long> recentSeededAtMs = new ConcurrentHashMap<>();
    private final VanillaOccupancyPyramid vanillaOccupancy = new VanillaOccupancyPyramid();

    private record FrontierEpoch(int playerL1X, int playerL1Z,
                                 int vanillaRadiusBlocks, int leadBlocks) {}

    private FrontierEpoch lastFrontierEpoch;
    private final Set<VanillaFrontierGuardPlanner.ParentTransaction> plannedFrontierTransactions =
            new HashSet<>();

    /** Tracks which (section) positions we've already generated. Thread-safe. */
    private final Set<Long> generatedSections = ConcurrentHashMap.newKeySet();

    /** Cached column conditioning data — avoids redundant noise sampling. Thread-safe. */
    private final ConcurrentHashMap<Long, ColumnContext> columnContextCache =
            new ConcurrentHashMap<>();

    /** Active pipeline queue (set during pipeline execution). */
    private volatile LodGenerationQueue activeQueue;

    /** Stats: how many sections used real vs synthetic conditioning data. Thread-safe. */
    private final AtomicInteger realDataSections = new AtomicInteger();
    private final AtomicInteger syntheticDataSections = new AtomicInteger();
    private final AtomicInteger noiseAccessSections = new AtomicInteger();
    private final AtomicInteger skippedAirSections = new AtomicInteger();

    /** Server-side noise access — null if unavailable (dedicated server). */
    private volatile WorldNoiseAccess noiseAccess;

    /**
     * Hot-swappable sampler factory for the 15 NoiseRouter fields.
     * Reads {@code terrainBackend} config on each call to
     * {@link NoiseRouterSamplerFactory#getSampler()}.
     * Null until the world's {@link net.minecraft.world.gen.noise.NoiseConfig}
     * is available.
     */
    private volatile NoiseRouterSamplerFactory samplerFactory;

    /** Server reference for noise access (integrated server in singleplayer). */
    private volatile MinecraftServer server;

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    /**
    * Begin loading the Voxy ONNX model set on a background thread so they are
     * ready (or nearly so) by the time {@link #start} is called.
     *
     * <p>Safe to call multiple times — a second call is a no-op while a
     * previous future is still pending.  The future is consumed (and nulled)
     * by {@link #resolveModel}; calling {@code preloadModel()} again after
     * the session ends will restart pre-loading for the next join.
     *
     * <p>Intended to be wired to {@code ClientPlayConnectionEvents.INIT},
     * which fires as soon as the network connection is established — well
     * before the "Loading terrain..." screen appears.
     */
    // Early session-mode decision before BOTH preloadModel() and resolveVoxyModel()/worker entry
    public void preloadModel() {
        // Early tracer-mode gate: must precede VoxyModelRunner creation
        if (endL4TracerMode) {
            HelloTerrainMod.LOGGER.info("[LodGen] End L4 tracer mode — skipping preloadModel (no ONNX)");
            return;
        }
        if (preloadFuture != null && !preloadFuture.isDone()) {
            HelloTerrainMod.LOGGER.debug("[LodGen] preloadModel() — already in progress, skipping");
            return;
        }
        if (!Config.useOnnxTerrain()) return;

        HelloTerrainMod.LOGGER.info("[LodGen] Pre-loading VoxyModelRunner in background...");
        preloadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                java.nio.file.Path modelDir = Config.modelDir();
                VoxyModelRunner runner = VoxyModelRunner.tryLoad(modelDir);
                if (runner != null) {
                    HelloTerrainMod.LOGGER.info("[LodGen] VoxyModelRunner pre-load complete");
                } else {
                    HelloTerrainMod.LOGGER.warn("[LodGen] VoxyModelRunner pre-load: model set not found");
                }
                return runner;
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn("[LodGen] VoxyModelRunner pre-load failed (will retry in worker): {}",
                        e.getMessage());
                return null;
            }
        });
    }

    /**
     * Start the LOD generation service for a given world.
     *
     * @param world  the Minecraft world (client-side)
     * @param server the Minecraft server (integrated server for singleplayer;
     *               null for dedicated-server clients)
     */
    public void start(World world, MinecraftServer server) {
        if (running.getAndSet(true)) {
            HelloTerrainMod.LOGGER.warn("[LodGen] Service already running");
            return;
        }

        stopRequested.set(false);
        positionReady.set(false);
        lastSelectionPassMs = 0;
        lastSelectionPlayerSectionX = Integer.MIN_VALUE;
        lastSelectionPlayerSectionZ = Integer.MIN_VALUE;
        generatedSections.clear();
        columnContextCache.clear();
        resetFrontierGuardState();
        activeQueue = null;
        realDataSections.set(0);
        syntheticDataSections.set(0);
        noiseAccessSections.set(0);
        skippedAirSections.set(0);
        diagnosticCount.set(0);
        refinementLifecycle.reset();
        exactL1Sampling.reset();
        if (samplerFactory != null) {
            try { samplerFactory.close(); } catch (Exception ignored) {}
            samplerFactory = null;
        }
        noiseAccess = null;
        this.server = server;
        // Early session-mode decision before BOTH preloadModel() and resolveVoxyModel()/worker entry
        this.endL4TracerMode = decideEndL4TracerMode(world);
        if (endL4TracerMode) {
            HelloTerrainMod.LOGGER.info("[LodGen] End L4 tracer mode enabled — The End, model-free, L4-only");
        }

        workerThread = new Thread(() -> runWorker(world), "LODiffusion-Gen");
        workerThread.setDaemon(true);
        workerThread.start();

        HelloTerrainMod.LOGGER.info("[LodGen] Service started");
    }

    /**
     * Stop the service and wait for the worker to finish.
     */
    public void stop() {
        if (!running.get()) return;

        stopRequested.set(true);
        // Signal population done so stage workers can drain and exit
        LodGenerationQueue q = activeQueue;
        if (q != null) q.signalPopulationDone();

        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException ignored) {}
        }
        running.set(false);
        generatedSections.clear();
        columnContextCache.clear();
        resetFrontierGuardState();
        if (q != null) q.clear();
        activeQueue = null;
        if (samplerFactory != null) {
            try { samplerFactory.close(); } catch (Exception ignored) {}
            samplerFactory = null;
        }
        if (voxyModelRunner != null) {
            try { voxyModelRunner.close(); } catch (Exception ignored) {}
            voxyModelRunner = null;
        }
        HelloTerrainMod.LOGGER.info("[LodGen] Service stopped");
    }

    /**
     * Update the player's current section position (called each client tick).
     */
    public void updatePlayerPosition(BlockPos pos) {
        this.playerSectionX = WorldSectionCoord.blockToSection(pos.getX());
        this.playerSectionY = WorldSectionCoord.blockToSection(pos.getY());
        this.playerSectionZ = WorldSectionCoord.blockToSection(pos.getZ());
        ShadowRouterJobQueue.updatePlayerSection(this.playerSectionX, this.playerSectionZ);

        seedNearPlayerDemandIfNeeded();

        if (positionReady.compareAndSet(false, true)) {
            HelloTerrainMod.LOGGER.info("[LodGen] Player position initialized: section ({}, {}, {})",
                    playerSectionX, playerSectionY, playerSectionZ);
        }
    }

    /**
     * Client-tick frontier update with only scalar values at the core boundary.
     * Vanilla generation and Voxy ingestion remain entirely outside this planner.
     */
    public void updatePlayerPosition(BlockPos pos, double horizontalVelocityX,
                                     double horizontalVelocityZ, int clientViewDistanceChunks,
                                     int simulationDistanceChunks) {
        updatePlayerPosition(pos);
        if (running.get() && endL4TracerMode) {
            enqueueVanillaFrontierGuardForTest(
                    new VanillaFrontierGuardPlanner.FrontierSnapshot(pos.getX(), pos.getZ(),
                            horizontalVelocityX, horizontalVelocityZ,
                            clientViewDistanceChunks, simulationDistanceChunks),
                    Config.getInt("vanillaFrontierLeadTicks", 20));
        }
    }

    synchronized int enqueueVanillaFrontierGuardForTest(
            VanillaFrontierGuardPlanner.FrontierSnapshot snapshot, int leadTicks) {
        VanillaFrontierGuardPlanner.Input input = snapshot.toInput(leadTicks);
        FrontierEpoch epoch = new FrontierEpoch(
                WorldSectionCoord.blockToWorldSection(input.playerBlockX(), Level.L1.value()),
                WorldSectionCoord.blockToWorldSection(input.playerBlockZ(), Level.L1.value()),
                input.vanillaRadiusBlocks(), input.leadBlocks());
        if (epoch.equals(lastFrontierEpoch)) {
            return 0;
        }
        lastFrontierEpoch = epoch;

        int enqueued = 0;
        for (VanillaFrontierGuardPlanner.ParentTransaction parent
                : VanillaFrontierGuardPlanner.plan(input)) {
            if (plannedFrontierTransactions.contains(parent)) {
                continue;
            }
            VoxyRequestDecoder.VoxyNodeRequest request = new VoxyRequestDecoder.VoxyNodeRequest();
            request.lodLevel = parent.level();
            request.worldX = parent.wsX();
            request.worldY = parent.wsY();
            request.worldZ = parent.wsZ();
            request.demandKind = VoxyDemandKind.VANILLA_FRONTIER_GUARD;
            request.workKind = net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT;
            request.demandSource = VoxyDemandSource.VANILLA_RADIUS_ANNULUS;
            ShadowRouterJobQueue.EnqueueResult result = ShadowRouterJobQueue.enqueue(request);
            if (result.representsScheduledWork()) {
                plannedFrontierTransactions.add(parent);
            }
            if (result == ShadowRouterJobQueue.EnqueueResult.QUEUED) {
                enqueued++;
            }
        }
        return enqueued;
    }

    private synchronized void resetFrontierGuardState() {
        lastFrontierEpoch = null;
        plannedFrontierTransactions.clear();
    }

    static int l0WorldSectionForChunk(int chunkCoordinate) {
        return Math.floorDiv(chunkCoordinate, 2);
    }

    static int chunkColumnOwnershipMask(int chunkX, int chunkZ) {
        int quadrant = Math.floorMod(chunkX, 2) | (Math.floorMod(chunkZ, 2) << 1);
        return (1 << quadrant) | (1 << (quadrant + 4));
    }

    synchronized void observeVanillaChunkColumnForTest(int chunkX, int chunkZ) {
        observeVanillaChunkColumn(chunkX, chunkZ);
    }

    /** Records a post-load vanilla chunk observation; this never participates in chunk loading. */
    synchronized void observeVanillaChunkColumn(int chunkX, int chunkZ) {
        int l0X = l0WorldSectionForChunk(chunkX);
        int l0Z = l0WorldSectionForChunk(chunkZ);
        int mask = chunkColumnOwnershipMask(chunkX, chunkZ);
        for (int l0Y = 0; l0Y < 4; l0Y++) {
            enqueueOccupancyDelta(vanillaOccupancy.observeVanillaL0Octants(l0X, l0Y, l0Z, mask));
        }
    }

    synchronized boolean isFullyVanillaForTest(int level, int wsX, int wsY, int wsZ) {
        return vanillaOccupancy.classify(new VanillaOccupancyPyramid.Cell(level, wsX, wsY, wsZ))
                == VanillaOccupancyPyramid.Occupancy.FULL_VANILLA;
    }

    private void enqueueOccupancyDelta(VanillaOccupancyPyramid.Delta delta) {
        for (VanillaOccupancyPyramid.Cell mixed : delta.newlyMixedParents()) {
            if (mixed.level() > Level.L0.value()) enqueueOccupancyParent(mixed);
        }
        for (VanillaOccupancyPyramid.Cell boundary : delta.addedUrgent()) {
            if (vanillaOccupancy.classify(boundary) == VanillaOccupancyPyramid.Occupancy.FULL_VANILLA) {
                continue;
            }
            if (boundary.level() == Level.L4.value()
                    && vanillaOccupancy.classify(boundary) == VanillaOccupancyPyramid.Occupancy.NONE) {
                enqueueOccupancyHorizon(boundary);
            } else if (boundary.level() <= Level.L3.value()) {
                enqueueOccupancyParent(boundary.parent());
            }
        }
    }

    void enqueueOccupancyDeltaForTest(VanillaOccupancyPyramid.Delta delta) {
        enqueueOccupancyDelta(delta);
    }

    private void enqueueOccupancyParent(VanillaOccupancyPyramid.Cell parent) {
        if (parent.level() < Level.L1.value() || parent.level() > Level.L4.value()) return;
        VoxyRequestDecoder.VoxyNodeRequest request = new VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = parent.level();
        request.worldX = parent.x();
        request.worldY = parent.y();
        request.worldZ = parent.z();
        request.demandKind = VoxyDemandKind.VANILLA_FRONTIER_GUARD;
        request.workKind = net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT;
        request.demandSource = VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY;
        request.demandedChildMask = vanillaOccupancy.missingChildOctants(parent);
        if (request.demandedChildMask == 0) return;
        ShadowRouterJobQueue.enqueue(request);
    }

    private void enqueueOccupancyHorizon(VanillaOccupancyPyramid.Cell cell) {
        VoxyRequestDecoder.VoxyNodeRequest request = new VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = Level.L4.value();
        request.worldX = cell.x();
        request.worldY = cell.y();
        request.worldZ = cell.z();
        request.demandKind = VoxyDemandKind.VANILLA_FRONTIER_GUARD;
        request.workKind = net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF;
        request.demandSource = VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY;
        ShadowRouterJobQueue.enqueue(request);
    }

    private synchronized boolean isFullyVanillaOccupancyRequest(VoxyRequestDecoder.VoxyNodeRequest request) {
        return vanillaOccupancy.classify(new VanillaOccupancyPyramid.Cell(
                        request.lodLevel, request.worldX, request.worldY, request.worldZ))
                == VanillaOccupancyPyramid.Occupancy.FULL_VANILLA;
    }

    private static int childOctantMask(int childX, int childY, int childZ) {
        int octant = Math.floorMod(childX, 2)
                | (Math.floorMod(childZ, 2) << 1)
                | (Math.floorMod(childY, 2) << 2);
        return 1 << octant;
    }

    synchronized boolean isFullyVanillaOccupancyRequestForTest(
            VoxyRequestDecoder.VoxyNodeRequest request) {
        return isFullyVanillaOccupancyRequest(request);
    }

    synchronized void recordFrontierGuardOutcomeForTest(
            VoxyRequestDecoder.VoxyNodeRequest request, RefinementOutcome outcome) {
        if (request == null || outcome == null
                || request.demandKind != VoxyDemandKind.VANILLA_FRONTIER_GUARD
                || outcome.status() != RefinementOutcome.Status.FAILED) {
            return;
        }
        plannedFrontierTransactions.remove(new VanillaFrontierGuardPlanner.ParentTransaction(
                request.lodLevel, request.worldX, request.worldY, request.worldZ));
    }

    private void seedNearPlayerDemandIfNeeded() {
        if (!running.get()) {
            return;
        }
        // End L4 tracer: 121 L4-only (X/Z -5..5, Y=0), disable L3/L2/L1/L0 seeding
        if (endL4TracerMode) {
            return;
        }

        int queueDepth = ShadowRouterJobQueue.size();
        if (queueDepth > NEAR_SEED_MAX_QUEUE_DEPTH) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean movedSection = (playerSectionX != lastSeedPlayerSectionX)
                || (playerSectionZ != lastSeedPlayerSectionZ);
        if (!movedSection && (now - lastNearSeedMs) < NEAR_SEED_INTERVAL_MS) {
            return;
        }

        int enqueued = enqueueLocalSeedRing(Level.L4.value(), NEAR_SEED_L4_RADIUS, now);

        lastSeedPlayerSectionX = playerSectionX;
        lastSeedPlayerSectionZ = playerSectionZ;
        lastNearSeedMs = now;

        if (recentSeededAtMs.size() > 8192) {
            long cutoff = now - (NEAR_SEED_REENQUEUE_MS * 3L);
            recentSeededAtMs.entrySet().removeIf(e -> e.getValue() < cutoff);
        }

        if (enqueued > 0 && (movedSection || (now % 5000L) < NEAR_SEED_INTERVAL_MS)) {
            HelloTerrainMod.LOGGER.info(
                    "[LodGen][Seed] enqueued={} L4 radius={} queueDepth={} player=({}, {}, {})",
                    enqueued, NEAR_SEED_L4_RADIUS,
                    queueDepth,
                    playerSectionX, playerSectionY, playerSectionZ);
        }
    }

    private int enqueueLocalSeedRing(int level, int radius, long nowMs) {
        int centerX = WorldSectionCoord.sectionToWorldSection(playerSectionX, level);
        int centerZ = WorldSectionCoord.sectionToWorldSection(playerSectionZ, level);
        int centerY = WorldSectionCoord.sectionToWorldSection(playerSectionY, level);

        int enqueued = 0;
        int wsY = centerY;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                    int wsX = centerX + dx;
                    int wsZ = centerZ + dz;
                    long seedKey = seedKey(level, wsX, wsY, wsZ);
                    Long last = recentSeededAtMs.get(seedKey);
                    if (last != null && (nowMs - last) < NEAR_SEED_REENQUEUE_MS) {
                        continue;
                    }

                    VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
                    req.lodLevel = level;
                    req.worldX = wsX;
                    req.worldY = wsY;
                    req.worldZ = wsZ;
                    req.demandKind = VoxyDemandKind.HORIZON_COVERAGE;
                    req.workKind = net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF;
                    req.demandSource = VoxyDemandSource.HORIZON_SEED;
                    ShadowRouterJobQueue.enqueue(req);
                    recentSeededAtMs.put(seedKey, nowMs);
                    enqueued++;
            }
        }
        return enqueued;
    }

    private static long seedKey(int level, int wsX, int wsY, int wsZ) {
        long x = ((long) wsX) & 0x1FFFFFL;
        long y = ((long) wsY) & 0x1FFFFFL;
        long z = ((long) wsZ) & 0x1FFFFFL;
        long l = ((long) level) & 0x7L;
        return (l << 63) ^ (x << 42) ^ (y << 21) ^ z;
    }

    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------ //
    //  Worker loop
    // ------------------------------------------------------------------ //

    private void runWorker(World world) {
        try {
            HelloTerrainMod.LOGGER.info("[LodGen] Worker starting — waiting for Voxy WorldEngine...");

            // Get Voxy world engine (may need to wait for Voxy to initialize)
            Object worldEngine = waitForWorldEngine(world);
            if (worldEngine == null) {
                HelloTerrainMod.LOGGER.error("[LodGen] Could not obtain Voxy WorldEngine — aborting");
                return;
            }

            HelloTerrainMod.LOGGER.info("[LodGen] Got Voxy WorldEngine — loading model...");

            // Try to bind to the integrated server's noise pipeline for real
            // heightmap / biome / router data at any coordinate.
            noiseAccess = WorldNoiseAccess.tryCreate(server, world);
            if (noiseAccess == null) {
                HelloTerrainMod.LOGGER.warn("[LodGen] Noise access unavailable — "
                        + "will fall back to heightmap-only generation");
        } else {
            HelloTerrainMod.LOGGER.info("[LodGen] Using REAL noise access — "
                    + "no synthetic fallback needed");
            if (Boolean.getBoolean("lodiffusion.flightTour.autoStart")) {
                WorldNoiseAccess.ExactEndL1Probe probe =
                        noiseAccess.probeExactEndL1(new SectionPos(0, 4, 0));
                HelloTerrainMod.LOGGER.info(
                        "[LodGen][ExactL1Control] main-island nonAir={} "
                                + "unloadedBefore={} unloadedAfter={}",
                        probe.nonAirVoxels(),
                        probe.targetChunksUnloadedBefore(),
                        probe.targetChunksUnloadedAfter());
            }
        }

            // Early tracer-mode gate: must precede resolveVoxyModel()
            if (endL4TracerMode) {
                if (noiseAccess == null) {
                    HelloTerrainMod.LOGGER.error(
                            "[LodGen] End L4 tracer mode — noiseAccess unavailable, aborting");
                    return;
                }
                Object tracerMapper = VoxyCompat.getMapper(worldEngine);
                Registry<Biome> tracerBiomeRegistry =
                        world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
                VoxyIdMaps tracerMaps = CanonicalVoxyMaps.from(tracerMapper, tracerBiomeRegistry);
                VoxelVolumeWriter tracerWriter = RealVoxyVolumeWriter.create(worldEngine, tracerMaps);
                waitForPlayerPosition();
                if (stopRequested.get()) {
                    return;
                }
                HelloTerrainMod.LOGGER.info(
                        "[LodGen] End L4 tracer mode — enqueuing 121 L4 requests (X/Z -5..5 Y=0)");
                enqueueEndL4TracerRequests();
                boolean tracerProduced = runEndL4TracerPipeline(world, tracerWriter);
                HelloTerrainMod.LOGGER.info(
                        "[LodGen] End L4 tracer pipeline stopped: produced={}", tracerProduced);
                return;
            }

            if (decideEndL4TracerMode(world)) {
                HelloTerrainMod.LOGGER.error(
                        "[LodGen] End session escaped the typed tracer gate; refusing alternate generation");
                return;
            }

            if (noiseAccess != null) {
                // Create the hot-swappable sampler factory (reads terrainBackend config).
                // Full world context enables UpstreamNoiseContext (heightmap + biome providers).
                samplerFactory = NoiseRouterSamplerFactory.create(
                        noiseAccess.serverWorld(),
                        noiseAccess.generator(),
                        noiseAccess.biomeSource(),
                        noiseAccess.noiseConfig());
                HelloTerrainMod.LOGGER.info("[LodGen] NoiseRouterSamplerFactory ready — backend={}",
                        samplerFactory.getSampler().backendName());
            }

            // ── Primary path: demand-driven 5-model voxy runtime ────────
            voxyModelRunner = resolveVoxyModel();

            Object voxyMapper = VoxyCompat.getMapper(worldEngine);
            Registry<Biome> biomeRegistry =
                    world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);

                    if (voxyModelRunner != null && noiseAccess != null) {
                VoxyIdMaps idMaps = CanonicalVoxyMaps.from(voxyMapper, biomeRegistry);
                // Message-Chains fix: factory encapsulates VoxyCompat.getMapper chain.
                VoxelVolumeWriter writer = RealVoxyVolumeWriter.create(worldEngine, idMaps);

                waitForPlayerPosition();
                if (stopRequested.get()) return;

                HelloTerrainMod.LOGGER.info(
                    "[LodGen] VoxyModelRunner loaded — starting demand-driven generation "
                    + "(inFlight={})",
                    ShadowRouterJobQueue.inFlightSize());

                boolean produced = runDemandVoxyPipeline(world, writer);
                if (produced) return;

                HelloTerrainMod.LOGGER.warn(
                    "[LodGen] Demand-driven Voxy pipeline produced no terrain — "
                    + "falling back to heightmap generator");
                generatedSections.clear();
            }

            // ── Heightmap fallback path ──────────────────────────────────
                if (voxyModelRunner != null && noiseAccess == null) {
                HelloTerrainMod.LOGGER.warn(
                    "[LodGen] VoxyModelRunner loaded but noise access unavailable — " +
                        "falling back to heightmap generator");
                } else if (voxyModelRunner == null) {
                HelloTerrainMod.LOGGER.info(
                    "[LodGen] No Voxy model set found — using heightmap fallback generator");
            }

            VoxyIdMaps fallbackMaps = CanonicalVoxyMaps.from(voxyMapper, biomeRegistry);
            VoxelVolumeWriter fallbackWriter = RealVoxyVolumeWriter.create(worldEngine, fallbackMaps);

            waitForPlayerPosition();
            if (stopRequested.get()) return;

            HelloTerrainMod.LOGGER.info(
                    "[LodGen] Starting FALLBACK generation from player section ({}, {})",
                    playerSectionX, playerSectionZ);

            runFallbackPipeline(world, fallbackWriter);

        } catch (Exception e) {
            if (!stopRequested.get()) {
                HelloTerrainMod.LOGGER.error("[LodGen] Worker crashed: {}", e.getMessage(), e);
            }
        } finally {
            running.set(false);
            HelloTerrainMod.LOGGER.info("[LodGen] Worker exited");
        }
    }


        /**
     * Heightmap clip for native-resolution logits at a specific LOD level.
     *
     * <p>At LOD level {@code voxyLvl}, each voxel covers {@code 2^voxyLvl}
     * blocks along each axis.  A voxel is clipped to air if its <em>lowest</em>
     * block Y is at or above the surface height for the center of its XZ
     * footprint.
     *
     * @param logits    [1][N][D][D][D] where D = 16 >> voxyLvl
     * @param rawHm     [16][16] surface heightmap in [x][z] order
     * @param sectionY  L0 section Y coordinate
     * @param voxyLvl   Voxy storage level (1-4)
     */

    /**
     * Translate canonical biome IDs to Voxy biome IDs for a column.
     */

    /** Counter for detailed diagnostics (reset per LOD pass). Thread-safe. */
    private final AtomicInteger diagnosticCount = new AtomicInteger();

    // ------------------------------------------------------------------ //
    //  Per-column conditioning context
    // ------------------------------------------------------------------ //

    /**
     * Pre-sampled conditioning data for a single 16×16 column (chunk).
     * Sampled once per column and reused for all Y sections in that column.
     */
    record ColumnContext(
        float[][] rawHm,          // [16][16] surface heightmap in block Y
        int[][]   biomeIdx,       // [16][16] biome indices
        float[][] hp5,            // [5][16] height-planes (4×4, row-major)
        float[][] oceanFloorHm    // [16][16] ocean/river floor block-Y (may be null)
    ) {}

    /**
     * Build the conditioning context for a column, using the best available
     * data source.
     *
     * <p>Priority:
     * <ol>
     *   <li>{@link WorldNoiseAccess} — real heightmap + biome at any coordinate
     *       (no chunk needed)</li>
     *   <li>Loaded chunk — real heightmap + biome</li>
     *   <li>Synthetic — sine-wave heightmap + constant biome (last resort)</li>
     * </ol>
     */
    private ColumnContext buildColumnContext(World world, int sectionX, int sectionZ) {
        float[][] rawHm;
        int[][]   biomeIdx;
        float[][] hp5;
        float[][] oceanFloorHm = null;

        if (noiseAccess != null) {
            // *** PRIMARY PATH: Real noise data at any coordinate ***
            // Use HeightmapProvider from the upstream context when available;
            // fall back to AnchorSampler + WorldNoiseAccess for backward compat.
            if (samplerFactory != null) {
                try {
                    var ctx = samplerFactory.getUpstreamContext();
                    HeightmapData hmData = ctx.heightmapProvider()
                            .sampleHeightmaps(sectionX, sectionZ);
                    rawHm        = hmData.worldSurface();
                    oceanFloorHm = hmData.oceanFloor();

                        // Column biomes (2D, 16×16) for Voxy block writes.
                        // We sample at surface-Y at quart centres, expanding to block res.
                    String[][] biomeNames = noiseAccess.sampleBiomeNames(
                            sectionX, sectionZ, rawHm);
                    biomeIdx = new int[16][16];
                    for (int x = 0; x < 16; x++)
                        for (int z = 0; z < 16; z++)
                            biomeIdx[x][z] = BiomeMapping.toCanonicalId(biomeNames[x][z]);

                    hp5 = AnchorSampler.computeHeightPlanes(rawHm, oceanFloorHm);
                } catch (Exception e) {
                    // UpstreamNoiseContext unavailable (e.g. legacy 1-arg factory);
                    // fall back to original AnchorSampler path.
                    AnchorSampler.AnchorInputs anchor =
                            AnchorSampler.sampleFromNoise(noiseAccess, sectionX, sectionZ);
                    rawHm        = anchor.rawHm();
                    biomeIdx     = anchor.biomeIdx();
                    hp5          = anchor.heightPlanes5();
                    oceanFloorHm = anchor.oceanFloorHm();
                }
            } else {
                AnchorSampler.AnchorInputs anchor =
                        AnchorSampler.sampleFromNoise(noiseAccess, sectionX, sectionZ);
                rawHm        = anchor.rawHm();
                biomeIdx     = anchor.biomeIdx();
                hp5          = anchor.heightPlanes5();
                oceanFloorHm = anchor.oceanFloorHm();
            }
            noiseAccessSections.incrementAndGet();
            if (diagnosticCount.get() < 3) {
                HelloTerrainMod.LOGGER.info(
                        "[LodGen] Using NOISE ACCESS data for column ({},{}) — " +
                        "real heightmap + biome",
                        sectionX, sectionZ);
            }
        } else {
            Chunk chunk = tryGetLoadedChunk(world, sectionX, sectionZ);
            if (chunk != null) {
                rawHm    = AnchorSampler.sampleHeightmap(chunk);
                biomeIdx = AnchorSampler.sampleBiomes(chunk);
                realDataSections.incrementAndGet();
                if (diagnosticCount.get() < 3) {
                    HelloTerrainMod.LOGGER.info(
                            "[LodGen] Using REAL chunk data for column ({},{})",
                            sectionX, sectionZ);
                }
            } else {
                // Last resort — synthetic (should rarely happen with noise access)
                rawHm    = buildHeightmap(sectionX, sectionZ);
                biomeIdx = new int[16][16];
                for (int[] row : biomeIdx) java.util.Arrays.fill(row, 1);
                syntheticDataSections.incrementAndGet();
                if (diagnosticCount.get() < 3) {
                    HelloTerrainMod.LOGGER.info(
                            "[LodGen] Using SYNTHETIC data for column ({},{}) — " +
                            "chunk not loaded, no noise access.",
                            sectionX, sectionZ);
                }
            }
            hp5 = AnchorSampler.computeHeightPlanes(rawHm, null);  // no ocean floor in synthetic path
        }

        return new ColumnContext(rawHm, biomeIdx, hp5, oceanFloorHm);
    }

    /**
     * Get or build column context with caching. Thread-safe.
     * Avoids redundant noise sampling when multiple Y sections share
     * the same column.
     */
    private ColumnContext getOrBuildColumnContext(World world, int sx, int sz) {
        long key = ((long) (sx & 0xFFFF) << 16) | (sz & 0xFFFFL);
        return columnContextCache.computeIfAbsent(key, k -> buildColumnContext(world, sx, sz));
    }

    // ------------------------------------------------------------------ //
    //  Coarsen & diagnostics
    // ------------------------------------------------------------------ //



    // ------------------------------------------------------------------ //
    //  Input building (position-dependent synthetic conditioning)
    // ------------------------------------------------------------------ //

    /** Sea level in block Y coordinates. */
    static final float SEA_LEVEL = 62f;

    /** Amplitude of terrain height variation (blocks). */
    static final float HEIGHT_AMPLITUDE = 24f;

    /**
     * Build a raw heightmap (in block Y coordinates) for a 16×16 section
     * column at the given section (x, z).  Uses deterministic multi-octave
     * sine/cosine noise so that adjacent sections share consistent terrain
     * shape.
     *
     * @return float[16][16] of raw block-Y heights (approx 40–90 range)
     */
    static float[][] buildHeightmap(int sectionX, int sectionZ) {
        float[][] hm = new float[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // Global block coordinates
                float bx = sectionX * 16f + lx;
                float bz = sectionZ * 16f + lz;

                // Multi-octave sine noise for gentle rolling hills
                float h = SEA_LEVEL;
                h += HEIGHT_AMPLITUDE * 0.50f * (float) Math.sin(bx * 0.005 + 1.7)
                                              * (float) Math.cos(bz * 0.007 + 0.3);
                h += HEIGHT_AMPLITUDE * 0.25f * (float) Math.sin(bx * 0.013 + 3.1)
                                              * (float) Math.sin(bz * 0.011 + 2.2);
                h += HEIGHT_AMPLITUDE * 0.12f * (float) Math.cos(bx * 0.037 + 0.9)
                                              * (float) Math.sin(bz * 0.029 + 4.1);
                // Clamp to valid MC range
                hm[lx][lz] = Math.max(0f, Math.min(320f, h));
            }
        }
        return hm;
    }

    // ------------------------------------------------------------------ //
    //  Section spiral ordering
    // ------------------------------------------------------------------ //

    /**
     * Build a list of (x, z) section coordinates ordered by distance
     * from the center, limited to the given radius.
     *
     * <p>Every pass covers a <em>full disc</em> — finer LOD passes
     * naturally overwrite our earlier coarser data. Existing Voxy-native
     * sections from real chunk loading are preserved by the writer seam.
     *
     * @param distantFirst if true, sort furthest-from-center first so
     *                     distant horizon terrain appears immediately.
     */
    private List<int[]> buildSpiralSections(int centerX, int centerZ,
                                             int radius, boolean distantFirst) {
        List<int[]> sections = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                sections.add(new int[]{centerX + dx, centerZ + dz});
            }
        }
        // Sort by Manhattan distance — ascending (center-first) or
        // descending (horizon-first) depending on the pass.
        Comparator<int[]> cmp = Comparator.comparingInt(s ->
                Math.abs(s[0] - centerX) + Math.abs(s[1] - centerZ));
        sections.sort(distantFirst ? cmp.reversed() : cmp);
        return sections;
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /**
     * Try to get a loaded chunk from the world without blocking or
     * triggering generation.  Returns null if the chunk is not currently
     * loaded in the client (or server) chunk manager.
     *
     * <p>This is called from the worker thread; access is read-only so
     * it is safe on both client and server chunk managers.
     */
    private Chunk tryGetLoadedChunk(World world, int chunkX, int chunkZ) {
        try {
            return world.getChunkManager().getChunk(
                    chunkX, chunkZ, ChunkStatus.FULL, false);
        } catch (Exception e) {
            // Chunk manager threw — treat as not loaded
            return null;
        }
    }

    /**
     * True when a full vanilla chunk is currently loaded at this section X/Z.
     * Section coordinates on X/Z are 16-block aligned and match chunk coords.
     */
    private boolean isVanillaChunkLoaded(World world, int sectionX, int sectionZ) {
        return tryGetLoadedChunk(world, sectionX, sectionZ) != null;
    }

    /**
     * Pack section coordinates into a single long key for deduplication.
     * Each axis uses 20 bits, supporting ±524,287 sections.
     */
    static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFFL);
    }

    /**
     * Block until the client tick handler has supplied the player's real position.
     */
    private void waitForPlayerPosition() {
        for (int i = 0; i < 300; i++) {   // 30 seconds max
            if (stopRequested.get()) return;
            if (positionReady.get()) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
        HelloTerrainMod.LOGGER.warn("[LodGen] Timed out waiting for player position — using (0, 0)");
    }

    /**
     * Wait for the Voxy WorldEngine to become available (with timeout).
     */
    private Object waitForWorldEngine(World world) {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (stopRequested.get()) return null;

            Object engine = VoxyCompat.getWorldEngine(world);
            if (engine != null) return engine;

            HelloTerrainMod.LOGGER.debug("[LodGen] Waiting for Voxy WorldEngine (attempt {})", attempt);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  Heightmap fallback pipeline
    // ------------------------------------------------------------------ //

    /**
     * Ultra-fast fallback pipeline that generates terrain from heightmap data
     * alone, without any ONNX model.  Processes one column at a time on a
     * single thread — I/O ({@code insertUpdate}) is the bottleneck, not compute.
     *
     * <p>Reuses all existing infrastructure: spiral ordering, column context
     * caching, surface Y-range filtering, and deduplication.
     */
    private void runFallbackPipeline(World world, VoxelVolumeWriter writer) {
        if (decideEndL4TracerMode(world)) {
            HelloTerrainMod.LOGGER.error(
                    "[LodGen] Refusing heightmap fallback writes in The End");
            return;
        }
        Object worldEngine = (writer instanceof RealVoxyVolumeWriter r) ? r.getWorldEngine() : null;
        int totalSections = 0;
        int skippedAir = 0;
        int skippedExisting = 0;
        int columnsProcessed = 0;
        long startTime = System.currentTimeMillis();

        HelloTerrainMod.LOGGER.info(
                "[LodGen] Fallback pipeline starting — continuous mode, radius={}",
                GENERATION_RADIUS);

        // ── Continuous loop: re-spiral from player position each pass ───
        while (!stopRequested.get()) {
            int centerX = playerSectionX;
            int centerZ = playerSectionZ;

            List<int[]> columns = buildSpiralSections(centerX, centerZ,
                    GENERATION_RADIUS, false);

            boolean anyNew = false;

            for (int[] col : columns) {
                if (stopRequested.get()) break;

                int sx = col[0], sz = col[1];

                // If player moved far, restart spiral from new position
                int drift = Math.abs(playerSectionX - centerX)
                          + Math.abs(playerSectionZ - centerZ);
                if (drift > 2) break;

                // NOTE: We intentionally do NOT skip columns loaded by vanilla.
                // The per-section sectionExists() check below prevents overwriting
                // any sections Voxy has already ingested from real chunks, and
                // filling the rest avoids a visible gap between vanilla render
                // distance and the LOD terrain.

                // Get or build column context (cached across Y sections)
                ColumnContext ctx = getOrBuildColumnContext(world, sx, sz);


                // Compute Y range from surface heightmap
                float minH = Float.MAX_VALUE, maxH = -Float.MAX_VALUE;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        float h = ctx.rawHm()[lx][lz];
                        if (h < minH) minH = h;
                        if (h > maxH) maxH = h;
                    }
                }

                // Extend range down to cover water if surface is below sea level
                float effectiveMax = Math.max(maxH, HeightmapFallbackGenerator.SEA_LEVEL);

                int minSectionY = Math.max(
                        Math.floorDiv((int) Math.floor(minH), 16) - SURFACE_MARGIN,
                        Y_BASE_SECTION);
                int maxSectionY = Math.min(
                        Math.floorDiv((int) Math.ceil(effectiveMax), 16) + SURFACE_MARGIN,
                        Y_BASE_SECTION + Y_SECTIONS - 1);

                // Process all Y sections in this column
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    if (stopRequested.get()) break;

                    long key = sectionKey(sx, sy, sz);
                    if (generatedSections.contains(key)) continue;

                    // Never generate fallback terrain into sections backed by
                    // loaded vanilla chunks.
                    if (isVanillaChunkLoaded(world, sx, sz)) {
                        skippedExisting++;
                        generatedSections.add(key);
                        continue;
                    }

                    // Skip if Voxy already has real data for this section
                    if (VoxyCompat.sectionExists(worldEngine, sx, sy, sz)) {
                        skippedExisting++;
                        generatedSections.add(key);
                        continue;
                    }

                    SectionPos pos = new SectionPos(sx, sy, sz);
                    // Route through internal candidate seam (currently fallback for L0 sections)
                    VoxelVolume vol = selectCandidate().produceSection(pos, ctx);
                    WriteOutcome outcome;
                    try {
                        outcome = writer.writeSection(pos, vol);
                    } catch (VolumeUnavailableException e) {
                        HelloTerrainMod.LOGGER.warn("[LodGen] Fallback write unavailable: {}", e.getMessage());
                        generatedSections.add(key);
                        continue;
                    }
                    switch (outcome.status()) {
                        case WRITTEN -> {
                            totalSections++;
                            anyNew = true;
                            generatedSections.add(key);
                        }
                        case SKIPPED_AIR -> {
                            skippedAir++;
                            generatedSections.add(key);
                        }
                        case SKIPPED_EXISTS -> {
                            skippedExisting++;
                            generatedSections.add(key);
                        }
                        default -> throw new IllegalStateException("Unknown outcome: " + outcome.status());
                    }
                }

                columnsProcessed++;

                // Progress logging
                if (columnsProcessed % 100 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double sectionsPerSec = totalSections > 0
                            ? totalSections / (elapsed / 1000.0) : 0;
                    HelloTerrainMod.LOGGER.info(
                            "[LodGen] Fallback progress: {} columns, {} sections written, "
                            + "{} air-skipped, {} existing-skipped ({} sec, {} sections/s)",
                            columnsProcessed, totalSections,
                            skippedAir, skippedExisting,
                            elapsed / 1000, (int) sectionsPerSec);
                }

                // Track skipped sections above/below surface
                int generatedRange = maxSectionY - minSectionY + 1;
                skippedAirSections.addAndGet(Y_SECTIONS - generatedRange);
            }

            // If nothing new was generated (all in radius already done), idle
            if (!anyNew && !stopRequested.get()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    if (!stopRequested.get()) {
                        HelloTerrainMod.LOGGER.warn("[LodGen] Fallback interrupted");
                    }
                    break;
                }
            }

            // Periodically evict distant tracked sections to bound memory
            ChunkScheduler.evictDistantSections(
                    generatedSections, playerSectionX, playerSectionZ,
                    GENERATION_RADIUS * 10);
        }

        // Free cached column context
        columnContextCache.clear();

        long elapsed = System.currentTimeMillis() - startTime;
        HelloTerrainMod.LOGGER.info(
                "[LodGen] Fallback pipeline stopped — {} sections in {}.{}s "
                + "({} columns, {} air-skipped, {} existing-skipped)",
                totalSections, elapsed / 1000, elapsed % 1000,
                columnsProcessed, skippedAir, skippedExisting);
    }

    enum DemandProcessResult {
        WRITTEN,
        SKIPPED,
        DEFERRED,
        FAILED,
    }

    @FunctionalInterface
    interface HorizonLeafProcessor {
        DemandProcessResult process();
    }

    DemandProcessResult processEndPhysicalWork(
            VoxyRequestDecoder.VoxyNodeRequest req,
            VoxelVolumeWriter writer,
            HorizonLeafProcessor l4HorizonLeaf) {
        if (req == null || req.workKind == null) {
            return DemandProcessResult.FAILED;
        }
        if (req.workKind == net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT) {
            RefinementOutcome outcome = processEndRefinementRequest(req, writer);
            recordRefinementAttempt(req, outcome);
            return switch (outcome.status()) {
                case PUBLISHED -> DemandProcessResult.WRITTEN;
                case ALREADY_COVERED -> DemandProcessResult.SKIPPED;
                case BLOCKED_PARENT -> DemandProcessResult.DEFERRED;
                case FAILED -> DemandProcessResult.FAILED;
            };
        }
        if (writer == null || l4HorizonLeaf == null) return DemandProcessResult.FAILED;
        return switch (req.workKind) {
            case HORIZON_LEAF -> RefinementAdmissionGate.allows(req.workKind)
                    && req.lodLevel == Level.L4.value()
                    ? l4HorizonLeaf.process()
                    : DemandProcessResult.SKIPPED;
            case PARENT_REFINEMENT -> throw new IllegalStateException("handled above");
        };
    }

    private record Noise3dBundle(float[] noise3d, long[] biome3d, int[][] biomeForWrite) {}

    private record Climate2dBundle(float[] climate2d, long[] biome2d, int[][] biomeForWrite) {}

    /**
     * Demand-driven runtime pipeline consuming Voxy traversal requests from
     * ShadowRouterJobQueue and servicing them through VoxyModelRunner.
     */
    private boolean runDemandVoxyPipeline(World world, VoxelVolumeWriter writer) {
        int dequeued = 0;
        int written = 0;
        int skipped = 0;
        int deferred = 0;
        int failed = 0;
        long startMs = System.currentTimeMillis();
        long lastProgressLogMs = startMs;

        while (!stopRequested.get()) {
            VoxyRequestDecoder.VoxyNodeRequest req = ShadowRouterJobQueue.dequeueAny();
            if (req == null) {
                try {
                    Thread.sleep(DEMAND_IDLE_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            dequeued++;
            if (isFullyVanillaOccupancyRequest(req)) {
                ShadowRouterJobQueue.markSkippedFull(req);
                recordRefinementAttempt(req, RefinementOutcome.alreadyCovered());
                skipped++;
                continue;
            }

            boolean requeued = false;
            boolean failedRequest = false;
            try {
                DemandProcessResult result =
                        processDemandRequest(world, writer, req);
                switch (result) {
                    case WRITTEN -> written++;
                    case SKIPPED -> skipped++;
                    case DEFERRED -> {
                        deferred++;
                        ShadowRouterJobQueue.requeue(req);
                        requeued = true;
                    }
                    case FAILED -> {
                        failed++;
                        failedRequest = true;
                    }
                }

                long progressNow = System.currentTimeMillis();
                if (written == 1
                        || dequeued == 1
                        || progressNow - lastProgressLogMs >= DEMAND_PROGRESS_LOG_MS) {
                    logDemandProgress(startMs, dequeued, written, skipped, deferred, failed);
                    lastProgressLogMs = progressNow;
                }
            } finally {
                if (failedRequest) {
                    ShadowRouterJobQueue.markFailed(req);
                } else if (!requeued) {
                    ShadowRouterJobQueue.markCompleted(req);
                }
            }
        }

        logDemandProgress(startMs, dequeued, written, skipped, deferred, failed);
        HelloTerrainMod.LOGGER.info(
                "[LodGen] Demand pipeline stopped: dequeued={} written={} skipped={} deferred={} failed={} refine={} exactL1={}",
                dequeued, written, skipped, deferred, failed, refinementLifecycle.compact(),
                exactL1Sampling.compact());
        return written > 0;
    }

    private void logDemandProgress(long startMs, int dequeued,
                                   int written, int skipped,
                                   int deferred, int failed) {
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startMs);
        HelloTerrainMod.LOGGER.info(
                "[LodGen][Demand] dequeued={} written={} skipped={} deferred={} failed={} inFlight={} demand={} refine={} exactL1={} elapsed={}s",
                dequeued, written, skipped, deferred, failed,
                ShadowRouterJobQueue.inFlightSize(), ShadowRouterJobQueue.demandMetrics().compact(),
                refinementLifecycle.compact(),
                exactL1Sampling.compact(),
                elapsedMs / 1000);
    }

    /**
     * Orchestrator for one demand request. Reaches into {@link VoxyCompat}
     * / {@link VoxyWorldBinding} intentionally — this is the demand
     * pipeline coordinator, not Feature Envy. Fine-grained helpers
     * ({@code isRegionReady}, {@code computeOccupancy}) live on the
     * binding; this method sequences them. Mask/octant logic stays on
     * {@code VoxyWorldBinding} (see {@code computeChildExistenceMask},
     * {@code computeOccupiedOctantMask}); this method only decides
     * skip/defer/write.
     */
    private DemandProcessResult processDemandRequest(World world,
                                                     VoxelVolumeWriter writer,
                                                     VoxyRequestDecoder.VoxyNodeRequest req) {
        if (req == null) {
            return DemandProcessResult.FAILED;
        }
        if (decideEndL4TracerMode(world)) {
            return processEndPhysicalWork(
                    req, writer, () -> processDemandLeaf(world, writer, req));
        }
        if (writer == null) return DemandProcessResult.FAILED;
        return processDemandLeaf(world, writer, req);
    }

    private DemandProcessResult processDemandLeaf(World world,
                                                   VoxelVolumeWriter writer,
                                                   VoxyRequestDecoder.VoxyNodeRequest req) {
        Object worldEngine = (writer instanceof RealVoxyVolumeWriter r) ? r.getWorldEngine() : null;
        if (voxyModelRunner == null || samplerFactory == null) {
            return DemandProcessResult.FAILED;
        }

        int level = req.lodLevel;
        int wsX = req.worldX;
        int wsY = req.worldY;
        int wsZ = req.worldZ;

        if (level < 0 || level > 4) return DemandProcessResult.SKIPPED;
        if (!voxyModelRunner.hasLevel(level)) return DemandProcessResult.SKIPPED;
        if (isOutOfWorldY(level, wsY)) return DemandProcessResult.SKIPPED;
        if (VoxyCompat.allOctantsPopulated(worldEngine, level, wsX, wsY, wsZ)) {
            return DemandProcessResult.SKIPPED;
        }

        // Dependency scheduling: finer levels require a parent section to exist first.
        long[] parentInput = null;
        if (level < 4) {
            int parentLevel = level + 1;
            int pX = wsX >> 1;
            int pY = wsY >> 1;
            int pZ = wsZ >> 1;

            if (!VoxyCompat.sectionExistsAtLevel(worldEngine, parentLevel, pX, pY, pZ)) {
                enqueueParentRequest(parentLevel, pX, pY, pZ);
                return DemandProcessResult.DEFERRED;
            }

            int octant = (wsX & 1) | ((wsZ & 1) << 1) | ((wsY & 1) << 2);
            parentInput = VoxyWorldBinding.readAndUpsampleParentOctant(
                    worldEngine, parentLevel, pX, pY, pZ, octant);
            if (parentInput == null) {
                enqueueParentRequest(parentLevel, pX, pY, pZ);
                return DemandProcessResult.DEFERRED;
            }
        }

        // +4 voxel-center offset (see VOXEL_CENTER_OFFSET): centers sample in 32^3 WorldSection
        int yPosition = (wsY << (level + 1)) + VOXEL_CENTER_OFFSET;
        VoxyModelRunner.LevelResult modelOut = null;
        int[][] biome32;
        long inferStartNs;

        if (level <= 1) {
            Noise3dBundle bundle = buildNoise3dInput(level, wsX, wsY, wsZ);
            if (bundle == null) return DemandProcessResult.FAILED;

            inferStartNs = System.nanoTime();
            logInferenceStart(level, wsX, wsY, wsZ, yPosition);
            try {
                modelOut = level == 1
                        ? voxyModelRunner.runL1(bundle.noise3d(), bundle.biome3d(), yPosition, parentInput)
                        : voxyModelRunner.runL0(bundle.noise3d(), bundle.biome3d(), yPosition, parentInput);
            } finally {
                logInferenceEnd(level, wsX, wsY, wsZ, yPosition, inferStartNs, modelOut != null);
            }
            biome32 = upsampleBiomeTo32(bundle.biomeForWrite());
        } else {
            Climate2dBundle bundle = buildClimate2dInput(level, wsX, wsY, wsZ);
            if (bundle == null) return DemandProcessResult.FAILED;

            inferStartNs = System.nanoTime();
            logInferenceStart(level, wsX, wsY, wsZ, yPosition);
            try {
                modelOut = switch (level) {
                    case 4 -> voxyModelRunner.runL4(bundle.climate2d(), bundle.biome2d(), yPosition);
                    case 3 -> voxyModelRunner.runL3(bundle.climate2d(), bundle.biome2d(), yPosition, parentInput);
                    case 2 -> voxyModelRunner.runL2(bundle.climate2d(), bundle.biome2d(), yPosition, parentInput);
                    default -> null;
                };
            } finally {
                logInferenceEnd(level, wsX, wsY, wsZ, yPosition, inferStartNs, modelOut != null);
            }
            biome32 = upsampleBiomeTo32(bundle.biomeForWrite());
        }

        if (modelOut == null) {
            return DemandProcessResult.FAILED;
        }

        // Do not skip writing the requested level when occupancy predicts no child expansion.
        // Voxy can still render this coarse level as fallback while finer children are absent.

        byte preserveMask = loadedChunkOctantMask(world, level, wsX, wsY, wsZ);
        if (preserveMask == (byte) 0xFF) {
            return DemandProcessResult.SKIPPED;
        }
        VoxelVolume vol = VoxelPredictionDecoder.fromOctreeArgmax(modelOut.blocks(), biome32);
        int sx0 = WorldSectionCoord.worldSectionToBlockMin(wsX, level) >> 4;
        int sy0 = WorldSectionCoord.worldSectionToBlockMin(wsY, level) >> 4;
        int sz0 = WorldSectionCoord.worldSectionToBlockMin(wsZ, level) >> 4;
        SectionPos origin = new SectionPos(sx0, sy0, sz0);
        Level lvl = Level.values()[level];
        WriteOutcome outcome;
        try {
            outcome = writer.writeRegion(origin, lvl, vol);
        } catch (VolumeUnavailableException e) {
            HelloTerrainMod.LOGGER.warn("[LodGen] Octree write unavailable: {}", e.getMessage());
            return DemandProcessResult.SKIPPED;
        }
        return outcome.status() == WriteOutcome.Status.WRITTEN ? DemandProcessResult.WRITTEN : DemandProcessResult.SKIPPED;
    }

    private void logInferenceStart(int level, int wsX, int wsY, int wsZ, int yPosition) {
        HelloTerrainMod.LOGGER.info(
                "[LodGen][Infer] start lod={} ws=({}, {}, {}) yPos={}",
                level, wsX, wsY, wsZ, yPosition);
    }

    private void logInferenceEnd(int level, int wsX, int wsY, int wsZ,
                                 int yPosition, long startNs, boolean ok) {
        long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        HelloTerrainMod.LOGGER.info(
                "[LodGen][Infer] end lod={} ws=({}, {}, {}) yPos={} ok={} elapsedMs={}",
                level, wsX, wsY, wsZ, yPosition, ok, elapsedMs);
    }

    private Noise3dBundle buildNoise3dInput(int level, int wsX, int wsY, int wsZ) {
        int axisTiles = 1 << (level + 1); // L0=2, L1=4
        int xCells = axisTiles * 4;
        int yCells = axisTiles * 2;
        int zCells = axisTiles * 4;

        float[] noise = new float[RouterField.COUNT * xCells * yCells * zCells];
        long[] biome = new long[xCells * yCells * zCells];

        Map<Long, SectionNoiseData> noiseCache = new HashMap<>();
        Map<Long, int[][][]> biomeCache = new HashMap<>();

        NoiseRouterSampler sampler = samplerFactory.getSampler();
        BiomeProvider bp = null;
        try {
            bp = samplerFactory.getUpstreamContext().biomeProvider();
        } catch (Exception ignored) {
        }

        int sxBase = wsX << (level + 1);
        int syBase = wsY << (level + 1);
        int szBase = wsZ << (level + 1);

        for (int tx = 0; tx < axisTiles; tx++) {
            for (int ty = 0; ty < axisTiles; ty++) {
                for (int tz = 0; tz < axisTiles; tz++) {
                    int sx = sxBase + tx;
                    int sy = syBase + ty;
                    int sz = szBase + tz;
                    long key = sectionKey(sx, sy, sz);

                    SectionNoiseData snd = noiseCache.get(key);
                    if (snd == null) {
                        snd = sampler.sampleSection(sx, sy, sz);
                        noiseCache.put(key, snd);
                    }

                    int[][][] b = biomeCache.get(key);
                    if (b == null) {
                        b = new int[4][2][4];
                        if (bp != null) {
                            try {
                                b = bp.classifyBiomes(sx, sy, sz, snd);
                            } catch (Exception ignored) {
                            }
                        }
                        biomeCache.put(key, b);
                    }

                    for (int qx = 0; qx < 4; qx++) {
                        for (int qy = 0; qy < 2; qy++) {
                            for (int qz = 0; qz < 4; qz++) {
                                int gx = tx * 4 + qx;
                                int gy = ty * 2 + qy;
                                int gz = tz * 4 + qz;

                                int dstBiome = ((gx * yCells) + gy) * zCells + gz;
                                biome[dstBiome] = b[qx][qy][qz];

                                int srcOff = qx * 8 + qy * 4 + qz;
                                for (int c = 0; c < RouterField.COUNT; c++) {
                                    int src = c * SectionNoiseData.CELLS_PER_FIELD + srcOff;
                                    int dst = (((c * xCells) + gx) * yCells + gy) * zCells + gz;
                                    noise[dst] = snd.flat()[src];
                                }
                            }
                        }
                    }
                }
            }
        }

        int midY = yCells / 2;
        int[][] biome2d = new int[zCells][xCells];
        for (int x = 0; x < xCells; x++) {
            for (int z = 0; z < zCells; z++) {
                int idx = ((x * yCells) + midY) * zCells + z;
                biome2d[z][x] = (int) biome[idx];
            }
        }

        return new Noise3dBundle(noise, biome, biome2d);
    }

    private Climate2dBundle buildClimate2dInput(int level, int wsX, int wsY, int wsZ) {
        int[] channels = level == 2 ? L2_CLIMATE_CHANNELS : L3_L4_CLIMATE_CHANNELS;
        float[] climate = new float[channels.length * 8 * 8];
        long[] biome2d = new long[8 * 8];
        int[][] biomeForWrite = new int[8][8];

        int axisTiles = 1 << (level + 1);
        int nativeX = axisTiles * 4;
        int nativeY = axisTiles * 2;
        int nativeZ = axisTiles * 4;

        int sxBase = wsX << (level + 1);
        int syBase = wsY << (level + 1);
        int szBase = wsZ << (level + 1);
        int srcY = nativeY / 2;

        NoiseRouterSampler sampler = samplerFactory.getSampler();
        BiomeProvider bp = null;
        try {
            bp = samplerFactory.getUpstreamContext().biomeProvider();
        } catch (Exception ignored) {
        }

        Map<Long, SectionNoiseData> noiseCache = new HashMap<>();
        Map<Long, int[][][]> biomeCache = new HashMap<>();

        for (int oz = 0; oz < 8; oz++) {
            for (int ox = 0; ox < 8; ox++) {
                int srcX = (ox * (nativeX - 1)) / 7;
                int srcZ = (oz * (nativeZ - 1)) / 7;

                int tx = srcX / 4;
                int ty = srcY / 2;
                int tz = srcZ / 4;
                int qx = srcX % 4;
                int qy = srcY % 2;
                int qz = srcZ % 4;

                int sx = sxBase + tx;
                int sy = syBase + ty;
                int sz = szBase + tz;
                long key = sectionKey(sx, sy, sz);

                SectionNoiseData snd = noiseCache.get(key);
                if (snd == null) {
                    snd = sampler.sampleSection(sx, sy, sz);
                    noiseCache.put(key, snd);
                }

                int[][][] b = biomeCache.get(key);
                if (b == null) {
                    b = new int[4][2][4];
                    if (bp != null) {
                        try {
                            b = bp.classifyBiomes(sx, sy, sz, snd);
                        } catch (Exception ignored) {
                        }
                    }
                    biomeCache.put(key, b);
                }

                int off = qx * 8 + qy * 4 + qz;
                int spatial = oz * 8 + ox;
                for (int c = 0; c < channels.length; c++) {
                    climate[(c * 64) + spatial] =
                            snd.flat()[channels[c] * SectionNoiseData.CELLS_PER_FIELD + off];
                }

                int biomeId = b[qx][qy][qz];
                biome2d[spatial] = biomeId;
                biomeForWrite[oz][ox] = biomeId;
            }
        }

        return new Climate2dBundle(climate, biome2d, biomeForWrite);
    }

    private int[][] upsampleBiomeTo32(int[][] smallBiomeZx) {
        int srcZ = smallBiomeZx.length;
        int srcX = smallBiomeZx[0].length;
        int[][] out = new int[32][32];
        for (int z = 0; z < 32; z++) {
            int sz = (z * srcZ) / 32;
            for (int x = 0; x < 32; x++) {
                int sx = (x * srcX) / 32;
                out[z][x] = smallBiomeZx[sz][sx];
            }
        }
        return out;
    }


    private void enqueueParentRequest(int parentLevel, int pX, int pY, int pZ) {
        VoxyRequestDecoder.VoxyNodeRequest parentReq = new VoxyRequestDecoder.VoxyNodeRequest();
        parentReq.lodLevel = parentLevel;
        parentReq.worldX = pX;
        parentReq.worldY = pY;
        parentReq.worldZ = pZ;
        parentReq.demandKind = VoxyDemandKind.HORIZON_COVERAGE;
        parentReq.workKind = net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF;
        parentReq.demandSource = VoxyDemandSource.PARENT_DEPENDENCY;
        ShadowRouterJobQueue.enqueue(parentReq);
    }

    private byte loadedChunkOctantMask(World world, int level, int wsX, int wsY, int wsZ) {
        if (level < 0 || level > 4) {
            return 0;
        }

        if (level == 0) {
            return loadedVanillaL0OctantMask(world, wsX, wsY, wsZ);
        }

        int chunkSpanPerAxis = 1 << (level + 1);
        int chunkSpanPerOctant = 1 << level;
        int baseChunkX = wsX * chunkSpanPerAxis;
        int baseChunkZ = wsZ * chunkSpanPerAxis;
        byte mask = 0;

        // Preserve any octant whose X/Z footprint overlaps a currently loaded
        // vanilla chunk column. We intentionally ignore Y here: if vanilla owns
        // any part of the column, do not let coarse LOD writes replace it.
        for (int octant = 0; octant < 8; octant++) {
            int chunkStartX = baseChunkX + ((octant & 1) * chunkSpanPerOctant);
            int chunkStartZ = baseChunkZ + (((octant >> 1) & 1) * chunkSpanPerOctant);
            boolean loaded = false;
            for (int dx = 0; dx < chunkSpanPerOctant && !loaded; dx++) {
                for (int dz = 0; dz < chunkSpanPerOctant; dz++) {
                    if (tryGetLoadedChunk(world, chunkStartX + dx, chunkStartZ + dz) != null) {
                        loaded = true;
                        break;
                    }
                }
            }
            if (loaded) {
                mask |= (byte) (1 << octant);
            }
        }
        return mask;
    }

    /**
     * At L0, preserve only octants whose corresponding loaded vanilla 16^3 slice
     * actually contains non-air blocks.
     *
     * <p>Using mere chunk-column presence here creates a one-chunk moat around the
     * detailed terrain: any loaded chunk overlapping the 32^3 WorldSection causes
     * its whole 16x16 XZ octant to be skipped, even if that particular Y half is
     * empty or not rendered by vanilla. That produces the persistent 16-block gap
     * seen at the edge of the detailed region.
     */
    private byte loadedVanillaL0OctantMask(World world, int wsX, int wsY, int wsZ) {
        int baseChunkX = wsX << 1;
        int baseChunkZ = wsZ << 1;
        int baseBlockY = wsY << 5; // wsY * 32
        byte mask = 0;
        BlockPos.Mutable probePos = new BlockPos.Mutable();

        for (int octant = 0; octant < 8; octant++) {
            int chunkX = baseChunkX + (octant & 1);
            int chunkZ = baseChunkZ + ((octant >> 1) & 1);
            int blockYStart = baseBlockY + (((octant >> 2) & 1) << 4);

            Chunk chunk = tryGetLoadedChunk(world, chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }

            boolean occupied = false;
            int blockXStart = chunkX << 4;
            int blockZStart = chunkZ << 4;
            for (int localY = 0; localY < 16 && !occupied; localY++) {
                int blockY = blockYStart + localY;
                for (int localZ = 0; localZ < 16 && !occupied; localZ++) {
                    int blockZ = blockZStart + localZ;
                    for (int localX = 0; localX < 16; localX++) {
                        int blockX = blockXStart + localX;
                        if (!chunk.getBlockState(probePos.set(blockX, blockY, blockZ)).isAir()) {
                            occupied = true;
                            break;
                        }
                    }
                }
            }

            if (occupied) {
                mask |= (byte) (1 << octant);
            }
        }

        return mask;
    }

    // --- End L4 tracer pipeline (L4-only, model-free) ---

    /**
     * Tracer-mode demand pipeline: services 121 L4 requests via
     * {@link EndL4DeterministicCandidate} and {@link VoxelVolumeWriter#writeRegion}.
     * L4 only — disables L3/L2/L1/L0 seeding and partial-fill descent.
     * Reuses dedup/backpressure via {@link ShadowRouterJobQueue}.
     * Direct full-WorldSection path via {@link VoxyWorldBinding#writeFullWorldSection}
     * owns completed geometry publication; future refinement comes from the
     * screen-space selector.
     *
     * @return true if at least one region was written
     */
    private boolean runEndL4TracerPipeline(World world, VoxelVolumeWriter writer) {
        if (noiseAccess == null) {
            HelloTerrainMod.LOGGER.warn("[LodGen][Tracer] no noiseAccess — aborting tracer pipeline");
            return false;
        }
        EndL4DeterministicCandidate tracerCandidate = new EndL4DeterministicCandidate(noiseAccess);
        if (tracerStartMs == 0) {
            tracerStartMs = System.currentTimeMillis();
        }
        long startMs = tracerStartMs;
        long lastProgressLogMs = startMs;

        while (!stopRequested.get()) {
            try {
                runScheduledEndSelection(
                        System.currentTimeMillis(), REFINEMENT_BUDGET_PER_PASS);
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn(
                        "[LodGen][Refine] selection pass failed: {}", e.getMessage());
            }
            VoxyRequestDecoder.VoxyNodeRequest req = ShadowRouterJobQueue.dequeueAny();
            if (req == null) {
                try {
                    Thread.sleep(DEMAND_IDLE_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // In tracer mode, idle after 121 means horizon filled — keep idling
                // until stopRequested; do not busy-loop.
                continue;
            }

            if (isFullyVanillaOccupancyRequest(req)) {
                ShadowRouterJobQueue.markSkippedFull(req);
                recordRefinementAttempt(req, RefinementOutcome.alreadyCovered());
                // Boundary work is outside the fixed initial L4 tracer batch.
                // It must not advance that batch's terminal accounting.
                if (req.demandSource != VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY) {
                    tracerSkipped.incrementAndGet();
                    maybeEmitTracerTerminal();
                }
                continue;
            }

            // Route complete parent transactions before native L4 horizon work.
            if (req.workKind == net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT) {
                RefinementOutcome refOutcome = processEndRefinementRequest(req, writer);
                recordRefinementAttempt(req, refOutcome);
                recordFrontierGuardOutcomeForTest(req, refOutcome);
                if (refOutcome.status() == RefinementOutcome.Status.BLOCKED_PARENT) {
                    ShadowRouterJobQueue.requeue(req);
                } else if (refOutcome.status() == RefinementOutcome.Status.FAILED) {
                    ShadowRouterJobQueue.markFailed(req);
                } else {
                    ShadowRouterJobQueue.markCompleted(req);
                }
                if (refOutcome.status() == RefinementOutcome.Status.PUBLISHED) {
                    refinementWritten.incrementAndGet();
                } else if (refOutcome.status() == RefinementOutcome.Status.FAILED) {
                    refinementFailed.incrementAndGet();
                }
                continue;
            }

            if (req.workKind != net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF
                    || req.lodLevel != Level.L4.value()
                    || req.worldY != END_L4_TRACER_WS_Y) {
                ShadowRouterJobQueue.markCompleted(req);
                tracerSkipped.incrementAndGet();
                maybeEmitTracerTerminal();
                continue;
            }

            int wsX = req.worldX;
            int wsY = req.worldY;
            int wsZ = req.worldZ;

            if (isOutOfWorldY(4, wsY)) {
                ShadowRouterJobQueue.markCompleted(req);
                tracerSkipped.incrementAndGet();
                maybeEmitTracerTerminal();
                continue;
            }

            // Preserve vanilla octants where present (Handoff Q7 B zero-gap: vanilla wins)
            byte preserveMask = loadedChunkOctantMask(world, 4, wsX, wsY, wsZ);
            if (preserveMask == (byte) 0xFF) {
                ShadowRouterJobQueue.markSkippedFull(req);
                tracerSkipped.incrementAndGet();
                maybeEmitTracerTerminal();
                continue;
            }

            int sx0 = WorldSectionCoord.worldSectionToBlockMin(wsX, 4) >> 4;
            int sy0 = WorldSectionCoord.worldSectionToBlockMin(wsY, 4) >> 4;
            int sz0 = WorldSectionCoord.worldSectionToBlockMin(wsZ, 4) >> 4;
            SectionPos origin = new SectionPos(sx0, sy0, sz0);

            if (writer.isRegionFullyPopulated(origin, Level.L4)) {
                ShadowRouterJobQueue.markCompleted(req);
                tracerSkipped.incrementAndGet();
                maybeEmitTracerTerminal();
                continue;
            }

            boolean writeFailed = false;
            try {
                VoxelVolume vol = tracerCandidate.produceRegion(Level.L4, origin);
                WriteOutcome outcome = writer.writeRegion(origin, Level.L4, vol);
                if (outcome.status() == WriteOutcome.Status.WRITTEN) {
                    tracerWritten.incrementAndGet();
                } else {
                    tracerSkipped.incrementAndGet();
                }
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn(
                        "[LodGen][Tracer] write failed ws=({},{},{}): {}",
                        wsX, wsY, wsZ, e.getMessage());
                tracerFailed.incrementAndGet();
                writeFailed = true;
            } finally {
                if (writeFailed) {
                    ShadowRouterJobQueue.markFailed(req);
                } else {
                    ShadowRouterJobQueue.markCompleted(req);
                }
            }
            maybeEmitTracerTerminal();

            long now = System.currentTimeMillis();
            int w = tracerWritten.get();
            int s = tracerSkipped.get();
            int f = tracerFailed.get();
            if (w == 1 || now - lastProgressLogMs >= DEMAND_PROGRESS_LOG_MS) {
                HelloTerrainMod.LOGGER.info(
                        "[LodGen][Tracer] dequeued written={} skipped={} failed={} inFlight={} demand={} refine={} exactL1={}",
                        w, s, f, ShadowRouterJobQueue.inFlightSize(),
                        ShadowRouterJobQueue.demandMetrics().compact(),
                        refinementLifecycle.compact(), exactL1Sampling.compact());
                lastProgressLogMs = now;
            }
        }

        HelloTerrainMod.LOGGER.info(
                "[LodGen][Tracer] stopped: written={} skipped={} failed={} demand={} refine={} exactL1={}",
                tracerWritten.get(), tracerSkipped.get(), tracerFailed.get(),
                ShadowRouterJobQueue.demandMetrics().compact(), refinementLifecycle.compact(),
                exactL1Sampling.compact());
        return tracerWritten.get() > 0;
    }

    // Package-private single-request entry for unit tests (no loop/sleep)
    WriteOutcome processTracerRequestForTest(VoxyRequestDecoder.VoxyNodeRequest req,
                                              VoxelVolumeWriter writer, World world) {
        if (req == null || writer == null || noiseAccess == null) {
            return null;
        }
        if (tracerStartMs == 0) {
            tracerStartMs = System.currentTimeMillis();
        }
        if (req.lodLevel != 4 || req.worldY != END_L4_TRACER_WS_Y) {
            tracerSkipped.incrementAndGet();
            maybeEmitTracerTerminal();
            return WriteOutcome.skippedExists();
        }
        int wsX = req.worldX;
        int wsY = req.worldY;
        int wsZ = req.worldZ;
        int sx0 = WorldSectionCoord.worldSectionToBlockMin(wsX, 4) >> 4;
        int sy0 = WorldSectionCoord.worldSectionToBlockMin(wsY, 4) >> 4;
        int sz0 = WorldSectionCoord.worldSectionToBlockMin(wsZ, 4) >> 4;
        SectionPos origin = new SectionPos(sx0, sy0, sz0);
        if (writer.isRegionFullyPopulated(origin, Level.L4)) {
            tracerSkipped.incrementAndGet();
            maybeEmitTracerTerminal();
            return WriteOutcome.skippedExists();
        }
        try {
            EndL4DeterministicCandidate tracerCandidate = new EndL4DeterministicCandidate(noiseAccess);
            VoxelVolume vol = tracerCandidate.produceRegion(Level.L4, origin);
            WriteOutcome outcome = writer.writeRegion(origin, Level.L4, vol);
            if (outcome.status() == WriteOutcome.Status.WRITTEN) {
                tracerWritten.incrementAndGet();
            } else {
                tracerSkipped.incrementAndGet();
            }
            maybeEmitTracerTerminal();
            return outcome;
        } catch (Exception e) {
            tracerFailed.incrementAndGet();
            maybeEmitTracerTerminal();
            return null;
        }
    }

    /**
     * Test-only helper to record a terminal failure without a writer.
     * Increments failed and checks for terminal emission.
     */
    void recordTracerFailureForTest() {
        if (tracerStartMs == 0) {
            tracerStartMs = System.currentTimeMillis();
        }
        tracerFailed.incrementAndGet();
        maybeEmitTracerTerminal();
    }

    void recordTracerWrittenForTest() {
        if (tracerStartMs == 0) {
            tracerStartMs = System.currentTimeMillis();
        }
        tracerWritten.incrementAndGet();
        maybeEmitTracerTerminal();
    }

    void recordTracerSkippedForTest() {
        if (tracerStartMs == 0) {
            tracerStartMs = System.currentTimeMillis();
        }
        tracerSkipped.incrementAndGet();
        maybeEmitTracerTerminal();
    }

    void setNoiseAccessForTest(WorldNoiseAccess access) {
        this.noiseAccess = access;
    }

    private VoxyModelRunner resolveVoxyModel() {
        CompletableFuture<VoxyModelRunner> future = preloadFuture;
        preloadFuture = null;  // consume so we don't reuse a stale instance

        if (future != null) {
            try {
                HelloTerrainMod.LOGGER.info("[LodGen] Waiting for pre-loaded VoxyModelRunner...");
                VoxyModelRunner preloaded = future.get(60, TimeUnit.SECONDS);
                if (preloaded != null) {
                    HelloTerrainMod.LOGGER.info("[LodGen] Using pre-loaded VoxyModelRunner");
                    return preloaded;
                }
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn(
                        "[LodGen] Pre-load future failed — falling back to synchronous load: {}",
                        e.getMessage());
            }
        }

        java.nio.file.Path modelDir = Config.modelDir();
        try {
            HelloTerrainMod.LOGGER.info("[LodGen] Loading VoxyModelRunner from {}...", modelDir);
            return VoxyModelRunner.tryLoad(modelDir);
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn("[LodGen] VoxyModelRunner load failed: {}", e.getMessage());
            return null;
        }
    }

}
