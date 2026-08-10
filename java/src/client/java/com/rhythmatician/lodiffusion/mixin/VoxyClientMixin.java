package com.rhythmatician.lodiffusion.mixin;

import com.rhythmatician.lodiffusion.voxy.VoxyDebugState;
import me.cortex.voxy.client.VoxyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VoxyClient.class)
public abstract class VoxyClientMixin {

    @Inject(method = "getOcclusionDebugState", at = @At("HEAD"), cancellable = true)
    private static void lod$getOcclusionDebugState(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(VoxyDebugState.occlusionDebugState);
    }
}
