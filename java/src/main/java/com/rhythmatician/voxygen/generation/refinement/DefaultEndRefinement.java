package com.rhythmatician.voxygen.generation.refinement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.output.WriteOutcome;
import com.rhythmatician.voxygen.semantic.WorldSectionCoord;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult;
import com.rhythmatician.voxygen.generation.scheduling.VanillaFrontierGuardPlanner;
import com.rhythmatician.voxygen.generation.scheduling.VanillaOccupancyPyramid;
import com.rhythmatician.voxygen.generation.session.GenerationSession;

/** Default implementation of the deep {@link EndRefinement} module. */
@SuppressWarnings("null")
public final class DefaultEndRefinement implements EndRefinement {
    private static final int HORIZON_BURST = 4;

    public static Config productionConfig() {
        return new Config(
                com.rhythmatician.lodiffusion.Config.getInt("endRefinementFocalPx", 1000),
                com.rhythmatician.lodiffusion.Config.getInt("endRefinementSubDivPx", 64),
                com.rhythmatician.lodiffusion.Config.getInt("endRefinementBudgetPerPass", 256),
                Math.max(1, com.rhythmatician.lodiffusion.Config.getInt(
                        "endRefinementVisualOutstandingTarget", 16)),
                com.rhythmatician.lodiffusion.Config.getInt(
                        "endRefinementRenderDistanceBlocks", 8192),
                com.rhythmatician.lodiffusion.Config.getInt("nearSeedIntervalMs", 1000),
                Math.max(1, com.rhythmatician.lodiffusion.Config.getInt(
                        "endRefinementMaxAttempts", 3)),
                Math.max(1L, com.rhythmatician.lodiffusion.Config.getInt(
                        "endRefinementRetryBackoffMs", 50)),
                GenerationSession.END_L4_TRACER_TOTAL);
    }

    public record Config(int focalPx, int subdivisionPx, int selectionBudget,
                  int visualWorkingSet, double renderDistanceBlocks,
                  long selectionIntervalMillis, int maxAttempts,
                  long retryBackoffMillis, int initialHorizonSize) {
        public Config(int focalPx, int subdivisionPx, int selectionBudget,
               int visualWorkingSet, double renderDistanceBlocks,
               long selectionIntervalMillis) {
            this(focalPx, subdivisionPx, selectionBudget, visualWorkingSet,
                    renderDistanceBlocks, selectionIntervalMillis, 3, 50, 0);
        }

        public Config {
            if (focalPx <= 0 || subdivisionPx <= 0 || selectionBudget < 0
                    || visualWorkingSet <= 0 || renderDistanceBlocks <= 0
                    || selectionIntervalMillis < 0 || maxAttempts <= 0
                    || retryBackoffMillis <= 0 || initialHorizonSize < 0) {
                throw new IllegalArgumentException("invalid End refinement config");
            }
        }
    }

    @FunctionalInterface
    public interface ChildTerrain {
        VoxelVolume produce(Level level, SectionPos origin);
    }

    @FunctionalInterface
    public interface ParentWriter {
        ParentRefinementResult refine(ParentRefinementIntent intent);
    }

    @FunctionalInterface
    public interface HorizonCoverage {
        WriteOutcome ensure(SectionPos l4Origin);
    }

    private enum Urgency { FRONTIER, VISUAL }

    private enum PrerequisiteOutcome {
        UNRESOLVED,
        RENDERABLE,
        TERMINAL_EMPTY,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE
    }

    private enum HorizonStatus {
        UNRESOLVED,
        EXECUTING,
        WRITTEN,
        EXISTING,
        TERMINAL_EMPTY,
        RETRYABLE_FAILURE,
        EXHAUSTED_FAILURE
    }

    private record ParentKey(Level level, SectionPos origin) {
        ParentKey {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(origin, "origin");
            if (level == Level.L0 || !level.isAligned(origin)) {
                throw new IllegalArgumentException("parent must be aligned L1..L4");
            }
        }
    }

    private record Dependency(ParentKey dependent, int prerequisiteBit) {}

    private record ParentPrerequisite(ParentKey parent, int requiredBit) {}

    private record HorizonPrerequisite(SectionPos origin) {}

    private static final class ParentState {
        int pending;
        int executing;
        int represented;
        int deterministicEmpty;
        int vanillaCovered;
        int retryable;
        final int[] attempts = new int[8];
        final long[] nextEligibleMillis = new long[8];
        Urgency urgency = Urgency.VISUAL;
        boolean queued;
        double distance;
        Object prerequisite;
    }

