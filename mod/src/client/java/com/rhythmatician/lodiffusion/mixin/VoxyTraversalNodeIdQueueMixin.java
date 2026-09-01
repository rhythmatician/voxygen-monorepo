package com.rhythmatician.lodiffusion.mixin;

import com.rhythmatician.lodiffusion.voxy.LodOverlayNodeIdQueueProvider;
import com.rhythmatician.voxygen.backend.voxy.VoxyTraversalNodeIdShaderPatch;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HierarchicalOcclusionTraverser.class, remap = false)
public abstract class VoxyTraversalNodeIdQueueMixin implements LodOverlayNodeIdQueueProvider {
    @Unique private static final long LODIFFUSION_NODE_ID_QUEUE_BYTES = (200_000L + 1L) * Integer.BYTES;
    @Unique private GlBuffer lodiffusion$selectedNodeIds;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void lodiffusion$allocateSelectedNodeIds(CallbackInfo ci) {
        lodiffusion$selectedNodeIds = new GlBuffer(LODIFFUSION_NODE_ID_QUEUE_BYTES).zero();
    }

    @Inject(method = "bindings", at = @At("RETURN"), require = 1)
    private void lodiffusion$bindSelectedNodeIds(Viewport<?> viewport, CallbackInfo ci) {
        GL45C.glBindBufferBase(
                GL45C.GL_SHADER_STORAGE_BUFFER,
                VoxyTraversalNodeIdShaderPatch.NODE_ID_QUEUE_BINDING,
                lodiffusion$selectedNodeIds.id);
    }

    @Inject(
            method = "doTraversal",
            at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser;traverseInternal()V"),
            require = 1)
    private void lodiffusion$clearSelectedNodeIds(Viewport<?> viewport, CallbackInfo ci) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL45C.glClearNamedBufferSubData(
                    lodiffusion$selectedNodeIds.id,
                    GL45C.GL_R32UI,
                    0,
                    Integer.BYTES,
                    GL45C.GL_RED_INTEGER,
                    GL45C.GL_UNSIGNED_INT,
                    stack.ints(0));
        }
    }

    @Inject(method = "free", at = @At("HEAD"), require = 1)
    private void lodiffusion$freeSelectedNodeIds(CallbackInfo ci) {
        lodiffusion$selectedNodeIds.free();
        lodiffusion$selectedNodeIds = null;
    }

    @Override
    public GlBuffer selectedNodeIds() {
        return lodiffusion$selectedNodeIds;
    }
}
