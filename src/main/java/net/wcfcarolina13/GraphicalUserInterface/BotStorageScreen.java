package net.wcfcarolina13.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GraphicalUserInterface.Widgets.DropdownMenuWidget;
import net.wcfcarolina13.network.ChestCollectPayload;
import net.wcfcarolina13.network.ChestDismissPayload;
import net.wcfcarolina13.network.RequestChestRegistryPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bot storage/chest registry screen — resizable panel with scrollbar,
 * shows all bot-placed chests grouped by context with per-row actions.
 */
public class BotStorageScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-storage-screen");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type CHEST_LIST_TYPE = new TypeToken<List<ChestEntry>>() {}.getType();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm")
            .withZone(ZoneId.systemDefault());

    // ── UI prefs persistence ────────────────────────────────────────────
    private static final Path UI_PREFS_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("frens").resolve("storage_screen_ui.json");
    private record UiPrefs(Integer x, Integer y, Integer w, Integer h) {}
    private static UiPrefs CACHED_PREFS = loadUiPrefs();

    // ── Data model ──────────────────────────────────────────────────────
    public static final class ItemInfo {
        public String id; // e.g. "minecraft:diamond"
        public int n;     // count
    }

    public static final class ChestEntry {
        public int x, y, z;
        public String context;
        public long placedAtMs;
        public boolean destroyed;
        public List<ItemInfo> contents; // null if no snapshot

        // Accessors for compatibility with existing code.
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public String context() { return context; }
        public long placedAtMs() { return placedAtMs; }
        public boolean destroyed() { return destroyed; }
    }

    private static volatile List<ChestEntry> LAST_CHESTS = List.of();

    public static void applyChestRegistryJson(String json) {
        if (json == null) { LAST_CHESTS = List.of(); return; }
        try {
            List<ChestEntry> parsed = GSON.fromJson(json, CHEST_LIST_TYPE);
            LAST_CHESTS = parsed != null ? java.util.Collections.unmodifiableList(new java.util.ArrayList<>(parsed)) : List.of();
        } catch (Exception e) {
            LOGGER.warn("Failed to parse chest registry JSON: {}", e.getMessage());
            LAST_CHESTS = List.of();
        }
    }

    // ── Panel constants ─────────────────────────────────────────────────
    private static final int PANEL_DEFAULT_W = 300;
    private static final int PANEL_DEFAULT_H = 320;
    private static final int PANEL_MIN_W = 260;
    private static final int PANEL_MIN_H = 180;
    private static final int PANEL_MARGIN = 8;
    private static final int PANEL_PAD = 8;
    private static final int HEADER_H = 24;
    private static final int SCROLLBAR_W = 6;
    private static final int THUMB_MIN_H = 16;
    private static final int SCROLL_STEP = 14;
    private static final int RESIZE_GRAB = 5;
    private static final int ROW_H = 46;
    private static final int GROUP_HEADER_H = 16;
    private static final int BOTTOM_BAR_H = 28;
    private static final int BUTTON_W = 48;
    private static final int BUTTON_H = 16;

    // Colors
    private static final int COL_BG = 0xB0101010;
    private static final int COL_PANEL = 0xEE111111;
    private static final int COL_BORDER = 0xFF2A2A2A;
    private static final int COL_HEADER = 0xFF1A1A1A;
    private static final int COL_TITLE = 0xFFFFE08A;
    private static final int COL_TRACK = 0xFF171717;
    private static final int COL_THUMB = 0xFF7A6240;
    private static final int COL_THUMB_HL = 0xFFB08C40;

    // Resize masks
    private static final int RESIZE_LEFT = 1;
    private static final int RESIZE_RIGHT = 2;
    private static final int RESIZE_TOP = 4;
    private static final int RESIZE_BOTTOM = 8;

    // ── Instance state ──────────────────────────────────────────────────
    private final Screen parent;
    private String botTarget;

    private DropdownMenuWidget botSelector;
    private String lastSelectedBot;
    private ChestEntry hoveredEntry;
    private ChestEntry selectedEntry; // click-to-expand

    private String statusMessage;
    private long statusMessageExpiry;
    private static final long STATUS_DISPLAY_MS = 3000L;

    private int panelX, panelY, panelW, panelH;
    private double contentScroll;
    private boolean draggingScroll;
    private int scrollGrabOffset;
    private boolean draggingResize, draggingMove;
    private int resizeMask;
    private int dragMouseStartX, dragMouseStartY;
    private int dragPanelStartX, dragPanelStartY, dragPanelStartW, dragPanelStartH;

    private int contentHeight;

    private ButtonWidget refreshButton;
    private ButtonWidget closeButton;

    // Cached row layout for click detection (rebuilt each frame in renderContent).
    private record RowLayout(ChestEntry entry, int y, int h, int collectBtnX, int dismissBtnX, int btnY) {}
    private final List<RowLayout> renderedRows = new ArrayList<>();

    public BotStorageScreen(Screen parent, String botTarget) {
        super(Text.literal("Bot Storage"));
        this.parent = parent;
        this.botTarget = botTarget;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void init() {
        clearChildren();

        applySavedOrDefaultPanel();
        buildBotSelector();
        buildBottomButtons();
        rebuildContentHeight();
        contentScroll = MathHelper.clamp(contentScroll, 0.0, maxScroll());
        requestRefresh();
    }

    private void buildBotSelector() {
        List<String> botNames = collectOnlineBotAliases();
        int dropdownX = panelX + PANEL_PAD + this.textRenderer.getWidth("Storage") + 10;
        int dropdownW = Math.min(120, panelW - (dropdownX - panelX) - PANEL_PAD - 50);
        if (dropdownW < 60) dropdownW = 60;

        botSelector = new DropdownMenuWidget(
                dropdownX, panelY + 4, dropdownW, 16,
                Text.literal(stripQuotes(botTarget)), botNames);
        botSelector.setSelectedOption(stripQuotes(botTarget));
        lastSelectedBot = stripQuotes(botTarget);
        this.addDrawableChild(botSelector);
    }

    private List<String> collectOnlineBotAliases() {
        Set<String> onlineNames = getOnlinePlayerNames();
        Set<String> unique = new LinkedHashSet<>();

        // Always include current bot
        String current = stripQuotes(botTarget);
        if (current != null && !current.isBlank()) {
            unique.add(current);
        }

        // Add all configured bots that are online
        if (Frens.CONFIG != null) {
            for (String alias : Frens.CONFIG.getBotGameProfile().keySet()) {
                if (alias != null && onlineNames.contains(alias.toLowerCase(Locale.ROOT))) {
                    unique.add(alias);
                }
            }
            for (String alias : Frens.CONFIG.getAllBotAliases()) {
                if (alias != null && onlineNames.contains(alias.toLowerCase(Locale.ROOT))) {
                    unique.add(alias);
                }
            }
        }

        ArrayList<String> sorted = new ArrayList<>(unique);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private Set<String> getOnlinePlayerNames() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() != null && entry.getProfile().name() != null) {
                names.add(entry.getProfile().name().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static String stripQuotes(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String formatBotTarget(String alias) {
        if (alias == null || alias.isBlank()) return "";
        return alias.contains(" ") ? "\"" + alias + "\"" : alias;
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    // ── Panel layout ────────────────────────────────────────────────────

    private void applySavedOrDefaultPanel() {
        UiPrefs p = CACHED_PREFS;
        if (p != null && p.w != null && p.h != null) {
            panelW = Math.max(PANEL_MIN_W, p.w);
            panelH = Math.max(PANEL_MIN_H, p.h);
            panelX = p.x != null ? p.x : (this.width - panelW) / 2;
            panelY = p.y != null ? p.y : (this.height - panelH) / 2;
        } else {
            resetPanelToDefault();
        }
        clampPanel();
    }

    private void resetPanelToDefault() {
        panelW = PANEL_DEFAULT_W;
        panelH = PANEL_DEFAULT_H;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
    }

    private void clampPanel() {
        panelX = MathHelper.clamp(panelX, PANEL_MARGIN, this.width - PANEL_MIN_W - PANEL_MARGIN);
        panelY = MathHelper.clamp(panelY, PANEL_MARGIN, this.height - PANEL_MIN_H - PANEL_MARGIN);
        panelW = Math.min(panelW, this.width - panelX - PANEL_MARGIN);
        panelH = Math.min(panelH, this.height - panelY - PANEL_MARGIN);
    }

    private Rect contentRect() {
        int cx = panelX + PANEL_PAD;
        int cy = panelY + HEADER_H;
        int cw = panelW - PANEL_PAD * 2 - SCROLLBAR_W - 2;
        int ch = panelH - HEADER_H - BOTTOM_BAR_H;
        return new Rect(cx, cy, Math.max(cw, 40), Math.max(ch, 20));
    }

    private int maxScroll() {
        Rect cr = contentRect();
        return Math.max(0, contentHeight - cr.h);
    }

    // ── Bottom bar buttons ──────────────────────────────────────────────

    private void buildBottomButtons() {
        refreshButton = ButtonWidget.builder(Text.literal("Refresh"), b -> requestRefresh())
                .dimensions(0, 0, 58, 18).build();
        refreshButton.setTooltip(Tooltip.of(Text.literal("Refresh chest data from server")));
        this.addDrawableChild(refreshButton);

        closeButton = ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(0, 0, 50, 18).build();
        this.addDrawableChild(closeButton);
    }

    // ── Content height ─────────────────────────────────────────────────

    private void rebuildContentHeight() {
        List<ChestEntry> chests = getChestsSnapshot();
        Map<String, List<ChestEntry>> grouped = groupByContext(chests);
        int h = 0;
        for (Map.Entry<String, List<ChestEntry>> group : grouped.entrySet()) {
            h += GROUP_HEADER_H;
            for (ChestEntry entry : group.getValue()) {
                h += computeRowHeight(entry);
            }
            h += 4;
        }
        contentHeight = h + 4;
    }

    private int computeRowHeight(ChestEntry entry) {
        if (entry == selectedEntry && entry.contents != null && !entry.contents.isEmpty()) {
            // Expanded: base row + one line per item
            return ROW_H + (entry.contents.size() * (this.textRenderer.fontHeight + 1)) + 4;
        }
        return ROW_H;
    }

    private static Map<String, List<ChestEntry>> groupByContext(List<ChestEntry> chests) {
        Map<String, List<ChestEntry>> grouped = new LinkedHashMap<>();
        for (ChestEntry c : chests) {
            String ctx = c.context != null && !c.context.isBlank() ? capitalize(c.context) : "Other";
            grouped.computeIfAbsent(ctx, k -> new ArrayList<>()).add(c);
        }
        return grouped;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    /** Convert "minecraft:iron_ingot" → "Iron Ingot". */
    private static String formatItemName(String itemId) {
        if (itemId == null) return "?";
        String raw = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("_")) {
            if (sb.length() > 0) sb.append(' ');
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Detect bot selector change
        if (botSelector != null) {
            String selected = botSelector.getSelectedOption();
            if (selected != null && !selected.equals(lastSelectedBot)) {
                lastSelectedBot = selected;
                botTarget = formatBotTarget(selected);
                requestRefresh();
                rebuildContentHeight();
            }
        }

        Rect cr = contentRect();
        contentScroll = MathHelper.clamp(contentScroll, 0.0, maxScroll());

        // Panel background
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);
        drawBorder(context, panelX, panelY, panelW, panelH, COL_BORDER);

        // Header
        context.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + HEADER_H, COL_HEADER);
        context.drawTextWithShadow(this.textRenderer, "Storage", panelX + PANEL_PAD, panelY + 7, COL_TITLE);

        // Position bot selector in header
        if (botSelector != null) {
            int dropdownX = panelX + PANEL_PAD + this.textRenderer.getWidth("Storage") + 10;
            int dropdownW = Math.min(120, panelX + panelW - dropdownX - PANEL_PAD - 50);
            if (dropdownW < 60) dropdownW = 60;
            botSelector.setX(dropdownX);
            botSelector.setY(panelY + 4);
            botSelector.setWidth(dropdownW);
        }

        // Reset layout hint in header
        String resetLabel = "Reset";
        int resetW = this.textRenderer.getWidth(resetLabel) + 8;
        int resetX = panelX + panelW - resetW - PANEL_PAD - 4;
        int resetY = panelY + 5;
        boolean hoverReset = mouseX >= resetX && mouseX < resetX + resetW && mouseY >= resetY && mouseY < resetY + 14;
        context.drawTextWithShadow(this.textRenderer, resetLabel, resetX + 4, resetY + 2,
                hoverReset ? 0xFFFFCC66 : 0xFF888888);

        // Content area — enable scissor to clip
        context.enableScissor(cr.x, cr.y, cr.right(), cr.bottom());
        renderContent(context, cr, mouseX, mouseY);
        context.disableScissor();

        // Scrollbar
        renderScrollbar(context, cr, mouseX, mouseY);

        // Position bottom buttons
        int bottomY = panelY + panelH - BOTTOM_BAR_H + 4;
        refreshButton.setX(panelX + PANEL_PAD);
        refreshButton.setY(bottomY);
        closeButton.setX(panelX + panelW - PANEL_PAD - 50);
        closeButton.setY(bottomY);

        // Chest count status or action feedback
        String status;
        int statusColor;
        if (statusMessage != null && System.currentTimeMillis() < statusMessageExpiry) {
            status = statusMessage;
            statusColor = 0xFFAADD66;
        } else {
            statusMessage = null;
            List<ChestEntry> snapshot = getChestsSnapshot();
            int total = snapshot.size();
            long active = snapshot.stream().filter(c -> !c.destroyed).count();
            status = active + " active / " + total + " total";
            statusColor = 0xFFB0B0B0;
        }
        int statusW = this.textRenderer.getWidth(status);
        context.drawTextWithShadow(this.textRenderer, status,
                panelX + panelW / 2 - statusW / 2, bottomY + 4, statusColor);

        // Render dropdown on top so it overlays content when open
        if (botSelector != null) {
            botSelector.renderOnTop(context, mouseX, mouseY, delta);
        }

        // Chest contents tooltip on hover
        if (hoveredEntry != null && hoveredEntry.contents != null && !hoveredEntry.contents.isEmpty()) {
            List<String> lines = new java.util.ArrayList<>();
            String title = (hoveredEntry.destroyed ? "Last Known " : "") + "Contents:";
            lines.add(title);
            for (ItemInfo item : hoveredEntry.contents) {
                lines.add("  " + formatItemName(item.id) + " x" + item.n);
            }
            renderTooltip(context, mouseX, mouseY, lines);
        }
        hoveredEntry = null;
    }

    private void renderTooltip(DrawContext context, int mx, int my, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        int pad = 5;
        int lineH = this.textRenderer.fontHeight + 1;
        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, this.textRenderer.getWidth(line));
        }
        int boxW = maxW + pad * 2;
        int boxH = lines.size() * lineH + pad * 2;
        int x = mx + 12;
        int y = my - 4;
        if (x + boxW > this.width) x = mx - boxW - 4;
        if (y + boxH > this.height) y = this.height - boxH;
        if (y < 0) y = 0;

        context.fill(x, y, x + boxW, y + boxH, 0xEE101010);
        context.fill(x, y, x + boxW, y + 1, 0xFF333333);
        context.fill(x, y + boxH - 1, x + boxW, y + boxH, 0xFF333333);
        context.fill(x, y, x + 1, y + boxH, 0xFF333333);
        context.fill(x + boxW - 1, y, x + boxW, y + boxH, 0xFF333333);

        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? 0xFFFFE08A : 0xFFD8C7A0;
            context.drawText(this.textRenderer, lines.get(i), x + pad, y + pad + i * lineH, color, false);
        }
    }

    private void renderContent(DrawContext context, Rect cr, int mouseX, int mouseY) {
        renderedRows.clear();
        List<ChestEntry> chests = getChestsSnapshot();
        if (chests.isEmpty()) {
            context.drawText(this.textRenderer, "No chests registered yet.",
                    cr.x + 6, cr.y + 6, 0xFFB8A76A, false);
            return;
        }

        Map<String, List<ChestEntry>> grouped = groupByContext(chests);
        int y = cr.y - (int) contentScroll;
        int fh = this.textRenderer.fontHeight;

        for (Map.Entry<String, List<ChestEntry>> group : grouped.entrySet()) {
            // Group header
            context.drawTextWithShadow(this.textRenderer, group.getKey(), cr.x + 4, y + 3, 0xFFFFE08A);
            y += GROUP_HEADER_H;

            for (ChestEntry entry : group.getValue()) {
                boolean isDestroyed = entry.destroyed;
                boolean isSelected = entry == selectedEntry;
                int rowH = computeRowHeight(entry);

                // Row background
                int rowBg = isSelected ? 0x40B08C40
                        : (isDestroyed ? 0x30662222 : 0x20567832);
                context.fill(cr.x + 1, y, cr.right() - 1, y + rowH - 2, rowBg);
                if (isSelected) {
                    // Selection border
                    context.fill(cr.x + 1, y, cr.x + 3, y + rowH - 2, 0xFFB08C40);
                }

                // Coordinates
                String coords = entry.x + ", " + entry.y + ", " + entry.z;
                context.drawText(this.textRenderer, coords, cr.x + 6, y + 3,
                        isDestroyed ? 0xFF996666 : 0xFFE6D7A3, false);

                // Status + timestamp
                String statusLabel = isDestroyed ? "Destroyed" : "Active";
                int statusColor = isDestroyed ? 0xFFCC4444 : 0xFF66AA44;
                String dateStr = "";
                if (entry.placedAtMs > 0) {
                    try { dateStr = " - " + DATE_FMT.format(Instant.ofEpochMilli(entry.placedAtMs)); }
                    catch (Exception ignored) {}
                }
                int statusY = y + 3 + fh + 2;
                context.drawText(this.textRenderer, statusLabel + dateStr, cr.x + 6, statusY, statusColor, false);

                // Contents summary (brief, third line)
                if (entry.contents != null && !entry.contents.isEmpty()) {
                    StringBuilder brief = new StringBuilder();
                    int shown = 0;
                    for (ItemInfo item : entry.contents) {
                        if (shown > 0) brief.append(", ");
                        brief.append(formatItemName(item.id)).append(" x").append(item.n);
                        shown++;
                        if (shown >= 3) { brief.append("..."); break; }
                    }
                    int contentsY = statusY + fh + 1;
                    String contentsStr = brief.toString();
                    int maxTextW = cr.w - 12 - BUTTON_W * 2 - 14;
                    if (this.textRenderer.getWidth(contentsStr) > maxTextW) {
                        contentsStr = this.textRenderer.trimToWidth(contentsStr, maxTextW - 6) + "...";
                    }
                    context.drawText(this.textRenderer, contentsStr, cr.x + 6, contentsY,
                            isDestroyed ? 0xFF776666 : 0xFF999977, false);
                }

                // Custom-drawn Collect button
                int collectBtnX = cr.right() - BUTTON_W * 2 - 8;
                int btnY = y + 4;
                boolean collectHover = !isDestroyed && mouseX >= collectBtnX && mouseX < collectBtnX + BUTTON_W
                        && mouseY >= btnY && mouseY < btnY + BUTTON_H;
                int collectBg = isDestroyed ? 0xFF1A1A1A : (collectHover ? 0xFF3A5A2A : 0xFF2A3A1E);
                context.fill(collectBtnX, btnY, collectBtnX + BUTTON_W, btnY + BUTTON_H, collectBg);
                context.fill(collectBtnX, btnY, collectBtnX + BUTTON_W, btnY + 1, 0xFF444444);
                context.fill(collectBtnX, btnY + BUTTON_H - 1, collectBtnX + BUTTON_W, btnY + BUTTON_H, 0xFF222222);
                context.drawText(this.textRenderer, "Collect",
                        collectBtnX + (BUTTON_W - this.textRenderer.getWidth("Collect")) / 2, btnY + 4,
                        isDestroyed ? 0xFF555555 : (collectHover ? 0xFFEEFFCC : 0xFFCCDDAA), false);

                // Custom-drawn Dismiss button
                int dismissBtnX = collectBtnX + BUTTON_W + 4;
                boolean dismissHover = mouseX >= dismissBtnX && mouseX < dismissBtnX + BUTTON_W
                        && mouseY >= btnY && mouseY < btnY + BUTTON_H;
                int dismissBg = dismissHover ? 0xFF5A2A2A : 0xFF3A1E1E;
                context.fill(dismissBtnX, btnY, dismissBtnX + BUTTON_W, btnY + BUTTON_H, dismissBg);
                context.fill(dismissBtnX, btnY, dismissBtnX + BUTTON_W, btnY + 1, 0xFF444444);
                context.fill(dismissBtnX, btnY + BUTTON_H - 1, dismissBtnX + BUTTON_W, btnY + BUTTON_H, 0xFF222222);
                context.drawText(this.textRenderer, "Dismiss",
                        dismissBtnX + (BUTTON_W - this.textRenderer.getWidth("Dismiss")) / 2, btnY + 4,
                        dismissHover ? 0xFFFFAAAA : 0xFFCC9999, false);

                // Expanded contents (when selected)
                if (isSelected && entry.contents != null && !entry.contents.isEmpty()) {
                    int expandY = y + ROW_H;
                    for (ItemInfo item : entry.contents) {
                        String line = "  \u2022 " + formatItemName(item.id) + " x" + item.n;
                        context.drawText(this.textRenderer, line, cr.x + 10, expandY,
                                isDestroyed ? 0xFF887777 : 0xFFD8C7A0, false);
                        expandY += fh + 1;
                    }
                }

                // Track hovered row for tooltip
                if (mouseX >= cr.x && mouseX < cr.right() && mouseY >= y && mouseY < y + rowH) {
                    hoveredEntry = entry;
                }

                // Record layout for click detection
                renderedRows.add(new RowLayout(entry, y, rowH, collectBtnX, dismissBtnX, btnY));

                y += rowH;
            }
            y += 4;
        }
    }

    private void renderScrollbar(DrawContext context, Rect cr, int mouseX, int mouseY) {
        int maxS = maxScroll();
        int trackX = cr.right() + 2;
        int trackY = cr.y;
        int trackH = cr.h;
        context.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, COL_TRACK);
        if (maxS <= 0) return;

        int thumbH = Math.max(THUMB_MIN_H, (int) ((double) cr.h / (cr.h + maxS) * trackH));
        int thumbY = trackY + (int) ((contentScroll / maxS) * (trackH - thumbH));
        boolean hoverThumb = mouseX >= trackX && mouseX < trackX + SCROLLBAR_W
                && mouseY >= thumbY && mouseY < thumbY + thumbH;
        context.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH,
                draggingScroll || hoverThumb ? COL_THUMB_HL : COL_THUMB);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, COL_BG);
    }

    private static void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ── Input handling ──────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        Rect cr = contentRect();
        if (cr.contains(mouseX, mouseY)) {
            contentScroll -= vAmount * SCROLL_STEP;
            contentScroll = MathHelper.clamp(contentScroll, 0.0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, hAmount, vAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        double mx = click.x();
        double my = click.y();

        // Header reset-layout click
        String resetLabel = "Reset";
        int resetW = this.textRenderer.getWidth(resetLabel) + 8;
        int resetX = panelX + panelW - resetW - PANEL_PAD - 4;
        int resetY = panelY + 5;
        if (mx >= resetX && mx < resetX + resetW && my >= resetY && my < resetY + 14) {
            resetPanelToDefault();
            persistPanelPrefs();
            init();
            return true;
        }

        // Scrollbar thumb drag
        Rect cr = contentRect();
        int maxS = maxScroll();
        if (maxS > 0) {
            int trackX = cr.right() + 2;
            int trackY = cr.y;
            int trackH = cr.h;
            int thumbH = Math.max(THUMB_MIN_H, (int) ((double) cr.h / (cr.h + maxS) * trackH));
            int thumbY = trackY + (int) ((contentScroll / maxS) * (trackH - thumbH));
            if (mx >= trackX && mx < trackX + SCROLLBAR_W && my >= thumbY && my < thumbY + thumbH) {
                draggingScroll = true;
                scrollGrabOffset = (int) my - thumbY;
                return true;
            }
        }

        // Resize edges
        int rm = resizeMaskFor(mx, my);
        if (rm != 0) {
            draggingResize = true;
            resizeMask = rm;
            dragMouseStartX = (int) mx;
            dragMouseStartY = (int) my;
            dragPanelStartX = panelX;
            dragPanelStartY = panelY;
            dragPanelStartW = panelW;
            dragPanelStartH = panelH;
            return true;
        }

        // Let the bot selector dropdown handle clicks first (it's in the header area).
        if (botSelector != null && botSelector.isMouseOver(mx, my)) {
            return super.mouseClicked(click, isInside);
        }

        // Custom row button clicks + row selection
        for (RowLayout rl : renderedRows) {
            if (my < rl.y || my >= rl.y + rl.h) continue;
            // Collect button?
            if (mx >= rl.collectBtnX && mx < rl.collectBtnX + BUTTON_W
                    && my >= rl.btnY && my < rl.btnY + BUTTON_H) {
                if (!rl.entry.destroyed) sendCollect(rl.entry);
                return true;
            }
            // Dismiss button?
            if (mx >= rl.dismissBtnX && mx < rl.dismissBtnX + BUTTON_W
                    && my >= rl.btnY && my < rl.btnY + BUTTON_H) {
                sendDismiss(rl.entry);
                return true;
            }
            // Row click → toggle selection
            if (mx >= cr.x && mx < cr.right()) {
                selectedEntry = (selectedEntry == rl.entry) ? null : rl.entry;
                rebuildContentHeight();
                return true;
            }
        }

        // Header drag (move)
        if (mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + HEADER_H) {
            draggingMove = true;
            dragMouseStartX = (int) mx;
            dragMouseStartY = (int) my;
            dragPanelStartX = panelX;
            dragPanelStartY = panelY;
            return true;
        }

        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        int mx = (int) click.x();
        int my = (int) click.y();
        if (draggingScroll) {
            int maxS = maxScroll();
            if (maxS <= 0) { draggingScroll = false; return false; }
            Rect cr = contentRect();
            int trackY = cr.y;
            int trackH = cr.h;
            int thumbH = Math.max(THUMB_MIN_H, (int) ((double) cr.h / (cr.h + maxS) * trackH));
            int desiredY = my - scrollGrabOffset;
            int minY = trackY;
            int maxY = trackY + trackH - thumbH;
            desiredY = MathHelper.clamp(desiredY, minY, maxY);
            contentScroll = maxY > minY ? ((double) (desiredY - minY) / (maxY - minY)) * maxS : 0;
            return true;
        }
        if (draggingResize) {
            int dx = mx - dragMouseStartX;
            int dy = my - dragMouseStartY;
            if ((resizeMask & RESIZE_LEFT) != 0) {
                int newX = dragPanelStartX + dx;
                int newW = dragPanelStartW - dx;
                if (newW >= PANEL_MIN_W) { panelX = newX; panelW = newW; }
            }
            if ((resizeMask & RESIZE_RIGHT) != 0) {
                panelW = Math.max(PANEL_MIN_W, dragPanelStartW + dx);
            }
            if ((resizeMask & RESIZE_TOP) != 0) {
                int newY = dragPanelStartY + dy;
                int newH = dragPanelStartH - dy;
                if (newH >= PANEL_MIN_H) { panelY = newY; panelH = newH; }
            }
            if ((resizeMask & RESIZE_BOTTOM) != 0) {
                panelH = Math.max(PANEL_MIN_H, dragPanelStartH + dy);
            }
            clampPanel();
            return true;
        }
        if (draggingMove) {
            panelX = dragPanelStartX + mx - dragMouseStartX;
            panelY = dragPanelStartY + my - dragMouseStartY;
            clampPanel();
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingScroll || draggingResize || draggingMove) {
            if (draggingResize || draggingMove) persistPanelPrefs();
            draggingScroll = false;
            draggingResize = false;
            draggingMove = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    private int resizeMaskFor(double mx, double my) {
        boolean withinY = my >= panelY - RESIZE_GRAB && my <= panelY + panelH + RESIZE_GRAB;
        boolean withinX = mx >= panelX - RESIZE_GRAB && mx <= panelX + panelW + RESIZE_GRAB;
        if (!withinX || !withinY) return 0;
        int mask = 0;
        if (Math.abs(mx - panelX) <= RESIZE_GRAB) mask |= RESIZE_LEFT;
        if (Math.abs(mx - (panelX + panelW)) <= RESIZE_GRAB) mask |= RESIZE_RIGHT;
        if (Math.abs(my - panelY) <= RESIZE_GRAB) mask |= RESIZE_TOP;
        if (Math.abs(my - (panelY + panelH)) <= RESIZE_GRAB) mask |= RESIZE_BOTTOM;
        return mask;
    }

    // ── UI prefs persistence ────────────────────────────────────────────

    private void persistPanelPrefs() {
        CACHED_PREFS = new UiPrefs(panelX, panelY, panelW, panelH);
        try {
            Files.createDirectories(UI_PREFS_PATH.getParent());
            try (Writer w = Files.newBufferedWriter(UI_PREFS_PATH)) {
                GSON.toJson(CACHED_PREFS, w);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to save storage screen prefs: {}", e.getMessage());
        }
    }

    private static UiPrefs loadUiPrefs() {
        try {
            if (Files.exists(UI_PREFS_PATH)) {
                try (Reader r = Files.newBufferedReader(UI_PREFS_PATH)) {
                    UiPrefs prefs = GSON.fromJson(r, UiPrefs.class);
                    return prefs;
                }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("bot-storage-screen")
                    .warn("Failed to load storage screen prefs: {}", e.getMessage());
        }
        return null;
    }

    // ── Network ─────────────────────────────────────────────────────────

    private void requestRefresh() {
        if (botTarget == null || botTarget.isBlank()) return;
        if (ClientPlayNetworking.canSend(RequestChestRegistryPayload.ID)) {
            ClientPlayNetworking.send(new RequestChestRegistryPayload(botTarget));
        }
    }

    private void sendCollect(ChestEntry entry) {
        if (botTarget == null || botTarget.isBlank()) return;
        if (!ClientPlayNetworking.canSend(ChestCollectPayload.ID)) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botName", botTarget);
        out.put("x", entry.x);
        out.put("y", entry.y);
        out.put("z", entry.z);
        ClientPlayNetworking.send(new ChestCollectPayload(GSON.toJson(out)));
        showStatus("Sent to collect from " + entry.x + ", " + entry.y + ", " + entry.z);
    }

    private void sendDismiss(ChestEntry entry) {
        if (botTarget == null || botTarget.isBlank()) return;
        if (!ClientPlayNetworking.canSend(ChestDismissPayload.ID)) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botName", botTarget);
        out.put("x", entry.x);
        out.put("y", entry.y);
        out.put("z", entry.z);
        ClientPlayNetworking.send(new ChestDismissPayload(GSON.toJson(out)));
        showStatus("Dismissed");
    }

    private void showStatus(String msg) {
        statusMessage = msg;
        statusMessageExpiry = System.currentTimeMillis() + STATUS_DISPLAY_MS;
    }

    private static List<ChestEntry> getChestsSnapshot() {
        List<ChestEntry> c = LAST_CHESTS;
        return c != null ? c : List.of();
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
