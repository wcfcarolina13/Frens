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

    /**
     * Items picked up over a sweep: the larger of what the sweep counted itself and how far the
     * pile in range shrank. The sweep does not see drops taken by plain pickup (already in reach,
     * walked over, nearby-drop collection), so its own count alone undercounts.
     *
     * @param initialInRange items lying in range when the sweep started
     * @param remaining      items still lying in range at the end
     * @param counted        items the sweep counted as picked up
     */
    public static int pickedUpCount(int initialInRange, int remaining, int counted) {
        return Math.max(Math.max(0, counted), Math.max(0, initialInRange) - Math.max(0, remaining));
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
