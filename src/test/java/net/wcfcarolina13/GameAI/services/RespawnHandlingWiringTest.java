package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class RespawnHandlingWiringTest {
    @Test
    void fallbackRejectsOtherServerUnregisteredAndReplacedLifecyclesBeforeMutation() throws IOException {
        String ensure = method("ensureRespawnHandled");
        assertTrue(ensure.contains("Object lifecycle = RESPAWN_HANDLING.captureLifecycle(botUuid)"));
        String callback = block(ensure, "srv.send(new ServerTask((int) checkTick, () ->");
        int guard = callback.indexOf("if (BotEventHandler.server != srv");
        int mutate = callback.indexOf("currentBot.setHealth(");
        assertTrue(guard >= 0 && guard < mutate);
        String guarded = callback.substring(guard, callback.indexOf("LOGGER.warn("));
        assertTrue(guarded.contains("!BotRegistry.isRegistered(botUuid)"));
        assertTrue(guarded.contains("!RESPAWN_HANDLING.isCurrent(botUuid, lifecycle)"));
        assertTrue(guarded.contains("return;"));
        assertTrue(guarded.contains("RESPAWN_HANDLING.handledWithin(botUuid, checkTick, 5)"));
    }

    @Test
    void completionAndDiagnosticsUseTheSpecificBotsTick() throws IOException {
        assertTrue(method("onBotRespawn").contains(
                "RESPAWN_HANDLING.recordHandled(bot.getUuid(), srv.getTicks())"));
        assertTrue(method("updateBehavior").contains(
                "RESPAWN_HANDLING.lastHandledTick(bot.getUuid())"));
        assertFalse(source().contains("private static long lastRespawnHandledTick"));
    }

    @Test
    void unregisterAndSessionResetInvalidateQueuedWorkWithoutDroppingOtherCleanup() throws IOException {
        String unregister = method("unregisterBot");
        int forget = unregister.indexOf("RESPAWN_HANDLING.forget(uuid)");
        assertTrue(forget >= 0 && forget < unregister.indexOf("BotPersistenceService.removeBot(bot)"));
        assertTrue(unregister.contains("ToolSelector.forgetBot(uuid)"));
        String reset = method("resetAll");
        assertTrue(reset.contains("RESPAWN_HANDLING.reset()"));
        assertTrue(reset.contains("ToolSelector.resetSession()"));
    }

    @Test
    void fallbackRetainsTheExistingQueueMechanismAndEntityResolution() throws IOException {
        String ensure = method("ensureRespawnHandled");
        assertTrue(ensure.contains("long checkTick = srv.getTicks() + 5;"));
        assertTrue(ensure.contains("srv.send(new ServerTask((int) checkTick, () ->"));
        assertTrue(ensure.contains("srv.getPlayerManager().getPlayer(botUuid)"));
        assertTrue(ensure.contains("if (currentBot == null) currentBot = bot;"));
        assertTrue(ensure.contains("onBotRespawn(currentBot)"));
    }

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", " ");
    }

    private static String method(String name) throws IOException {
        String source = source();
        var declaration = Pattern.compile("public static [\\w.<>]+ " + name + "\\([^)]*\\)\\s*\\{")
                .matcher(source);
        assertTrue(declaration.find(), "Missing method: " + name);
        return block(source, declaration.group());
    }

    private static String block(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "Missing scope: " + marker);
        int start = source.indexOf('{', at);
        int depth = 1;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed scope: " + marker);
    }
}
