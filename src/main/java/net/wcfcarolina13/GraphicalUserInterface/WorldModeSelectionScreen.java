package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.ModeSelectionChoicePayload;

import java.util.List;

/**
 * First-time world mode selection.
 *
 * <p>Shown once for new worlds/servers to choose between questing flow and admin flow.</p>
 */
public class WorldModeSelectionScreen extends Screen {

    private final String worldLabel;
    private final boolean canChoose;
    private boolean submitted = false;

    public WorldModeSelectionScreen(String worldLabel, boolean canChoose) {
        super(Text.literal("Choose World Mode"));
        this.worldLabel = (worldLabel == null || worldLabel.isBlank()) ? "default" : worldLabel.trim();
        this.canChoose = canChoose;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int panelY = Math.max(12, this.height / 2 - 120);
        int panelH = 140; // taller to fit the in-development disclaimer
        // Place buttons below the info panel with a small gap
        int top = panelY + panelH + 10;
        int w = Math.min(320, this.width - 40);
        int h = 20;
        int gap = 8;

        ButtonWidget questing = ButtonWidget.builder(
                        Text.literal("Questing Mode (Village Recruitment)"),
                        btn -> submitChoice(true))
                .dimensions(cx - w / 2, top, w, h)
                .build();
        questing.active = canChoose;
        this.addDrawableChild(questing);

        ButtonWidget admin = ButtonWidget.builder(
                        Text.literal("Admin Mode (Manual Spawn / Commands)"),
                        btn -> submitChoice(false))
                .dimensions(cx - w / 2, top + h + gap, w, h)
                .build();
        admin.active = canChoose;
        this.addDrawableChild(admin);
    }

    private void submitChoice(boolean questingMode) {
        if (submitted || this.client == null || this.client.getNetworkHandler() == null || !canChoose) {
            return;
        }
        submitted = true;
        ClientPlayNetworking.send(new ModeSelectionChoicePayload(questingMode));
        // After Admin Mode is chosen, drop the user into BotRestoreScreen so they
        // can pick saved bots to spawn or create their first one.  Questing mode
        // has its own recruitment dialogue flow — leave it alone.
        if (!questingMode && this.client != null) {
            java.util.List<String> aliases = net.wcfcarolina13.FrensClient.getKnownRestorableBotAliases();
            this.client.setScreen(new BotRestoreScreen(null, aliases));
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input != null && input.key() == 256 /* ESC */) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        super.close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int panelW = Math.min(360, this.width - 30);
        int panelX = cx - panelW / 2;
        int panelY = Math.max(12, this.height / 2 - 120);
        int panelH = 140;

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0181818);
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF4A4A4A);
        context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF4A4A4A);
        context.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF4A4A4A);
        context.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF4A4A4A);

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Choose AI-Player Mode"), cx, panelY + 8, 0xFFE6D7A3);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("World: " + worldLabel), cx, panelY + 22, 0xFFB8A76A);

        int textY = panelY + 36;
        List<OrderedText> lines = this.textRenderer.wrapLines(
                Text.literal("Questing: Go to a village, interact with villager/bell/bed, then initiate contact to recruit.\n"
                + "Admin: Use /bot spawn <name> admin to create companions. Each needs a unique name. You can add more anytime."),
                panelW - 14
        );
        for (OrderedText line : lines) {
            context.drawText(this.textRenderer, line, panelX + 7, textY, 0xFFD5D5D5, false);
            textY += this.textRenderer.fontHeight + 1;
        }

        // In-development disclaimer for Questing mode. Rendered as a boxed
        // warning banner inside the info panel so it's impossible to miss
        // when the player is about to pick a mode.
        textY += 4;
        int warnBoxX = panelX + 5;
        int warnBoxY = textY;
        int warnBoxW = panelW - 10;
        String warnMark = "\u26A0"; // ⚠
        List<OrderedText> warnLines = this.textRenderer.wrapLines(
                Text.literal(warnMark + " Heads up: Questing mode is still in development and more buggy than Admin. Pick Admin if you want the most stable experience."),
                warnBoxW - 8);
        int warnBoxH = warnLines.size() * (this.textRenderer.fontHeight + 1) + 6;
        int warnFill = 0xC0332A14;
        int warnBorder = 0xFFB08C40;
        context.fill(warnBoxX, warnBoxY, warnBoxX + warnBoxW, warnBoxY + warnBoxH, warnFill);
        context.fill(warnBoxX, warnBoxY, warnBoxX + warnBoxW, warnBoxY + 1, warnBorder);
        context.fill(warnBoxX, warnBoxY + warnBoxH - 1, warnBoxX + warnBoxW, warnBoxY + warnBoxH, warnBorder);
        context.fill(warnBoxX, warnBoxY, warnBoxX + 1, warnBoxY + warnBoxH, warnBorder);
        context.fill(warnBoxX + warnBoxW - 1, warnBoxY, warnBoxX + warnBoxW, warnBoxY + warnBoxH, warnBorder);
        int warnTextY = warnBoxY + 3;
        for (OrderedText line : warnLines) {
            context.drawText(this.textRenderer, line, warnBoxX + 4, warnTextY, 0xFFFFE08A, false);
            warnTextY += this.textRenderer.fontHeight + 1;
        }

        if (!canChoose) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Only an admin or delegated player can choose this."),
                    cx,
                    this.height - 26,
                    0xFFFF8080);
        } else if (submitted) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Applying selection..."),
                    cx,
                    this.height - 26,
                    0xFFB8A76A);
        }
    }
}
