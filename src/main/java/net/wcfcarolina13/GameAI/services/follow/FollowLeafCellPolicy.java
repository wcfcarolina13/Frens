package net.wcfcarolina13.GameAI.services.follow;

/** Leaf collision heights relative to the bot's current feet. */
public final class FollowLeafCellPolicy {
    private FollowLeafCellPolicy() {}

    public static int[] heights(boolean raisedSupport) {
        // A one-block step puts feet one cell above the current feet and head two above.
        return raisedSupport ? new int[] {1, 2} : new int[] {0, 1, 2};
    }
}
