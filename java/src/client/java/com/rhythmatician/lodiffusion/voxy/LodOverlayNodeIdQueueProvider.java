package com.rhythmatician.lodiffusion.voxy;

import me.cortex.voxy.client.core.gl.GlBuffer;

/** Provides selected node IDs parallel to Voxy's untouched mesh render list. */
public interface LodOverlayNodeIdQueueProvider {
    GlBuffer selectedNodeIds();
}
