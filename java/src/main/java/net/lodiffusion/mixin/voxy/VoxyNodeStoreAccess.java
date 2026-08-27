package net.lodiffusion.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code NodeManager.nodeData} (NodeStore) for refusal recording.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager")
public interface VoxyNodeStoreAccess {

    @Accessor("nodeData")
    Object voxygen$nodeData();

    /**
     * Convenience: resolves a node id to its world-section position via the
     * node store. Implemented as a default through the accessor.
     */
    default long voxygen$nodePosition(int nodeId) {
        return me.cortex.voxy.client.core.rendering.hierachical.NodeStoreAccessBridge.nodePosition(
                voxygen$nodeData(), nodeId);
    }
}
