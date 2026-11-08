package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Whether scaffold descent may set a bot straight onto its column. That is meant for the small
 * drift of a sweep. A bot that walked away meanwhile, for instance to a crafting table, would be
 * teleported back, and if the column's top was no longer clear it landed inside a block.
 */
final class ScaffoldRecenterPolicy {

    static final double MAX_FORCE_DISTANCE_SQ = 2.0 * 2.0;

    private ScaffoldRecenterPolicy() {
    }

    static boolean mayForcePosition(double distanceSq, boolean feetClear, boolean headClear) {
        return distanceSq <= MAX_FORCE_DISTANCE_SQ && feetClear && headClear;
    }
}
