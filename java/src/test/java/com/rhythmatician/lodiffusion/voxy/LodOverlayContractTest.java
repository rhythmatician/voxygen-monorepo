package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LodOverlayContractTest {
    @AfterEach
    void restoreDiagnosticDefault() {
        LodOverlayState.setEnabled(true);
    }

    @Test
    void diagnosticBuildDefaultsOverlayOnAndKeepsItsOwnState() {
        assertTrue(LodOverlayState.isEnabled());
        assertFalse(LodOverlayState.toggle());
        assertFalse(LodOverlayState.isEnabled());
    }

    @Test
    @SuppressWarnings("null")
    void paletteMatchesTheFlyoverLegend() {
        assertEquals(
                List.of(0xFF3030, 0xFF9D2E, 0xF2E84B, 0x35D6E8, 0xB56BFF),
                LodOverlayState.palette().stream().map(LodOverlayState.PaletteEntry::rgb).toList());
        assertEquals("L0 red | L1 orange | L2 yellow | L3 cyan | L4 violet | vanilla uncolored",
                LodOverlayState.legend());
    }

    @Test
    void resourcesWireThePipelineAndActualLodShader() throws Exception {
        Path root = locateJavaRoot();
        String fabric = Files.readString(root.resolve("src/main/resources/fabric.mod.json"));
        String mixins = Files.readString(root.resolve("src/main/resources/lodiffusion.client.mixins.json"));
        String shader = Files.readString(root.resolve(
                "src/main/resources/assets/lodiffusion/shaders/lod/hierarchical/debug/node_outline.vert"));
        String overlayMixin = Files.readString(root.resolve(
                "src/client/java/com/rhythmatician/lodiffusion/mixin/AbstractRenderPipelineLodOverlayMixin.java"));

        assertTrue(fabric.contains("com.rhythmatician.lodiffusion.voxy.LodOverlayClient"));
        assertTrue(mixins.contains("AbstractRenderPipelineLodOverlayMixin"));
        assertTrue(mixins.contains("VoxyDebugRendererShaderMixin"));
        assertTrue(mixins.contains("VoxyTraversalNodeIdQueueMixin"));
        assertTrue(mixins.contains("VoxyTraversalShaderLoaderMixin"));
        assertTrue(shader.contains("layout(binding = 1"));
        assertTrue(shader.contains("lodLevel = packedPos.x >> 28"));
        assertFalse(shader.contains("printf("));
        assertTrue(shader.contains("#FF3030"));
        assertTrue(shader.contains("#B56BFF"));
        assertTrue(shader.contains("255.0,  48.0,  48.0,  24.0"));
        assertTrue(shader.contains("255.0, 157.0,  46.0,  24.0"));
        assertTrue(shader.contains("242.0, 232.0,  75.0,  24.0"));
        assertTrue(shader.contains(" 53.0, 214.0, 232.0,  24.0"));
        assertTrue(shader.contains("181.0, 107.0, 255.0,  24.0"));

        assertTrue(overlayMixin.contains("glEnable(GL45C.GL_BLEND)"));
        assertTrue(overlayMixin.contains("glBlendFuncSeparate"));
        assertTrue(overlayMixin.contains("GL45C.GL_SRC_ALPHA"));
        assertTrue(overlayMixin.contains("GL45C.GL_ONE_MINUS_SRC_ALPHA"));
        assertTrue(overlayMixin.contains("glBlendEquationSeparate"));
        assertTrue(overlayMixin.contains("GL45C.GL_FUNC_ADD"));
        assertTrue(overlayMixin.contains("GL45C.GL_COLOR_WRITEMASK"));
        assertTrue(overlayMixin.contains("glColorMask("));
        assertTrue(overlayMixin.contains("selectedNodeIds()"));
        assertFalse(overlayMixin.contains("viewport.getRenderList()"));
    }

    @Test
    void traversalPatchPreservesMeshQueueAndWritesParallelNodeIdsAtTheSameIndex() {
        String source = """
                layout(binding = RENDER_QUEUE_BINDING, std430) restrict buffer renderQueueStruct {
                    uint renderQueueIndex;
                    uint[] renderQueue;
                };
                renderQueue[renderIndex] = getMesh(node);
                """;

        String patched = VoxyTraversalNodeIdShaderPatch.patch(source);

        assertTrue(patched.contains("renderQueue[renderIndex] = getMesh(node);"));
        assertTrue(patched.contains("selectedNodeIds[renderIndex] = node.nodeId;"));
        assertTrue(patched.contains("atomicMax(selectedNodeIdCount, renderIndex + 1u);"));
    }

    private static Path locateJavaRoot() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("src/main/resources/fabric.mod.json"))) {
                return cursor;
            }
            if (Files.exists(cursor.resolve("java/src/main/resources/fabric.mod.json"))) {
                return cursor.resolve("java");
            }
        }
        throw new IllegalStateException("Could not locate java project root");
    }
}
