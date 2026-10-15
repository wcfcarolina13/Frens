package net.wcfcarolina13.PathFinding.unified;

import net.wcfcarolina13.PathFinding.PathMovePolicy;

/**
 * Move costs in game ticks: Baritone's {@code ActionCosts} table (checked against upstream
 * 2026-10-14) plus our swim entry and wade penalties. {@link #JUMP_ONE_BLOCK} and the fall table are
 * computed from the vanilla velocity curve {@code v(t) = (0.98^t - 1) * -3.92} exactly as Baritone
 * does; {@code ActionCostsTest} pins them so an edit cannot drift them silently.
 */
public final class ActionCosts {
    public static final double WALK_ONE_BLOCK = 20 / 4.317;
    public static final double SPRINT_ONE_BLOCK = 20 / 5.612;
    public static final double WALK_ONE_IN_WATER = 20 / 2.2;
    public static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;
    public static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;
    public static final double COST_INF = 1_000_000;
    public static final double JUMP_PENALTY = 2;
    public static final double BLOCK_PLACEMENT_PENALTY = 20;
    public static final double BREAK_ADDITIONAL = 2;
    /** Ours: a swim leg should lose to a comparable dry route. */
    public static final double SWIM_ENTRY_PENALTY = 10;

    /**
     * One old finder cost unit (a flat walking step) in ticks. Converts NavHazardCache and
     * PassageAnchorService values and {@link PathMovePolicy#WADE_PENALTY}.
     */
    public static final double STEP_TICKS = WALK_ONE_BLOCK;
    /** Extra ticks for a landing whose body is wadeable water: 4.633, so a wade step is about 9.27 ticks. */
    public static final double WADE_PENALTY = PathMovePolicy.WADE_PENALTY * STEP_TICKS;

    /** Highest fall the table covers. */
    public static final int FALL_TABLE_SIZE = 64;
    private static final double[] FALL_N = new double[FALL_TABLE_SIZE + 1];

    public static final double JUMP_ONE_BLOCK;

    static {
        for (int i = 0; i <= FALL_TABLE_SIZE; i++) {
            FALL_N[i] = distanceToTicks(i);
        }
        JUMP_ONE_BLOCK = distanceToTicks(1.25) - distanceToTicks(0.25);
    }

    private ActionCosts() {
    }

    /** Ticks to fall {@code n} blocks. */
    public static double fallN(int n) {
        if (n < 0 || n > FALL_TABLE_SIZE) {
            throw new IllegalArgumentException("fall " + n);
        }
        return FALL_N[n];
    }

    static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    static double distanceToTicks(double distance) {
        if (distance == 0) {
            return 0;
        }
        double remaining = distance;
        int tick = 0;
        while (true) {
            double fall = velocity(tick);
            if (remaining <= fall) {
                return tick + remaining / fall;
            }
            remaining -= fall;
            tick++;
        }
    }
}
