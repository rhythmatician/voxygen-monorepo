package net.lodiffusion.mixin.voxy;

import com.rhythmatician.lodiffusion.voxy.VoxyNodeRequestRetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the west-side void race: re-issues leaf expansion requests that
 * were refused for an empty child-existence snapshot once the section later
 * reports non-empty children.
 *
 * <p>Refusal recording is done by {@link VoxyNodeRequestRetryRecorder},
 * which wraps the refusal warn call in {@code makeLeafChildRequest}. This
 * class only performs the retry when a refused position later gains
 * children.</p>
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager")
public class VoxyNodeRequestRetryMixin {

    @Inject(
            method = "processChildChange(JB)V",
            at = @At("TAIL"),
            require = 0)
    private void voxygen$retryRefusedRequest(long position, byte childExistence, CallbackInfo ci) {
        if (VoxyNodeRequestRetry.shouldRetry(position, childExistence)) {
            // Re-issue through the public entry point: it resolves the node,
            // marks the request in-flight, and calls makeLeafChildRequest
            // with the now non-empty stored existence.
            ((VoxyNodeRequestRetryInvoker) (Object) this).voxygen$processRequest(position);
        }
    }
}
