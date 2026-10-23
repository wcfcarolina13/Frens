package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolSelectorLifecycleTest {
    private static final Path MAIN = Path.of("src/main/java/net/wcfcarolina13");

    @Test
    void forgettingABotRemovesOnlyItsCooldownAndAcceptsNull() throws IOException {
        String body = method(selector(), "public static void forgetBot(");
        assertTrue(body.contains("if (botUuid != null)"));
        assertTrue(body.contains("LAST_BUNDLE_REACH_TICK.remove(botUuid)"));
        assertFalse(body.contains(".clear()"));
    }

    @Test
    void sessionResetClearsAllToolBundleCooldowns() throws IOException {
        assertTrue(method(selector(), "public static void resetSession(")
                .contains("LAST_BUNDLE_REACH_TICK.clear()"));
    }

    @Test
    void unregisterForgetsTheToolCooldownAlongsideCombatCleanup() throws IOException {
        String body = method(events(), "public static void unregisterBot(");
        assertTrue(body.contains("ToolSelector.forgetBot(uuid)"));
        assertTrue(body.contains("CombatInventoryManager.forgetBot(uuid)"));
        assertTrue(body.indexOf("ToolSelector.forgetBot(uuid)") < body.indexOf("BotPersistenceService.removeBot(bot)"));
    }

    @Test
    void resettingBotTrackingAlsoResetsToolCooldowns() throws IOException {
        String body = method(events(), "public static void resetAll(");
        assertTrue(body.contains("BotRegistry.clear()"));
        assertTrue(body.contains("ToolSelector.resetSession()"));
    }

    private static String selector() throws IOException {
        return Files.readString(MAIN.resolve("PlayerUtils/ToolSelector.java"));
    }

    private static String events() throws IOException {
        return Files.readString(MAIN.resolve("GameAI/BotEventHandler.java"));
    }

    private static String method(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, "Missing lifecycle method: " + signature);
        int start = source.indexOf('{', signatureAt);
        int depth = 1;
        for (int end = start + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, end);
        }
        throw new AssertionError("Unclosed method: " + signature);
    }
}
