package net.wcfcarolina13.GameAI.skills.support;

import net.wcfcarolina13.GameAI.services.NavLegPolicy;

/**
 * Whether a woodcut relocation walk that stopped short keeps walking toward the same target
 * (6ad-6al field bug D: one walk call ends on its deadline mid-trip, and the run used to give up the
 * target and search again from wherever the bot stood). Another hop toward the same target is worth
 * it while the last walk closed real distance. Pure: no {@code net.minecraft.*} types.
 */
public final class RelocationWalkPolicy {

    /** Extra walk calls toward one relocation target after the first. Not MAX_RELOCATIONS (targets). */
    public static final int MAX_EXTRA_HOPS = 3;

    /**
     * Wall-clock bound on one relocation attempt (every candidate and hop of one
     * relocateToUnprotectedTree call). Without it 1 + {@link #MAX_EXTRA_HOPS} hops of up to 40 s per
     * candidate, over up to 8 blind-walk candidates, could keep the bot walking for many minutes.
     */
    public static final long RELOCATION_BUDGET_MS = 90_000L;

    private RelocationWalkPolicy() {
    }

    /** Whether a relocation that started at {@code startedAtMs} has used its {@link #RELOCATION_BUDGET_MS}. */
    public static boolean budgetExhausted(long startedAtMs, long nowMs) {
        return nowMs - startedAtMs >= RELOCATION_BUDGET_MS;
    }

    /**
     * Whether a sunset-gated relocation should stop walking: the same test as woodcut's own gate
     * (time of day at or past {@code sunsetTimeOfDay}), so a long relocation does not run past dusk.
     */
    public static boolean pastSunset(boolean sunsetGated, long worldTimeOfDay, int sunsetTimeOfDay) {
        return sunsetGated && (int) Math.floorMod(worldTimeOfDay, 24000L) >= sunsetTimeOfDay;
    }

    /**
     * Another hop toward the same target when fewer than {@link #MAX_EXTRA_HOPS} extra hops ran and the
     * last walk brought the bot at least {@link NavLegPolicy#MIN_PROGRESS_BLOCKS} closer (straight line).
     *
     * @param prevDistBlocks distance to the target before the last walk
     * @param nowDistBlocks  distance to the target after it
     * @param hopsDone       extra hops already made (0 after the first walk)
     */
    public static boolean continueToward(double prevDistBlocks, double nowDistBlocks, int hopsDone) {
        if (hopsDone < 0 || hopsDone >= MAX_EXTRA_HOPS) {
            return false;
        }
        double progress = prevDistBlocks - nowDistBlocks;
        return progress >= NavLegPolicy.MIN_PROGRESS_BLOCKS;
    }
}
