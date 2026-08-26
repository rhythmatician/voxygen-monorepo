package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Test-only adapter around the pinned real Voxy NodeManager. */
public final class HeadlessNodeManagerProbe {
    private final long parentPosition;
    private final Geometry geometry = new Geometry();
    private final Watcher watcher = new Watcher();
    private final NodeManager manager = new NodeManager(1 << 10, geometry, watcher);
    private final Map<Integer, GpuNode> gpuNodes = new HashMap<>();
    private int parentNodeId = -1;

    public HeadlessNodeManagerProbe(long parentPosition) {
        this.parentPosition = parentPosition;
        manager.setTLNCallbacks(id -> parentNodeId = id, ignored -> { });
        manager.insertTopLevelNode(parentPosition);
    }

    public void completeCoarseLeaf(byte childExistence) {
        manager.processChildChange(parentPosition, childExistence);
        manager.processGeometryResult(nonEmptySection(parentPosition, childExistence));
        captureNodeChanges();
    }

    public void requestRefinement() {
        manager.processRequest(parentPosition);
        captureNodeChanges();
    }

    public void publishChildExistence(byte childExistence) {
        manager.processChildChange(parentPosition, childExistence);
        captureNodeChanges();
    }

    public void completeChild(int octant, byte childExistence) {
        long position = childPosition(octant);
        manager.processChildChange(position, childExistence);
        manager.processGeometryResult(nonEmptySection(position, childExistence));
        captureNodeChanges();
    }

    public boolean coarseMeshAllocated() {
        return parentNode().geometryId() > 0 && geometry.contains(parentNode().geometryId());
    }

    public boolean coarseMeshReferencedByActiveGraph() {
        return parentNode().position() == parentPosition && parentNode().geometryId() > 0;
    }

    public boolean parentRequestInFlight() {
        return parentNode().requestInFlight();
    }

    public boolean parentHasNoChildReferences() {
        return parentNode().childPointer() == -1;
    }

    public boolean parentReferencesInstalledChildren() {
        return parentNode().childPointer() >= 0 && !parentNode().requestInFlight();
    }

    public boolean childGeometryInstalledAndReferenced(int octant) {
        long position = childPosition(octant);
        return gpuNodes.values().stream()
                .anyMatch(node -> node.position() == position
                        && node.geometryId() > 0
                        && geometry.contains(node.geometryId()));
    }

