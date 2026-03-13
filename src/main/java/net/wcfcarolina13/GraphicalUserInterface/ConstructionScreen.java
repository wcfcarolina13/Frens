package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact construction menu modeled after the base manager panel layout.
 * Keeps headers readable in any environment by drawing everything inside a fixed panel.
 */
public class ConstructionScreen extends Screen {

    private record ConstructionEntry(String category, String section, String command, String label,
                                     String description, String structureType) {}

    private record SectionRenderInfo(String label, int relY, int color, boolean major) {}

    private record ControlSlot(ButtonWidget widget, ConstructionEntry entry, int col, int relY) {}

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

    private static final Map<String, Integer> CATEGORY_COLORS = new LinkedHashMap<>();
    static {
        CATEGORY_COLORS.put("SHELTER", 0xFFA0D0A0);
        CATEGORY_COLORS.put("DEFENSIVE", 0xFFC0A060);
        CATEGORY_COLORS.put("STRONGHOLD", 0xFFFFE08A);
        CATEGORY_COLORS.put("UTILITY", 0xFF9FB6CD);
    }

    private static final List<ConstructionEntry> CONSTRUCTION_OPTIONS = List.of(
            new ConstructionEntry("SHELTER", "Quick Shelters", "shelter hovel", "Hovel", "9x5x9 emergency shelter", "hovel"),
            new ConstructionEntry("SHELTER", "Quick Shelters", "shelter burrow", "Burrow", "Underground 5x8x5 chamber", "burrow"),
            new ConstructionEntry("SHELTER", "Planned Builds", "build small_shelter", "Small Shelter", "5x5 cobblestone shelter", "small_shelter"),
            new ConstructionEntry("SHELTER", "Planned Builds", "build small_hut", "Small Hut", "5x5 wooden hut", "small_hut"),
            new ConstructionEntry("DEFENSIVE", "Defensive Modules", "build watchtower", "Watchtower", "4x4x8 observation tower", "watchtower"),
            new ConstructionEntry("DEFENSIVE", "Defensive Modules", "build defensive_wall_section", "Wall Section", "5-block crenellated wall", "defensive_wall_section"),
            new ConstructionEntry("DEFENSIVE", "Defensive Modules", "build defensive_wall_corner", "Wall Corner", "L-shaped corner piece", "defensive_wall_corner"),
            new ConstructionEntry("DEFENSIVE", "Defensive Modules", "build defensive_gatehouse", "Gatehouse", "Archway with pillars", "defensive_gatehouse"),
            new ConstructionEntry("STRONGHOLD", "1) Build & Resume", "fortify", "Fortify Village", "Build defensive wall around nearby village", null),
            new ConstructionEntry("STRONGHOLD", "1) Build & Resume", "fortify resume", "Resume Wall", "Continue nearest incomplete saved wall", null),
            new ConstructionEntry("STRONGHOLD", "2) Maintain", "fortify patch", "Fortify Patch", "Scan and repair an existing wall", null),
            new ConstructionEntry("STRONGHOLD", "2) Maintain", "fortify moat", "Fortify Moat", "Dig moat around saved wall schema", null),
            new ConstructionEntry("STRONGHOLD", "2) Maintain", "fortify status", "Fortify Status", "View wall completion stats", null),
            new ConstructionEntry("STRONGHOLD", "2) Maintain", "fortify list", "Fortify List", "List all saved walls", null),
            new ConstructionEntry("STRONGHOLD", "3) Evolve Schema", "fortify drift", "Drift Check", "Compare saved wall schema against current village footprint", null),
            new ConstructionEntry("STRONGHOLD", "3) Evolve Schema", "fortify expand", "Expand Wall", "Merge current village footprint into nearest wall schema", null),
            new ConstructionEntry("STRONGHOLD", "3) Evolve Schema", "ui.open_bases", "Wall Manager", "Open wall picker for named resume/patch/moat/drift/expand actions", null),
            new ConstructionEntry("UTILITY", "Infrastructure", "build bridge", "Bridge", "9-block bridge with railings", "bridge"),
            new ConstructionEntry("UTILITY", "Infrastructure", "build test_platform", "Platform", "3x3 test platform", "test_platform")
    );

