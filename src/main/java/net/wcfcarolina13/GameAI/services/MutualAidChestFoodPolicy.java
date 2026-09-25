package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawals.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decisions behind {@code BotMutualAidService}'s shared-chest food take: how many pieces to
 * ask for, which chest to try first, and what one supply-facade answer means for the rest of the
 * attempt.
 *
 * <p>No Minecraft types, so it is tested without the game. {@link Kind} is a plain enum nested in
 * {@code SupplyWithdrawals}; loading it does not load the outer class.
 */
public final class MutualAidChestFoodPolicy {

    /** A full hunger bar. */
    public static final int MAX_FOOD_LEVEL = 20;

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
     * chest first would drop it.
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
        /** The owner was asked and has not answered: end the attempt; the next one asks this chest again. */
        WAIT,
        /** Permitted, but the bot has no room for even one piece: make room, then ask once more. */
        MAKE_ROOM_AND_RETRY,
        /** Refused for this food only: try the next food in the same chest. */
        NEXT_ITEM,
        /** Refused for this chest: try the next chest. */
        NEXT_CHEST,
        /** Nothing more to try now. */
        STOP
    }

    /**
     * @param kind         the facade's result kind
     * @param reason       the facade's reason ({@code SupplyWithdrawals.Result#reason()})
     * @param roomMadeOnce whether this take already made room once
     */
    public static Next afterWithdraw(Kind kind, String reason, boolean roomMadeOnce) {
        if (kind == null) {
            return Next.STOP;
        }
        switch (kind) {
            case MOVED:
                return Next.TAKEN;
            case WAITING:
                return Next.WAIT;
            case READY:
                // The caller only asks chests already in reach and never walks, so READY is not expected.
                return Next.STOP;
            case REFUSED:
            default:
                break;
        }
        String why = reason == null ? "" : reason;
        if (is(why, "NO_ROOM")) {
            return roomMadeOnce ? Next.STOP : Next.MAKE_ROOM_AND_RETRY;
        }
        // The policy or the reserve refuses this food, the owner said No to it lately, or it is gone.
        if (is(why, "INELIGIBLE") || is(why, "REJECT_COOLDOWN") || is(why, "NO_STOCK")) {
            return Next.NEXT_ITEM;
        }
        // This chest may not be asked now. Another chest may still be covered by a standing
        // permission, which needs no owner nearby and no prompt.
        if (is(why, "DENIED") || is(why, "OWNER_NOT_NEARBY") || is(why, "PROMPT_COOLDOWN")) {
            return Next.NEXT_CHEST;
        }
        // NOT_PERMITTED (the owner just said No, or the prompt expired), another prompt pending, the
        // service down, the bot gone, a busy server, a mismatch: nothing else is worth asking now.
        return Next.STOP;
    }

    /** Whether {@code reason} is {@code name}, with or without a bracketed detail. */
    private static boolean is(String reason, String name) {
        return reason.equals(name) || reason.startsWith(name + "(");
    }
}
