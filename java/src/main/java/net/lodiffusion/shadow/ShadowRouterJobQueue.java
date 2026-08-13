package net.lodiffusion.shadow;

import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe job queue for LOD terrain generation requests from Voxy.
 * 
 * Maintains separate per-LOD priority queues (LOD 0–4) ordered by distance to player.
 * Supports enqueue (from mixin callback) and dequeue (from dispatcher).
 */
public class ShadowRouterJobQueue {

    private static final int[] PARTIAL_FILL_PRIORITY_ORDER = {0, 1, 2, 3, 4};
    private static final int[] REGULAR_PRIORITY_ORDER = {4, 3, 2, 1, 0};

    // Hard cap to prevent far-field request floods from starving nearby refinement.
    // Units are player-section coordinates (16-block sections).
    private static final double MAX_PLAYER_SECTION_DISTANCE = 256.0;

    private record RequestKey(int lod, int x, int y, int z) {
        static RequestKey of(VoxyRequestDecoder.VoxyNodeRequest req) {
            return new RequestKey(req.lodLevel, req.worldX, req.worldY, req.worldZ);
        }
    }
    
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    @SuppressWarnings("unchecked")
    private static final PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] lodQueues = 
        new PriorityQueue[5];

    // Separate per-LOD queues for partial-fill requests (highest priority).
    @SuppressWarnings("unchecked")
    private static final PriorityQueue<VoxyRequestDecoder.VoxyNodeRequest>[] partialFillQueues =
        new PriorityQueue[5];

    // Player position in section-space units (updated by LodGenerationService).
    private static volatile int playerSectionX = 0;
    private static volatile int playerSectionZ = 0;

    // Requests currently queued but not yet handed out.
    private static final Set<RequestKey> queuedKeys = new HashSet<>();
    // Requests handed out by dequeue* and awaiting completion callback.
    private static final Set<RequestKey> inFlightKeys = new HashSet<>();
    
    static {
        for (int i = 0; i < 5; i++) {
            // Order by ascending distance: closest requests first
            ShadowRouterJobQueue.lodQueues[i] = new PriorityQueue<>(
                Comparator.comparingDouble(ShadowRouterJobQueue::estimateDistance)
            );
            ShadowRouterJobQueue.partialFillQueues[i] = new PriorityQueue<>(
                Comparator.comparingDouble(ShadowRouterJobQueue::estimateDistance)
            );
        }
    }
    
    /**
     * Enqueue a single request (called by VoxyShadowBridgeMixin).
     * 
     * @param request Decoded Voxy node request
     */
    public static void enqueue(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (request == null || request.lodLevel < 0 || request.lodLevel > 4) {
            return;  // Ignore invalid requests
        }
        if (!request.isPartialFill && !shouldAccept(request)) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            RequestKey key = RequestKey.of(request);
            if (queuedKeys.contains(key) || inFlightKeys.contains(key)) {
                return;
            }
            if (request.isPartialFill) {
                partialFillQueues[request.lodLevel].offer(request);
            } else {
                lodQueues[request.lodLevel].offer(request);
            }
            queuedKeys.add(key);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Enqueue multiple requests (batch optimization).
     * 
     * @param requests Array of requests to enqueue
     */
    public static void enqueueBatch(VoxyRequestDecoder.VoxyNodeRequest[] requests) {
        if (requests == null || requests.length == 0) {
            return;
        }
        
        lock.writeLock().lock();
        try {
            for (VoxyRequestDecoder.VoxyNodeRequest req : requests) {
                if (req != null && req.lodLevel >= 0 && req.lodLevel <= 4) {
                    if (!shouldAccept(req)) {
                        continue;
                    }
                    RequestKey key = RequestKey.of(req);
                    if (queuedKeys.contains(key) || inFlightKeys.contains(key)) {
                        continue;
                    }
                    lodQueues[req.lodLevel].offer(req);
                    queuedKeys.add(key);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Dequeue the highest-priority request across all LOD levels.
     * Priority: partial-fill first (L0 → L4), then regular generation (L4 → L0).
     * 
     * @return Next request to generate, or null if queue is empty
     */
    public static VoxyRequestDecoder.VoxyNodeRequest dequeueAny() {
        lock.writeLock().lock();
        try {
            // Priority 1: partial-fill from finest to coarsest.
            for (int lod : PARTIAL_FILL_PRIORITY_ORDER) {
                VoxyRequestDecoder.VoxyNodeRequest req = partialFillQueues[lod].poll();
                if (req != null) {
                    RequestKey key = RequestKey.of(req);
                    queuedKeys.remove(key);
                    inFlightKeys.add(key);
                    return req;
                }
            }
            // Priority 2: regular generation from coarsest to finest.
            for (int lod : REGULAR_PRIORITY_ORDER) {
                VoxyRequestDecoder.VoxyNodeRequest req = lodQueues[lod].poll();
                if (req != null) {
                    RequestKey key = RequestKey.of(req);
                    queuedKeys.remove(key);
                    inFlightKeys.add(key);
                    return req;
                }
            }
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Dequeue from a specific LOD level.
     * 
     * @param lod LOD level [0, 4]
     * @return Next request at that LOD, or null
     */
    public static VoxyRequestDecoder.VoxyNodeRequest dequeue(int lod) {
        if (lod < 0 || lod > 4) {
            return null;
        }
        
        lock.writeLock().lock();
        try {
            VoxyRequestDecoder.VoxyNodeRequest req = lodQueues[lod].poll();
            if (req != null) {
                RequestKey key = RequestKey.of(req);
                queuedKeys.remove(key);
                inFlightKeys.add(key);
            }
            return req;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark a request as finished so it can be enqueued again in the future.
     */
    public static void markCompleted(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (request == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            inFlightKeys.remove(RequestKey.of(request));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark a request as not yet done and re-queue it if it is still unique.
     */
    public static void requeue(VoxyRequestDecoder.VoxyNodeRequest request) {
        if (request == null || request.lodLevel < 0 || request.lodLevel > 4) {
            return;
        }
        if (!request.isPartialFill && !shouldAccept(request)) {
            return;
        }

        lock.writeLock().lock();
        try {
            RequestKey key = RequestKey.of(request);
            inFlightKeys.remove(key);
            if (queuedKeys.contains(key)) {
                return;
            }
            if (request.isPartialFill) {
                partialFillQueues[request.lodLevel].offer(request);
            } else {
                lodQueues[request.lodLevel].offer(request);
            }
            queuedKeys.add(key);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get total queue size across all LODs.
     */
    public static int size() {
        lock.readLock().lock();
        try {
            int total = 0;
            for (Queue<?> queue : lodQueues) {
                total += queue.size();
            }
            for (Queue<?> queue : partialFillQueues) {
                total += queue.size();
            }
            return total;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get queue size for a specific LOD.
     */
    public static int sizeForLod(int lod) {
        if (lod < 0 || lod > 4) {
            return 0;
        }
        lock.readLock().lock();
        try {
            return lodQueues[lod].size() + partialFillQueues[lod].size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Clear all queues.
     */
    public static void clear() {
        lock.writeLock().lock();
        try {
            for (Queue<?> queue : lodQueues) {
                queue.clear();
            }
            for (Queue<?> queue : partialFillQueues) {
                queue.clear();
            }
            queuedKeys.clear();
            inFlightKeys.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Number of requests currently being processed by a consumer. */
    public static int inFlightSize() {
        lock.readLock().lock();
        try {
            return inFlightKeys.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if any queue has pending requests.
     */
    public static boolean hasWork() {
        lock.readLock().lock();
        try {
            for (Queue<?> queue : partialFillQueues) {
                if (!queue.isEmpty()) {
                    return true;
                }
            }
            for (Queue<?> queue : lodQueues) {
                if (!queue.isEmpty()) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if any partial-fill queue has pending requests.
     */
    public static boolean hasPartialFillWork() {
        lock.readLock().lock();
        try {
            for (Queue<?> queue : partialFillQueues) {
                if (!queue.isEmpty()) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Update player position in section-space units for distance estimation.
     */
    public static void updatePlayerSection(int sectionX, int sectionZ) {
        playerSectionX = sectionX;
        playerSectionZ = sectionZ;
    }

    /**
     * Estimate distance from request to player (simplified).
     * 
     * Uses player-relative section-space distance and a mild LOD penalty so
     * nearby requests are preferred while still allowing coarser levels to drain.
     */
    private static double estimateDistance(VoxyRequestDecoder.VoxyNodeRequest req) {
        if (req == null) {
            return Double.MAX_VALUE;
        }

        // Convert world-section coords at level L into player-section-space center.
        // worldSection(block, L) == playerSection >> (L+1), so inverse is << (L+1).
        int sectionScale = 1 << (req.lodLevel + 1);
        double reqCenterX = (req.worldX * (double) sectionScale) + (sectionScale * 0.5);
        double reqCenterZ = (req.worldZ * (double) sectionScale) + (sectionScale * 0.5);

        double dx = reqCenterX - playerSectionX;
        double dz = reqCenterZ - playerSectionZ;
        double distance = Math.sqrt((dx * dx) + (dz * dz));

        // Prefer coarser levels slightly for the same location so dependency chains
        // (L4->L3->L2->L1->L0) don't thrash on deferred child requests.
        double lodBias = (4 - req.lodLevel) * 2.0;
        return distance + lodBias;
    }

    private static boolean shouldAccept(VoxyRequestDecoder.VoxyNodeRequest req) {
        return estimateRawPlayerSectionDistance(req) <= MAX_PLAYER_SECTION_DISTANCE;
    }

    private static double estimateRawPlayerSectionDistance(VoxyRequestDecoder.VoxyNodeRequest req) {
        if (req == null) {
            return Double.MAX_VALUE;
        }
        int sectionScale = 1 << (req.lodLevel + 1);
        double reqCenterX = (req.worldX * (double) sectionScale) + (sectionScale * 0.5);
        double reqCenterZ = (req.worldZ * (double) sectionScale) + (sectionScale * 0.5);
        double dx = reqCenterX - playerSectionX;
        double dz = reqCenterZ - playerSectionZ;
        return Math.sqrt((dx * dx) + (dz * dz));
    }
}
