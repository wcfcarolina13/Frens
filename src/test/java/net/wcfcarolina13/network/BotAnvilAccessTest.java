package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BotAnvilAccessTest {
    private static final Path MAIN = Path.of("src/main/java/net/wcfcarolina13");

    @Test
    void receiverDeniesBeforeLookingUpTheAnvilOrExposingInventory() throws IOException {
        String source = Files.readString(MAIN.resolve("network/BotAnvilNetworkManager.java"));
        String denial = method(source, "if (!BotAccessGate.permits(player, bot, \"anvil-open\"))");
        assertTrue(denial.contains("ChatUtils.sendSystemMessage("));
        assertTrue(denial.contains("permission"));
        assertTrue(denial.contains("return;"));
        int gate = source.indexOf("BotAccessGate.permits(");
        assertTrue(gate < source.indexOf("findNearestAnvil("));
        assertTrue(gate < source.indexOf("bot.getInventory()"));
    }

    @Test
    void openScreenRechecksOwnershipPresenceReachAndVanillaWorkstationRules() throws IOException {
        String body = method(handler(), "public boolean canUse(");
        assertTrue(body.contains("if (bot == null) return super.canUse(player);"));
        assertTrue(body.contains("player instanceof ServerPlayerEntity serverPlayer"));
        assertTrue(body.contains("bot.isRemoved()"));
        assertTrue(body.contains("BotAccessPolicy.allowed(BotAccessGate.decide(serverPlayer, bot))"));
        assertTrue(body.contains("player.getEntityWorld() == bot.getEntityWorld()"));
        assertTrue(body.contains("player.squaredDistanceTo(bot) <= 64.0 * 64.0"));
        assertTrue(body.contains("&& super.canUse(player)"));
    }

    @Test
    void revokedAccessStopsSlotClicksAndOutputBeforeMutation() throws IOException {
        String source = handler();
        String click = method(source, "public void onSlotClick(");
        int guard = click.indexOf("if (bot != null && !canUse(player)) return;");
        assertTrue(guard >= 0);
        assertTrue(guard < click.indexOf("logSlotClick("));
        assertTrue(guard < click.indexOf("super.onSlotClick("));
        String output = method(source, "protected boolean canTakeOutput(");
        int outputGuard = output.indexOf("if (!canUse(player)) return false;");
        assertTrue(outputGuard > output.indexOf("if (bot == null)"));
        assertTrue(outputGuard < output.indexOf("bot.experienceLevel"));
    }

    @Test
    void losingAccessDoesNotSuppressReturningItemsToTheBotOnClose() throws IOException {
        String close = method(handler(), "public void onClosed(");
        assertFalse(close.contains("canUse("));
        assertFalse(close.contains("BotAccessGate"));
        assertTrue(close.contains("bot.getInventory().insertStack(cursor)"));
        assertTrue(close.contains("this.input.removeStack(i)"));
        assertTrue(close.contains("bot.getInventory().insertStack(stack)"));
    }

    private static String handler() throws IOException {
        return Files.readString(MAIN.resolve("ui/BotAnvilScreenHandler.java"));
    }

    private static String method(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, "Missing entry point: " + signature);
        int start = source.indexOf('{', signatureAt);
        int depth = 1;
        for (int end = start + 1; end < source.length(); end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(start + 1, end);
        }
        fail("Unclosed body: " + signature);
        return "";
    }
}
