package net.wcfcarolina13.GameAI;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the door-plan movement rules extracted after the 2026-08-28 field session, where a
 * bot following the commander down an indoor staircase pinned itself at a double door for ~5s.
 *
 * <p>Root cause (one cause, two symptoms): while a plan was stepping through an open door, the
 * bot force-jumped on EVERY grounded tick. Under a low ceiling the jump made it perpetually
 * airborne at the 2-high doorway (head collision → no horizontal entry), and the vertical
 * bouncing flipped its BlockPos Y between 65 and 66 each jump, resetting the full-BlockPos
 * stuck counter — so the 24-tick stuck-abort never fired and only the plan TTL (then the
 * wolf-teleport rescue) recovered it.
 */
class FollowDoorPlanRulesTest {

    @Test
    void stuckTrackingIgnoresVerticalBouncing() {
        BlockPos ground = new BlockPos(252, 65, 1284);
        BlockPos midJump = new BlockPos(252, 66, 1284);
        assertTrue(FollowDoorRules.samePlanColumn(ground, midJump),
                "a jump in place must still count as stuck — Y is not progress");
        assertTrue(FollowDoorRules.samePlanColumn(ground, ground));
        assertFalse(FollowDoorRules.samePlanColumn(ground, new BlockPos(253, 65, 1284)),
                "horizontal movement is progress");
        assertFalse(FollowDoorRules.samePlanColumn(ground, new BlockPos(252, 65, 1285)));
        assertFalse(FollowDoorRules.samePlanColumn(null, ground),
                "no previous sample means not stuck");
    }

    @Test
    void doorJumpFiresOnlyOnGenuineStagnationNotEveryTick() {
        // Tick 0 of a healthy step-through: no jump. The old behavior (jump whenever
        // stepping && door open && on ground) is exactly what pinned the bot under a
        // low ceiling, so "stepping" alone must never be a jump reason.
        assertFalse(FollowDoorRules.shouldForceDoorJump(0),
                "a progressing bot must not jump into a 2-high doorway");
        assertFalse(FollowDoorRules.shouldForceDoorJump(7),
                "below the stagnation threshold, keep walking");
        // At the threshold the bot has provably stalled on something at the threshold
        // (door leaf collision, pressure plate, overhanging stair) — now the jump helps.
        assertTrue(FollowDoorRules.shouldForceDoorJump(8));
        assertTrue(FollowDoorRules.shouldForceDoorJump(20));
    }
}
