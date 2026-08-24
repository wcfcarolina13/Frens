package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the "wait it out" decision for a sleep attempt that found no usable nearby bed while
 * a nearby player is already sleeping: suppression applies ONLY when the bot would have to craft
 * a bed. A bot already carrying one must go on to place it and sleep — bots count toward the
 * players-sleeping percentage, so a bot waiting awake is what blocks the sleeper's night from
 * advancing (the reported bug: bed in inventory, commander in bed, no placement attempt).
 */
class SleepBedCandidatePolicyTest {

    @Test
    void botCarryingABedIsNeverSuppressedByANearbySleeper() {
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(true, true, true));
    }

    @Test
    void botWithoutABedWaitsOutANearbySleeperInsteadOfCrafting() {
        assertTrue(SleepBedCandidatePolicy.waitOutNearbySleeper(true, true, false));
    }

    @Test
    void noNearbySleeperMeansNoSuppressionEitherWay() {
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(true, false, false));
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(true, false, true));
    }

    @Test
    void daytimeNeverReachesTheWaitOutBranch() {
        // canSleepNow=false attempts exit earlier with the not-night message; the policy must
        // not claim the wait-out path for them.
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(false, true, false));
    }
}
