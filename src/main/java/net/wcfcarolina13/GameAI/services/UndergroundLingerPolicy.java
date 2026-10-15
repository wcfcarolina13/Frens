package net.wcfcarolina13.GameAI.services;

/**
 * Pure pre-checks for {@code BotFleeService.shouldSuppressSurfaceRecovery} (the underground linger
 * tree), plus the dig-down bunker cap rule.
 *
 * <ul>
 *   <li>Inside its own registered shelter, a bot stays put.</li>
 *   <li>At night or in a thunderstorm, a bot stays put. This now applies before the tree's
 *       "non-functional position" bypass (#0), which used to skip the night rule.</li>
 *   <li>By day, a bot in a shallow open-sky hole (1..6 blocks below the wide surface sample) with at
 *       least 2 scaffold blocks climbs out: pillaring mines anything overhead by hand and places
 *       the scaffold under itself, so a pickaxe is not needed and being well fed is no reason to
 *       wait.</li>
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
    /** Scaffold blocks the pillar recovery needs before it runs. */
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

    public static Decision decide(boolean openSky,
                                  int feetY,
                                  int wideSurfaceFeetY,
                                  boolean isDay,
                                  boolean thundering,
                                  int scaffoldCount,
                                  int food,
                                  boolean inShelter) {
        if (food <= CRITICAL_FOOD) {
            return Decision.FALL_THROUGH;
        }
        if (inShelter) {
            return Decision.SUPPRESS_SHELTERED;
        }
        if (!isDay || thundering) {
            return Decision.SUPPRESS_NIGHT;
        }
        int depth = depthBelowSurface(feetY, wideSurfaceFeetY);
        if (openSky && depth >= 1 && depth <= MAX_CLIMB_DEPTH && scaffoldCount >= MIN_SCAFFOLD) {
            return Decision.ALLOW_RECOVERY;
        }
        return Decision.FALL_THROUGH;
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
