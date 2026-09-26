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

    /**
     * An already-open door the bot is about to walk through: close it behind the bot (base
     * security) unless redstone holds it open or controls it. Ownership does not matter here.
     */
    public boolean shouldScheduleCloseForAlreadyOpen() {
        return !locked && open && opensByHand && !powered && !controlled();
    }

    /** Never: the already-open path must not arm the reopen throttle (it blocked reopening a plate-shut gate). */
    public boolean shouldSetAlreadyOpenCooldown() {
        return false;
    }

    public boolean shouldMarkRecentlyClosed() {
        return !locked && openedByBot && !open && !controlled();
    }
}
