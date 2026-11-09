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

    /**
     * In the air on the way to the safe-drop column: once the feet are over that column and still
     * moving sideways, the sideways momentum is dropped so the bot comes down there and not over
     * the next, deeper column.
     */
    public static boolean holdOverTarget(int bx, int bz, int tx, int tz, double vx, double vz) {
        return bx == tx && bz == tz && (vx * vx + vz * vz) > 1.0E-6D;
    }
}
