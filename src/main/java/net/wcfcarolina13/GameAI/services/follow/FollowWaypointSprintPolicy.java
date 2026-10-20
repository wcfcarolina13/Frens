package net.wcfcarolina13.GameAI.services.follow;

/**
 * Whether follow may sprint toward the current waypoint.
 *
 * <p>Sprint carries the bot 0.5-0.7 blocks past where it lets go of the input, so sprinting
 * into a kept corner overshot it and re-created the leaf-corner cut the corner was kept to
 * avoid. No sprint when the path turns at the current waypoint or the next waypoint changes Y.</p>
 */
public final class FollowWaypointSprintPolicy {

    private FollowWaypointSprintPolicy() {}

    /**
     * @param next the waypoint after {@code peek} as {x, y, z}, or null when {@code peek} is last
     * @return true when the path turns or changes height at {@code peek}
     */
    public static boolean turnsAt(int botX, int botZ,
                                  int peekX, int peekY, int peekZ,
                                  int[] next) {
        if (next == null || next.length < 3) {
            return false;
        }
        if (next[1] != peekY) {
            return true;
        }
        int inX = Integer.signum(peekX - botX);
        int inZ = Integer.signum(peekZ - botZ);
        int outX = Integer.signum(next[0] - peekX);
        int outZ = Integer.signum(next[2] - peekZ);
        if (inX == 0 && inZ == 0) {
            // Bot already in the waypoint's column (above or below it): any heading is a turn.
            return true;
        }
        return inX != outX || inZ != outZ;
    }

    public static boolean allowSprint(boolean farEnough, boolean turnsAtWaypoint) {
        return farEnough && !turnsAtWaypoint;
    }
}
