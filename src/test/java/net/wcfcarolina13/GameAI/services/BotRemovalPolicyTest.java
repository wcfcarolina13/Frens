package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotRemovalPolicyTest {

    @Test
    void registeredInBothPlacesIsPresentAndGetsVanillaRemove() {
        assertTrue(BotRemovalPolicy.isStillRegistered(true, true));
        assertTrue(BotRemovalPolicy.shouldCallVanillaRemove(true, true));
    }

    @Test
    void mapOnlyLeakStillCountsAsPresent() {
        // The pre-fix fallback removed the bot from the list but left the map entry.
        assertTrue(BotRemovalPolicy.isStillRegistered(true, false));
        assertTrue(BotRemovalPolicy.shouldCallVanillaRemove(true, false));
    }

    @Test
    void listOnlyStillCountsAsPresent() {
        assertTrue(BotRemovalPolicy.isStillRegistered(false, true));
        assertTrue(BotRemovalPolicy.shouldCallVanillaRemove(false, true));
    }

    @Test
    void goneFromBothIsRemovedAndSkipsSecondVanillaRemove() {
        // Also covers a newer same-UUID entity owning the map slot: mapHoldsThisBot is false.
        assertFalse(BotRemovalPolicy.isStillRegistered(false, false));
        assertFalse(BotRemovalPolicy.shouldCallVanillaRemove(false, false));
    }

    @Test
    void vanillaRemoveOfLiveBotOutsideFrensRemovalSaves() {
        // Singleplayer quit: IntegratedServer.stop removes the bot before SERVER_STOPPING.
        assertTrue(BotRemovalPolicy.shouldSaveOnVanillaRemove(true, false, false));
    }

    @Test
    void frensOwnRemovalDoesNotSave() {
        // zzz logoff / travel already saved; a stale duplicate must not overwrite the snapshot.
        assertFalse(BotRemovalPolicy.shouldSaveOnVanillaRemove(true, true, false));
    }

    @Test
    void deadBotDoesNotSave() {
        assertFalse(BotRemovalPolicy.shouldSaveOnVanillaRemove(true, false, true));
    }

    @Test
    void realPlayerNeverSaves() {
        assertFalse(BotRemovalPolicy.shouldSaveOnVanillaRemove(false, false, false));
        assertFalse(BotRemovalPolicy.shouldSaveOnVanillaRemove(false, true, true));
    }
}
