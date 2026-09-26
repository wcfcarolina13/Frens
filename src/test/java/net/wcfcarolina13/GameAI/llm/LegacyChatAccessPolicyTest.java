package net.wcfcarolina13.GameAI.llm;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyChatAccessPolicyTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void ownerPasses() {
        assertTrue(LegacyChatAccessPolicy.isAuthorized(false, false, OWNER, OWNER));
    }

    @Test
    void operatorPassesOnForeignAndUnownedBots() {
        assertTrue(LegacyChatAccessPolicy.isAuthorized(false, true, STRANGER, OWNER));
        assertTrue(LegacyChatAccessPolicy.isAuthorized(false, true, STRANGER, null));
    }

    @Test
    void integratedHostPassesWithoutOperatorStatus() {
        assertTrue(LegacyChatAccessPolicy.isAuthorized(true, false, STRANGER, OWNER));
        assertTrue(LegacyChatAccessPolicy.isAuthorized(true, false, STRANGER, null));
    }

    @Test
    void foreignNonOperatorDenied() {
        assertFalse(LegacyChatAccessPolicy.isAuthorized(false, false, STRANGER, OWNER));
    }

    @Test
    void unownedBotDeniedForNonOperator() {
        assertFalse(LegacyChatAccessPolicy.isAuthorized(false, false, STRANGER, null));
    }

    @Test
    void missingActorDeniedEvenForHostOrOperator() {
        assertFalse(LegacyChatAccessPolicy.isAuthorized(true, true, null, OWNER));
    }

    // ── mayConsumeConfirmation (re-check when the "yes" arrives) ───────────

    @Test
    void confirmationConsumedOnlyWhenStillAuthorizedForARegisteredBot() {
        assertTrue(LegacyChatAccessPolicy.mayConsumeConfirmation(true, true, true));
    }

    @Test
    void confirmationDroppedAfterLosingControlOfTheBot() {
        assertFalse(LegacyChatAccessPolicy.mayConsumeConfirmation(true, true, false));
    }

    @Test
    void confirmationDroppedWhenTheNameNoLongerResolvesToARegisteredBot() {
        assertFalse(LegacyChatAccessPolicy.mayConsumeConfirmation(true, false, true));
    }

    @Test
    void confirmationDroppedWhenTheSenderIsGone() {
        assertFalse(LegacyChatAccessPolicy.mayConsumeConfirmation(false, true, true));
    }
}
