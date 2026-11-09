package net.wcfcarolina13.GameAI.services;

/**
 * Tree-canopy escape rules ({@link TreeStuckEscapeService}) that need no Minecraft types.
 */
public final class TreeStuckDropPolicy {

    /** Vanilla fall damage is fall distance minus 3: a 3-block drop is the deepest that costs nothing. */
    public static final int SAFE_DROP_HEIGHT = 3;

    private TreeStuckDropPolicy() {}

    /** Whether {@code drop} open blocks under an edge cell make it a safe walk-off. */
    public static boolean isSafeDrop(int drop) {
        return drop >= 0 && drop <= SAFE_DROP_HEIGHT;
    }

    /** How far the bot's centre may be from the column centre with its whole hitbox (half-width 0.3) inside the column. */
    public static final double CLEAR_OF_LIP = 0.2D;

    /**
     * In the air on the way to the safe-drop column ({@code tx,tz} its block coordinates): once the
     * whole hitbox is inside that column and still moving sideways, the sideways momentum is
     * dropped so the bot comes down there and not over the next, deeper column. Holding as soon
     * as the centre crossed in would leave part of the hitbox over the leaf lip, and the bot would
     * land back on it.
     */
    public static boolean holdOverTarget(double x, double z, int tx, int tz, double vx, double vz) {
        return Math.abs(x - (tx + 0.5D)) <= CLEAR_OF_LIP && Math.abs(z - (tz + 0.5D)) <= CLEAR_OF_LIP
                && (vx * vx + vz * vz) > 1.0E-6D;
    }
}
