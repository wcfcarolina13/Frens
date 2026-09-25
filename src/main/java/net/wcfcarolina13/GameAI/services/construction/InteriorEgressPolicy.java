package net.wcfcarolina13.GameAI.services.construction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure geometry for leaving a construction footprint through its doorway instead of
 * pathing into a wall. Port of the FortifyVillageSkill gate-routing decision
 * (inside approach -> opening -> exit, aligned on the opening's outward normal) onto an
 * axis-aligned footprint. No Minecraft types: cells are (x, z) ints, columns are packed
 * longs ({@link #packColumn(int, int)}).
 *
 * <p>"Inside" is strict: a cell on the footprint boundary counts as OUTSIDE, same rule as
 * Fortify's {@code pointStrictlyInsideHull}. Boundary cells are wall cells, so a bot heading
 * for one from the interior still has to get past the wall line.</p>
 *
 * <p>The footprint is taken per walking band (planned blocks at feet Y and head Y), not from
 * the whole planned volume, so a floor that extends past the walls (small_hut's entrance
 * platform one level down) does not push the wall line into the "strict interior", and a bot
 * standing above the walls sees no footprint at all.</p>
 */
public final class InteriorEgressPolicy {

    /** Deepest the aligned inside approach may sit (Fortify gate routing uses 3). */
    public static final int MAX_APPROACH_DEPTH = 3;
    /** Exit distance beyond the opening, along the outward normal. */
    public static final int EXIT_DISTANCE = 2;
    private static final int MAX_EXIT_DISTANCE = 64;
    /** Deepest drop below the bot's floor an egress waypoint may have (3+ hurts; off a tower it is a fall). */
    public static final int MAX_EGRESS_DROP = 2;
    /** Ground Y for a column with nothing to stand on within {@link #MAX_EGRESS_DROP}. */
    public static final int NO_GROUND = Integer.MIN_VALUE;

    private InteriorEgressPolicy() {}

    public record Cell(int x, int z) {}

    /** Inclusive axis-aligned XZ bounds. */
    public record Footprint(int minX, int minZ, int maxX, int maxZ) {
        public Footprint {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("empty footprint " + minX + "," + minZ + " -> " + maxX + "," + maxZ);
            }
        }

        /** Boundary counts as outside. */
        public boolean strictlyInside(int x, int z) {
            return x > minX && x < maxX && z > minZ && z < maxZ;
        }

        /** Inclusive containment (boundary counts as inside). */
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean onBoundary(int x, int z) {
            return contains(x, z) && !strictlyInside(x, z);
        }

        public boolean hasInterior() {
            return maxX - minX >= 2 && maxZ - minZ >= 2;
        }

        public String summary() {
            return minX + "," + minZ + " -> " + maxX + "," + maxZ;
        }
    }

    public enum Source {
        /** A planned door block (left as air until DoorPlacementService runs post-build). */
        DOOR_BLOCK,
        /** A perimeter column the schematic leaves empty at walking height. */
        DESIGNED_GAP,
        /** A planned wall column that is not built yet (open in the world right now). */
        UNBUILT_GAP
    }

    /** One walkable perimeter column plus its unit outward normal (NORTH = 0,-1, EAST = 1,0). */
    public record Doorway(int x, int z, int outDx, int outDz, Source source) {
        public Doorway {
            if (Math.abs(outDx) + Math.abs(outDz) != 1) {
                throw new IllegalArgumentException("outward normal must be a unit axis vector: " + outDx + "," + outDz);
            }
        }
    }

    /**
     * Three aligned waypoints through one doorway. {@code approachSkippable} is true when the
     * bot already stands on the doorway axis inside the clear corridor, so a straight walk to
     * the opening cannot clip the door frame.
     */
    public record Route(Doorway doorway, Cell approach, Cell opening, Cell exit, boolean approachSkippable) {
        public List<Cell> waypoints() {
            return List.of(approach, opening, exit);
        }
    }

    /** Planned columns in one walking band (feet Y and feet Y + 1). */
    public record Band(Footprint footprint, Set<Long> blockedColumns, Set<Long> doorColumns) {
        public boolean isBlocked(int x, int z) {
            return blockedColumns.contains(packColumn(x, z));
        }

        /** Door blocks and schematic gaps on the perimeter; empty when there is no footprint. */
        public List<Doorway> designedDoorways() {
            return footprint == null ? List.of() : deriveDoorways(footprint, blockedColumns, doorColumns);
        }
    }

    /**
     * Planned cells of one construction, indexed per Y so a band lookup only touches two layers.
     * Door cells are kept apart from solid cells: doors stay air during the build, so a door
     * column is walkable.
     */
    public static final class Layout {
        private final Map<Integer, Set<Long>> solidByY;
        private final Map<Integer, Set<Long>> doorByY;

        private Layout(Map<Integer, Set<Long>> solidByY, Map<Integer, Set<Long>> doorByY) {
            this.solidByY = solidByY;
            this.doorByY = doorByY;
        }

        public static Builder builder() {
            return new Builder();
        }

        public boolean isEmpty() {
            return solidByY.isEmpty() && doorByY.isEmpty();
        }

        /**
         * The walking band at {@code feetY}: a column is blocked when a non-door block is planned
         * at feet or head height. Footprint = XZ bounds of every planned column in the band, or
         * null when nothing is planned there (bot above the walls, or below the structure).
         */
        public Band bandAt(int feetY) {
            Set<Long> blocked = new HashSet<>();
            Set<Long> doors = new HashSet<>();
            for (int y = feetY; y <= feetY + 1; y++) {
                blocked.addAll(solidByY.getOrDefault(y, Set.of()));
                doors.addAll(doorByY.getOrDefault(y, Set.of()));
            }
            doors.removeAll(blocked);
            Footprint footprint = boundsOf(blocked, doors);
            return new Band(footprint, Set.copyOf(blocked), Set.copyOf(doors));
        }

        public static final class Builder {
            private final Map<Integer, Set<Long>> solidByY = new HashMap<>();
            private final Map<Integer, Set<Long>> doorByY = new HashMap<>();

            public Builder solid(int x, int y, int z) {
                solidByY.computeIfAbsent(y, k -> new HashSet<>()).add(packColumn(x, z));
                return this;
            }

            public Builder door(int x, int y, int z) {
                doorByY.computeIfAbsent(y, k -> new HashSet<>()).add(packColumn(x, z));
                return this;
            }

            public Layout build() {
                return new Layout(freeze(solidByY), freeze(doorByY));
            }

            private static Map<Integer, Set<Long>> freeze(Map<Integer, Set<Long>> byY) {
                Map<Integer, Set<Long>> copy = new HashMap<>();
                byY.forEach((y, cols) -> copy.put(y, Set.copyOf(cols)));
                return Map.copyOf(copy);
            }
        }
    }

    public static long packColumn(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long column) {
        return (int) (column >> 32);
    }

    public static int unpackZ(long column) {
        return (int) column;
    }

    /** Bot strictly inside, target not strictly inside (boundary = outside). */
    public static boolean needsEgress(Footprint footprint, int botX, int botZ, int targetX, int targetZ) {
        return footprint != null
                && footprint.strictlyInside(botX, botZ)
                && !footprint.strictlyInside(targetX, targetZ);
    }

    /**
     * Egress for a move whose requested target and resolved stance may differ. Both must be
     * outside the strict interior: a wall-top stance picked for an interior target is reached
     * from inside, and an interior stance picked for a wall target needs no exit. A stance one
     * step from the bot (Chebyshev distance 1, e.g. the doorway cell beside it) is a local step,
     * not a crossing, same exemption as the hovel's.
     */
    public static boolean needsEgress(Footprint footprint,
                                      int botX, int botZ,
                                      int targetX, int targetZ,
                                      int stanceX, int stanceZ) {
        return needsEgress(footprint, botX, botZ, targetX, targetZ)
                && !footprint.strictlyInside(stanceX, stanceZ)
                && Math.max(Math.abs(stanceX - botX), Math.abs(stanceZ - botZ)) > 1;
    }

    /**
     * A straight-line nudge that would carry the bot from the strict interior across the
     * footprint edge. Adjacent steps (Chebyshev distance 1) are exempt: stepping into a door
     * cell or onto a one-high wall is a local move, not a walk into the wall.
     */
    public static boolean isLongCrossing(Footprint footprint, int botX, int botZ, int targetX, int targetZ) {
        if (!needsEgress(footprint, botX, botZ, targetX, targetZ)) {
            return false;
        }
        return Math.max(Math.abs(targetX - botX), Math.abs(targetZ - botZ)) > 1;
    }

    /**
     * Unbuilt wall columns as last-resort doorways: non-corner boundary columns that the plan
     * fills ({@code plannedBlocked}) but that are open right now ({@code nowBlocked} excludes
     * them). Used only when no designed doorway yields a route.
     */
    public static List<Doorway> unbuiltGaps(Footprint footprint, Set<Long> plannedBlocked, Set<Long> nowBlocked) {
        if (footprint == null || !footprint.hasInterior() || plannedBlocked == null) {
            return List.of();
        }
        Set<Long> now = nowBlocked == null ? Set.of() : nowBlocked;
        List<Doorway> gaps = new ArrayList<>();
        for (Doorway candidate : deriveDoorways(footprint, Set.of(), Set.of())) {
            long column = packColumn(candidate.x(), candidate.z());
            if (plannedBlocked.contains(column) && !now.contains(column)) {
                gaps.add(new Doorway(candidate.x(), candidate.z(), candidate.outDx(), candidate.outDz(), Source.UNBUILT_GAP));
            }
        }
        return List.copyOf(gaps);
    }

    /**
     * Walkable perimeter columns of the band: non-corner boundary columns with nothing solid
     * planned at feet or head height. Outward normal comes from the edge the column sits on.
     * Corners are skipped (no single normal). Returns empty when the footprint has no interior.
     */
    public static List<Doorway> deriveDoorways(Footprint footprint, Set<Long> blockedColumns, Set<Long> doorColumns) {
        if (footprint == null || !footprint.hasInterior()) {
            return List.of();
        }
        Set<Long> blocked = blockedColumns == null ? Set.of() : blockedColumns;
        Set<Long> doors = doorColumns == null ? Set.of() : doorColumns;
        List<Doorway> doorways = new ArrayList<>();
        for (int x = footprint.minX() + 1; x < footprint.maxX(); x++) {
            addIfOpen(doorways, x, footprint.minZ(), 0, -1, blocked, doors);
            addIfOpen(doorways, x, footprint.maxZ(), 0, 1, blocked, doors);
        }
        for (int z = footprint.minZ() + 1; z < footprint.maxZ(); z++) {
            addIfOpen(doorways, footprint.minX(), z, -1, 0, blocked, doors);
            addIfOpen(doorways, footprint.maxX(), z, 1, 0, blocked, doors);
        }
        return List.copyOf(doorways);
    }

    private static void addIfOpen(List<Doorway> out, int x, int z, int outDx, int outDz,
                                  Set<Long> blocked, Set<Long> doors) {
        long column = packColumn(x, z);
        if (blocked.contains(column)) {
            return;
        }
        out.add(new Doorway(x, z, outDx, outDz, doors.contains(column) ? Source.DOOR_BLOCK : Source.DESIGNED_GAP));
    }

    /**
     * Aligned waypoints through {@code doorway}. The inside approach sits on the doorway axis at
     * the bot's own depth, clamped to [1, clear corridor depth <= {@link #MAX_APPROACH_DEPTH}],
     * so a bot next to the door sidesteps onto the axis and a bot deep inside lines up ~3 in.
     * The exit sits {@link #EXIT_DISTANCE} beyond the opening, extended until it is clear of the
     * footprint. Empty when the cell just inside the opening is blocked or not interior.
     *
     * <p>{@code blockedColumns} = columns the bot cannot walk through at this height. The runtime
     * caller passes live world obstacles (a planned floor that is not built yet is no obstacle);
     * the planned band is the fallback when no world is at hand.</p>
     */
    public static Optional<Route> route(Footprint footprint, Doorway doorway, Set<Long> blockedColumns,
                                        int botX, int botZ) {
        if (footprint == null || doorway == null) {
            return Optional.empty();
        }
        Set<Long> blocked = blockedColumns == null ? Set.of() : blockedColumns;
        int inDx = -doorway.outDx();
        int inDz = -doorway.outDz();

        int clearDepth = 0;
        for (int k = 1; k <= MAX_APPROACH_DEPTH; k++) {
            int cx = doorway.x() + inDx * k;
            int cz = doorway.z() + inDz * k;
            if (!footprint.strictlyInside(cx, cz) || blocked.contains(packColumn(cx, cz))) {
                break;
            }
            clearDepth = k;
        }
        if (clearDepth == 0) {
            return Optional.empty();
        }

        int relX = botX - doorway.x();
        int relZ = botZ - doorway.z();
        int botDepth = relX * inDx + relZ * inDz;
        int lateral = inDx != 0 ? relZ : relX;
        int depth = Math.max(1, Math.min(clearDepth, botDepth));
        Cell approach = new Cell(doorway.x() + inDx * depth, doorway.z() + inDz * depth);
        Cell opening = new Cell(doorway.x(), doorway.z());

        int exitDistance = EXIT_DISTANCE;
        while (footprint.contains(doorway.x() + doorway.outDx() * exitDistance,
                doorway.z() + doorway.outDz() * exitDistance)) {
            exitDistance++;
            if (exitDistance > MAX_EXIT_DISTANCE) {
                return Optional.empty();
            }
        }
        Cell exit = new Cell(doorway.x() + doorway.outDx() * exitDistance,
                doorway.z() + doorway.outDz() * exitDistance);

        boolean skippable = lateral == 0 && botDepth >= 1 && botDepth <= clearDepth;
        return Optional.of(new Route(doorway, approach, opening, exit, skippable));
    }

    /**
     * Picks the doorway minimising the walk bot -> doorway -> target (Manhattan). Nearest-to-bot
     * alone can send the bot out the far side of the building from its target; nearest-to-target
     * alone can pick a door across the room. The sum is the trip the bot actually makes, and with
     * one door (the usual case) it changes nothing. Ties keep input order. Doorways whose
     * corridor is blocked are skipped.
     */
    public static Optional<Route> chooseRoute(Footprint footprint,
                                              List<Doorway> doorways,
                                              Set<Long> blockedColumns,
                                              int botX, int botZ,
                                              int targetX, int targetZ) {
        return chooseRoute(footprint, doorways, blockedColumns, botX, botZ, targetX, targetZ, c -> true);
    }

    /**
     * {@link #chooseRoute(Footprint, List, Set, int, int, int, int)} with a footing check: a
     * doorway is used only if {@link #withSafeExit} finds standable ground under its opening and
     * on the way out ({@code standable} is the caller's per-column ground test, see
     * {@link #groundAcceptable}). On a raised floor a missing railing reads as a doorway; this
     * keeps the egress from walking the bot off the edge.
     */
    public static Optional<Route> chooseRoute(Footprint footprint,
                                              List<Doorway> doorways,
                                              Set<Long> blockedColumns,
                                              int botX, int botZ,
                                              int targetX, int targetZ,
                                              Predicate<Cell> standable) {
        if (footprint == null || doorways == null || doorways.isEmpty()) {
            return Optional.empty();
        }
        Predicate<Cell> ground = standable == null ? c -> true : standable;
        List<Doorway> ordered = new ArrayList<>(doorways);
        ordered.sort(Comparator.comparingInt(d -> tripCost(d, botX, botZ, targetX, targetZ)));
        for (Doorway doorway : ordered) {
            Optional<Route> route = route(footprint, doorway, blockedColumns, botX, botZ)
                    .flatMap(r -> withSafeExit(r, ground));
            if (route.isPresent()) {
                return route;
            }
        }
        return Optional.empty();
    }

    /**
     * Ground under a cell is acceptable for the egress walk when its top is level with the bot's
     * floor or at most {@link #MAX_EGRESS_DROP} blocks below it. {@code groundY} is the Y of the
     * supporting block (anything with a collision shape, walkable partials included), or
     * {@link #NO_GROUND} when nothing supports the cell within that drop.
     */
    public static boolean groundAcceptable(int botFeetY, int groundY) {
        if (groundY == NO_GROUND) {
            return false;
        }
        int drop = botFeetY - 1 - groundY;
        return drop >= 0 && drop <= MAX_EGRESS_DROP;
    }

    /** Both the opening and the exit have acceptable ground ({@link #groundAcceptable}). */
    public static boolean exitAcceptable(int botFeetY, int openingGroundY, int exitGroundY) {
        return groundAcceptable(botFeetY, openingGroundY) && groundAcceptable(botFeetY, exitGroundY);
    }

    /**
     * The route with its exit pulled in to the farthest cell (at least one past the opening, at
     * most the route's own exit) that is reachable over standable cells only; empty when the
     * opening itself or the first cell past it fails. Cells are checked contiguously so the walk
     * never crosses a hole to reach safe ground beyond it.
     */
    public static Optional<Route> withSafeExit(Route route, Predicate<Cell> standable) {
        if (route == null || standable == null) {
            return Optional.ofNullable(route);
        }
        if (!standable.test(route.opening())) {
            return Optional.empty();
        }
        Doorway d = route.doorway();
        int exitDistance = (route.exit().x() - route.opening().x()) * d.outDx()
                + (route.exit().z() - route.opening().z()) * d.outDz();
        return farthestStandable(route.opening(), d.outDx(), d.outDz(), exitDistance, standable)
                .map(exit -> new Route(d, route.approach(), route.opening(), exit, route.approachSkippable()));
    }

    /**
     * Farthest cell 1..{@code maxDistance} steps from {@code from} along ({@code dx}, {@code dz})
     * such that every cell from step 1 up to it passes {@code standable}; empty when step 1 fails.
     */
    public static Optional<Cell> farthestStandable(Cell from, int dx, int dz, int maxDistance, Predicate<Cell> standable) {
        if (from == null || standable == null) {
            return Optional.empty();
        }
        Cell best = null;
        for (int k = 1; k <= maxDistance; k++) {
            Cell c = new Cell(from.x() + dx * k, from.z() + dz * k);
            if (!standable.test(c)) {
                break;
            }
            best = c;
        }
        return Optional.ofNullable(best);
    }

    static int tripCost(Doorway doorway, int botX, int botZ, int targetX, int targetZ) {
        return Math.abs(doorway.x() - botX) + Math.abs(doorway.z() - botZ)
                + Math.abs(targetX - doorway.x()) + Math.abs(targetZ - doorway.z());
    }

    private static Footprint boundsOf(Set<Long> a, Set<Long> b) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (Set<Long> set : List.of(a, b)) {
            for (long column : set) {
                int x = unpackX(column);
                int z = unpackZ(column);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
                any = true;
            }
        }
        return any ? new Footprint(minX, minZ, maxX, maxZ) : null;
    }
}
