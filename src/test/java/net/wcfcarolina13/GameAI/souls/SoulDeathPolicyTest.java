package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulDeathPolicyTest {

    @Test
    void autoRespawnOnKeepsTheProfileActive() {
        assertFalse(SoulDeathPolicy.deactivateOnDeath(true));
    }

    @Test
    void autoRespawnOffShelvesTheBotAndDeactivatesTheProfile() {
        assertTrue(SoulDeathPolicy.deactivateOnDeath(false));
    }
}
