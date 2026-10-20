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

    /** Slack on the last live waypoint's reach, so a bot halted exactly at its stop distance counts. */
    public static final double LAST_LIVE_REACH_EPSILON = 0.25D;

    private FollowWaypointReachPolicy() {}

    /**
     * Reach for the last waypoint of a plan that follows a live player. That waypoint is usually
     * the player's own block (or the player's old block, or the end of a path that stops short),
     * and the bot halts {@code stopDistance} (personal space) before it. So it counts as reached
     * once the bot is within that stop distance horizontally (plus a small slack) and at most one
     * level away; it is then dropped and direct pursuit, which keeps personal space, takes over.
     * Anything tighter freezes the bot short of a waypoint it will never walk closer to.
     *
     * @param botX         bot's exact X
     * @param botZ         bot's exact Z
     * @param botBlockY    bot's block Y
     * @param stopDistance the distance the bot stops at for this waypoint
     */
    public static boolean reachedLastLive(double botX, int botBlockY, double botZ,
                                          int peekX, int peekY, int peekZ,
                                          double stopDistance) {
        if (Math.abs(peekY - botBlockY) > 1) {
            return false;
        }
        double dx = (peekX + 0.5D) - botX;
        double dz = (peekZ + 0.5D) - botZ;
        double reach = Math.max(0.0D, stopDistance) + LAST_LIVE_REACH_EPSILON;
        return dx * dx + dz * dz <= reach * reach;
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