    private static final int PANEL_MARGIN = 8;
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 412;
    private static final int PANEL_PAD = 8;
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 56;
    private static final int SCROLLBAR_W = 6;
    private static final int THUMB_MIN_H = 16;
    private static final int SCROLL_STEP = 14;

    private static final int COLUMNS = 2;
    private static final int BUTTON_WIDTH = 118;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int SECTION_HEADER_H = 18;
    private static final int SUBSECTION_HEADER_H = 14;
    private static final int SECTION_GAP = 6;
    private static final int CATEGORY_GAP = 4;

    private static final int COL_BG = 0xB0101010;
    private static final int COL_PANEL = 0xF0101010;
    private static final int COL_BORDER = 0xFF2A2A2A;
    private static final int COL_HEADER = 0xFF1A1A1A;
    private static final int COL_CONTENT = 0xEE141414;
    private static final int COL_FOOTER = 0xFF141414;
    private static final int COL_TRACK = 0xFF171717;
    private static final int COL_THUMB = 0xFF7A6240;
    private static final int COL_THUMB_HL = 0xFFB08C40;

    private final Screen parent;
    private final String botTarget;

    private final List<SectionRenderInfo> sectionRenderInfos = new ArrayList<>();
    private final List<ControlSlot> controlSlots = new ArrayList<>();
    private final Map<ButtonWidget, ConstructionEntry> buttonEntries = new LinkedHashMap<>();

    private ButtonWidget backButton;
    private ButtonWidget basesButton;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentHeight;

    private double contentScroll;
    private boolean draggingScroll;
    private int scrollGrabOffset;

    public ConstructionScreen(Screen parent, String botTarget) {
        super(Text.literal("Construction"));
        this.parent = parent;
        this.botTarget = botTarget;
    }

    @Override
    protected void init() {
        this.clearChildren();
        this.sectionRenderInfos.clear();
        this.controlSlots.clear();
        this.buttonEntries.clear();
        this.draggingScroll = false;
        this.scrollGrabOffset = 0;

        applyDefaultPanel();
        buildContentControls();
        buildFooterControls();
        this.contentScroll = MathHelper.clamp(this.contentScroll, 0.0D, maxScroll());
    }

    private void applyDefaultPanel() {
        this.panelW = Math.min(PANEL_W, Math.max(PANEL_W, this.width - PANEL_MARGIN * 2));
        this.panelH = Math.min(PANEL_H, Math.max(PANEL_H, this.height - PANEL_MARGIN * 2));
        this.panelW = Math.min(this.panelW, this.width - PANEL_MARGIN * 2);
        this.panelH = Math.min(this.panelH, this.height - PANEL_MARGIN * 2);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = Math.max(PANEL_MARGIN, (this.height - this.panelH) / 2);
    }

