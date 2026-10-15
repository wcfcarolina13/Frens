package net.wcfcarolina13.GameAI.skills.support;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which bot is working which tree, shared across woodcut runs so several bots spread over a forest
 * instead of queueing on the same trunk (sandbox #19: three bots on one base in single file).
 * A claim expires on its own, so a bot that dies or disconnects mid-tree never blocks the tree for
 * good; the owner refreshes it while it works. Pure: tree bases are packed longs
 * ({@code BlockPos.asLong()}) and time is passed in.
 */
public final class WoodcutTreeClaims {

    /** How long a claim lives without a refresh. */
    public static final long DEFAULT_TTL_MS = 60_000L;

    public record Claim(UUID owner, long expiresAtMs) {
        boolean expired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    private final Map<Long, Claim> claims = new ConcurrentHashMap<>();

    /**
     * Claims the tree for this bot. Succeeds when the tree is free, its claim expired, or the bot
     * already holds it; a success (re)sets the expiry to {@code nowMs + ttlMs}.
     */
    public boolean tryClaim(long base, UUID bot, long nowMs, long ttlMs) {
        if (bot == null) {
            return false;
        }
        Claim result = claims.compute(base, (key, existing) -> {
            if (existing == null || existing.expired(nowMs) || bot.equals(existing.owner())) {
                return new Claim(bot, nowMs + Math.max(0L, ttlMs));
            }
            return existing;
        });
        return bot.equals(result.owner());
    }

    /** Releases the tree only when this bot holds the claim. */
    public boolean release(long base, UUID bot) {
        if (bot == null) {
            return false;
        }
        boolean[] removed = {false};
        claims.computeIfPresent(base, (key, existing) -> {
            if (bot.equals(existing.owner())) {
                removed[0] = true;
                return null;
            }
            return existing;
        });
        return removed[0];
    }

    /** Releases every claim this bot holds (end of its run). */
    public void releaseAll(UUID bot) {
        if (bot == null) {
            return;
        }
        claims.entrySet().removeIf(entry -> bot.equals(entry.getValue().owner()));
    }

    /** Live claims held by other bots, for excluding their trees from this bot's search. */
    public Set<Long> claimedByOthers(UUID bot, long nowMs) {
        Set<Long> out = new HashSet<>();
        for (Map.Entry<Long, Claim> entry : claims.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.expired(nowMs) || claim.owner().equals(bot)) {
                continue;
            }
            out.add(entry.getKey());
        }
        return out;
    }

    /** The live owner of the tree, or null when it is free or its claim expired. */
    public UUID ownerOf(long base, long nowMs) {
        Claim claim = claims.get(base);
        return claim == null || claim.expired(nowMs) ? null : claim.owner();
    }
}
