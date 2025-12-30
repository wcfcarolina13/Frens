package net.shasankp000.GraphicalUserInterface;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.ui.BotPlayerInventoryScreenHandler;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BotPlayerInventoryScreen extends HandledScreen<BotPlayerInventoryScreenHandler> {
    private static final Identifier BACKGROUND_TEXTURE = Identifier.of("minecraft", "textures/gui/container/inventory.png");
    private static final int SECTION_WIDTH = 176;
    private static final int SECTION_HEIGHT = 166;
    private static final int BLOCK_GAP = 12;
    private static final int STATS_AREA_HEIGHT = 48;
    private static final int TOPIC_PADDING = 6;
    private static final int TOPIC_ROW_HEIGHT = 10;
    private static final int TOPIC_CONTROL_GAP = 2;
    private static final double FOLLOW_DISTANCE_STEP = 1.0D;
    private static final double FOLLOW_DISTANCE_MIN = 1.0D;
    private static final double FOLLOW_DISTANCE_MAX = 64.0D;
    private static final double FOLLOW_DISTANCE_DEFAULT = 4.0D;
    private OtherClientPlayerEntity fallbackBot;
    private final String botAlias;
    private float lastMouseX;
    private float lastMouseY;
    private int topicScrollIndex;
    private boolean topicsExpanded;

    private static final int TOPICS_OVERLAY_MAX_WIDTH = 240;
    private static final int TOPICS_OVERLAY_MAX_HEIGHT = 220;
    private static final int TOPICS_OVERLAY_MIN_WIDTH = 190;
    private static final int TOPICS_OVERLAY_MIN_HEIGHT = 140;
    private static final int TOPICS_OVERLAY_PADDING = 10;
    private static final int TOPICS_OVERLAY_HEADER_PAD = 6;
    private static final int TOPICS_OVERLAY_FOOTER_PAD = 6;

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private enum TopicAction {
        FOLLOW,
        GUARD,
        PATROL,
        RETURN_HOME,
        SLEEP,
        AUTO_RETURN_SUNSET,
        AUTO_RETURN_SUNSET_GUARD_PATROL,
        IDLE_HOBBIES,
        VOICED_DIALOGUE,
        TELEPORT_SKILLS,
        TELEPORT_DROP_SWEEP,
        BASES,
        SKILL_FISH,
        SKILL_WOODCUT,
        SKILL_WOOL,
        SKILL_HOVEL,
        SKILL_BURROW,
        SKILL_FARM,
        SKILL_MINING,
        SKILL_STRIPMINE,
        SKILL_ASCENT,
        SKILL_DESCENT
    }

    private static final class TopicEntry {
        private final String label;
        private final TopicAction action;
        private final boolean toggle;
        private final int indent;

        private TopicEntry(String label, TopicAction action, boolean toggle, int indent) {
            this.label = label;
            this.action = action;
            this.toggle = toggle;
            this.indent = indent;
        }
    }

    private static final List<TopicEntry> TOPIC_ENTRIES = List.of(
            new TopicEntry("Follow", TopicAction.FOLLOW, true, 0),
            new TopicEntry("Guard", TopicAction.GUARD, true, 0),
            new TopicEntry("Patrol", TopicAction.PATROL, true, 0),
            new TopicEntry("Return Home", TopicAction.RETURN_HOME, false, 0),
            new TopicEntry("Sleep", TopicAction.SLEEP, false, 0),
            new TopicEntry("Auto Home @ Sunset", TopicAction.AUTO_RETURN_SUNSET, true, 0),
            new TopicEntry("Guard/Patrol eligible", TopicAction.AUTO_RETURN_SUNSET_GUARD_PATROL, true, 1),
            new TopicEntry("Idle Hobbies", TopicAction.IDLE_HOBBIES, true, 0),
            new TopicEntry("Voiced Dialogue", TopicAction.VOICED_DIALOGUE, true, 0),
            new TopicEntry("TP during Skills", TopicAction.TELEPORT_SKILLS, true, 0),
            new TopicEntry("TP during Sweeps", TopicAction.TELEPORT_DROP_SWEEP, true, 0),
            new TopicEntry("Bases…", TopicAction.BASES, false, 0),
            new TopicEntry("Fishing", TopicAction.SKILL_FISH, false, 0),
            new TopicEntry("Woodcut", TopicAction.SKILL_WOODCUT, false, 0),
            new TopicEntry("Wool", TopicAction.SKILL_WOOL, false, 0),
            new TopicEntry("Hovel", TopicAction.SKILL_HOVEL, false, 0),
            new TopicEntry("Burrow", TopicAction.SKILL_BURROW, false, 0),
            new TopicEntry("Farming", TopicAction.SKILL_FARM, false, 0),
            new TopicEntry("Mining", TopicAction.SKILL_MINING, false, 0),
            new TopicEntry("Stripmine", TopicAction.SKILL_STRIPMINE, false, 1),
            new TopicEntry("Ascent", TopicAction.SKILL_ASCENT, false, 1),
            new TopicEntry("Descent", TopicAction.SKILL_DESCENT, false, 1)
    );

    public BotPlayerInventoryScreen(BotPlayerInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = SECTION_WIDTH * 2 + BLOCK_GAP;
        this.backgroundHeight = SECTION_HEIGHT + STATS_AREA_HEIGHT;
        this.titleX = 8;
        this.titleY = 6;
        this.playerInventoryTitleX = SECTION_WIDTH + BLOCK_GAP + 8;
        this.playerInventoryTitleY = 6;
        this.botAlias = extractAlias(title.getString());
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x + SECTION_WIDTH + BLOCK_GAP, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);

        int statsTop = y + SECTION_HEIGHT + 2;
        context.fill(x, statsTop, x + SECTION_WIDTH, statsTop + STATS_AREA_HEIGHT - 4, 0xC0101010);
        drawBotStats(context, x + 6, statsTop + 6);
        drawTopicPanel(context, x + SECTION_WIDTH + BLOCK_GAP, statsTop, mouseX, mouseY);
    }

    private void drawBotStats(DrawContext context, int x, int y) {
        BotPlayerInventoryScreenHandler handler = this.handler;
        float health = handler.getBotHealth();
        float maxHealth = handler.getBotMaxHealth();
        int hunger = handler.getBotHunger();
        int level = handler.getBotLevel();
        float xpProgress = handler.getBotXpProgress();

        String healthLabel = String.format("Health: %.1f / %.1f", health, maxHealth);
        context.drawText(this.textRenderer, healthLabel, x, y, 0xFFEFEFEF, false);
        drawBar(context, x, y + 10, 120, 6, health / maxHealth, 0xFFB83E3E);
        String hungerLabel = "Hunger: " + hunger;
        context.drawText(this.textRenderer, hungerLabel, x, y + 20, 0xFFEFEFEF, false);
        drawBar(context, x, y + 30, 120, 6, MathHelper.clamp(hunger / 20f, 0.0f, 1.0f), 0xFFE3C05C);

        int xpAreaX = x + 130;
        int xpAreaW = Math.max(40, SECTION_WIDTH - 136);
        String xpLabel = "XP L" + level;
        context.drawText(this.textRenderer, xpLabel, xpAreaX, y, 0xFFEFEFEF, false);
        drawBar(context, xpAreaX, y + 10, xpAreaW, 6, xpProgress, 0xFF4FA3E3);
    }

    private void drawBar(DrawContext context, int x, int y, int width, int height, float value, int color) {
        int border = 0xFF000000;
        context.fill(x, y, x + width, y + height, 0xFF1A1A1A);
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
        context.fill(x, y, x + 1, y + height, border);
        context.fill(x + width - 1, y, x + width, y + height, border);
        int filled = Math.round((width - 2) * MathHelper.clamp(value, 0.0f, 1.0f));
        context.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
    }

    private void drawTopicPanel(DrawContext context, int panelX, int panelY, int mouseX, int mouseY) {
        int panelWidth = SECTION_WIDTH;
        int panelHeight = STATS_AREA_HEIGHT - 4;
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0101010);

        boolean headerHover = isMouseOverTopicsHeader(mouseX, mouseY);
        int headerColor = headerHover || topicsExpanded ? 0xFFFFE08A : 0xFFE6D7A3;
        context.drawText(this.textRenderer, "Topics", panelX + TOPIC_PADDING, panelY + 2, headerColor, false);
        String openLabel = topicsExpanded ? "Close" : "Open";
        int openX = panelX + panelWidth - TOPIC_PADDING - this.textRenderer.getWidth(openLabel);
        context.drawText(this.textRenderer, openLabel, openX, panelY + 2, headerColor, false);
        int rowX = panelX + TOPIC_PADDING;
        int rowY = panelY + 2 + this.textRenderer.fontHeight + 1;
        int rowW = panelWidth - TOPIC_PADDING * 2;
        int listHeight = panelHeight - (rowY - panelY);
        int visibleRows = Math.max(1, listHeight / TOPIC_ROW_HEIGHT);
        clampTopicScroll(visibleRows);

        for (int i = 0; i < visibleRows; i++) {
            int entryIndex = topicScrollIndex + i;
            if (entryIndex >= TOPIC_ENTRIES.size()) {
                break;
            }
            TopicEntry entry = TOPIC_ENTRIES.get(entryIndex);
            int currentY = rowY + i * TOPIC_ROW_HEIGHT;
            if (entry.action == TopicAction.FOLLOW) {
                drawFollowRow(context, rowX, currentY, rowW, mouseX, mouseY);
            } else {
                boolean active = isEntryActive(entry.action);
                drawTopicRow(context, rowX, currentY, rowW, entry, active, mouseX, mouseY);
            }
        }
    }

    private Rect computeTopicsOverlayRect() {
        int w = MathHelper.clamp(getTopicPanelWidth() + 40, TOPICS_OVERLAY_MIN_WIDTH, TOPICS_OVERLAY_MAX_WIDTH);
        int h = MathHelper.clamp(SECTION_HEIGHT + 8, TOPICS_OVERLAY_MIN_HEIGHT, TOPICS_OVERLAY_MAX_HEIGHT);

        // Anchor near the existing Topics panel (bottom-right of the shared inventory).
        int anchorRight = getTopicPanelX() + getTopicPanelWidth();
        int x = anchorRight - w;
        int y = getTopicPanelY() - h - 6;

        // Clamp within screen.
        x = MathHelper.clamp(x, 12, Math.max(12, this.width - w - 12));
        y = MathHelper.clamp(y, 12, Math.max(12, this.height - h - 12));
        return new Rect(x, y, w, h);
    }

    private boolean isMouseOverTopicsHeader(double mouseX, double mouseY) {
        int panelX = getTopicPanelX();
        int panelY = getTopicPanelY();
        int panelW = getTopicPanelWidth();
        int headerH = this.textRenderer.fontHeight + 4;
        return mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + headerH;
    }

    private boolean isMouseOverTopicsOverlay(double mouseX, double mouseY) {
        if (!topicsExpanded) return false;
        return computeTopicsOverlayRect().contains(mouseX, mouseY);
    }

    private void drawTopicsOverlay(DrawContext context, int mouseX, int mouseY) {
        Rect r = computeTopicsOverlayRect();
        int border = 0xFF000000;
        int fill = 0xEE101010;

        // Panel background + border.
        context.fill(r.x, r.y, r.right(), r.bottom(), fill);
        context.fill(r.x, r.y, r.right(), r.y + 1, border);
        context.fill(r.x, r.bottom() - 1, r.right(), r.bottom(), border);
        context.fill(r.x, r.y, r.x + 1, r.bottom(), border);
        context.fill(r.right() - 1, r.y, r.right(), r.bottom(), border);

        // Header.
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        context.fill(r.x + 1, r.y + 1, r.right() - 1, r.y + headerH, 0xFF161616);
        context.drawText(this.textRenderer, "Topics", r.x + TOPICS_OVERLAY_PADDING, r.y + TOPICS_OVERLAY_HEADER_PAD + 1, 0xFFFFE08A, false);

        // Close box (top-right).
        String closeLabel = "X";
        int closeSize = 12;
        int closeX = r.right() - TOPICS_OVERLAY_PADDING - closeSize;
        int closeY = r.y + (headerH - closeSize) / 2;
        boolean closeHover = mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize;
        context.fill(closeX, closeY, closeX + closeSize, closeY + closeSize, closeHover ? 0xFF2A2A2A : 0xFF1A1A1A);
        int closeTextX = closeX + (closeSize - this.textRenderer.getWidth(closeLabel)) / 2;
        context.drawText(this.textRenderer, closeLabel, closeTextX, closeY + 2, 0xFFEFEFEF, false);

        // Footer hint.
        String hint = "Scroll, click a topic; Esc closes";
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;
        context.fill(r.x + 1, footerY, r.right() - 1, r.bottom() - 1, 0xFF161616);
        context.drawText(this.textRenderer, hint, r.x + TOPICS_OVERLAY_PADDING, footerY + TOPICS_OVERLAY_FOOTER_PAD, 0xFFB0B0B0, false);

        // List region.
        int listX = r.x + TOPICS_OVERLAY_PADDING;
        int listY = r.y + headerH + 2;
        int listW = r.w - TOPICS_OVERLAY_PADDING * 2;
        int listH = (footerY - 2) - listY;
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampTopicScroll(visibleRows);

        // Clip to list area.
        context.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < visibleRows; i++) {
            int entryIndex = topicScrollIndex + i;
            if (entryIndex >= TOPIC_ENTRIES.size()) break;
            TopicEntry entry = TOPIC_ENTRIES.get(entryIndex);
            int rowY = listY + i * TOPIC_ROW_HEIGHT;
            if (entry.action == TopicAction.FOLLOW) {
                drawFollowRow(context, listX, rowY, listW, mouseX, mouseY);
            } else {
                boolean active = isEntryActive(entry.action);
                drawTopicRow(context, listX, rowY, listW, entry, active, mouseX, mouseY);
            }
        }
        context.disableScissor();
    }

    private void toggleTopicsExpanded(boolean open) {
        this.topicsExpanded = open;
        if (!open) {
            return;
        }
        // Ensure the scroll is valid for the (larger) overlay view.
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int listY = r.y + headerH + 2;
        int listH = (r.bottom() - footerH - 2) - listY;
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampTopicScroll(visibleRows);
    }

    private boolean clickTopicsOverlay(double mouseX, double mouseY) {
        Rect r = computeTopicsOverlayRect();
        if (!r.contains(mouseX, mouseY)) {
            toggleTopicsExpanded(false);
            return true;
        }

        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int closeSize = 12;
        int closeX = r.right() - TOPICS_OVERLAY_PADDING - closeSize;
        int closeY = r.y + (headerH - closeSize) / 2;
        if (mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize) {
            toggleTopicsExpanded(false);
            return true;
        }

        // Follow +/- controls.
        int adjust = getFollowAdjustDirectionInOverlay(mouseX, mouseY);
        if (adjust != 0) {
            adjustFollowDistance(adjust);
            return true;
        }

        TopicEntry entry = getTopicEntryAtOverlay(mouseX, mouseY);
        if (entry != null) {
            handleTopicEntry(entry);
            return true;
        }
        return true;
    }

    private TopicEntry getTopicEntryAtOverlay(double mouseX, double mouseY) {
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;

        int listX = r.x + TOPICS_OVERLAY_PADDING;
        int listY = r.y + headerH + 2;
        int listW = r.w - TOPICS_OVERLAY_PADDING * 2;
        int listH = (r.bottom() - footerH - 2) - listY;
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampTopicScroll(visibleRows);

        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return null;
        }
        int rowIndex = (int) ((mouseY - listY) / TOPIC_ROW_HEIGHT);
        if (rowIndex < 0 || rowIndex >= visibleRows) {
            return null;
        }
        int entryIndex = topicScrollIndex + rowIndex;
        if (entryIndex < 0 || entryIndex >= TOPIC_ENTRIES.size()) {
            return null;
        }
        return TOPIC_ENTRIES.get(entryIndex);
    }

    private int getFollowAdjustDirectionInOverlay(double mouseX, double mouseY) {
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;

        int listX = r.x + TOPICS_OVERLAY_PADDING;
        int listY = r.y + headerH + 2;
        int listW = r.w - TOPICS_OVERLAY_PADDING * 2;
        int listH = (r.bottom() - footerH - 2) - listY;
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampTopicScroll(visibleRows);

        int followIndex = 0;
        int visibleStart = topicScrollIndex;
        int visibleEnd = topicScrollIndex + visibleRows;
        if (followIndex < visibleStart || followIndex >= visibleEnd) {
            return 0;
        }

        int rowY = listY + (followIndex - visibleStart) * TOPIC_ROW_HEIGHT;
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = listX + listW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        if (mouseY >= controlY && mouseY < controlY + controlSize) {
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return 1;
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return -1;
            }
        }
        return 0;
    }

    private void drawFollowRow(DrawContext context, int rowX, int rowY, int rowW, int mouseX, int mouseY) {
        boolean active = isFollowActive();
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int baseRow = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int rowColor = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : baseRow;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = rowX + rowW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        String status = active ? "ON" : "OFF";
        int statusX = minusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(status);
        String distanceLabel = formatFollowDistance();
        int distX = statusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(distanceLabel);
        int labelX = rowX + 4;

        if (distX < labelX + 40) {
            distX = labelX + 40;
        }

        context.drawText(this.textRenderer, "Follow", labelX, rowY + 1, 0xFFEFEFEF, false);
        context.drawText(this.textRenderer, distanceLabel, distX, rowY + 1, 0xFFE6D7A3, false);
        context.drawText(this.textRenderer, status, statusX, rowY + 1, active ? 0xFFE6D7A3 : 0xFFB0B0B0, false);

        drawControlBox(context, minusX, controlY, controlSize, "-", mouseX, mouseY);
        drawControlBox(context, plusX, controlY, controlSize, "+", mouseX, mouseY);
    }

    private void drawTopicRow(DrawContext context, int rowX, int rowY, int rowW, TopicEntry entry,
                              boolean active, int mouseX, int mouseY) {
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int baseRow = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int rowColor = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : baseRow;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int textY = rowY + 1;
        int labelX = rowX + 4 + entry.indent * 8;
        String label = entry.indent > 0 ? "- " + entry.label : entry.label;
        context.drawText(this.textRenderer, label, labelX, textY, 0xFFEFEFEF, false);

        String status = entry.toggle ? (active ? "ON" : "OFF") : "RUN";
        int statusX = rowX + rowW - 4 - this.textRenderer.getWidth(status);
        int statusColor = entry.toggle ? (active ? 0xFFE6D7A3 : 0xFFB0B0B0)
                : (hover ? 0xFFE6D7A3 : 0xFFB0B0B0);
        context.drawText(this.textRenderer, status, statusX, textY, statusColor, false);
    }

    private void drawControlBox(DrawContext context, int x, int y, int size, String label, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        int fill = hover ? 0xFF2F2F2F : 0xFF1A1A1A;
        context.fill(x, y, x + size, y + size, fill);
        int textX = x + (size - this.textRenderer.getWidth(label)) / 2;
        int textY = y + 1;
        context.drawText(this.textRenderer, label, textX, textY, 0xFFEFEFEF, false);
    }

    private String formatFollowDistance() {
        double value = getFollowDistance();
        if (value <= 0.0D) {
            return "D--";
        }
        return "D" + String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, this.botAlias, this.titleX, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, "Level: " + this.handler.getBotLevel(), this.titleX + 90, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        if (topicsExpanded) {
            return clickTopicsOverlay(click.x(), click.y());
        }

        if (isMouseOverTopicsHeader(click.x(), click.y())) {
            toggleTopicsExpanded(true);
            return true;
        }
        int adjust = getFollowAdjustDirection(click.x(), click.y());
        if (adjust != 0) {
            adjustFollowDistance(adjust);
            return true;
        }
        TopicEntry entry = getTopicEntryAt(click.x(), click.y());
        if (entry != null) {
            handleTopicEntry(entry);
            return true;
        }
        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (topicsExpanded && isMouseOverTopicsOverlay(mouseX, mouseY)) {
            Rect r = computeTopicsOverlayRect();
            int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
            int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
            int listY = r.y + headerH + 2;
            int listH = (r.bottom() - footerH - 2) - listY;
            int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
            int maxScroll = Math.max(0, TOPIC_ENTRIES.size() - visibleRows);
            if (maxScroll == 0) {
                return true;
            }
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                topicScrollIndex = MathHelper.clamp(topicScrollIndex + delta, 0, maxScroll);
            }
            return true;
        }
        if (isMouseOverTopicPanel(mouseX, mouseY)) {
            int listHeight = getTopicListHeight();
            int visibleRows = Math.max(1, listHeight / TOPIC_ROW_HEIGHT);
            int maxScroll = Math.max(0, TOPIC_ENTRIES.size() - visibleRows);
            if (maxScroll == 0) {
                return false;
            }
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                topicScrollIndex = MathHelper.clamp(topicScrollIndex + delta, 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input != null && topicsExpanded && input.key() == 256 /* ESC */) {
            toggleTopicsExpanded(false);
            return true;
        }
        return super.keyPressed(input);
    }

    private TopicEntry getTopicEntryAt(double mouseX, double mouseY) {
        if (!isMouseOverTopicPanel(mouseX, mouseY)) {
            return null;
        }
        int rowX = getTopicRowX();
        int rowY = getTopicListTop();
        int rowW = getTopicRowWidth();
        int listHeight = getTopicListHeight();
        int visibleRows = Math.max(1, listHeight / TOPIC_ROW_HEIGHT);
        clampTopicScroll(visibleRows);
        if (mouseX < rowX || mouseX >= rowX + rowW || mouseY < rowY || mouseY >= rowY + listHeight) {
            return null;
        }
        int rowIndex = (int) ((mouseY - rowY) / TOPIC_ROW_HEIGHT);
        if (rowIndex < 0 || rowIndex >= visibleRows) {
            return null;
        }
        int entryIndex = topicScrollIndex + rowIndex;
        if (entryIndex < 0 || entryIndex >= TOPIC_ENTRIES.size()) {
            return null;
        }
        return TOPIC_ENTRIES.get(entryIndex);
    }

    private int getFollowAdjustDirection(double mouseX, double mouseY) {
        int followIndex = 0;
        int listHeight = getTopicListHeight();
        int visibleRows = Math.max(1, listHeight / TOPIC_ROW_HEIGHT);
        clampTopicScroll(visibleRows);
        int visibleStart = topicScrollIndex;
        int visibleEnd = topicScrollIndex + visibleRows;
        if (followIndex < visibleStart || followIndex >= visibleEnd) {
            return 0;
        }

        int rowX = getTopicRowX();
        int rowY = getTopicListTop() + (followIndex - visibleStart) * TOPIC_ROW_HEIGHT;
        int rowW = getTopicRowWidth();
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int plusX = rowX + rowW - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        if (mouseY >= controlY && mouseY < controlY + controlSize) {
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return 1;
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return -1;
            }
        }
        return 0;
    }

    private void handleTopicEntry(TopicEntry entry) {
        switch (entry.action) {
            case FOLLOW -> toggleFollow();
            case GUARD -> toggleGuard();
            case PATROL -> togglePatrol();
            case RETURN_HOME -> runReturnHome();
            case SLEEP -> runSleep();
            case AUTO_RETURN_SUNSET -> toggleAutoReturnSunset();
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> toggleAutoReturnSunsetGuardPatrol();
            case IDLE_HOBBIES -> toggleIdleHobbies();
            case VOICED_DIALOGUE -> toggleVoicedDialogue();
            case TELEPORT_SKILLS -> toggleTeleportSkills();
            case TELEPORT_DROP_SWEEP -> toggleTeleportDropSweep();
            case BASES -> openBasesManager();
            case SKILL_FISH -> runSkillCommand("fish", null);
            case SKILL_WOODCUT -> runSkillCommand("woodcut", null);
            case SKILL_WOOL -> runSkillCommand("wool", null);
            case SKILL_HOVEL -> runShelterWithLook("hovel");
            case SKILL_BURROW -> runShelterWithLook("burrow");
            case SKILL_FARM -> runSkillCommand("farm", null);
            case SKILL_MINING -> runSkillCommand("mining", null);
            case SKILL_STRIPMINE -> runSkillCommand("stripmine", null);
            case SKILL_ASCENT -> runSkillCommand("mining", "ascent");
            case SKILL_DESCENT -> runSkillCommand("mining", "descent");
        }
    }

    private boolean isEntryActive(TopicAction action) {
        return switch (action) {
            case FOLLOW -> isFollowActive();
            case GUARD -> isGuardActive();
            case PATROL -> isPatrolActive();
            case AUTO_RETURN_SUNSET -> isAutoReturnAtSunsetActive();
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> isAutoReturnGuardPatrolEligibleActive();
            case IDLE_HOBBIES -> isIdleHobbiesActive();
            case VOICED_DIALOGUE -> isVoicedDialogueActive();
            case TELEPORT_SKILLS -> isTeleportSkillsActive();
            case TELEPORT_DROP_SWEEP -> isTeleportDropSweepActive();
            default -> false;
        };
    }

    private boolean isAutoReturnAtSunsetActive() {
        return this.handler != null && this.handler.isBotAutoReturnAtSunset();
    }

    private boolean isIdleHobbiesActive() {
        return this.handler != null && this.handler.isBotIdleHobbiesEnabled();
    }

    private boolean isAutoReturnGuardPatrolEligibleActive() {
        return this.handler != null && this.handler.isBotAutoReturnGuardPatrolEligible();
    }

    private void runSkillCommand(String skillName, String action) {
        String botTarget = formatBotTarget();
        String args = action != null && !action.isBlank()
                ? action + " " + botTarget
                : botTarget;
        String command = "bot skill " + skillName + " " + args;
        sendChatCommand(command);
    }

    private boolean isMouseOverTopicPanel(double mouseX, double mouseY) {
        int panelX = getTopicPanelX();
        int panelY = getTopicPanelY();
        int panelW = getTopicPanelWidth();
        int panelH = getTopicPanelHeight();
        return mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + panelH;
    }

    private int getTopicPanelX() {
        return this.x + SECTION_WIDTH + BLOCK_GAP;
    }

    private int getTopicPanelY() {
        return this.y + SECTION_HEIGHT + 2;
    }

    private int getTopicPanelWidth() {
        return SECTION_WIDTH;
    }

    private int getTopicPanelHeight() {
        return STATS_AREA_HEIGHT - 4;
    }

    private int getTopicRowX() {
        return getTopicPanelX() + TOPIC_PADDING;
    }

    private int getTopicRowWidth() {
        return getTopicPanelWidth() - TOPIC_PADDING * 2;
    }

    private int getTopicListTop() {
        return getTopicPanelY() + 2 + this.textRenderer.fontHeight + 1;
    }

    private int getTopicListHeight() {
        return getTopicPanelHeight() - (getTopicListTop() - getTopicPanelY());
    }

    private void clampTopicScroll(int visibleRows) {
        int maxScroll = Math.max(0, TOPIC_ENTRIES.size() - visibleRows);
        topicScrollIndex = MathHelper.clamp(topicScrollIndex, 0, maxScroll);
    }

    private boolean isFollowActive() {
        return this.handler != null && this.handler.isBotFollowing();
    }

    private boolean isGuardActive() {
        return this.handler != null && this.handler.isBotGuarding();
    }

    private boolean isPatrolActive() {
        return this.handler != null && this.handler.isBotPatrolling();
    }

    private double getFollowDistance() {
        return this.handler != null ? this.handler.getBotFollowDistance() : 0.0D;
    }

    private String formatBotTarget() {
        if (botAlias.contains(" ")) {
            return "\"" + botAlias + "\"";
        }
        return botAlias;
    }

    private void toggleFollow() {
        String botTarget = formatBotTarget();
        String command = isFollowActive() ? "bot follow stop " + botTarget : "bot follow " + botTarget;
        sendChatCommand(command);
    }

    private void adjustFollowDistance(int direction) {
        double current = getFollowDistance();
        double base = current > 0.0D ? current : FOLLOW_DISTANCE_DEFAULT;
        double next = base + (FOLLOW_DISTANCE_STEP * direction);
        next = MathHelper.clamp(next, FOLLOW_DISTANCE_MIN, FOLLOW_DISTANCE_MAX);
        String botTarget = formatBotTarget();
        String command = "bot follow-distance " + String.format(Locale.ROOT, "%.1f", next) + " " + botTarget;
        sendChatCommand(command);
    }

    private void toggleGuard() {
        String botTarget = formatBotTarget();
        String command = isGuardActive() ? "bot stop " + botTarget : "bot guard " + botTarget;
        sendChatCommand(command);
    }

    private void togglePatrol() {
        String botTarget = formatBotTarget();
        String command = isPatrolActive() ? "bot stop " + botTarget : "bot patrol " + botTarget;
        sendChatCommand(command);
    }

    private void runReturnHome() {
        String botTarget = formatBotTarget();
        String command = "bot return " + botTarget;
        sendChatCommand(command);
    }

    private void runSleep() {
        String botTarget = formatBotTarget();
        String command = "bot sleep " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoReturnSunset() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_sunset toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoReturnSunsetGuardPatrol() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_sunset_guard_patrol toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleIdleHobbies() {
        String botTarget = formatBotTarget();
        String command = "bot idle_hobbies toggle " + botTarget;
        sendChatCommand(command);
    }

    private boolean isVoicedDialogueActive() {
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botAlias);
        return settings != null && settings.isVoicedDialogue();
    }

    private void toggleVoicedDialogue() {
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getOrCreateBotControl(botAlias);
        settings.setVoicedDialogue(!settings.isVoicedDialogue());
        AIPlayer.CONFIG.save();
    }

    private boolean isTeleportSkillsActive() {
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botAlias);
        return settings != null && settings.isTeleportDuringSkills();
    }

    private void toggleTeleportSkills() {
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getOrCreateBotControl(botAlias);
        settings.setTeleportDuringSkills(!settings.isTeleportDuringSkills());
        AIPlayer.CONFIG.save();
    }

    private boolean isTeleportDropSweepActive() {
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(botAlias);
        return settings != null && settings.isTeleportDuringDropSweep();
    }

    private void toggleTeleportDropSweep() {
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getOrCreateBotControl(botAlias);
        settings.setTeleportDuringDropSweep(!settings.isTeleportDuringDropSweep());
        AIPlayer.CONFIG.save();
    }

    private void openBasesManager() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BaseManagerScreen(this));
    }

    /**
     * Closes the screen and sends a shelter command with @look flag.
     * The server will use the player's current look direction to determine placement.
     * For hovel: centered where player looks
     * For burrow: digs in the direction player looks
     */
    private void runShelterWithLook(String shelterType) {
        if (this.client == null) {
            return;
        }
        String botTarget = formatBotTarget();
        // Set the pending shelter type - will be used when player presses go_to_look keybind
        net.shasankp000.AIPlayerClient.setPendingShelter(shelterType, botTarget);
        // Close the screen first so player can see where they're looking
        this.close();
        // Show instruction message
        if (this.client.player != null) {
            this.client.player.sendMessage(
                net.minecraft.text.Text.literal("Look where you want the " + shelterType + " and press your 'Go To Look' keybind (or /bot shelter_look " + shelterType + ")"),
                true
            );
        }
    }

    private void sendChatCommand(String command) {
        if (this.client == null || this.client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        this.client.getNetworkHandler().sendChatCommand(raw);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawEntities(context, mouseX, mouseY);
        if (topicsExpanded) {
            drawTopicsOverlay(context, mouseX, mouseY);
        }
        if (!topicsExpanded || !isMouseOverTopicsOverlay(mouseX, mouseY)) {
            this.drawMouseoverTooltip(context, mouseX, mouseY);
        }
    }

    private void drawEntities(DrawContext context, int mouseX, int mouseY) {
        int leftX1 = this.x + 26;
        int leftY1 = this.y + 8;
        int leftX2 = this.x + 75;
        int leftY2 = this.y + 78;
        int rightX1 = this.x + SECTION_WIDTH + BLOCK_GAP + 26;
        int rightY1 = this.y + 8;
        int rightX2 = this.x + SECTION_WIDTH + BLOCK_GAP + 75;
        int rightY2 = this.y + 78;
        LivingEntity botEntity = findBotEntity();
        if (botEntity != null) {
            InventoryScreen.drawEntity(context, leftX1, leftY1, leftX2, leftY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, botEntity);
        }
        if (this.client != null && this.client.player != null) {
            InventoryScreen.drawEntity(context, rightX1, rightY1, rightX2, rightY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, this.client.player);
        }
    }

    private LivingEntity findBotEntity() {
        if (this.client == null || this.client.world == null) return null;
        String raw = this.title.getString();
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return null;
        String name = raw.substring(0, idx);
        for (AbstractClientPlayerEntity player : this.client.world.getPlayers()) {
            if (player.getGameProfile().name().equals(name)) {
                return player;
            }
        }
        if (this.client.player == null) return null;
        if (fallbackBot == null || !fallbackBot.getGameProfile().name().equals(name)) {
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("bot:" + name).getBytes(StandardCharsets.UTF_8)), name);
            fallbackBot = new OtherClientPlayerEntity(this.client.world, profile);
            fallbackBot.copyPositionAndRotation(this.client.player);
        }
        fallbackBot.tick();
        return fallbackBot;
    }

    private static String extractAlias(String raw) {
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return raw;
        return raw.substring(0, idx);
    }
}
