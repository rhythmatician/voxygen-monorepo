package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.refinement.DefaultEndRefinement;
import com.rhythmatician.voxygen.generation.dimension.end.EndL4DeterministicCandidate;
import com.rhythmatician.voxygen.generation.refinement.EndRefinement;
import com.rhythmatician.voxygen.generation.scheduling.VanillaFrontierGuardPlanner;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.output.WriteOutcome;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult;

class EndRefinementModuleTest {
    private static final DefaultEndRefinement.Config CONFIG =
            new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 0, 3, 10, 0);
    private static final EndRefinement.Frame FRAME =
            new EndRefinement.Frame(1, new SectionPos(0, 4, 0), List.of(), false);

    @Test
    void allInitial121HorizonTargetsFinishBeforeOrdinaryVisualRefinement() {
        List<String> operations = new ArrayList<>();
        DefaultEndRefinement module = new DefaultEndRefinement(
                new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 0, 3, 10, 121),
                intent -> {
                    operations.add("refine");
                    return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
                },
                (level, origin) -> solid(),
                origin -> {
                    operations.add("horizon");
                    return WriteOutcome.written(1);
                });
        List<SectionPos> targets = horizonTargets();

        for (int tick = 1; tick <= 122; tick++) {
            module.advance(new EndRefinement.Frame(
                    tick, new SectionPos(0, 4, 0), targets, false));
        }

        assertEquals(121, operations.subList(0, 121).stream()
                .filter("horizon"::equals).count());
        assertEquals(121, module.snapshot().initialHorizon().terminal());
        assertEquals(121, module.snapshot().initialHorizon().written());
        assertTrue(operations.size() > 121);
        assertEquals("refine", operations.get(121));
    }

    @Test
    void proactiveFrontierTransactionReachesOneSparseParentIntentWithoutShadowQueue() {
        List<ParentRefinementIntent> intents = new ArrayList<>();
        DefaultEndRefinement module = module(intent -> {
            intents.add(intent);
            return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
        });
        VanillaFrontierGuardPlanner.ParentTransaction transaction =
                new VanillaFrontierGuardPlanner.ParentTransaction(new SectionPos(0, 0, 0));

        assertEquals(1, module.observeFrontier(List.of(transaction)));
        assertEquals(0, module.observeFrontier(List.of(transaction)));
        assertEquals(1, module.snapshot().refinement().queued());
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(FRAME).status());

        assertEquals(1, intents.size());
        assertEquals(Level.L1, intents.getFirst().parentLevel());
        assertEquals(new SectionPos(0, 0, 0), intents.getFirst().parentOrigin());
        assertEquals(0xFF, intents.getFirst().demandedChildMask());
    }

    @Test
    void retryUsesBackoffIsBoundedAndClearsAfterSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("transient");
            return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
        });
        module.observeFrontier(List.of(parent(0, 0, 0)));

        assertEquals(EndRefinement.StepResult.Status.FAILED, module.advance(frame(0)).status());
        assertTrue(module.snapshot().retryableChildren() > 0);
        assertEquals(EndRefinement.StepResult.Status.IDLE, module.advance(frame(9)).status());
        assertEquals(1, attempts.get());
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(10)).status());
        assertEquals(2, attempts.get());
        assertEquals(0, module.snapshot().retryableChildren());
    }

    @Test
    void repeatedFailureStopsAfterConfiguredAttemptBound() {
        AtomicInteger attempts = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("persistent");
        });
        module.observeFrontier(List.of(parent(0, 0, 0)));

        assertEquals(EndRefinement.StepResult.Status.FAILED, module.advance(frame(0)).status());
        assertEquals(EndRefinement.StepResult.Status.FAILED, module.advance(frame(10)).status());
        assertEquals(EndRefinement.StepResult.Status.FAILED, module.advance(frame(30)).status());
        assertEquals(EndRefinement.StepResult.Status.IDLE, module.advance(frame(1000)).status());
        assertEquals(3, attempts.get());
        assertTrue(module.snapshot().retryableChildren() > 0);
        assertEquals(0, module.snapshot().pendingChildren());
    }

    @Test
    void terminalEmptyPrerequisiteResolvesBlockedDescendantWithoutDeadlock() {
        AtomicInteger l1Attempts = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            if (intent.parentLevel() == Level.L1) {
                l1Attempts.incrementAndGet();
                return ParentRefinementResult.parentMissing();
            }
            return published(intent.demandedChildMask(), 0, intent.demandedChildMask());
        });
        module.observeFrontier(List.of(parent(0, 0, 0)));

        assertEquals(EndRefinement.StepResult.Status.DEFERRED, module.advance(frame(0)).status());
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(1)).status());
        assertEquals(EndRefinement.StepResult.Status.IDLE, module.advance(frame(2)).status());
        assertEquals(1, l1Attempts.get());
        assertEquals(0, module.snapshot().pendingChildren());
        assertTrue(module.snapshot().deterministicEmptyChildren() > 0);
    }

    @Test
    void renderablePrerequisiteWakesBlockedDescendant() {
        AtomicInteger l1Attempts = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            if (intent.parentLevel() == Level.L1 && l1Attempts.getAndIncrement() == 0) {
                return ParentRefinementResult.parentMissing();
            }
            return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
        });
        module.observeFrontier(List.of(parent(0, 0, 0)));

        assertEquals(EndRefinement.StepResult.Status.DEFERRED, module.advance(frame(0)).status());
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(1)).status());
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(2)).status());
        assertEquals(2, l1Attempts.get());
    }

    @Test
    void admissionGateBlocksRefinementButNeverHorizon() {
        AtomicInteger refinements = new AtomicInteger();
        AtomicInteger horizons = new AtomicInteger();
        DefaultEndRefinement module = new DefaultEndRefinement(
                new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 0, 3, 10, 1),
                intent -> {
                    refinements.incrementAndGet();
                    return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
                },
                (level, origin) -> solid(),
                origin -> {
                    horizons.incrementAndGet();
                    return WriteOutcome.written(1);
                },
                () -> false);

        assertEquals(0, module.observeFrontier(List.of(parent(0, 0, 0))));
        module.advance(new EndRefinement.Frame(
                1, FRAME.playerSection(), List.of(new SectionPos(0, 0, 0)), false));

        assertEquals(1, horizons.get());
        assertEquals(0, refinements.get());
    }

    @Test
    void staleFullVanillaSuppressesWriterAndPartialVanillaMaterializesOnlyMissingBits() {
        List<String> writtenParents = new ArrayList<>();
        List<ParentRefinementIntent> partialIntents = new ArrayList<>();
        DefaultEndRefinement full = module(intent -> {
            writtenParents.add(intent.parentLevel() + ":" + intent.parentOrigin());
            return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
        });
        full.observeFrontier(List.of(parent(0, 0, 0)));
        fillL1WithVanilla(full);
        for (int tick = 1; tick <= 64; tick++) full.advance(frame(tick));
        assertFalse(writtenParents.contains(Level.L1 + ":" + new SectionPos(0, 0, 0)));
        assertTrue(full.snapshot().vanillaCoveredChildren() >= 8);

        DefaultEndRefinement partial = module(intent -> {
            partialIntents.add(intent);
            return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
        });
        partial.observeVanilla(new EndRefinement.ObservedVanilla(new SectionPos(0, 0, 0), 0xFF));
        partial.observeFrontier(List.of(parent(0, 0, 0)));
        for (int tick = 1; tick <= 128; tick++) partial.advance(frame(tick));
        ParentRefinementIntent partialTarget = partialIntents.stream()
                .filter(intent -> intent.parentLevel() == Level.L1
                        && intent.parentOrigin().equals(new SectionPos(0, 0, 0)))
                .findFirst().orElseThrow();
        assertTrue(Integer.bitCount(partialTarget.demandedChildMask()) < 8);
        assertTrue(partial.snapshot().vanillaCoveredChildren() > 0);
    }

    @Test
    void expensiveWriterRunsOutsideLockSoVanillaObservationAndStopStayBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
            return published(intent.demandedChildMask(), intent.demandedChildMask(), 0);
        });
        module.observeFrontier(List.of(parent(0, 0, 0)));

        Thread worker = new Thread(() -> module.advance(frame(0)), "end-refinement-test");
        worker.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                module.observeVanilla(new EndRefinement.ObservedVanilla(
                        new SectionPos(8, 0, 0), 1)));
        assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                assertEquals(EndRefinement.StepResult.Status.STOPPED,
                        module.advance(new EndRefinement.Frame(
                                1, FRAME.playerSection(), List.of(), true)).status()));

        release.countDown();
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertEquals(1, calls.get());
        assertEquals(0, module.snapshot().representedChildren(),
                "completion from the stopped epoch must not repopulate reset state");
    }

    @Test
    void movingTargetsCannotCompleteTheFixedInitialBatchEarly() {
        DefaultEndRefinement module = new DefaultEndRefinement(
                new DefaultEndRefinement.Config(1000, 64, 0, 16, 8192, 0, 3, 10, 2),
                intent -> published(intent.demandedChildMask(), intent.demandedChildMask(), 0),
                (level, origin) -> solid(), origin -> WriteOutcome.written(1));
        SectionPos first = new SectionPos(0, 0, 0);
        SectionPos second = new SectionPos(32, 0, 0);
        SectionPos moved = new SectionPos(64, 0, 0);

        module.advance(new EndRefinement.Frame(1, FRAME.playerSection(),
                List.of(first, second), false));
        module.advance(new EndRefinement.Frame(2, FRAME.playerSection(),
                List.of(moved), false));

        EndRefinement.InitialHorizonSummary initial = module.snapshot().initialHorizon();
        assertEquals(2, initial.targets());
        assertEquals(2, initial.terminal());
        assertEquals(3, module.snapshot().horizon().admitted());
    }

    private static DefaultEndRefinement module(DefaultEndRefinement.ParentWriter writer) {
        return new DefaultEndRefinement(CONFIG, writer,
                (level, origin) -> solid(), origin -> WriteOutcome.written(1), () -> true);
    }

    private static EndRefinement.Frame frame(long time) {
        return new EndRefinement.Frame(time, FRAME.playerSection(), List.of(), false);
    }

    private static VanillaFrontierGuardPlanner.ParentTransaction parent(int x, int y, int z) {
        return new VanillaFrontierGuardPlanner.ParentTransaction(new SectionPos(x, y, z));
    }

    private static void fillL1WithVanilla(EndRefinement module) {
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    module.observeVanilla(new EndRefinement.ObservedVanilla(
                            new SectionPos(x * 2, y * 2, z * 2), 0xFF));
                }
            }
        }
    }

    private static List<SectionPos> horizonTargets() {
        List<SectionPos> result = new ArrayList<>();
        for (int z = -5; z <= 5; z++) {
            for (int x = -5; x <= 5; x++) {
                result.add(new SectionPos(x * 32, 0, z * 32));
            }
        }
        return result;
    }

    private static ParentRefinementResult published(int required, int represented, int empty) {
        WriteOutcome outcome = represented == 0
                ? WriteOutcome.skippedAir() : WriteOutcome.written(Integer.bitCount(represented));
        return ParentRefinementResult.published(outcome, represented & required, empty & required);
    }

    private static VoxelVolume solid() {
        return VoxelVolume.uniform(32, EndL4DeterministicCandidate.BLOCK_END_STONE, 0);
    }
}