    private void buildContentControls() {
        Map<String, Map<String, List<ConstructionEntry>>> grouped = new LinkedHashMap<>();
        for (ConstructionEntry entry : CONSTRUCTION_OPTIONS) {
            grouped
                    .computeIfAbsent(entry.category(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(entry.section(), key -> new ArrayList<>())
                    .add(entry);
        }

        int y = 0;
        for (Map.Entry<String, Map<String, List<ConstructionEntry>>> categoryEntry : grouped.entrySet()) {
            String category = categoryEntry.getKey();
            int categoryColor = CATEGORY_COLORS.getOrDefault(category, 0xFFFFFFFF);
            sectionRenderInfos.add(new SectionRenderInfo("── " + category + " ──", y, categoryColor, true));
            y += SECTION_HEADER_H;

            for (Map.Entry<String, List<ConstructionEntry>> sectionEntry : categoryEntry.getValue().entrySet()) {
                sectionRenderInfos.add(new SectionRenderInfo("• " + sectionEntry.getKey(), y, 0xFFB8A76A, false));
                y += SUBSECTION_HEADER_H;

                List<ConstructionEntry> entries = sectionEntry.getValue();
                int numRows = (entries.size() + COLUMNS - 1) / COLUMNS;
                for (int i = 0; i < entries.size(); i++) {
                    ConstructionEntry entry = entries.get(i);
                    ButtonWidget button = ButtonWidget.builder(Text.literal(entry.label()), btn -> buildConstruction(entry))
                            .dimensions(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
                            .build();
                    this.addDrawableChild(button);
                    buttonEntries.put(button, entry);
                    controlSlots.add(new ControlSlot(button, entry, i % COLUMNS, y + (i / COLUMNS) * (BUTTON_HEIGHT + GAP)));
                }

                y += numRows * (BUTTON_HEIGHT + GAP);
                y += SECTION_GAP;
            }

            y += CATEGORY_GAP;
        }

        this.contentHeight = y + 4;
    }

    private void buildFooterControls() {
        this.basesButton = ButtonWidget.builder(Text.literal("Bases"), btn -> openBasesManager())
                .dimensions(0, 0, 88, 20)
                .build();
        this.backButton = ButtonWidget.builder(Text.literal("Back"), btn -> this.close())
                .dimensions(0, 0, 88, 20)
                .build();
        this.addDrawableChild(this.basesButton);
        this.addDrawableChild(this.backButton);
    }

    private Rect contentRect() {
        int x = panelX + PANEL_PAD;
        int y = panelY + HEADER_H + 4;
        int w = Math.max(40, panelW - (PANEL_PAD * 2) - SCROLLBAR_W - 2);
        int h = Math.max(44, panelH - HEADER_H - FOOTER_H - 8);
        return new Rect(x, y, w, h);
    }

    private Rect footerRect() {
        return new Rect(panelX + 1, panelY + panelH - FOOTER_H, panelW - 2, FOOTER_H - 1);
    }

    private int maxScroll() {
        Rect content = contentRect();
        return Math.max(0, contentHeight - content.h);
    }

    private void layoutContentControls() {
        Rect content = contentRect();
        int gridWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * GAP;
        int leftX = content.x + Math.max(0, (content.w - gridWidth) / 2);
        int maxScroll = maxScroll();
        this.contentScroll = MathHelper.clamp(this.contentScroll, 0.0D, maxScroll);

        for (ControlSlot slot : controlSlots) {
            ButtonWidget button = slot.widget();
            int x = leftX + slot.col() * (BUTTON_WIDTH + GAP);
            int y = content.y + slot.relY() - (int) this.contentScroll;
            button.setX(x);
            button.setY(y);
            boolean visible = y + button.getHeight() > content.y && y < content.bottom();
            button.visible = visible;
            button.active = visible;
        }
    }

    private void layoutFooterControls() {
        Rect footer = footerRect();
        int buttonY = footer.bottom() - BUTTON_HEIGHT - 6;

        basesButton.setX(footer.x + 8);
        basesButton.setY(buttonY);
        basesButton.visible = true;
        basesButton.active = true;

        backButton.setX(footer.right() - 8 - backButton.getWidth());
        backButton.setY(buttonY);
        backButton.visible = true;
        backButton.active = true;
    }

    private ConstructionEntry getHoveredEntry(int mouseX, int mouseY) {
        for (Map.Entry<ButtonWidget, ConstructionEntry> mapping : buttonEntries.entrySet()) {
            ButtonWidget button = mapping.getKey();
            if (button.visible && button.isMouseOver(mouseX, mouseY)) {
                return mapping.getValue();
            }
        }
        return null;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, COL_BG);

        layoutContentControls();
        layoutFooterControls();

        Rect content = contentRect();
        Rect footer = footerRect();

        context.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COL_BORDER);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);

        context.fill(panelX, panelY, panelX + panelW, panelY + HEADER_H, COL_HEADER);
        context.fill(content.x, content.y, content.right(), content.bottom(), COL_CONTENT);
        context.fill(footer.x, footer.y, footer.right(), footer.bottom(), COL_FOOTER);

        String titleText = this.title != null ? this.title.getString() : "Construction";
        int titleWidth = this.textRenderer.getWidth(titleText);
        int titleX = panelX + Math.max(PANEL_PAD, (panelW - titleWidth) / 2);
        int titleY = panelY + Math.max(1, (HEADER_H - this.textRenderer.fontHeight) / 2);
        context.drawTextWithShadow(this.textRenderer, titleText, titleX, titleY, 0xFFFFE08A);

        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            String hint = "Scroll for more";
            int hintWidth = this.textRenderer.getWidth(hint);
            context.drawText(this.textRenderer, hint, panelX + panelW - PANEL_PAD - hintWidth, titleY, 0xFF8E8E8E, false);
        }

