package net.wcfcarolina13.GameAI.services;

/**
 * When a bot's saved leash target ({@code RideSyncService.LEASH_TARGET}, the mount it holds on a
 * lead) should be forgotten. The target is persisted so a held mount is restored on rejoin, so it
 * is only cleared when the bot has really let go of the lead. No Minecraft types.
 */
public final class LeashTargetPolicy {

    /** What just happened to the bot and its leads. */
    public enum Event {
        /** The bot tied the animals it was leading to a fence. */
        TIED_TO_FENCE,
        /** The bot dismounted and kept its mount on a lead (forceDismountAndSecure). */
        HELD_BY_BOT,
        /** The bot died, which drops every lead it held. */
        DIED,
        /** The bot left the server. The target is what restores the mount on rejoin. */
        DISCONNECT,
        /** The owner ran {@code /bot leash release}: the bot let go of every lead it held. */
        RELEASED
    }

    private LeashTargetPolicy() {
    }

    /**
     * @param event                 what happened
     * @param targetStillHeldByBot  the saved leash target is loaded and still on this bot's lead.
     *                              Only read for {@link Event#TIED_TO_FENCE}: the fence tie can leave
     *                              some leads in hand, and a mount still held must stay tracked
     */
    public static boolean shouldClear(Event event, boolean targetStillHeldByBot) {
        if (event == null) {
            return false;
        }
        return switch (event) {
            case TIED_TO_FENCE -> !targetStillHeldByBot;
            case DIED, RELEASED -> true;
            case HELD_BY_BOT, DISCONNECT -> false;
        };
    }

    /**
     * Whether the saved mount record ({@code MountPersistenceService}) should drop its
     * {@code heldByBot} flag, so a rejoin does not put the animal back on the bot's lead.
     *
     * @param event              what happened (only TIED_TO_FENCE, DIED and RELEASED ever rewrite)
     * @param recordHeldByBot    the saved record says the bot holds the mount on a lead
     * @param recordMountMatches the saved record's mount is the leash target that was let go.
     *                           Ignored for {@link Event#DIED}: death drops every lead
     */
    public static boolean shouldForgetHeldRecord(Event event, boolean recordHeldByBot, boolean recordMountMatches) {
        if (event == null || !recordHeldByBot) {
            return false;
        }
        return switch (event) {
            case TIED_TO_FENCE, RELEASED -> recordMountMatches;
            case DIED -> true;
            case HELD_BY_BOT, DISCONNECT -> false;
        };
    }
}
