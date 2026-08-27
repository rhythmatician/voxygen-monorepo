package me.cortex.voxy.client.core.rendering.hierachical;

import java.lang.reflect.Method;

/**
 * Reflection bridge for resolving a node id to its position from a
 * {@code NodeStore} instance without compile-time access to its internals.
 */
public final class NodeStoreAccessBridge {
    private static volatile Method nodePositionMethod;

    private NodeStoreAccessBridge() { }

    public static long nodePosition(Object nodeStore, int nodeId) {
        try {
            Method m = nodePositionMethod;
            if (m == null) {
                synchronized (NodeStoreAccessBridge.class) {
                    if (nodePositionMethod == null) {
                        nodePositionMethod = nodeStore.getClass().getMethod("nodePosition", int.class);
                    }
                    m = nodePositionMethod;
                }
            }
            return (long) m.invoke(nodeStore, nodeId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot resolve node position", e);
        }
    }
}
