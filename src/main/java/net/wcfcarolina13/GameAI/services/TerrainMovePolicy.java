package net.wcfcarolina13.GameAI.services;

import java.util.Locale;

/**
 * Costed choice for a bot that is stuck facing a rise toward its goal (1.1.240, Bradley:
 * "breaking blocks, scaffolding, and simply walking around are all options a human player would
 * use to reach its destination with little effort here").
 *
 * <p>Moves and costs (in rough "blocks of effort"):</p>
 * <ul>
 *   <li>{@link Move#WALK_AROUND}: a local detour exists; cost = its extra blocks (at least 1). Only a
 *       BFS escape route that leaves the trap, or a directive, is a detour ({@link #detourCounts}); the
 *       1-hop flat fallback never is (1.1.240 C1: it left Jake facing the same wall). Without a detour,
 *       an open (non-trap) cell with an adjacent sidestep walks at {@link #SIDESTEP_COST}, which no
 *       BREAK beats (1.1.240 I3: walk around soft terrain in open country, do not dig).</li>
 *   <li>{@link Move#BREAK}: mine the blocking cells. A 2-rise needs only its head-height cell (the
 *       rise becomes a 1-block stair), a 3+ rise needs the foot and head cells. Cost =
 *       sum(ground break ticks) / 20 + 2 per cell. Every cell must pass territory/protection and
 *       {@link ObstructionMinePolicy} (each cell at most {@link ObstructionMinePolicy#MAX_GROUND_BREAK_TICKS}),
 *       so stone by hand is never chosen.</li>
 *   <li>{@link Move#PLACE}: one scaffold block under the bot turns a 2-rise into a stair; cost
 *       {@link #PLACE_COST}. Only for a rise of exactly 2.</li>
 * </ul>
 * <p>The cheapest applicable move wins. Ties go to WALK_AROUND, then BREAK (spends no inventory).
 * Rise below 2 is not a terrain problem: WALK_AROUND when a detour exists, else NONE.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class TerrainMovePolicy {

    public static final double PLACE_COST = 3.0D;
    public static final double BREAK_COST_PER_CELL = 2.0D;
    public static final double TICKS_PER_COST_UNIT = 20.0D;
    /** Fixed walk cost of a sidestep from an open cell; BREAK costs at least this, and ties go to walking. */
    public static final double SIDESTEP_COST = 2.0D;

    public enum Move { WALK_AROUND, BREAK, PLACE, NONE }

    /**
     * @param rise                 solid cells stacked in front of the bot from foot level (4 means 4 or more)
     * @param blockingCells        cells BREAK has to mine (0 when BREAK is not possible)
     * @param breakTicksSum        sum of on-ground break ticks of those cells
     * @param maxCellBreakTicks    slowest single cell (Integer.MAX_VALUE when any is unbreakable)
     * @param allBlockingCellsBreakAllowed territory + protection + hazards allow every cell
     * @param scaffoldCount        cheap scaffold blocks in inventory
     * @param placeAllowed         territory/protection allow a block under the bot and the stair top is clear
     * @param hasDetourCandidate   a local walking detour exists and is not a remembered failure
     * @param detourExtraBlocks    blocks that detour walks
     * @param trapLikeCell         the bot's cell is not open (sidesteps do not count there)
     * @param sidestepAvailable    an adjacent sidestep exists that is not a remembered failure
     */
    public record Inputs(int rise,
                         int blockingCells,
                         long breakTicksSum,
                         int maxCellBreakTicks,
                         boolean allBlockingCellsBreakAllowed,
                         int scaffoldCount,
                         boolean placeAllowed,
                         boolean hasDetourCandidate,
                         int detourExtraBlocks,
                         boolean trapLikeCell,
                         boolean sidestepAvailable) {

        /** A trap-like cell with no sidestep: only the detour counts for walking. */
        public Inputs(int rise, int blockingCells, long breakTicksSum, int maxCellBreakTicks,
                      boolean allBlockingCellsBreakAllowed, int scaffoldCount, boolean placeAllowed,
                      boolean hasDetourCandidate, int detourExtraBlocks) {
            this(rise, blockingCells, breakTicksSum, maxCellBreakTicks, allBlockingCellsBreakAllowed,
                    scaffoldCount, placeAllowed, hasDetourCandidate, detourExtraBlocks, true, false);
        }
    }

    public record Decision(Move move, double cost, double walkCost, double breakCost, double placeCost) {
        /** Cost formatted for logs: "-" when a move does not apply. */
        public static String fmt(double cost) {
            return Double.isInfinite(cost) ? "-" : String.format(Locale.ROOT, "%.2f", cost);
        }
    }

    private TerrainMovePolicy() {}

    /** Cells BREAK must mine for a rise: 0 below 2, 1 for a 2-rise, 2 (foot and head) for 3+. */
    public static int cellsToBreak(int rise) {
        if (rise < 2) {
            return 0;
        }
        return rise == 2 ? 1 : 2;
    }

    /**
     * Whether a local-escape candidate counts as a WALK_AROUND detour: a directive, or a BFS route
     * candidate that leaves the trap. The 1-hop flat/step fallback never counts, even when it reports
     * leavesTrap (it does whenever the neighbour has more open sides).
     */
    public static boolean detourCounts(boolean searchRoute, boolean leavesTrap, boolean directive) {
        return directive || (searchRoute && leavesTrap);
    }

    public static double walkCost(Inputs in) {
        if (in.hasDetourCandidate()) {
            return Math.max(1, in.detourExtraBlocks());
        }
        if (!in.trapLikeCell() && in.sidestepAvailable()) {
            return SIDESTEP_COST;
        }
        return Double.POSITIVE_INFINITY;
    }

    public static double breakCost(Inputs in) {
        if (in.rise() < 2 || in.blockingCells() <= 0 || !in.allBlockingCellsBreakAllowed()) {
            return Double.POSITIVE_INFINITY;
        }
        if (in.maxCellBreakTicks() > ObstructionMinePolicy.MAX_GROUND_BREAK_TICKS || in.breakTicksSum() < 0) {
            return Double.POSITIVE_INFINITY;
        }
        return in.breakTicksSum() / TICKS_PER_COST_UNIT + BREAK_COST_PER_CELL * in.blockingCells();
    }

    public static double placeCost(Inputs in) {
        if (in.rise() != 2 || in.scaffoldCount() <= 0 || !in.placeAllowed()) {
            return Double.POSITIVE_INFINITY;
        }
        return PLACE_COST;
    }

    public static Decision choose(Inputs in) {
        double walk = walkCost(in);
        double brk = in.rise() >= 2 ? breakCost(in) : Double.POSITIVE_INFINITY;
        double place = in.rise() >= 2 ? placeCost(in) : Double.POSITIVE_INFINITY;

        Move move = Move.NONE;
        double best = Double.POSITIVE_INFINITY;
        // Order encodes the tie-break: walk first, then break, then place (strictly cheaper wins).
        if (walk < best) {
            move = Move.WALK_AROUND;
            best = walk;
        }
        if (brk < best) {
            move = Move.BREAK;
            best = brk;
        }
        if (place < best) {
            move = Move.PLACE;
            best = place;
        }
        return new Decision(move, best, walk, brk, place);
    }
}
