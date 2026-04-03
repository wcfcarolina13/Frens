package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.FrensClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Searchable layman-friendly guide with category separators and dedicated detail cards.
 */
public final class BotGuideScreen extends Screen {

    private record GuideTopic(String id,
                              String category,
                              String title,
                              String summary,
                              List<String> details,
                              String command,
                              String shortcuts,
                              String tags) {
    }

    private record GuideRow(String category, GuideTopic topic) {
        boolean isHeader() {
            return topic == null;
        }
    }

    private record DetailLine(OrderedText text, int color) {
    }

    private record Rect(int x, int y, int w, int h) {
        int right() {
            return x + w;
        }

        int bottom() {
            return y + h;
        }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 30;
    private static final int PANEL_GAP = 8;
    private static final int LIST_SCROLLBAR_W = 11;
    private static final int DETAIL_SCROLLBAR_W = 11;
    private static final int SCROLLBAR_MIN_THUMB = 22;
    private static final int CATEGORY_ROW_H = 16;
    private static final int TOPIC_ROW_H = 18;
    private static final int SCROLL_STEP = 18;

    private final Screen parent;
    private final String botAlias;

    private TextFieldWidget searchField;
    private int listScrollPx = 0;
    private int detailScrollPx = 0;
    private boolean draggingListScroll = false;
    private boolean draggingDetailScroll = false;
    private int listScrollGrabOffset = 0;
    private int detailScrollGrabOffset = 0;
    private String selectedTopicId = "";

