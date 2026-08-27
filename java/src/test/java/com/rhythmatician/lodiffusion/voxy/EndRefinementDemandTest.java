package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EndRefinementDemandTest {
    private static final DefaultEndRefinement.Config CONFIG =
            new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 1000);

    @Test
    void initialCoverageRunsBeforeRefinementAndKeepsTheHorizonHoleFree() {
        List<String> work = new ArrayList<>();
        DefaultEndRefinement module = module(intent -> {
            work.add("refinement");
            return published(intent.demandedChildMask());
        }, origin -> {
            work.add("horizon");
            return WriteOutcome.written(1);
        });
        observeOneVanillaOctant(module);

        assertEquals(EndRefinement.StepResult.Status.PROGRESSED,
                module.advance(frame(1, new SectionPos(0, 0, 0))).status());
        assertEquals(List.of("horizon"), work);
        assertEquals(1, module.snapshot().horizon().completed());
        assertEquals(0, module.snapshot().refinement().completed());
    }

    @Test
    void frontierWorkRunsBeforeOrdinaryScreenSpaceDemand() {
        List<Level> parents = new ArrayList<>();
        DefaultEndRefinement module = module(intent -> {
            parents.add(intent.parentLevel());
            return published(intent.demandedChildMask());
        }, origin -> WriteOutcome.written(1));
        observeOneVanillaOctant(module);

        module.advance(frame(1, new SectionPos(0, 0, 0)));
        module.advance(frame(2, new SectionPos(0, 0, 0)));

        assertFalse(parents.isEmpty());
        assertEquals(Level.L4, parents.getFirst(),
                "frontier demand must win before ordinary screen-space descendants");
    }

    @Test
    void fullyVanillaParentSkipsMaterialization() {
        List<ParentRefinementIntent> transactions = new ArrayList<>();
        DefaultEndRefinement module = module(intent -> {
            transactions.add(intent);
            return published(intent.demandedChildMask());
        }, origin -> WriteOutcome.written(1));
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    module.observeVanilla(new EndRefinement.ObservedVanilla(
                            new SectionPos(x * 2, y * 2, z * 2), 0xFF));
                }
            }
        }

        for (int tick = 1; tick <= 8; tick++) module.advance(frame(tick, new SectionPos(0, 0, 0)));

        assertFalse(transactions.stream().anyMatch(intent -> intent.parentLevel() == Level.L1
                && intent.parentOrigin().equals(new SectionPos(0, 0, 0))),
                "a fully vanilla L1 parent must not materialize a child transaction");
    }

    @Test
    void blockedWorkCreatesCoverageThenRetriesTheSameParent() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger horizons = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            if (attempts.getAndIncrement() == 0) return ParentRefinementResult.parentMissing();
            return published(intent.demandedChildMask());
        }, origin -> {
            horizons.incrementAndGet();
            return WriteOutcome.written(1);
        });
        observeOneVanillaOctant(module);

        assertEquals(EndRefinement.StepResult.Status.DEFERRED, module.advance(frame(1)).status());
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(2)).status());
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(3)).status());
        assertEquals(1, horizons.get());
        assertEquals(2, attempts.get());
    }

    @Test
    void failedWorkRemainsEligibleForRetry() {
        AtomicInteger attempts = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("transient");
            return published(intent.demandedChildMask());
        }, origin -> WriteOutcome.written(1));
        observeOneVanillaOctant(module);

        assertEquals(EndRefinement.StepResult.Status.FAILED, module.advance(frame(1)).status());
        assertTrue(module.snapshot().retryableChildren() > 0);
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(2)).status());
        assertEquals(2, attempts.get());
    }

    @Test
    void busyHorizonWorkerCannotStarveQueuedRefinement() {
        AtomicInteger refinements = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            refinements.incrementAndGet();
            return published(intent.demandedChildMask());
        }, origin -> WriteOutcome.written(1));
        observeOneVanillaOctant(module);
        List<SectionPos> horizon = List.of(new SectionPos(0, 0, 0), new SectionPos(32, 0, 0),
                new SectionPos(64, 0, 0), new SectionPos(96, 0, 0), new SectionPos(128, 0, 0));

        for (int tick = 1; tick <= 5; tick++) module.advance(frame(tick, horizon));

        assertEquals(1, refinements.get(),
                "four horizon leaves are bounded before one refinement transaction runs");
        assertEquals(4, module.snapshot().horizon().completed());
    }

    @Test
    void selectionUsesCadenceAndMovementRatherThanWorkerLoopFrequency() {
        DefaultEndRefinement module = module(intent -> published(intent.demandedChildMask()),
                origin -> WriteOutcome.written(1));
        List<SectionPos> horizon = List.of(new SectionPos(0, 0, 0));

        module.advance(new EndRefinement.Frame(1, new SectionPos(0, 4, 0), horizon, false));
        long admitted = module.snapshot().refinement().admitted();
        module.advance(new EndRefinement.Frame(2, new SectionPos(0, 4, 0), horizon, false));
        assertEquals(admitted, module.snapshot().refinement().admitted());

        module.advance(new EndRefinement.Frame(3, new SectionPos(1, 4, 0), horizon, false));
        assertTrue(module.snapshot().refinement().admitted() >= admitted);
    }

    private static DefaultEndRefinement module(
            DefaultEndRefinement.ParentWriter writer, DefaultEndRefinement.HorizonCoverage horizon) {
        return new DefaultEndRefinement(CONFIG, writer, (level, origin) -> solid(), horizon);
    }

    private static EndRefinement.Frame frame(long time) {
        return new EndRefinement.Frame(time, new SectionPos(0, 4, 0), List.of(), false);
    }

    private static EndRefinement.Frame frame(long time, SectionPos horizon) {
        return new EndRefinement.Frame(time, new SectionPos(0, 4, 0), List.of(horizon), false);
    }

    private static EndRefinement.Frame frame(long time, List<SectionPos> horizon) {
        return new EndRefinement.Frame(time, new SectionPos(0, 4, 0), horizon, false);
    }

    private static void observeOneVanillaOctant(EndRefinement module) {
        module.observeVanilla(new EndRefinement.ObservedVanilla(new SectionPos(0, 0, 0), 1));
    }

    private static ParentRefinementResult published(int mask) {
        return ParentRefinementResult.published(WriteOutcome.written(Integer.bitCount(mask)), mask, 0);
    }

    private static VoxelVolume solid() {
        return VoxelVolume.uniform(32, EndL4DeterministicCandidate.BLOCK_END_STONE, 0);
    }
}
