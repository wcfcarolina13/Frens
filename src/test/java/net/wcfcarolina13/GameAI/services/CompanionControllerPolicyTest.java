package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CompanionControllerPolicyTest {
    @Test
    void recordedOwnershipIdentityDoesNotDependOnOnlinePresence() {
        UUID owner = UUID.randomUUID();
        UUID recruiter = UUID.randomUUID();
        assertEquals(owner, CompanionControllerPolicy.resolve(owner, id -> id, () -> recruiter));
    }

    @Test
    void ownerlessRecruiterIdentityAlsoSurvivesBeingOffline() {
        UUID recruiter = UUID.randomUUID();
        assertEquals(recruiter, CompanionControllerPolicy.resolve(null, id -> id, () -> recruiter));
    }

    @Test
    void onlineLookupUsesTheSameIdentityAccessorAsConsentProvenance() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java"));
        int lookupAt = source.indexOf("public static ServerPlayerEntity resolveController(");
        int identityAt = source.indexOf("public static UUID resolveControllerUuid(");
        assertTrue(lookupAt >= 0 && identityAt > lookupAt);
        String onlineLookup = source.substring(lookupAt, identityAt);
        assertTrue(onlineLookup.contains("resolveControllerUuid(server, bot)"));
        assertTrue(onlineLookup.contains("getPlayer(controllerUuid)"));
        assertTrue(onlineLookup.contains("!controller.isRemoved()"));
        String identity = source.substring(identityAt, source.indexOf("public static boolean canBotChatToController("));
        assertTrue(identity.contains("CompanionControllerPolicy.resolve(resolveOwnerUuidForAlias(alias), ownerUuid -> ownerUuid"));
        assertTrue(identity.contains("return UUID.fromString(recruiterUuid)"));
        assertFalse(identity.contains("getPlayerManager()"), "online presence must not change consent identity");
    }

    @Test
    void liveAndShelvedCompanionsShareTheSameAliasAuthorityLookup() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java"));
        assertTrue(source.contains("return resolveControllerUuidForAlias(server, bot == null ? null : bot.getName().getString())"));
        assertTrue(source.contains("server == null || alias == null || alias.isBlank()"));
        assertTrue(source.contains("recruitedAlias.equalsIgnoreCase(alias)"));
        assertTrue(source.contains("return bot == null ? null : resolveOwnerUuidForAlias(bot.getName().getString())"));
        assertEquals(1, source.split(java.util.regex.Pattern.quote("Frens.CONFIG.getOwner(alias)"), -1).length - 1);
    }

    @Test
    void onlineRecordedOwnerWinsOverOriginalRecruiter() {
        UUID owner = UUID.randomUUID();
        assertEquals("current owner", CompanionControllerPolicy.resolve(owner,
                id -> id.equals(owner) ? "current owner" : null, () -> "original recruiter"));
    }

    @Test
    void offlineRecordedOwnerDoesNotHandControlBackToRecruiter() {
        AtomicBoolean recruitedFallbackUsed = new AtomicBoolean();
        assertNull(CompanionControllerPolicy.resolve(UUID.randomUUID(), id -> null, () -> {
            recruitedFallbackUsed.set(true);
            return "original recruiter";
        }));
        assertFalse(recruitedFallbackUsed.get());
    }

    @Test
    void ownerlessRecruitedBotKeepsItsRecruiterFallback() {
        assertEquals("recruiter", CompanionControllerPolicy.resolve(null, id -> {
            fail("an ownerless bot has no owner to look up");
            return null;
        }, () -> "recruiter"));
    }

    @Test
    void ownerlessBotWithoutAnOnlineRecruiterHasNoController() {
        assertNull(CompanionControllerPolicy.resolve(null, id -> "unexpected", () -> null));
    }
}
