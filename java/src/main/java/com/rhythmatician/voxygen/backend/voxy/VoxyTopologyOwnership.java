package com.rhythmatician.voxygen.backend.voxy;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.rhythmatician.voxygen.semantic.Level;

/**
 * Owns the renderer topology of generated fallback sections until a complete
 * parent-to-children handoff is published.
 *
 * <p>Ownership is intentionally object-identity based. A Voxy {@code WorldSection}
 * belongs to exactly one world tracker, so its identity cannot collide across
 * dimensions. Weak keys avoid retaining unloaded Voxy sections if a handoff is
 * abandoned during world teardown.</p>
 */
public final class VoxyTopologyOwnership {
    private static final ReentrantReadWriteLock PROMOTION_GATE = new ReentrantReadWriteLock(true);
    private static final ReferenceQueue<Object> COLLECTED_SECTIONS = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, Boolean> OWNED_FALLBACKS = new HashMap<>();

    private VoxyTopologyOwnership() {}

    /**
     * Register a generated nonterminal fallback. L0 is terminal and is never
     * topology-owned.
     *
     * @return true when this call acquired ownership
     */
    public static boolean registerGeneratedFallback(Object worldSection, int level) {
        Objects.requireNonNull(worldSection, "worldSection");
        if (level <= Level.L0.value() || level > Level.L4.value()) {
            return false;
        }
        PROMOTION_GATE.writeLock().lock();
        try {
            drainCollectedSections();
            return OWNED_FALLBACKS.putIfAbsent(
                    new IdentityWeakReference(worldSection, COLLECTED_SECTIONS), Boolean.TRUE) == null;
        } finally {
            PROMOTION_GATE.writeLock().unlock();
        }
    }

    /** Returns whether native child propagation must leave this parent unchanged. */
    public static boolean shouldSuppressNativePromotion(Object parentWorldSection) {
        return isOwned(parentWorldSection);
    }

    /**
     * Enter Voxy's native child-state mutation. An unowned mutation holds the
     * read side until {@link #finishNativePromotion()} so a concurrent fallback
     * claim cannot normalize over an in-flight native CAS.
     *
     * @return true when the caller must suppress the mutation
     */
    public static boolean beginNativePromotion(Object parentWorldSection) {
        PROMOTION_GATE.readLock().lock();
        if (parentWorldSection != null
                && OWNED_FALLBACKS.containsKey(new IdentityWeakReference(parentWorldSection))) {
            PROMOTION_GATE.readLock().unlock();
            return true;
        }
        return false;
    }

    /** Finish an unowned native child-state mutation begun by {@link #beginNativePromotion}. */
    public static void finishNativePromotion() {
        if (PROMOTION_GATE.getReadHoldCount() != 0) {
            PROMOTION_GATE.readLock().unlock();
        }
    }

    /** Returns whether this exact Voxy section instance is an owned fallback. */
    public static boolean isOwned(Object worldSection) {
        if (worldSection == null) {
            return false;
        }
        PROMOTION_GATE.readLock().lock();
        try {
            return OWNED_FALLBACKS.containsKey(new IdentityWeakReference(worldSection));
        } finally {
            PROMOTION_GATE.readLock().unlock();
        }
    }

    /**
     * End ownership after the parent has received its complete child mask.
     *
     * @return true exactly once for each registered parent
     */
    public static boolean releaseAfterHandoff(Object parentWorldSection) {
        if (parentWorldSection == null) {
            return false;
        }
        PROMOTION_GATE.writeLock().lock();
        try {
            drainCollectedSections();
            return OWNED_FALLBACKS.remove(new IdentityWeakReference(parentWorldSection)) != null;
        } finally {
            PROMOTION_GATE.writeLock().unlock();
        }
    }

    public static void clearForTest() {
        PROMOTION_GATE.writeLock().lock();
        try {
            OWNED_FALLBACKS.clear();
            while (COLLECTED_SECTIONS.poll() != null) {
                // Drain references enqueued by a preceding test.
            }
        } finally {
            PROMOTION_GATE.writeLock().unlock();
        }
    }

    private static void drainCollectedSections() {
        IdentityWeakReference collected;
        while ((collected = (IdentityWeakReference) COLLECTED_SECTIONS.poll()) != null) {
            OWNED_FALLBACKS.remove(collected);
        }
    }

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        private IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.identityHash = System.identityHashCode(referent);
        }

        private IdentityWeakReference(Object referent) {
            super(referent);
            this.identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof IdentityWeakReference reference)) {
                return false;
            }
            Object section = get();
            return section != null && section == reference.get();
        }
    }
}
