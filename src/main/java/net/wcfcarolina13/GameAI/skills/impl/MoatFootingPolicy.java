package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Moat digging must not dig out the ground the fren is standing on. Doing that dropped it into the
 * ditch, and the escape pillar it then built put blocks back into cells it had already dug.
 * Pure so it can be unit tested without Minecraft types.
 */
final class MoatFootingPolicy {

    private MoatFootingPolicy() {
    }

    /**
     * True when the cell at (x, y, z) holds the fren up. A cell below its feet in its own column
     * always counts. A cell in a neighbouring column its box overlaps counts only when the cell
     * under its own column is open, because then that neighbour is what it stands on.
     * Box bounds are block coordinates, min inclusive and max exclusive.
     */
    static boolean isUnderFeet(int feetX, int feetZ, int feetY, boolean centreSupported,
                               double boxMinX, double boxMaxX, double boxMinZ, double boxMaxZ,
                               int x, int y, int z) {
        if (y >= feetY) {
            return false;
        }
        if (x == feetX && z == feetZ) {
            return true;
        }
        if (centreSupported) {
            return false;
        }
        boolean xOverlap = x < boxMaxX && x + 1 > boxMinX;
        boolean zOverlap = z < boxMaxZ && z + 1 > boxMinZ;
        return xOverlap && zOverlap;
    }
}
