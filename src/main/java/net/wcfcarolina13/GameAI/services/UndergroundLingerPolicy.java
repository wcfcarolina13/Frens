package net.wcfcarolina13.GameAI.services;

/**
 * Pure pre-checks for {@code BotFleeService.shouldSuppressSurfaceRecovery} (the underground linger
 * tree), plus the dig-down bunker cap rule.
 *
 * <ul>
 *   <li>Inside its own registered shelter, a bot stays put.</li>
 *   <li>At night or in a thunderstorm, a bot stays put. This now applies before the tree's
 *       "non-functional position" bypass (#0), which used to skip the night rule. It only applies in
 *       dimensions with a day cycle: in the Nether and the End {@code World.isDay()} is always
 *       false, so the rule would hold a bot there forever.</li>
 *   <li>By day, a bot in a shallow open-sky hole (1..6 blocks below the wide surface sample) with
 *       enough scaffold for the climb ({@link #scaffoldNeededToClimb}) climbs out, unless it is near
 *       a base (the same exemption the tree's #0 uses: a sunken nook at home is not a pit).
 *       Pillaring mines anything overhead with whatever tool is held (never inside a base) and
 *       places the scaffold under itself, so a pickaxe is not needed and being well fed is no
 *       reason to wait.</li>
 * </ul>
 *
 * <p>Critically hungry bots (food at or below {@link #CRITICAL_FOOD}) always fall through, so the
 * tree's own "critically hungry, surface now" rule keeps owning them, day or night.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class UndergroundLingerPolicy {

    /** Matches the tree's check #4: at or below this food level the bot surfaces regardless. */
    public static final int CRITICAL_FOOD = 6;
    /** Deepest open-sky hole (feet below the wide surface sample) the day climb-out handles. */
    public static final int MAX_CLIMB_DEPTH = 6;
    /** Fewest scaffold blocks the pillar recovery runs with (its callers gate on 2). */
    public static final int MIN_SCAFFOLD = 2;

    public enum Decision {
        /** Shallow open-sky hole by day with scaffold: skip the rest of the tree's suppressions. */
        ALLOW_RECOVERY,
        /** Inside its own registered shelter. */
        SUPPRESS_SHELTERED,
        /** Night or thunder. */
        SUPPRESS_NIGHT,
        /** No opinion: run the existing tree unchanged. */
        FALL_THROUGH
    }

    private UndergroundLingerPolicy() {}

    /**
     * @param hasDayCycle false in fixed-time dimensions (Nether, End), where {@code isDay} is always
     *                    false: the night rule is skipped there and the tree runs unchanged
     * @param nearBase    the bot is near one of its bases (the tree's #0 exemption): no climb-out
     */
    public static Decision decide(boolean openSky,
                                  int feetY,
                                  int wideSurfaceFeetY,
                                  boolean hasDayCycle,
                                  boolean isDay,
                                  boolean thundering,
                                  int scaffoldCount,
                                  int food,
                                  boolean inShelter,
                                  boolean nearBase) {
        if (food <= CRITICAL_FOOD) {
            return Decision.FALL_THROUGH;
        }
        if (inShelter) {
            return Decision.SUPPRESS_SHELTERED;
        }
        if (!hasDayCycle) {
            return Decision.FALL_THROUGH;
        }
        if (!isDay || thundering) {
            return Decision.SUPPRESS_NIGHT;
        }
        if (nearBase) {
            return Decision.FALL_THROUGH;
        }
        int depth = depthBelowSurface(feetY, wideSurfaceFeetY);
        if (openSky && depth >= 1 && depth <= MAX_CLIMB_DEPTH
                && scaffoldCount >= scaffoldNeededToClimb(depth)) {
            return Decision.ALLOW_RECOVERY;
        }
        return Decision.FALL_THROUGH;
    }

    /**
     * Scaffold blocks a climb out of a hole {@code depth} deep needs. Each pillar step raises the
     * feet by one, and from {@code depth - 1} the bot steps up onto the rim, so the pillar recovery
     * (which pillars {@code min(6, scaffold)} steps, then looks for staging) needs
     * {@code depth - 1} blocks, and never fewer than {@link #MIN_SCAFFOLD}. With less it pillars,
     * finds no staging, climbs back down and repeats.
     */
    public static int scaffoldNeededToClimb(int depth) {
        return Math.max(MIN_SCAFFOLD, depth - 1);
    }

    /** Blocks the feet sit below the wide (median of 9 columns) surface feet Y. */
    public static int depthBelowSurface(int feetY, int wideSurfaceFeetY) {
        return wideSurfaceFeetY - feetY;
    }

    /**
     * True when a cap at {@code capY} is the block the shelter validator reads as overhead cover:
     * the cell directly above the head, stand + 2.
     */
    public static boolean bunkerCapCovers(int standY, int capY) {
        return capY == standY + 2;
    }
}
