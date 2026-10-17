package net.wcfcarolina13.GameAI.services;

/**
 * Which slow steps {@code RideSyncService.forceDismountAndSecure} runs on the skill worker before
 * its one server-thread hop, and what it says when that hop does not finish. No Minecraft types.
 */
public final class ForceDismountPlan {

    private ForceDismountPlan() {
    }

    /** Look for a fence in reach (a short read hop) only when the mount can be tied and a lead is ready. */
    public static boolean needsFenceLookup(boolean leashable, boolean hasLead) {
        return leashable && hasLead;
    }

    /**
     * Fetch or craft a fence on the worker (it may walk and sleep) only when the mount can be tied,
     * a lead is ready and no fence is in reach. The hop then places it from the inventory.
     */
    public static boolean needsFenceProvision(boolean leashable, boolean hasLead, boolean fenceInReach) {
        return leashable && hasLead && !fenceInReach;
    }

    /**
     * Chat reply when the server-thread hop timed out or failed. If the bot is no longer mounted
     * the hop got as far as the dismount, so only securing the animal is in doubt.
     */
    public static String fallbackMessage(String botName, boolean stillMounted) {
        String name = botName == null || botName.isBlank() ? "The bot" : botName;
        return stillMounted
                ? name + " couldn't dismount. Try again."
                : name + " dismounted but couldn't secure the animal. Try /bot leash again.";
    }
}
