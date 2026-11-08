package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Frens bot's death is revived in place and never reaches PlayerManager.respawnPlayer, so the
 * AFTER_RESPAWN listener (which notes the soul respawn) never runs for it. The death fallback in
 * BotEventHandler.ensureRespawnHandled must note it instead, gated by the same policy flag.
 */
class DeathSoulRespawnWiringTest {

    @Test
    void deathFallbackNotesTheSoulRespawnAfterTheReset() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
        int start = src.indexOf("public static void ensureRespawnHandled(");
        int end = src.indexOf("public static boolean updateBehavior(", start);
        assertTrue(start >= 0 && end > start);
        String body = src.substring(start, end);
        int reset = body.indexOf("onBotRespawn(currentBot, false)");
        int gate = body.indexOf("RespawnResetPolicy.plan(false).noteSoulRespawn()");
        int note = body.indexOf("SoulEventObserver.onBotRespawn(currentBot)");
        assertTrue(reset >= 0 && gate > reset && note > gate, "soul note after the reset, behind the policy flag");
    }

    @Test
    void thePolicyNotesTheSoulRespawnOnADeath() {
        assertTrue(RespawnResetPolicy.plan(false).noteSoulRespawn());
    }
}
