package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BotEnchantAccessTest {
    private static String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13", relative))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static String method(String source, String name) {
        var declaration = Pattern.compile("(?:public|private)\\s+(?:static\\s+)?[\\w.<>]+\\s+"
                + name + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
        assertTrue(declaration.find(), "method missing: " + name);
        int start = declaration.end();
        int depth = 1;
        for (int i = start; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i);
        }
        throw new AssertionError("unterminated method: " + name);
    }

    private static String handler() throws IOException {
        return source("ui/BotEnchantmentScreenHandler.java");
    }

    @Test
    void receiverAuthorizesBeforeOpeningAndExplainsRefusal() throws IOException {
        String body = method(source("network/BotEnchantNetworkManager.java"), "handle");
        int permission = body.indexOf("if (!BotAccessGate.permits(player, bot,");
        int message = body.indexOf("player.sendMessage(", permission);
        int stop = body.indexOf("return;", message);
        int open = body.indexOf("player.openHandledScreen(");
        assertTrue(permission >= 0 && permission < message && message < stop && stop < open);
    }

    @Test
    void liveAccessCombinesOwnershipWithBotAndVanillaWorkstationReach() throws IOException {
        String body = method(handler(), "canUse");
        assertTrue(body.contains("if (bot == null)"), "client prediction remains available");
        assertTrue(body.contains("player instanceof ServerPlayerEntity viewer"));
        assertTrue(body.contains("!bot.isRemoved()"));
        assertTrue(body.contains("BotAccessPolicy.allowed(BotAccessGate.decide(viewer, bot))"),
                "the existing gate rechecks current registry and ownership including op/host");
        assertTrue(body.contains("viewer.getEntityWorld() == bot.getEntityWorld()"));
        assertTrue(body.contains("viewer.squaredDistanceTo(bot) <= 64.0 * 64.0"));
        assertTrue(body.contains("&& super.canUse(player)"), "vanilla table proximity still applies");
    }

    @Test
    void bothServerMutationEntrypointsRecheckAccessBeforeTouchingItems() throws IOException {
        String source = handler();
        String button = method(source, "onButtonClick");
        int clientBranch = button.indexOf("if (bot == null)");
        int buttonGate = button.indexOf("if (!canUse(player))");
        int itemRead = button.indexOf("ItemStack itemStack =");
        assertTrue(clientBranch >= 0 && clientBranch < buttonGate && buttonGate < itemRead);
        assertTrue(button.substring(buttonGate, itemRead).contains("return false;"));
        String slots = method(source, "onSlotClick");
        int slotGate = slots.indexOf("if (bot != null && !canUse(player))");
        int mutate = slots.indexOf("super.onSlotClick(");
        assertTrue(slotGate >= 0 && slotGate < mutate);
        assertTrue(slots.substring(slotGate, mutate).contains("return;"));
    }

    @Test
    void losingAccessDoesNotSkipReturningInputsAndCursorOnClose() throws IOException {
        String close = method(handler(), "onClosed");
        assertFalse(close.contains("canUse("));
        assertFalse(close.contains("BotAccessGate"));
        assertTrue(close.contains("this.getCursorStack()"));
        assertTrue(close.contains("this.enchantSlots.removeStack(i)"));
        assertTrue(close.contains("bot.getInventory().insertStack("));
        assertTrue(close.contains("bot.dropItem("));
    }
}
