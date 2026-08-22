package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAutoCookingServicePolicyTest {

    @Test
    void doesNotStartAutoCookAboveConfiguredThreshold() {
        assertFalse(BotAutoCookingService.shouldStartEmergencyAutoCook(11, true, true));
    }

    @Test
    void doesNotStartAutoCookWithoutCookableFood() {
        assertFalse(BotAutoCookingService.shouldStartEmergencyAutoCook(10, false, true));
    }

    @Test
    void startsAutoCookAtConfiguredThreshold() {
        assertTrue(BotAutoCookingService.shouldStartEmergencyAutoCook(10, true, true));
    }

    @Test
    void onlyCollectsReadyFoodAtOrBelowConfiguredThreshold() {
        assertFalse(BotAutoCookingService.shouldCollectReadyFood(11, true));
        assertTrue(BotAutoCookingService.shouldCollectReadyFood(10, true));
    }
}
