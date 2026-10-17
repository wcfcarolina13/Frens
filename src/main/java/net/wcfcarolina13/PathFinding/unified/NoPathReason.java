package net.wcfcarolina13.PathFinding.unified;

/**
 * Why a search found no full path. Precedence (see {@code UnifiedPathFinder.reason}): BUDGET when the
 * search was cut short, then UNLOADED, BLOCKED_PROTECTED, BOXED_IN, BLOCKED_UNBREAKABLE, NO_SCAFFOLD,
 * and BOXED_IN for an exhausted region nothing else explains. GOAL_NOT_STANDABLE overrides all of these
 * when the goal had no standable cell within 2 (the search still returns a partial toward it), and
 * NO_SCAFFOLD is also given to a plan cut before a placement the bot cannot afford. SELF_CONFLICT is
 * given to a plan cut before a move that an earlier move of the same plan undoes (the search prices
 * every move on unmutated terrain, so it can plan to mine a cell and later step onto it: 6ad-6al field
 * bug F); see {@code UnifiedPathFinder.truncateToExecutable}. Since 1.1.267 the search also gives
 * SELF_CONFLICT to a NO_PATH when it refused at least one successor an earlier move of its own path
 * undoes and the reason would otherwise be BOXED_IN, BLOCKED_UNBREAKABLE or NO_SCAFFOLD
 * ({@code UnifiedPathFinder.selfConflictOr}). NONE on a FULL plan.
 */
public enum NoPathReason {
    BOXED_IN, GOAL_NOT_STANDABLE, BUDGET, BLOCKED_PROTECTED, BLOCKED_UNBREAKABLE, NO_SCAFFOLD, UNLOADED, SELF_CONFLICT, NONE
}
