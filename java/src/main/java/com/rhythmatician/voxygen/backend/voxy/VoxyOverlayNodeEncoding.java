package com.rhythmatician.voxygen.backend.voxy;

/** CPU characterization of Voxy's shader-visible packed node position. */
public final class VoxyOverlayNodeEncoding {
    private VoxyOverlayNodeEncoding() {}

    public static int levelFromFirstNodeWord(int firstNodeWord) {
        return firstNodeWord >>> 28;
    }
}
