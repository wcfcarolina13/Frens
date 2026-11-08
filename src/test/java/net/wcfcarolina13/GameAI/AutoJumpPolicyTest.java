package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoJumpPolicyTest {

    @Test
    void jumpsAFullBlockWithRoomAboveBoth() {
        assertTrue(AutoJumpPolicy.shouldJump(true, 1.0D, true, true));
    }

    @Test
    void noJumpUnderATwoHighCeiling() {
        // PR #70: a bot in its own tunnel, pressed against a crafting table, jumped every tick into the ceiling
        assertFalse(AutoJumpPolicy.shouldJump(true, 1.0D, true, false));
    }

    @Test
    void noJumpWhenTheFrontBlockHasNoRoomAbove() {
        assertFalse(AutoJumpPolicy.shouldJump(true, 1.0D, false, true));
    }

    @Test
    void noJumpAtFencesOrOpenAir() {
        assertFalse(AutoJumpPolicy.shouldJump(true, 1.5D, true, true));
        assertFalse(AutoJumpPolicy.shouldJump(false, 0.0D, true, true));
    }

    @Test
    void slabStepStillJumps() {
        assertTrue(AutoJumpPolicy.shouldJump(true, 0.5D, true, true));
    }
}
