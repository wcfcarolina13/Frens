package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shooter eye at the origin, aim point 10 blocks down +X, all at the same height. */
class RangedFriendlyFirePolicyTest {

    private static boolean inLine(double x, double z, boolean isBot) {
        return RangedFriendlyFirePolicy.inLineOfFire(0, 0, 0, 10, 0, 0, x, 0, z, isBot);
    }

    @Test
    void anAllyBotBetweenShooterAndTargetBlocksTheShot() {
        assertTrue(inLine(5.0, 0.0, true));
        assertTrue(inLine(5.0, 0.8, true));
    }

    @Test
    void anAllyBotBesideTheTargetOffAxisIsClear() {
        assertFalse(inLine(10.0, 1.2, true));
        assertFalse(inLine(9.7, 0.6, true));
    }

    @Test
    void anAllyBotBehindTheTargetIsClear() {
        assertFalse(inLine(10.3, 0.0, true));
        assertFalse(inLine(12.0, 0.0, true));
    }

    @Test
    void anAllyBotWellOffTheLineIsClear() {
        assertFalse(inLine(5.0, 1.2, true));
    }

    @Test
    void anyoneBehindTheShooterIsClear() {
        assertFalse(inLine(-1.0, 0.0, true));
        assertFalse(inLine(-1.0, 0.0, false));
    }

    @Test
    void aHumanKeepsTheWiderOlderCone() {
        assertTrue(inLine(5.0, 1.2, false));
        assertTrue(inLine(10.0, 1.2, false));
        assertTrue(inLine(10.3, 0.0, false));
        assertFalse(inLine(11.5, 0.0, false));
        assertFalse(inLine(5.0, 1.6, false));
    }

    @Test
    void aVeryShortShotIsNeverGated() {
        assertFalse(RangedFriendlyFirePolicy.inLineOfFire(0, 0, 0, 0.3, 0, 0, 0.1, 0, 0, false));
    }

    @Test
    void theConeFollowsAPitchedShot() {
        // Aim 10 out and 10 up: an ally on that diagonal blocks, one on the ground below it does not.
        assertTrue(RangedFriendlyFirePolicy.inLineOfFire(0, 0, 0, 10, 10, 0, 5, 5, 0, true));
        assertFalse(RangedFriendlyFirePolicy.inLineOfFire(0, 0, 0, 10, 10, 0, 5, 0, 0, true));
    }

    @Test
    void aCloseShooterSidestepsOnlyForAFriendInTheLine() {
        double minSq = 4.5 * 4.5;
        assertFalse(RangedFriendlyFirePolicy.mayReposition(9.0, minSq, false));
        assertTrue(RangedFriendlyFirePolicy.mayReposition(9.0, minSq, true));
        assertTrue(RangedFriendlyFirePolicy.mayReposition(36.0, minSq, false));
    }

    @Test
    void aClearSidewaysStanceWinsOverClosingPastTheAlly() {
        double sideways = RangedFriendlyFirePolicy.repositionScore(true, 0, 0, 36, true, 0);
        double closing = RangedFriendlyFirePolicy.repositionScore(true, 0, 0, 9, true, 3);
        assertTrue(sideways > closing);
    }

    @Test
    void closingToAClearShotWinsWhenTheSidewaysStanceIsBlocked() {
        double sideways = RangedFriendlyFirePolicy.repositionScore(false, 10, 0, 9, true, 0);
        double closing = RangedFriendlyFirePolicy.repositionScore(true, 0, 0, 36, true, 6);
        assertTrue(closing > sideways);
    }

    @Test
    void terrainRepositionKeepsItsExistingMovementCostPreference() {
        double sideways = RangedFriendlyFirePolicy.repositionScore(true, 1_000_000, 0, 36, false, 0);
        double closing = RangedFriendlyFirePolicy.repositionScore(true, 1_000_000, 0, 9, false, 3);
        assertTrue(closing > sideways);
    }

    @Test
    void theFieldDefenderNeedsTheExtendedSidewaysRing() {
        // Jake starts 15 blocks from Bob, with the zombie another four blocks past Bob.
        assertTrue(RangedFriendlyFirePolicy.inLineOfFire(0, 0, 1.45, 19, 0, 0, 15, 0, 0, true));
        assertFalse(RangedFriendlyFirePolicy.inLineOfFire(0, 0, 6, 19, 0, 0, 15, 0, 0, true));
    }
}
