package net.lodiffusion.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rhythmatician.voxygen.generation.refinement.RefinementAdmissionGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RefinementAdmissionGateTest {
    @AfterEach
    void restoreQueueAndProperty() {
        System.clearProperty(RefinementAdmissionGate.DISABLE_PROPERTY);
        ShadowRouterJobQueue.clear();
    }

    @Test
    void disabledModeRejectsParentAdmissionButAcceptsHorizonLeaves() {
        System.setProperty(RefinementAdmissionGate.DISABLE_PROPERTY, "true");

        assertEquals(ShadowRouterJobQueue.EnqueueResult.REJECTED,
                ShadowRouterJobQueue.enqueue(request(VoxyWorkKind.PARENT_REFINEMENT)));
        assertEquals(ShadowRouterJobQueue.EnqueueResult.QUEUED,
                ShadowRouterJobQueue.enqueue(request(VoxyWorkKind.HORIZON_LEAF)));
    }

    private static VoxyRequestDecoder.VoxyNodeRequest request(VoxyWorkKind kind) {
        VoxyRequestDecoder.VoxyNodeRequest request = new VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = 4;
        request.worldY = 0;
        request.workKind = kind;
        request.demandKind = kind == VoxyWorkKind.HORIZON_LEAF
                ? VoxyDemandKind.HORIZON_COVERAGE
                : VoxyDemandKind.VISUAL_REFINEMENT;
        return request;
    }
}
