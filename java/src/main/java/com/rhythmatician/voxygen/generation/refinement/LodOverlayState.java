package com.rhythmatician.voxygen.generation.refinement;

import java.util.List;

/** Shared state and palette for the actual-Voxy LOD diagnostic overlay. */
public final class LodOverlayState {
    public record PaletteEntry(int lod, String name, int rgb) {
        public int argb() {
            return 0xFF000000 | rgb;
        }
    }

    private static final List<PaletteEntry> PALETTE = List.of(
            new PaletteEntry(0, "red", 0xFF3030),
            new PaletteEntry(1, "orange", 0xFF9D2E),
            new PaletteEntry(2, "yellow", 0xF2E84B),
            new PaletteEntry(3, "cyan", 0x35D6E8),
            new PaletteEntry(4, "violet", 0xB56BFF));

    private static volatile boolean enabled = true;

    private LodOverlayState() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static List<PaletteEntry> palette() {
        return PALETTE;
    }

    public static String legend() {
        return "L0 red | L1 orange | L2 yellow | L3 cyan | L4 violet | vanilla uncolored";
    }
}
