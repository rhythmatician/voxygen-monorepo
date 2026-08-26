package com.rhythmatician.lodiffusion.mixin;

import com.rhythmatician.lodiffusion.voxy.LodOverlayState;
import com.rhythmatician.lodiffusion.voxy.LodOverlayNodeIdQueueProvider;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.DebugRenderer;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.ByteBuffer;

/** Draws Voxy's post-traversal render list with a color for its actual node LOD. */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public abstract class AbstractRenderPipelineLodOverlayMixin {
    @Shadow @Final private HierarchicalOcclusionTraverser traversal;
    @Unique private DebugRenderer lodiffusion$lodDebugRenderer;

    @Inject(
            method = "runPipeline",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V"),
            require = 1)
    private void lodiffusion$renderActualLodOverlay(
            Viewport<?> viewport, int sourceFramebuffer, int width, int height, CallbackInfo ci) {
        if (!LodOverlayState.isEnabled()) {
            return;
        }
        if (lodiffusion$lodDebugRenderer == null) {
            lodiffusion$lodDebugRenderer = new DebugRenderer();
        }

        int program = GL45C.glGetInteger(GL45C.GL_CURRENT_PROGRAM);
        int vertexArray = GL45C.glGetInteger(GL45C.GL_VERTEX_ARRAY_BINDING);
        int drawIndirectBuffer = GL45C.glGetInteger(GL45C.GL_DRAW_INDIRECT_BUFFER_BINDING);
        int uniformBuffer = GL45C.glGetInteger(GL45C.GL_UNIFORM_BUFFER_BINDING);
        int storageBuffer = GL45C.glGetInteger(GL45C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int sceneUniform = GL45C.glGetIntegeri(GL45C.GL_UNIFORM_BUFFER_BINDING, 0);
        int nodeData = GL45C.glGetIntegeri(GL45C.GL_SHADER_STORAGE_BUFFER_BINDING, 1);
        int nodeList = GL45C.glGetIntegeri(GL45C.GL_SHADER_STORAGE_BUFFER_BINDING, 2);
        boolean depthTest = GL45C.glIsEnabled(GL45C.GL_DEPTH_TEST);
        boolean blend = GL45C.glIsEnabled(GL45C.GL_BLEND);
        boolean cullFace = GL45C.glIsEnabled(GL45C.GL_CULL_FACE);
        int depthFunction = GL45C.glGetInteger(GL45C.GL_DEPTH_FUNC);
        boolean depthWrite = GL45C.glGetBoolean(GL45C.GL_DEPTH_WRITEMASK);
        float lineWidth = GL45C.glGetFloat(GL45C.GL_LINE_WIDTH);
        int blendSrcRgb = GL45C.glGetInteger(GL45C.GL_BLEND_SRC_RGB);
        int blendDstRgb = GL45C.glGetInteger(GL45C.GL_BLEND_DST_RGB);
        int blendSrcAlpha = GL45C.glGetInteger(GL45C.GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = GL45C.glGetInteger(GL45C.GL_BLEND_DST_ALPHA);
        int blendEquationRgb = GL45C.glGetInteger(GL45C.GL_BLEND_EQUATION_RGB);
        int blendEquationAlpha = GL45C.glGetInteger(GL45C.GL_BLEND_EQUATION_ALPHA);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer colorMask = stack.malloc(4);
            GL45C.glGetBooleanv(GL45C.GL_COLOR_WRITEMASK, colorMask);
            try {
                GL45C.glEnable(GL45C.GL_BLEND);
                GL45C.glBlendFuncSeparate(
                        GL45C.GL_SRC_ALPHA,
                        GL45C.GL_ONE_MINUS_SRC_ALPHA,
                        GL45C.GL_ONE,
                        GL45C.GL_ONE_MINUS_SRC_ALPHA);
                GL45C.glBlendEquationSeparate(GL45C.GL_FUNC_ADD, GL45C.GL_FUNC_ADD);
                GL45C.glDepthMask(false);
                GL45C.glColorMask(true, true, true, true);
                lodiffusion$lodDebugRenderer.render(
                        viewport,
                        traversal.getNodeBuffer(),
                        ((LodOverlayNodeIdQueueProvider) traversal).selectedNodeIds());
            } finally {
                GL45C.glUseProgram(program);
                GL45C.glBindVertexArray(vertexArray);
                GL45C.glBindBuffer(GL45C.GL_DRAW_INDIRECT_BUFFER, drawIndirectBuffer);
                GL45C.glBindBufferBase(GL45C.GL_UNIFORM_BUFFER, 0, sceneUniform);
                GL45C.glBindBufferBase(GL45C.GL_SHADER_STORAGE_BUFFER, 1, nodeData);
                GL45C.glBindBufferBase(GL45C.GL_SHADER_STORAGE_BUFFER, 2, nodeList);
                GL45C.glBindBuffer(GL45C.GL_UNIFORM_BUFFER, uniformBuffer);
                GL45C.glBindBuffer(GL45C.GL_SHADER_STORAGE_BUFFER, storageBuffer);
                GL45C.glDepthFunc(depthFunction);
                GL45C.glDepthMask(depthWrite);
                GL45C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
                GL45C.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
                GL45C.glColorMask(
                        colorMask.get(0) != 0,
                        colorMask.get(1) != 0,
                        colorMask.get(2) != 0,
                        colorMask.get(3) != 0);
                GL45C.glLineWidth(lineWidth);
                lodiffusion$restoreCapability(GL45C.GL_DEPTH_TEST, depthTest);
                lodiffusion$restoreCapability(GL45C.GL_BLEND, blend);
                lodiffusion$restoreCapability(GL45C.GL_CULL_FACE, cullFace);
            }
        }
    }

    @Inject(method = "free0", at = @At("HEAD"), require = 1)
    private void lodiffusion$freeLodOverlayRenderer(CallbackInfo ci) {
        if (lodiffusion$lodDebugRenderer != null) {
            lodiffusion$lodDebugRenderer.free();
            lodiffusion$lodDebugRenderer = null;
        }
    }

    @Unique
    private static void lodiffusion$restoreCapability(int capability, boolean enabled) {
        if (enabled) {
            GL45C.glEnable(capability);
        } else {
            GL45C.glDisable(capability);
        }
    }
}
