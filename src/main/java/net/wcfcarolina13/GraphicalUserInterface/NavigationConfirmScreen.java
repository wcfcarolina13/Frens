package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.SpellGuidancePayload;

public class NavigationConfirmScreen extends Screen {
    private final Screen parent;
    private final String botAlias;
    private final String spellType; // "guidance" or "recall"
    private int selectedOption = 0; // 0 = first radio, 1 = second radio

    public NavigationConfirmScreen(Screen parent, String botAlias, String spellType) {
        super(Text.literal(spellType.equals("guidance") ? "Remote Guidance" : "Chorus Recall"));
        this.parent = parent;
        this.botAlias = botAlias;
        this.spellType = spellType;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 160;
        int h = 20;

        // Radio option buttons (toggle selection)
        String opt1 = spellType.equals("guidance") ? "> Guide to me" : "> Teleport bot to me";
        String opt2 = spellType.equals("guidance") ? "  Guide to base" : "  Teleport me to bot";

        if (selectedOption == 0) {
            opt1 = spellType.equals("guidance") ? "> Guide to me" : "> Teleport bot to me";
            opt2 = spellType.equals("guidance") ? "  Guide to base" : "  Teleport me to bot";
        } else {
            opt1 = spellType.equals("guidance") ? "  Guide to me" : "  Teleport bot to me";
            opt2 = spellType.equals("guidance") ? "> Guide to base" : "> Teleport me to bot";
        }

        final String label1 = opt1;
        final String label2 = opt2;

        this.addDrawableChild(ButtonWidget.builder(Text.literal(label1), btn -> {
            selectedOption = 0;
            updateRadioLabels();
        }).dimensions(cx - w / 2, 60, w, h).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal(label2), btn -> {
            selectedOption = 1;
            updateRadioLabels();
        }).dimensions(cx - w / 2, 60 + h + 4, w, h).build());

        // Confirm / Cancel
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), btn -> confirm())
                .dimensions(cx - w / 2, this.height - 60, 76, h).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> close())
                .dimensions(cx - w / 2 + 84, this.height - 60, 76, h).build());
    }

    private void updateRadioLabels() {
        // Re-init to update button labels with selection indicators
        this.clearChildren();
        init();
    }

    private void confirm() {
        MinecraftClient client = this.client;
        if (client == null) return;

        String destination;
        if (spellType.equals("guidance")) {
            destination = selectedOption == 0 ? "player" : "base"; // TODO: base name from dropdown
        } else {
            destination = selectedOption == 0 ? "bot_to_player" : "player_to_bot";
        }

        // Send spell cast via network payload
        ClientPlayNetworking.send(new SpellGuidancePayload(botAlias, spellType, destination));

        // Play acceptance sound client-side
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

        // Warning text
        String warning = spellType.equals("guidance")
                ? "Both ender pearls will be consumed."
                : "Ender pearl and chorus fruit will be consumed from both.";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(warning), cx, this.height - 80, 0xFFFF8888);
    }
}
