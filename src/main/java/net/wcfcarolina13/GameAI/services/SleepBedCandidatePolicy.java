package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;

import java.util.Set;

final class SleepBedCandidatePolicy {

    private SleepBedCandidatePolicy() {}

    static boolean addUniqueFoot(Set<BlockPos> seenFeet, BlockPos foot) {
        return seenFeet != null && foot != null && seenFeet.add(foot.toImmutable());
    }

    /**
     * Whether a sleep attempt that found no usable nearby bed should stop and "wait it out"
     * because a nearby player is already sleeping — instead of setting up the bot's own bed.
     *
     * <p>Only suppresses when the bot would have to <em>craft</em> a bed first: burning wool and
     * planks is wasted work when the logoff fallback exists. A bot already carrying a bed places
     * it and sleeps too — bots count toward the players-sleeping percentage, so a bot that
     * "waits it out" awake is exactly what keeps the nearby sleeper's night from ever advancing.
     */
    static boolean waitOutNearbySleeper(boolean canSleepNow, boolean playerSleepingNearby,
                                         boolean hasBedItem) {
        return canSleepNow && playerSleepingNearby && !hasBedItem;
    }
}
