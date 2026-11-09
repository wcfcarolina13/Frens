package net.wcfcarolina13.GameAI;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepFloorPolicyTest {

    @Test
    void groundAndAStairEdgeAreFineAPitIsNot() {
        assertTrue(StepFloorPolicy.allows(1));
        assertTrue(StepFloorPolicy.allows(2));
        assertFalse(StepFloorPolicy.allows(3));
    }

    @Test
    void moveRelativeChecksTheFloorAndTheKitingStepIsHoleAware() throws Exception {
        String actions = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/BotActions.java"));
        assertTrue(actions.contains("StepFloorPolicy.allows(floorDepth)"));
        String handler = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        assertTrue(handler.contains("moveTowardInCombat(bot, positionOf(closest), 1.5D, false);"));
    }
}
