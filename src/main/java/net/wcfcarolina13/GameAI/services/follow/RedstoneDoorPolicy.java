package net.wcfcarolina13.GameAI.services.follow;

/** Decisions for hand-operated doors and gates near redstone controls. */
public record RedstoneDoorPolicy(boolean locked, boolean open, boolean powered,
                                 boolean opensByHand, boolean openedByBot,
                                 boolean plateAdjacent, boolean triggerAdjacent) {
    private boolean controlled() {
        return plateAdjacent || triggerAdjacent;
    }

    public boolean shouldScheduleOwnClose() {
        return !locked && openedByBot && open && !powered && !controlled();
    }

    public boolean mayCloseNow() {
        return !locked && open && !powered && !controlled();
    }

    public boolean mayHandOpen() {
        return !locked && !open && opensByHand;
    }

    public boolean shouldSetAlreadyOpenCooldown() {
        return false;
    }

    public boolean shouldMarkRecentlyClosed() {
        return !locked && openedByBot && !open && !controlled();
    }
}
