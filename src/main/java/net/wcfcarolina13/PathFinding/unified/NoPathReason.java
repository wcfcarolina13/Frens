package net.wcfcarolina13.PathFinding.unified;

/**
 * Why a search found no full path. Precedence (see {@code UnifiedPathFinder.reason}): BUDGET when the
 * search was cut short, then UNLOADED, BLOCKED_PROTECTED, BOXED_IN, BLOCKED_UNBREAKABLE, NO_SCAFFOLD,
 * and BOXED_IN for an exhausted region nothing else explains. GOAL_NOT_STANDABLE overrides all of these
 * when the goal had no standable cell within 2 (the search still returns a partial toward it), and
 * NO_SCAFFOLD is also given to a plan cut before a placement the bot cannot afford. NONE on a FULL plan.
 */
public enum NoPathReason {
    BOXED_IN, GOAL_NOT_STANDABLE, BUDGET, BLOCKED_PROTECTED, BLOCKED_UNBREAKABLE, NO_SCAFFOLD, UNLOADED, NONE
}
