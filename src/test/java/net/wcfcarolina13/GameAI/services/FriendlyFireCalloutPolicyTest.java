package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.FriendlyFireCalloutPolicy.PostCombatBucket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendlyFireCalloutPolicyTest {

    @Test
    void botHittingAHumanForRealDamageApologises() {
        assertTrue(FriendlyFireCalloutPolicy.shouldApologize(true, true, false, false, 2.5f));
    }

    @Test
    void botVictimsAreLeftToTheirOwnReceivedLine() {
        assertFalse(FriendlyFireCalloutPolicy.shouldApologize(false, true, false, false, 2.5f));
    }

    @Test
    void nonBotAttackersDoNotTriggerTheApology() {
        assertFalse(FriendlyFireCalloutPolicy.shouldApologize(true, false, false, false, 2.5f));
    }

    @Test
    void selfDamageIsIgnored() {
        assertFalse(FriendlyFireCalloutPolicy.shouldApologize(true, true, true, false, 2.5f));
    }

    @Test
    void shieldBlockedHitsAreIgnored() {
        assertFalse(FriendlyFireCalloutPolicy.shouldApologize(true, true, false, true, 2.5f));
    }

    @Test
    void zeroDamageSnowballHitsAreIgnored() {
        assertFalse(FriendlyFireCalloutPolicy.shouldApologize(true, true, false, false, 0.0f));
        assertFalse(FriendlyFireCalloutPolicy.shouldApologize(true, true, false, false, -1.0f));
    }

    @Test
    void threeHostilesIsAMultiFight() {
        assertFalse(FriendlyFireCalloutPolicy.isMultiHostileFight(2));
        assertTrue(FriendlyFireCalloutPolicy.isMultiHostileFight(3));
        assertTrue(FriendlyFireCalloutPolicy.isMultiHostileFight(7));
    }

    @Test
    void fightPeakingAtThreeHostilesReachesTheMultiPool() {
        assertEquals(PostCombatBucket.MULTI,
                FriendlyFireCalloutPolicy.postCombatBucket(true, false, false, 3, true, true));
        assertEquals(PostCombatBucket.MULTI,
                FriendlyFireCalloutPolicy.postCombatBucket(true, false, false, 5, true, false));
    }

    @Test
    void explosionsOutrankTheMultiPool() {
        assertEquals(PostCombatBucket.EXPLOSION_CREEPER,
                FriendlyFireCalloutPolicy.postCombatBucket(true, true, true, 4, true, true));
        assertEquals(PostCombatBucket.EXPLOSION,
                FriendlyFireCalloutPolicy.postCombatBucket(true, true, false, 4, true, true));
    }

    @Test
    void loneWeakMobIsTheSinglePool() {
        assertEquals(PostCombatBucket.SINGLE,
                FriendlyFireCalloutPolicy.postCombatBucket(true, false, false, 1, true, false));
    }

    @Test
    void twoHostilesOrAStrongMobIsGeneral() {
        assertEquals(PostCombatBucket.GENERAL,
                FriendlyFireCalloutPolicy.postCombatBucket(true, false, false, 2, true, false));
        assertEquals(PostCombatBucket.GENERAL,
                FriendlyFireCalloutPolicy.postCombatBucket(true, false, false, 1, true, true));
    }

    @Test
    void missingMetadataIsGeneral() {
        assertEquals(PostCombatBucket.GENERAL,
                FriendlyFireCalloutPolicy.postCombatBucket(false, true, true, 9, true, true));
    }
}
