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
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentHeight;
    private int scrollOffset;
    private int statusY;
    private List<OrderedText> headingLines = List.of();
    private List<OrderedText> worldLines = List.of();
    private List<OrderedText> infoLines = List.of();
    private List<OrderedText> warnLines = List.of();
    private List<OrderedText> statusLines = List.of();

    public WorldModeSelectionScreen(String worldLabel, boolean canChoose) {
        super(Text.literal("Choose Frens World Mode"));
        this.worldLabel = (worldLabel == null || worldLabel.isBlank()) ? "default" : worldLabel.trim();
        this.canChoose = canChoose;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        panelW = Math.max(40, Math.min(360, this.width - 30));
        panelX = cx - panelW / 2;
        headingLines = this.textRenderer.wrapLines(this.title, panelW - 22);
        worldLines = this.textRenderer.wrapLines(Text.literal("World: " + worldLabel), panelW - 22);
        infoLines = this.textRenderer.wrapLines(Text.literal(
                "Frens works without Ollama or any AI model. AI companion chat is optional (local Ollama); voices are optional too.\n"
                + "Questing: Go to a village, interact with villager/bell/bed, then initiate contact to recruit.\n"
                + "Admin: Use /bot spawn <name> admin to create companions. Each needs a unique name. You can add more anytime."),
                panelW - 22);
        warnLines = this.textRenderer.wrapLines(Text.literal(
                "\u26A0 Heads up: Questing mode is still in development and more buggy than Admin. Pick Admin if you want the most stable experience."),
                panelW - 26);
        statusLines = this.textRenderer.wrapLines(Text.literal(canChoose
                ? "Applying selection..." : "Only an admin or delegated player can choose this."), panelW - 14);
        int lineHeight = this.textRenderer.fontHeight + 1;
        contentHeight = 8 + headingLines.size() * lineHeight + 4 + worldLines.size() * lineHeight
                + 4 + infoLines.size() * lineHeight + 4 + warnLines.size() * lineHeight + 6 + 8;
        int footerHeight = 10 + 48 + 8 + statusLines.size() * lineHeight;
        panelH = Math.min(contentHeight, Math.max(20, this.height - footerHeight - 16));
        panelY = Math.max(8, (this.height - panelH - footerHeight) / 2);
        scrollOffset = 0;
        int top = panelY + panelH + 10;
        int w = Math.max(20, Math.min(320, this.width - 40));
        int h = 20;
        int gap = 8;
        statusY = top + h * 2 + gap + 8;

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
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (contentHeight > panelH && mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + panelH) {
            scrollOffset = Math.max(0, Math.min(contentHeight - panelH,
                    scrollOffset - (int) (verticalAmount * (this.textRenderer.fontHeight + 1) * 3)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int drawLines(DrawContext context, List<OrderedText> lines, int x, int y, int color) {
        for (OrderedText line : lines) {
            context.drawText(this.textRenderer, line, x, y, color, false);
            y += this.textRenderer.fontHeight + 1;
        }
        return y;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xD0101010);
        super.render(context, mouseX, mouseY, delta);

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0181818);
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF4A4A4A);
        context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF4A4A4A);
        context.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF4A4A4A);
        context.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF4A4A4A);

        context.enableScissor(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + panelH - 1);
        int textY = drawLines(context, headingLines, panelX + 7, panelY + 8 - scrollOffset, 0xFFE6D7A3) + 4;
        textY = drawLines(context, worldLines, panelX + 7, textY, 0xFFB8A76A) + 4;
        textY = drawLines(context, infoLines, panelX + 7, textY, 0xFFD5D5D5) + 4;
        int warnBoxX = panelX + 5;
        int warnBoxY = textY;
        int warnBoxW = panelW - 18;
        int warnBoxH = warnLines.size() * (this.textRenderer.fontHeight + 1) + 6;
        int warnFill = 0xC0332A14;
        int warnBorder = 0xFFB08C40;
        context.fill(warnBoxX, warnBoxY, warnBoxX + warnBoxW, warnBoxY + warnBoxH, warnFill);
        context.fill(warnBoxX, warnBoxY, warnBoxX + warnBoxW, warnBoxY + 1, warnBorder);
        context.fill(warnBoxX, warnBoxY + warnBoxH - 1, warnBoxX + warnBoxW, warnBoxY + warnBoxH, warnBorder);
        context.fill(warnBoxX, warnBoxY, warnBoxX + 1, warnBoxY + warnBoxH, warnBorder);
        context.fill(warnBoxX + warnBoxW - 1, warnBoxY, warnBoxX + warnBoxW, warnBoxY + warnBoxH, warnBorder);
        drawLines(context, warnLines, warnBoxX + 4, warnBoxY + 3, 0xFFFFE08A);
        context.disableScissor();

        if (contentHeight > panelH) {
            int trackHeight = panelH - 4;
            int thumbHeight = Math.max(8, trackHeight * panelH / contentHeight);
            int thumbY = panelY + 2 + scrollOffset * (trackHeight - thumbHeight) / (contentHeight - panelH);
            context.fill(panelX + panelW - 6, panelY + 2, panelX + panelW - 3,
                    panelY + panelH - 2, 0xFF333333);
            context.fill(panelX + panelW - 6, thumbY, panelX + panelW - 3,
                    thumbY + thumbHeight, 0xFFB8A76A);
        }

        if (!canChoose || submitted) {
            drawLines(context, statusLines, panelX + 7, statusY, canChoose ? 0xFFB8A76A : 0xFFFF8080);
        }
    }
}
