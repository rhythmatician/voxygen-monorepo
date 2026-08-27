package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VanillaOccupancyIntegrationTest {
    private static final DefaultEndRefinement.Config CONFIG =
            new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 0, 3, 10, 0);

    @Test
    void chunkColumnOwnsBothYOctantsInItsL0QuadrantAcrossEndHeight() {
        assertEquals(0b0001_0001, GenerationSession.chunkColumnOwnershipMask(0, 0));
        assertEquals(0b1000_1000, GenerationSession.chunkColumnOwnershipMask(-1, -1));
        assertEquals(-1, GenerationSession.l0WorldSectionForChunk(-1));
        assertEquals(0, GenerationSession.l0WorldSectionForChunk(1));
    }

    @Test
    void observedVanillaCreatesSparseBoundaryWorkButNeverMaterializesOwnedBits() {
        List<ParentRefinementIntent> intents = new ArrayList<>();
        DefaultEndRefinement module = module(intent -> {
            intents.add(intent);
            return ParentRefinementResult.published(
                    WriteOutcome.written(Integer.bitCount(intent.demandedChildMask())),
                    intent.demandedChildMask(), 0);
        });

        module.observeVanilla(new EndRefinement.ObservedVanilla(
                new SectionPos(0, 0, 0), 0xFF));
        for (int tick = 1; tick <= 64 && intents.isEmpty(); tick++) {
            module.advance(frame(tick));
        }

        assertFalse(intents.isEmpty());
        assertTrue(intents.stream().allMatch(intent -> intent.demandedChildMask() != 0));
    }

    @Test
    void staleRequestThatBecomesFullVanillaDoesNotReachTerrainOrWriter() {
        AtomicInteger terrain = new AtomicInteger();
        List<String> writes = new ArrayList<>();
        DefaultEndRefinement module = new DefaultEndRefinement(CONFIG, intent -> {
            writes.add(intent.parentLevel() + ":" + intent.parentOrigin());
            return ParentRefinementResult.published(
                    WriteOutcome.written(Integer.bitCount(intent.demandedChildMask())),
                    intent.demandedChildMask(), 0);
        }, (level, origin) -> {
            terrain.incrementAndGet();
            return solid();
        }, origin -> WriteOutcome.written(1), () -> true);
        module.observeFrontier(List.of(
                new VanillaFrontierGuardPlanner.ParentTransaction(new SectionPos(0, 0, 0))));

        fillL1WithVanilla(module);
        for (int tick = 1; tick <= 64; tick++) module.advance(frame(tick));

        assertEquals(0, terrain.get());
        assertFalse(writes.contains(Level.L1 + ":" + new SectionPos(0, 0, 0)));
        assertTrue(module.snapshot().vanillaCoveredChildren() >= 8);
        assertEquals(0, module.snapshot().pendingChildren());
    }

    @Test
    void partialVanillaParentTreatsOwnedOctantsAsCoveredAndWritesOnlyMissingOctants() {
        List<ParentRefinementIntent> intents = new ArrayList<>();
        DefaultEndRefinement module = module(intent -> {
            intents.add(intent);
            return ParentRefinementResult.published(
                    WriteOutcome.written(Integer.bitCount(intent.demandedChildMask())),
                    intent.demandedChildMask(), 0);
        });
        module.observeVanilla(new EndRefinement.ObservedVanilla(
                new SectionPos(0, 0, 0), 0xFF));
        module.observeFrontier(List.of(
                new VanillaFrontierGuardPlanner.ParentTransaction(new SectionPos(0, 0, 0))));

        for (int tick = 1; tick <= 128; tick++) module.advance(frame(tick));

        ParentRefinementIntent target = intents.stream()
                .filter(intent -> intent.parentLevel() == Level.L1
                        && intent.parentOrigin().equals(new SectionPos(0, 0, 0)))
                .findFirst().orElseThrow();
        assertTrue(Integer.bitCount(target.demandedChildMask()) < 8);
        assertTrue(module.snapshot().vanillaCoveredChildren() > 0);
        assertEquals(0,
                module.snapshot().pendingChildren() + module.snapshot().executingChildren());
    }

    @Test
    void negativeChunkCoordinatesMapToTheSameSparseOccupancyRules() {
        List<SectionPos> parents = new ArrayList<>();
        DefaultEndRefinement module = module(intent -> {
            parents.add(intent.parentOrigin());
            return ParentRefinementResult.published(
                    WriteOutcome.written(Integer.bitCount(intent.demandedChildMask())),
                    intent.demandedChildMask(), 0);
        });

        module.observeVanilla(new EndRefinement.ObservedVanilla(
                new SectionPos(-2, 0, -2), GenerationSession.chunkColumnOwnershipMask(-1, -1)));
        module.advance(frame(1));

        assertFalse(parents.isEmpty());
        assertTrue(parents.stream().anyMatch(origin -> origin.x() <= 0 && origin.z() <= 0));
    }

    private static DefaultEndRefinement module(DefaultEndRefinement.ParentWriter writer) {
        return new DefaultEndRefinement(CONFIG, writer, (level, origin) -> solid(),
                origin -> WriteOutcome.written(1), () -> true);
    }

    private static EndRefinement.Frame frame(long time) {
        return new EndRefinement.Frame(
                time, new SectionPos(0, 4, 0), List.of(), false);
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

    private static VoxelVolume solid() {
        return VoxelVolume.uniform(32, EndL4DeterministicCandidate.BLOCK_END_STONE, 0);
    }
}
