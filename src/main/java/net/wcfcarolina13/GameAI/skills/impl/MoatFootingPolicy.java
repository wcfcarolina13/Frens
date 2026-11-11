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
     * True when the cell at (x, y, z) lies under the fren's feet: its column overlaps the fren's
     * horizontal box (min inclusive, max exclusive, in block coordinates) and it is below feetY.
     */
    static boolean isUnderFeet(double boxMinX, double boxMaxX, double boxMinZ, double boxMaxZ,
                               int feetY, int x, int y, int z) {
        if (y >= feetY) {
            return false;
        }
        boolean xOverlap = x < boxMaxX && x + 1 > boxMinX;
        boolean zOverlap = z < boxMaxZ && z + 1 > boxMinZ;
        return xOverlap && zOverlap;
    }
}
