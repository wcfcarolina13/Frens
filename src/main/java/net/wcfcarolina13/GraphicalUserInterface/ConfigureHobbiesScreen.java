package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler;

import java.util.List;
import java.util.Locale;

/**
 * Per-hobby on/off menu opened from the bot's Idle Hobbies entry.
 * Each toggle sends "/bot hobby <name> on|off <target>" via the chat command path,
 * mirroring how the master Idle Hobbies toggle works. State is read from the
 * BotPlayerInventoryScreenHandler's slot-25 disabled-hobbies bitmask, which the
 * server refreshes on every tick — so toggles appear in the UI on the next sync.
 */
public class ConfigureHobbiesScreen extends Screen {

    private static final int POPUP_WIDTH = 380;
    private static final int POPUP_HEIGHT = 260;
    private static final int COLS = 3;
    private static final int ROW_HEIGHT = 22;
    private static final int CELL_PAD = 6;

    private final Screen parent;
    private final BotPlayerInventoryScreenHandler handler;
    private final String botTarget;

    /** Display label per hobby. Order mirrors HOBBY_BIT_ORDER. */
    private static final List<String> LABELS = List.of(
            "Hunt", "Fish", "Feed Animals", "Pick Flowers", "Gather Seeds",
            "Mine Stone", "Shadow Companion", "Hangout (Campfire)", "Cook", "Leaf Litter",
            "Mushrooms", "Wander", "Woodcut", "Collect Dirt", "Honey Collect"
    );

    public ConfigureHobbiesScreen(Screen parent, BotPlayerInventoryScreenHandler handler, String botTarget) {
        super(Text.literal("§bIdle Hobbies"));
        this.parent = parent;
        this.handler = handler;
        this.botTarget = botTarget == null ? "" : botTarget;
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;

        // Per-hobby toggle grid
        List<String> names = BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER;
        int gridTop = cy + 30;
        int colWidth = (POPUP_WIDTH - CELL_PAD * (COLS + 1)) / COLS;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String label = i < LABELS.size() ? LABELS.get(i) : name;
            int col = i % COLS;
            int row = i / COLS;
            int bx = cx + CELL_PAD + col * (colWidth + CELL_PAD);
            int by = gridTop + row * (ROW_HEIGHT + 2);
            ButtonWidget btn = ButtonWidget.builder(buttonText(name, label),
                            b -> toggleHobby(name, b))
                    .dimensions(bx, by, colWidth, ROW_HEIGHT)
                    .build();
            addDrawableChild(btn);
        }

        // Bottom row: Check All / Uncheck All / Done
        int btnY = cy + POPUP_HEIGHT - 28;
        int btnW = (POPUP_WIDTH - CELL_PAD * 4) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("Enable All"),
                        b -> bulkSet(true))
                .dimensions(cx + CELL_PAD, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Disable All"),
                        b -> bulkSet(false))
                .dimensions(cx + CELL_PAD * 2 + btnW, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + CELL_PAD * 3 + btnW * 2, btnY, btnW, 20).build());
    }

    private Text buttonText(String name, String label) {
        boolean on = handler != null && handler.isHobbyAllowed(name);
        String prefix = on ? "§a[x] " : "§7[ ] ";
        return Text.literal(prefix + label);
    }

    private void toggleHobby(String name, ButtonWidget button) {
        boolean currentlyOn = handler != null && handler.isHobbyAllowed(name);
        String action = currentlyOn ? "off" : "on";
        sendCommand("bot hobby " + name + " " + action + " " + botTarget);
        // Refresh button label after a beat — server will sync slot 25 on next tick;
        // the button updates on its own thanks to render() reading handler state each frame.
        rebuild();
    }

    private void bulkSet(boolean enabled) {
        String action = enabled ? "on" : "off";
        for (String name : BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER) {
            sendCommand("bot hobby " + name + " " + action + " " + botTarget);
        }
        rebuild();
    }

    private void rebuild() {
        // Re-init to pick up handler state for new button labels.
        this.clearChildren();
        this.init();
    }

    private void sendCommand(String command) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || p.networkHandler == null) return;
        String trimmed = command.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        p.networkHandler.sendChatCommand(trimmed);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        // Border + interior
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xCC222222);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 10, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
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
    public boolean keyPressed(KeyInput input) {
        int key = input != null ? input.key() : -1;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
