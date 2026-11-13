package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.RequestBasesPayload;
import net.wcfcarolina13.network.SpellGuidancePayload;

import java.util.ArrayList;
import java.util.List;

public class NavigationConfirmScreen extends Screen {
    private final Screen parent;
    private final String botAlias;
    private final String spellType; // "guidance" or "recall"
    private int selectedOption = 0;

    // For guidance mode: dynamic destination list built from bases.
    private final List<DestinationEntry> destinations = new ArrayList<>();
    private boolean basesRequested = false;
    // First destination row shown; the list scrolls when it doesn't fit above the warning line.
    private int scrollOffset = 0;

    private record DestinationEntry(String label, String destination, boolean enabled) {}

    public NavigationConfirmScreen(Screen parent, String botAlias, String spellType) {
        super(Text.literal(spellType.equals("guidance") ? "Remote Guidance" : "Chorus Recall"));
        this.parent = parent;
        this.botAlias = botAlias;
        this.spellType = spellType;
    }

    @Override
    protected void init() {
        if (spellType.equals("guidance")) {
            initGuidanceMode();
        } else {
            initRecallMode();
        }
    }

    // ── Guidance mode: scrollable destination picker ──────────────────────

    private void initGuidanceMode() {
        // Request bases from server on first open.
        if (!basesRequested) {
            basesRequested = true;
            ClientPlayNetworking.send(new RequestBasesPayload(""));
        }

        // Build destination list from cached bases.
        destinations.clear();
        destinations.add(new DestinationEntry("Guide to me", "player", true));

        List<BaseManagerScreen.BaseDto> bases = BaseManagerScreen.getCachedBases();

        // Find home base.
        String homeLabel = null;
        for (BaseManagerScreen.BaseDto b : bases) {
            if (b == null || !b.isBase()) {
                continue;
            }
            if (b.home()) {
                homeLabel = b.label();
                break;
            }
        }
        if (homeLabel != null && !homeLabel.isBlank()) {
            destinations.add(new DestinationEntry("Home (" + homeLabel + ")", homeLabel, true));
        } else {
            destinations.add(new DestinationEntry("Home (not set)", "", false));
        }

        // Add each saved base.
        for (BaseManagerScreen.BaseDto b : bases) {
            if (b == null || !b.isBase()) continue;
            if (b.label() == null || b.label().isBlank()) continue;
            // Skip if already shown as home.
            if (homeLabel != null && b.label().equalsIgnoreCase(homeLabel)) continue;
            destinations.add(new DestinationEntry(b.label(), b.label(), true));
        }

        // Clamp selection, and open the list where the selection is.
        if (selectedOption >= destinations.size()) {
            selectedOption = 0;
        }
        scrollOffset = GuidanceListLayout.clampOffset(scrollOffset, selectedOption, destinations.size(),
                visibleListRows());

        buildGuidanceButtons();
    }

    private static final int LIST_TOP_Y = 50;
    private static final int LIST_BOTTOM_MARGIN = 84;
    private static final int LIST_ROW_STEP = 22;

