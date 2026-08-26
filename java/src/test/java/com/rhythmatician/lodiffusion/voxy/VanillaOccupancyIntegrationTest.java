package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyDemandKind;
import net.lodiffusion.shadow.VoxyDemandSource;
import net.lodiffusion.shadow.VoxyRequestDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VanillaOccupancyIntegrationTest {
    @BeforeEach void setUp() { ShadowRouterJobQueue.clear(); }
    @AfterEach void tearDown() { ShadowRouterJobQueue.clear(); }

    @Test
    void chunkColumnOwnsBothYOctantsInItsL0QuadrantAcrossEndHeight() {
        assertEquals(0b0001_0001, GenerationSession.chunkColumnOwnershipMask(0, 0));
        assertEquals(0b1000_1000, GenerationSession.chunkColumnOwnershipMask(-1, -1));
        assertEquals(-1, GenerationSession.l0WorldSectionForChunk(-1));
        assertEquals(0, GenerationSession.l0WorldSectionForChunk(1));
    }

    @Test
    void occupancyDeltaQueuesSparseBoundaryWorkButNeverFullVanillaCells() {
        GenerationSession session = new GenerationSession();

        session.observeVanillaChunkColumnForTest(0, 0);

        assertTrue(ShadowRouterJobQueue.size() > 0);
        var request = ShadowRouterJobQueue.dequeueAny();
        assertEquals(VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY, request.demandSource);
        assertTrue(request.demandKind == VoxyDemandKind.VANILLA_FRONTIER_GUARD
                || request.demandKind == VoxyDemandKind.HORIZON_COVERAGE);

        session.observeVanillaChunkColumnForTest(1, 0);
        session.observeVanillaChunkColumnForTest(0, 1);
        session.observeVanillaChunkColumnForTest(1, 1);
        assertTrue(session.isFullyVanillaForTest(0, 0, 0, 0));
    }

    @Test
    void urgentBoundaryCellsAlwaysRequestTheirContainingParentTransaction() {
        GenerationSession session = new GenerationSession();
        for (int level = 0; level <= 3; level++) {
            ShadowRouterJobQueue.clear();
            VanillaOccupancyPyramid.Cell boundary = new VanillaOccupancyPyramid.Cell(level, -3, 1, 5);
            session.enqueueOccupancyDeltaForTest(new VanillaOccupancyPyramid.Delta(
                    java.util.Set.of(boundary), java.util.Set.of(), java.util.Set.of()));

            VoxyRequestDecoder.VoxyNodeRequest request = ShadowRouterJobQueue.dequeueAny();
            assertEquals(level + 1, request.lodLevel, "boundary L" + level + " parent level");
            assertEquals(Math.floorDiv(-3, 2), request.worldX);
            assertEquals(Math.floorDiv(1, 2), request.worldY);
            assertEquals(Math.floorDiv(5, 2), request.worldZ);
            assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, request.demandKind);
            assertEquals(net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT, request.workKind);
            ShadowRouterJobQueue.markCompleted(request);
        }
    }

    @Test
    void l4OccupancyBoundaryIsGuardPriorityHorizonLeaf() {
        GenerationSession session = new GenerationSession();
        VanillaOccupancyPyramid.Cell boundary = new VanillaOccupancyPyramid.Cell(4, 3, 0, -2);
        session.enqueueOccupancyDeltaForTest(new VanillaOccupancyPyramid.Delta(
                java.util.Set.of(boundary), java.util.Set.of(), java.util.Set.of()));

        VoxyRequestDecoder.VoxyNodeRequest request = ShadowRouterJobQueue.dequeueAny();
        assertEquals(4, request.lodLevel);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, request.demandKind);
        assertEquals(net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF, request.workKind);
        assertEquals(VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY, request.demandSource);
        ShadowRouterJobQueue.markCompleted(request);
        assertEquals(1, ShadowRouterJobQueue.demandMetrics().guard().queued());
        assertEquals(1, ShadowRouterJobQueue.demandMetrics().guard().completed());
    }

    @Test
    void staleFullyVanillaParentTransactionIsRecognizedBeforeProduction() {
        GenerationSession session = new GenerationSession();
        for (int chunkX = 0; chunkX < 4; chunkX++) {
            for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                session.observeVanillaChunkColumnForTest(chunkX, chunkZ);
            }
        }

        VoxyRequestDecoder.VoxyNodeRequest request = new VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = 1;
        request.workKind = net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT;
        request.demandKind = VoxyDemandKind.VANILLA_FRONTIER_GUARD;
        request.demandSource = VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY;
        assertTrue(session.isFullyVanillaOccupancyRequestForTest(request));
    }
}
