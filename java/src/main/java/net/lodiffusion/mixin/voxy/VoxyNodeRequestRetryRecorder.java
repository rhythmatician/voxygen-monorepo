package net.lodiffusion.mixin.voxy;

import com.rhythmatician.lodiffusion.voxy.VoxyNodeRequestRetry;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records leaf expansion requests refused for an empty child-existence
 * snapshot. Injection pins to the refusal branch of
 * {@code makeLeafChildRequest} via its unique {@code invalidateNode} call —
 * the only place a request is dropped with "existence mask of 0".
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager")
public class VoxyNodeRequestRetryRecorder {

    /**
     * The refusal branch calls {@code invalidateNode(nodeId)} right before
     * returning. Capturing the position there is exact: successful requests
     * never reach this call from makeLeafChildRequest.
     */
    @Inject(
            method = "makeLeafChildRequest(I)V",
            at = @At(value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/rendering/hierachical/NodeManager;invalidateNode(I)V"),
            require = 0)
    private void voxygen$recordEmptyMaskRefusal(int nodeId, CallbackInfo ci) {
        // The refusal path is: warn -> unmarkRequestInFlight -> invalidateNode -> return.
        // We recover the position from the node id through NodeStore.
        VoxyNodeRequestRetry.recordRefusal(
                ((VoxyNodeStoreAccess) (Object) this).voxygen$nodePosition(nodeId));
    }
}
