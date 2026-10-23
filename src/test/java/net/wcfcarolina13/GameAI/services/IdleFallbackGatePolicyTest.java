package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleFallbackGatePolicyTest {

    private static final long NOW = 10_000L;

    @Test
    void everyHobbyUsesItsOwnBackoffRegardlessOfTools() {
        // Hunt and collect_dirt use the same gate as any other picker hobby.
        assertFalse(IdleFallbackGatePolicy.allowsHobby(true, NOW, NOW + 1200L));
        assertTrue(IdleFallbackGatePolicy.allowsHobby(true, NOW, NOW));
        assertTrue(IdleFallbackGatePolicy.allowsHobby(true, NOW, 0L));
        assertFalse(IdleFallbackGatePolicy.allowsHobby(false, NOW, 0L));
    }

    @Test
    void cooldownOrBackoffPassWithNoWorkDoesNotClaimTheTick() {
        assertFalse(IdleFallbackGatePolicy.reportsWork(false, false, false));
    }

    @Test
    void startedWoodcutClaimsTheTick() {
        assertTrue(IdleFallbackGatePolicy.reportsWork(true, false, false));
    }

    @Test
    void movedOrCraftedClaimsTheTickEvenWhenBackedOff() {
        assertTrue(IdleFallbackGatePolicy.reportsWork(false, true, false));
        assertTrue(IdleFallbackGatePolicy.reportsWork(false, false, true));
        assertTrue(IdleFallbackGatePolicy.reportsWork(false, true, true));
    }

    @Test
    void axelessBotInsideHobbyBackoffIsBlocked() {
        assertTrue(IdleFallbackGatePolicy.blocksResourcesWoodcut(false, NOW, NOW + 1200L, 0L));
    }

    @Test
    void axelessBotInsideFlatCooldownIsBlocked() {
        assertTrue(IdleFallbackGatePolicy.blocksResourcesWoodcut(false, NOW, 0L, NOW + 240L));
    }

    @Test
    void axelessBotWithBothWindowsElapsedIsNotBlocked() {
        assertFalse(IdleFallbackGatePolicy.blocksResourcesWoodcut(false, NOW, NOW, NOW - 1L));
        assertFalse(IdleFallbackGatePolicy.blocksResourcesWoodcut(false, NOW, 0L, 0L));
    }

    @Test
    void heldAxeDoesNotBypassHobbyFailureBackoff() {
        assertTrue(IdleFallbackGatePolicy.blocksResourcesWoodcut(true, NOW, NOW + 12_000L, NOW + 12_000L));
        assertTrue(IdleFallbackGatePolicy.blocksResourcesWoodcut(true, NOW, NOW + 1L, 0L));
    }

    @Test
    void heldAxeBypassesOnlyTheFlatNoAxeCooldown() {
        assertFalse(IdleFallbackGatePolicy.blocksResourcesWoodcut(true, NOW, NOW, NOW + 12_000L));
        assertFalse(IdleFallbackGatePolicy.blocksResourcesWoodcut(true, NOW, 0L, NOW + 12_000L));
    }

    @Test
    void unchangedToolsKeepAllWoodcutRoutesBlockedWhileOtherHobbiesCanRun() {
        int failedReadiness = HobbyBackoffPolicy.axeReadiness(true, false);
        long woodcutBackoff = NOW + 1200L;
        for (long tick = NOW; tick < woodcutBackoff; tick++) {
            assertFalse(HobbyBackoffPolicy.availabilityImproved(failedReadiness,
                    HobbyBackoffPolicy.axeReadiness(true, false)));
            // Ordinary fallback cooldowns can be cleared by unrelated tasks or movement.
            assertTrue(IdleFallbackGatePolicy.blocksResourcesWoodcut(true, tick, woodcutBackoff, 0L));
            assertFalse(IdleFallbackGatePolicy.allowsHobby(true, tick, woodcutBackoff));
            assertTrue(IdleFallbackGatePolicy.allowsHobby(true, tick, 0L));
        }
        assertFalse(IdleFallbackGatePolicy.blocksResourcesWoodcut(true, woodcutBackoff, woodcutBackoff, 0L));
    }
}
