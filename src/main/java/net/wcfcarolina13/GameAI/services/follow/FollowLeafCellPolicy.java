package net.wcfcarolina13.GameAI.services.follow;

/** Leaf collision heights relative to the bot's current feet. */
public final class FollowLeafCellPolicy {
    private FollowLeafCellPolicy() {}

    private static final int[] NONE = new int[0];

    public static int[] heights(boolean raisedSupport) {
        // A one-block step puts feet one cell above the current feet and head two above.
        return raisedSupport ? new int[] {1, 2} : new int[] {0, 1, 2};
    }

    /**
     * Heights in the bot's own column that block the move. A step up needs a jump, so a leaf
     * at y+2 (just over the bot's head) stops it even when the front column is clear. On level
     * ground the bot walks under that cell, so nothing in its own column is checked.
     */
    public static int[] ownColumnHeights(boolean raisedSupport) {
        return raisedSupport ? new int[] {2} : NONE.clone();
    }
}
