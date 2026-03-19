package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.NavigationResponsePayload;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class NavigationHudOverlay {

    private record PendingRequest(String botAlias, String destination, int seconds, long expireMs) {}

    private static final Queue<PendingRequest> PENDING_QUEUE = new ConcurrentLinkedQueue<>();

    private NavigationHudOverlay() {}

    /** Called when server sends NavigationRequestPayload. Queues for sequential display. */
    public static void show(String botAlias, String destination, int estimatedSeconds) {
        PENDING_QUEUE.add(new PendingRequest(botAlias, destination, estimatedSeconds,
                System.currentTimeMillis() + 60_000L));
    }

    public static boolean isVisible() {
        expireStale();
        return !PENDING_QUEUE.isEmpty();
    }

    public static void dismiss() {
        PendingRequest front = PENDING_QUEUE.poll();
        if (front != null) {
            ClientPlayNetworking.send(new NavigationResponsePayload(front.botAlias, false));
        }
    }

    public static void accept() {
        PendingRequest front = PENDING_QUEUE.poll();
        if (front != null) {
            ClientPlayNetworking.send(new NavigationResponsePayload(front.botAlias, true));
        }
    }

    /** Remove expired requests from the front, sending dismiss for each. */
    private static void expireStale() {
        long now = System.currentTimeMillis();
        while (!PENDING_QUEUE.isEmpty()) {
            PendingRequest front = PENDING_QUEUE.peek();
            if (front != null && now >= front.expireMs) {
                PENDING_QUEUE.poll();
                ClientPlayNetworking.send(new NavigationResponsePayload(front.botAlias, false));
            } else {
                break;
            }
        }
    }

    /** Called from HudRenderCallback. */
    public static void render(DrawContext context) {
        if (!isVisible()) return;
        PendingRequest front = PENDING_QUEUE.peek();
        if (front == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        int y = screenH - 68;
        int cx = screenW / 2;

        String msg = front.botAlias + " wants to return home. (~" + front.seconds + "s)";
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(msg), cx, y, 0xFFFFCC00);

        int queueSize = PENDING_QUEUE.size();
        String controls = queueSize > 1
                ? "[Y] Accept   [N] Dismiss   (" + (queueSize - 1) + " more)"
                : "[Y] Accept   [N] Dismiss";
        context.drawCenteredTextWithShadow(client.textRenderer,
                Text.literal(controls), cx, y + 12, 0xFFB0B0B0);
    }

    /** Handle keyboard input for Y/N accept/dismiss. */
    public static boolean handleKeyPress(int keyCode) {
        if (!isVisible()) return false;
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) { accept(); return true; }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_N) { dismiss(); return true; }
        return false;
    }
}
