package net.wcfcarolina13.GameAI.skills.impl.shelter;

import java.util.List;

/**
 * Pure (int-only) geometry for leaving a hovel through its doorway.
 *
 * <p>The hovel is a square footprint: walls sit on the ring where {@code max(|dx|,|dz|) == radius}
 * around the build center, the doorway gap is the wall cell {@code radius} blocks out along the door
 * side's normal ({@link HovelBlueprint#isDoorGap}), and nothing else in the wall is open.</p>
 *
 * <p>Two rules come from the Fortify gate-routing fix (guardrails: "Hull Boundary ≠ Interior"):</p>
 * <ul>
 *   <li>a target on the wall line counts as OUTSIDE (it is reachable from the exterior ring, and an
 *       interior bot walking straight at it humps the wall), and</li>
 *   <li>a bot standing on the wall line at floor level counts as INSIDE (it needs the doorway too).</li>
 * </ul>
 *
 * <p>Kept free of {@code net.minecraft} types so it can be unit-tested without a game bootstrap.</p>
 */
final class HovelEgressGeometry {

    enum FootprintZone { INSIDE, ON_WALL, OUTSIDE }

    /** Where the egress starts, in route order. */
    enum EgressStep { APPROACH, DOOR, EXIT }

    record Cell(int x, int z) {}

    /**
     * Aligned door route: all three cells lie on the door normal through the build center.
     * {@code exitCandidates} are ordered by preference and are all strictly outside the footprint.
     */
    record Waypoints(Cell approach, Cell door, List<Cell> exitCandidates) {}

    /** How far past the door the preferred exit sits. Equals PERIMETER_RING_OFFSET, so it lands on the ring. */
    static final int EXIT_DISTANCE = 2;

    /** How far off the door axis a fallback exit cell may be. */
    static final int EXIT_MAX_LATERAL = 2;

    /** Consecutive failed egresses after which one hovel build stops trying the door route (Fortify's cap). */
    static final int MAX_CONSECUTIVE_EGRESS_FAILURES = 2;

    private HovelEgressGeometry() {
    }

    /** Boundary-aware zone. A radius of zero or less means there is no footprint: everything is OUTSIDE. */
    static FootprintZone classify(int x, int z, int centerX, int centerZ, int radius) {
        if (radius <= 0) return FootprintZone.OUTSIDE;
        int m = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
        if (m < radius) return FootprintZone.INSIDE;
        if (m == radius) return FootprintZone.ON_WALL;
        return FootprintZone.OUTSIDE;
    }

    /**
     * Whether a move from the bot's cell to the target must go out through the doorway first.
     *
     * <ul>
     *   <li>Bot OUTSIDE, or target strictly INSIDE: no.</li>
     *   <li>Bot and target within one step of each other (Chebyshev ≤ 1): no. A single step cannot cross
     *       a one-block wall from strictly inside to strictly outside, and without this a bot already on
     *       its wall-line destination would walk the whole door route to reach its own cell.</li>
     *   <li>Bot ON_WALL but more than one block above or below floor stand level: no. It is on top of the
     *       wall (roof walk, pillar), not behind it.</li>
     *   <li>Otherwise yes: bot INSIDE or ON_WALL, target ON_WALL or OUTSIDE.</li>
     * </ul>
     */
    static boolean needsDoorEgress(FootprintZone botZone,
                                   FootprintZone targetZone,
                                   int botX, int botZ, int botFeetY,
                                   int targetX, int targetZ,
                                   int floorStandY) {
        if (botZone == null || targetZone == null) return false;
        if (botZone == FootprintZone.OUTSIDE) return false;
        if (targetZone == FootprintZone.INSIDE) return false;
        if (Math.max(Math.abs(botX - targetX), Math.abs(botZ - targetZ)) <= 1) return false;
        if (botZone == FootprintZone.ON_WALL && Math.abs(botFeetY - floorStandY) > 1) return false;
        return true;
    }

    /**
     * What the exterior-access guard returns when a door egress fails. Before 1.1.218 the guard ran the
     * egress only for a strictly INSIDE bot heading to a strictly OUTSIDE target and returned its result;
     * every other pair (a wall-line bot or a wall-line target) went straight on to the normal move. A failed
     * egress keeps that: proceed unless the pair is INSIDE to OUTSIDE. For INSIDE to OUTSIDE the old egress
     * judged success by the exit move alone, so a failure where that move reported arrival
     * ({@code moverSucceeded}, the {@code not-outside} step) proceeds too.
     */
    static boolean proceedAfterEgressFailure(FootprintZone botZone, FootprintZone targetZone, boolean moverSucceeded) {
        return moverSucceeded || botZone != FootprintZone.INSIDE || targetZone != FootprintZone.OUTSIDE;
    }

