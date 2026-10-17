package net.wcfcarolina13.PathFinding;

/**
 * Which finder a budgeted probe ({@link PathFinder#probePlan}, {@link PathFinder#canReach}) runs for
 * a bot's resolved choice. A probe runs with the caller's time budget, often on the server thread,
 * so it may only use a finder that honours that budget:
 *
 * <ul>
 *   <li>UNIFIED runs as itself, with {@code PlanRequest.probe(timeoutMs)}.</li>
 *   <li>BARITONE runs as itself, with its timeout overload.</li>
 *   <li>BIDIR (classic) has no budgeted entry: it always runs to its 10 s hard cap, which would
 *   freeze the server tick. It probes with Baritone instead, as {@code canReach} always has; slice D
 *   removes the classic finder altogether.</li>
 * </ul>
 *
 * A null choice is treated as {@link PathFinderChoice#DEFAULT}.
 */
public final class PathFinderProbePolicy {

    private PathFinderProbePolicy() {
    }

    public static PathFinderChoice budgetedFinder(PathFinderChoice choice) {
        PathFinderChoice c = choice != null ? choice : PathFinderChoice.DEFAULT;
        return c == PathFinderChoice.UNIFIED ? PathFinderChoice.UNIFIED : PathFinderChoice.BARITONE;
    }
}
