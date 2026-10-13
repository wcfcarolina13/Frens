package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostPermissionPolicyTest {

    @Test
    void consoleAndCommandBlocksAlwaysPass() {
        assertTrue(HostPermissionPolicy.allowsBotCommands(true, false, false));
    }

    @Test
    void operatorPasses() {
        assertTrue(HostPermissionPolicy.allowsBotCommands(false, true, false));
    }

    @Test
    void integratedHostPassesWithCommandsOff() {
        assertTrue(HostPermissionPolicy.allowsBotCommands(false, false, true));
    }

    @Test
    void lanGuestOrDedicatedNonOpIsRefused() {
        assertFalse(HostPermissionPolicy.allowsBotCommands(false, false, false));
    }

    @Test
    void serverAdminIsOperatorOrHost() {
        assertTrue(HostPermissionPolicy.isServerAdmin(true, false));
        assertTrue(HostPermissionPolicy.isServerAdmin(false, true));
        assertTrue(HostPermissionPolicy.isServerAdmin(true, true));
        assertFalse(HostPermissionPolicy.isServerAdmin(false, false));
    }
}
