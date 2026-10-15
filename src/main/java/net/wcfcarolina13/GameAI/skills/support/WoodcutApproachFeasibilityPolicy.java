package net.wcfcarolina13.GameAI.skills.support;

/**
 * Pure decisions for a woodcut approach from a spot the bot may not be able to leave.
 *
 * <p>1.1.238 sandbox: the bot sat in a roofed cave pocket at Y62-63 while the trees stood on the
 * surface at Y67-69. It had no axe (so no pillar) and no scaffold, every stand walk failed, and it
 * spent 10 minutes cycling stands around two unreachable trees while holding the task slot, so
 * surface recovery was refused ("System task slot busy").
 *
 * <p>Enclosure keys on the sky and the walls, never on depth alone. A bot in a 1-2 block dip under
 * open sky or a leaf canopy is ordinary woodcut terrain: {@code nearSurface} is true there (the
 * surface reference scans through logs and leaves), so it proceeds even when the canopy hides the
 * sky and the dip walls read as blocked.
 *
 * <p>No Minecraft types.
 */
public final class WoodcutApproachFeasibilityPolicy {

    /** Blocked cardinal neighbours (of 4) that make a roofed spot a pocket. */
    public static final int ENCLOSED_BLOCKED_CARDINALS = 3;
    /** Failed or skipped targets in a row, from about the same spot, that end the run. */
    public static final int TRAPPED_TARGET_STREAK = 2;
    /** Moving at least this far (blocks) from the first failure's spot resets the streak. */
    public static final int TRAPPED_MOVE_BLOCKS = 3;
    /**
     * Failed dry-reposition stands per target, whatever side they were on. The sign-keyed
     * identical-failure counter resets whenever the distance-sorted candidates alternate sides,
     * so it never bounded a walled-in target; this one does.
     */
    public static final int MAX_DRY_REPOSITION_FAILURES_PER_TARGET = 4;

    /** The run's result line on EXIT_TRAPPED (the /bot skill worker echoes it to chat). */
    public static final String TRAPPED_LINE = "I'm stuck down here and can't reach any trees.";

    public enum Verdict {
        /** Try the target as usual. */
        PROCEED,
        /** Enclosed and unable to climb: leave this target without walking to any stand. */
        SKIP_TARGET,
        /** Enclosed, and the last targets all failed from the same spot: end the run. */
        EXIT_TRAPPED
    }

    private WoodcutApproachFeasibilityPolicy() {
    }

    /**
     * Whether the bot stands in a pocket: no sky above it, well below the local surface, and
     * walled on at least {@link #ENCLOSED_BLOCKED_CARDINALS} sides. All three must hold.
     */
    public static boolean isEnclosed(boolean openSky, boolean nearSurface, int blockedCardinals) {
        return !openSky && !nearSurface && blockedCardinals >= ENCLOSED_BLOCKED_CARDINALS;
    }

    /**
     * Whether the bot can build its way up: pillaring is allowed (it has an axe, or is not
     * bootstrapping) and it holds or can make scaffold.
     */
    public static boolean canClimb(boolean mayPillar, int scaffoldBlocks) {
        return mayPillar && scaffoldBlocks > 0;
    }

    /**
     * The verdict before walking to any stand for a target. Already on its own pillar: proceed
     * (the climb is under way). Enclosed and unable to climb: skip the target, but only once a
     * real approach already failed from this spot ({@code trappedStreak >= 1}). The first target
     * from an enclosed spot always gets one bounded walk, because the enclosure read can miss an
     * open side (a dip on a slope walled on three sides). Otherwise proceed.
     *
     * @param trappedStreak failed targets in a row from about this spot (0 when the bot has moved
     *                      {@link #TRAPPED_MOVE_BLOCKS} or more since the streak's anchor)
     */
    public static Verdict beforeApproach(boolean openSky,
                                         boolean nearSurface,
                                         int blockedCardinals,
                                         boolean mayPillar,
                                         int scaffoldBlocks,
                                         boolean onPillar,
                                         int trappedStreak) {
        if (onPillar || !isEnclosed(openSky, nearSurface, blockedCardinals)) {
            return Verdict.PROCEED;
        }
        if (canClimb(mayPillar, scaffoldBlocks) || trappedStreak < 1) {
            return Verdict.PROCEED;
        }
        return Verdict.SKIP_TARGET;
    }

    /**
     * The stuck streak after a target was skipped or failed its approach. Not enclosed: 0 (the
     * caller forgets its anchor). Moved {@link #TRAPPED_MOVE_BLOCKS} or more from the anchor, or no
     * streak yet: 1 (the caller anchors here). Otherwise the streak grows by one.
     */
    public static int nextStuckStreak(boolean enclosed, int previousStreak, int movedBlocksFromAnchor) {
        if (!enclosed) {
            return 0;
        }
        if (previousStreak <= 0 || movedBlocksFromAnchor >= TRAPPED_MOVE_BLOCKS) {
            return 1;
        }
        return previousStreak + 1;
    }

    /**
     * The verdict after a target was skipped or failed its approach. EXIT_TRAPPED when the bot is
     * enclosed, not on its own pillar, and the last {@link #TRAPPED_TARGET_STREAK} or more targets
     * failed with the bot moving less than {@link #TRAPPED_MOVE_BLOCKS} blocks. Otherwise PROCEED.
     */
    public static Verdict afterFailedApproach(boolean enclosed,
                                              boolean onPillar,
                                              int stuckStreak,
                                              int movedBlocksFromAnchor) {
        if (enclosed
                && !onPillar
                && stuckStreak >= TRAPPED_TARGET_STREAK
                && movedBlocksFromAnchor < TRAPPED_MOVE_BLOCKS) {
            return Verdict.EXIT_TRAPPED;
        }
        return Verdict.PROCEED;
    }

    /** Whether a target's dry-reposition budget is spent (failures on any side count). */
    public static boolean dryRepositionBudgetSpent(int failuresThisTarget) {
        return failuresThisTarget >= MAX_DRY_REPOSITION_FAILURES_PER_TARGET;
    }
}
