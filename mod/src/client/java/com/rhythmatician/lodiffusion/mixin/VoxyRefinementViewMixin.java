package com.rhythmatician.lodiffusion.mixin;

import java.util.ArrayList;
import com.rhythmatician.voxygen.generation.refinement.RefinementView;
import com.rhythmatician.voxygen.generation.scheduling.LodGenerationService;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies Voxy's already-updated view geometry without traversal or renderer ownership. */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public abstract class VoxyRefinementViewMixin {
    @Inject(method = "runPipeline", at = @At("HEAD"), require = 1)
    private void lodiffusion$captureRefinementView(Viewport<?> viewport,
            int framebuffer, int width, int height, CallbackInfo ci) {
        LodGenerationService service = LodGenerationService.getInstance();
        if (service == null) return;
        RefinementView view = null;
        if (viewport.width > 0 && viewport.height > 0 && viewport.frustumPlanes != null) {
            var planes = new ArrayList<RefinementView.Plane>();
            for (var plane : viewport.frustumPlanes) {
                if (plane == null) {
                    planes.clear();
                    break;
                }
                planes.add(new RefinementView.Plane(plane.x, plane.y, plane.z, plane.w));
            }
            view = new RefinementView(System.currentTimeMillis(),
                    viewport.cameraX, viewport.cameraY, viewport.cameraZ, planes);
        }
        service.updateRefinementView(MinecraftClient.getInstance().world, view);
    }
}