    private static final class HorizonState {
        final boolean initial;
        HorizonStatus status = HorizonStatus.UNRESOLVED;
        int attempts;
        long nextEligibleMillis;

        HorizonState(boolean initial) {
            this.initial = initial;
        }

        boolean terminal() {
            return status == HorizonStatus.WRITTEN
                    || status == HorizonStatus.EXISTING
                    || status == HorizonStatus.TERMINAL_EMPTY
                    || status == HorizonStatus.EXHAUSTED_FAILURE;
        }
    }

    private record ParentWork(ParentKey parent, Urgency urgency, double distance) {}

    private record HorizonWork(SectionPos origin, boolean initial) {}

    private sealed interface Claim permits HorizonClaim, ParentClaim, SkippedClaim {
        long epoch();
    }

    private record HorizonClaim(long epoch, SectionPos origin, boolean initial) implements Claim {}

    private record ParentClaim(long epoch, ParentKey parent, int requestedMask,
                               int materializeMask, int vanillaMask,
                               Urgency urgency) implements Claim {}

    private record SkippedClaim(long epoch) implements Claim {}

    private final Config config;
    private final ParentWriter writer;
    private final ChildTerrain terrain;
    private final HorizonCoverage horizon;
    private final BooleanSupplier refinementAdmission;
    private final VanillaOccupancyPyramid occupancy = new VanillaOccupancyPyramid();
    private final RefinementLifecycleTelemetry lifecycle = new RefinementLifecycleTelemetry();
    private final Map<ParentKey, ParentState> parents = new HashMap<>();
    private final Map<ParentKey, Set<Dependency>> blockedDependents = new HashMap<>();
    private final Map<SectionPos, Set<ParentKey>> horizonDependents = new HashMap<>();
    private final PriorityQueue<ParentWork> queue = new PriorityQueue<>(
            Comparator.comparing(ParentWork::urgency)
                    .thenComparingDouble(ParentWork::distance)
                    .thenComparing(Comparator.comparingInt(
                            (ParentWork work) -> work.parent().level().value()).reversed())
                    .thenComparingInt(work -> work.parent().origin().x())
                    .thenComparingInt(work -> work.parent().origin().y())
                    .thenComparingInt(work -> work.parent().origin().z()));
    private final PriorityQueue<HorizonWork> horizonQueue = new PriorityQueue<>(
            Comparator.comparing(HorizonWork::initial).reversed()
                    .thenComparingInt(work -> work.origin().x())
                    .thenComparingInt(work -> work.origin().y())
                    .thenComparingInt(work -> work.origin().z()));
    private final Map<SectionPos, HorizonState> horizons = new HashMap<>();
    private Set<SectionPos> initialHorizonTargets;
    private int horizonDispatchesSinceRefinement;
    private long horizonAdmitted;
    private long horizonCompleted;
    private long horizonFailed;
    private long horizonSkipped;
    private long refinementAdmitted;
    private long refinementCompleted;
    private long refinementFailed;
    private long refinementSkipped;
    private long lastSelectionMillis;
    private SectionPos lastSelectionPlayer;
    private boolean stopped;
    private long epoch;

    public DefaultEndRefinement(Config config, ParentWriter writer,
                         ChildTerrain terrain, HorizonCoverage horizon) {
        this(config, writer, terrain, horizon,
                () -> RefinementAdmissionGate.allows(
                        net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT));
    }

    public DefaultEndRefinement(Config config, ParentWriter writer, ChildTerrain terrain,
                         HorizonCoverage horizon, BooleanSupplier refinementAdmission) {
        this.config = Objects.requireNonNull(config, "config");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.horizon = Objects.requireNonNull(horizon, "horizon");
        this.refinementAdmission = Objects.requireNonNull(
                refinementAdmission, "refinementAdmission");
    }

    @Override
    public void observeVanilla(ObservedVanilla observation) {
        Objects.requireNonNull(observation, "observation");
        synchronized (this) {
            int l0X = WorldSectionCoord.sectionToWorldSection(observation.l0Origin().x(), 0);
            int l0Y = WorldSectionCoord.sectionToWorldSection(observation.l0Origin().y(), 0);
            int l0Z = WorldSectionCoord.sectionToWorldSection(observation.l0Origin().z(), 0);
            VanillaOccupancyPyramid.Delta delta = occupancy.observeVanillaL0Octants(
                    l0X, l0Y, l0Z, observation.ownedOctantMask());
            if (!refinementAdmission.getAsBoolean()) return;
            for (VanillaOccupancyPyramid.Cell mixed : delta.newlyMixedParents()) {
                if (mixed.level() > 0) admitOccupancyParent(mixed);
            }
            for (VanillaOccupancyPyramid.Cell boundary : delta.addedUrgent()) {
                if (occupancy.relation(boundary) == VanillaOccupancyPyramid.Relation.FULL) continue;
                if (boundary.level() < Level.L4.value()) admitOccupancyParent(boundary.parent());
            }
        }
    }

