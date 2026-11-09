package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropGuardExemptionPolicyTest {

    @Test
    void followKeepsTheNormalDescentExemption() {
        // The commander just below a ledge, two cells away: follow may step down toward them.
        assertTrue(DropGuardExemptionPolicy.exempt(false, 3.0, true, 10, 0, 12, 0));
        assertFalse(DropGuardExemptionPolicy.exempt(false, 6.0, true, 10, 0, 12, 0));
        assertFalse(DropGuardExemptionPolicy.exempt(false, 3.0, false, 10, 0, 12, 0));
    }

    @Test
    void guardStepsAroundAPitBesideItsPoint() {
        // Sandbox: pit at (7200, 2300), guard point on the ground at (7198, 2301), same level.
        assertFalse(DropGuardExemptionPolicy.exempt(true, 0.0, true, 7200, 2300, 7198, 2301));
    }

    @Test
    void guardMayStillDropOntoItsOwnPoint() {
        assertTrue(DropGuardExemptionPolicy.exempt(true, 0.0, true, 7198, 2301, 7198, 2301));
    }

    @Test
    void guardGoesDownOnlyWhenItsTargetIsDownThere() {
        // A zombie on the ground beside the pit: the bot does not step down into its own stair.
        assertFalse(DropGuardExemptionPolicy.exempt(true, 0.0, true, 7499, 2300, 7502, 2300));
        assertFalse(DropGuardExemptionPolicy.exempt(true, 1.0, true, 7499, 2300, 7502, 2300));
        // A zombie 3 blocks down in the pit: it may go after it.
        assertTrue(DropGuardExemptionPolicy.exempt(true, 3.0, true, 7499, 2300, 7500, 2300));
    }

    @Test
    void guardCountsDropsFromTwoBlocksAndFollowFromThree() {
        assertEquals(2, DropGuardExemptionPolicy.minDepth(true));
        assertEquals(3, DropGuardExemptionPolicy.minDepth(false));
    }

    @Test
    void guardAndPatrolWalkBackWithHolesAvoided() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        int count = 0;
        for (int i = src.indexOf("moveTowardStayingOutOfHoles(bot, "); i >= 0; i = src.indexOf("moveTowardStayingOutOfHoles(bot, ", i + 1)) {
            count++;
        }
        assertEquals(4, count, "guard return, patrol return, patrol target and the first move after a patrol pick");
    }

    @Test
    void theMeleeApproachAvoidsHolesForGuardAndPatrol() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        int count = 0;
        for (int i = src.indexOf("moveTowardInCombat(bot, positionOf(closest), "); i >= 0; i = src.indexOf("moveTowardInCombat(bot, positionOf(closest), ", i + 1)) {
            count++;
        }
        assertEquals(3, count, "both melee approach sites and the kiting step forward");
        assertTrue(src.contains("st.mode == Mode.GUARD || st.mode == Mode.PATROL"));
    }

    // The sandbox column (2026-11-08): ground 120, then stair treads 119 and 118, pit floor 117.
    @Test
    void theStairIsRefusedFromItsSecondTreadMeasuredFromTheGuardPoint() {
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.refusesStep(119, 120, 120, false));
        org.junit.jupiter.api.Assertions.assertTrue(DropGuardExemptionPolicy.refusesStep(118, 119, 120, false));
        org.junit.jupiter.api.Assertions.assertTrue(DropGuardExemptionPolicy.refusesStep(118, 120, 120, false));
        // The target is down in the pit: allowed.
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.refusesStep(118, 119, 120, true));
    }

    @Test
    void aGuardBelowItsPointStillWalksLevelOrClimbs() {
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.refusesStep(117, 117, 120, false));
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.refusesStep(118, 117, 120, false));
        org.junit.jupiter.api.Assertions.assertEquals(119, DropGuardExemptionPolicy.minBypassStandY(120));
    }

    @Test
    void theAirHaltIsGuardOnlyAndAirborneOnly() {
        org.junit.jupiter.api.Assertions.assertTrue(DropGuardExemptionPolicy.haltInAir(true, false, false, true));
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.haltInAir(false, false, false, true));
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.haltInAir(true, true, false, true));
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.haltInAir(true, false, true, true));
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.haltInAir(true, false, false, false));
    }

    // PATROL measures from where it stands (reference = its own level): 1-block steps down a slope
    // pass one at a time, a 2-deep drop in front is still refused.
    @Test
    void aPatrolJudgesDropsFromItsOwnLevel() {
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.refusesStep(123, 124, 124, false));
        org.junit.jupiter.api.Assertions.assertFalse(DropGuardExemptionPolicy.refusesStep(122, 123, 123, false));
        org.junit.jupiter.api.Assertions.assertTrue(DropGuardExemptionPolicy.refusesStep(122, 124, 124, false));
    }
}
