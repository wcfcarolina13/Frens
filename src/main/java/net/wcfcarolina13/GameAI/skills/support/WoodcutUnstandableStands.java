package net.wcfcarolina13.GameAI.skills.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-bot memory of woodcut stands the unified pathfinder refused as GOAL_NOT_STANDABLE, so the
 * skill stops walking at the same unstandable cell run after run. It outlives a single run (the
 * entries expire after {@link #TTL_MS}) and is never cleared at run end. Only the unified
 * finder reports a reason; a Baritone failure carries none and is never recorded here.
 * Pure: stands are packed longs ({@code BlockPos.asLong()}), the clock is injected.
 */
public final class WoodcutUnstandableStands {

    public static final long TTL_MS = 5L * 60L * 1000L;

    /** The token MovementService writes into a walk detail when the goal cell cannot be stood on. */
    static final String GOAL_NOT_STANDABLE_TOKEN = "reason=GOAL_NOT_STANDABLE";

    private final Map<UUID, Map<Long, Long>> expiryByBot = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public WoodcutUnstandableStands() {
        this(System::currentTimeMillis);
    }

    public WoodcutUnstandableStands(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * True when a MovementService walk detail says the goal was not standable. Matches both the
     * "partial plan stopped after N legs reason=GOAL_NOT_STANDABLE plan=..." and the
     * "no walkable path reason=GOAL_NOT_STANDABLE plan=..." forms, and nothing whose reason only
     * starts with the same letters.
     */
    public static boolean isGoalNotStandable(String detail) {
        if (detail == null) {
            return false;
        }
        int at = detail.indexOf(GOAL_NOT_STANDABLE_TOKEN);
        while (at >= 0) {
            int end = at + GOAL_NOT_STANDABLE_TOKEN.length();
            boolean startsToken = at == 0 || !isTokenChar(detail.charAt(at - 1));
            boolean endsToken = end >= detail.length() || !isTokenChar(detail.charAt(end));
            if (startsToken && endsToken) {
                return true;
            }
            at = detail.indexOf(GOAL_NOT_STANDABLE_TOKEN, at + 1);
        }
        return false;
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Remembers the stand for this bot for {@link #TTL_MS}, dropping the bot's expired entries. */
    public void record(UUID bot, long stand) {
        if (bot == null) {
            return;
        }
        long now = clock.getAsLong();
        Map<Long, Long> stands = expiryByBot.computeIfAbsent(bot, ignored -> new ConcurrentHashMap<>());
        stands.values().removeIf(expiry -> expiry <= now);
        stands.put(stand, now + TTL_MS);
    }

    /** True while this stand is remembered as unstandable for this bot. */
    public boolean isBlocked(UUID bot, long stand) {
        if (bot == null) {
            return false;
        }
        Map<Long, Long> stands = expiryByBot.get(bot);
        if (stands == null) {
            return false;
        }
        Long expiry = stands.get(stand);
        if (expiry == null) {
            return false;
        }
        if (expiry <= clock.getAsLong()) {
            stands.remove(stand, expiry);
            return false;
        }
        return true;
    }

    public void clear(UUID bot) {
        if (bot != null) {
            expiryByBot.remove(bot);
        }
    }
}
