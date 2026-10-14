package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Player-facing drop sweep results: one plain sentence each. The detailed numbers (radius,
 * passes) belong in the log line, not in chat. Pure so it can be unit tested.
 */
public final class DropSweepMessages {

    private DropSweepMessages() {
    }

    /**
     * @param pickedUp   items actually picked up (stack counts, not entities)
     * @param outOfReach items still lying in range when the sweep stopped
     */
    public static String summary(int pickedUp, int outOfReach) {
        int picked = Math.max(0, pickedUp);
        int left = Math.max(0, outOfReach);
        if (picked == 0 && left == 0) {
            return "Nothing to pick up nearby.";
        }
        if (picked == 0) {
            return "Couldn't reach " + (left == 1 ? "the 1 item" : "the " + left + " items") + " nearby.";
        }
        if (left == 0) {
            return "Picked up " + items(picked) + ".";
        }
        return "Picked up " + items(picked) + ", " + left + " out of reach.";
    }

    /** The bot stopped because its inventory is full and nothing could be stored or dropped. */
    public static String inventoryFull(int pickedUp) {
        int picked = Math.max(0, pickedUp);
        if (picked == 0) {
            return "My inventory is full, so I stopped picking things up.";
        }
        return "My inventory is full, so I stopped after picking up " + items(picked) + ".";
    }

    static String items(int n) {
        return n == 1 ? "1 item" : n + " items";
    }
}
