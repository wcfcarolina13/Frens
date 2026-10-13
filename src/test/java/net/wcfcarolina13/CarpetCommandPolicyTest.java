package net.wcfcarolina13;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarpetCommandPolicyTest {

    @Test
    void carpetPlayerCommandsAreSkippedWithoutCarpet() {
        assertFalse(CarpetCommandPolicy.shouldDispatch("player Jake stop", false));
        assertFalse(CarpetCommandPolicy.shouldDispatch("/player Jake move forward", false));
        assertFalse(CarpetCommandPolicy.shouldDispatch("  player Jake turn left", false));
    }

    @Test
    void carpetPlayerCommandsDispatchWhenCarpetIsLoaded() {
        assertTrue(CarpetCommandPolicy.shouldDispatch("player Jake stop", true));
        assertTrue(CarpetCommandPolicy.shouldDispatch("/player Jake look north", true));
    }

    @Test
    void otherCommandsAlwaysDispatch() {
        assertTrue(CarpetCommandPolicy.shouldDispatch("bot stop Jake", false));
        assertTrue(CarpetCommandPolicy.shouldDispatch("/give @s minecraft:stick", false));
        assertTrue(CarpetCommandPolicy.shouldDispatch("bot stop Jake", true));
    }

    @Test
    void nearMissPrefixesAreNotCarpetCommands() {
        assertTrue(CarpetCommandPolicy.shouldDispatch("players list", false));
        assertTrue(CarpetCommandPolicy.shouldDispatch("playerstats", false));
        assertTrue(CarpetCommandPolicy.shouldDispatch("Player Jake stop", false), "Brigadier literals are case-sensitive");
        assertFalse(CarpetCommandPolicy.isCarpetPlayerCommand("player"));
    }

    @Test
    void nullOrBlankNeverDispatches() {
        assertFalse(CarpetCommandPolicy.shouldDispatch(null, true));
        assertFalse(CarpetCommandPolicy.shouldDispatch("   ", true));
        assertFalse(CarpetCommandPolicy.isCarpetPlayerCommand(null));
    }
}
