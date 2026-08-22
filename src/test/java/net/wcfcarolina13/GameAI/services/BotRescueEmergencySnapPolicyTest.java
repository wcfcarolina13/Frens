package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRescueEmergencySnapPolicyTest {

    @Test
    void snapsWhenRecentSuffocationDamageOccursWhileFullyEncased() {
        assertTrue(BotRescueService.shouldAttemptEmergencySnap(true, true, true));
    }

    @Test
    void doesNotSnapAnEncasedBotWithoutRecentSuffocationDamage() {
        assertFalse(BotRescueService.shouldAttemptEmergencySnap(false, true, true));
    }

    @Test
    void doesNotSnapWhenFeetCellStillHasAnEscapeRoute() {
        assertFalse(BotRescueService.shouldAttemptEmergencySnap(true, true, false));
    }

    @Test
    void doesNotSnapWhenHeadCellStillHasAnEscapeRoute() {
        assertFalse(BotRescueService.shouldAttemptEmergencySnap(true, false, true));
    }
}
