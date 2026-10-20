package net.wcfcarolina13.GameAI.services;

/**
 * Pure rule for whether a return-to-base request starts a new return episode or is a
 * re-plan of the one already running. A re-plan keeps the stuck counters in
 * {@link ReturnBaseStuckService}; a new episode starts them fresh.
 */
public final class ReturnEpisodePolicy {

    /** Bases closer than 3 blocks (horizontally) count as the same base. */
    public static final double SAME_BASE_DIST_SQ = 9.0D;

    private ReturnEpisodePolicy() {
    }

    /**
     * True unless the bot is already returning to a base within 3 blocks (XZ) of the
     * requested one.
     */
    public static boolean isNewReturn(boolean wasReturning, double oldX, double oldZ, double newX, double newZ) {
        if (!wasReturning) {
            return true;
        }
        double dx = newX - oldX;
        double dz = newZ - oldZ;
        return dx * dx + dz * dz >= SAME_BASE_DIST_SQ;
    }
}
