package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Lightweight recovery menu shown when the client knows about saved bots but none are present.
 */
public class BotRestoreScreen extends Screen {

    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int ALIASES_PER_PAGE = 6;

    private final Screen parent;
    private final List<String> aliases;
    private int page = 0;

    public BotRestoreScreen(Screen parent, List<String> aliases) {
        super(Text.literal("Restore Companion"));
        this.parent = parent;
        this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    @Override
    protected void init() {
        int panelWidth = 280;
        int panelHeight = 220;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        int pageCount = Math.max(1, (int) Math.ceil((double) aliases.size() / (double) ALIASES_PER_PAGE));
        page = Math.max(0, Math.min(page, pageCount - 1));

        int startIndex = page * ALIASES_PER_PAGE;
        int endIndex = Math.min(aliases.size(), startIndex + ALIASES_PER_PAGE);
        int buttonX = (this.width - BUTTON_WIDTH) / 2;
        int y = panelY + 52;

        for (int i = startIndex; i < endIndex; i++) {
            String alias = aliases.get(i);
            this.addDrawableChild(ButtonWidget.builder(
                    Text.literal("Respawn " + alias),
                    btn -> restoreAlias(alias)
            ).dimensions(buttonX, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
            y += BUTTON_HEIGHT + BUTTON_GAP;
        }

        int navY = panelY + panelHeight - 50;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Cancel"),
                btn -> this.close()
        ).dimensions(panelX + 16, navY, 80, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("◀ Prev"),
                btn -> {
                    if (page > 0) {
                        page--;
                        this.clearAndInit();
                    }
                }
        ).dimensions(panelX + panelWidth - 184, navY, 80, 20).build()).active = page > 0;

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Next ▶"),
                btn -> {
                    if (page + 1 < pageCount) {
                        page++;
                        this.clearAndInit();
                    }
                }
        ).dimensions(panelX + panelWidth - 96, navY, 80, 20).build()).active = page + 1 < pageCount;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Flat dim background avoids Minecraft's per-frame blur reentry crash on custom screens.
        context.fill(0, 0, this.width, this.height, 0xD0101010);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int panelWidth = 280;
        int panelHeight = 220;
        int panelX = (this.width - panelWidth) / 2;
        int panelY = (this.height - panelHeight) / 2;

        context.fill(panelX - 1, panelY - 1, panelX + panelWidth + 1, panelY + panelHeight + 1, 0xFF2C2C2C);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xDD101010);

        String title = "No companions are currently present";
        int titleWidth = this.textRenderer.getWidth(title);
        context.drawTextWithShadow(this.textRenderer, title, panelX + (panelWidth - titleWidth) / 2, panelY + 14, 0xFFE6D7A3);

        String subtitle = "Pick a saved bot to respawn in Admin mode.";
        int subtitleWidth = this.textRenderer.getWidth(subtitle);
        context.drawTextWithShadow(this.textRenderer, subtitle, panelX + (panelWidth - subtitleWidth) / 2, panelY + 30, 0xFFB8A76A);

        super.render(context, mouseX, mouseY, delta);

        int pageCount = Math.max(1, (int) Math.ceil((double) aliases.size() / (double) ALIASES_PER_PAGE));
        String footer = aliases.isEmpty()
                ? "No saved bot aliases were found."
                : "Page " + (page + 1) + " / " + pageCount + " • You can also use /bot spawn <name> admin";
        String trimmedFooter = this.textRenderer.trimToWidth(footer, panelWidth - 20);
        context.drawTextWithShadow(this.textRenderer, trimmedFooter, panelX + 10, panelY + panelHeight - 24, 0xFF909090);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private void restoreAlias(String alias) {
        MinecraftClient client = this.client;
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        String target = quoteAlias(alias);
        client.getNetworkHandler().sendChatCommand("bot spawn " + target + " admin");
        if (client.player != null) {
            client.player.sendMessage(Text.literal("Respawning " + alias + " in Admin mode."), true);
        }
        client.setScreen(null);
    }

    private static String quoteAlias(String alias) {
        if (alias == null) {
            return "";
        }
        String trimmed = alias.trim();
        if (trimmed.contains(" ")) {
            return "\"" + trimmed + "\"";
        }
        return trimmed;
    }
}