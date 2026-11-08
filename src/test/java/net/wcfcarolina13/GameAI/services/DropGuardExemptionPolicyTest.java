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
        assertEquals(3, count, "guard return, patrol return and patrol target");
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
}
