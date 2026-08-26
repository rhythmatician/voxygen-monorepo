package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;

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

    public HeadlessNodeManagerProbe(long parentPosition) {
        this.parentPosition = parentPosition;
        manager.insertTopLevelNode(parentPosition);
    }

    public void completeCoarseLeaf(byte childExistence) {
        manager.processChildChange(parentPosition, childExistence);
        manager.processGeometryResult(nonEmptySection(parentPosition, childExistence));
    }

    public void requestRefinement() {
        manager.processRequest(parentPosition);
    }

    public void publishChildExistence(byte childExistence) {
        manager.processChildChange(parentPosition, childExistence);
    }

    public boolean coarseGeometryRetained() {
        return geometry.activeMeshCount() == 1;
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

        int activeMeshCount() {
            return activeMeshes.size();
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