    /**
     * Whether an egress attempt counts toward {@link #MAX_CONSECUTIVE_EGRESS_FAILURES}. An abort
     * ({@code /bot stop}) during the walk is not a routing failure.
     */
    static boolean countsTowardEgressCap(boolean ok, boolean aborted) {
        return !ok && !aborted;
    }

    /**
     * Door route cells for a door on the side whose unit normal is ({@code normalX}, {@code normalZ}).
     *
     * <ul>
     *   <li>approach: {@code max(0, radius - 2)} out from the center, i.e. two cells inside the door
     *       (the center for radius 2);</li>
     *   <li>door: the doorway gap cell, {@code radius} out;</li>
     *   <li>exit candidates: {@code radius + 2} (the perimeter ring), then {@code radius + 1} (the
     *       reserved outside-front cell), then {@code radius + 3}. For a square footprint every cell past
     *       the wall line is already outside, so no hull-style "extend until outside" loop is needed.</li>
     * </ul>
     */
    static Waypoints doorWaypoints(int centerX, int centerZ, int radius, int normalX, int normalZ) {
        requireCardinal(normalX, normalZ);
        int r = Math.max(1, radius);
        Cell approach = along(centerX, centerZ, normalX, normalZ, approachDistance(r));
        Cell door = along(centerX, centerZ, normalX, normalZ, r);
        List<Cell> exits = List.of(
                along(centerX, centerZ, normalX, normalZ, r + EXIT_DISTANCE),
                along(centerX, centerZ, normalX, normalZ, r + 1),
                along(centerX, centerZ, normalX, normalZ, r + 3)
        );
        return new Waypoints(approach, door, exits);
    }

    /**
     * Where to start the route from the bot's current cell.
     *
     * <ul>
     *   <li>In the doorway, or past it on the door axis: EXIT.</li>
     *   <li>On the door axis between the approach cell and the door: DOOR (already lined up and within
     *       reach of the doorway, so the gap gets cleared before the door leg).</li>
     *   <li>Anywhere else, including the far side of the room on the axis: APPROACH first, so the door
     *       leg is taken head-on from close range rather than diagonally or from across the room.</li>
     * </ul>
     */
    static EgressStep firstStep(int botX, int botZ, int centerX, int centerZ, int radius, int normalX, int normalZ) {
        requireCardinal(normalX, normalZ);
        int r = Math.max(1, radius);
        int dx = botX - centerX;
        int dz = botZ - centerZ;
        int alongNormal = dx * normalX + dz * normalZ;
        int lateral = dx * -normalZ + dz * normalX;
        if (lateral != 0) return EgressStep.APPROACH;
        if (alongNormal >= r) return EgressStep.EXIT;
        if (alongNormal >= approachDistance(r)) return EgressStep.DOOR;
        return EgressStep.APPROACH;
    }

    /**
     * Fallback filter for the exit waypoint: in front of the door wall (at least one cell past it along the
     * normal) and within {@link #EXIT_MAX_LATERAL} of the door axis. A cell around the corner, or far along
     * the wall face, would make the door-to-exit leg run sideways into the wall beside the doorway.
     */
    static boolean isInFrontOfDoor(int x, int z, int centerX, int centerZ, int radius, int normalX, int normalZ) {
        requireCardinal(normalX, normalZ);
        int dx = x - centerX;
        int dz = z - centerZ;
        int alongNormal = dx * normalX + dz * normalZ;
        int lateral = dx * -normalZ + dz * normalX;
        return alongNormal >= Math.max(1, radius) + 1 && Math.abs(lateral) <= EXIT_MAX_LATERAL;
    }

    /** Vertical offsets to try at a fixed column, nearest first, downward before upward: 0, -1, +1, -2, +2... */
    static int[] verticalScanOffsets(int maxDy) {
        int n = Math.max(0, maxDy);
        int[] out = new int[1 + 2 * n];
        out[0] = 0;
        for (int i = 1; i <= n; i++) {
            out[2 * i - 1] = -i;
            out[2 * i] = i;
        }
        return out;
    }

    /** Approach cell distance from the center along the door normal: two cells inside the door, at least the center. */
    private static int approachDistance(int radius) {
        return Math.max(0, radius - 2);
    }

    private static Cell along(int centerX, int centerZ, int normalX, int normalZ, int distance) {
        return new Cell(centerX + normalX * distance, centerZ + normalZ * distance);
    }

    private static void requireCardinal(int normalX, int normalZ) {
        if (Math.abs(normalX) + Math.abs(normalZ) != 1) {
            throw new IllegalArgumentException("door normal must be a horizontal unit vector: " + normalX + "," + normalZ);
        }
    }
}