        context.enableScissor(content.x, content.y, content.right(), content.bottom());
        for (SectionRenderInfo info : sectionRenderInfos) {
            int y = content.y + info.relY() - (int) this.contentScroll;
            int rowH = info.major() ? SECTION_HEADER_H : SUBSECTION_HEADER_H;
            if (y + rowH < content.y || y > content.bottom()) {
                continue;
            }
            int stripFill = info.major() ? 0x66303030 : 0x44202020;
            context.fill(content.x + 2, y + 1, content.right() - 2, y + rowH - 2, stripFill);
            if (info.major()) {
                int labelWidth = this.textRenderer.getWidth(info.label());
                int labelX = content.x + Math.max(4, (content.w - labelWidth) / 2);
                context.drawTextWithShadow(this.textRenderer, info.label(), labelX, y + 4, info.color());
            } else {
                context.drawText(this.textRenderer, info.label(), content.x + 6, y + 3, info.color(), false);
            }
        }
        super.render(context, mouseX, mouseY, delta);
        context.disableScissor();

        if (maxScroll > 0) {
            int trackX = content.right() + 4;
            int trackY = content.y;
            int trackH = content.h;
            context.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, COL_TRACK);
            int[] thumb = computeThumb(trackY, trackH, maxScroll);
            if (thumb != null) {
                boolean hover = mouseX >= trackX && mouseX < trackX + SCROLLBAR_W
                        && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
                int color = (hover || draggingScroll) ? COL_THUMB_HL : COL_THUMB;
                context.fill(trackX + 1, thumb[0], trackX + SCROLLBAR_W - 1, thumb[0] + thumb[1], color);
            }
        }

        basesButton.render(context, mouseX, mouseY, delta);
        backButton.render(context, mouseX, mouseY, delta);

        renderFooterHelp(context, footer, getHoveredEntry(mouseX, mouseY));
    }

    private void renderFooterHelp(DrawContext context, Rect footer, ConstructionEntry hoveredEntry) {
        String help = hoveredEntry != null
                ? hoveredEntry.label() + " — " + hoveredEntry.description()
                : "Select a build to close back to gameplay and place it in the world. Fortify tools may run immediately.";
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(help), Math.max(60, footer.w - 16));
        int y = footer.y + 5;
        int maxLines = Math.min(2, lines.size());
        for (int i = 0; i < maxLines; i++) {
            context.drawText(this.textRenderer, lines.get(i), footer.x + 8, y + i * (this.textRenderer.fontHeight + 1), 0xFFB0B0B0, false);
        }
    }

    private int[] computeThumb(int trackTop, int trackHeight, int maxScroll) {
        Rect content = contentRect();
        if (contentHeight <= content.h || trackHeight <= 0) {
            return null;
        }

        int thumbHeight = Math.max(THUMB_MIN_H, trackHeight * content.h / Math.max(1, contentHeight));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int range = Math.max(0, trackHeight - thumbHeight);
        int clamped = MathHelper.clamp((int) contentScroll, 0, maxScroll);
        int thumbY = trackTop + (range <= 0 ? 0 : Math.round((float) range * clamped / maxScroll));
        return new int[]{thumbY, thumbHeight};
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Rect content = contentRect();
        int maxScroll = maxScroll();
        if ((content.contains(mouseX, mouseY) || isOnScrollTrack(mouseX, mouseY)) && maxScroll > 0) {
            contentScroll = MathHelper.clamp(contentScroll - verticalAmount * SCROLL_STEP, 0.0D, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        layoutContentControls();
        layoutFooterControls();

        double mouseX = click.x();
        double mouseY = click.y();
        Rect content = contentRect();
        int maxScroll = maxScroll();

        if (maxScroll > 0) {
            int trackX = content.right() + 4;
            int trackY = content.y;
            int trackH = content.h;
            if (mouseX >= trackX && mouseX < trackX + SCROLLBAR_W && mouseY >= trackY && mouseY < trackY + trackH) {
                int[] thumb = computeThumb(trackY, trackH, maxScroll);
                if (thumb != null && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1]) {
                    draggingScroll = true;
                    scrollGrabOffset = (int) mouseY - thumb[0];
                    return true;
                }
                if (thumb != null) {
                    float frac = (float) (mouseY - trackY) / (float) trackH;
                    contentScroll = MathHelper.clamp(frac * maxScroll, 0.0D, maxScroll);
                    draggingScroll = true;
                    scrollGrabOffset = thumb[1] / 2;
                    return true;
                }
            }
        }

        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingScroll) {
            int maxScroll = maxScroll();
            if (maxScroll <= 0) {
                return true;
            }
            Rect content = contentRect();
            int trackTop = content.y;
            int trackHeight = content.h;
            int thumbHeight = Math.max(THUMB_MIN_H, trackHeight * content.h / Math.max(1, contentHeight));
            thumbHeight = Math.min(trackHeight, thumbHeight);
            int minY = trackTop;
            int maxY = trackTop + trackHeight - thumbHeight;
            if (maxY <= minY) {
                return true;
            }
            int desiredY = (int) click.y() - scrollGrabOffset;
            desiredY = MathHelper.clamp(desiredY, minY, maxY);
            double ratio = (double) (desiredY - minY) / (double) (maxY - minY);
            contentScroll = MathHelper.clamp(ratio * maxScroll, 0.0D, maxScroll);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    private boolean isOnScrollTrack(double mouseX, double mouseY) {
        Rect content = contentRect();
        int trackX = content.right() + 4;
        return mouseX >= trackX && mouseX < trackX + SCROLLBAR_W && mouseY >= content.y && mouseY < content.bottom();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private void closeToGameplay() {
        if (this.client != null) {
            this.client.setScreen(null);
        }
    }

    private void openBasesManager() {
        if (this.client == null) {
            return;
        }
        if (this.parent instanceof BaseManagerScreen) {
            this.client.setScreen(this.parent);
            return;
        }
        this.client.setScreen(new BaseManagerScreen(this, normalizeBotAlias(botTarget)));
    }

    private void buildConstruction(ConstructionEntry entry) {
        if (this.client == null) {
            return;
        }
        var player = this.client.player;
        if (player == null || player.networkHandler == null) {
            return;
        }

        if ("ui.open_bases".equals(entry.command())) {
            openBasesManager();
            return;
        }

        String target = formatBotTarget(botTarget);
        String structureType = entry.structureType();
        if (structureType == null || structureType.isEmpty()) {
            String cmd = "bot " + entry.command() + (target.isEmpty() ? "" : " " + target);
            player.networkHandler.sendChatCommand(cmd);
            closeToGameplay();
            return;
        }

        net.wcfcarolina13.FrensClient.setPendingShelter(structureType, target);
        closeToGameplay();
    }

    private static String normalizeBotAlias(String target) {
        if (target == null) {
            return "";
        }
        String trimmed = target.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String formatBotTarget(String target) {
        String trimmed = normalizeBotAlias(target);
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.contains(" ") ? "\"" + trimmed + "\"" : trimmed;
    }
}
