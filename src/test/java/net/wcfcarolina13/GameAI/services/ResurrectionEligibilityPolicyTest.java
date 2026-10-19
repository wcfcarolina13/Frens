package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.ResurrectionEligibilityPolicy.Candidate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResurrectionEligibilityPolicyTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Candidate dead(String alias, UUID owner, long diedAt) {
        return new Candidate(alias, owner, diedAt, false, false);
    }

    // ── isDeadAndShelved ───────────────────────────────────────────────────

    @Test
    void deadOfflineWithAutoRespawnOffQualifies() {
        assertTrue(ResurrectionEligibilityPolicy.isDeadAndShelved(dead("Bob", OWNER, 100L)));
    }

    @Test
    void neverDiedHereDoesNotQualify() {
        assertFalse(ResurrectionEligibilityPolicy.isDeadAndShelved(new Candidate("Bob", OWNER, null, false, false)));
    }

    @Test
    void onlineBotDoesNotQualifyEvenWithADeathRecord() {
        // Death records are never cleared, so a bot that came back still has one.
        assertFalse(ResurrectionEligibilityPolicy.isDeadAndShelved(new Candidate("Bob", OWNER, 100L, true, false)));
    }

    @Test
    void autoRespawnOnDoesNotQualify() {
        assertFalse(ResurrectionEligibilityPolicy.isDeadAndShelved(new Candidate("Bob", OWNER, 100L, false, true)));
    }

    @Test
    void nullOrBlankAliasDoesNotQualify() {
        assertFalse(ResurrectionEligibilityPolicy.isDeadAndShelved(null));
        assertFalse(ResurrectionEligibilityPolicy.isDeadAndShelved(dead(null, OWNER, 100L)));
        assertFalse(ResurrectionEligibilityPolicy.isDeadAndShelved(dead("  ", OWNER, 100L)));
    }

    // ── authorized ─────────────────────────────────────────────────────────

    @Test
    void ownerOpAndHostAreAuthorized() {
        assertTrue(ResurrectionEligibilityPolicy.authorized(OWNER, OWNER, false, false));
        assertTrue(ResurrectionEligibilityPolicy.authorized(STRANGER, OWNER, true, false));
        assertTrue(ResurrectionEligibilityPolicy.authorized(STRANGER, OWNER, false, true));
        assertTrue(ResurrectionEligibilityPolicy.authorized(STRANGER, null, true, false));
        assertTrue(ResurrectionEligibilityPolicy.authorized(STRANGER, null, false, true));
    }

    @Test
    void strangerIsDeniedOnOwnedAndUnownedBots() {
        assertFalse(ResurrectionEligibilityPolicy.authorized(STRANGER, OWNER, false, false));
        assertFalse(ResurrectionEligibilityPolicy.authorized(STRANGER, null, false, false));
        assertFalse(ResurrectionEligibilityPolicy.authorized(null, null, false, false));
    }

    // ── chooseBot ──────────────────────────────────────────────────────────

    @Test
    void ownerGetsTheirOnlyDeadBot() {
        assertEquals("Bob", ResurrectionEligibilityPolicy.chooseBot(OWNER, false, false,
                List.of(dead("Bob", OWNER, 100L))));
    }

    @Test
    void strangerGetsNothingFromSomeoneElsesDeadBot() {
        assertNull(ResurrectionEligibilityPolicy.chooseBot(STRANGER, false, false,
                List.of(dead("Bob", OWNER, 100L))));
    }

    @Test
    void ordinaryPlayerSkipsBotsTheyDoNotOwnAndGetsTheirOwn() {
        assertEquals("Mine", ResurrectionEligibilityPolicy.chooseBot(OWNER, false, false,
                List.of(dead("Theirs", STRANGER, 900L), dead("Mine", OWNER, 100L))));
    }

    @Test
    void severalQualify_latestDeathWins() {
        assertEquals("Ana", ResurrectionEligibilityPolicy.chooseBot(OWNER, false, false,
                List.of(dead("Bob", OWNER, 100L), dead("Ana", OWNER, 300L), dead("Cy", OWNER, 200L))));
    }

    @Test
    void tieKeepsTheEarlierCandidate() {
        assertEquals("Bob", ResurrectionEligibilityPolicy.chooseBot(OWNER, false, false,
                List.of(dead("Bob", OWNER, 100L), dead("Ana", OWNER, 100L))));
    }

    @Test
    void latestDeathThatIsNotShelvedIsSkipped() {
        assertEquals("Bob", ResurrectionEligibilityPolicy.chooseBot(OWNER, false, false, List.of(
                dead("Bob", OWNER, 100L),
                new Candidate("Online", OWNER, 900L, true, false),
                new Candidate("Auto", OWNER, 800L, false, true))));
    }

    @Test
    void opAndHostCanResurrectAnyonesBot() {
        List<Candidate> c = List.of(dead("Bob", STRANGER, 100L), dead("Unowned", null, 200L));
        assertEquals("Unowned", ResurrectionEligibilityPolicy.chooseBot(OWNER, true, false, c));
        assertEquals("Unowned", ResurrectionEligibilityPolicy.chooseBot(OWNER, false, true, c));
    }

    @Test
    void emptyOrNullCandidateListGivesNull() {
        assertNull(ResurrectionEligibilityPolicy.chooseBot(OWNER, true, true, null));
        assertNull(ResurrectionEligibilityPolicy.chooseBot(OWNER, true, true, new ArrayList<>()));
    }

    // ── parseOwner ─────────────────────────────────────────────────────────

    @Test
    void parseOwnerHandlesValidBlankAndMalformed() {
        assertEquals(OWNER, ResurrectionEligibilityPolicy.parseOwner(" " + OWNER + " "));
        assertNull(ResurrectionEligibilityPolicy.parseOwner(null));
        assertNull(ResurrectionEligibilityPolicy.parseOwner("  "));
        assertNull(ResurrectionEligibilityPolicy.parseOwner("not-a-uuid"));
    }
}
