package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.NavigationResponsePayload;

public final class NavigationHudOverlay {
    private static String pendingBotAlias = null;
    private static String pendingDestination = null;
    private static int pendingSeconds = 0;
    private static long showUntilMs = 0L;

    private NavigationHudOverlay() {}

    /** Called when server sends NavigationRequestPayload. */
    public static void show(String botAlias, String destination, int estimatedSeconds) {
        pendingBotAlias = botAlias;
        pendingDestination = destination;
        pendingSeconds = estimatedSeconds;
        showUntilMs = System.currentTimeMillis() + 60_000L;
    }

    public static boolean isVisible() {
        return pendingBotAlias != null && System.currentTimeMillis() < showUntilMs;
    }

    public static void dismiss() {
        if (pendingBotAlias != null) {
            ClientPlayNetworking.send(new NavigationResponsePayload(pendingBotAlias, false));
        }
        clear();
    }

    public static void accept() {
        if (pendingBotAlias != null) {
            ClientPlayNetworking.send(new NavigationResponsePayload(pendingBotAlias, true));
        }
        clear();
    }

    private static void clear() {
        pendingBotAlias = null;
        pendingDestination = null;
        pendingSeconds = 0;
        showUntilMs = 0L;
    }

    /** Called from HudRenderCallback. */
    public static void render(DrawContext context) {
        if (!isVisible()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        int y = screenH - 68;
        int cx = screenW / 2;

        String msg = pendingBotAlias + " wants to return home. (~" + pendingSeconds + "s)";
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(msg), cx, y, 0xFFFFCC00);
        context.drawCenteredTextWithShadow(client.textRenderer,
                Text.literal("[Y] Accept   [N] Dismiss"), cx, y + 12, 0xFFB0B0B0);
    }

    /** Handle keyboard input for Y/N accept/dismiss. */
    public static boolean handleKeyPress(int keyCode) {
        if (!isVisible()) return false;
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) { accept(); return true; }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_N) { dismiss(); return true; }
        return false;
    }
}
