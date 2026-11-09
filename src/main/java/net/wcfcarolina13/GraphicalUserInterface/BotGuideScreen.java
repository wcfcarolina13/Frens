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
import net.wcfcarolina13.guide.GuideCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Searchable layman-friendly guide with category separators and dedicated detail cards.
 */
public final class BotGuideScreen extends Screen {

    private record GuideRow(String category, GuideCatalog.Topic topic) {
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
    private static final int AI_SETUP_BTN_W = 56;

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
        this(parent, botAlias, "");
    }

    public BotGuideScreen(Screen parent, String botAlias, String topicId) {
        super(Text.literal("Guide"));
        this.parent = parent;
        this.botAlias = botAlias != null ? botAlias : "bot";
        this.selectedTopicId = topicId != null ? topicId : "";
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
        // "AI setup" always sits left of the optional Admin button (its width + a 4 px gap).
        int rightPad = (showAdmin ? 62 : 8) + AI_SETUP_BTN_W + 4;
        int searchW = Math.max(100, this.width - searchX - rightPad);
        int searchY = (HEADER_H - 18) / 2;

        this.searchField = new TextFieldWidget(this.textRenderer, searchX, searchY, searchW, 18, Text.literal("Search"));
        this.searchField.setMaxLength(128);
        // Short placeholder when the field is narrow (the 320-px minimum GUI width leaves ~130 px).
        String placeholder = UiFitPolicy.pickLabel(
                List.of("Search actions, commands, shortcuts...", "Search..."),
                searchW - 8, this.textRenderer::getWidth);
        this.searchField.setPlaceholder(Text.literal(placeholder));
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

        int aiSetupX = (showAdmin ? this.width - 58 : this.width - 8) - 4 - AI_SETUP_BTN_W;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("AI setup"), b -> {
            if (this.client != null) {
                this.client.setScreen(new AiSetupChecklistScreen(this));
            }
        }).dimensions(aiSetupX, searchY, AI_SETUP_BTN_W, 18).build());

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
        List<GuideCatalog.Topic> visibleTopics = filteredTopics();
        ensureSelectedTopic(visibleTopics);
        GuideCatalog.Topic selected = selectedTopic(visibleTopics);

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
        String hint = guideLayout().stacked()
                ? "Click a topic above to open its detail card"
                : "Click a topic on the left to open its detail card";
        hint = UiFitPolicy.elide(hint, this.width - 16, this.textRenderer::getWidth);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint),
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
                GuideCatalog.Topic topic = row.topic();
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
                                 GuideCatalog.Topic selected,
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
        int headW = Math.max(0, panel.w - 16);
        context.drawText(this.textRenderer, UiFitPolicy.elide(category, headW, this.textRenderer::getWidth),
                panel.x + 8, panel.y + 6, 0xFFB8A76A, false);
        context.drawText(this.textRenderer, UiFitPolicy.elide(title, headW, this.textRenderer::getWidth),
                panel.x + 8, panel.y + 18, 0xFFFFE08A, false);

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
            GuideCatalog.Topic selected = selectedTopic(filteredTopics());
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

        List<GuideCatalog.Topic> visibleTopics = filteredTopics();
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

        GuideCatalog.Topic selected = selectedTopic(visibleTopics);
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
            GuideCatalog.Topic selected = selectedTopic(filteredTopics());
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

    private GuideLayoutPolicy.Layout guideLayout() {
        Rect body = bodyRect();
        return GuideLayoutPolicy.layout(body.x, body.y, body.w, body.h, PANEL_GAP);
    }

    private Rect topicsPanelRect() {
        GuideLayoutPolicy.Box b = guideLayout().topics();
        return new Rect(b.x(), b.y(), b.w(), b.h());
    }

    /** The detail card. Both panels come from one layout, so the topics rect is only kept for call-site shape. */
    private Rect detailPanelRect(Rect topicsPanel) {
        GuideLayoutPolicy.Box b = guideLayout().detail();
        return new Rect(b.x(), b.y(), b.w(), b.h());
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

    private void ensureSelectedTopic(List<GuideCatalog.Topic> visibleTopics) {
        if (visibleTopics == null || visibleTopics.isEmpty()) {
            selectedTopicId = "";
            return;
        }
        for (GuideCatalog.Topic topic : visibleTopics) {
            if (topic.id().equals(selectedTopicId)) {
                return;
            }
        }
        selectedTopicId = visibleTopics.get(0).id();
    }

    private GuideCatalog.Topic selectedTopic(List<GuideCatalog.Topic> visibleTopics) {
        if (visibleTopics == null || visibleTopics.isEmpty()) {
            return null;
        }
        for (GuideCatalog.Topic topic : visibleTopics) {
            if (topic.id().equals(selectedTopicId)) {
                return topic;
            }
        }
        return visibleTopics.get(0);
    }

    private List<GuideRow> buildRows(List<GuideCatalog.Topic> topics) {
        if (topics == null || topics.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, List<GuideCatalog.Topic>> grouped = new LinkedHashMap<>();
        for (GuideCatalog.Topic topic : topics) {
            grouped.computeIfAbsent(topic.category(), k -> new ArrayList<>()).add(topic);
        }

        ArrayList<GuideRow> rows = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            rows.add(new GuideRow(entry.getKey(), null));
            for (GuideCatalog.Topic topic : entry.getValue()) {
                rows.add(new GuideRow(entry.getKey(), topic));
            }
        }
        return rows;
    }

    private List<DetailLine> buildDetailLines(GuideCatalog.Topic topic, int maxWidth) {
        ArrayList<DetailLine> out = new ArrayList<>();
        if (topic == null) {
            // Side by side keeps the 1.1.227 wording. Stacked has no list on the left.
            String prompt = guideLayout().stacked()
                    ? "Select a topic from the list to read details."
                    : "Select a topic from the left list to read details.";
            for (OrderedText line : this.textRenderer.wrapLines(Text.literal(prompt), maxWidth)) {
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

    /** Operator or integrated-server host (UI hint only, the server re-checks). */
    private boolean isAdminUser() {
        return net.wcfcarolina13.ui.ClientAdminCheck.isLikelyAdmin();
    }

    private boolean isAdminOnlyGuideTopic(GuideCatalog.Topic topic) {
        return topic != null && topic.adminOnly();
    }

    /**
     * Questing world-mode topics, hidden while the server's questingModeEnabled flag is off
     * (1.1.235). The flag reaches the client through the config sync.
     */
    private static boolean isQuestingOnlyGuideTopic(GuideCatalog.Topic topic) {
        return topic != null && topic.questingOnly();
    }

    private List<GuideCatalog.Topic> filteredTopics() {
        List<GuideCatalog.Topic> base = baseTopics();
        if (net.wcfcarolina13.Frens.CONFIG == null || !net.wcfcarolina13.Frens.CONFIG.isQuestingModeEnabled()) {
            ArrayList<GuideCatalog.Topic> visible = new ArrayList<>();
            for (GuideCatalog.Topic topic : base) {
                if (!isQuestingOnlyGuideTopic(topic)) {
                    visible.add(topic);
                }
            }
            base = visible;
        }
        if (!isAdminUser()) {
            ArrayList<GuideCatalog.Topic> filtered = new ArrayList<>();
            for (GuideCatalog.Topic topic : base) {
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
        ArrayList<GuideCatalog.Topic> out = new ArrayList<>();
        for (GuideCatalog.Topic topic : base) {
            String haystack = (topic.category() + " " + topic.title() + " " + topic.summary() + " "
                    + String.join(" ", topic.details()) + " " + topic.command() + " " + topic.shortcuts() + " " + topic.tags())
                    .toLowerCase(Locale.ROOT);
            if (haystack.contains(q)) {
                out.add(topic);
            }
        }
        return out;
    }

    /** Topics from {@code assets/frens/guide/topics.json}, with this screen's bot and hotkey filled in. */
    private List<GuideCatalog.Topic> baseTopics() {
        String target = botTarget();
        String guideKey = FrensClient.getGuideHotkeyDisplayName();
        List<GuideCatalog.Topic> out = new ArrayList<>();
        for (GuideCatalog.Topic topic : GuideCatalog.topics()) {
            out.add(topic.resolve(target, guideKey));
        }
        return out;
    }
}
