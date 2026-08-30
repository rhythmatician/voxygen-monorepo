package com.rhythmatician.voxygen.semantic;

/**
 * Section-grid coordinate where one unit = 16 Minecraft blocks.
 *
 * <p>L0 position: sectionX = blockX >> 4. Canonical position for all
 * generation and writing. SectionPos is a grid coordinate, not a
 * world-block coordinate.
 */
public record SectionPos(int x, int y, int z) {
}
