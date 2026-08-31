package com.rhythmatician.voxygen.backend.voxy;

/** Adds a parallel selected-node-ID queue without changing Voxy's mesh render queue. */
public final class VoxyTraversalNodeIdShaderPatch {
    public static final int NODE_ID_QUEUE_BINDING = 11;
    public static final String TRAVERSAL_SHADER = "voxy:lod/hierarchical/traversal_dev.comp";

    private static final String QUEUE_DECLARATION = """
            layout(binding = RENDER_QUEUE_BINDING, std430) restrict buffer renderQueueStruct {
                uint renderQueueIndex;
                uint[] renderQueue;
            };
            """;
    private static final String MESH_WRITE = "renderQueue[renderIndex] = getMesh(node);";

    private VoxyTraversalNodeIdShaderPatch() {}

    public static String patch(String source) {
        if (!source.contains(QUEUE_DECLARATION) || !source.contains(MESH_WRITE)) {
            throw new IllegalStateException("Pinned Voxy traversal shader anchors changed");
        }
        String nodeQueue = QUEUE_DECLARATION + """

                layout(binding = 11, std430) restrict buffer lodiffusionSelectedNodeIdQueue {
                    uint selectedNodeIdCount;
                    uint[] selectedNodeIds;
                };
                """;
        String nodeWrite = MESH_WRITE + """

                                selectedNodeIds[renderIndex] = node.nodeId;
                                atomicMax(selectedNodeIdCount, renderIndex + 1u);
                """;
        return source.replace(QUEUE_DECLARATION, nodeQueue).replace(MESH_WRITE, nodeWrite);
    }
}
