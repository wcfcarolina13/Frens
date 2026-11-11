package net.wcfcarolina13.GameAI.skills.impl;

/**
 * When the wall build skips a side because something else stands where the wall goes. Pure so it
 * can be tested without Minecraft types.
 */
final class FortifyOverlapPolicy {

    /** Skip a side when more than this share of its wall cells is taken by something foreign. */
    static final double MAX_FOREIGN_SHARE = 0.4;

    private FortifyOverlapPolicy() {}

    /**
     * @param open      the cell is air or replaceable (grass, snow)
     * @param satisfied the cell already holds the planned block or an accepted substitute
     */
    static boolean countsAsOverlap(boolean open, boolean satisfied) {
        return !open && !satisfied;
    }

    static boolean shouldSkipEdge(int wallCells, int foreignCells) {
        return wallCells > 0 && foreignCells > wallCells * MAX_FOREIGN_SHARE;
    }
}