    @Override
    public int observeFrontier(
            List<VanillaFrontierGuardPlanner.ParentTransaction> transactions) {
        Objects.requireNonNull(transactions, "transactions");
        synchronized (this) {
            if (!refinementAdmission.getAsBoolean()) return 0;
            int admitted = 0;
            for (VanillaFrontierGuardPlanner.ParentTransaction transaction : transactions) {
                ParentKey key = new ParentKey(Level.L1, transaction.origin());
                int mask = occupancy.missingChildOctants(cell(key));
                state(key).vanillaCovered |= (~mask) & 0xFF;
                if (admit(key, mask, Urgency.FRONTIER, 0.0)) admitted++;
            }
            return admitted;
        }
    }

    @Override
    public StepResult advance(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        Claim claim;
        synchronized (this) {
            if (frame.stopped()) {
                reset();
                stopped = true;
                return new StepResult(StepResult.Status.STOPPED, false);
            }
            if (stopped) stopped = false;
            admitHorizonTargets(frame.horizonTargets());
            if (refinementAdmission.getAsBoolean()) admitSelectionIfDue(frame);
            enqueueEligibleRetries(frame.monotonicMillis());
            claim = claimNext(frame.monotonicMillis());
            if (claim == null) return new StepResult(StepResult.Status.IDLE, false);
        }

        if (claim instanceof HorizonClaim horizonClaim) {
            return executeHorizon(horizonClaim, frame.monotonicMillis());
        }
        if (claim instanceof SkippedClaim) {
            return new StepResult(StepResult.Status.PROGRESSED, false);
        }
        return executeParent((ParentClaim) claim, frame.monotonicMillis());
    }

    private StepResult executeHorizon(HorizonClaim claim, long now) {
        WriteOutcome outcome;
        try {
            outcome = horizon.ensure(claim.origin());
        } catch (Exception failure) {
            synchronized (this) {
                completeHorizonFailure(claim, now);
            }
            return new StepResult(StepResult.Status.FAILED, false);
        }
        synchronized (this) {
            if (claim.epoch() != epoch) return new StepResult(StepResult.Status.STOPPED, false);
            HorizonState state = horizons.get(claim.origin());
            if (state == null || state.status != HorizonStatus.EXECUTING) {
                return new StepResult(StepResult.Status.STOPPED, false);
            }
            boolean renderable = outcome != null
                    && (outcome.status() == WriteOutcome.Status.WRITTEN
                    || outcome.status() == WriteOutcome.Status.SKIPPED_EXISTS);
            if (renderable) {
                if (outcome != null && outcome.status() == WriteOutcome.Status.WRITTEN) {
                    state.status = HorizonStatus.WRITTEN;
                    horizonCompleted++;
                } else {
                    state.status = HorizonStatus.EXISTING;
                    horizonSkipped++;
                }
                resolveHorizonDependents(claim.origin(), PrerequisiteOutcome.RENDERABLE, now);
            } else {
                state.status = HorizonStatus.TERMINAL_EMPTY;
                horizonSkipped++;
                resolveHorizonDependents(claim.origin(), PrerequisiteOutcome.TERMINAL_EMPTY, now);
            }
            return new StepResult(StepResult.Status.PROGRESSED,
                    outcome != null && outcome.status() == WriteOutcome.Status.WRITTEN);
        }
    }

