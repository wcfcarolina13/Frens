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
        int ticks = ObstructionMinePolicy.groundBreakTicks(speed, hardness,
                ObstructionMinePolicy.canHarvest(requiresTool, suitable));
        return ObstructionMinePolicy.evaluate(ticks, MAX_GROUND_BREAK_TICKS);
    }

    @Test
    void canHarvestOnlyFailsForAToolRequiredBlockWithoutItsTool() {
        assertTrue(ObstructionMinePolicy.canHarvest(false, false));
        assertTrue(ObstructionMinePolicy.canHarvest(true, true));
        assertFalse(ObstructionMinePolicy.canHarvest(true, false));
    }

    @Test
    void stoneWithoutPickaxeIsTooSlowByHand() {
        // requiresTool, hardness 1.5, /100 hand penalty: ~150 ground ticks.
        assertTrue(ObstructionMinePolicy.groundBreakTicks(HAND, 1.5f, false) > MAX_GROUND_BREAK_TICKS);
        assertEquals(ObstructionMinePolicy.Verdict.TOO_SLOW, verdict(true, false, HAND, 1.5f));
    }

    @Test
    void snowLayerWithoutShovelIsAllowed() {
        // 1.21.11 Blocks.SNOW: requiresTool, strength 0.1 -> 10 ground ticks by hand.
        assertEquals(10, ObstructionMinePolicy.groundBreakTicks(HAND, 0.1f, false));
        assertEquals(ObstructionMinePolicy.Verdict.ALLOW, verdict(true, false, HAND, 0.1f));
    }

    @Test
    void snowBlockWithoutShovelIsAllowed() {
        // 1.21.11 Blocks.SNOW_BLOCK: requiresTool, strength 0.2 -> 20 ground ticks by hand.
        assertEquals(20, ObstructionMinePolicy.groundBreakTicks(HAND, 0.2f, false));
        assertEquals(ObstructionMinePolicy.Verdict.ALLOW, verdict(true, false, HAND, 0.2f));
    }

    @Test
    void netherrackByHandIsAllowed() {
        // 1.21.11 Blocks.NETHERRACK: requiresTool, strength 0.4 -> 40 ground ticks by hand.
        assertEquals(40, ObstructionMinePolicy.groundBreakTicks(HAND, 0.4f, false));
        assertEquals(ObstructionMinePolicy.Verdict.ALLOW, verdict(true, false, HAND, 0.4f));
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
        assertFalse(ObstructionMinePolicy.allow(Integer.MAX_VALUE, MAX_GROUND_BREAK_TICKS));
    }

    @Test
    void instantBlockTakesOneTick() {
        assertEquals(1, ObstructionMinePolicy.groundBreakTicks(HAND, 0.0f, true));
    }

    @Test
    void capIsInclusive() {
        assertTrue(ObstructionMinePolicy.allow(MAX_GROUND_BREAK_TICKS, MAX_GROUND_BREAK_TICKS));
        assertFalse(ObstructionMinePolicy.allow(MAX_GROUND_BREAK_TICKS + 1, MAX_GROUND_BREAK_TICKS));
    }
}
