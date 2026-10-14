package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionCommanderPolicyTest {
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void recruiterCommands() {
        assertTrue(CompanionCommanderPolicy.isAuthorized(ACTOR, ACTOR.toString(), null, false, false));
        assertTrue(CompanionCommanderPolicy.isAuthorized(ACTOR, ACTOR.toString().toUpperCase(), OTHER, false, false));
    }

    @Test
    void exactOwnerCommandsEvenWhenNotTheRecruiter() {
        assertTrue(CompanionCommanderPolicy.isAuthorized(ACTOR, OTHER.toString(), ACTOR, false, false));
        // recruiter cleared by a reset: the owner keeps command
        assertTrue(CompanionCommanderPolicy.isAuthorized(ACTOR, null, ACTOR, false, false));
        assertTrue(CompanionCommanderPolicy.isAuthorized(ACTOR, "", ACTOR, false, false));
    }

    @Test
    void operatorAndHostCommand() {
        assertTrue(CompanionCommanderPolicy.isAuthorized(ACTOR, OTHER.toString(), OTHER, true, false));
        assertTrue(CompanionCommanderPolicy.isAuthorized(ACTOR, OTHER.toString(), OTHER, false, true));
    }

    @Test
    void strangerIsRefused() {
        assertFalse(CompanionCommanderPolicy.isAuthorized(ACTOR, OTHER.toString(), OTHER, false, false));
    }

    @Test
    void unownedUnrecruitedBotIsNotOpenToAnyone() {
        // Unlike isAllowedToControl, no owner does not mean everyone.
        assertFalse(CompanionCommanderPolicy.isAuthorized(ACTOR, null, null, false, false));
        assertFalse(CompanionCommanderPolicy.isAuthorized(ACTOR, "  ", null, false, false));
    }

    @Test
    void nullActorIsRefusedEvenWithAdminFlags() {
        assertFalse(CompanionCommanderPolicy.isAuthorized(null, null, null, true, true));
    }

    @Test
    void malformedRecruiterDoesNotMatch() {
        assertFalse(CompanionCommanderPolicy.isAuthorized(ACTOR, "not-a-uuid", null, false, false));
    }

    @Test
    void parseOwnerUuid() {
        assertEquals(ACTOR, CompanionCommanderPolicy.parseOwnerUuid(ACTOR.toString()));
        assertEquals(ACTOR, CompanionCommanderPolicy.parseOwnerUuid(" " + ACTOR + " "));
        assertNull(CompanionCommanderPolicy.parseOwnerUuid(null));
        assertNull(CompanionCommanderPolicy.parseOwnerUuid(""));
        assertNull(CompanionCommanderPolicy.parseOwnerUuid("SERVER"));
    }
}