    private StepResult executeParent(ParentClaim claim, long now) {
        ParentRefinementResult result;
        try {
            result = writer.refine(new ParentRefinementIntent(
                    claim.parent().origin(), claim.parent().level(), claim.materializeMask(),
                    (childLevel, childOrigin) -> terrain.produce(childLevel, childOrigin)));
        } catch (Exception failure) {
            synchronized (this) {
                completeParentFailure(claim, now);
            }
            return new StepResult(StepResult.Status.FAILED, false);
        }
        synchronized (this) {
            if (claim.epoch() != epoch) return new StepResult(StepResult.Status.STOPPED, false);
            ParentState state = parents.get(claim.parent());
            if (state == null || (state.executing & claim.requestedMask()) == 0) {
                return new StepResult(StepResult.Status.STOPPED, false);
            }
            state.executing &= ~claim.requestedMask();
            if (result.status() == ParentRefinementResult.Status.PARENT_MISSING) {
                state.pending |= claim.requestedMask();
                blockOnPrerequisite(claim.parent(), state);
                lifecycle.recordAttempt(claim.parent().level().value(),
                        RefinementOutcome.blockedParent(claim.parent().origin()));
                return new StepResult(StepResult.Status.DEFERRED, false);
            }

            // The publication transaction is whole-parent: it materialized
            // every sibling, so every terminal outcome it reports is credited
            // regardless of which subset was originally demanded. This keeps
            // scheduler accounting in sync with the work actually performed.
            int represented = result.representedMask();
            int empty = result.emptyMask();
            int terminal = represented | empty | claim.vanillaMask();
            state.represented |= represented;
            state.deterministicEmpty |= empty;
            state.vanillaCovered |= claim.vanillaMask();
            clearRetryState(state, terminal);
            int unreported = 0xFF & ~(state.represented
                    | state.deterministicEmpty | state.vanillaCovered)
                    & (claim.requestedMask() | claim.materializeMask());
            if (unreported != 0) scheduleRetry(state, unreported, now);
            lifecycle.recordAttempt(claim.parent().level().value(),
                    RefinementOutcome.published(result.writeOutcome()));
            refinementCompleted++;
            resolveParentDependents(claim.parent(), represented, empty, now);
            enqueueIfEligible(claim.parent(), state, now);
            return new StepResult(StepResult.Status.PROGRESSED, represented != 0);
        }
    }

    private synchronized Claim claimNext(long now) {
        boolean initialComplete = initialHorizonComplete();
        if (!initialComplete) {
            ParentWork frontier = peekEligibleParent(now, true);
            HorizonClaim initial = claimHorizon(now, true);
            if (initial != null && (frontier == null || horizonDispatchesSinceRefinement < HORIZON_BURST)) {
                horizonDispatchesSinceRefinement++;
                return initial;
            }
            if (frontier != null) {
                horizonDispatchesSinceRefinement = 0;
                return claimParent(frontier, now);
            }
            return initial;
        }

        HorizonClaim horizonClaim = claimHorizon(now, false);
        ParentWork any = peekEligibleParent(now, false);
        if (horizonClaim != null && (any == null || horizonDispatchesSinceRefinement < HORIZON_BURST)) {
            horizonDispatchesSinceRefinement++;
            return horizonClaim;
        }
        if (any != null) {
            horizonDispatchesSinceRefinement = 0;
            return claimParent(any, now);
        }
        return horizonClaim;
    }

    private HorizonClaim claimHorizon(long now, boolean initialOnly) {
        List<HorizonWork> deferred = new ArrayList<>();
        HorizonClaim claim = null;
        while (!horizonQueue.isEmpty()) {
            HorizonWork work = horizonQueue.poll();
            HorizonState state = horizons.get(work.origin());
            if (state == null || state.terminal() || state.status == HorizonStatus.EXECUTING) continue;
            if (initialOnly && !state.initial) {
                deferred.add(work);
                continue;
            }
            if (state.nextEligibleMillis > now) {
                deferred.add(work);
                continue;
            }
            state.status = HorizonStatus.EXECUTING;
            state.attempts++;
            claim = new HorizonClaim(epoch, work.origin(), state.initial);
            break;
        }
        horizonQueue.addAll(deferred);
        return claim;
    }

    private ParentWork peekEligibleParent(long now, boolean frontierOnly) {
        List<ParentWork> deferred = new ArrayList<>();
        ParentWork selected = null;
        while (!queue.isEmpty()) {
            ParentWork work = queue.poll();
            ParentState state = parents.get(work.parent());
            if (state == null || !state.queued) continue;
            if (frontierOnly && state.urgency != Urgency.FRONTIER) {
                deferred.add(work);
                continue;
            }
            int eligible = eligibleMask(state, now);
            if (eligible == 0 || state.prerequisite != null || state.executing != 0) {
                state.queued = false;
                continue;
            }
            selected = work;
            break;
        }
        queue.addAll(deferred);
        return selected;
    }

    private Claim claimParent(ParentWork work, long now) {
        ParentState state = parents.get(work.parent());
        state.queued = false;
        int requested = eligibleMask(state, now);
        if (requested == 0) return null;
        state.pending &= ~requested;
        VanillaOccupancyPyramid.Cell cell = cell(work.parent());
        int missing = occupancy.relation(cell) == VanillaOccupancyPyramid.Relation.FULL
                ? 0 : occupancy.missingChildOctants(cell);
        int materialize = requested & missing;
        int vanilla = requested & ~missing;
        state.executing |= requested;
        if (materialize == 0) {
            state.executing &= ~requested;
            state.vanillaCovered |= vanilla;
            clearRetryState(state, requested);
            refinementSkipped++;
            lifecycle.recordAttempt(work.parent().level().value(),
                    RefinementOutcome.alreadyCovered());
            resolveParentDependents(work.parent(), vanilla, 0, now);
            return new SkippedClaim(epoch);
        }
        return new ParentClaim(epoch, work.parent(), requested, materialize, vanilla, state.urgency);
    }

