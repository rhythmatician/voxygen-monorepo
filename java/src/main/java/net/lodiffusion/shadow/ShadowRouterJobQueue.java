package net.lodiffusion.shadow;

import com.rhythmatician.lodiffusion.voxy.RefinementAdmissionGate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Thread-safe scheduler for explicit Voxy terrain demand. */
public final class ShadowRouterJobQueue {
    private static final int[] COARSE_FIRST = {4, 3, 2, 1, 0};
    private static final double MAX_PLAYER_SECTION_DISTANCE = 256.0;

    public enum EnqueueResult {
        QUEUED,
        UPGRADED,
        DUPLICATE,
        IN_FLIGHT,
        REJECTED;

        public boolean representsScheduledWork() {
            return this == QUEUED || this == UPGRADED || this == IN_FLIGHT;
        }
    }

    /** Lifecycle totals and current depths for one explicit demand kind. */
    public record DemandMetrics(long queued, long dequeued, long completed, long failed,
                                long skippedFull,
                                int queuedDepth, int inFlightDepth) {}

    /** Runtime-facing metrics for the three active top-down demand kinds. */
    public record DemandMetricsSnapshot(DemandMetrics horizon, DemandMetrics guard,
                                        DemandMetrics visual) {
        public String compact() {
            return "H=" + format(horizon) + " G=" + format(guard) + " V=" + format(visual);
        }

        private static String format(DemandMetrics metrics) {
            return "q" + metrics.queued() + "/d" + metrics.dequeued() + "/c"
                    + metrics.completed() + "/f" + metrics.failed() + "/full"
                    + metrics.skippedFull() + "@" + metrics.queuedDepth() + "+"
                    + metrics.inFlightDepth();
        }
    }

