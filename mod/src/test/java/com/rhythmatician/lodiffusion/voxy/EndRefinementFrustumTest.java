package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.refinement.*;
import com.rhythmatician.voxygen.generation.scheduling.VanillaFrontierGuardPlanner;
import com.rhythmatician.voxygen.semantic.*;
import com.rhythmatician.voxygen.output.WriteOutcome;

class EndRefinementFrustumTest {
    private static final List<SectionPos> HORIZON = List.of(
            new SectionPos(32, 0, 0), new SectionPos(-64, 0, 0));

    private static DefaultEndRefinement module(List<ParentRefinementIntent> published) {
        return new DefaultEndRefinement(
                new DefaultEndRefinement.Config(100000, 64, 100000, 100000, 8192, 1000),
                intent -> { published.add(intent); return ParentRefinementResult.published(WriteOutcome.written(1)); },
                (level, origin) -> VoxelVolume.uniform(32, 0, 255), origin -> WriteOutcome.written(1), () -> true);
    }

    private static EndRefinement.Frame frame(long now, RefinementView view) {
        return new EndRefinement.Frame(now, new SectionPos(0, 0, 0), HORIZON, false, view);
    }

    @Test
    void cameraCullPreservesBothHorizonRootsAndTurningAdmitsTheOppositeHalf() {
        var publications = new ArrayList<ParentRefinementIntent>();
        var module = module(publications);
        module.advance(frame(100, RefinementFrustumTest.view(1)));
        module.advance(frame(101, RefinementFrustumTest.view(1)));
        assertEquals(2, module.snapshot().horizon().completed());
        assertEquals(585, module.snapshot().visualSelection().admitted());
        assertEquals(585, module.snapshot().visualSelection().frustumRejected());
        assertEquals(1170, module.snapshot().visualSelection().considered());
        assertEquals(0, module.snapshot().deterministicEmptyChildren());
        module.advance(frame(102, RefinementFrustumTest.view(-1)));
        assertEquals(1170, module.snapshot().visualSelection().admitted());
        assertEquals(1170, module.snapshot().visualSelection().frustumRejected());
    }

    @Test
    void missingStaleFutureAndMalformedViewsPreserveAllVisualDemand() {
        for (RefinementView view : new RefinementView[]{null, RefinementFrustumTest.view(1),
                new RefinementView(1200, 0, 0, 0, RefinementFrustumTest.view(1).planes()),
                new RefinementView(1101, Double.NaN, 0, 0, RefinementFrustumTest.view(1).planes()),
                new RefinementView(1101, 0, 0, 0, List.of())}) {
            var module = module(new ArrayList<>());
            module.advance(frame(1101, view));
            assertEquals(1170, module.snapshot().visualSelection().admitted());
            assertEquals(0, module.snapshot().visualSelection().frustumRejected());
        }
    }

    @Test
    void frontierRemainsAdmittedBehindTheCamera() {
        var publications = new ArrayList<ParentRefinementIntent>();
        var module = module(publications);
        assertEquals(1, module.observeFrontier(List.of(
                new VanillaFrontierGuardPlanner.ParentTransaction(new SectionPos(-64, 0, 0)))));
        module.advance(new EndRefinement.Frame(100, new SectionPos(0, 0, 0), List.of(), false,
                RefinementFrustumTest.view(1)));
        assertEquals(1, publications.size());
        assertEquals(new SectionPos(-64, 0, 0), publications.getFirst().parentOrigin());
        assertEquals(0, module.snapshot().visualSelection().frustumRejected());
    }

    @Test
    void parentPrerequisiteBehindCameraWakesTheAdmittedRequest() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var publications = new ArrayList<ParentRefinementIntent>();
        var module = new DefaultEndRefinement(
                new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 1000), intent -> {
                    publications.add(intent);
                    if (intent.parentLevel() == Level.L1 && attempts.getAndIncrement() == 0) {
                        return ParentRefinementResult.parentMissing();
                    }
                    return ParentRefinementResult.published(WriteOutcome.written(1));
                }, (level, origin) -> VoxelVolume.uniform(32, 1, 0), origin -> WriteOutcome.written(1), () -> true);
        module.observeFrontier(List.of(new VanillaFrontierGuardPlanner.ParentTransaction(new SectionPos(-64, 0, 0))));
        for (int i = 0; i < 3; i++) {
            module.advance(new EndRefinement.Frame(100 + i, new SectionPos(0, 0, 0), List.of(), false,
                    RefinementFrustumTest.view(1)));
        }
        assertEquals(List.of(Level.L1, Level.L2, Level.L1),
                publications.stream().map(ParentRefinementIntent::parentLevel).toList());
        assertEquals(2, attempts.get());
        assertEquals(0, module.snapshot().visualSelection().frustumRejected());
    }
}
