package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.BotAccessPolicy.Decision;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAccessPolicyTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // ── authorize ──────────────────────────────────────────────────────────

    @Test
    void humanOrUnregisteredTargetIsDeniedEvenForOpAndHost() {
        assertEquals(Decision.DENY_NOT_BOT, BotAccessPolicy.authorize(STRANGER, null, false, true, false));
        assertEquals(Decision.DENY_NOT_BOT, BotAccessPolicy.authorize(STRANGER, null, false, false, true));
        assertEquals(Decision.DENY_NOT_BOT, BotAccessPolicy.authorize(OWNER, OWNER, false, true, true));
        assertEquals(Decision.DENY_NOT_BOT, BotAccessPolicy.authorize(null, null, false, false, false));
    }

    @Test
    void ownerIsAllowed() {
        assertEquals(Decision.ALLOW_OWNER, BotAccessPolicy.authorize(OWNER, OWNER, true, false, false));
    }

    @Test
    void ownerWinsOverOpAndHost() {
        assertEquals(Decision.ALLOW_OWNER, BotAccessPolicy.authorize(OWNER, OWNER, true, true, true));
    }

    @Test
    void strangerIsDeniedOnOwnedBot() {
        assertEquals(Decision.DENY_NOT_OWNER, BotAccessPolicy.authorize(STRANGER, OWNER, true, false, false));
    }

    @Test
    void unownedBotIsDeniedToOrdinaryPlayer() {
        assertEquals(Decision.DENY_UNOWNED, BotAccessPolicy.authorize(STRANGER, null, true, false, false));
    }

    @Test
    void opIsAllowedOnOwnedAndUnownedBots() {
        assertEquals(Decision.ALLOW_OP, BotAccessPolicy.authorize(STRANGER, OWNER, true, true, false));
        assertEquals(Decision.ALLOW_OP, BotAccessPolicy.authorize(STRANGER, null, true, true, false));
    }

    @Test
    void opWinsOverHost() {
        assertEquals(Decision.ALLOW_OP, BotAccessPolicy.authorize(STRANGER, OWNER, true, true, true));
    }

    @Test
    void hostWithoutOpIsAllowedSoSinglePlayerWithCheatsOffWorks() {
        assertEquals(Decision.ALLOW_HOST, BotAccessPolicy.authorize(STRANGER, OWNER, true, false, true));
        assertEquals(Decision.ALLOW_HOST, BotAccessPolicy.authorize(STRANGER, null, true, false, true));
    }

    @Test
    void nullRequesterIsNeverTheOwner() {
        assertEquals(Decision.DENY_UNOWNED, BotAccessPolicy.authorize(null, null, true, false, false));
        assertEquals(Decision.DENY_NOT_OWNER, BotAccessPolicy.authorize(null, OWNER, true, false, false));
    }

    @Test
    void allowedIsTrueOnlyForAllowDecisions() {
        assertTrue(BotAccessPolicy.allowed(Decision.ALLOW_OWNER));
        assertTrue(BotAccessPolicy.allowed(Decision.ALLOW_OP));
        assertTrue(BotAccessPolicy.allowed(Decision.ALLOW_HOST));
        assertFalse(BotAccessPolicy.allowed(Decision.DENY_NOT_BOT));
        assertFalse(BotAccessPolicy.allowed(Decision.DENY_NOT_OWNER));
        assertFalse(BotAccessPolicy.allowed(Decision.DENY_UNOWNED));
        assertFalse(BotAccessPolicy.allowed(null));
    }

    // ── logSafe ────────────────────────────────────────────────────────────

    @Test
    void logSafeHandlesNullAndControlCharacters() {
        assertEquals("null", BotAccessPolicy.logSafe(null));
        assertEquals("a?b?c?", BotAccessPolicy.logSafe("a\nb\rc\u007F"));
    }

    @Test
    void logSafeTruncatesAt32Chars() {
        String exact = "x".repeat(32);
        assertEquals(exact, BotAccessPolicy.logSafe(exact));
        assertEquals(exact + "…", BotAccessPolicy.logSafe(exact + "yyyy"));
    }

    // ── shouldLogDeny ──────────────────────────────────────────────────────

    @Test
    void denyLogIsRateLimitedToOncePerFiveSeconds() {
        assertTrue(BotAccessPolicy.shouldLogDeny(null, 1_000L));
        assertFalse(BotAccessPolicy.shouldLogDeny(1_000L, 1_000L));
        assertFalse(BotAccessPolicy.shouldLogDeny(1_000L, 5_999L));
        assertTrue(BotAccessPolicy.shouldLogDeny(1_000L, 6_000L));
    }

    @Test
    void denyLogFiresWhenClockGoesBackwards() {
        assertTrue(BotAccessPolicy.shouldLogDeny(10_000L, 9_000L));
    }
}