    private void completeHorizonFailure(HorizonClaim claim, long now) {
        if (claim.epoch() != epoch) return;
        HorizonState state = horizons.get(claim.origin());
        if (state == null || state.status != HorizonStatus.EXECUTING) return;
        horizonFailed++;
        if (state.attempts < config.maxAttempts()) {
            state.status = HorizonStatus.RETRYABLE_FAILURE;
            state.nextEligibleMillis = now + retryDelay(state.attempts);
            horizonQueue.offer(new HorizonWork(claim.origin(), state.initial));
            resolveHorizonDependents(claim.origin(), PrerequisiteOutcome.RETRYABLE_FAILURE, now);
        } else {
            state.status = HorizonStatus.EXHAUSTED_FAILURE;
            resolveHorizonDependents(
                    claim.origin(), PrerequisiteOutcome.TERMINAL_FAILURE, now);
        }
    }

    private void completeParentFailure(ParentClaim claim, long now) {
        if (claim.epoch() != epoch) return;
        ParentState state = parents.get(claim.parent());
        if (state == null) return;
        state.executing &= ~claim.requestedMask();
        int exhausted = scheduleRetry(state, claim.requestedMask(), now);
        if (exhausted != 0) resolveFailedParentDependents(claim.parent(), exhausted);
        lifecycle.recordAttempt(claim.parent().level().value(), RefinementOutcome.failed());
        refinementFailed++;
    }

    private int scheduleRetry(ParentState state, int mask, long now) {
        int exhausted = 0;
        for (int bit = 0; bit < 8; bit++) {
            int flag = 1 << bit;
            if ((mask & flag) == 0) continue;
            state.attempts[bit]++;
            state.retryable |= flag;
            if (state.attempts[bit] < config.maxAttempts()) {
                state.pending |= flag;
                state.nextEligibleMillis[bit] = now + retryDelay(state.attempts[bit]);
            } else {
                exhausted |= flag;
            }
        }
        return exhausted;
    }

    private long retryDelay(int attempts) {
        int shift = Math.min(20, Math.max(0, attempts - 1));
        return config.retryBackoffMillis() << shift;
    }

    private void clearRetryState(ParentState state, int mask) {
        state.retryable &= ~mask;
        for (int bit = 0; bit < 8; bit++) {
            int flag = 1 << bit;
            if ((mask & flag) == 0) continue;
            state.attempts[bit] = 0;
            state.nextEligibleMillis[bit] = 0;
            state.pending &= ~flag;
        }
    }

    private int eligibleMask(ParentState state, long now) {
        int eligible = 0;
        for (int bit = 0; bit < 8; bit++) {
            int flag = 1 << bit;
            if ((state.pending & flag) != 0 && state.nextEligibleMillis[bit] <= now) {
                eligible |= flag;
            }
        }
        return eligible;
    }

    private void enqueueEligibleRetries(long now) {
        for (Map.Entry<ParentKey, ParentState> entry : parents.entrySet()) {
            enqueueIfEligible(entry.getKey(), entry.getValue(), now);
        }
        for (Map.Entry<SectionPos, HorizonState> entry : horizons.entrySet()) {
            HorizonState state = entry.getValue();
            if (state.status == HorizonStatus.RETRYABLE_FAILURE
                    && state.nextEligibleMillis <= now
                    && horizonQueue.stream().noneMatch(work -> work.origin().equals(entry.getKey()))) {
                horizonQueue.offer(new HorizonWork(entry.getKey(), state.initial));
            }
        }
    }

    private void admitHorizonTargets(List<SectionPos> targets) {
        if (targets.isEmpty()) return;
        if (initialHorizonTargets == null) {
            LinkedHashSet<SectionPos> fixed = new LinkedHashSet<>(targets);
            if (config.initialHorizonSize() > 0
                    && fixed.size() != config.initialHorizonSize()) {
                throw new IllegalArgumentException("initial horizon requires exactly "
                        + config.initialHorizonSize() + " unique targets, got " + fixed.size());
            }
            initialHorizonTargets = Set.copyOf(fixed);
        }
        for (SectionPos origin : targets) {
            admitHorizon(origin, initialHorizonTargets.contains(origin));
        }
    }

