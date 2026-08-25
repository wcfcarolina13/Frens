package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-hobby on/off menu opened from the bot's Idle Hobbies entry.
 * Each toggle sends "/bot hobby <name> on|off <target>" via the chat command path,
 * mirroring how the master Idle Hobbies toggle works.
 *
 * Visual state has two layers: the server-authoritative bitmask in botStats slot 25,
 * and a client-side optimistic override map. The override flips immediately on click
 * so the user sees instant feedback; once the server resyncs and the handler bitmask
 * matches the override, the override drops out (rendered state and handler state
 * agree, so we're back to authoritative reads).
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

    /** Optimistic local overrides until the server bitmask catches up. */
    private final Map<String, Boolean> localOverrides = new HashMap<>();

    /** Display label per hobby. Order mirrors HOBBY_BIT_ORDER. */
    private static final List<String> LABELS = List.of(
            "Hunt", "Fish", "Feed Animals", "Pick Flowers", "Gather Seeds",
            "Mine Stone", "Shadow Companion", "Hangout (Campfire)", "Cook", "Leaf Litter",
            "Mushrooms", "Wander", "Woodcut", "Collect Dirt", "Honey Collect",
            "Walk Dogs"
    );

    /**
     * Tooltip per hobby. Order mirrors HOBBY_BIT_ORDER. Each line should fit in a
     * single tooltip width without wrapping aggressively — keep them short and
     * factual, with any "watch out for this" caveats up front.
     */
    private static final List<String> TOOLTIPS = List.of(
            // hunt
            "Opportunistic kill of nearby passive or weak hostile mobs.\nNot the dedicated Auto Hunt service — that's separate.",
            // fish
            "Cast a fishing rod at a nearby water tile.\nNot the dedicated fishing skill — that's separate.",
            // feed_animals
            "Feed nearby commander-owned animals to heal/breed them.",
            // flowers
            "Pick flowers in the surrounding area.",
            // grass_seeds
            "Break tall grass to gather wheat seeds.",
            // mining
            "Mine nearby stone with a pickaxe.\n§cWarning: can dig through terrain to reach buried stone.\nLeave OFF near unprotected builds until the surface gate ships.",
            // shadow_companion
            "Stand quietly near you while you're nearby — passive presence.",
            // hangout
            "Sit and idle by a nearby campfire.",
            // cook
            "Cook raw food in inventory using a nearby furnace or campfire.",
            // leaf_litter
            "Collect leaf piles for fuel and composting.",
            // mushrooms
            "Forage mushrooms in the surrounding area.",
            // wander
            "Take a low-intensity local stroll.\nThis is the default fallback when nothing else fits;\nturn OFF if you want the bot to stand still when idle.",
            // woodcut
            "Chop a nearby unprotected tree.",
            // collect_dirt
            "Dig dirt with a shovel.",
            // honey_collect
            "Harvest honey from beehives using shears or a glass bottle.",
            // walk_dogs
            "Stand up nearby unnamed sitting tamed wolves so they tag along.\nName your wolf to opt it out. Disabled while inside a registered base."
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
            if (i < TOOLTIPS.size()) {
                btn.setTooltip(Tooltip.of(Text.literal(TOOLTIPS.get(i))));
            }
            addDrawableChild(btn);
        }

        // Bottom row: Enable All / Disable All / Done
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

    /** Effective on/off: optimistic override wins; otherwise read the handler bitmask. */
    private boolean isOn(String name) {
        Boolean override = localOverrides.get(name);
        if (override != null) return override;
        return handler != null && handler.isHobbyAllowed(name);
    }

    private Text buttonText(String name, String label) {
        boolean on = isOn(name);
        String prefix = on ? "§a[x] " : "§7[ ] ";
        return Text.literal(prefix + label);
    }

    private void toggleHobby(String name, ButtonWidget button) {
        boolean newState = !isOn(name);
        localOverrides.put(name, newState);
        sendCommand("bot hobby " + name + " " + (newState ? "on" : "off") + " " + botTarget);
        rebuild();
    }

    private void bulkSet(boolean enabled) {
        for (String name : BotPlayerInventoryScreenHandler.HOBBY_BIT_ORDER) {
            localOverrides.put(name, enabled);
            sendCommand("bot hobby " + name + " " + (enabled ? "on" : "off") + " " + botTarget);
        }
        rebuild();
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    /** Drop overrides whose value already matches the server's authoritative state. */
    private void reconcileOverrides() {
        if (handler == null || localOverrides.isEmpty()) return;
        boolean changed = false;
        var it = localOverrides.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (handler.isHobbyAllowed(e.getKey()) == e.getValue()) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            // Rebuild so button labels switch from override-source to handler-source seamlessly.
            // (No visible change — the rendered state stays identical.)
            this.clearChildren();
            this.init();
        }
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
        reconcileOverrides();
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        // Border + interior
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xCC222222);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 10, 0xFFFFFFFF);
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