    private void buildGuidanceButtons() {
        this.clearChildren();

        int cx = this.width / 2;
        int w = 200;
        int h = 20;
        int gap = 2;
        int topY = LIST_TOP_Y;
        int bottomY = this.height - 60;
        // Rows stop above the warning line (height - 80) so they never cover Confirm or Cancel.
        int visible = visibleListRows();
        // Only keep the window in range here; scrolling may leave the selection off screen.
        scrollOffset = GuidanceListLayout.clampOffset(scrollOffset, -1, destinations.size(), visible);

        // Destination entries as radio buttons, the visible window only.
        int end = Math.min(destinations.size(), scrollOffset + visible);
        for (int i = scrollOffset; i < end; i++) {
            DestinationEntry entry = destinations.get(i);
            String prefix = (i == selectedOption) ? "> " : "  ";
            String label = prefix + entry.label();
            final int idx = i;
            ButtonWidget btn = this.addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                if (entry.enabled()) {
                    selectedOption = idx;
                    buildGuidanceButtons();
                }
            }).dimensions(cx - w / 2, topY + (i - scrollOffset) * (h + gap), w, h).build());
            btn.active = entry.enabled();
        }

        // Scroll buttons beside the list when it doesn't all fit (the mouse wheel also scrolls).
        if (destinations.size() > visible) {
            ButtonWidget up = this.addDrawableChild(ButtonWidget.builder(Text.literal("\u25B2"), b -> scroll(-1))
                    .dimensions(cx + w / 2 + 4, topY, 20, h).build());
            up.active = scrollOffset > 0;
            ButtonWidget down = this.addDrawableChild(ButtonWidget.builder(Text.literal("\u25BC"), b -> scroll(1))
                    .dimensions(visible > 1 ? cx + w / 2 + 4 : cx + w / 2 + 26,
                            topY + (visible - 1) * (h + gap), 20, h).build());
            down.active = scrollOffset + visible < destinations.size();
        }

        // Confirm / Cancel at bottom.
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), btn -> confirm())
                .dimensions(cx - w / 2, bottomY, 96, h).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> close())
                .dimensions(cx - w / 2 + 104, bottomY, 96, h).build());
    }

    /** Rows that fit between the list top (50) and the warning line (height - 80), 22 px apart. */
    private int visibleListRows() {
        return GuidanceListLayout.visibleRows(LIST_TOP_Y, this.height - LIST_BOTTOM_MARGIN, LIST_ROW_STEP);
    }

    private void scroll(int rows) {
        int visible = visibleListRows();
        int max = Math.max(0, destinations.size() - visible);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset + rows));
        buildGuidanceButtons();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (spellType.equals("guidance") && verticalAmount != 0) {
            scroll(verticalAmount > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ── Recall mode: 2 radio options (unchanged) ─────────────────────────

    private void initRecallMode() {
        int cx = this.width / 2;
        int w = 200;
        int h = 20;

        String opt1 = selectedOption == 0 ? "> Teleport bot to me" : "  Teleport bot to me";
        String opt2 = selectedOption == 1 ? "> Teleport me to bot" : "  Teleport me to bot";

        this.addDrawableChild(ButtonWidget.builder(Text.literal(opt1), btn -> {
            selectedOption = 0;
            updateRadioLabels();
        }).dimensions(cx - w / 2, 60, w, h).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal(opt2), btn -> {
            selectedOption = 1;
            updateRadioLabels();
        }).dimensions(cx - w / 2, 60 + h + 4, w, h).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), btn -> confirm())
                .dimensions(cx - w / 2, this.height - 60, 96, h).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> close())
                .dimensions(cx - w / 2 + 104, this.height - 60, 96, h).build());
    }

    private void updateRadioLabels() {
        this.clearChildren();
        init();
    }

    @Override
    public void tick() {
        super.tick();
        // Re-check bases after server responds (may arrive after first init).
        if (spellType.equals("guidance") && basesRequested) {
            List<BaseManagerScreen.BaseDto> current = BaseManagerScreen.getCachedBases();
            if (!current.isEmpty() && destinations.size() <= 2) {
                // Bases arrived — rebuild.
                initGuidanceMode();
            }
        }
    }

    // ── Confirm / close ──────────────────────────────────────────────────

    private void confirm() {
        MinecraftClient client = this.client;
        if (client == null) return;

        String destination;
        if (spellType.equals("guidance")) {
            if (selectedOption < 0 || selectedOption >= destinations.size()) return;
            DestinationEntry entry = destinations.get(selectedOption);
            if (!entry.enabled()) return;
            destination = entry.destination();
        } else {
            destination = selectedOption == 0 ? "bot_to_player" : "player_to_bot";
        }

        ClientPlayNetworking.send(new SpellGuidancePayload(botAlias, spellType, destination));

        if (client.player != null) {
            client.player.playSound(SoundEvents.ENTITY_ENDER_EYE_LAUNCH, 0.7f, 1.2f);
        }
        close();
    }

    @Override
    public void close() {
        MinecraftClient client = this.client;
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 20, 0xFFFFFFFF);

        String warning = spellType.equals("guidance")
                ? "Both ender pearls are used when the fren leaves."
                : "Ender pearl and chorus fruit will be consumed from both.";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(warning), cx, this.height - 80, 0xFFFF8888);
    }
}
