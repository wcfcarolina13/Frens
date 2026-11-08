package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotSleepProximityPolicyTest {

    /** The radius is 16 blocks, so the threshold on squared distance is 256. */
    private static final double RADIUS_SQ = 16.0 * 16.0;

    @Test
    void sameSpotIsWithinRadius() {
        assertTrue(BotSleepProximityPolicy.isWithinCommanderSleepRadius(0.0));
    }

    @Test
    void exactlySixteenBlocksIsInclusive() {
        assertTrue(BotSleepProximityPolicy.isWithinCommanderSleepRadius(RADIUS_SQ));
    }

    @Test
    void justPastSixteenBlocksIsOutside() {
        assertFalse(BotSleepProximityPolicy.isWithinCommanderSleepRadius(Math.nextUp(RADIUS_SQ)));
        assertFalse(BotSleepProximityPolicy.isWithinCommanderSleepRadius(17.0 * 17.0));
    }

    @Test
    void justInsideIsWithin() {
        assertTrue(BotSleepProximityPolicy.isWithinCommanderSleepRadius(Math.nextDown(RADIUS_SQ)));
        assertTrue(BotSleepProximityPolicy.isWithinCommanderSleepRadius(15.0 * 15.0));
    }

    @Test
    void thresholdIsOnSquaredDistanceNotLinearDistance() {
        // A linear distance of 16 would be "within"; passing 16 means 4 blocks squared.
        assertTrue(BotSleepProximityPolicy.isWithinCommanderSleepRadius(16.0));
        // 20 blocks away (400) must not count even though 20 < 256.
        assertFalse(BotSleepProximityPolicy.isWithinCommanderSleepRadius(400.0));
    }

    @Test
    void nonFiniteDistancesAreOutside() {
        // A garbage distance must skip co-sleep (BotWakeUpDialogueService), never trigger it.
        assertFalse(BotSleepProximityPolicy.isWithinCommanderSleepRadius(Double.POSITIVE_INFINITY));
        assertFalse(BotSleepProximityPolicy.isWithinCommanderSleepRadius(Double.NaN));
    }
}