    private void admitHorizon(SectionPos origin, boolean initial) {
        HorizonState existing = horizons.get(origin);
        if (existing != null) return;
        horizons.put(origin, new HorizonState(initial));
        horizonQueue.offer(new HorizonWork(origin, initial));
        horizonAdmitted++;
    }

    private boolean initialHorizonComplete() {
        if (initialHorizonTargets == null) return config.initialHorizonSize() == 0;
        for (SectionPos target : initialHorizonTargets) {
            HorizonState state = horizons.get(target);
            if (state == null || !state.terminal()) return false;
        }
        return true;
    }

    private void admitSelectionIfDue(Frame frame) {
        boolean moved = lastSelectionPlayer == null
                || frame.playerSection().x() != lastSelectionPlayer.x()
                || frame.playerSection().z() != lastSelectionPlayer.z();
        boolean elapsed = lastSelectionMillis == 0
                || frame.monotonicMillis() - lastSelectionMillis >= config.selectionIntervalMillis();
        if (!moved && !elapsed) return;
        lastSelectionMillis = frame.monotonicMillis();
        lastSelectionPlayer = frame.playerSection();
        int visualOutstanding = 0;
        for (ParentState state : parents.values()) {
            if (state.urgency == Urgency.VISUAL) {
                visualOutstanding += Integer.bitCount(state.pending | state.executing);
            }
        }
        int capacity = Math.min(config.selectionBudget(),
                Math.max(0, config.visualWorkingSet() - visualOutstanding));
        if (capacity == 0) return;
        double camX = WorldSectionCoord.sectionToBlockMin(frame.playerSection().x()) + 8.0;
        double camY = WorldSectionCoord.sectionToBlockMin(frame.playerSection().y()) + 8.0;
        double camZ = WorldSectionCoord.sectionToBlockMin(frame.playerSection().z()) + 8.0;
        List<RefinementDemandSelector.Emission> selected = RefinementDemandSelector.select(
                new RefinementDemandSelector.Params(camX, camY, camZ,
                        config.focalPx(), config.subdivisionPx(), Level.L0.value(),
                        config.renderDistanceBlocks(), capacity, frame.horizonTargets()));
        List<RefinementDemandSelector.Emission> frontier = new ArrayList<>();
        List<RefinementDemandSelector.Emission> ordinary = new ArrayList<>();
        for (RefinementDemandSelector.Emission emission : selected) {
            ParentKey key = fromSelector(emission.request());
            switch (occupancy.relation(cell(key))) {
                case FULL -> {
                    ParentState state = state(key);
                    state.vanillaCovered = 0xFF;
                    clearRetryState(state, 0xFF);
                }
                case FRONTIER -> frontier.add(emission);
                case ORDINARY -> ordinary.add(emission);
            }
        }
        int admitted = 0;
        frontier.addAll(ordinary);
        for (RefinementDemandSelector.Emission emission : frontier) {
            if (admitted >= capacity) break;
            ParentKey key = fromSelector(emission.request());
            Urgency urgency = occupancy.relation(cell(key))
                    == VanillaOccupancyPyramid.Relation.FRONTIER ? Urgency.FRONTIER : Urgency.VISUAL;
            if (admit(key, emission.demandedChildMask(), urgency, emission.distBlocks())) admitted++;
        }
    }

    private boolean admit(ParentKey key, int mask, Urgency urgency, double distance) {
        if (mask == 0) return false;
        ParentState state = state(key);
        int terminal = state.represented | state.deterministicEmpty | state.vanillaCovered;
        int newMask = mask & ~terminal & ~state.pending & ~state.executing;
        if (newMask == 0) {
            if (urgency.ordinal() < state.urgency.ordinal()) state.urgency = urgency;
            return false;
        }
        state.pending |= newMask;
        refinementAdmitted++;
        if (urgency.ordinal() < state.urgency.ordinal()) state.urgency = urgency;
        state.distance = Math.min(state.distance == 0 ? distance : state.distance, distance);
        enqueueIfEligible(key, state, Long.MAX_VALUE);
        return true;
    }

    private void enqueueIfEligible(ParentKey key, ParentState state, long now) {
        if (!state.queued && state.executing == 0 && state.prerequisite == null
                && eligibleMask(state, now) != 0) {
            state.queued = true;
            queue.offer(new ParentWork(key, state.urgency, state.distance));
        }
    }

