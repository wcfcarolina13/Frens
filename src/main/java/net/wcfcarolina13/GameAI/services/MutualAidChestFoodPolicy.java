package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decisions behind {@code BotMutualAidService}'s shared-chest food take: how many pieces to
 * ask for, which chest to try first, what one supply-facade answer means for the rest of the
 * attempt, and how long the bot then leaves chests alone.
 *
 * <p>No Minecraft types, so it is tested without the game. {@link Kind} and {@link Scope} are plain
 * enums nested in the supply classes; loading them does not load the outer classes.
 */
public final class MutualAidChestFoodPolicy {

    /** A full hunger bar. */
    public static final int MAX_FOOD_LEVEL = 20;

    /** Between two shared-chest food attempts by one bot, whatever the first came to (8 s). */
    public static final long THROTTLE_TICKS = 20L * 8L;
    /**
     * After a refusal that reaches everything the bot asks for now (the owner's No, an ignored
     * prompt, a cooldown, another prompt open, no owner): no chest is asked for 60 s.
     */
    public static final long BOT_PAUSE_TICKS = 20L * 60L;
    /** After the owner was found away from the chest: no chest is asked for a flat 60 s. */
    public static final long OWNER_AWAY_DEFER_TICKS = 20L * 60L;

    private MutualAidChestFoodPolicy() {
    }

    /**
     * Pieces of one food to take: 2, or 3 when starving, never more than the hunger deficit
     * needs at this food's nutrition, nor more than {@code available}; at least 1.
     *
     * @param foodLevel the bot's hunger bar, 0..20
     * @param starving  whether the bot counts as starving
     * @param nutrition the food's nutrition per piece; 0 or less means unknown (no deficit cap)
     * @param available how many the chest could grant
     */
    public static int withdrawCount(int foodLevel, boolean starving, int nutrition, int available) {
        int hungerDeficit = Math.max(1, MAX_FOOD_LEVEL - foodLevel);
        int pieces = starving ? 3 : 2;
        if (nutrition > 0) {
            pieces = Math.max(1, Math.min(pieces, (int) Math.ceil(hungerDeficit / (double) nutrition)));
        }
        return Math.max(1, Math.min(available, pieces));
    }

    /**
     * The candidate chests nearest first, except that the chest the bot last asked about goes
     * first: its owner's answer is only redeemed by asking that chest again, and asking any other
     * chest first would open a second prompt.
     *
     * @param byDistance the candidates, nearest first; not modified
     * @param asked      the chest the bot is waiting on, or {@code null}
     */
    public static <T> List<T> askedFirst(List<T> byDistance, T asked) {
        if (asked == null || !byDistance.contains(asked)) {
            return byDistance;
        }
        List<T> ordered = new ArrayList<>(byDistance.size());
        ordered.add(asked);
        for (T candidate : byDistance) {
            if (!asked.equals(candidate)) {
                ordered.add(candidate);
            }
        }
        return ordered;
    }

    /** What the attempt does after one answer from the supply facade. */
    public enum Next {
        /** Food moved into the bot: eat, and end the attempt. */
        TAKEN,
        /**
         * The owner was asked and has not answered: end the attempt without walking; the next one
         * (after {@link #THROTTLE_TICKS}) asks this chest again, which picks the answer up.
         */
        WAIT,
        /** Permitted, but the bot has no room for even one piece: make room, then ask once more. */
        MAKE_ROOM_AND_RETRY,
        /** Refused for this food wherever it is ({@link Scope#ITEM}): skip it in every chest this attempt. */
        NEXT_ITEM,
        /** Nothing but the moment ({@link Scope#BUSY}): leave this food in this chest for the next attempt; go on. */
        NEXT_TARGET,
        /** Refused for this chest ({@link Scope#CHEST}): try the next chest (a double chest's other half is skipped too). */
        NEXT_CHEST,
        /** Nothing more to try now; the next attempt after the usual {@link #THROTTLE_TICKS}. */
        STOP,
        /** Refused for everything this bot asks now ({@link Scope#BOT}): stop; no chest is asked for {@link #BOT_PAUSE_TICKS}. */
        PAUSE,
        /** The owner is away ({@link Scope#OWNER_ABSENT}): stop; no chest is asked for {@link #OWNER_AWAY_DEFER_TICKS}. */
        DEFER
    }

    /**
     * Decides from the result's kind and, for a refusal, its scope only; the reason string is for
     * the logs. The one site fact it takes is whether a piece of this food still fits the bot: a
     * permitted take the bot has no room for comes back {@link Scope#BUSY} (the ticket is kept),
     * and only then is room made, once. {@link Scope#TARGET} reads as {@link Scope#CHEST} for now.
     *
     * @param kind         the facade's result kind
     * @param scope        the facade's refusal scope ({@code SupplyWithdrawals.Result#scope()})
     * @param roomForOne   whether one more piece of this food fits the bot's inventory right now
     * @param roomMadeOnce whether this take already made room once
     */
    public static Next afterWithdraw(Kind kind, Scope scope, boolean roomForOne, boolean roomMadeOnce) {
        if (kind == null) {
            return Next.STOP;
        }
        switch (kind) {
            case MOVED:
                return Next.TAKEN;
            case WAITING:
                return Next.WAIT;
            case READY:
                // Permitted but out of reach. This site asks only chests already in reach, runs on the
                // server thread and never walks; asking another chest now could open a second prompt.
                return Next.STOP;
            case REFUSED:
            default:
                break;
        }
        if (scope == null) {
            return Next.PAUSE; // a refusal without a scope reaches everything (fail closed)
        }
        switch (scope) {
            case ITEM:
                return Next.NEXT_ITEM;
            case CHEST:
            case TARGET:
                return Next.NEXT_CHEST;
            case OWNER_ABSENT:
                return Next.DEFER;
            case BUSY:
                if (roomForOne) {
                    return Next.NEXT_TARGET;
                }
                // Room was made and still nothing fits: nothing here is droppable, and asking another
                // chest could only open a prompt the bot cannot redeem. Try again next attempt.
                return roomMadeOnce ? Next.STOP : Next.MAKE_ROOM_AND_RETRY;
            case BOT:
            case NONE:
            default:
                return Next.PAUSE;
        }
    }

    /** How long, in server ticks, before this bot asks a chest for food again after an attempt ended in {@code next}. */
    public static long probeDelayTicks(Next next) {
        if (next == Next.PAUSE) {
            return BOT_PAUSE_TICKS;
        }
        if (next == Next.DEFER) {
            return OWNER_AWAY_DEFER_TICKS;
        }
        return THROTTLE_TICKS;
    }
}
