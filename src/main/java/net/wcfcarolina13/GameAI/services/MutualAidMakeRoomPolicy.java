package net.wcfcarolina13.GameAI.services;

/**
 * Pure decisions behind {@code BotMutualAidService}'s make-room on the server tick: whether a bot
 * must drop a stack to take food or a gift, and how long it waits after a drop that found nothing
 * it may drop.
 *
 * <p>Make-room runs on the server thread, so it only ever drops one stack; it never walks to a
 * chest, places one or deposits into one. A flower is a cosmetic gift and never makes a full bot
 * drop anything. A drop that finds nothing it may drop holds that bot's make-room off for
 * {@link #RETRY_TICKS}, so a full bot does not retry (and log) every tick.
 *
 * <p>No Minecraft types, so it is tested without the game.
 */
public final class MutualAidMakeRoomPolicy {

    /** After a drop that found nothing the bot may drop, no make-room drop for that bot for 10 s. */
    public static final long RETRY_TICKS = 20L * 10L;

    /** What the room is for. */
    public enum Kind {
        /** A starving bot with a full inventory next to dropped food. */
        FOOD_PICKUP,
        /** A bot about to receive spare gear from another bot. */
        GEAR,
        /** A bot about to receive a flower from another bot. */
        FLOWER,
        /** A bot about to receive food from another bot: the handoff needs no room made. */
        FOOD_SHARE
    }

    /** What to do about room in the bot's inventory. */
    public enum Decision {
        /** Go ahead without dropping anything. */
        NOT_NEEDED,
        /** Drop one stack to free a slot, then go ahead if that worked. */
        DROP,
        /** Do not go ahead, and drop nothing. */
        DECLINE
    }

    private MutualAidMakeRoomPolicy() {
    }

    /**
     * Food handoffs never need room; a bot with an empty main-inventory slot needs none either.
     * Otherwise a flower is declined and anything else drops one stack.
     *
     * @param kind         what the room is for
     * @param hasEmptySlot whether the bot has an empty main-inventory slot
     */
    public static Decision decide(Kind kind, boolean hasEmptySlot) {
        if (kind == Kind.FOOD_SHARE || hasEmptySlot) {
            return Decision.NOT_NEEDED;
        }
        return kind == Kind.FLOWER ? Decision.DECLINE : Decision.DROP;
    }

    /**
     * Whether a make-room drop may be tried at {@code nowTick}.
     *
     * @param nextAllowedTick the tick from {@link #nextAllowedAfterFailure}, or {@code 0} when the
     *                        bot has no failed drop on record
     */
    public static boolean mayAttempt(long nowTick, long nextAllowedTick) {
        return nowTick >= nextAllowedTick;
    }

    /** The first tick a make-room drop may be tried again after one failed at {@code nowTick}. */
    public static long nextAllowedAfterFailure(long nowTick) {
        return nowTick + RETRY_TICKS;
    }
}
