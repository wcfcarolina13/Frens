package net.wcfcarolina13.PathFinding.unified;

import java.util.Set;

/**
 * What a caller asks of one unified search (1.1.242 slice B): the move gates and the budget. The
 * bot-derived parts of a {@link SearchContext} (tool speeds, scaffold count, sprint, territory) come
 * from the world-side snapshot instead, so this record stays pure and testable.
 *
 * @param maxFall   1 removes FALL (2..3 blocks) from the move set; the legacy List&lt;PathNode&gt;
 *                  executor only walks the old one-block step-downs
 * @param failureMs 0 or less: none, {@code primaryMs} is the whole budget (server thread)
 * @param nodeCap   0 or less: {@link SearchContext#defaultNodeCap}
 */
public record PlanRequest(boolean allowBreak,
                          boolean allowPlace,
                          boolean allowSwim,
                          boolean allowDownward,
                          int maxFall,
                          long primaryMs,
                          long failureMs,
                          int nodeCap,
                          Set<Long> forbiddenFirstHops) {

    /**
     * Node cap for probes and server-thread searches (design section 5.2). 4500 since 1.1.267: the
     * mutated layer (field bug F) keys a second node per cell, so the old 3000 ended break-heavy
     * probes early as BUDGET partials. Time budgets are unchanged and still bound the search.
     */
    public static final int PROBE_NODE_CAP = 4500;

    public PlanRequest {
        maxFall = Math.max(1, Math.min(SearchContext.DEFAULT_MAX_FALL, maxFall));
        primaryMs = Math.max(1, primaryMs);
        forbiddenFirstHops = forbiddenFirstHops == null ? Set.of() : Set.copyOf(forbiddenFirstHops);
    }

    /**
     * What {@code PathFinder.calculatePath} asks for while its callers still walk a
     * {@code List<PathNode>} with {@code walkSegment}: the old finders' move set (flat, diagonal,
     * one-block step up or down, wading) priced in ticks. No break, place, swim or multi-block fall.
     */
    public static PlanRequest legacyWalk() {
        return new PlanRequest(false, false, false, false, 1,
                SearchContext.DEFAULT_PRIMARY_MS, SearchContext.DEFAULT_FAILURE_MS, 0, Set.of());
    }

    /** The walkTo row of design section 5.2: swim on, falls up to 3, break and place per the caller's gates. */
    public static PlanRequest walkTo(boolean allowBreak, boolean allowPlace) {
        return new PlanRequest(allowBreak, allowPlace, true, false, SearchContext.DEFAULT_MAX_FALL,
                SearchContext.DEFAULT_PRIMARY_MS, SearchContext.DEFAULT_FAILURE_MS, 0, Set.of());
    }

    /** A reachability probe: the caller's budget is the whole budget, no swim, no world changes. */
    public static PlanRequest probe(long timeoutMs) {
        return new PlanRequest(false, false, false, false, SearchContext.DEFAULT_MAX_FALL,
                timeoutMs, 0, PROBE_NODE_CAP, Set.of());
    }

    /** Leg replans after the first (design section 5.2): 300 ms primary, 1200 ms failure. */
    public PlanRequest asReplan() {
        return new PlanRequest(allowBreak, allowPlace, allowSwim, allowDownward, maxFall, 300, 1200, nodeCap,
                forbiddenFirstHops);
    }

    /** On the server thread there is no failure timeout and the node cap is the probe cap unless set. */
    public PlanRequest onServerThread() {
        return new PlanRequest(allowBreak, allowPlace, allowSwim, allowDownward, maxFall, primaryMs, 0,
                nodeCap > 0 ? nodeCap : PROBE_NODE_CAP, forbiddenFirstHops);
    }

    public PlanRequest withForbiddenFirstHops(Set<Long> hops) {
        return new PlanRequest(allowBreak, allowPlace, allowSwim, allowDownward, maxFall, primaryMs, failureMs,
                nodeCap, hops);
    }

    /** Copies the gates and the budget onto a context builder. */
    public SearchContext.Builder applyTo(SearchContext.Builder b) {
        b.allowBreak(allowBreak)
                .allowPlace(allowPlace)
                .allowSwim(allowSwim)
                .allowDownward(allowDownward)
                .maxFall(maxFall)
                .primaryMs(primaryMs)
                .failureMs(failureMs)
                .forbiddenFirstHops(forbiddenFirstHops);
        if (nodeCap > 0) {
            b.nodeCap(nodeCap);
        }
        return b;
    }
}
