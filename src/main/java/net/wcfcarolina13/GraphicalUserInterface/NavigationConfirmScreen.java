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

        // Clamp selection.
        if (selectedOption >= destinations.size()) {
            selectedOption = 0;
        }

        buildGuidanceButtons();
    }

    private void buildGuidanceButtons() {
        this.clearChildren();

        int cx = this.width / 2;
        int w = 200;
        int h = 20;
        int gap = 2;
        int topY = 50;

        // Destination entries as radio buttons.
        for (int i = 0; i < destinations.size(); i++) {
            DestinationEntry entry = destinations.get(i);
            String prefix = (i == selectedOption) ? "> " : "  ";
            String label = prefix + entry.label();
            final int idx = i;
            ButtonWidget btn = this.addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                if (entry.enabled()) {
                    selectedOption = idx;
                    buildGuidanceButtons();
                }
            }).dimensions(cx - w / 2, topY + i * (h + gap), w, h).build());
            btn.active = entry.enabled();
        }

        // Confirm / Cancel at bottom.
        int bottomY = this.height - 60;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), btn -> confirm())
                .dimensions(cx - w / 2, bottomY, 96, h).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> close())
                .dimensions(cx - w / 2 + 104, bottomY, 96, h).build());
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
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 20, 0xFFFFFF);

        String warning = spellType.equals("guidance")
                ? "Both ender pearls will be consumed."
                : "Ender pearl and chorus fruit will be consumed from both.";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(warning), cx, this.height - 80, 0xFFFF8888);
    }
}
