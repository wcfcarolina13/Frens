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
        assertTrue(ensure.contains("onBotRespawn(currentBot, false)"));
    }

    @Test
    void respawnResetStepsAreGatedByThePolicy() throws IOException {
        String reset = method("onBotRespawn");
        assertTrue(reset.contains("RespawnResetPolicy.plan(alive)"));
        assertTrue(reset.indexOf("registerBot(bot)") < reset.indexOf("if (plan.clearLeash()"));
        assertTrue(reset.contains("RideSyncService.clearBotState(bot.getUuid())"));
        String targets = block(reset, "if (plan.clearTargets())");
        assertTrue(targets.contains("setBaseTarget(bot, null)") && targets.contains("setFollowTarget(bot, null)"));
        assertTrue(reset.contains("plan.relocate() ? moveToRespawnPoint(bot, srv) : null"));
        String mode = block(reset, "if (plan.resetMode())");
        assertTrue(mode.contains("setMode(bot, Mode.IDLE)"));
        assertTrue(block(reset, "if (plan.regroupChat())").contains("has regrouped and is ready to re-engage."));
        assertTrue(block(reset, "if (plan.lateAbortMessage() != null)").contains("TaskService.forceAbort("));
        // Dead flags and the tracker are cleared on both paths, outside every plan gate.
        assertTrue(reset.contains("setDead(false)"));
        assertFalse(source().contains("\"§cTask aborted due to bot respawn.\""));
    }

    @Test
    void respawnListenerPassesAliveAndGatesTheSoulNote() throws IOException {
        String frens = flatten(Path.of("src/main/java/net/wcfcarolina13/Frens.java"));
        assertTrue(frens.contains("AutoFaceEntity.handleBotRespawn(newPlayer, alive)"));
        assertTrue(frens.contains("BotEventHandler.onBotRespawn(newPlayer, alive)"));
        String soul = block(frens, "if (net.wcfcarolina13.GameAI.services.RespawnResetPolicy.plan(alive).noteSoulRespawn())");
        assertTrue(soul.contains("SoulEventObserver.onBotRespawn(newPlayer)"));

        String autoFace = flatten(Path.of("src/main/java/net/wcfcarolina13/Entity/AutoFaceEntity.java"));
        String restart = block(autoFace, "public static void handleBotRespawn(ServerPlayerEntity bot, boolean alive)");
        assertTrue(restart.contains("TaskService.onBotRespawn(bot, plan.taskAbortMessage())"));
        assertTrue(block(restart, "if (plan.sleepBeforeRestart())").contains("Thread.sleep(100)"));
        assertTrue(restart.indexOf("stopAutoFace(bot)") < restart.indexOf("startAutoFace(bot)"));
    }

    @Test
    void theNameMatchedPerBotRespawnListenerIsGone() {
        assertFalse(Files.exists(Path.of("src/main/java/net/wcfcarolina13/Entity/RespawnHandler.java")));
    }

    private static String source() throws IOException {
        return flatten(Path.of("src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java"));
    }

    private static String flatten(Path path) throws IOException {
        return Files.readString(path)
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