    public BotGuideScreen(Screen parent, String botAlias) {
        super(Text.literal("Guide"));
        this.parent = parent;
        this.botAlias = botAlias != null ? botAlias : "bot";
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // Position search field to the right of the "Guide" label, with room for Admin button
        int labelEndX = 12 + this.textRenderer.getWidth("\uD83D\uDCD8 Guide") + 8;
        int searchX = labelEndX + 4;
        boolean showAdmin;
        if (parent instanceof BotPlayerInventoryScreen inv) {
            showAdmin = inv.canShowAdminTab();
        } else {
            // Opened via ] hotkey — always show Admin button.
            // Content within the Admin tab is filtered by permissions.
            showAdmin = true;
        }
        int rightPad = showAdmin ? 62 : 8;
        int searchW = Math.max(100, this.width - searchX - rightPad);
        int searchY = (HEADER_H - 18) / 2;

        this.searchField = new TextFieldWidget(this.textRenderer, searchX, searchY, searchW, 18, Text.literal("Search"));
        this.searchField.setMaxLength(128);
        this.searchField.setPlaceholder(Text.literal("Search actions, commands, shortcuts..."));
        this.searchField.setChangedListener(s -> {
            this.listScrollPx = 0;
            this.detailScrollPx = 0;
            ensureSelectedTopic(filteredTopics());
        });
        this.addDrawableChild(this.searchField);
        this.addSelectableChild(this.searchField);

        if (showAdmin) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Admin"), b -> {
                if (parent instanceof BotPlayerInventoryScreen inv2) {
                    inv2.switchToAdminTab();
                    close();
                } else {
                    // No parent inventory screen — request server to open it remotely.
                    BotPlayerInventoryScreen.pendingAdminTab = true;
                    // Save cursor so the inventory screen can restore it (setScreen centers the cursor).
                    if (this.client != null) {
                        long wh = this.client.getWindow().getHandle();
                        double[] curX = new double[1], curY = new double[1];
                        org.lwjgl.glfw.GLFW.glfwGetCursorPos(wh, curX, curY);
                        BotPlayerInventoryScreen.pendingAdminCursorX = curX[0];
                        BotPlayerInventoryScreen.pendingAdminCursorY = curY[0];
                    }
                    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                            new net.wcfcarolina13.network.GuideOpenInventoryPayload(botAlias));
                    close();
                }
            }).dimensions(this.width - 58, searchY, 50, 18).build());
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx - 40, this.height - 24, 80, 20)
                .build());

        ensureSelectedTopic(filteredTopics());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        MinecraftClient client = this.client;
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xD0101010);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        List<GuideTopic> visibleTopics = filteredTopics();
        ensureSelectedTopic(visibleTopics);
        GuideTopic selected = selectedTopic(visibleTopics);

        Rect topicsPanel = topicsPanelRect();
        Rect detailPanel = detailPanelRect(topicsPanel);
        Rect topicViewport = topicViewport(topicsPanel);
        Rect detailViewport = detailViewport(detailPanel);

        List<GuideRow> rows = buildRows(visibleTopics);
        int listContentH = rowsContentHeight(rows);
        listScrollPx = clampScroll(listScrollPx, topicViewport.h, listContentH);

        int detailTextW = Math.max(40, detailViewport.w - DETAIL_SCROLLBAR_W - 6);
        List<DetailLine> detailLines = buildDetailLines(selected, detailTextW);
        int detailContentH = Math.max(0, detailLines.size() * this.textRenderer.fontHeight + 8);
        detailScrollPx = clampScroll(detailScrollPx, detailViewport.h, detailContentH);

        renderBackground(context, mouseX, mouseY, delta);
        drawHeader(context);
        drawTopicsPanel(context, topicsPanel, topicViewport, rows, mouseX, mouseY);
        drawDetailPanel(context, detailPanel, detailViewport, selected, detailLines, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawHeader(DrawContext context) {
        int headerFill = 0xFF141414;
        int border = 0xFF000000;
        context.fill(0, 0, this.width, HEADER_H, headerFill);
        context.fill(0, HEADER_H - 1, this.width, HEADER_H, border);
        context.drawText(this.textRenderer, "📘 Guide", 12, 7, 0xFFFFE08A, false);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Click a topic on the left to open its detail card"),
                this.width / 2, HEADER_H + 2, 0xFFB0B0B0);
    }

    private void drawTopicsPanel(DrawContext context, Rect panel, Rect viewport, List<GuideRow> rows, int mouseX, int mouseY) {
        int border = 0xFF000000;
        context.fill(panel.x, panel.y, panel.right(), panel.bottom(), 0xFF121212);
        context.fill(panel.x, panel.y, panel.right(), panel.y + 1, border);
        context.fill(panel.x, panel.bottom() - 1, panel.right(), panel.bottom(), border);
        context.fill(panel.x, panel.y, panel.x + 1, panel.bottom(), border);
        context.fill(panel.right() - 1, panel.y, panel.right(), panel.bottom(), border);

        String label = "Topics";
        context.drawText(this.textRenderer, label, panel.x + 6, panel.y + 6, 0xFFE6D7A3, false);

        int rowAreaW = Math.max(60, viewport.w - LIST_SCROLLBAR_W - 2);
        context.enableScissor(viewport.x, viewport.y, viewport.x + rowAreaW, viewport.bottom());

        int y = viewport.y - listScrollPx;
        for (GuideRow row : rows) {
            int rowH = rowHeight(row);
            int rowBottom = y + rowH;
            if (rowBottom <= viewport.y) {
                y += rowH;
                continue;
            }
            if (y >= viewport.bottom()) {
                break;
            }

            if (row.isHeader()) {
                context.fill(viewport.x, y, viewport.x + rowAreaW, rowBottom, 0xFF2A1D0E);
                context.fill(viewport.x + 2, rowBottom - 1, viewport.x + rowAreaW - 2, rowBottom, 0xFF6B522C);
                context.drawText(this.textRenderer, row.category(), viewport.x + 4, y + 3, 0xFFFFD48A, false);
            } else {
                GuideTopic topic = row.topic();
                if (topic == null) {
                    y += rowH;
                    continue;
                }
                boolean selected = topic != null && topic.id().equals(selectedTopicId);
                boolean hover = mouseX >= viewport.x && mouseX < viewport.x + rowAreaW && mouseY >= y && mouseY < rowBottom;
                int fill = selected ? 0xFF3A2C14 : 0xFF1A1A1A;
                if (hover) {
                    fill = selected ? 0xFF4A3720 : 0xFF282828;
                }
                context.fill(viewport.x, y, viewport.x + rowAreaW, rowBottom, fill);
                context.drawText(this.textRenderer, elide(topic.title(), rowAreaW - 8), viewport.x + 4, y + 4, 0xFFE6D7A3, false);
            }
            y += rowH;
        }

        context.disableScissor();

        Rect track = new Rect(viewport.x + rowAreaW + 1, viewport.y, LIST_SCROLLBAR_W, viewport.h);
        Rect thumb = computeThumb(track, viewport.h, rowsContentHeight(rows), listScrollPx);
        drawScrollbar(context, track, thumb, mouseX, mouseY, draggingListScroll);
    }

    private void drawDetailPanel(DrawContext context,
                                 Rect panel,
                                 Rect viewport,
                                 GuideTopic selected,
                                 List<DetailLine> detailLines,
                                 int mouseX,
                                 int mouseY) {
        int border = 0xFF000000;
        context.fill(panel.x, panel.y, panel.right(), panel.bottom(), 0xFF121212);
        context.fill(panel.x, panel.y, panel.right(), panel.y + 1, border);
        context.fill(panel.x, panel.bottom() - 1, panel.right(), panel.bottom(), border);
        context.fill(panel.x, panel.y, panel.x + 1, panel.bottom(), border);
        context.fill(panel.right() - 1, panel.y, panel.right(), panel.bottom(), border);

        String title = selected == null ? "No topic selected" : selected.title();
        String category = selected == null ? "" : selected.category();
        context.drawText(this.textRenderer, category, panel.x + 8, panel.y + 6, 0xFFB8A76A, false);
        context.drawText(this.textRenderer, title, panel.x + 8, panel.y + 18, 0xFFFFE08A, false);

        int detailTextW = Math.max(40, viewport.w - DETAIL_SCROLLBAR_W - 6);
        int lineH = this.textRenderer.fontHeight;

        context.enableScissor(viewport.x, viewport.y, viewport.x + detailTextW, viewport.bottom());
        int y = viewport.y + 2 - detailScrollPx;
        for (DetailLine line : detailLines) {
            if (y + lineH > viewport.y && y < viewport.bottom()) {
                context.drawText(this.textRenderer, line.text(), viewport.x + 2, y, line.color(), false);
            }
            y += lineH;
            if (y >= viewport.bottom()) {
                break;
            }
        }
        context.disableScissor();

        int detailContentH = Math.max(0, detailLines.size() * lineH + 8);
        Rect track = new Rect(viewport.x + detailTextW + 1, viewport.y, DETAIL_SCROLLBAR_W, viewport.h);
        Rect thumb = computeThumb(track, viewport.h, detailContentH, detailScrollPx);
        drawScrollbar(context, track, thumb, mouseX, mouseY, draggingDetailScroll);
    }

    private void drawScrollbar(DrawContext context, Rect track, Rect thumb, int mouseX, int mouseY, boolean dragging) {
        context.fill(track.x, track.y, track.right(), track.bottom(), 0xFF171717);
        context.fill(track.x, track.y, track.right(), track.y + 1, 0xFF2A2A2A);
        context.fill(track.x, track.bottom() - 1, track.right(), track.bottom(), 0xFF2A2A2A);
        context.fill(track.x, track.y, track.x + 1, track.bottom(), 0xFF2A2A2A);
        context.fill(track.right() - 1, track.y, track.right(), track.bottom(), 0xFF2A2A2A);

        if (thumb != null) {
            boolean hover = thumb.contains(mouseX, mouseY);
            int fill = (hover || dragging) ? 0xFFB08C40 : 0xFF7A6240;
            context.fill(thumb.x + 1, thumb.y + 1, thumb.right() - 1, thumb.bottom() - 1, fill);
        }
    }

    private Rect computeThumb(Rect track, int viewportH, int contentH, int scrollPx) {
        if (track == null || contentH <= viewportH || viewportH <= 0) {
            return null;
        }
        int thumbH = Math.max(SCROLLBAR_MIN_THUMB, (track.h * viewportH) / Math.max(1, contentH));
        thumbH = Math.min(track.h, thumbH);
        int maxScroll = Math.max(1, contentH - viewportH);
        int range = Math.max(0, track.h - thumbH);
        int clampedScroll = MathHelper.clamp(scrollPx, 0, maxScroll);
        int thumbY = track.y + (range <= 0 ? 0 : (int) Math.round((double) range * ((double) clampedScroll / (double) maxScroll)));
        return new Rect(track.x, thumbY, track.w, thumbH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Rect topicsPanel = topicsPanelRect();
        Rect detailPanel = detailPanelRect(topicsPanel);
        Rect topicViewport = topicViewport(topicsPanel);
        Rect detailViewport = detailViewport(detailPanel);

        int delta = verticalAmount > 0 ? -SCROLL_STEP : (verticalAmount < 0 ? SCROLL_STEP : 0);
        if (delta == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        if (topicViewport.contains(mouseX, mouseY)) {
            List<GuideRow> rows = buildRows(filteredTopics());
            int contentH = rowsContentHeight(rows);
            listScrollPx = clampScroll(listScrollPx + delta, topicViewport.h, contentH);
            return true;
        }
        if (detailViewport.contains(mouseX, mouseY)) {
            GuideTopic selected = selectedTopic(filteredTopics());
            int detailTextW = Math.max(40, detailViewport.w - DETAIL_SCROLLBAR_W - 6);
            int contentH = Math.max(0, buildDetailLines(selected, detailTextW).size() * this.textRenderer.fontHeight + 8);
            detailScrollPx = clampScroll(detailScrollPx + delta, detailViewport.h, contentH);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        if (super.mouseClicked(click, isInside)) {
            return true;
        }

        double mouseX = click.x();
        double mouseY = click.y();
        Rect topicsPanel = topicsPanelRect();
        Rect detailPanel = detailPanelRect(topicsPanel);
        Rect topicViewport = topicViewport(topicsPanel);
        Rect detailViewport = detailViewport(detailPanel);

        List<GuideTopic> visibleTopics = filteredTopics();
        List<GuideRow> rows = buildRows(visibleTopics);
        int listContentH = rowsContentHeight(rows);

        int rowAreaW = Math.max(60, topicViewport.w - LIST_SCROLLBAR_W - 2);
        Rect listTrack = new Rect(topicViewport.x + rowAreaW + 1, topicViewport.y, LIST_SCROLLBAR_W, topicViewport.h);
        Rect listThumb = computeThumb(listTrack, topicViewport.h, listContentH, listScrollPx);

        if (click.button() == 0 && listTrack.contains(mouseX, mouseY)) {
            if (listThumb != null && listThumb.contains(mouseX, mouseY)) {
                draggingListScroll = true;
                listScrollGrabOffset = (int) mouseY - listThumb.y;
                return true;
            }
            if (listThumb != null) {
                int page = Math.max(TOPIC_ROW_H, topicViewport.h - TOPIC_ROW_H);
                listScrollPx = clampScroll(listScrollPx + (mouseY < listThumb.y ? -page : page), topicViewport.h, listContentH);
                return true;
            }
        }

        if (topicViewport.contains(mouseX, mouseY) && mouseX < topicViewport.x + rowAreaW) {
            int y = topicViewport.y - listScrollPx;
            for (GuideRow row : rows) {
                int rowH = rowHeight(row);
                int rowBottom = y + rowH;
                if (!row.isHeader() && mouseY >= y && mouseY < rowBottom) {
                    selectedTopicId = row.topic().id();
                    detailScrollPx = 0;
                    return true;
                }
                y += rowH;
            }
        }

        GuideTopic selected = selectedTopic(visibleTopics);
        int detailTextW = Math.max(40, detailViewport.w - DETAIL_SCROLLBAR_W - 6);
        int detailContentH = Math.max(0, buildDetailLines(selected, detailTextW).size() * this.textRenderer.fontHeight + 8);
        Rect detailTrack = new Rect(detailViewport.x + detailTextW + 1, detailViewport.y, DETAIL_SCROLLBAR_W, detailViewport.h);
        Rect detailThumb = computeThumb(detailTrack, detailViewport.h, detailContentH, detailScrollPx);

        if (click.button() == 0 && detailTrack.contains(mouseX, mouseY)) {
            if (detailThumb != null && detailThumb.contains(mouseX, mouseY)) {
                draggingDetailScroll = true;
                detailScrollGrabOffset = (int) mouseY - detailThumb.y;
                return true;
            }
            if (detailThumb != null) {
                int page = Math.max(this.textRenderer.fontHeight * 4, detailViewport.h - this.textRenderer.fontHeight * 3);
                detailScrollPx = clampScroll(detailScrollPx + (mouseY < detailThumb.y ? -page : page), detailViewport.h, detailContentH);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click == null || click.button() != 0) {
            return super.mouseDragged(click, deltaX, deltaY);
        }

        Rect topicsPanel = topicsPanelRect();
        Rect detailPanel = detailPanelRect(topicsPanel);
        Rect topicViewport = topicViewport(topicsPanel);
        Rect detailViewport = detailViewport(detailPanel);

        if (draggingListScroll) {
            List<GuideRow> rows = buildRows(filteredTopics());
            int contentH = rowsContentHeight(rows);
            int rowAreaW = Math.max(60, topicViewport.w - LIST_SCROLLBAR_W - 2);
            Rect track = new Rect(topicViewport.x + rowAreaW + 1, topicViewport.y, LIST_SCROLLBAR_W, topicViewport.h);
            Rect thumb = computeThumb(track, topicViewport.h, contentH, listScrollPx);
            if (thumb != null) {
                listScrollPx = scrollFromThumb(click, track, thumb, topicViewport.h, contentH, listScrollGrabOffset);
            }
            return true;
        }

        if (draggingDetailScroll) {
            GuideTopic selected = selectedTopic(filteredTopics());
            int detailTextW = Math.max(40, detailViewport.w - DETAIL_SCROLLBAR_W - 6);
            int contentH = Math.max(0, buildDetailLines(selected, detailTextW).size() * this.textRenderer.fontHeight + 8);
            Rect track = new Rect(detailViewport.x + detailTextW + 1, detailViewport.y, DETAIL_SCROLLBAR_W, detailViewport.h);
            Rect thumb = computeThumb(track, detailViewport.h, contentH, detailScrollPx);
            if (thumb != null) {
                detailScrollPx = scrollFromThumb(click, track, thumb, detailViewport.h, contentH, detailScrollGrabOffset);
            }
            return true;
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }

    private int scrollFromThumb(Click click, Rect track, Rect thumb, int viewportH, int contentH, int grabOffset) {
        if (click == null || track == null || thumb == null || contentH <= viewportH) {
            return 0;
        }
        int minY = track.y;
        int maxY = track.bottom() - thumb.h;
        if (maxY <= minY) {
            return 0;
        }
        int desiredY = (int) click.y() - grabOffset;
        desiredY = MathHelper.clamp(desiredY, minY, maxY);
        double ratio = (double) (desiredY - minY) / (double) (maxY - minY);
        int maxScroll = Math.max(0, contentH - viewportH);
        return MathHelper.clamp((int) Math.round(ratio * maxScroll), 0, maxScroll);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingListScroll) {
            draggingListScroll = false;
            return true;
        }
        if (draggingDetailScroll) {
            draggingDetailScroll = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input != null && input.key() == 256) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    private Rect bodyRect() {
        int x = 12;
        int y = HEADER_H + 14;
        int w = Math.max(200, this.width - 24);
        int h = Math.max(100, this.height - y - FOOTER_H);
        return new Rect(x, y, w, h);
    }

    private Rect topicsPanelRect() {
        Rect body = bodyRect();
        int desired = MathHelper.clamp((body.w * 34) / 100, 220, 320);
        int w = Math.min(desired, Math.max(180, body.w - 200));
        return new Rect(body.x, body.y, w, body.h);
    }

    private Rect detailPanelRect(Rect topicsPanel) {
        Rect body = bodyRect();
        int x = topicsPanel.right() + PANEL_GAP;
        int w = Math.max(180, body.right() - x);
        return new Rect(x, body.y, w, body.h);
    }

    private Rect topicViewport(Rect topicsPanel) {
        return new Rect(topicsPanel.x + 6, topicsPanel.y + 24, topicsPanel.w - 12, topicsPanel.h - 30);
    }

    private Rect detailViewport(Rect detailPanel) {
        return new Rect(detailPanel.x + 8, detailPanel.y + 40, detailPanel.w - 16, detailPanel.h - 48);
    }

    private int clampScroll(int current, int viewportH, int contentH) {
        int max = Math.max(0, contentH - viewportH);
        return MathHelper.clamp(current, 0, max);
    }

    private int rowHeight(GuideRow row) {
        return row != null && row.isHeader() ? CATEGORY_ROW_H : TOPIC_ROW_H;
    }

    private int rowsContentHeight(List<GuideRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int h = 0;
        for (GuideRow row : rows) {
            h += rowHeight(row);
        }
        return h;
    }

    private void ensureSelectedTopic(List<GuideTopic> visibleTopics) {
        if (visibleTopics == null || visibleTopics.isEmpty()) {
            selectedTopicId = "";
            return;
        }
        for (GuideTopic topic : visibleTopics) {
            if (topic.id().equals(selectedTopicId)) {
                return;
            }
        }
        selectedTopicId = visibleTopics.get(0).id();
    }

    private GuideTopic selectedTopic(List<GuideTopic> visibleTopics) {
        if (visibleTopics == null || visibleTopics.isEmpty()) {
            return null;
        }
        for (GuideTopic topic : visibleTopics) {
            if (topic.id().equals(selectedTopicId)) {
                return topic;
            }
        }
        return visibleTopics.get(0);
    }

    private List<GuideRow> buildRows(List<GuideTopic> topics) {
        if (topics == null || topics.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, List<GuideTopic>> grouped = new LinkedHashMap<>();
        for (GuideTopic topic : topics) {
            grouped.computeIfAbsent(topic.category(), k -> new ArrayList<>()).add(topic);
        }

        ArrayList<GuideRow> rows = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            rows.add(new GuideRow(entry.getKey(), null));
            for (GuideTopic topic : entry.getValue()) {
                rows.add(new GuideRow(entry.getKey(), topic));
            }
        }
        return rows;
    }

    private List<DetailLine> buildDetailLines(GuideTopic topic, int maxWidth) {
        ArrayList<DetailLine> out = new ArrayList<>();
        if (topic == null) {
            for (OrderedText line : this.textRenderer.wrapLines(Text.literal("Select a topic from the left list to read details."), maxWidth)) {
                out.add(new DetailLine(line, 0xFFE6D7A3));
            }
            return out;
        }

        appendWrapped(out, "Summary", 0xFFB8A76A, maxWidth);
        appendWrapped(out, topic.summary(), 0xFFE6D7A3, maxWidth);
        appendBlank(out);

        appendWrapped(out, "What This Does", 0xFFB8A76A, maxWidth);
        for (String detail : topic.details()) {
            appendWrapped(out, "- " + detail, 0xFFE6D7A3, maxWidth);
        }
        appendBlank(out);

        appendWrapped(out, "Command", 0xFFB8A76A, maxWidth);
        String cmd = topic.command() == null || topic.command().isBlank() ? "UI-only action (no command needed)." : topic.command();
        appendWrapped(out, cmd, 0xFFFFE08A, maxWidth);
        appendBlank(out);

        appendWrapped(out, "Shortcuts", 0xFFB8A76A, maxWidth);
        String keys = topic.shortcuts() == null || topic.shortcuts().isBlank() ? "None" : topic.shortcuts();
        appendWrapped(out, keys, 0xFFE6D7A3, maxWidth);

        return out;
    }

    private void appendWrapped(List<DetailLine> out, String text, int color, int maxWidth) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (OrderedText line : this.textRenderer.wrapLines(Text.literal(text), maxWidth)) {
            out.add(new DetailLine(line, color));
        }
    }

    private void appendBlank(List<DetailLine> out) {
        out.add(new DetailLine(Text.literal(" ").asOrderedText(), 0xFFE6D7A3));
    }

    private String elide(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String candidate = text.substring(0, mid) + ellipsis;
            if (this.textRenderer.getWidth(candidate) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    private String botTarget() {
        if (botAlias.contains(" ")) {
            return "\"" + botAlias + "\"";
        }
        return botAlias;
    }

    private boolean isAdminUser() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }
        var player = client.player;
        if (player == null) {
            return false;
        }
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("hasPermissionLevel", int.class);
            Object r = m.invoke(player, 2);
            if (r instanceof Boolean b) {
                return b;
            }
        } catch (Throwable ignored) {
            // Fail closed when permission APIs are unavailable.
        }
        return false;
    }

    private boolean isAdminOnlyGuideTopic(GuideTopic topic) {
        if (topic == null || topic.id() == null) {
            return false;
        }
        String id = topic.id().trim().toLowerCase(Locale.ROOT);
        return id.equals("modes_delegate") || id.equals("modes_direct");
    }

    private List<GuideTopic> filteredTopics() {
        List<GuideTopic> base = baseTopics();
        if (!isAdminUser()) {
            ArrayList<GuideTopic> filtered = new ArrayList<>();
            for (GuideTopic topic : base) {
                if (!isAdminOnlyGuideTopic(topic)) {
                    filtered.add(topic);
                }
            }
            base = filtered;
        }
        String q = searchField != null ? searchField.getText().trim().toLowerCase(Locale.ROOT) : "";
        if (q.isBlank()) {
            return base;
        }
        ArrayList<GuideTopic> out = new ArrayList<>();
        for (GuideTopic topic : base) {
            String haystack = (topic.category() + " " + topic.title() + " " + topic.summary() + " "
                    + String.join(" ", topic.details()) + " " + topic.command() + " " + topic.shortcuts() + " " + topic.tags())
                    .toLowerCase(Locale.ROOT);
            if (haystack.contains(q)) {
                out.add(topic);
            }
        }
        return out;
    }

    private List<GuideTopic> baseTopics() {
        String target = botTarget();
        String guideKey = FrensClient.getGuideHotkeyDisplayName();
        return List.of(
                new GuideTopic(
                        "basics_stop",
                        "Basics",
                        "Stop",
                        "Immediately cancels whatever the bot is doing.",
                        List.of(
                                "Use this if the bot behavior is wrong or unsafe.",
                                "Stop clears the current task and lets you issue a new one."
                        ),
                        "bot stop " + target,
                        "Shortcut: \\ (Stop looked-at bot)",
                        "cancel halt panic"
                ),
                new GuideTopic(
                        "basics_resume",
                        "Basics",
                        "Resume",
                        "Resumes a paused pending task when available.",
                        List.of(
                                "If the bot asks whether to resume or stop, Resume continues that saved task.",
                                "If no pending task exists, the command safely does nothing."
                        ),
                        "bot resume " + target,
                        "Shortcut: (optional) bind Resume key in controls",
                        "continue paused task"
                ),
                    new GuideTopic(
                        "basics_switch_bot",
                        "Basics",
                        "Switch Active Bot (Inventory)",
                        "Cycle to another companion from the inventory UI without retyping commands.",
                        List.of(
                            "In Bot Inventory, press [ and ] to switch previous/next companion.",
                            "You can also click < / > in the switch chip on the bot stats row or overlay header.",
                            "Switching reuses /bot open <alias>, so normal ownership and permission rules still apply."
                        ),
                        "bot open <alias> (or /bot open for last targeted)",
                        "In Bot Inventory: [ previous, ] next",
                        "switch inventory open alias multi bot"
                    ),
                    new GuideTopic(
                        "basics_bot_controls",
                        "Basics",
                        "Bot Controls Panel",
                        "Configure spawn behavior and per-bot preferences in one place.",
                        List.of(
                            "Open from Admin tab: Bot Controls >.",
                            "Spawn modes are Training, Questing, and Admin.",
                            "General settings include Global Text Dialogue and Global Voiced Dialogue toggles.",
                            "Also includes gameplay, survival, behavior, and pathfinder toggles.",
                            "Use this as the primary settings hub instead of memorizing many commands.",
                            "See also: Modes topics for progression context."
                        ),
                        "UI-only action (Admin > Bot Controls >)",
                        "Actions overlay: Admin tab > Bot Controls >",
                        "bot controls settings spawn mode admin panel"
                    ),
                new GuideTopic(
                        "move_follow",
                        "Movement",
                        "Follow",
                        "Keeps the bot near you while you travel.",
                        List.of(
                                "Great for cave runs and travel support.",
                                "Use follow distance controls in Actions to tighten or loosen spacing."
                        ),
                        "bot follow " + target,
                        "Shortcut: ` (toggle follow for looked-at bot)",
                        "trail escort companion"
                ),
                new GuideTopic(
                        "settings_follow_teleport",
                        "Settings",
                        "Follow Teleport",
                        "Wolf-style catch-up teleport during follow mode.",
                        List.of(
                                "When enabled, the bot teleports near you if it falls more than 15 blocks behind, gets stuck for 3 seconds, or loses vertical contact (10+ block Y gap).",
                                "This only affects follow mode — it does not enable teleport during skills, sweeps, or other behaviors.",
                                "The bot finds a safe spot behind you, so it won't teleport into lava or off cliffs.",
                                "Toggle in Bot Controls > Behavior > Follow Teleport.",
                                "Admins can control which players see this toggle in Player Settings."
                        ),
                        "",
                        "",
                        "follow teleport wolf catch-up warp tp stuck lost"
                ),
                new GuideTopic(
                        "move_come",
                        "Movement",
                    "Regroup",
                    "Calls the companion back toward you using the safer regroup behavior.",
                        List.of(
                        "Use this when follow is struggling or you want the bot to come back to you deliberately.",
                        "Preferred command: /bot regroup. Legacy alias: /bot come.",
                        "Safe regroup avoids the more aggressive recovery behavior used by the older come wording."
                        ),
                    "bot companion regroup (legacy: bot companion come)",
                        "No keybind by default",
                    "regroup come stuck follow recovery"
                ),
                new GuideTopic(
                        "move_return",
                        "Movement",
                        "Return Home",
                        "Sends the bot back to its saved home/base anchor.",
                        List.of(
                                "Toggle style: pressing again can cancel and return control.",
                                "Pair with Guard/Patrol for base security loops."
                        ),
                        "bot return " + target,
                        "No keybind by default",
                        "home base return"
                ),
                new GuideTopic(
                        "cleanup",
                        "Gathering",
                        "Cleanup (Drop Sweep)",
                        "Collects nearby dropped items quickly.",
                        List.of(
                                "Best after combat, mining, logging, or building.",
                                "Runs a focused pickup sweep to reduce item despawns."
                        ),
                        "bot skill drop_sweep " + target,
                        "Actions menu: Cleanup",
                        "drop sweep pickup"
                ),
                new GuideTopic(
                        "gather_fish",
                        "Gathering",
                        "Fishing",
                        "Fish until sunset or a chosen catch count.",
                        List.of(
                                "Set a custom count from the Actions row +/- controls.",
                                "When count is unset, fishing runs by default behavior."
                        ),
                        "bot skill fish <count> " + target,
                        "Actions menu count controls",
                        "fish food water"
                ),
                new GuideTopic(
                        "gather_woodcut",
                        "Gathering",
                        "Woodcut",
                        "Fells nearby trees. Defaults to 'Until sunset' mode; use +/- to set a specific tree count.",
                        List.of(
                                "At the default (Until sunset), the bot cuts trees until dusk then stops.",
                                "Use +/- to set a minimum tree count instead.",
                                "Use Cleanup after large runs to gather leftovers quickly."
                        ),
                        "bot skill woodcut [count] " + target,
                        "Actions menu count controls",
                        "tree lumber logs"
                ),
                new GuideTopic(
                        "gather_wool",
                        "Gathering",
                        "Wool",
                        "Shears sheep and gathers wool.",
                        List.of(
                                "Use +/- to specify a target amount.",
                                "Works best in open areas with nearby sheep."
                        ),
                        "bot skill wool <count> " + target,
                        "Actions menu count controls",
                        "sheep wool"
                ),
                new GuideTopic(
                        "gather_leaf_litter",
                        "Gathering",
                        "Leaf Litter",
                        "Collects leaf litter from the ground for cheap furnace fuel.",
                        List.of(
                                "Works best in forested biomes where leaf litter is abundant.",
                                "Leaf litter is the cheapest fuel — always preferred over logs or coal.",
                                "Manual command collects up to a full stack; idle hobby collects a few."
                        ),
                        "bot skill leaf_litter " + target,
                        "Actions menu",
                        "leaf litter fuel kindling fire forest"
                ),
                new GuideTopic(
                        "farm",
                        "Farming",
                        "Build Farm",
                        "Builds and plants a small farm with irrigation.",
                        List.of(
                                "Chooses a site, prepares tilled rows, and handles irrigation.",
                                "If a site is unsafe/invalid, it should relocate or fail clearly."
                        ),
                        "bot skill farm " + target,
                        "No keybind by default",
                        "crop irrigation agriculture build"
                ),
                new GuideTopic(
                        "plant",
                        "Farming",
                        "Plant Seeds",
                        "Plants seeds on any empty tilled soil nearby.",
                        List.of(
                                "Scans a 16-block radius for empty farmland and plants available seeds.",
                                "Supports wheat, beetroot, melon, pumpkin, potato, and carrot."
                        ),
                        "bot skill plant " + target,
                        "No keybind by default",
                        "seed plant crop sow farming"
                ),
                new GuideTopic(
                        "harvest",
                        "Farming",
                        "Harvest Crops",
                        "Harvests fully mature crops, replants them, and keeps the field tidy.",
                        List.of(
                                "Scans a 16-block radius for ripe crops on farmland and carefully breaks them.",
                                "Sneaks to avoid trampling tilled soil, replants matching crops, then runs a drop sweep.",
                                "If inventory space gets tight, it offloads into nearby chests and can pull more seeds from nearby chests to finish replanting."
                        ),
                        "bot skill harvest " + target,
                        "No keybind by default",
                        "harvest reap crop gather farming mature"
                ),
                new GuideTopic(
                        "mine_dirt",
                        "Mining",
                        "Collect Dirt",
                        "Collects dirt-like blocks for scaffolding and builds.",
                        List.of(
                                "Useful prep for construction and terrain patching.",
                                "Command supports optional target amount."
                        ),
                        "bot skill collect_dirt <count> " + target,
                        "No keybind by default",
                        "dirt scaffold"
                ),
                new GuideTopic(
                        "mine_strip",
                        "Mining",
                        "Stripmine",
                        "Mines a straight tunnel for ores and stone resources.",
                        List.of(
                                "Use +/- to set desired tunnel length.",
                                "The bot should refuse impossible or unsafe tool scenarios."
                        ),
                        "bot skill stripmine <length> " + target,
                        "Actions menu count controls",
                        "tunnel ore"
                ),
                new GuideTopic(
                        "mine_ascent",
                        "Mining",
                        "Ascent",
                        "Climbs upward by a block count or until surface sky.",
                        List.of(
                                "Use +/- for block count.",
                        "Use the ☀ toggle in Actions to turn Surface mode on/off."
                        ),
                        "bot skill mining ascent <blocks|surface> " + target,
                    "Actions menu: +/- and ☀ mode toggle",
                        "up climb surface"
                ),
                new GuideTopic(
                        "mine_descent",
                        "Mining",
                        "Descent",
                        "Digs downward by a selected block count.",
                        List.of(
                                "Use +/- to set depth target.",
                                "Good for controlled shaft digging with oversight."
                        ),
                        "bot skill mining descent <blocks> " + target,
                        "Actions menu count controls",
                        "down dig shaft"
                ),
                new GuideTopic(
                        "construction_fortify",
                        "Construction",
                        "Fortify Village",
                        "Builds a defensive wall with moat around a village using a convex hull of its structures.",
                        List.of(
                                "The bot computes a convex hull around village structures and builds along the edges.",
                                "Full cross-section: wall + slab top + inner cliff face + spider overhang + 3-wide moat + exterior clearance.",
                                "Moat is 3 blocks deep with cobblestone floor. Gatehouse has a walkable bridge across the moat.",
                                "Towers at each hull vertex, walls along edges, and a gatehouse on the longest edge.",
                                "Give the bot stone bricks, cobblestone, or slabs before starting.",
                                "Village structures are detected and excluded — walls route around buildings automatically.",
                                "dry_run — preview layout with particles (orange=towers, blue=walls, gold=gate, dark blue=moat, purple=overhang, red=clear).",
                                "status <name> — per-edge completion stats + particles highlighting missing blocks in red.",
                                "resume / patch / drift / expand / merge / list / name — manage saved walls and schema updates.",
                                "Tip: Use the Base Manager screen for wall status, ownership, and nearby village mapping."
                        ),
                        "bot fortify " + target,
                        "Actions > Construction > Fortify Village",
                        "fortify village wall defense perimeter tower gatehouse resume patch status merge hull particles"
                ),
                new GuideTopic(
                        "construction_map_village",
                        "Construction",
                        "Map Village",
                        "Saves the nearby settlement as a shared remembered no-go zone without building a wall.",
                        List.of(
                                "Open the Base Manager and use the Villages section to map the local settlement.",
                                "The saved village reuses the fortification convex hull and stores it by name.",
                                "Mapped villages are global shared data for the world, not personal claims.",
                                "Bots avoid mapped villages for mining, woodcutting, dirt collection, flower/mushroom/grass gathering, and passive-mob hunting.",
                                "Village rows appear in the same manager list as bases and walls, but they are not valid home or go-to targets."
                        ),
                        "UI-only flow",
                        "Actions > Bases > Map Village",
                        "village mapping perimeter convex hull no-go zone bases manager"
                ),
                new GuideTopic(
                        "modes_quest",
                        "Modes",
                        "Quest Mode",
                        "Layman progression path: recruit first, then unlock more capabilities.",
                        List.of(
                                "Designed for survival progression and narrative onboarding.",
                        "Dialogue topics help explain what is unlocked next.",
                        "Backlink: Bot Controls panel includes Spawn Mode and related settings."
                        ),
                        "UI-driven mode",
                        "Guide + Dialogue topics",
                        "recruit unlock quest"
                ),
                    new GuideTopic(
                        "modes_delegate",
                        "Modes",
                        "Delegate Mode Setup",
                        "Admins can permit specific non-operator players to choose world mode.",
                        List.of(
                            "Use mode_access allow/revoke to manage delegated players.",
                            "Operators can always choose mode; delegates are world-specific.",
                            "Clear removes all delegates for the current world."
                        ),
                        "bot recruit mode_access allow <player> | revoke <player> | clear",
                        "No keybind by default",
                        "delegate guest permission world mode setup"
                    ),
                new GuideTopic(
                        "modes_direct",
                        "Modes",
                        "Direct Mode",
                        "Spawn/use bots with broad capabilities immediately.",
                        List.of(
                                "Best for testing, admin workflows, and sandbox play.",
                        "No recruitment progression needed.",
                        "Backlink: use Bot Controls to switch Spawn Mode and tune behavior."
                        ),
                        "bot spawn <alias>",
                        "No keybind by default",
                        "direct admin"
                ),
                    new GuideTopic(
                        "admin_learning_mode",
                        "Admin",
                        "Learning Mode (Admin)",
                        "Operator-only demonstration capture used to record player examples for later bot-control tuning.",
                        List.of(
                            "Admin entries like Learning Status / Start / Stop control a recording session, not normal companion progression.",
                            "Captured traces can include movement, camera, interactions, and local context for analysis.",
                            "Use this for testing and tuning; see the Roadmap topic for the larger ML / LLM direction."
                        ),
                        "bot learn status | arm | start | stop | list | report",
                        "No keybind by default",
                        "learning admin ml llm demo trace tuning"
                    ),
                new GuideTopic(
                    "roadmap_overview",
                    "Roadmap",
                    "Roadmap (High-Level)",
                    "High-level summary of what already exists, what is being polished now, and what optional AI work is planned next.",
                    List.of(
                        "Available now: per-bot controls, recruitment progression, admin permissions, optional LLM toggles, and Learning Mode v1 demonstration capture.",
                        "Active polish: fortify tower reliability, cavity reporting, guide clarity, keybind/help cleanup, and learning trace playtesting.",
                        "Optional AI work: better LLM provider UX, memory/tool routing polish, and turning learning captures into safer bot-control improvements.",
                        "Voice expansion is still on the list, but current voiced dialogue remains an optional add-on rather than a core requirement."
                    ),
                    "UI-only reference",
                    "Guide topic only",
                    "roadmap voices text dialogue priorities future"
                ),
                new GuideTopic(
                        "items_wizard_tome",
                        "Items",
                        "Wizard's Tome",
                        "A rare magical item that unlocks companion spells and abilities.",
                        List.of(
                                "Crafted via ritual: sneak-right-click an Enchanting Table in The End.",
                                "Requires a fully powered table (15 bookshelves).",
                                "Hold an Eye of Ender and have in inventory: 1 Book, 1 Diamond, 3 Redstone, 3 Lapis Lazuli.",
                                "Once crafted, open it to access the companion spells menu.",
                                "Can also be obtained via /give @p frens:wizard_tome in creative."
                        ),
                        "/give @p frens:wizard_tome (creative)",
                        "Open Spells keybind (when holding tome)",
                        "wizard tome spellbook magic enchant craft ritual end"
                ),
                new GuideTopic(
                        "shortcuts",
                        "Shortcuts",
                        "Command + Keybind Cheatsheet",
                        "Quick reference for the most-used controls.",
                        List.of(
                            "Guide -> " + guideKey + " -> open in-game companion guide",
                                "Follow toggle -> ` -> bot follow toggle <bot>",
                                "Go to look / context action -> - -> go_to_look, recruitment contact, or spells (depends on context)",
                            "Switch bot in inventory -> [ / ] -> previous / next companion",
                                "Sleep -> (hold \\ then 5) -> bot sleep <bot>",
                                "Stop -> \\ -> bot stop <bot>",
                                "Leash / tether helper -> ' -> leash action when available",
                                "Resume -> (bind key.frens.resume) -> bot resume <bot>",
                                "Open Spells -> (bind key.frens.open_spells) -> opens spells menu when unlocked",
                                "Recruit Contact -> (optional bind key.frens.recruit_contact) -> opens recruitment contact flow",
                                "Tip: hold Stop (\\) for the 1-0 hotkey overlay menu."
                        ),
                        "bot follow toggle <bot> | bot go_to_look | bot stop <bot> | bot resume <bot>",
                        "Minecraft Controls: Follow, Go To Look, Open Guide, Stop, Leash, Resume, Open Spells, Recruit Contact",
                        "shortcut hotkey keybind follow stop goto go to look"
                ),
                new GuideTopic(
                        "items_nav_artifacts",
                        "Items",
                        "Navigation Artifacts",
                        "Give your companion a Compass, Map, or Eye of Ender to unlock autonomous navigation.",
                        List.of(
                                "Compass / Map (basic): bot can navigate to its nearest or preferred base, same dimension only.",
                                "Eye of Ender (enhanced): navigate to any named base, cross-dimension, instant teleport. Either you or the bot can hold it.",
                                "Use /bot open <alias> to give items to your companion via its inventory screen.",
                                "Navigation artifacts are reusable — they are not consumed."
                        ),
                        "/bot open <alias>",
                        "Give item via Bot Inventory screen",
                        "compass map eye ender navigation artifact tier"
                ),
                new GuideTopic(
                        "spells_remote_guidance",
                        "Spells",
                        "Remote Guidance",
                        "Uses paired ender pearls to guide your companion across any distance.",
                        List.of(
                                "Both you and your companion must hold at least one Ender Pearl.",
                                "Choose destination: guide to your location or to a known base.",
                                "Both pearls are consumed when travel begins.",
                                "Travel always uses fast travel \u2014 the bot disappears briefly and reappears at your location.",
                                "Open the Spells tab to cast this spell."
                        ),
                        "UI-only action (Spells tab)",
                        "Shortcut: bind key.frens.open_spells",
                        "spell guidance ender pearl paired navigation remote"
                ),
                new GuideTopic(
                        "spells_chorus_recall",
                        "Spells",
                        "Chorus Recall",
                        "Consumes paired ender pearls and chorus fruit for an instant teleport.",
                        List.of(
                                "Both you and your companion must hold one Ender Pearl AND one Chorus Fruit.",
                                "Choose direction: teleport bot to you, or you to bot.",
                                "Works across dimensions. Always instant — no delay.",
                                "All four items are consumed (one pearl + one chorus from each)."
                        ),
                        "UI-only action (Spells tab)",
                        "Shortcut: bind key.frens.open_spells",
                        "spell recall chorus fruit ender pearl teleport instant paired"
                ),
                new GuideTopic(
                        "settings_nav_modes",
                        "Settings",
                        "Fast Travel & Navigation Tiers",
                        "Companion fast travel speed depends on held artifacts.",
                        List.of(
                                "All navigation uses fast travel \u2014 the bot disappears and reappears at the destination.",
                                "Tier 1 (Map on bot): fast travel to bases at 2x delay (~2s/chunk).",
                                "Tier 1 (Map + Compass on bot): fast travel to bases AND player at 2x delay.",
                                "Tier 2 (Eye of Ender, either): standard speed (~1s/chunk) to bases and player.",
                                "Tier 2 (Ender Pearls, both hold): same as Eye of Ender (pearls NOT consumed for storage/nav).",
                                "Full Access (Wizard's Tome or Enchanting Table): standard speed everywhere.",
                                "Cross-dimension travel adds 30 seconds. Minimum 5s, maximum 5 minutes."
                        ),
                        "/bot config <alias> nav_mode fast_travel",
                        "Give bot a Map and Compass for basic navigation; Eye of Ender for faster travel.",
                        "navigation mode fast travel tier map compass eye ender artifact speed"
                ),
                new GuideTopic(
                        "items_spell_ingredients",
                        "Items",
                        "Spell Ingredients",
                        "Quick reference for all spell-related items and what they unlock.",
                        List.of(
                                "Wizard's Tome: full access to all companion spells anywhere.",
                                "Enchanting Table (nearby): full access to all spells.",
                                "Goat Horn: regroup only (player holds).",
                                "Eye of Ender: summon only with 60s cooldown (player holds); enhanced navigation (either holds).",
                                "Ender Pearl (paired): Remote Guidance spell \u2014 both must hold, consumed.",
                                "Ender Pearl + Chorus Fruit (paired): Chorus Recall \u2014 instant teleport, consumed."
                        ),
                        "UI-only action (Spells tab)",
                        "Hold item or be near Enchanting Table to unlock spells",
                        "spell ingredient wizard tome eye ender pearl chorus goat horn enchanting"
                ),
                new GuideTopic(
                        "quick_store",
                        "Core Actions",
                        "Quick Store & Quick Fetch",
                        "Point at a nearby chest and click to deposit or take items.",
                        List.of(
                                "Quick Store: Actions \u2192 Core Actions \u2192 Quick Store. Bot walks to the chest and deposits its inventory.",
                                "Quick Fetch: Actions \u2192 Core Actions \u2192 Quick Fetch. Bot walks to the chest and takes items into its inventory.",
                                "Point at any container (chest, barrel, shulker box) and left-click to confirm.",
                                "The bot physically walks to the chest before interacting \u2014 it must be within 32 blocks.",
                                "If the chest fills up (store) or the bot\u2019s inventory fills up (fetch), remaining items stay put.",
                                "The bot does NOT remember chests used this way. Only automated-task chests appear in Storage.",
                                "Right-click to cancel targeting mode.",
                                "The bot can be interrupted with /bot stop. Items are never dropped on the ground."
                        ),
                        "UI-only action (Actions \u2192 Core Actions)",
                        "Works with any container block within 6 blocks of your crosshair",
                        "quick store fetch deposit withdraw chest point click target container"
                ),
                new GuideTopic(
                        "storage_chests",
                        "Utilities",
                        "Storage",
                        "View and manage supply chests your companions have placed in the world.",
                        List.of(
                                "Open via Actions \u2192 Utilities \u2192 Storage.",
                                "Each chest shows coordinates, status, and a snapshot of its contents.",
                                "Go: fast-travel the bot to the chest location.",
                                "Collect: bot fast-travels to chest, withdraws items, optionally returns (to player, home, or stays).",
                                "Dismiss: removes the chest from the registry (does not break the block).",
                                "Fast travel speed depends on artifacts: Map/Compass = 2x delay, Eye of Ender or higher = standard speed.",
                                "Hover a chest row to see a tooltip of its last-known contents.",
                                "Note: Only chests the bot places or uses in automated tasks appear here. Chests targeted via Quick Store/Fetch are not tracked."
                        ),
                        "UI-only action (Actions \u2192 Utilities \u2192 Storage)",
                        "Switch between bots with the dropdown in the header",
                        "storage chest supply collect go dismiss fast travel artifacts"
                ),
                new GuideTopic(
                        "zones_protected",
                        "Utilities",
                        "Protected Zones",
                        "Mark areas where bots cannot break blocks.",
                        List.of(
                                "Admins can create protected zones using the Zone Wand.",
                                "Get the wand: /bot zone wand or 'New Zone' in the Base Manager.",
                                "Right-click a block to set Corner 1, right-click again for Corner 2.",
                                "Cyan particles appear showing the zone boundary.",
                                "Press [=] to confirm and name the zone.",
                                "Bots will not break any blocks inside protected zones.",
                                "Any player can view zone boundaries via Base Manager > Show.",
                                "Admins can rename or delete zones in the Base Manager.",
                                "Zones persist across server restarts.",
                                "To re-do a selection, right-click again to restart from Corner 1."
                        ),
                        "/bot zone wand, /bot zone list, /bot zone protect <radius> [label]",
                        "Confirm zone: = (Equals key)",
                        "zone protect wand area region boundary admin"
                )
        );
    }
}