    private record RequestKey(int lod, int x, int y, int z, VoxyWorkKind workKind) {
        static RequestKey of(VoxyRequestDecoder.VoxyNodeRequest request) {
            return new RequestKey(request.lodLevel, request.worldX, request.worldY, request.worldZ,
                    request.workKind);
        }
    }

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    @SuppressWarnings("unchecked")
    private static final PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] horizonQueues =
            new PriorityQueue[5];
    @SuppressWarnings("unchecked")
    private static final PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] guardQueues =
            new PriorityQueue[5];
    @SuppressWarnings("unchecked")
    private static final PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] visualQueues =
            new PriorityQueue[5];

    private static volatile int playerSectionX;
    private static volatile int playerSectionZ;
    /** A horizon dispatch earns one guard transaction when a guard is waiting. */
    private static boolean guardTurn;
    /** Coverage turns served while visual work was eligible; capped at four. */
    private static int coverageDispatchesSinceVisual;
    private static final Map<RequestKey, VoxyRequestDecoder.VoxyNodeRequest> queuedRequests =
            new HashMap<>();
    private static final Map<RequestKey, VoxyDemandKind> inFlightRequests = new HashMap<>();
    private static final long[][] lifecycleCounts = new long[VoxyDemandKind.values().length][5];

    static {
        Comparator<VoxyRequestDecoder.VoxyNodeRequest> comparator =
                Comparator.comparingDouble(ShadowRouterJobQueue::estimateDistance)
                        .thenComparing(Comparator.comparingInt(
                                (VoxyRequestDecoder.VoxyNodeRequest request) -> request.lodLevel)
                                .reversed())
                        .thenComparingInt(request -> request.worldX)
                        .thenComparingInt(request -> request.worldY)
                        .thenComparingInt(request -> request.worldZ);
        for (int level = 0; level <= 4; level++) {
            horizonQueues[level] = new PriorityQueue<>(comparator);
            guardQueues[level] = new PriorityQueue<>(comparator);
            visualQueues[level] = new PriorityQueue<>(comparator);
        }
    }

    private ShadowRouterJobQueue() {}

    public static EnqueueResult enqueue(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (!isValid(request) || !RefinementAdmissionGate.allows(request.workKind)
                || !shouldAccept(request)) return EnqueueResult.REJECTED;
        lock.writeLock().lock();
        try {
            return enqueueLocked(request);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void enqueueBatch(VoxyRequestDecoder.VoxyNodeRequest[] requests) {
        if (requests == null || requests.length == 0) return;
        lock.writeLock().lock();
        try {
            for (VoxyRequestDecoder.VoxyNodeRequest request : requests) {
                if (isValid(request) && RefinementAdmissionGate.allows(request.workKind)
                        && shouldAccept(request)) enqueueLocked(request);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static VoxyRequestDecoder.VoxyNodeRequest dequeueAny() {
        lock.writeLock().lock();
        try {
            VoxyRequestDecoder.VoxyNodeRequest request = dequeueFairLocked();
            return takeLocked(request);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static VoxyRequestDecoder.VoxyNodeRequest dequeue(int lod) {
        if (lod < 0 || lod > 4) return null;
        lock.writeLock().lock();
        try {
            VoxyRequestDecoder.VoxyNodeRequest request = dequeueFairForLodLocked(lod);
            return takeLocked(request);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void markCompleted(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (request == null) return;
        lock.writeLock().lock();
        try {
            VoxyDemandKind kind = inFlightRequests.remove(RequestKey.of(request));
            if (kind != null) count(kind, 2);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Marks an in-flight request as terminally failed for demand-specific telemetry. */
    public static void markFailed(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (request == null) return;
        lock.writeLock().lock();
        try {
            VoxyDemandKind kind = inFlightRequests.remove(RequestKey.of(request));
            if (kind != null) count(kind, 3);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Full-vanilla sections are terminally skipped because Voxy owns them. */
    public static void markSkippedFull(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (request == null) return;
        lock.writeLock().lock();
        try {
            VoxyDemandKind kind = inFlightRequests.remove(RequestKey.of(request));
            if (kind != null) count(kind, 4);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void requeue(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (!isValid(request) || !RefinementAdmissionGate.allows(request.workKind)
                || !shouldAccept(request)) return;
        lock.writeLock().lock();
        try {
            inFlightRequests.remove(RequestKey.of(request));
            enqueueLocked(request);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static int size() {
        lock.readLock().lock();
        try {
            return sizeOf(horizonQueues) + sizeOf(guardQueues) + sizeOf(visualQueues);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static int sizeForLod(int lod) {
        if (lod < 0 || lod > 4) return 0;
        lock.readLock().lock();
        try {
            return horizonQueues[lod].size() + guardQueues[lod].size() + visualQueues[lod].size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void clear() {
        lock.writeLock().lock();
        try {
            clear(horizonQueues);
            clear(guardQueues);
            clear(visualQueues);
            queuedRequests.clear();
            inFlightRequests.clear();
            clearLifecycleCounts();
            guardTurn = false;
            coverageDispatchesSinceVisual = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static int inFlightSize() {
        lock.readLock().lock();
        try {
            return inFlightRequests.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Snapshot is consistent with queue state because it is captured under the queue lock. */
    public static DemandMetricsSnapshot demandMetrics() {
        lock.readLock().lock();
        try {
            return new DemandMetricsSnapshot(
                    metricsFor(VoxyDemandKind.HORIZON_COVERAGE, horizonQueues),
                    metricsFor(VoxyDemandKind.VANILLA_FRONTIER_GUARD, guardQueues),
                    metricsFor(VoxyDemandKind.VISUAL_REFINEMENT, visualQueues));
        } finally {
            lock.readLock().unlock();
        }
    }

    public static boolean hasWork() {
        lock.readLock().lock();
        try {
            return hasQueued(horizonQueues) || hasQueued(guardQueues) || hasQueued(visualQueues);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void updatePlayerSection(int sectionX, int sectionZ) {
        lock.writeLock().lock();
        try {
            if (playerSectionX == sectionX && playerSectionZ == sectionZ) return;
            playerSectionX = sectionX;
            playerSectionZ = sectionZ;
            reheapify(horizonQueues);
            reheapify(guardQueues);
            reheapify(visualQueues);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static EnqueueResult enqueueLocked(VoxyRequestDecoder.VoxyNodeRequest request) {
        RequestKey key = RequestKey.of(request);
        VoxyRequestDecoder.VoxyNodeRequest queued = queuedRequests.get(key);
        if (queued != null) {
            if (request.demandKind.priority().ordinal() < queued.demandKind.priority().ordinal()) {
                queueFor(queued)[queued.lodLevel].remove(queued);
                queued.demandKind = request.demandKind;
                queued.demandSource = request.demandSource;
                queueFor(queued)[queued.lodLevel].offer(queued);
                count(request.demandKind, 0);
                return EnqueueResult.UPGRADED;
            }
            return EnqueueResult.DUPLICATE;
        }
        if (inFlightRequests.containsKey(key)) return EnqueueResult.IN_FLIGHT;
        queueFor(request)[request.lodLevel].offer(request);
        queuedRequests.put(key, request);
        count(request.demandKind, 0);
        return EnqueueResult.QUEUED;
    }

    private static VoxyRequestDecoder.VoxyNodeRequest dequeueFairLocked() {
        boolean hasHorizon = hasQueued(horizonQueues);
        boolean hasGuard = hasQueued(guardQueues);
        boolean hasVisual = hasQueued(visualQueues);
        if (!hasHorizon && !hasGuard) {
            coverageDispatchesSinceVisual = 0;
            return hasVisual ? poll(COARSE_FIRST, visualQueues) : null;
        }
        if (!hasVisual) {
            coverageDispatchesSinceVisual = 0;
            return dequeueCoverageLocked();
        }
        if (coverageDispatchesSinceVisual == 4) {
            coverageDispatchesSinceVisual = 0;
            return poll(COARSE_FIRST, visualQueues);
        }
        VoxyRequestDecoder.VoxyNodeRequest request = dequeueCoverageLocked();
        if (request != null) coverageDispatchesSinceVisual++;
        return request;
    }

    private static VoxyRequestDecoder.VoxyNodeRequest dequeueFairForLodLocked(int lod) {
        boolean hasHorizon = !horizonQueues[lod].isEmpty();
        boolean hasGuard = !guardQueues[lod].isEmpty();
        boolean hasVisual = !visualQueues[lod].isEmpty();
        if (!hasHorizon && !hasGuard) {
            coverageDispatchesSinceVisual = 0;
            return hasVisual ? visualQueues[lod].poll() : null;
        }
        if (!hasVisual) {
            coverageDispatchesSinceVisual = 0;
            return dequeueCoverageForLodLocked(lod);
        }
        if (coverageDispatchesSinceVisual == 4) {
            coverageDispatchesSinceVisual = 0;
            return visualQueues[lod].poll();
        }
        VoxyRequestDecoder.VoxyNodeRequest request = dequeueCoverageForLodLocked(lod);
        if (request != null) coverageDispatchesSinceVisual++;
        return request;
    }

    private static VoxyRequestDecoder.VoxyNodeRequest dequeueCoverageLocked() {
        boolean hasHorizon = hasQueued(horizonQueues);
        boolean hasGuard = hasQueued(guardQueues);
        if (guardTurn && hasGuard) {
            guardTurn = false;
            return pollNearestGuardLocked();
        }
        if (hasHorizon) {
            guardTurn = hasGuard;
            return poll(COARSE_FIRST, horizonQueues);
        }
        guardTurn = false;
        return hasGuard ? pollNearestGuardLocked() : null;
    }

    private static VoxyRequestDecoder.VoxyNodeRequest dequeueCoverageForLodLocked(int lod) {
        boolean hasHorizon = !horizonQueues[lod].isEmpty();
        boolean hasGuard = !guardQueues[lod].isEmpty();
        if (guardTurn && hasGuard) {
            guardTurn = false;
            return guardQueues[lod].poll();
        }
        if (hasHorizon) {
            guardTurn = hasGuard;
            return horizonQueues[lod].poll();
        }
        guardTurn = false;
        return hasGuard ? guardQueues[lod].poll() : null;
    }

    /** Guard urgency is spatial: compare every LOD's nearest pending footprint. */
    private static VoxyRequestDecoder.VoxyNodeRequest pollNearestGuardLocked() {
        VoxyRequestDecoder.VoxyNodeRequest nearest = null;
        int nearestLod = -1;
        for (int lod = 0; lod <= 4; lod++) {
            VoxyRequestDecoder.VoxyNodeRequest candidate = guardQueues[lod].peek();
            if (candidate != null && (nearest == null || compareGuards(candidate, nearest) < 0)) {
                nearest = candidate;
                nearestLod = lod;
            }
        }
        return nearest == null ? null : guardQueues[nearestLod].poll();
    }

    private static int compareGuards(VoxyRequestDecoder.VoxyNodeRequest left,
            VoxyRequestDecoder.VoxyNodeRequest right) {
        int distance = Double.compare(estimateRawPlayerSectionDistance(left),
                estimateRawPlayerSectionDistance(right));
        if (distance != 0) return distance;
        int lod = Integer.compare(right.lodLevel, left.lodLevel);
        if (lod != 0) return lod;
        int x = Integer.compare(left.worldX, right.worldX);
        if (x != 0) return x;
        int y = Integer.compare(left.worldY, right.worldY);
        return y != 0 ? y : Integer.compare(left.worldZ, right.worldZ);
    }

    private static VoxyRequestDecoder.VoxyNodeRequest takeLocked(
            VoxyRequestDecoder.VoxyNodeRequest request) {
        if (request == null) return null;
        RequestKey key = RequestKey.of(request);
        queuedRequests.remove(key);
        inFlightRequests.put(key, request.demandKind);
        count(request.demandKind, 1);
        return request;
    }

    private static PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] queueFor(
            VoxyRequestDecoder.VoxyNodeRequest request) {
        return switch (request.demandKind) {
            case HORIZON_COVERAGE -> horizonQueues;
            case VANILLA_FRONTIER_GUARD -> guardQueues;
            case VISUAL_REFINEMENT -> visualQueues;
        };
    }

    private static VoxyRequestDecoder.VoxyNodeRequest poll(int[] order,
            PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] queues) {
        for (int lod : order) {
            VoxyRequestDecoder.VoxyNodeRequest request = queues[lod].poll();
            if (request != null) return request;
        }
        return null;
    }

    private static boolean isValid(VoxyRequestDecoder.VoxyNodeRequest request) {
        return request != null && request.workKind != null && request.demandKind != null && request.demandSource != null
                && request.lodLevel >= 0 && request.lodLevel <= 4;
    }

    private static boolean shouldAccept(VoxyRequestDecoder.VoxyNodeRequest request) {
        return estimateRawPlayerSectionDistance(request) <= MAX_PLAYER_SECTION_DISTANCE;
    }

    private static boolean hasQueued(PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] queues) {
        for (Queue<?> queue : queues) if (!queue.isEmpty()) return true;
        return false;
    }

    private static DemandMetrics metricsFor(VoxyDemandKind kind,
            PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] queues) {
        long[] counts = lifecycleCounts[kind.ordinal()];
        int inFlight = 0;
        for (VoxyDemandKind inFlightKind : inFlightRequests.values()) {
            if (inFlightKind == kind) inFlight++;
        }
        return new DemandMetrics(counts[0], counts[1], counts[2], counts[3], counts[4],
                sizeOf(queues), inFlight);
    }

    private static void count(VoxyDemandKind kind, int lifecycleIndex) {
        lifecycleCounts[kind.ordinal()][lifecycleIndex]++;
    }

    private static void clearLifecycleCounts() {
        for (long[] counts : lifecycleCounts) {
            java.util.Arrays.fill(counts, 0L);
        }
    }

    private static int sizeOf(PriorityQueue<?>[] queues) {
        int size = 0;
        for (Queue<?> queue : queues) size += queue.size();
        return size;
    }

    private static void clear(PriorityQueue<?>[] queues) {
        for (Queue<?> queue : queues) queue.clear();
    }

    private static void reheapify(PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] queues) {
        for (PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest> queue : queues) {
            if (queue.size() < 2) continue;
            ArrayList<VoxyRequestDecoder.VoxyNodeRequest> requests = new ArrayList<>(queue);
            queue.clear();
            queue.addAll(requests);
        }
    }

    private static double estimateDistance(VoxyRequestDecoder.VoxyNodeRequest request) {
        return estimateRawPlayerSectionDistance(request) + ((4 - request.lodLevel) * 2.0);
    }

    private static double estimateRawPlayerSectionDistance(VoxyRequestDecoder.VoxyNodeRequest request) {
        int sectionScale = 1 << (request.lodLevel + 1);
        double minX = request.worldX * (double) sectionScale;
        double maxX = minX + sectionScale;
        double minZ = request.worldZ * (double) sectionScale;
        double maxZ = minZ + sectionScale;
        double dx = distanceToInterval(playerSectionX, minX, maxX);
        double dz = distanceToInterval(playerSectionZ, minZ, maxZ);
        return Math.sqrt((dx * dx) + (dz * dz));
    }

    private static double distanceToInterval(double point, double minimum, double maximum) {
        if (point < minimum) return minimum - point;
        return point > maximum ? point - maximum : 0.0;
    }
}
