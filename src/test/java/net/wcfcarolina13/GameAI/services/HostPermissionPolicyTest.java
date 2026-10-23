package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.util.UUID;

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
    @Test
    void hostUuidPassesOnAnIntegratedServer() {
        UUID host = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertTrue(HostPermissionPolicy.isIntegratedHost(false, host, host));
    }

    @Test
    void aDifferentUuidCannotBecomeHostByUsingTheSameName() {
        assertFalse(HostPermissionPolicy.isIntegratedHost(false,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("11111111-1111-1111-1111-111111111111")));
    }

    @Test
    void dedicatedServerHasNoHostEvenIfProfilesMatch() {
        UUID profile = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertFalse(HostPermissionPolicy.isIntegratedHost(true, profile, profile));
    }

    @Test
    void missingHostOrRequesterDoesNotGrantPrivileges() {
        UUID profile = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertFalse(HostPermissionPolicy.isIntegratedHost(false, null, profile));
        assertFalse(HostPermissionPolicy.isIntegratedHost(false, profile, null));
        assertFalse(HostPermissionPolicy.isIntegratedHost(false, null, null));
    }

}
