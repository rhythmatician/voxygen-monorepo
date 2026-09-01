package com.rhythmatician.voxygen.backend.voxy;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks render-tree leaf requests that were refused because the child
 * existence snapshot was empty ("Not creating a leaf request with existence
 * mask of 0"), and re-issues them once the section later reports non-empty
 * children.
 *
 * <p>This closes the west-side void race: Voxy's {@code NodeManager}
 * permanently drops an expansion request whose existence snapshot raced
 * ahead of our topology write. The refused node stays in the active map but
 * is never re-requested, so the position renders as void forever.</p>
 */
public final class VoxyNodeRequestRetry {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(VoxyNodeRequestRetry.class);

    private static final int MAX_PENDING_SIZE = 4096;

    private static final Set<Long> REFUSED_POSITIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private VoxyNodeRequestRetry() { }

    /** Records that the expansion request for {@code position} was refused for an empty mask. */
    public static void recordRefusal(long position) {
        REFUSED_POSITIONS.add(position);
        int size = REFUSED_POSITIONS.size();
        if (size > MAX_PENDING_SIZE || (size % 1024 == 0 && size != 0)) {
            LOGGER.warn("[VoxyRetry] pending refusals={}, max={}, latestPosition={}",
                    size, MAX_PENDING_SIZE, Long.toHexString(position));
        }
    }

    /**
     * Called after a child-existence change reaches the render tree. If the
     * position was previously refused and now has non-empty children, clears
     * the refusal and reports that the request must be re-issued.
     *
     * @return true when the caller should re-issue the expansion request
     */
    public static boolean shouldRetry(long position, byte childExistence) {
        if (childExistence == 0 || REFUSED_POSITIONS.isEmpty()) {
            return false;
        }
        if (REFUSED_POSITIONS.remove(position)) {
            return true;
        }
        // Fast path miss; keep the set small by dropping empty-mask noise.
        return false;
    }

    /**
     * Production lifecycle clear: called when the owning NodeManager/session/world is torn down.
     * Disconnect, world replacement, and dimension rebind must not carry refused positions
     * into the next NodeManager/session (see #151 isolation precedent).
     */
    public static void clear() {
        int pending = REFUSED_POSITIONS.size();
        if (pending != 0) {
            LOGGER.info("[VoxyRetry] clearing {} pending refused positions on lifecycle boundary", pending);
        }
        REFUSED_POSITIONS.clear();
    }

    /** Production telemetry: number of pending refused positions. */
    public static int pendingCount() {
        return REFUSED_POSITIONS.size();
    }

    public static void clearForTest() {
        clear();
    }

    public static int pendingCountForTest() {
        return pendingCount();
    }
}
