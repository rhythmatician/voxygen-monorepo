package com.rhythmatician.lodiffusion.voxy;

/**
 * Holds the occlusion-debug state that our VoxyClientMixin reads.
 *
 * <p>Mixin rules forbid non-private static methods in a mixin class, so
 * we keep the mutable state here and let both the mixin and the slash
 * command reference this class directly.
 */
public final class VoxyDebugState {
    private VoxyDebugState() { }

    /** 0 = off, non-zero = on (matches VoxyClient.getOcclusionDebugState semantics). */
    public static volatile int occlusionDebugState = 0;
}
