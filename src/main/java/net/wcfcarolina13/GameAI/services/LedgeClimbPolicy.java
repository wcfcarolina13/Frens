package net.wcfcarolina13.GameAI.services;

/**
 * How a bot floating beside a ledge it carved gets up onto it (finding 49). The bot floats with its
 * feet a few tenths below the ledge top and, in the flooded pocket that made finding 48, a ceiling
 * two cells above the water stops vanilla's own climb boost (which wants 0.6 of free headroom).
 * Holding jump makes the bot swim up, and while it pushes against the ledge lip it gets the same
 * 0.3 upward kick vanilla gives, without vanilla's headroom check: {@link #canRiseOnto} is that
 * check, done once against the real ceiling.
 *
 * <p>Pure: no {@code net.minecraft.*} types.</p>
 */
public final class LedgeClimbPolicy {

    /** A standing player's height in blocks. */
    public static final double PLAYER_HEIGHT = 1.8;
    /** Upward speed while pushing against the lip, the same value vanilla's water climb boost sets. */
    public static final double CLIMB_BOOST_VY = 0.3;
    /** Only boost when the bot is not already rising at least this fast. */
    public static final double BOOST_BELOW_VY = 0.2;

    private static final double EPS = 1.0E-6;

    /** What the climb loop does on one step. */
    public enum Step {
        /** Hold jump (swim up) and push toward the ledge centre. */
        HOLD_JUMP_FORWARD,
        /** As above, and the bot is pressed against the lip: kick it upward. */
        BOOST,
        /** Standing on the ledge: stop. */
        DONE,
        /** Out of time. */
        GIVE_UP
    }

    private LedgeClimbPolicy() {
    }

    /**
     * The stand cell's y for a ledge carved beside the top water cell at {@code waterTopY}: the floor
     * {@code N} keeps the water cell's y and the bot stands in {@code N.up()}, so its feet rest at the
     * floor's top, one above the water cell (see {@link ShoreStepPolicy}).
     */
    public static int standY(int waterTopY) {
        return waterTopY + 1;
    }

    /** {@link #canRiseOnto(double, double, double, double)} for a standing player. */
    public static boolean canRiseOnto(double feetY, double ledgeTopY, double ceilingY) {
        return canRiseOnto(feetY, ledgeTopY, ceilingY, PLAYER_HEIGHT);
    }

    /**
     * True when a bot whose feet are at {@code feetY} can rise until its feet clear
     * {@code ledgeTopY} without its head meeting the ceiling over its column at {@code ceilingY}.
     * A bot already above the ledge top only needs its current headroom.
     */
    public static boolean canRiseOnto(double feetY, double ledgeTopY, double ceilingY, double height) {
        if (Double.isNaN(feetY) || Double.isNaN(ledgeTopY) || Double.isNaN(ceilingY) || height <= 0.0) {
            return false;
        }
        return Math.max(feetY, ledgeTopY) + height <= ceilingY + EPS;
    }

    /**
     * The next step of the climb.
     *
     * @param feetY      the bot's feet height
     * @param ledgeTopY  the ledge floor's top surface (the stand cell's y)
     * @param onGround   the bot is standing on something
     * @param atStand    the bot's block position is the stand cell
     * @param hCollision the bot is pressed against a block sideways
     * @param elapsedMs  time since the climb started
     * @param timeoutMs  how long the climb may take
     */
    public static Step next(double feetY, double ledgeTopY, boolean onGround, boolean atStand,
                            boolean hCollision, long elapsedMs, long timeoutMs) {
        if (onGround && atStand) {
            return Step.DONE;
        }
        if (elapsedMs >= timeoutMs) {
            return Step.GIVE_UP;
        }
        if (hCollision && feetY < ledgeTopY) {
            return Step.BOOST;
        }
        return Step.HOLD_JUMP_FORWARD;
    }
}
