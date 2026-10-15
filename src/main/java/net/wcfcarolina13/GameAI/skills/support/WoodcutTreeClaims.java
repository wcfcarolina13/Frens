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
 * good; the owner refreshes it while it works. Pure: a tree is its dimension id plus base coords,
 * and time is passed in.
 *
 * <p>Each claim also carries the run token of the woodcut run that made it. A bot's quick restart
 * overlaps the old run's unwinding, so the old run releases only claims stamped with its own token
 * and never the new run's.
 */
public final class WoodcutTreeClaims {

    /** How long a claim lives without a refresh. */
    public static final long DEFAULT_TTL_MS = 60_000L;

    /** A tree: the dimension id (e.g. {@code minecraft:overworld}) and its base block. */
    public record TreeKey(String dimension, int x, int y, int z) {
    }

    public record Claim(UUID owner, long runToken, long expiresAtMs) {
        boolean expired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    private final Map<TreeKey, Claim> claims = new ConcurrentHashMap<>();

    /**
     * Claims the tree for this bot's run. Succeeds when the tree is free, its claim expired, or the
     * bot already holds it; a success (re)sets the expiry to {@code nowMs + ttlMs} and stamps the
     * claim with {@code runToken}.
     */
    public boolean tryClaim(TreeKey tree, UUID bot, long runToken, long nowMs, long ttlMs) {
        if (tree == null || bot == null) {
            return false;
        }
        Claim result = claims.compute(tree, (key, existing) -> {
            if (existing == null || existing.expired(nowMs) || bot.equals(existing.owner())) {
                return new Claim(bot, runToken, nowMs + Math.max(0L, ttlMs));
            }
            return existing;
        });
        return bot.equals(result.owner());
    }

    /** Releases the tree only when this bot's run (same token) holds the claim. */
    public boolean release(TreeKey tree, UUID bot, long runToken) {
        if (tree == null || bot == null) {
            return false;
        }
        boolean[] removed = {false};
        claims.computeIfPresent(tree, (key, existing) -> {
            if (bot.equals(existing.owner()) && existing.runToken() == runToken) {
                removed[0] = true;
                return null;
            }
            return existing;
        });
        return removed[0];
    }

    /** Releases every claim this bot's run (same token) holds (end of the run). */
    public void releaseAll(UUID bot, long runToken) {
        if (bot == null) {
            return;
        }
        claims.entrySet().removeIf(entry -> bot.equals(entry.getValue().owner())
                && entry.getValue().runToken() == runToken);
    }

    /**
     * Live claims held by other bots in this dimension whose base is within {@code radius} blocks
     * (XZ, square) of the bot, for excluding their trees from this bot's search and for deciding
     * whether to wait on them. A negative radius means any distance in the dimension.
     */
    public Set<TreeKey> claimedByOthersNear(UUID bot, String dimension, int botX, int botZ, int radius, long nowMs) {
        Set<TreeKey> out = new HashSet<>();
        for (Map.Entry<TreeKey, Claim> entry : claims.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.expired(nowMs) || claim.owner().equals(bot)) {
                continue;
            }
            TreeKey tree = entry.getKey();
            if (isClaimNear(tree.dimension(), tree.x(), tree.z(), dimension, botX, botZ, radius)) {
                out.add(tree);
            }
        }
        return out;
    }

    /**
     * Whether a claim at ({@code claimDimension}, claimX, claimZ) matters to a bot at
     * ({@code botDimension}, botX, botZ): same dimension, and within {@code radius} blocks on both
     * horizontal axes (the scan is a square box). A negative radius skips the distance check.
     */
    public static boolean isClaimNear(String claimDimension, int claimX, int claimZ,
                                      String botDimension, int botX, int botZ, int radius) {
        if (claimDimension == null || !claimDimension.equals(botDimension)) {
            return false;
        }
        return radius < 0 || withinXZ(claimX, claimZ, botX, botZ, radius);
    }

    /** Both horizontal offsets are at most {@code radius} (a square around either point). */
    public static boolean withinXZ(int ax, int az, int bx, int bz, int radius) {
        return Math.abs(ax - bx) <= radius && Math.abs(az - bz) <= radius;
    }

    /** The live owner of the tree, or null when it is free or its claim expired. */
    public UUID ownerOf(TreeKey tree, long nowMs) {
        Claim claim = tree == null ? null : claims.get(tree);
        return claim == null || claim.expired(nowMs) ? null : claim.owner();
    }
}
