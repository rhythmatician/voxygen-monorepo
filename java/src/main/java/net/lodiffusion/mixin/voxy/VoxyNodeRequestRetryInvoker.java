package net.lodiffusion.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code NodeManager.processRequest} for the retry path.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager")
public interface VoxyNodeRequestRetryInvoker {
    @Invoker("processRequest")
    void voxygen$processRequest(long position);
}
