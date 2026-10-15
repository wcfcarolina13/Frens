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
 * block, so this refuses it when the on-ground break time exceeds {@link #MAX_GROUND_BREAK_TICKS}.
 * 100 ticks (5 s) fits inside the callers' 6 s wait with room for settling.</p>
 *
 * <p>A block that needs a tool the bot is not holding is timed with vanilla's /100 hand penalty
 * ({@link #canHarvest} false), not refused outright: in 1.21.11 {@code SNOW}, {@code SNOW_BLOCK}
 * and {@code NETHERRACK} carry {@code requiresTool()} (javap on {@code Blocks}) yet break by hand in
 * 10 / 20 / 40 ground ticks, while stone by hand is 150 and still refused. Stone/deepslate with a
 * wooden pickaxe (23/45), dirt by hand (15) and leaves by hand (6) pass. Valuable ores mined with
 * too low a tier stay guarded inside {@code MiningTool}.</p>
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

    /** Whether the held item harvests the block: vanilla's /30 divisor instead of the /100 penalty. */
    public static boolean canHarvest(boolean requiresTool, boolean hasSuitableTool) {
        return !requiresTool || hasSuitableTool;
    }

    /**
     * {@code groundBreakTicks} must be computed with {@link #canHarvest}, so a tool-required block
     * without its tool carries the /100 hand penalty and only the time cap decides.
     */
    public static Verdict evaluate(int groundBreakTicks, int maxTicks) {
        return groundBreakTicks > maxTicks ? Verdict.TOO_SLOW : Verdict.ALLOW;
    }

    public static boolean allow(int groundBreakTicks, int maxTicks) {
        return evaluate(groundBreakTicks, maxTicks) == Verdict.ALLOW;
    }
}
