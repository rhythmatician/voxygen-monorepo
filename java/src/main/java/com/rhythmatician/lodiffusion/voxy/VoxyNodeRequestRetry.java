package com.rhythmatician.lodiffusion.voxy;

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
    private static final Set<Long> REFUSED_POSITIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private VoxyNodeRequestRetry() { }

    /** Records that the expansion request for {@code position} was refused for an empty mask. */
    public static void recordRefusal(long position) {
        REFUSED_POSITIONS.add(position);
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

    public static void clearForTest() {
        REFUSED_POSITIONS.clear();
    }

    static int pendingCountForTest() {
        return REFUSED_POSITIONS.size();
    }
}