    private void admitOccupancyParent(VanillaOccupancyPyramid.Cell parent) {
        if (parent.level() < 1 || parent.level() > 4) return;
        ParentKey key = fromCell(parent);
        int missing = occupancy.missingChildOctants(parent);
        state(key).vanillaCovered |= (~missing) & 0xFF;
        admit(key, missing, Urgency.FRONTIER, 0.0);
    }

    private void blockOnPrerequisite(ParentKey blocked, ParentState blockedState) {
        if (blocked.level() == Level.L4) {
            blockedState.prerequisite = new HorizonPrerequisite(blocked.origin());
            horizonDependents.computeIfAbsent(blocked.origin(), ignored -> new HashSet<>())
                    .add(blocked);
            admitHorizon(blocked.origin(), initialHorizonTargets != null
                    && initialHorizonTargets.contains(blocked.origin()));
            return;
        }
        Level coarser = Level.values()[blocked.level().value() + 1];
        SectionPos origin = alignToParent(blocked.origin(), coarser);
        ParentKey prerequisite = new ParentKey(coarser, origin);
        int bit = childOctantMask(blocked.origin(), blocked.level(), prerequisite.origin());
        blockedState.prerequisite = new ParentPrerequisite(prerequisite, bit);
        blockedDependents.computeIfAbsent(prerequisite, ignored -> new HashSet<>())
                .add(new Dependency(blocked, bit));
        admit(prerequisite, bit, blockedState.urgency, 0.0);
    }

    private void resolveHorizonDependents(
            SectionPos completed, PrerequisiteOutcome outcome, long now) {
        if (outcome == PrerequisiteOutcome.RETRYABLE_FAILURE
                || outcome == PrerequisiteOutcome.UNRESOLVED) return;
        Set<ParentKey> dependents = horizonDependents.remove(completed);
        if (dependents == null) return;
        for (ParentKey dependent : dependents) {
            ParentState state = parents.get(dependent);
            if (state == null) continue;
            state.prerequisite = null;
            if (outcome == PrerequisiteOutcome.RENDERABLE) {
                enqueueIfEligible(dependent, state, now);
            } else if (outcome == PrerequisiteOutcome.TERMINAL_EMPTY) {
                terminallyEmpty(state);
            } else {
                terminallyFailed(state);
            }
        }
    }

    private void resolveParentDependents(
            ParentKey completed, int renderableMask, int emptyMask, long now) {
        Set<Dependency> dependents = blockedDependents.get(completed);
        if (dependents == null) return;
        Set<Dependency> unresolved = new HashSet<>();
        for (Dependency dependency : dependents) {
            ParentState state = parents.get(dependency.dependent());
            if (state == null) continue;
            if ((renderableMask & dependency.prerequisiteBit()) != 0) {
                state.prerequisite = null;
                enqueueIfEligible(dependency.dependent(), state, now);
            } else if ((emptyMask & dependency.prerequisiteBit()) != 0) {
                state.prerequisite = null;
                terminallyEmpty(state);
            } else {
                unresolved.add(dependency);
            }
        }
        if (unresolved.isEmpty()) blockedDependents.remove(completed);
        else blockedDependents.put(completed, unresolved);
    }

    private void terminallyEmpty(ParentState state) {
        int mask = state.pending;
        state.pending = 0;
        state.deterministicEmpty |= mask;
        clearRetryState(state, mask);
    }

    private void terminallyFailed(ParentState state) {
        state.retryable |= state.pending;
        state.pending = 0;
    }

    private void resolveFailedParentDependents(ParentKey completed, int failedMask) {
        Set<Dependency> dependents = blockedDependents.get(completed);
        if (dependents == null) return;
        Set<Dependency> unresolved = new HashSet<>();
        for (Dependency dependency : dependents) {
            ParentState state = parents.get(dependency.dependent());
            if ((failedMask & dependency.prerequisiteBit()) != 0) {
                if (state != null) {
                    state.prerequisite = null;
                    terminallyFailed(state);
                }
            } else {
                unresolved.add(dependency);
            }
        }
        if (unresolved.isEmpty()) blockedDependents.remove(completed);
        else blockedDependents.put(completed, unresolved);
    }

