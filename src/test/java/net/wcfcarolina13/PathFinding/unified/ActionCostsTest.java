package net.wcfcarolina13.PathFinding.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pins the tick table (design section 2.2) and the BlockPos.asLong cell layout. */
class ActionCostsTest {
    private static final double EPS = 0.0005;

    @Test
    void baritoneTableMatchesUpstream() {
        assertEquals(4.633, ActionCosts.WALK_ONE_BLOCK, EPS);
        assertEquals(3.564, ActionCosts.SPRINT_ONE_BLOCK, EPS);
        assertEquals(9.091, ActionCosts.WALK_ONE_IN_WATER, EPS);
        assertEquals(3.706, ActionCosts.WALK_OFF_BLOCK, EPS);
        assertEquals(0.927, ActionCosts.CENTER_AFTER_FALL, EPS);
        assertEquals(1_000_000, ActionCosts.COST_INF);
        assertEquals(2, ActionCosts.JUMP_PENALTY);
        assertEquals(20, ActionCosts.BLOCK_PLACEMENT_PENALTY);
        assertEquals(2, ActionCosts.BREAK_ADDITIONAL);
        assertEquals(10, ActionCosts.SWIM_ENTRY_PENALTY);
    }

    @Test
    void computedJumpAndFallTablePinned() {
        assertEquals(3.163, ActionCosts.JUMP_ONE_BLOCK, EPS);
        assertEquals(0.0, ActionCosts.fallN(0), EPS);
        assertEquals(5.615, ActionCosts.fallN(1), EPS);
        assertEquals(7.788, ActionCosts.fallN(2), EPS);
        assertEquals(9.469, ActionCosts.fallN(3), EPS);
        assertEquals(10.912, ActionCosts.fallN(4), EPS);
        for (int n = 1; n < 20; n++) {
            assertTrue(ActionCosts.fallN(n + 1) > ActionCosts.fallN(n), "fall table increases");
        }
    }

    @Test
    void wadeStepStaysWithinTwoPercentOfBaritoneWater() {
        assertEquals(ActionCosts.WALK_ONE_BLOCK, ActionCosts.WADE_PENALTY, 1e-9);
        double wadeStep = ActionCosts.WALK_ONE_BLOCK + ActionCosts.WADE_PENALTY;
        assertEquals(9.27, wadeStep, 0.01);
        assertTrue(Math.abs(wadeStep / ActionCosts.WALK_ONE_IN_WATER - 1) < 0.02);
    }

    @Test
    void cellPosMatchesBlockPosAsLongLayout() {
        // BlockPos.asLong (javap, 1.21.11): x & (2^26-1) << 38 | z & (2^26-1) << 12 | y & (2^12-1)
        assertEquals((1L << 38) | (3L << 12) | 2L, CellPos.pack(1, 2, 3));
        assertEquals(6047314468927L, CellPos.pack(22, 63, 126));
        assertEquals(-1L, CellPos.pack(-1, -1, -1));
        int[][] samples = {{0, 0, 0}, {22, 63, 126}, {-12, 68, 152}, {-30_000_000, -64, 29_999_999},
                {29_999_999, 319, -30_000_000}, {-1, -2048, 5}, {7, 2047, -7}};
        for (int[] s : samples) {
            long p = CellPos.pack(s[0], s[1], s[2]);
            assertEquals(s[0], CellPos.x(p));
            assertEquals(s[1], CellPos.y(p));
            assertEquals(s[2], CellPos.z(p));
        }
    }
}
