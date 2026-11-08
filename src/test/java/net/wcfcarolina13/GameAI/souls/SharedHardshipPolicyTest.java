package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedHardshipPolicyTest {

    @Test
    void onlyAnAttackerThatIsNeitherTheOwnerNorABotMakesAFight() {
        assertTrue(SharedHardshipPolicy.isFight(true, false, false), "a mob or another player");
        assertFalse(SharedHardshipPolicy.isFight(false, false, false), "no attacker: fall, pearl, /kill");
        assertFalse(SharedHardshipPolicy.isFight(true, true, false), "the owner's own arrow or TNT");
        assertFalse(SharedHardshipPolicy.isFight(true, false, true), "a bot hitting its owner");
    }
}
