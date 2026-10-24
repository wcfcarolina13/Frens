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
     *
     * <p>With "Bots log off when they can't sleep" off there is no fallback, so the bot never
     * waits it out: it crafts and places its own bed instead (1.1.298, Bradley's ruling).
     */
    static boolean waitOutNearbySleeper(boolean logoffSettingOn, boolean canSleepNow,
                                         boolean playerSleepingNearby, boolean hasBedItem) {
        return logoffSettingOn && canSleepNow && playerSleepingNearby && !hasBedItem;
    }

    /** What a sleep attempt says once no nearby bed worked, before it sets up its own. */
    enum NoBedStep {
        /** A nearby bed was tried and failed, and the bot won't craft one (it will log off). */
        BLOCKED_BED_WAIT_OUT,
        /** No free bed nearby, someone is asleep, and the bot won't craft one. */
        NO_BED_WAIT_OUT,
        /** A nearby bed was tried and failed: say so, then set up its own. */
        BLOCKED_BED_TRY_OWN,
        /** No free bed nearby and the bot carries one: say so, then place it. */
        NO_BED_SET_UP_OWN,
        /** Nothing to say here (for example it isn't night, or it will craft quietly). */
        NONE
    }

    /**
     * Picks the step after the nearby beds failed. A bed that was found but couldn't be used
     * (obstructed, unsafe, unreachable) is reported as such, never as "someone's already
     * sleeping", even when the bot then waits it out.
     */
    static NoBedStep afterNearbyBedsFailed(boolean hadNearbyBeds, boolean canSleepNow,
                                           boolean waitOut, boolean hasBedItem) {
        if (waitOut) {
            return hadNearbyBeds ? NoBedStep.BLOCKED_BED_WAIT_OUT : NoBedStep.NO_BED_WAIT_OUT;
        }
        if (!canSleepNow) return NoBedStep.NONE;
        if (hadNearbyBeds) return NoBedStep.BLOCKED_BED_TRY_OWN;
        if (hasBedItem) return NoBedStep.NO_BED_SET_UP_OWN;
        return NoBedStep.NONE;
    }
}
