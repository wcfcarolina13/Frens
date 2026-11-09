package net.wcfcarolina13.Commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source pins: a per-bot /bot config toggle that BotControlApplier.applyToBot re-applies from the saved
 * controls must also write those saved controls. Every mode command (follow, stop, come, guard) calls
 * applyToBot through registerBot, so an in-memory-only setting lasted until the next one (1.1.299:
 * teleportDuringSkills on, /bot stop, then /bot come walked 78 blocks).
 */
class BotConfigPersistenceTest {

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    @Test
    void perBotTogglesWriteTheSavedControls() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"),
                StandardCharsets.UTF_8);
        assertTrue(body(src, "private static int executeTeleportConfig(").contains("savedBotControl(context, bot).setTeleportDuringSkills(enabled)"));
        assertTrue(body(src, "private static int executeInventoryFullConfig(").contains("savedBotControl(context, bot).setPauseOnFullInventory(enabled)"));
        assertTrue(body(src, "private static int executeTeleportConfigTargets(").contains("Frens.CONFIG.save()"));
        assertTrue(body(src, "private static int executeInventoryFullConfigTargets(").contains("Frens.CONFIG.save()"));
    }

    @Test
    void applierStillReappliesBothFromTheSavedControls() throws IOException {
        String applier = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/GameAI/services/BotControlApplier.java"),
                StandardCharsets.UTF_8);
        String apply = body(applier, "public static void applyToBot(");
        assertTrue(apply.contains("settings.isTeleportDuringSkills()") && apply.contains("settings.isPauseOnFullInventory()"));
    }

    @Test
    void comeTeleportClearsTheStopLatchFirst() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java"),
                StandardCharsets.UTF_8);
        String come = body(src, "private static int executeCome(");
        int clear = come.lastIndexOf("TaskService.clearAbortLatch(bot.getUuid())");
        int teleport = come.indexOf("MovementService.execute(bot.getCommandSource(), bot, plan, Boolean.TRUE)");
        assertTrue(clear > 0 && clear < teleport, "the teleport come clears the abort latch before moving");
    }
}
