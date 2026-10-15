package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.PlayerUtils.BreakTimePolicy;

/**
 * Decides whether movement's opportunistic obstruction mining may break a block
 * ({@code MovementService.tryMineObstructionToward} and {@code tryMinePlannedStepTrim}).
 *
 * <p>Those paths clear a block in the way of a walk and wait at most 6 s for the result.
 * Sandbox finding #14 (1.1.239): with no pickaxe the bot hand-mined stone (150 ground ticks,
 * x5 airborne, no drop in survival), timed out, retried after the 4.5 s per-block cooldown and
 * stacked parallel jobs while digging sideways into a pocket. A walk should go around such a
 * block, so this refuses it:</p>
 * <ul>
 *   <li>the block needs a tool to drop ({@code isToolRequired}) and the held item is not suitable:
 *       slow (the /100 hand penalty) and the block is lost anyway;</li>
 *   <li>the on-ground break time exceeds {@link #MAX_GROUND_BREAK_TICKS}. 100 ticks (5 s) fits inside
 *       the callers' 6 s wait with room for settling, and still allows stone/deepslate with a wooden
 *       pickaxe (23/45 ticks), dirt by hand (15) and leaves by hand (6).</li>
 * </ul>
 *
 * <p>Ticks are computed on the ground (no airborne /5) from the held tool's base speed, so a
 * bot mid-step-up never trips the cap. Efficiency and haste are ignored, which only overestimates.</p>
 *
 * <p>No {@code net.minecraft.*} types.</p>
 */
public final class ObstructionMinePolicy {

    /** On-ground break time cap for movement obstruction mining (5 s). */
    public static final int MAX_GROUND_BREAK_TICKS = 100;

    public enum Verdict {
        ALLOW,
        NEEDS_TOOL,
        TOO_SLOW
    }

    private ObstructionMinePolicy() {}

    /**
     * On-ground break ticks for a block of {@code hardness} with a tool of base speed
     * {@code toolSpeed}. Unbreakable blocks (hardness -1) give {@link Integer#MAX_VALUE}.
     */
    public static int groundBreakTicks(float toolSpeed, float hardness, boolean canHarvest) {
        float delta = BreakTimePolicy.delta(toolSpeed, hardness, canHarvest);
        if (!(delta > 0.0f)) {
            return Integer.MAX_VALUE;
        }
        return BreakTimePolicy.requiredTicks(delta);
    }

    public static Verdict evaluate(boolean requiresTool, boolean hasSuitableTool, int groundBreakTicks, int maxTicks) {
        if (requiresTool && !hasSuitableTool) {
            return Verdict.NEEDS_TOOL;
        }
        if (groundBreakTicks > maxTicks) {
            return Verdict.TOO_SLOW;
        }
        return Verdict.ALLOW;
    }

    public static boolean allow(boolean requiresTool, boolean hasSuitableTool, int groundBreakTicks, int maxTicks) {
        return evaluate(requiresTool, hasSuitableTool, groundBreakTicks, maxTicks) == Verdict.ALLOW;
    }
}
