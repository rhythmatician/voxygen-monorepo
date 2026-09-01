package com.rhythmatician.lodiffusion.mixin;

import me.cortex.voxy.client.core.rendering.hierachical.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Redirects only Voxy's node-outline vertex shader to the LOD palette shader. */
@Mixin(value = DebugRenderer.class, remap = false)
public abstract class VoxyDebugRendererShaderMixin {
    @ModifyConstant(
            method = "<init>",
            constant = @Constant(stringValue = "voxy:lod/hierarchical/debug/node_outline.vert"),
            require = 1)
    private String lodiffusion$useLodPaletteVertexShader(String original) {
        return "lodiffusion:lod/hierarchical/debug/node_outline.vert";
    }
}
