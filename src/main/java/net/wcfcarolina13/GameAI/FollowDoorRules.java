package net.wcfcarolina13.GameAI;

import net.minecraft.util.math.BlockPos;

/**
 * Pure movement rules for the follow-mode door plan, extracted from {@code BotEventHandler}
 * after the 2026-08-28 field session so they can be unit-tested without class-loading the
 * (bootstrap-heavy) event handler.
 *
 * <p>Root cause they encode (one cause, two symptoms): while a plan was stepping through an
 * open door the bot force-jumped on EVERY grounded tick. Under a low ceiling the jump made it
 * perpetually airborne at the 2-high doorway (head collision → no horizontal entry), and the
 * bounce flipped its BlockPos Y each jump, resetting the full-BlockPos stuck counter — so the
 * stuck-abort never fired and only the plan TTL (then the wolf-teleport rescue) recovered it.
 */
public final class FollowDoorRules {

    /** Horizontal-stagnation ticks before a doorway jump is warranted. */
    public static final int STUCK_JUMP_TICKS = 8;

    private FollowDoorRules() {
    }

    /**
     * Jump ONLY on genuine horizontal stagnation at the doorway — never merely because a
     * step-through is in progress. The historical jump-while-stepping motivations (the door's
     * 3-pixel leaf collision, pressure plates, overhanging stair thresholds) are all stall
     * scenarios, so jump-on-stagnation covers them a few ticks later; a bot that is actually
     * progressing must never be forced airborne into a two-high doorway.
     */
    public static boolean shouldForceDoorJump(int horizontalStuckTicks) {
        return horizontalStuckTicks >= STUCK_JUMP_TICKS;
    }

    /** Same horizontal column (X/Z), Y ignored — vertical bouncing is not progress. */
    public static boolean samePlanColumn(BlockPos prev, BlockPos cur) {
        return prev != null && cur != null && prev.getX() == cur.getX() && prev.getZ() == cur.getZ();
    }
}
