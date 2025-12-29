package net.shasankp000.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.Network.ConfigJsonUtil;
import net.shasankp000.Network.configNetworkManager;
import net.minecraft.text.OrderedText;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Secondary configuration screen focused on high-level bot toggles that used to require manual chat commands.
 * This UI lets players persist preferred spawn modes, LLM enablement, and safety toggles across sessions.
 */
public class BotControlScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int MIN_TEXT_ZONE = 34;
    private static final int TEXT_PADDING = 6;
    private static final int BUTTON_SECTION_PADDING = 8;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private CyclingButtonWidget<Boolean> worldToggle;
    private ToggleLayout toggleLayout;
    private double scrollOffset;
    private int listTop;
    private int listHeight;

    public BotControlScreen(Screen parent) {
        super(Text.of("Bot Controls"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        scrollOffset = 0;
        int centerX = this.width / 2;
        int top = 80;
        toggleLayout = computeToggleLayout();

        boolean worldEnabled = AIPlayer.CONFIG.isDefaultLlmWorldEnabled();
        worldToggle = CyclingButtonWidget.onOffBuilder(worldEnabled)
                .build(centerX - 90, top - 40, Math.min(this.width - 80, 260), 20,
                        Text.of("LLM World Toggle"), (button, value) -> {});
        worldToggle.setTooltip(Tooltip.of(Text.of("Master switch for LLM chat control in this world.")));
        worldToggle.setX((this.width - worldToggle.getWidth()) / 2);
        this.addDrawableChild(worldToggle);

        List<String> aliases = new ArrayList<>(AIPlayer.CONFIG.getBotGameProfile().keySet());
        java.util.LinkedHashSet<String> aliasSet = new java.util.LinkedHashSet<>(aliases);
        aliasSet.add("Jake");
        aliasSet.add("Bob");
        aliasSet.add("default");
        List<String> normalized = new ArrayList<>(aliasSet);
        normalized.sort(Comparator.comparing(name -> name.equalsIgnoreCase("default") ? "0" : name.toLowerCase(Locale.ROOT)));

        for (String alias : normalized) {
            rows.add(buildRow(alias));
        }
        refreshRowMetrics();

        listTop = worldToggle.getY() + 40;
        listHeight = Math.max(60, this.height - listTop - 120);

        ButtonWidget saveButton = ButtonWidget.builder(Text.of("Save"), button -> {
            saveSettings();
            if (this.client != null) {
                this.client.getToastManager().add(SystemToast.create(this.client,
                        SystemToast.Type.NARRATOR_TOGGLE,
                        Text.of("Bot settings saved"), Text.of("Applied new bot preferences")));
            }
        }).dimensions(centerX - 100, this.height - 40, 90, 20).build();
        this.addDrawableChild(saveButton);

        ButtonWidget closeButton = ButtonWidget.builder(Text.of("Close"), button -> close())
                .dimensions(centerX + 10, this.height - 40, 90, 20).build();
        this.addDrawableChild(closeButton);
    }

    private Row buildRow(String alias) {
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getOrCreateBotControl(alias);
        ManualConfig.BotOwnership ownership = AIPlayer.CONFIG.getOwner(alias);
        String ownerName = ownership != null && ownership.ownerName() != null && !ownership.ownerName().isBlank()
                ? ownership.ownerName()
                : ownership != null && ownership.ownerUuid() != null ? ownership.ownerUuid() : "Unassigned";
        boolean isDefault = alias.equalsIgnoreCase("default");
        String title = isDefault ? "Default Profile" : alias;
        String subtitle = isDefault
                ? "Fallback profile used whenever a bot has no alias override."
                : "Owner: " + ownerName;
        String helper = isDefault
                ? "Applies to any bot without its own override."
                : "Customize how " + alias + " behaves in-game.";

        int buttonWidth = toggleLayout.buttonWidth();
        CyclingButtonWidget<Boolean> autoSpawn = CyclingButtonWidget.onOffBuilder(settings.isAutoSpawn())
                .build(0, 0, buttonWidth, 20, Text.of("Auto Spawn"), (button, value) -> {});
        autoSpawn.setTooltip(Tooltip.of(Text.of("Automatically spawn this bot at login using the saved location.")));
        this.addDrawableChild(autoSpawn);

        CyclingButtonWidget<String> spawnMode = CyclingButtonWidget.<String>builder(
                        value -> Text.of("play".equals(value) ? "Play" : "Training"),
                        () -> settings.getSpawnMode())
                .values("training", "play")
                .build(0, 0, buttonWidth, 20, Text.of("Mode"), (button, value) -> {});
        spawnMode.setTooltip(Tooltip.of(Text.of("Training keeps the bot sandboxed. Play enables full AI behaviors.")));
        this.addDrawableChild(spawnMode);

        CyclingButtonWidget<Boolean> teleport = CyclingButtonWidget.onOffBuilder(settings.isTeleportDuringSkills())
                .build(0, 0, buttonWidth, 20, Text.of("Teleport"), (button, value) -> {});
        teleport.setTooltip(Tooltip.of(Text.of("Allow emergency teleports during skills (needed for tight shafts).")));
        this.addDrawableChild(teleport);

        CyclingButtonWidget<Boolean> pause = CyclingButtonWidget.onOffBuilder(settings.isPauseOnFullInventory())
                .build(0, 0, buttonWidth, 20, Text.of("Pause Inv"), (button, value) -> {});
        pause.setTooltip(Tooltip.of(Text.of("Pause the job when the inventory is full; resume with /bot resume.")));
        this.addDrawableChild(pause);
        
        CyclingButtonWidget<Boolean> dropTeleport = CyclingButtonWidget.onOffBuilder(settings.isTeleportDuringDropSweep())
                .build(0, 0, buttonWidth, 20, Text.of("Drop TP"), (button, value) -> {});
        dropTeleport.setTooltip(Tooltip.of(Text.of("Allow teleports when collecting drops after mining.")));
        this.addDrawableChild(dropTeleport);

        CyclingButtonWidget<Boolean> llm = CyclingButtonWidget.onOffBuilder(settings.isLlmEnabled())
                .build(0, 0, buttonWidth, 20, Text.of("LLM Bot"), (button, value) -> {});
        llm.setTooltip(Tooltip.of(Text.of("Enable natural-language control for this bot.")));
        this.addDrawableChild(llm);

        return new Row(alias, title, subtitle, helper, autoSpawn, spawnMode, teleport, pause, dropTeleport, llm);
    }

    private void saveSettings() {
        ManualConfig config = AIPlayer.CONFIG;
        config.setDefaultLlmWorldEnabled(worldToggle.getValue());
        for (Row row : rows) {
            ManualConfig.BotControlSettings settings = config.getOrCreateBotControl(row.alias);
            settings.setAutoSpawn(row.autoSpawn.getValue());
            settings.setSpawnMode(row.spawnMode.getValue());
            settings.setTeleportDuringSkills(row.teleport.getValue());
            settings.setPauseOnFullInventory(row.inventoryPause.getValue());
            settings.setTeleportDuringDropSweep(row.dropTeleport.getValue());
            settings.setLlmEnabled(row.llmEnabled.getValue());
        }
        config.save();
        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xB0000000);
        ToggleLayout layout = this.toggleLayout != null ? this.toggleLayout : computeToggleLayout();
        int centerX = this.width / 2;
        context.drawText(this.textRenderer, this.title, centerX - this.textRenderer.getWidth(this.title) / 2, 20, 0xFFFFFF, true);
        int infoWidth = this.width - 60;
        int tipY = worldToggle.getY() - 16;
        List<OrderedText> infoLines = this.textRenderer.wrapLines(
                Text.of("Each row customizes one bot alias; scroll if you have many bots. The default profile is used when a bot has no override."),
                infoWidth);
        for (OrderedText line : infoLines) {
            int lineWidth = this.textRenderer.getWidth(line);
            context.drawText(this.textRenderer, line, (this.width - lineWidth) / 2, tipY, 0xFFD5A6, false);
            tipY += this.textRenderer.fontHeight;
        }
        int headerY = worldToggle != null ? worldToggle.getY() + 18 : 50;
        String[] headers = {"Auto Spawn", "Mode", "Teleport", "Pause Inv", "Drop TP", "LLM Bot"};
        int columnWidth = layout.buttonWidth();
        int startX = layout.startX();
        int spacing = layout.spacing();
        for (int i = 0; i < headers.length; i++) {
            context.drawText(this.textRenderer, headers[i], startX + i * (columnWidth + spacing), headerY, 0xAAAAAA, false);
        }
        renderRows(context, layout);
        int pauseY = Math.min(this.height - 90, listTop + listHeight + 8);
        java.util.List<net.minecraft.text.OrderedText> pauseLines = this.textRenderer.wrapLines(
                net.minecraft.text.Text.of("Pause Inv pauses jobs when inventories fill; use /bot resume <alias> to continue."),
                this.width - 40);
        for (net.minecraft.text.OrderedText line : pauseLines) {
            int width = this.textRenderer.getWidth(line);
            context.drawText(this.textRenderer, line, (this.width - width) / 2, pauseY, 0xFFFFB6C1, true);
            pauseY += this.textRenderer.fontHeight + 2;
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderRows(DrawContext context, ToggleLayout layout) {
        if (rows.isEmpty()) {
            return;
        }
        int maxScroll = Math.max(0, getContentHeight() - listHeight);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);
        int clipLeft = layout.startX() - 12;
        int clipRight = layout.startX() + layout.rowWidth() + 12;
        context.enableScissor(clipLeft, listTop, clipRight, listTop + listHeight);
        int currentY = listTop - (int) scrollOffset;
        for (Row row : rows) {
            int rowHeight = row.totalHeight();
            boolean visible = currentY + rowHeight > listTop && currentY < listTop + listHeight;
            row.layout(layout, currentY, visible);
            if (visible) {
                row.render(context, layout, currentY, rowHeight, this.textRenderer);
            }
            currentY += rowHeight;
        }
        context.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (toggleLayout == null || listHeight <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        if (mouseX >= toggleLayout.startX() - 12 && mouseX <= toggleLayout.startX() + toggleLayout.rowWidth() + 12
                && mouseY >= listTop && mouseY <= listTop + listHeight) {
            int maxScroll = Math.max(0, getContentHeight() - listHeight);
            scrollOffset = MathHelper.clamp(scrollOffset - verticalAmount * 12, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private ToggleLayout computeToggleLayout() {
        int columns = 5;
        int spacing = 8;
        int available = Math.max(140, this.width - 80);
        int buttonWidth = Math.max(70, (available - spacing * (columns - 1)) / columns);
        int rowWidth = buttonWidth * columns + spacing * (columns - 1);
        int startX = (this.width - rowWidth) / 2;
        return new ToggleLayout(buttonWidth, spacing, rowWidth, startX);
    }

    private int getContentHeight() {
        int height = 0;
        for (Row row : rows) {
            height += row.totalHeight();
        }
        return height;
    }

    private void refreshRowMetrics() {
        if (this.textRenderer == null || this.toggleLayout == null) {
            return;
        }
        for (Row row : rows) {
            row.refreshMetrics(this.textRenderer, this.toggleLayout);
        }
    }

    private static class Row {
        final String alias;
        final String title;
        final String subtitle;
        final String helper;
        final CyclingButtonWidget<Boolean> autoSpawn;
        final CyclingButtonWidget<String> spawnMode;
        final CyclingButtonWidget<Boolean> teleport;
        final CyclingButtonWidget<Boolean> inventoryPause;
        final CyclingButtonWidget<Boolean> dropTeleport;
        final CyclingButtonWidget<Boolean> llmEnabled;
        private int textZoneHeight = MIN_TEXT_ZONE;
        private int totalHeight = MIN_TEXT_ZONE + BUTTON_HEIGHT + BUTTON_SECTION_PADDING * 2;

        Row(String alias,
            String title,
            String subtitle,
            String helper,
            CyclingButtonWidget<Boolean> autoSpawn,
            CyclingButtonWidget<String> spawnMode,
            CyclingButtonWidget<Boolean> teleport,
            CyclingButtonWidget<Boolean> inventoryPause,
            CyclingButtonWidget<Boolean> dropTeleport,
            CyclingButtonWidget<Boolean> llmEnabled) {
            this.alias = alias;
            this.title = title;
            this.subtitle = subtitle;
            this.helper = helper;
            this.autoSpawn = autoSpawn;
            this.spawnMode = spawnMode;
            this.teleport = teleport;
            this.inventoryPause = inventoryPause;
            this.dropTeleport = dropTeleport;
            this.llmEnabled = llmEnabled;
        }

        void refreshMetrics(TextRenderer renderer, ToggleLayout layout) {
            int frameWidth = layout.rowWidth() + 20;
            int textWidth = frameWidth - 20;
            int height = TEXT_PADDING + renderer.fontHeight + 2;
            height += measureWrappedHeight(renderer, subtitle, textWidth);
            height += measureWrappedHeight(renderer, helper, textWidth);
            height += TEXT_PADDING;
            textZoneHeight = Math.max(MIN_TEXT_ZONE, height);
            totalHeight = textZoneHeight + BUTTON_HEIGHT + BUTTON_SECTION_PADDING * 2;
        }

        int totalHeight() {
            return totalHeight;
        }

        void layout(ToggleLayout layout, int rowY, boolean visible) {
            int startX = layout.startX();
            int spacing = layout.spacing();
            int buttonWidth = layout.buttonWidth();
            int buttonY = rowY + textZoneHeight + 2;
            autoSpawn.visible = visible;
            autoSpawn.setX(startX);
            autoSpawn.setY(buttonY);
            autoSpawn.setWidth(buttonWidth);

            spawnMode.visible = visible;
            spawnMode.setX(startX + (buttonWidth + spacing));
            spawnMode.setY(buttonY);
            spawnMode.setWidth(buttonWidth);

            teleport.visible = visible;
            teleport.setX(startX + (buttonWidth + spacing) * 2);
            teleport.setY(buttonY);
            teleport.setWidth(buttonWidth);

            inventoryPause.visible = visible;
            inventoryPause.setX(startX + (buttonWidth + spacing) * 3);
            inventoryPause.setY(buttonY);
            inventoryPause.setWidth(buttonWidth);
            
            dropTeleport.visible = visible;
            dropTeleport.setX(startX + (buttonWidth + spacing) * 4);
            dropTeleport.setY(buttonY);
            dropTeleport.setWidth(buttonWidth);

            llmEnabled.visible = visible;
            llmEnabled.setX(startX + (buttonWidth + spacing) * 5);
            llmEnabled.setY(buttonY);
            llmEnabled.setWidth(buttonWidth);
        }

        void render(DrawContext context, ToggleLayout layout, int rowY, int rowHeight, TextRenderer textRenderer) {
            int frameX = layout.startX() - 10;
            int frameWidth = layout.rowWidth() + 20;
            context.fill(frameX, rowY, frameX + frameWidth, rowY + rowHeight - 4, 0x33000000);
            int textWidth = frameWidth - 20;
            int textX = frameX + 10;
            int textY = rowY + TEXT_PADDING;
            context.drawText(textRenderer, title, textX, textY, 0xFFE6C17B, false);
            textY += textRenderer.fontHeight + 2;
            drawWrappedLines(context, textRenderer, subtitle, textWidth, textX, textY, 0xFFC6C6C6);
            textY += measureWrappedHeight(textRenderer, subtitle, textWidth);
            drawWrappedLines(context, textRenderer, helper, textWidth, textX, textY, 0xFF9FB6CD);

            int buttonTop = rowY + textZoneHeight;
            context.fill(layout.startX() - 6,
                    buttonTop - 4,
                    layout.startX() + layout.rowWidth() + 6,
                    buttonTop + BUTTON_HEIGHT + BUTTON_SECTION_PADDING,
                    0x22000000);
        }

        private static void drawWrappedLines(DrawContext context,
                                             TextRenderer renderer,
                                             String text,
                                             int width,
                                             int x,
                                             int startY,
                                             int color) {
            if (text == null || text.isBlank()) {
                return;
            }
            int y = startY;
            List<OrderedText> lines = renderer.wrapLines(Text.of(text), width);
            for (OrderedText line : lines) {
                context.drawText(renderer, line, x, y, color, false);
                y += renderer.fontHeight;
            }
        }

        private static int measureWrappedHeight(TextRenderer renderer, String text, int width) {
            if (text == null || text.isBlank()) {
                return 0;
            }
            List<OrderedText> lines = renderer.wrapLines(Text.of(text), width);
            return lines.size() * renderer.fontHeight + 2;
        }
    }

    private record ToggleLayout(int buttonWidth, int spacing, int rowWidth, int startX) {
    }
}
