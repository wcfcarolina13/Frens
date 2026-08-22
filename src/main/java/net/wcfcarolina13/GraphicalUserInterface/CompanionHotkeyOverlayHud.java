package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.wcfcarolina13.FrensClient;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

/**
 * Lightweight non-pausing hotkey overlay for companion controls.
 */
public final class CompanionHotkeyOverlayHud {

    private static final int[] NUMBER_KEYS = {
            GLFW.GLFW_KEY_1,
            GLFW.GLFW_KEY_2,
            GLFW.GLFW_KEY_3,
            GLFW.GLFW_KEY_4,
            GLFW.GLFW_KEY_5,
            GLFW.GLFW_KEY_6,
            GLFW.GLFW_KEY_7,
            GLFW.GLFW_KEY_8,
            GLFW.GLFW_KEY_9,
            GLFW.GLFW_KEY_0
    };

    private static final String[] LABELS = {
            "1 🪖 Stand Down (60s)",
            "2 ▶ Resume",
            "3 ✨ Spells",
            "4 🏠 Home",
            "5 🛌 Sleep",
            "6 📣 Regroup",
            "7 ⛏ Stripmine",
            "8 🪜 Ascent",
            "9 🕳 Descent",
            "0 🧹 Cleanup"
    };

    private static final boolean[] LAST_DOWN = new boolean[10];
    private static long visibleUntilMs = 0L;

    private CompanionHotkeyOverlayHud() {
    }

    public static void show(long durationMs) {
        long now = System.currentTimeMillis();
        visibleUntilMs = Math.max(visibleUntilMs, now + Math.max(250L, durationMs));
    }

    public static void hide() {
        visibleUntilMs = 0L;
        Arrays.fill(LAST_DOWN, false);
    }

    public static boolean isVisible() {
        return System.currentTimeMillis() < visibleUntilMs;
    }

    public static Integer pollSelection(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            Arrays.fill(LAST_DOWN, false);
            return null;
        }

        Integer picked = null;
        boolean visible = isVisible();
        for (int i = 0; i < NUMBER_KEYS.length; i++) {
            boolean down = InputUtil.isKeyPressed(client.getWindow(), NUMBER_KEYS[i]);
            if (visible && down && !LAST_DOWN[i] && picked == null) {
                picked = (i == 9) ? 0 : (i + 1);
            }
            LAST_DOWN[i] = down;
        }
        return picked;
    }

    public static void render(DrawContext context) {
        if (!isVisible()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        int lineH = client.textRenderer.fontHeight + 1;
        int titleW = client.textRenderer.getWidth("Companion Hotkeys");
        int maxW = titleW;
        List<String> footerLines = footerLines();
        for (String label : LABELS) {
            maxW = Math.max(maxW, client.textRenderer.getWidth(label));
        }
        for (String footer : footerLines) {
            maxW = Math.max(maxW, client.textRenderer.getWidth(footer));
        }

        int boxW = maxW + 16;
        int boxH = 14 + LABELS.length * lineH + 6 + footerLines.size() * lineH + 8;
        int x = Math.max(8, context.getScaledWindowWidth() - boxW - 8);
        int y = 12;

        context.fill(x, y, x + boxW, y + boxH, 0xD0141414);
        context.fill(x, y, x + boxW, y + 1, 0xFF3A3A3A);
        context.fill(x, y + boxH - 1, x + boxW, y + boxH, 0xFF3A3A3A);
        context.fill(x, y, x + 1, y + boxH, 0xFF3A3A3A);
        context.fill(x + boxW - 1, y, x + boxW, y + boxH, 0xFF3A3A3A);

        context.drawTextWithShadow(client.textRenderer, "Companion Hotkeys", x + 8, y + 4, 0xFFFFE08A);
        int rowY = y + 14;
        for (String label : LABELS) {
            context.drawTextWithShadow(client.textRenderer, label, x + 8, rowY, 0xFFE6D7A3);
            rowY += lineH;
        }
        rowY += 2;
        for (String footer : footerLines) {
            context.drawTextWithShadow(client.textRenderer, footer, x + 8, rowY, 0xFFB8A76A);
            rowY += lineH;
        }
    }

    private static List<String> footerLines() {
        String follow = FrensClient.getCompanionShortcutInstruction(FrensClient.CompanionShortcut.FOLLOW);
        String goTo = FrensClient.getCompanionShortcutInstruction(FrensClient.CompanionShortcut.GO_TO_LOOK);
        String stop = FrensClient.getCompanionShortcutInstruction(FrensClient.CompanionShortcut.STOP);
        String openMenu = stop.startsWith("[")
                ? "Hold " + stop + " Open hotkey menu"
                : stop + " (hotkey menu)";
        return List.of(
                follow + " Toggle Follow",
                goTo + " Open spells / Go To Look / confirm direction",
                stop + " Stop",
                openMenu
        );
    }
}
