package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndCreditsPolicyTest {

    @Test
    void vanillaDetachesOnlyBeforeTheCreditsWereSeen() {
        assertEquals(EndCreditsPolicy.ExitPath.DETACH_AND_RESPAWN, EndCreditsPolicy.vanillaExitPath(false));
        assertEquals(EndCreditsPolicy.ExitPath.PORTAL_TELEPORT, EndCreditsPolicy.vanillaExitPath(true));
    }

    @Test
    void botKeepsTheCreditsUnseen() {
        assertFalse(EndCreditsPolicy.seenCreditsToKeep(true, true));
        assertFalse(EndCreditsPolicy.seenCreditsToKeep(true, false));
    }

    @Test
    void otherPlayersKeepVanillasValue() {
        assertTrue(EndCreditsPolicy.seenCreditsToKeep(false, true));
        assertFalse(EndCreditsPolicy.seenCreditsToKeep(false, false));
    }

    @Test
    void everyExitOfABotTakesTheDetachPath() {
        // Mirrors vanilla: detach sets the flag, copyFrom carries it, the bot override resets it.
        boolean seen = false;
        for (int trip = 1; trip <= 3; trip++) {
            assertEquals(EndCreditsPolicy.ExitPath.DETACH_AND_RESPAWN, EndCreditsPolicy.vanillaExitPath(seen),
                    "trip " + trip);
            seen = true; // ServerPlayerEntity.detachForDimensionChange
            seen = EndCreditsPolicy.seenCreditsToKeep(true, seen); // createFakePlayer override
        }
    }

    @Test
    void aBotSavedAfterAnOlderExitIsResetOnLoad() {
        // A save from 1.1.295-1.1.298 can hold seenCredits=true; readCustomData resets it.
        boolean loaded = EndCreditsPolicy.seenCreditsToKeep(true, true);
        assertEquals(EndCreditsPolicy.ExitPath.DETACH_AND_RESPAWN, EndCreditsPolicy.vanillaExitPath(loaded));
    }
}
