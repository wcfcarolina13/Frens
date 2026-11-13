package net.wcfcarolina13.GameAI.skills.impl;

/**
 * When a kill leaves too few of a kind (depopulation protection), decides whether the hunt may look
 * for another kind instead of ending. Pure so it can be unit tested without Minecraft types.
 */
final class HuntDepopPolicy {

    private HuntDepopPolicy() {
    }

    /**
     * A hunt for one named mob ends when that mob runs low, and so does a hunt that already reached
     * its kill target. Any other hunt (ticked animals, or anything edible) may move on.
     */
    static boolean mayLookForAnotherKind(boolean namedTarget, int kills, int targetCount) {
        return !namedTarget && kills < targetCount;
    }
}
