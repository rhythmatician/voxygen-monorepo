package net.lodiffusion.mixin.voxy;

import com.rhythmatician.voxygen.backend.voxy.VoxyNodeRequestRetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lifecycle-owned clear for the request-retry registry.
 *
 * <p>The retry set is indexed by packed position only (no world/dimension identity).
 * A stale refusal from world/session A must not match the same position in world/session B.
 * Clearing on {@code NodeManager} construction ensures a new render tree never inherits
 * refusals from the previous NodeManager that produced them, complementing the
 * {@code GenerationSession}/{@code LodGenerationService} lifecycle clears on
 * disconnect/world-replacement/dimension-rebind (#151 isolation).</p>
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager")
public class VoxyNodeRequestRetryLifecycleMixin {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void voxygen$clearRetryOnInit(CallbackInfo ci) {
        VoxyNodeRequestRetry.clear();
    }
}
