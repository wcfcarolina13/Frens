package net.wcfcarolina13.Entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BotIdentityRecoveryPolicyTest {

    private static final String GUARDY = "ea41c42e-a817-4066-82e1-a9c5566cfc1a";
    private static final String LOWER = "417f31bf-25a7-4014-a876-d163245bb8aa";

    @Test
    void theExactSpellingsUuidWinsOverANewerLowerCaseOne() {
        // The sandbox after the old bug: a newer guardy_<uuid> snapshot from the lower-case player.
        assertEquals(GUARDY, BotIdentityRecoveryPolicy.pick("Guardy", List.of(
                new BotIdentityRecoveryPolicy.Candidate(LOWER, "guardy"),
                new BotIdentityRecoveryPolicy.Candidate(GUARDY, "Guardy"))));
    }

    @Test
    void anUncachedSnapshotUuidIsReused() {
        assertEquals(GUARDY, BotIdentityRecoveryPolicy.pick("Guardy", List.of(
                new BotIdentityRecoveryPolicy.Candidate(GUARDY, null))));
    }

    @Test
    void anotherBotsUuidOrNothingGivesNull() {
        assertNull(BotIdentityRecoveryPolicy.pick("Guardy", List.of(
                new BotIdentityRecoveryPolicy.Candidate(GUARDY, "Guardian"))));
        assertNull(BotIdentityRecoveryPolicy.pick("Guardy", List.of(
                new BotIdentityRecoveryPolicy.Candidate("not-a-uuid", "Guardy"))));
        assertNull(BotIdentityRecoveryPolicy.pick("Guardy", List.of()));
    }

    private static final String U1 = "11111111-1111-1111-1111-111111111111";

    @Test
    void withNoSnapshotTheUserCacheIsUsedOnlyForTheExactSpelling() {
        var hit = new BotIdentityRecoveryPolicy.Candidate(U1, "Bob");
        org.junit.jupiter.api.Assertions.assertEquals(U1,
                BotIdentityRecoveryPolicy.choose("Bob", java.util.List.of(), hit).uuid());
        org.junit.jupiter.api.Assertions.assertNull(BotIdentityRecoveryPolicy.choose("bob", java.util.List.of(), hit));
        org.junit.jupiter.api.Assertions.assertNull(BotIdentityRecoveryPolicy.choose("Bob", null, null));
    }

    @Test
    void theProfileTakesTheMatchedUuidsCachedSpelling() {
        var matched = new BotIdentityRecoveryPolicy.Candidate(U1, "Bob");
        org.junit.jupiter.api.Assertions.assertEquals("Bob", BotIdentityRecoveryPolicy.profileName("bob", matched));
        org.junit.jupiter.api.Assertions.assertEquals("bob", BotIdentityRecoveryPolicy.profileName("bob",
                new BotIdentityRecoveryPolicy.Candidate(U1, null)));
        org.junit.jupiter.api.Assertions.assertEquals("bob", BotIdentityRecoveryPolicy.profileName("bob", null));
    }
}