    @Override
    public synchronized Snapshot snapshot() {
        long pending = 0;
        long executing = 0;
        long represented = 0;
        long empty = 0;
        long vanilla = 0;
        long retryable = 0;
        int queuedParents = 0;
        int executingParents = 0;
        for (ParentState state : parents.values()) {
            pending += Integer.bitCount(state.pending);
            executing += Integer.bitCount(state.executing);
            represented += Integer.bitCount(state.represented);
            empty += Integer.bitCount(state.deterministicEmpty);
            vanilla += Integer.bitCount(state.vanillaCovered);
            retryable += Integer.bitCount(state.retryable);
            if (state.queued) queuedParents++;
            if (state.executing != 0) executingParents++;
        }
        int initialTerminal = 0;
        int initialWritten = 0;
        int initialExisting = 0;
        int initialEmpty = 0;
        int initialFailed = 0;
        if (initialHorizonTargets != null) {
            for (SectionPos target : initialHorizonTargets) {
                HorizonState state = horizons.get(target);
                if (state == null) continue;
                if (state.terminal()) initialTerminal++;
                if (state.status == HorizonStatus.WRITTEN) initialWritten++;
                if (state.status == HorizonStatus.EXISTING) initialExisting++;
                if (state.status == HorizonStatus.TERMINAL_EMPTY) initialEmpty++;
                if (state.status == HorizonStatus.EXHAUSTED_FAILURE) initialFailed++;
            }
        }
        long horizonExecuting = horizons.values().stream()
                .filter(state -> state.status == HorizonStatus.EXECUTING).count();
        return new Snapshot(
                new DemandSummary(horizonAdmitted, horizonCompleted, horizonFailed,
                        horizonSkipped, horizonQueue.size(), (int) horizonExecuting),
                new DemandSummary(refinementAdmitted, refinementCompleted, refinementFailed,
                        refinementSkipped, queuedParents, executingParents),
                pending, executing, represented, empty, vanilla, retryable,
                new InitialHorizonSummary(
                        initialHorizonTargets == null ? 0 : initialHorizonTargets.size(),
                        initialTerminal, initialWritten, initialExisting,
                        initialEmpty, initialFailed),
                lifecycle.compact());
    }

    private void reset() {
        epoch++;
        parents.clear();
        blockedDependents.clear();
        horizonDependents.clear();
        queue.clear();
        horizonQueue.clear();
        horizons.clear();
        initialHorizonTargets = null;
        occupancy.clear();
        lifecycle.reset();
        lastSelectionMillis = 0;
        lastSelectionPlayer = null;
        horizonDispatchesSinceRefinement = 0;
        horizonAdmitted = horizonCompleted = horizonFailed = horizonSkipped = 0;
        refinementAdmitted = refinementCompleted = refinementFailed = refinementSkipped = 0;
    }

    private ParentState state(ParentKey key) {
        return parents.computeIfAbsent(key, ignored -> new ParentState());
    }

    private static ParentKey fromSelector(RefinementDemandSelector.NodeRequest request) {
        Level level = Level.values()[request.level()];
        return new ParentKey(level, new SectionPos(
                WorldSectionCoord.worldSectionToBlockMin(request.wsX(), request.level()) >> 4,
                WorldSectionCoord.worldSectionToBlockMin(request.wsY(), request.level()) >> 4,
                WorldSectionCoord.worldSectionToBlockMin(request.wsZ(), request.level()) >> 4));
    }

    private static ParentKey fromCell(VanillaOccupancyPyramid.Cell cell) {
        Level level = Level.values()[cell.level()];
        return new ParentKey(level, new SectionPos(
                WorldSectionCoord.worldSectionToBlockMin(cell.x(), cell.level()) >> 4,
                WorldSectionCoord.worldSectionToBlockMin(cell.y(), cell.level()) >> 4,
                WorldSectionCoord.worldSectionToBlockMin(cell.z(), cell.level()) >> 4));
    }

    private static VanillaOccupancyPyramid.Cell cell(ParentKey key) {
        int level = key.level().value();
        return new VanillaOccupancyPyramid.Cell(level,
                WorldSectionCoord.sectionToWorldSection(key.origin().x(), level),
                WorldSectionCoord.sectionToWorldSection(key.origin().y(), level),
                WorldSectionCoord.sectionToWorldSection(key.origin().z(), level));
    }

    private static SectionPos alignToParent(SectionPos childOrigin, Level parentLevel) {
        int size = parentLevel.regionSections();
        return new SectionPos(Math.floorDiv(childOrigin.x(), size) * size,
                Math.floorDiv(childOrigin.y(), size) * size,
                Math.floorDiv(childOrigin.z(), size) * size);
    }

    private static int childOctantMask(
            SectionPos childOrigin, Level childLevel, SectionPos parentOrigin) {
        int span = childLevel.regionSections();
        int x = Math.floorDiv(childOrigin.x() - parentOrigin.x(), span);
        int y = Math.floorDiv(childOrigin.y() - parentOrigin.y(), span);
        int z = Math.floorDiv(childOrigin.z() - parentOrigin.z(), span);
        return 1 << (x | (z << 1) | (y << 2));
    }
}
