package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DespawnSavePolicyTest {

    @Test
    void savesLiveFakePlayerWhenRequested() {
        assertTrue(DespawnSavePolicy.shouldSave(true, true, true, false));
    }

    @Test
    void skipsWhenNotRequested() {
        // /bot forget deletes the data right after removal; the death path must not save.
        assertFalse(DespawnSavePolicy.shouldSave(false, true, true, false));
    }

    @Test
    void skipsDeadBot() {
        // A save here would write the post-death inventory over the death record.
        assertFalse(DespawnSavePolicy.shouldSave(true, true, false, false));
    }

    @Test
    void skipsAlreadyRemovedInstance() {
        assertFalse(DespawnSavePolicy.shouldSave(true, true, true, true));
    }

    @Test
    void skipsRealPlayer() {
        assertFalse(DespawnSavePolicy.shouldSave(true, false, true, false));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13", relative))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static String method(String source, String signatureRegex) {
        var declaration = Pattern.compile(signatureRegex + "\\s*\\{").matcher(source);
        assertTrue(declaration.find(), "method missing: " + signatureRegex);
        int start = declaration.end();
        int depth = 1;
        for (int i = start; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i);
        }
        throw new AssertionError("unterminated method: " + signatureRegex);
    }

    @Test
    void despawnCommandAsksForSave() throws IOException {
        String body = method(source("Commands/modCommandRegistry.java"),
                "static int executeDespawnTargets\\(CommandContext<ServerCommandSource> context, String targetArg, boolean sessionOnly\\)[^{]*");
        assertTrue(body.contains("BotEventHandler.unregisterBot(bot, true)"), "despawn must save first");
        assertFalse(body.contains("BotEventHandler.unregisterBot(bot)"), "despawn must not use the no-save overload");
    }

    @Test
    void removeBotSavesAfterDismountAndBeforeDisconnect() throws IOException {
        String body = method(source("GameAI/services/BotPersistenceService.java"),
                "public static void removeBot\\(ServerPlayerEntity bot, boolean saveFirst\\)");
        int dismount = body.indexOf("bot.stopRiding()");
        int policy = body.indexOf("DespawnSavePolicy.shouldSave(saveFirst");
        int save = body.indexOf("persistViaPlayerManager(server, bot, \"despawn\")");
        int inventory = body.indexOf("BotInventoryStorageService.save(bot)");
        int kill = body.indexOf("fake.kill(");
        int remove = body.indexOf("tryRemoveFromPlayerManager(server, bot)");
        assertTrue(dismount >= 0 && policy > dismount, "save must follow the dismount so the mount record is right");
        assertTrue(save > policy && inventory > save, "save player data, then inventory");
        assertTrue(kill > inventory && remove > inventory, "save before the bot is disconnected and removed");
    }

    @Test
    void defaultOverloadsDoNotSave() throws IOException {
        assertTrue(method(source("GameAI/services/BotPersistenceService.java"),
                "public static void removeBot\\(ServerPlayerEntity bot\\)").contains("removeBot(bot, false)"));
        assertTrue(method(source("GameAI/BotEventHandler.java"),
                "public static void unregisterBot\\(ServerPlayerEntity bot\\)").contains("unregisterBot(bot, false)"));
    }
}
