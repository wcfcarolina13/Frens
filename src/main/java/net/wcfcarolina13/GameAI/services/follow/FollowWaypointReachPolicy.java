package net.wcfcarolina13.GameAI.services.follow;

/**
 * When a follow waypoint counts as reached (findings 47, 50).
 *
 * <p>The old check was 3D block distance squared &le; 2.25 from the bot's block, so a level,
 * diagonal or one-down-and-over neighbour counted as reached before the bot had entered that
 * column. Under oak canopies the bot then aimed diagonally through a leaf corner, or stopped a
 * neighbour short of the waypoint and never dropped it.</p>
 *
 * <p>Now a waypoint is reached when the bot stands in its column (same X and Z, |dy| &le; 1).
 * The one shortcut kept: a level waypoint one block straight ahead (not diagonal) whose next
 * waypoint continues one more step in the same direction at the same level, so the bot does not
 * slow down on a straight run.</p>
 */
public final class FollowWaypointReachPolicy {

    /** The pre-finding-47 reach (3D block distance squared), kept for the last waypoint. */
    public static final double LAST_LIVE_REACH_SQ = 2.25D;

    private FollowWaypointReachPolicy() {}

    /**
     * Reach for the last waypoint of a plan that follows a live player. That waypoint is usually
     * the player's own block, so the in-column rule drove the bot into the player whenever the
     * line of sight was blocked (personal space only stops a bot that can see). The old 3D
     * distance (&le; 2.25) applies instead.
     */
    public static boolean reachedLastLive(int botX, int botY, int botZ,
                                          int peekX, int peekY, int peekZ) {
        long dx = peekX - botX;
        long dy = peekY - botY;
        long dz = peekZ - botZ;
        return dx * dx + dy * dy + dz * dz <= LAST_LIVE_REACH_SQ;
    }

    /**
     * How close the bot walks to a waypoint before stopping: the last waypoint of a live follow
     * stops at personal space, any other at {@code columnStop} (inside the waypoint's column).
     */
    public static double stopDistance(boolean lastLiveWaypoint, double personalSpace, double columnStop) {
        return lastLiveWaypoint ? Math.max(columnStop, personalSpace) : columnStop;
    }

    /**
     * @param next the waypoint after {@code peek} as {x, y, z}, or null when {@code peek} is last
     */
    public static boolean reached(int botX, int botY, int botZ,
                                  int peekX, int peekY, int peekZ,
                                  int[] next) {
        if (botX == peekX && botZ == peekZ && Math.abs(botY - peekY) <= 1) {
            return true;
        }
        if (next == null || next.length < 3 || botY != peekY) {
            return false;
        }
        int dx = peekX - botX;
        int dz = peekZ - botZ;
        boolean straightAdjacent = (Math.abs(dx) == 1 && dz == 0) || (dx == 0 && Math.abs(dz) == 1);
        if (!straightAdjacent) {
            return false;
        }
        return next[1] == peekY
                && next[0] - peekX == dx
                && next[2] - peekZ == dz;
    }
}
