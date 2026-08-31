package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.lodiffusion.shadow.VoxyWorkKind;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.refinement.DefaultEndRefinement;
import com.rhythmatician.voxygen.generation.dimension.end.EndL4DeterministicCandidate;
import com.rhythmatician.voxygen.generation.refinement.EndRefinement;
import com.rhythmatician.voxygen.generation.refinement.RefinementAdmissionGate;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.output.WriteOutcome;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementBatch;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult;

class EndPhysicalWorkDispatcherTest {
    private static final DefaultEndRefinement.Config CONFIG =
            new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 0);
    private static final EndRefinement.Frame FRAME =
            new EndRefinement.Frame(1, new SectionPos(0, 4, 0), List.of(), false);

    @Test
    void diagnosticAdmissionGateBlocksRefinementButLeavesHorizonMutationEnabled() {
        String property = RefinementAdmissionGate.DISABLE_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            assertFalse(RefinementAdmissionGate.allows(VoxyWorkKind.PARENT_REFINEMENT));
            assertTrue(RefinementAdmissionGate.allows(VoxyWorkKind.HORIZON_LEAF));

            AtomicInteger horizons = new AtomicInteger();
            DefaultEndRefinement module = module(intent -> published(intent.demandedChildMask()),
                    origin -> {
                        horizons.incrementAndGet();
                        return WriteOutcome.written(1);
                    });
            assertEquals(EndRefinement.StepResult.Status.PROGRESSED,
                    module.advance(frame(1, List.of(new SectionPos(0, 0, 0)))) .status());
            assertEquals(1, horizons.get());
            assertEquals(0, module.snapshot().refinement().completed());
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void parentRefinementSubmitsOneSparseTransactionAndAttributesItOnce() {
        AtomicInteger terrainCalls = new AtomicInteger();
        AtomicInteger transactions = new AtomicInteger();
        DefaultEndRefinement module = new DefaultEndRefinement(CONFIG, intent -> {
            transactions.incrementAndGet();
            int required = intent.demandedChildMask();
            int childLevel = intent.parentLevel().value() - 1;
            for (int octant = 0; octant < 8; octant++) {
                if ((required & (1 << octant)) != 0) {
                    intent.childVolumes().produce(Level.values()[childLevel],
                            ParentRefinementBatch.childOrigins(intent.parentOrigin(), intent.parentLevel())
                                    .get(octant));
                }
            }
            return published(required);
        }, (level, origin) -> {
            terrainCalls.incrementAndGet();
            return solid();
        }, origin -> WriteOutcome.written(1));
        observeOneVanillaOctant(module);

        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(FRAME).status());
        assertEquals(1, transactions.get());
        assertEquals(module.snapshot().representedChildren(), terrainCalls.get());
        assertEquals(1, module.snapshot().refinement().completed());
        assertTrue(module.snapshot().lifecycle().contains("d1"));
        assertTrue(module.snapshot().lifecycle().contains("n1"));
    }

    @Test
    void blockedAndFailedTransactionsReceiveExactlyOneLifecycleAttribution() {
        DefaultEndRefinement blocked = module(intent -> ParentRefinementResult.parentMissing(),
                origin -> WriteOutcome.written(1));
        observeOneVanillaOctant(blocked);
        assertEquals(EndRefinement.StepResult.Status.DEFERRED, blocked.advance(FRAME).status());
        assertEquals(1, occurrences(blocked.snapshot().lifecycle(), ",b1"));
        assertEquals(1, occurrences(blocked.snapshot().lifecycle(), "d1"));

        DefaultEndRefinement failed = module(intent -> {
            throw new IllegalStateException("writer failed");
        }, origin -> WriteOutcome.written(1));
        observeOneVanillaOctant(failed);
        assertEquals(EndRefinement.StepResult.Status.FAILED, failed.advance(FRAME).status());
        assertEquals(1, occurrences(failed.snapshot().lifecycle(), ",f1"));
        assertEquals(1, occurrences(failed.snapshot().lifecycle(), "d1"));
    }

    @Test
    void l4HorizonLeafUsesTheDirectLeafRoute() {
        AtomicInteger refinements = new AtomicInteger();
        AtomicInteger horizons = new AtomicInteger();
        DefaultEndRefinement module = module(intent -> {
            refinements.incrementAndGet();
            return published(intent.demandedChildMask());
        }, origin -> {
            horizons.incrementAndGet();
            return WriteOutcome.written(1);
        });

        assertEquals(EndRefinement.StepResult.Status.PROGRESSED,
                module.advance(frame(1, List.of(new SectionPos(0, 0, 0)))).status());
        assertEquals(1, horizons.get());
        assertEquals(0, refinements.get());
    }

    private static DefaultEndRefinement module(
            DefaultEndRefinement.ParentWriter writer, DefaultEndRefinement.HorizonCoverage horizon) {
        return new DefaultEndRefinement(CONFIG, writer, (level, origin) -> solid(), horizon);
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

    private static int occurrences(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
