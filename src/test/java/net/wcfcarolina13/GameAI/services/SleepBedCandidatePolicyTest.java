package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(true, true, true, true));
    }

    @Test
    void botWithoutABedWaitsOutANearbySleeperInsteadOfCrafting() {
        assertTrue(SleepBedCandidatePolicy.waitOutNearbySleeper(true, true, true, false));
    }

    @Test
    void noNearbySleeperMeansNoSuppressionEitherWay() {
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(true, true, false, false));
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(true, true, false, true));
    }

    @Test
    void daytimeNeverReachesTheWaitOutBranch() {
        // canSleepNow=false attempts exit earlier with the not-night message; the policy must
        // not claim the wait-out path for them.
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(true, false, true, false));
    }

    @Test
    void settingOffNeverWaitsItOutSoTheBotCraftsItsOwnBed() {
        // No logoff fallback with the setting off: crafting is the only way the bot sleeps.
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(false, true, true, false));
        assertFalse(SleepBedCandidatePolicy.waitOutNearbySleeper(false, true, true, true));
    }

    @Test
    void anObstructedNearbyBedIsReportedAsBlockedNotAsSomeoneSleeping() {
        assertEquals(SleepBedCandidatePolicy.NoBedStep.BLOCKED_BED_WAIT_OUT,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(true, true, true, false));
        assertEquals(SleepBedCandidatePolicy.NoBedStep.BLOCKED_BED_TRY_OWN,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(true, true, false, false));
        assertEquals(SleepBedCandidatePolicy.NoBedStep.BLOCKED_BED_TRY_OWN,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(true, true, false, true));
    }

    @Test
    void noNearbyBedWaitsOutOnlyWhenThePolicySaysSo() {
        assertEquals(SleepBedCandidatePolicy.NoBedStep.NO_BED_WAIT_OUT,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(false, true, true, false));
        assertEquals(SleepBedCandidatePolicy.NoBedStep.NO_BED_SET_UP_OWN,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(false, true, false, true));
        assertEquals(SleepBedCandidatePolicy.NoBedStep.NONE,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(false, true, false, false));
    }

    @Test
    void notNightSaysNothingHereBecauseTheNotNightMessageFollows() {
        assertEquals(SleepBedCandidatePolicy.NoBedStep.NONE,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(true, false, false, true));
        assertEquals(SleepBedCandidatePolicy.NoBedStep.NONE,
                SleepBedCandidatePolicy.afterNearbyBedsFailed(false, false, false, true));
    }
}
