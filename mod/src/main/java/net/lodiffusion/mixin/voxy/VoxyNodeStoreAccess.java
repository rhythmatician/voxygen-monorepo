package net.lodiffusion.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code NodeManager.nodeData} (NodeStore) for refusal recording.
 *
 * <p>Accessor-only: mixin interfaces must not carry default methods. A default
 * method forces Knot to load the mixin class as a regular type when cast to,
 * which Mixin forbids (IllegalClassLoadError). Position resolution lives in
 * {@link me.cortex.voxy.client.core.rendering.hierachical.NodeStoreAccessBridge}.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager")
public interface VoxyNodeStoreAccess {

    @Accessor("nodeData")
    Object voxygen$nodeData();
}
