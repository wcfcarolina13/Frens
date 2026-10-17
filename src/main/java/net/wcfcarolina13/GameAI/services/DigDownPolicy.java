package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure rules for the emergency dig-down bunker in {@code BotFleeService}: whether the 3-deep column
 * under the bot may be dug at all, when the dig stops, what happens when the cap fails, and the
 * per-bot "no dig-down here until dawn" marks left after a climb-out.
 *
 * <p>The column is read on the server thread and handed in as one {@link Cell} code per depth (the
 * cell directly under the feet first) plus the floor under the third cell.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class DigDownPolicy {

    private DigDownPolicy() {}

    /** Cells dug under the feet. */
    public static final int DEPTH = 3;
    /** A mark blocks its column and this many blocks around it (Chebyshev). */
    public static final int MARK_RADIUS = 2;
    /** Ticks in a Minecraft day; day N's dawn is time-of-day {@code N * DAY_TICKS}. */
    public static final long DAY_TICKS = 24_000L;

    /**
     * Top-cell blocks that drop a cap block when mined by hand, so a bot with no seal block can
     * still cap: dirt and grass drop dirt. Sand and gravel are not here: they are falling blocks,
     * and the cap sits over the bot's air head cell.
     */
    public static final Set<String> HAND_SEAL_DROPS = Set.of("minecraft:dirt", "minecraft:grass_block");

    /**
     * Horizontal offset from the cell centre at which the 0.6-wide box fits the 1x1 shaft
     * (0.5 - 0.3 = 0.2, with a margin).
     */
    public static final double CENTRE_TOLERANCE = 0.18D;

    /** The bot's x/z are within {@link #CENTRE_TOLERANCE} of the cell's centre. */
    public static boolean centred(double x, double z, int cellX, int cellZ) {
        return Math.abs(x - (cellX + 0.5D)) <= CENTRE_TOLERANCE && Math.abs(z - (cellZ + 0.5D)) <= CENTRE_TOLERANCE;
    }

    /** How deep the feet are below the original feet level; 0 when the bot never fell in. */
    public static int pitDepth(int feetY, int groundFeetY) {
        return Math.max(0, groundFeetY - feetY);
    }

    /** After a failure or interrupt mid-dig: climb out when a cell really opened and nothing settled it. */
    public static boolean climbAfterFailure(int realRemovals, boolean settled) {
        return realRemovals > 0 && !settled;
    }

    /** What the climb-out does about a latched abort (the pillar aborts at step 0 on one). */
    public enum Latch { NONE, CLEAR, BLOCKED }

    /** Clear a latch no running task owns; a task-owned latch leaves the bot in the pit (logged). */
    public static Latch climbLatch(boolean latched, boolean taskOwnsIt) {
        if (!latched) {
            return Latch.NONE;
        }
        return taskOwnsIt ? Latch.BLOCKED : Latch.CLEAR;
    }

    /** Server-thread classification of one column cell. */
    public enum Cell {
        /** Breakable solid block: dig it. */
        DIGGABLE,
        /** Air or an empty-collision block: the bot would fall, not dig (a cave under the feet). */
        AIR,
        /** Any fluid. */
        FLUID,
        /** Negative hardness (bedrock, barrier). */
        UNBREAKABLE,
        /** Never-break block, harvest-protected ore, a claim or a mapped village refuses it. */
        PROTECTED
    }

    /**
     * @param columnMarked a mark from an earlier uncapped dig covers this column
     * @param cells        the column, cell under the feet first ({@link #DEPTH} entries)
     * @param floorSolid   the block under the last cell blocks movement and holds no fluid
     * @param sealBlocks   seal-family blocks the bot holds
     * @param topBlockId   registry id of the cell under the feet
     * @return null when the dig may run, else the skip reason for {@code dig-down skipped reason=}
     */
    public static String preflight(boolean columnMarked, List<Cell> cells, boolean floorSolid,
                                   int sealBlocks, String topBlockId) {
        if (columnMarked) {
            return "column-marked";
        }
        if (cells == null || cells.size() != DEPTH) {
            return "no-snapshot";
        }
        for (int i = 0; i < DEPTH; i++) {
            Cell cell = cells.get(i);
            if (cell != Cell.DIGGABLE) {
                return "cell" + (i + 1) + "-" + (cell == null ? "unknown" : cell.name().toLowerCase());
            }
        }
        if (!floorSolid) {
            return "no-floor";
        }
        if (sealBlocks <= 0 && (topBlockId == null || !HAND_SEAL_DROPS.contains(topBlockId))) {
            return "no-seal-block";
        }
        return null;
    }

    /** After mining cell {@code index} (0-based): keep digging only while every cell really opened. */
    public enum Step { CONTINUE, STOP_NOTHING_DUG, STOP_PARTIAL, DONE }

    public static Step afterMine(int index, boolean opened) {
        if (!opened) {
            return index == 0 ? Step.STOP_NOTHING_DUG : Step.STOP_PARTIAL;
        }
        return index + 1 >= DEPTH ? Step.DONE : Step.CONTINUE;
    }

    /**
     * The cap goes on the cell just above the head (the validator's cover cell), and only when the
     * bot stands in the dug column with that cell at or below the original feet level: from 2 deep
     * the head is walled by the ground, from 1 deep the head is at ground level and open sideways.
     */
    public static boolean capAllowed(int feetX, int feetY, int feetZ, int groundX, int groundFeetY, int groundZ) {
        return feetX == groundX && feetZ == groundZ && feetY + 2 <= groundFeetY;
    }

    /** What the bot does once the cap step is over. */
    public enum Outcome { SHELTER, CLIMB_OUT, NOTHING }

    /** A real dig with no cap is an open pit the bot cannot leave: climb out. */
    public static Outcome afterCap(int realRemovals, boolean capped) {
        if (realRemovals <= 0) {
            return Outcome.NOTHING;
        }
        return capped ? Outcome.SHELTER : Outcome.CLIMB_OUT;
    }

    /** What a capped (dig-down) or sealed (cliff) shelter does after its accept-time assessment. */
    public enum Accept { REGISTER, REGISTER_SEALED, CLIMB_OUT }

    /**
     * A capped pit that fails validation must not keep the bot capped and unregistered (no dawn
     * exit). Valid registers. A hostile next to a sealed shelter registers anyway, as wall-off does:
     * climbing out beside the mob is worse than the bunker. Every other reason (structural, or a
     * timed-out assessment) leaves.
     *
     * @param reason the assessment's invalid reason in lower case ({@code hostile_exposed}, ...)
     * @param sealed the shelter's entrance rule holds (dig-down: the cap blocks movement)
     */
    public static Accept accept(boolean valid, String reason, boolean sealed) {
        if (valid) {
            return Accept.REGISTER;
        }
        if ("hostile_exposed".equals(reason) && sealed) {
            return Accept.REGISTER_SEALED;
        }
        return Accept.CLIMB_OUT;
    }

    /** Pillar steps for the climb-out: refill the whole dug depth if the scaffold allows. */
    public static int climbSteps(int realRemovals, int scaffoldBlocks) {
        return Math.max(0, Math.min(realRemovals, scaffoldBlocks));
    }

    /** True when the feet are back at (or above) the original ground level. */
    public static boolean climbedOut(int feetY, int groundFeetY) {
        return feetY >= groundFeetY;
    }

    /** Game day of an absolute time-of-day; day N runs from dawn to the next dawn. */
    public static long gameDay(long timeOfDay) {
        return Math.floorDiv(timeOfDay, DAY_TICKS);
    }

    /** A no-dig-down mark: column x/z in a dimension, valid until the dawn that ends {@code day}. */
    public record Mark(String dimension, int x, int z, long day) {}

    public static boolean covers(Mark mark, String dimension, int x, int z) {
        return mark != null
                && mark.dimension() != null
                && mark.dimension().equals(dimension)
                && Math.abs(mark.x() - x) <= MARK_RADIUS
                && Math.abs(mark.z() - z) <= MARK_RADIUS;
    }

    public static boolean expired(Mark mark, long nowDay) {
        return mark == null || nowDay > mark.day();
    }

    public static boolean isMarked(List<Mark> marks, String dimension, int x, int z, long nowDay) {
        return coveringMark(marks, dimension, x, z, nowDay) != null;
    }

    /** The first live mark covering the column, or null. */
    public static Mark coveringMark(List<Mark> marks, String dimension, int x, int z, long nowDay) {
        if (marks == null) {
            return null;
        }
        for (Mark mark : marks) {
            if (!expired(mark, nowDay) && covers(mark, dimension, x, z)) {
                return mark;
            }
        }
        return null;
    }

    /** {@code skipped reason=column-marked} is logged once per mark (one column, one night). */
    public static boolean firstSkipLog(List<Mark> alreadyLogged, Mark mark) {
        return mark != null && (alreadyLogged == null || !alreadyLogged.contains(mark));
    }

    /** The marks to keep after adding {@code added}: expired ones are dropped. */
    public static List<Mark> withMark(List<Mark> marks, Mark added, long nowDay) {
        List<Mark> out = new ArrayList<>();
        if (marks != null) {
            for (Mark mark : marks) {
                if (!expired(mark, nowDay)) {
                    out.add(mark);
                }
            }
        }
        if (added != null) {
            out.add(added);
        }
        return List.copyOf(out);
    }
}
