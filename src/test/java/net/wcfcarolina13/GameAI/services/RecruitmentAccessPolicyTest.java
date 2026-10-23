package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static net.wcfcarolina13.GameAI.services.RecruitmentAccessPolicy.Decision.*;

class RecruitmentAccessPolicyTest {
    private static final UUID RECRUITER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test void canonicalAliasAcceptsCaseWhitespaceAndLegacyEmptyReplies() {
        for (String requested : new String[]{"Jake", " jAkE ", "", "  ", null}) {
            assertEquals(ALLOW, RecruitmentAccessPolicy.authorize("Jake", requested, RECRUITER, null));
        }
    }

    @Test void forgedAliasIsDeniedEvenForTheRecordedOwner() {
        assertEquals(DENY_ALIAS, RecruitmentAccessPolicy.authorize("Jake", "Bob", RECRUITER, RECRUITER.toString()));
    }

    @Test void missingServerAliasNeverAuthorizesAClientChoice() {
        assertEquals(DENY_ALIAS, RecruitmentAccessPolicy.authorize(null, "Jake", RECRUITER, null));
        assertEquals(DENY_ALIAS, RecruitmentAccessPolicy.authorize(" ", null, RECRUITER, null));
    }

    @Test void onlyUnownedOrAlreadyOwnedByRecruiterMayBeRecruited() {
        assertEquals(ALLOW, RecruitmentAccessPolicy.authorize("Jake", "Jake", RECRUITER, ""));
        assertEquals(ALLOW, RecruitmentAccessPolicy.authorize("Jake", "Jake", RECRUITER, RECRUITER.toString()));
        assertEquals(DENY_OWNER, RecruitmentAccessPolicy.authorize("Jake", "Jake", RECRUITER, OTHER.toString()));
    }

    @Test void malformedOwnershipIsNotTreatedAsOwnerless() {
        assertEquals(DENY_OWNER, RecruitmentAccessPolicy.authorize("Jake", "Jake", RECRUITER, "invalid-uuid"));
    }

    @Test void missingRequesterIsDenied() {
        assertEquals(DENY_OWNER, RecruitmentAccessPolicy.authorize("Jake", "Jake", null, null));
    }

    @Test void delayedSetupRequiresOwnershipToRemainWithOriginalRecruiter() {
        assertTrue(RecruitmentAccessPolicy.stillOwnedBy(RECRUITER, " " + RECRUITER + " "));
        for (String owner : new String[]{OTHER.toString(), "invalid-uuid", "", null}) {
            assertFalse(RecruitmentAccessPolicy.stillOwnedBy(RECRUITER, owner));
        }
        assertFalse(RecruitmentAccessPolicy.stillOwnedBy(null, RECRUITER.toString()));
    }

    @Test void serverAdmissionPrecedesHandshakeConsumptionAndWorldMutation() throws Exception {
        String source = source();
        int start = source.indexOf("public static void handleConfirmRecruit(");
        String body = source.substring(start, source.indexOf("private static void schedulePostSpawnSetup", start));
        int gate = body.indexOf("RecruitmentAccessPolicy.authorize(");
        assertTrue(gate >= 0 && gate < body.indexOf("LAST_DIALOGUE_GRANTED_TICK.remove("));
        assertTrue(gate < body.indexOf("existing.teleport("));
        assertTrue(gate < body.indexOf("createFakePlayer.createFake("));
        assertFalse(body.contains("? (st != null ? st.getBotAlias()"));
        assertTrue(body.contains("Only the companion offered by this world's recruitment dialogue can be recruited."));
    }

    @Test void delayedSetupRechecksOwnerBeforeLookingUpOrMovingTarget() throws Exception {
        String source = source();
        int start = source.indexOf("private static void schedulePostSpawnSetup");
        String body = source.substring(start, source.indexOf("private static Vec3d pickSafeSpawnNear", start));
        int gate = body.indexOf("RecruitmentAccessPolicy.stillOwnedBy(");
        assertTrue(gate >= 0 && gate < body.indexOf("getPlayer(alias)"));
        assertTrue(gate < body.indexOf("bot.teleport("));
        assertTrue(body.contains("ownership changed"));
    }

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/SurvivalRecruitmentService.java"));
    }
}
