package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import net.wcfcarolina13.network.RequestPlayerPreservePayload;
import net.wcfcarolina13.network.UpdatePlayerPreservePayload;

/**
 * Player-facing preferences screen. Currently hosts a single toggle —
 * "Preserve Expensive Gear" — but can grow to hold additional player-level
 * preferences without affecting the admin permission matrix.
 *
 * <p>State model: the screen sends {@link RequestPlayerPreservePayload} on
 * open, and the server replies with {@link net.wcfcarolina13.network.PlayerPreserveStatePayload}
 * which writes into {@link #SERVER_VALUE}. The render loop polls this field.
 * When the player flips the toggle, we update {@link #SERVER_VALUE}
 * optimistically and send {@link UpdatePlayerPreservePayload} to the server.
 *
 * <p><strong>Currently unused</strong> as of 2026-04-07. The "Preserve
 * Expensive Gear" toggle moved to BotPlayerInventoryScreen Admin →
 * Behavior. This class is kept alive only because its
 * {@link #SERVER_VALUE} static field is still the canonical client-side
 * cache for the toggle state, written by the S2C
 * {@code PlayerPreserveStatePayload} receiver in FrensClient. To revive
 * this screen, uncomment the BotControlScreen footer button block (look
 * for the "Personal Preferences footer button — commented out" marker).
 */
public class BotPlayerPreferencesScreen extends Screen {

    // Server-authoritative value, written by the S2C handler on the client.
    // Volatile because the network thread writes and the client thread reads.
    // Public so BotPlayerInventoryScreen can read/write it directly.
    public static volatile Boolean SERVER_VALUE = null;

    /** Called from the client payload receiver. */
    public static void setServerValue(boolean value) {
        SERVER_VALUE = value;
    }

    private final Screen parent;

    // Layout constants
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 180;
    private static final int ROW_HEIGHT = 22;
    private static final int CHIP_WIDTH = 44;
    private static final int CHIP_HEIGHT = 18;
    private static final int PADDING = 14;

    private int panelX;
    private int panelY;
    private int chipX;
    private int chipY;

    private boolean requestSent = false;

    public BotPlayerPreferencesScreen(Screen parent) {
        super(Text.literal("Personal Preferences"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - PANEL_WIDTH) / 2;
        this.panelY = (this.height - PANEL_HEIGHT) / 2;

        // Request the current value from the server exactly once per open.
        if (!requestSent) {
            ClientPlayNetworking.send(RequestPlayerPreservePayload.INSTANCE);
            requestSent = true;
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xD0101010);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // Panel background
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xD0101010);
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, 0xFF404040);
        context.fill(panelX, panelY + PANEL_HEIGHT - 1, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xFF404040);
        context.fill(panelX, panelY, panelX + 1, panelY + PANEL_HEIGHT, 0xFF404040);
        context.fill(panelX + PANEL_WIDTH - 1, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xFF404040);

        // Header
        Text header = Text.literal("Personal Preferences").formatted(Formatting.YELLOW);
        int headerX = panelX + (PANEL_WIDTH - this.textRenderer.getWidth(header)) / 2;
        context.drawText(this.textRenderer, header, headerX, panelY + 8, 0xFFFFFFFF, false);

        // Separator under header
        int sepY = panelY + 22;
        context.fill(panelX + PADDING, sepY, panelX + PANEL_WIDTH - PADDING, sepY + 1, 0xFF404040);

        // Row: "Preserve Expensive Gear" label + toggle chip
        int rowY = panelY + 38;
        context.drawText(this.textRenderer, "Preserve Expensive Gear", panelX + PADDING, rowY + 5, 0xFFFFFFFF, false);

        // Chip (right-aligned in the row)
        chipX = panelX + PANEL_WIDTH - PADDING - CHIP_WIDTH;
        chipY = rowY;
        boolean loaded = SERVER_VALUE != null;
        boolean enabled = loaded && SERVER_VALUE;
        int chipBg;
        if (!loaded) {
            chipBg = 0xFF3A3A3A; // loading
        } else if (enabled) {
            chipBg = 0xFF2E7D32; // ON (green)
        } else {
            chipBg = 0xFF5A1A1A; // OFF (red)
        }
        context.fill(chipX, chipY, chipX + CHIP_WIDTH, chipY + CHIP_HEIGHT, chipBg);
        String chipText;
        if (!loaded) {
            chipText = "...";
        } else if (enabled) {
            chipText = "ON";
        } else {
            chipText = "OFF";
        }
        int chipTextWidth = this.textRenderer.getWidth(chipText);
        int chipTextX = chipX + (CHIP_WIDTH - chipTextWidth) / 2;
        context.drawText(this.textRenderer, chipText, chipTextX, chipY + 5, 0xFFFFFFFF, false);

        // Hint text below the row (wraps)
        int hintY = rowY + ROW_HEIGHT + 8;
        String[] hintLines = new String[] {
                "Bots will refuse to use enchanted gear or items made of gold,",
                "diamond, netherite, or turtle shell once durability drops below",
                "11% — or 3% in combat. They'll swap to a cheaper alternative,",
                "check a nearby chest, or craft a new one.",
                "",
                "Applies to every bot you own."
        };
        int hintCurrentY = hintY;
        for (String line : hintLines) {
            context.drawText(this.textRenderer, line, panelX + PADDING, hintCurrentY, 0xFFB0B0B0, false);
            hintCurrentY += 10;
        }

        // Close hint at the bottom
        String closeHint = "Press ESC to close";
        int closeHintX = panelX + (PANEL_WIDTH - this.textRenderer.getWidth(closeHint)) / 2;
        context.drawText(this.textRenderer, closeHint, closeHintX, panelY + PANEL_HEIGHT - 16, 0xFF808080, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        if (click.button() == 0) {
            double mouseX = click.x();
            double mouseY = click.y();
            if (mouseX >= chipX && mouseX < chipX + CHIP_WIDTH
                    && mouseY >= chipY && mouseY < chipY + CHIP_HEIGHT) {
                // Optimistic flip
                boolean current = SERVER_VALUE != null && SERVER_VALUE;
                boolean next = !current;
                SERVER_VALUE = next;
                ClientPlayNetworking.send(new UpdatePlayerPreservePayload(next));
                return true;
            }
        }
        return super.mouseClicked(click, isInside);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
