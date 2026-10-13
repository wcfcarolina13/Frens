package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldModeAccessPolicyTest {

    @Test
    void commandsOffHostCanChoose() {
        assertTrue(WorldModeAccessPolicy.canChoose(false, true, false));
    }

    @Test
    void permissionOrDelegationAlsoAllows() {
        assertTrue(WorldModeAccessPolicy.canChoose(true, false, false));
        assertTrue(WorldModeAccessPolicy.canChoose(false, false, true));
    }

    @Test
    void lanGuestWithoutDelegationCannotChoose() {
        assertFalse(WorldModeAccessPolicy.canChoose(false, false, false));
    }
}
