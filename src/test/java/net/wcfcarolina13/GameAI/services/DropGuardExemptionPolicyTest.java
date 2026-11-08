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
        assertTrue(DropGuardExemptionPolicy.exempt(true, 3.0, true, 7198, 2301, 7198, 2301));
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
}
