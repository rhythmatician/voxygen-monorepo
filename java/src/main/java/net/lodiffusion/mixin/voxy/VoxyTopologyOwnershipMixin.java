package net.lodiffusion.mixin.voxy;

import com.rhythmatician.voxygen.backend.voxy.VoxyTopologyOwnership;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps an owned generated fallback leaf intact while vanilla data is ingested. */
@Mixin(targets = "me.cortex.voxy.common.world.WorldSection")
public class VoxyTopologyOwnershipMixin {
    @Inject(
            method = "updateEmptyChildState(Lme/cortex/voxy/common/world/WorldSection;)I",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void lodiffusion$suppressOwnedNativePromotion(
            @Coerce Object child, CallbackInfoReturnable<Integer> cir) {
        if (VoxyTopologyOwnership.beginNativePromotion(this)) {
            cir.setReturnValue(0);
        }
    }

    @Inject(
            method = "updateEmptyChildState(Lme/cortex/voxy/common/world/WorldSection;)I",
            at = @At("RETURN"),
            require = 1)
    private void lodiffusion$finishNativePromotion(
            @Coerce Object child, CallbackInfoReturnable<Integer> cir) {
        VoxyTopologyOwnership.finishNativePromotion();
    }
}
