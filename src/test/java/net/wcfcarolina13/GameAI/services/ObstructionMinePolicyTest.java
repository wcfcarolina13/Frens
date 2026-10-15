package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static net.wcfcarolina13.GameAI.services.ObstructionMinePolicy.MAX_GROUND_BREAK_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObstructionMinePolicyTest {

    private static final float HAND = 1.0f;
    private static final float WOOD = 2.0f;
    private static final float DIAMOND = 8.0f;

    private static ObstructionMinePolicy.Verdict verdict(boolean requiresTool, boolean suitable, float speed, float hardness) {
        boolean canHarvest = !requiresTool || suitable;
        int ticks = ObstructionMinePolicy.groundBreakTicks(speed, hardness, canHarvest);
        return ObstructionMinePolicy.evaluate(requiresTool, suitable, ticks, MAX_GROUND_BREAK_TICKS);
    }

    @Test
    void stoneWithoutPickaxeIsRefused() {
        assertEquals(ObstructionMinePolicy.Verdict.NEEDS_TOOL, verdict(true, false, HAND, 1.5f));
    }

    @Test
    void stoneWithWoodenPickaxeIsAllowed() {
        assertEquals(23, ObstructionMinePolicy.groundBreakTicks(WOOD, 1.5f, true));
        assertEquals(ObstructionMinePolicy.Verdict.ALLOW, verdict(true, true, WOOD, 1.5f));
    }

    @Test
    void deepslateWithWoodenPickaxeIsAllowed() {
        assertEquals(ObstructionMinePolicy.Verdict.ALLOW, verdict(true, true, WOOD, 3.0f));
    }

    @Test
    void dirtByHandIsAllowed() {
        assertEquals(ObstructionMinePolicy.Verdict.ALLOW, verdict(false, false, HAND, 0.5f));
    }

    @Test
    void leavesByHandAreAllowed() {
        assertEquals(ObstructionMinePolicy.Verdict.ALLOW, verdict(false, false, HAND, 0.2f));
    }

    @Test
    void obsidianWithDiamondPickaxeIsTooSlow() {
        assertTrue(ObstructionMinePolicy.groundBreakTicks(DIAMOND, 50.0f, true) > MAX_GROUND_BREAK_TICKS);
        assertEquals(ObstructionMinePolicy.Verdict.TOO_SLOW, verdict(true, true, DIAMOND, 50.0f));
    }

    @Test
    void unbreakableIsTooSlow() {
        assertEquals(Integer.MAX_VALUE, ObstructionMinePolicy.groundBreakTicks(HAND, -1.0f, true));
        assertFalse(ObstructionMinePolicy.allow(false, false, Integer.MAX_VALUE, MAX_GROUND_BREAK_TICKS));
    }

    @Test
    void instantBlockTakesOneTick() {
        assertEquals(1, ObstructionMinePolicy.groundBreakTicks(HAND, 0.0f, true));
    }

    @Test
    void capIsInclusive() {
        assertTrue(ObstructionMinePolicy.allow(false, false, MAX_GROUND_BREAK_TICKS, MAX_GROUND_BREAK_TICKS));
        assertFalse(ObstructionMinePolicy.allow(false, false, MAX_GROUND_BREAK_TICKS + 1, MAX_GROUND_BREAK_TICKS));
    }
}
