package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure rules for the wall-off night shelter in {@code BotFleeService}: find a natural overhang cell
 * with one or two open faces, decide whether the bot really arrived on it, order the seal
 * placements, and decide what the morning teardown may remove.
 *
 * <p>The world is handed in as a {@link Snapshot} built on the server thread: one byte per cell
 * around the bot's feet (the snapshot origin), plus sky visibility and hostile offsets. A cell's
 * code is decided by collision, never {@code isSolidBlock}: {@link #SOLID} means a non-empty
 * collision shape, so slabs and stairs count as footing and cover while carpets, torches and signs
 * ({@link #PARTIAL}) do not.</p>
 *
 * <p>Face offsets follow Minecraft: NORTH is dz -1, SOUTH dz +1, WEST dx -1, EAST dx +1.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class WallOffPolicy {

    /** Air, or a replaceable block with no collision (grass, flowers): free to stand in or seal. */
    public static final byte CLEAR = 0;
    /** Not air, not replaceable, non-empty collision shape: footing, wall or cover. */
    public static final byte SOLID = 1;
    /** Not air, not replaceable, no collision (torch, sign, rail, carpet): neither wall nor footing. */
    public static final byte PARTIAL = 2;
    /** Flowing fluid. */
    public static final byte FLUID = 3;
    /** Lava, fire, cactus, magma, campfire, or still fluid. */
    public static final byte HAZARD = 4;
    /** Clear, but inside a protected zone that refuses this bot a block mutation. */
    public static final byte PROTECTED = 5;
    /** Outside the snapshot box. */
    public static final byte OUT = 6;

    /** Horizontal search radius for stand cells. */
    public static final int SEARCH_RADIUS = 6;
    /** Vertical search range for stand cells, relative to the bot's feet. */
    public static final int SEARCH_DY_MIN = -2;
    public static final int SEARCH_DY_MAX = 2;
    /** Snapshot box the server hop reads: covers every neighbour, support and cover cell. */
    public static final int READ_RADIUS = 7;
    public static final int READ_DY_MIN = -3;
    public static final int READ_DY_MAX = 4;
    /** Placements per shelter, and the minimum seal blocks the bot must carry. */
    public static final int MAX_PLACEMENTS = 4;
    public static final int MIN_BLOCKS = 4;
    /** Candidates tried per attempt. */
    public static final int MAX_CANDIDATES = 3;
    /** Hostile within this squared distance of the stand cell rejects it (assessShelterAt's rule). */
    public static final int HOSTILE_RADIUS_SQ = 16;
    /**
     * Arrival tolerance from the cell centre on each horizontal axis. A player is 0.6 wide (half
     * 0.3) and {@code Box.intersects} is strict, so the bounding box stays out of the adjacent seal
     * cell only while the offset is at most 0.2; 0.18 keeps a margin for float noise. At 0.3 a bot
     * standing toward an open face gets {@code bot-intersects-target} from the placement.
     */
    public static final double CENTRE_TOLERANCE = 0.18D;
    /** Health lost during the walk that aborts the step. */
    public static final float ABORT_HP_DROP = 4.0F;

    private WallOffPolicy() {}

    public enum Face {
        NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

        public final int dx;
        public final int dz;

        Face(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    public enum Arrival { ARRIVED, OFF_CELL, OFF_CENTRE, AIRBORNE }

    public record Cell(int dx, int dy, int dz) {
        public Cell offset(int x, int y, int z) {
            return new Cell(dx + x, dy + y, dz + z);
        }
    }

    public record Candidate(Cell feet, List<Cell> placements, int openFaces, Face primaryFace, int distSq) {
        public Candidate {
            placements = List.copyOf(placements);
        }
    }

    /**
     * Cells in a box of {@code (2*radius+1)^2 * (dyMax-dyMin+1)}, indexed
     * {@code ((dy-dyMin)*side + (dz+radius))*side + (dx+radius)}. {@code hostileOffsets} holds
     * {dx, dy, dz} block offsets of live hostiles from the origin.
     */
    public record Snapshot(int radius, int dyMin, int dyMax, byte[] cells, boolean[] sky,
                           int[][] hostileOffsets, int blocks, boolean hotbarHasSealBlock) {

        public int side() {
            return radius * 2 + 1;
        }

        public static int volume(int radius, int dyMin, int dyMax) {
            int side = radius * 2 + 1;
            return side * side * (dyMax - dyMin + 1);
        }

        public static int index(int radius, int dyMin, int dx, int dy, int dz) {
            int side = radius * 2 + 1;
            return ((dy - dyMin) * side + (dz + radius)) * side + (dx + radius);
        }

        public boolean inside(int dx, int dy, int dz) {
            return Math.abs(dx) <= radius && Math.abs(dz) <= radius && dy >= dyMin && dy <= dyMax;
        }

        public byte cell(int dx, int dy, int dz) {
            if (!inside(dx, dy, dz)) {
                return OUT;
            }
            return cells[index(radius, dyMin, dx, dy, dz)];
        }

        /** Out-of-box cells read as sky-visible, so they never pass a cover rule. */
        public boolean sky(int dx, int dy, int dz) {
            if (!inside(dx, dy, dz)) {
                return true;
            }
            return sky[index(radius, dyMin, dx, dy, dz)];
        }
    }

    /** Seal-family blocks the bot carries (item id to count), restricted to {@code sealIds}. */
    public static int countSealable(Map<String, Integer> countsByItemId, Set<String> sealIds) {
        if (countsByItemId == null || sealIds == null) {
            return 0;
        }
        int total = 0;
        for (Map.Entry<String, Integer> e : countsByItemId.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && sealIds.contains(e.getKey())) {
                total += Math.max(0, e.getValue());
            }
        }
        return total;
    }

    /**
     * Stand cells the bot can wall off, fewest placements first, then nearest; at most
     * {@link #MAX_CANDIDATES}. A candidate never needs more than {@code min(maxPlacements, blocks)}
     * placements.
     */
    public static List<Candidate> rank(Snapshot s, int maxPlacements) {
        List<Candidate> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        int budget = Math.min(maxPlacements, s.blocks());
        if (budget <= 0) {
            return out;
        }
        for (int dy = SEARCH_DY_MIN; dy <= SEARCH_DY_MAX; dy++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
                    Candidate c = evaluate(s, dx, dy, dz, budget);
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt((Candidate c) -> c.placements().size())
                .thenComparingInt(Candidate::distSq));
        return out.size() > MAX_CANDIDATES ? new ArrayList<>(out.subList(0, MAX_CANDIDATES)) : out;
    }

    private static Candidate evaluate(Snapshot s, int x, int y, int z, int budget) {
        // 8. The whole neighbourhood must be inside the box.
        if (!s.inside(x - 1, y - 1, z - 1) || !s.inside(x + 1, y + 2, z + 1)) {
            return null;
        }
        // 1. Footing, two clear cells, cover.
        if (s.cell(x, y - 1, z) != SOLID
                || s.cell(x, y, z) != CLEAR
                || s.cell(x, y + 1, z) != CLEAR
                || s.cell(x, y + 2, z) != SOLID) {
            return null;
        }
        // 2. No sky at feet, head, or cover.
        if (s.sky(x, y, z) || s.sky(x, y + 1, z) || s.sky(x, y + 2, z)) {
            return null;
        }
        // 3. No fluid or hazard in the cell, the floor, or any feet-level neighbour.
        if (isWet(s.cell(x, y - 1, z))) {
            return null;
        }
        for (Face f : Face.values()) {
            if (isWet(s.cell(x + f.dx, y, z + f.dz))) {
                return null;
            }
        }
        // 4-6. Faces at feet AND head level.
        Map<Face, List<Cell>> openFaces = new EnumMap<>(Face.class);
        for (Face f : Face.values()) {
            int nx = x + f.dx;
            int nz = z + f.dz;
            byte feet = s.cell(nx, y, nz);
            byte head = s.cell(nx, y + 1, nz);
            if (feet == SOLID && head == SOLID) {
                continue; // closed
            }
            List<Cell> cells = new ArrayList<>(2);
            if (feet != SOLID) {
                if (feet != CLEAR) {
                    return null; // open face that cannot be sealed
                }
                // 6. The feet seal block needs solid support under it.
                if (s.cell(nx, y - 1, nz) != SOLID) {
                    return null;
                }
                cells.add(new Cell(nx, y, nz));
            }
            if (head != SOLID) {
                if (head != CLEAR) {
                    return null;
                }
                cells.add(new Cell(nx, y + 1, nz));
            }
            openFaces.put(f, cells);
        }
        int open = openFaces.size();
        if (open < 1 || open > 2) {
            return null;
        }
        List<Cell> placements = new ArrayList<>();
        Face primary = null;
        for (Map.Entry<Face, List<Cell>> e : openFaces.entrySet()) {
            placements.addAll(e.getValue());
            if (primary == null || (e.getValue().size() == 2 && openFaces.get(primary).size() < 2)) {
                primary = e.getKey();
            }
        }
        // 5. Placement budget.
        if (placements.size() > budget) {
            return null;
        }
        // 7. No hostile within 4 blocks.
        if (s.hostileOffsets() != null) {
            for (int[] h : s.hostileOffsets()) {
                if (h == null || h.length < 3) {
                    continue;
                }
                int ddx = h[0] - x;
                int ddy = h[1] - y;
                int ddz = h[2] - z;
                if (ddx * ddx + ddy * ddy + ddz * ddz <= HOSTILE_RADIUS_SQ) {
                    return null;
                }
            }
        }
        return new Candidate(new Cell(x, y, z), placements, open, primary, x * x + y * y + z * z);
    }

    private static boolean isWet(byte code) {
        return code == FLUID || code == HAZARD;
    }

    /**
     * Seal order: feet before head within a face (the head block stands on the feet block), the
     * primary face last so the bot's view stays open longest.
     */
    public static List<Cell> placementOrder(Candidate c) {
        List<Cell> first = new ArrayList<>();
        List<Cell> last = new ArrayList<>();
        if (c == null) {
            return first;
        }
        Cell feet = c.feet();
        for (Cell p : c.placements()) {
            boolean primary = c.primaryFace() != null
                    && p.dx() - feet.dx() == c.primaryFace().dx
                    && p.dz() - feet.dz() == c.primaryFace().dz;
            (primary ? last : first).add(p);
        }
        Comparator<Cell> byFaceThenHeight = Comparator.comparingInt((Cell p) -> faceOrdinal(feet, p))
                .thenComparingInt(Cell::dy);
        first.sort(byFaceThenHeight);
        last.sort(byFaceThenHeight);
        first.addAll(last);
        return first;
    }

    private static int faceOrdinal(Cell feet, Cell p) {
        for (Face f : Face.values()) {
            if (p.dx() - feet.dx() == f.dx && p.dz() - feet.dz() == f.dz) {
                return f.ordinal();
            }
        }
        return Face.values().length;
    }

    /**
     * Whether the bot stands on the stand cell, centred within {@link #CENTRE_TOLERANCE} on both
     * horizontal axes, on the ground. Seal cells are adjacent to the stand cell, so a bot straddling
     * the boundary would get a block placed into its bounding box.
     */
    public static Arrival arrival(double x, double y, double z, boolean onGround, int feetX, int feetY, int feetZ) {
        if ((int) Math.floor(x) != feetX || (int) Math.floor(y) != feetY || (int) Math.floor(z) != feetZ) {
            return Arrival.OFF_CELL;
        }
        if (!onGround) {
            return Arrival.AIRBORNE;
        }
        if (Math.abs(x - (feetX + 0.5D)) > CENTRE_TOLERANCE || Math.abs(z - (feetZ + 0.5D)) > CENTRE_TOLERANCE) {
            return Arrival.OFF_CENTRE;
        }
        return Arrival.ARRIVED;
    }

    /**
     * A latched abort with no running task is stale (a {@code /bot stop} latches even without a
     * ticket and nothing clears it for a background shelter thread): clear it before the walk, or
     * it vetoes the night shelter. A latch that belongs to a running task is a real cancellation.
     */
    public static boolean clearStaleLatchAtStart(boolean latched, boolean hasActiveTask) {
        return latched && !hasActiveTask;
    }

    /** The bot is being hit on the way: cliff-dig or dig-down are faster. */
    public static boolean abortWalk(float hpAtStart, float hpNow) {
        return hpAtStart - hpNow >= ABORT_HP_DROP;
    }

    /** Morning teardown only removes a ledger cell that still holds a seal-family block. */
    public static boolean teardownAllowed(String blockItemId, Set<String> sealIds) {
        return blockItemId != null && sealIds != null && sealIds.contains(blockItemId);
    }

    /**
     * Whether an {@code assessShelterAt} rejection tears the fresh wall down. Only a hostile next to
     * a finished wall keeps it (the caller then re-checks the eight wall cells); every other or
     * unknown reason tears down.
     */
    public static boolean teardownOnReject(String reason) {
        return reason == null || !"hostile_exposed".equals(reason.toLowerCase(Locale.ROOT));
    }

    /**
     * The server-tick revalidation of a registered wall-off shelter: keep the entry (and its
     * ledger) when the only complaint is a hostile nearby and all eight side cells still block
     * movement. Dropping it there would leave a sealed wall with no record, so dawn break-free
     * would never run. Any other reason, or a breached wall, drops it as before.
     */
    public static boolean keepOnRevalidate(String reason, boolean entranceSecured) {
        return entranceSecured && !teardownOnReject(reason);
    }
}
