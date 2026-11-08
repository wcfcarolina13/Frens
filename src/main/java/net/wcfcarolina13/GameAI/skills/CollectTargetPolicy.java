package net.wcfcarolina13.GameAI.skills;

/**
 * Whether a collect job already holds the amount it was asked for, using the same arithmetic as
 * CollectDirtSkill's loop: "until" mode counts the whole inventory, otherwise only the gain since the
 * job started. Pure so it can be tested without Minecraft types.
 */
public final class CollectTargetPolicy {
    private CollectTargetPolicy() {
    }

    public static boolean reached(int inventoryCount, int baselineCount, boolean untilMode, int targetCount) {
        int collected = untilMode ? inventoryCount : Math.max(0, inventoryCount - baselineCount);
        return collected >= targetCount;
    }
}
