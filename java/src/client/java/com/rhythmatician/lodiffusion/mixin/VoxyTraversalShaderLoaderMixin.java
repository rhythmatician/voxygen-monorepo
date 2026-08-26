package com.rhythmatician.lodiffusion.mixin;

import com.rhythmatician.lodiffusion.voxy.VoxyTraversalNodeIdShaderPatch;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderLoader.class, remap = false)
public abstract class VoxyTraversalShaderLoaderMixin {
    @Inject(method = "parse", at = @At("RETURN"), cancellable = true, require = 1)
    private static void lodiffusion$addSelectedNodeIdQueue(
            String id, CallbackInfoReturnable<String> callback) {
        if (VoxyTraversalNodeIdShaderPatch.TRAVERSAL_SHADER.equals(id)) {
            callback.setReturnValue(VoxyTraversalNodeIdShaderPatch.patch(callback.getReturnValue()));
        }
    }
}
