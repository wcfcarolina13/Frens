package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnResultPolicyTest {

    @Test
    void foundBotIsReadyEvenIfSomethingIsStillInFlight() {
        assertEquals(SpawnResultPolicy.Result.READY, SpawnResultPolicy.classify(true, false));
        assertEquals(SpawnResultPolicy.Result.READY, SpawnResultPolicy.classify(true, true));
    }

    @Test
    void missingBotWithSpawnInFlightIsPendingNotFailed() {
        // Online-mode dedicated server: the Mojang profile lookup has not finished yet.
        assertEquals(SpawnResultPolicy.Result.PENDING, SpawnResultPolicy.classify(false, true));
    }

    @Test
    void missingBotAfterSpawnFinishedIsFailed() {
        assertEquals(SpawnResultPolicy.Result.FAILED, SpawnResultPolicy.classify(false, false));
    }

    @Test
    void messagesAreNeutralAndDashFree() {
        String pending = SpawnResultPolicy.pendingMessage("Jake");
        assertTrue(pending.startsWith("Spawning Jake"));
        assertFalse(pending.toLowerCase().contains("error"));
        String aiOff = SpawnResultPolicy.aiOffMessage("Jake", "openai");
        assertTrue(aiOff.startsWith("AI is off for Jake."));
        for (String text : new String[]{pending, aiOff}) {
            assertFalse(text.contains("—"), text);
            assertFalse(text.contains(";"), text);
        }
    }
}
