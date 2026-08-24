package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SleepingChatScreen;

/**
 * Explains the companion sleep command while the player is in bed. Deliberately unobtrusive: a
 * single thin translucent strip across the very top of the screen (out of the way of the bed
 * view, chat, and the Leave Bed button), never a centered box.
 */
public final class SleepCommandHintHud {

    private static final String HINT =
            "Your Frens need to sleep too — type zzz in chat to send them to bed.";
    // Fallback for very narrow scaled widths where the full sentence would clip.
    private static final String HINT_COMPACT = "Type zzz in chat — Frens sleep too.";

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
        String hint = HINT;
        if (client.textRenderer.getWidth(hint) > screenWidth - 12) {
            hint = HINT_COMPACT;
        }

        int bandHeight = client.textRenderer.fontHeight + 8;
        context.fill(0, 0, screenWidth, bandHeight, 0xA0101010);
        context.fill(0, bandHeight, screenWidth, bandHeight + 1, 0x808A6D32);

        int textX = Math.max(6, (screenWidth - client.textRenderer.getWidth(hint)) / 2);
        int textY = (bandHeight - client.textRenderer.fontHeight) / 2 + 1;
        context.drawTextWithShadow(client.textRenderer, hint, textX, textY, 0xFFE6D7A3);
    }

    static boolean shouldRender(boolean playerSleeping, boolean sleepingChatScreenOpen) {
        return playerSleeping && sleepingChatScreenOpen;
    }
}
