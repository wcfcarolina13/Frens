package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.CreeperEvasionPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backlog 986 ruling: a cornered sword-only bot hits the creeper to knock it back, then backs off. */
class CreeperKnockbackPolicyTest {

    @Test
    void aSwordOnlyBotWithNoTrickSwingsInReach() {
        assertTrue(CreeperEvasionPolicy.swingFirst(Decision.STAY, true, false, false, 2.5, false));
        assertTrue(CreeperEvasionPolicy.trickUnavailable(Decision.STAY, false, false));
    }

    @Test
    void theTrickStillRunsWithABlockOrAShield() {
        assertFalse(CreeperEvasionPolicy.swingFirst(Decision.STAY, true, false, true, 2.5, false));
        assertFalse(CreeperEvasionPolicy.swingFirst(Decision.STAY, true, true, false, 2.5, false));
        assertFalse(CreeperEvasionPolicy.trickUnavailable(Decision.STAY, false, true));
        assertFalse(CreeperEvasionPolicy.trickUnavailable(Decision.STAY, true, false));
    }

    @Test
    void cornerednessDecidesForBackAwayAndFlee() {
        assertTrue(CreeperEvasionPolicy.swingFirst(Decision.BACK_AWAY, true, false, false, 2.0, true));
        assertFalse(CreeperEvasionPolicy.swingFirst(Decision.BACK_AWAY, true, false, false, 2.0, false));
        assertFalse(CreeperEvasionPolicy.swingFirst(Decision.FLEE_SPRINT, true, false, false, 2.0, false)); // open ground unchanged
    }

    @Test
    void outOfReachOrUnarmedNeverSwings() {
        assertFalse(CreeperEvasionPolicy.swingFirst(Decision.STAY, true, false, false, 3.5, false));
        assertFalse(CreeperEvasionPolicy.swingFirst(Decision.STAY, false, false, false, 2.0, false));
    }

    @Test
    void theBackoffSwingsOnlyWhenStuckInReachWithoutAShield() {
        assertTrue(CreeperEvasionPolicy.backoffSwing(true, false, 2.8, 10));
        assertFalse(CreeperEvasionPolicy.backoffSwing(true, false, 2.8, 9));    // still gaining ground
        assertFalse(CreeperEvasionPolicy.backoffSwing(true, true, 2.8, 30));    // the shield goes up instead
        assertFalse(CreeperEvasionPolicy.backoffSwing(false, false, 2.8, 30));  // nothing to swing
        assertFalse(CreeperEvasionPolicy.backoffSwing(true, false, 3.5, 30));   // out of reach
    }
}
