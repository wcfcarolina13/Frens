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

    /**
     * Another bot's live claim whose base is this close (XZ, square) to a tree is that tree: a 2x2
     * trunk's other column, or the same trunk read from a higher base once its bottom log is cut
     * (6ad-6al field bug G). The scan's moved-base skip uses the same radius.
     */
    public static final int NEAR_BASE_RADIUS = 2;

    /** A claim; {@code topY} is the claimed tree's top log, for the vertical part of the near rule. */
    public record Claim(UUID owner, long runToken, long expiresAtMs, int topY) {
        boolean expired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    private final Map<TreeKey, Claim> claims = new ConcurrentHashMap<>();

    /**
     * Claims the tree for this bot's run, with its top at the base (see the overload). A refresh of a
     * claim this bot holds keeps the stored top.
     */
    public boolean tryClaim(TreeKey tree, UUID bot, long runToken, long nowMs, long ttlMs) {
        return tryClaim(tree, tree == null ? 0 : tree.y(), bot, runToken, nowMs, ttlMs);
    }

    /**
     * Claims the tree (base {@code tree}, top log at {@code topY}) for this bot's run. Refused while
     * another bot holds a live claim that {@link #conflicts} with it: the same base, or a base within
     * {@link #NEAR_BASE_RADIUS} (XZ) whose height range meets this one, so a trunk cut from the
     * bottom is not claimed again one block up. Otherwise succeeds when the tree is free, its claim
     * expired, or the bot already holds it; a success (re)sets the expiry to {@code nowMs + ttlMs},
     * stamps the claim with {@code runToken}, and keeps the higher of the stored and given tops for
     * the bot's own claim. Synchronized so two bots cannot both pass the near check.
     */
    public synchronized boolean tryClaim(TreeKey tree, int topY, UUID bot, long runToken, long nowMs, long ttlMs) {
        if (tree == null || bot == null) {
            return false;
        }
        Claim existing = claims.get(tree);
        int top = Math.max(tree.y(), topY);
        if (existing != null && bot.equals(existing.owner())) {
            top = Math.max(top, existing.topY());
        }
        // The merged top: a refresh checks the whole claimed trunk, not just its base.
        if (conflictingClaim(tree, top, bot, nowMs) != null) {
            return false;
        }
        claims.put(tree, new Claim(bot, runToken, nowMs + Math.max(0L, ttlMs), top));
        return true;
    }

    /** Another bot's live claim that conflicts with {@code tree}..{@code topY}, or null. */
    private Map.Entry<TreeKey, Claim> conflictingClaim(TreeKey tree, int topY, UUID bot, long nowMs) {
        for (Map.Entry<TreeKey, Claim> entry : claims.entrySet()) {
            Claim claim = entry.getValue();
            if (claim.expired(nowMs) || claim.owner().equals(bot)) {
                continue;
            }
            TreeKey held = entry.getKey();
            if (conflicts(held.dimension(), held.x(), held.y(), held.z(), claim.topY(),
                    tree.dimension(), tree.x(), tree.y(), tree.z(), topY)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Whether a claimed tree (base claimX/claimBaseY/claimZ, top claimTopY) blocks a claim on a tree
     * with base x/baseY/z and top topY: same dimension, bases within {@link #NEAR_BASE_RADIUS} on both
     * horizontal axes, and the new tree's height range meets the claimed one widened by a block each
     * way. A tree on a terrace above or below the claimed tree's logs is a different tree. A top
     * below its base counts as the base.
     */
    public static boolean conflicts(String claimDimension, int claimX, int claimBaseY, int claimZ, int claimTopY,
                                    String dimension, int x, int baseY, int z, int topY) {
        if (claimDimension == null || !claimDimension.equals(dimension)) {
            return false;
        }
        if (!withinXZ(claimX, claimZ, x, z, NEAR_BASE_RADIUS)) {
            return false;
        }
        int claimLow = claimBaseY - 1;
        int claimHigh = Math.max(claimBaseY, claimTopY) + 1;
        int high = Math.max(baseY, topY);
        return baseY <= claimHigh && high >= claimLow;
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

    /**
     * Whether the cell lies in the soil ring ({@link RecentTreeSoilLedger#soilRingContains}) of any
     * bot's live claim in this dimension, this bot's own included: a tree being cut right now keeps
     * its soil before it lands in the ledger (finding 52).
     */
    public boolean liveClaimSoilRingContains(String dimension, int x, int y, int z, long nowMs) {
        if (dimension == null) {
            return false;
        }
        for (Map.Entry<TreeKey, Claim> entry : claims.entrySet()) {
            TreeKey tree = entry.getKey();
            if (entry.getValue().expired(nowMs) || !dimension.equals(tree.dimension())) {
                continue;
            }
            if (RecentTreeSoilLedger.soilRingContains(tree.x(), tree.y(), tree.z(), x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** The live owner of the tree, or null when it is free or its claim expired. */
    public UUID ownerOf(TreeKey tree, long nowMs) {
        Claim claim = tree == null ? null : claims.get(tree);
        return claim == null || claim.expired(nowMs) ? null : claim.owner();
    }

    /**
     * The owner of another bot's live claim that blocks {@code bot} from claiming {@code tree} with
     * top {@code topY} (see {@link #conflicts}), or null; names the bot in the "claimed by" lines when
     * the conflict is a near base rather than the exact one.
     */
    public synchronized UUID ownerOfNear(TreeKey tree, int topY, UUID bot, long nowMs) {
        if (tree == null) {
            return null;
        }
        Map.Entry<TreeKey, Claim> entry = conflictingClaim(tree, topY, bot, nowMs);
        return entry == null ? null : entry.getValue().owner();
    }
}
