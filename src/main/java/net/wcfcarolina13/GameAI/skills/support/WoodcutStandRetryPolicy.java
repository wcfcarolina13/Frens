package net.wcfcarolina13.GameAI.skills.support;

import java.util.Locale;

/**
 * When a woodcut target's remaining stands are not worth walking to.
 *
 * <p>1.1.239 sandbox: from a pond-side gully the bot never moved, yet one target got the direct
 * stand, the planner stand, four dry-reposition stands and the post-abandon reposition, about
 * 7 x 15 s. Every walk failed with "no walkable path" from the same cell, so each further stand
 * repeated the same failed search. After a path failure from a cell the bot has not left (less
 * than {@link #MIN_MOVED_BLOCKS} horizontally since the target's first approach), the target is
 * abandoned. A failure after real movement keeps the old retries: the bot is somewhere new.
 *
 * <p>Only path failures count. An abort ({@code /bot stop}), a requested-bounds refusal or a
 * throttled walk says nothing about the terrain. The pathfinder's search timeout ends in
 * MovementService's "no walkable path" (no path came back), so it is covered by that marker.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutStandRetryPolicy {

    /** Horizontal blocks the bot must have moved since the first approach to keep retrying. */
    public static final double MIN_MOVED_BLOCKS = 1.0D;

    private static final String[] PATH_FAILURE_MARKERS = {
            "no walkable path",
            "walk blocked",
            "a* timeout",
            "a* timed out",
            "path search timed out"
    };

    private WoodcutStandRetryPolicy() {
    }

    /** Whether a movement failure detail says the walk found no route or could not follow it. */
    public static boolean isPathFailure(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        // A throttled or cooling-down walk never searched; its text embeds the previous failure.
        if (lower.contains("aborted") || lower.contains("throttled") || lower.contains("cooling down")) {
            return false;
        }
        for (String marker : PATH_FAILURE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether to abandon the target now: the walk failed for a path reason and the bot moved less
     * than {@link #MIN_MOVED_BLOCKS} horizontally since the target's first approach attempt.
     */
    public static boolean skipRemainingStands(String detail, double movedBlocksHorizontal) {
        return isPathFailure(detail) && movedBlocksHorizontal < MIN_MOVED_BLOCKS;
    }

    /** Horizontal distance between the approach origin and the bot now (jump bob is ignored). */
    public static double horizontalMoved(double originX, double originZ, double nowX, double nowZ) {
        double dx = nowX - originX;
        double dz = nowZ - originZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The one log line a skipped target gets. */
    public static String skipLine(String base, String detail) {
        return "Woodcut approach: skipping remaining stands for " + base
                + " (path failed from an unmoved cell: " + detail + ")";
    }
}