    public Set<Long> referencedChildPositions() {
        return gpuNodes.values().stream()
                .filter(node -> node.position() != parentPosition)
                .filter(node -> node.geometryId() > 0)
                .filter(node -> geometry.contains(node.geometryId()))
                .map(GpuNode::position)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Mirrors pinned Voxy traversal semantics: a leaf renders itself; once the
     * parent has installed children, ONLY those children render — there is no
     * parent fallback for octants without an installed child.
     *
     * @return the position of the node that would be rendered for this octant,
     *         or empty when the octant has no render representation (a void).
     */
    public java.util.OptionalLong effectiveRenderablePosition(int octant) {
        GpuNode parent = parentNode();
        if (parent.geometryId() <= 0 || !geometry.contains(parent.geometryId())) {
            throw new IllegalStateException("Parent has no active renderable geometry");
        }
        if (parent.childPointer() < 0) {
            return java.util.OptionalLong.of(parent.position());
        }

        long expectedChildPosition = childPosition(octant);
        for (int offset = 0; offset < parent.childCount(); offset++) {
            GpuNode child = gpuNodes.get(parent.childPointer() + offset);
            if (child != null
                    && child.position() == expectedChildPosition
                    && child.geometryId() > 0
                    && geometry.contains(child.geometryId())) {
                return java.util.OptionalLong.of(child.position());
            }
        }
        // Pinned traversal: hasChildren(node) branch never enqueues the parent,
        // so an octant without an installed child renders nothing.
        return java.util.OptionalLong.empty();
    }

    public int effectiveRenderableLevel(int octant) {
        return WorldEngine.getLevel(
                effectiveRenderablePosition(octant).orElseThrow(
                        () -> new IllegalStateException(
                                "octant " + octant + " has no render representation")));
    }

    public static long allocatedNativeBytes() {
        return MemoryBuffer.getTotalSize();
    }

    public Set<Long> watchedChildren() {
        var children = new HashSet<>(watcher.positions());
        children.remove(parentPosition);
        return Set.copyOf(children);
    }

    public long childPosition(int octant) {
        int level = WorldEngine.getLevel(parentPosition);
        return WorldEngine.getWorldSectionId(level - 1,
                (WorldEngine.getX(parentPosition) << 1) | (octant & 1),
                (WorldEngine.getY(parentPosition) << 1) | ((octant >>> 2) & 1),
                (WorldEngine.getZ(parentPosition) << 1) | ((octant >>> 1) & 1));
    }

    private static BuiltSection nonEmptySection(long position, byte childExistence) {
        MemoryBuffer geometry = new MemoryBuffer(8).zero();
        return new BuiltSection(position, childExistence, 0, geometry, null, null);
    }

    private GpuNode parentNode() {
        GpuNode node = gpuNodes.get(parentNodeId);
        if (node == null) {
            throw new IllegalStateException("Parent node has no generated GPU record");
        }
        return node;
    }

    private void captureNodeChanges() {
        MemoryBuffer changes = manager._generateChangeList();
        if (changes == null) {
            return;
        }
        try {
            int count = Math.toIntExact(changes.size / 20L);
            for (int index = 0; index < count; index++) {
                long address = changes.address + index * 20L;
                int nodeId = MemoryUtil.memGetInt(address);
                int high = MemoryUtil.memGetInt(address + 4);
                int low = MemoryUtil.memGetInt(address + 8);
                long position = ((long) high << 32) | Integer.toUnsignedLong(low);
                int geometryAndFlags = MemoryUtil.memGetInt(address + 12);
                int childPointerWord = MemoryUtil.memGetInt(address + 16);
                int encodedGeometry = geometryAndFlags & 0xFFFFFF;
                int geometryId = encodedGeometry == 0xFFFFFF ? -1
                        : encodedGeometry == 0xFFFFFE ? -2 : encodedGeometry;
                int encodedChildPointer = childPointerWord & 0xFFFFFF;
                int childPointer = encodedChildPointer == 0xFFFFFF ? -1 : encodedChildPointer;
                int flags = geometryAndFlags >>> 24;
                boolean requestInFlight = (flags & 1) != 0;
                int childCount = ((flags >>> 2) & 0x7) + 1;
                gpuNodes.put(nodeId,
                        new GpuNode(position, geometryId, childPointer, childCount, requestInFlight));
            }
        } finally {
            changes.free();
        }
    }

    private record GpuNode(long position,
                           int geometryId,
                           int childPointer,
                           int childCount,
                           boolean requestInFlight) { }

    private static final class Geometry implements IGeometryManager {
        private final Set<Integer> activeMeshes = new HashSet<>();
        private int nextMeshId = 1;

        @Override
        public int uploadSection(BuiltSection section) {
            int id = nextMeshId++;
            activeMeshes.add(id);
            section.free();
            return id;
        }

        @Override
        public int uploadReplaceSection(int oldId, BuiltSection section) {
            activeMeshes.remove(oldId);
            int id = uploadSection(section);
            return id;
        }

        @Override
        public void removeSection(int id) {
            activeMeshes.remove(id);
        }

        @Override
        public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
            activeMeshes.remove(id);
        }

        boolean contains(int meshId) {
            return activeMeshes.contains(meshId);
        }
    }

    private static final class Watcher implements ISectionWatcher {
        private final Map<Long, Integer> watches = new HashMap<>();

        @Override
        public boolean watch(long position, int types) {
            int previous = watches.getOrDefault(position, 0);
            int next = previous | types;
            watches.put(position, next);
            return next != previous;
        }

        @Override
        public boolean unwatch(long position, int types) {
            int next = watches.getOrDefault(position, 0) & ~types;
            if (next == 0) {
                return watches.remove(position) != null;
            }
            watches.put(position, next);
            return false;
        }

        @Override
        public int get(long position) {
            return watches.getOrDefault(position, 0);
        }

        Set<Long> positions() {
            return watches.keySet();
        }
    }
}
