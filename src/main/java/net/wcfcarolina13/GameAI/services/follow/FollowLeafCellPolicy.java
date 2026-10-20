package net.wcfcarolina13.GameAI.services.follow;

/** Leaf collision heights relative to the bot's current feet. */
public final class FollowLeafCellPolicy {
    private FollowLeafCellPolicy() {}

    public static int[] heights(boolean raisedSupport) {
        // A raised support's walking surface needs both feet and head cleared. Do not
        // extend into the high canopy on level ground.
        return raisedSupport ? new int[] {0, 1, 2, 3} : new int[] {0, 1, 2};
    }
}
