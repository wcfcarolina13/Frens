package net.wcfcarolina13.GameAI.skills.impl;

import java.util.function.IntPredicate;

/**
 * The ground level under a column, not counting the fortification itself. The heightmap counts
 * the fren's own tower post or wall over its head as the surface, so a fren standing on the ground
 * inside a tower looked 2 or 3 blocks "below surface" and kept trying to pillar out through its
 * own tower. Pure so it can be unit tested without Minecraft types.
 */
final class NaturalSurfacePolicy {

    /** Fortification blocks are at most 6 high, so a few more cells cover any gap under them. */
    static final int MAX_SKIP = 12;

    private NaturalSurfacePolicy() {
    }

    /**
     * Walks down from the heightmap's top solid cell while {@code skip} says the cell is a
     * fortification block, or an open cell under one. Returns the first other cell, or
     * {@code heightmapTopY} itself when nothing is skipped or the walk runs past {@code maxSkip}.
     */
    static int surfaceY(int heightmapTopY, IntPredicate skip, int maxSkip) {
        int y = heightmapTopY;
        for (int steps = 0; steps < maxSkip; steps++) {
            if (!skip.test(y)) {
                return y;
            }
            y--;
        }
        return heightmapTopY;
    }
}
