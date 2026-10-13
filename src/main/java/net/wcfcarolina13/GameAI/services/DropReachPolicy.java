package net.wcfcarolina13.GameAI.services;

import java.util.function.BooleanSupplier;

/**
 * Pure reach rules for ground-item pickup (idle drop sweep and the woodcut
 * {@code DropSweeper} nudge). No Minecraft types so it is unit-testable.
 *
 * <p>Vertical cap: a player's item-pickup box is its hitbox (1.8 tall) grown by
 * 0.5 vertically, so a bot on flat ground collects anything whose block Y is up to
 * 2 above its feet block (an item resting on top of a 2-high pillar sits at
 * feet+2.0, inside feet+2.3). Anything 3+ above needs a climb or a raised stand,
 * which the opportunistic sweep does not build, so it is not worth committing to.
 * Below, 6 matches the old minimum search height of {@code findNearestDrop}, so the
 * small-radius callers keep their downward reach while the 20-block idle sweep no
 * longer targets drops at the bottom of a ravine.</p>
 *
 * <p>The vertical band applies to the idle sweep only ({@link #isIdleSweepReachable}):
 * an item more than 2 above the feet is still worth the walk when the bot can stand at
 * or beside it (a drop up a hill). The other {@code findNearestDrop} callers (linger,
 * follow, post-combat, guard, patrol) keep their old {@code max(6, radius)} box.</p>
 */
public final class DropReachPolicy {

    /** Highest item block Y above the bot's feet block that is collectable without a stand. */
    public static final int MAX_DY_ABOVE = 2;
    /** Deepest item block Y below the bot's feet block that a sweep will commit to. */
    public static final int MAX_DY_BELOW = 6;
    /** Existing idle-sweep no-progress timeout for a reachable drop (10 s). */
    public static final long REACHABLE_TIMEOUT_TICKS = 200L;
    /** Short timeout when the committed drop is out of vertical reach (3 s): stop staring at it. */
    public static final long OUT_OF_REACH_TIMEOUT_TICKS = 60L;
    /** An item that moved more than one block from the committed spot gets a new target. */
    public static final double RETARGET_MOVED_SQ = 1.0D;

    private DropReachPolicy() {
    }

    /** {@code dyItemMinusFeet} = item block Y minus the bot's feet block Y. */
    public static boolean isVerticallyReachable(int dyItemMinusFeet) {
        return dyItemMinusFeet <= MAX_DY_ABOVE && dyItemMinusFeet >= -MAX_DY_BELOW;
    }

    /**
     * Idle-sweep candidate rule: within the downward band, and either low enough to grab
     * from the feet block or with a 2-high stand at or beside it. The stand check reads the
     * world, so it is only evaluated for items above {@link #MAX_DY_ABOVE}.
     */
    public static boolean isIdleSweepReachable(int dyItemMinusFeet, BooleanSupplier hasStandAtOrBeside) {
        if (dyItemMinusFeet < -MAX_DY_BELOW) {
            return false;
        }
        return dyItemMinusFeet <= MAX_DY_ABOVE || hasStandAtOrBeside.getAsBoolean();
    }

    public static long noProgressTimeoutTicks(int dyItemMinusFeet) {
        return isVerticallyReachable(dyItemMinusFeet) ? REACHABLE_TIMEOUT_TICKS : OUT_OF_REACH_TIMEOUT_TICKS;
    }

    /** {@code movedSq} = squared distance from the committed target to the item's current position. */
    public static boolean shouldRetarget(double movedSq) {
        return movedSq > RETARGET_MOVED_SQ;
    }

    /** A nudge step into a cell is only safe when the bot fits there: feet and head both passable. */
    public static boolean isNudgeCellSafe(boolean feetPassable, boolean headPassable) {
        return feetPassable && headPassable;
    }
}
