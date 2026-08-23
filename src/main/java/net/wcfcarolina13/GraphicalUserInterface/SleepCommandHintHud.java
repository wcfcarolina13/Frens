package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

/** Explains the companion sleep command while the player is in bed. */
public final class SleepCommandHintHud {

    private static final String TITLE = "Your Frens need to sleep too";
    private static final String INSTRUCTION =
            "Type zzz in chat to make all your Frens in this dimension try to sleep.";

    private SleepCommandHintHud() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof SleepingChatScreen)) {
                return;
            }
            ScreenEvents.afterRender(screen).register(
                    (renderedScreen, context, mouseX, mouseY, tickDelta) -> render(context));
        });
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean playerSleeping = client != null
                && client.player != null
                && client.player.isSleeping();
        boolean sleepingChatScreenOpen = client != null
                && client.currentScreen instanceof SleepingChatScreen;
        if (!shouldRender(playerSleeping, sleepingChatScreenOpen)) {
            return;
        }

        int screenWidth = context.getScaledWindowWidth();
        int maxTextWidth = Math.min(280, Math.max(40, screenWidth - 32));
        List<OrderedText> instructionLines = client.textRenderer.wrapLines(
                Text.literal(INSTRUCTION), maxTextWidth);

        int lineHeight = client.textRenderer.fontHeight + 1;
        int textWidth = client.textRenderer.getWidth(TITLE);
        for (OrderedText line : instructionLines) {
            textWidth = Math.max(textWidth, client.textRenderer.getWidth(line));
        }

        int padding = 6;
        int boxWidth = textWidth + padding * 2;
        int boxHeight = padding * 2
                + client.textRenderer.fontHeight
                + 3
                + instructionLines.size() * lineHeight;
        int x = (screenWidth - boxWidth) / 2;
        int y = Math.max(8, context.getScaledWindowHeight() - 46 - boxHeight);

        context.fill(x - 1, y - 1, x + boxWidth + 1, y + boxHeight + 1, 0xFF000000);
        context.fill(x, y, x + boxWidth, y + boxHeight, 0xD0141414);
        context.fill(x, y, x + boxWidth, y + 1, 0xFF8A6D32);
        context.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, 0xFF8A6D32);
        context.fill(x, y, x + 1, y + boxHeight, 0xFF8A6D32);
        context.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, 0xFF8A6D32);

        int titleX = x + (boxWidth - client.textRenderer.getWidth(TITLE)) / 2;
        int textY = y + padding;
        context.drawTextWithShadow(client.textRenderer, TITLE, titleX, textY, 0xFFFFE08A);
        textY += client.textRenderer.fontHeight + 3;
        for (OrderedText line : instructionLines) {
            int lineX = x + (boxWidth - client.textRenderer.getWidth(line)) / 2;
            context.drawTextWithShadow(client.textRenderer, line, lineX, textY, 0xFFE6D7A3);
            textY += lineHeight;
        }
    }

    static boolean shouldRender(boolean playerSleeping, boolean sleepingChatScreenOpen) {
        return playerSleeping && sleepingChatScreenOpen;
    }
}
