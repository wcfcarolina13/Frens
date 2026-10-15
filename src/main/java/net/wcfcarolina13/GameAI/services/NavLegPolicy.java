package net.wcfcarolina13.GameAI.services;

/**
 * Leg loop for {@code MovementService.walkTo} with the unified finder (1.1.242, design section 5.4):
 * plan, execute, measure progress, and replan from the grounded cell while this says so.
 *
 * <p>A leg whose first hop {@link FailedRoutePolicy} remembers is dropped and replanned with that hop
 * forbidden ({@code SearchContext.forbiddenFirstHops}); that replan does not need progress. Plan-ahead
 * and spliced paths are a follow-up: legs are sequential. Pure: no {@code net.minecraft.*} types.</p>
 */
public final class NavLegPolicy {
    public static final int MAX_LEGS = 8;
    public static final int MIN_PROGRESS_BLOCKS = 3;

    private NavLegPolicy() {
    }

    /**
     * Whether to plan another leg after one ended.
     *
     * @param legsDone                 legs planned so far, this one included
     * @param progressBlocksTowardGoal Euclidean distance to the goal before the leg minus after it
     * @param firstHopRememberedFailed the leg was dropped because its first hop is a remembered failure
     * @param aborted                  stop command, abort latch, bot removed or server stopping
     */
    public static boolean continueLegs(int legsDone, double progressBlocksTowardGoal, boolean firstHopRememberedFailed,
                                       boolean aborted) {
        if (aborted || legsDone >= MAX_LEGS) {
            return false;
        }
        if (firstHopRememberedFailed) {
            return true;
        }
        return progressBlocksTowardGoal >= MIN_PROGRESS_BLOCKS;
    }
}
