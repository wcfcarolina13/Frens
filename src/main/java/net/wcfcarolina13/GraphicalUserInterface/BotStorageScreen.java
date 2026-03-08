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
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.network.ChestCollectPayload;
import net.wcfcarolina13.network.ChestDismissPayload;
import net.wcfcarolina13.network.RequestChestRegistryPayload;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bot storage/chest registry screen — resizable panel with scrollbar,
 * shows all bot-placed chests grouped by context with per-row actions.
 */
public class BotStorageScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type CHEST_LIST_TYPE = new TypeToken<List<ChestEntry>>() {}.getType();
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("MMM d HH:mm");

    // ── UI prefs persistence ────────────────────────────────────────────
    private static final Path UI_PREFS_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("frens").resolve("storage_screen_ui.json");
    private record UiPrefs(Integer x, Integer y, Integer w, Integer h) {}
    private static UiPrefs CACHED_PREFS = loadUiPrefs();

    // ── Data model ──────────────────────────────────────────────────────
    public record ChestEntry(int x, int y, int z, String context, long placedAtMs, boolean destroyed) {}

    private static List<ChestEntry> LAST_CHESTS = List.of();

    public static void applyChestRegistryJson(String json) {
        if (json == null) { LAST_CHESTS = List.of(); return; }
        try {
            List<ChestEntry> parsed = GSON.fromJson(json, CHEST_LIST_TYPE);
            LAST_CHESTS = parsed != null ? parsed : List.of();
        } catch (Exception ignored) {
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
    private static final int ROW_H = 36;
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
    private final String botTarget;

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

    // Per-row buttons (rebuilt on data change)
    private record RowButton(ButtonWidget widget, ChestEntry entry, int relY) {}
    private final List<RowButton> collectButtons = new ArrayList<>();
    private final List<RowButton> dismissButtons = new ArrayList<>();

    public BotStorageScreen(Screen parent, String botTarget) {
        super(Text.literal("Bot Storage"));
        this.parent = parent;
        this.botTarget = botTarget;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Override
    protected void init() {
        clearChildren();
        collectButtons.clear();
        dismissButtons.clear();

        applySavedOrDefaultPanel();
        buildBottomButtons();
        buildRowButtons();
        contentScroll = MathHelper.clamp(contentScroll, 0.0, maxScroll());
        requestRefresh();
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

    // ── Row buttons ─────────────────────────────────────────────────────

    private void buildRowButtons() {
        // Remove old buttons
        for (RowButton rb : collectButtons) this.remove(rb.widget);
        for (RowButton rb : dismissButtons) this.remove(rb.widget);
        collectButtons.clear();
        dismissButtons.clear();

        List<ChestEntry> chests = getChestsSnapshot();
        Map<String, List<ChestEntry>> grouped = groupByContext(chests);

        int relY = 0;
        for (Map.Entry<String, List<ChestEntry>> group : grouped.entrySet()) {
            relY += GROUP_HEADER_H;
            for (ChestEntry entry : group.getValue()) {
                int rowRelY = relY;

                ButtonWidget collectBtn = ButtonWidget.builder(Text.literal("Collect"), b -> sendCollect(entry))
                        .dimensions(0, 0, BUTTON_W, BUTTON_H).build();
                collectBtn.active = !entry.destroyed;
                this.addDrawableChild(collectBtn);
                collectButtons.add(new RowButton(collectBtn, entry, rowRelY));

                ButtonWidget dismissBtn = ButtonWidget.builder(Text.literal("Dismiss"), b -> sendDismiss(entry))
                        .dimensions(0, 0, BUTTON_W, BUTTON_H).build();
                this.addDrawableChild(dismissBtn);
                dismissButtons.add(new RowButton(dismissBtn, entry, rowRelY));

                relY += ROW_H;
            }
            relY += 4; // gap between groups
        }
        contentHeight = relY + 4;
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

    // ── Rendering ───────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        Rect cr = contentRect();
        contentScroll = MathHelper.clamp(contentScroll, 0.0, maxScroll());

        // Panel background
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);
        drawBorder(context, panelX, panelY, panelW, panelH, COL_BORDER);

        // Header
        context.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + HEADER_H, COL_HEADER);
        context.drawTextWithShadow(this.textRenderer, "Bot Storage", panelX + PANEL_PAD, panelY + 7, COL_TITLE);

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

        // Position row buttons
        positionRowButtons(cr);

        // Chest count status
        int total = getChestsSnapshot().size();
        long active = getChestsSnapshot().stream().filter(c -> !c.destroyed).count();
        String status = active + " active / " + total + " total";
        int statusW = this.textRenderer.getWidth(status);
        context.drawTextWithShadow(this.textRenderer, status,
                panelX + panelW / 2 - statusW / 2, bottomY + 4, 0xFFB0B0B0);
    }

    private void renderContent(DrawContext context, Rect cr, int mouseX, int mouseY) {
        List<ChestEntry> chests = getChestsSnapshot();
        if (chests.isEmpty()) {
            context.drawText(this.textRenderer, "No chests registered yet.",
                    cr.x + 6, cr.y + 6, 0xFFB8A76A, false);
            return;
        }

        Map<String, List<ChestEntry>> grouped = groupByContext(chests);
        int y = cr.y - (int) contentScroll;

        for (Map.Entry<String, List<ChestEntry>> group : grouped.entrySet()) {
            // Group header
            String header = group.getKey();
            context.drawTextWithShadow(this.textRenderer, header, cr.x + 4, y + 3, 0xFFFFE08A);
            y += GROUP_HEADER_H;

            for (ChestEntry entry : group.getValue()) {
                // Row background
                boolean isDestroyed = entry.destroyed;
                int rowBg = isDestroyed ? 0x30662222 : 0x20567832;
                context.fill(cr.x + 1, y, cr.right() - 1, y + ROW_H - 2, rowBg);

                // Coordinates (prominent)
                String coords = entry.x + ", " + entry.y + ", " + entry.z;
                int coordColor = isDestroyed ? 0xFF996666 : 0xFFE6D7A3;
                context.drawText(this.textRenderer, coords, cr.x + 6, y + 3, coordColor, false);

                // Status + timestamp
                String statusLabel = isDestroyed ? "Destroyed" : "Active";
                int statusColor = isDestroyed ? 0xFFCC4444 : 0xFF66AA44;
                String dateStr = "";
                if (entry.placedAtMs > 0) {
                    try { dateStr = " - " + DATE_FMT.format(new Date(entry.placedAtMs)); }
                    catch (Exception ignored) {}
                }
                context.drawText(this.textRenderer, statusLabel + dateStr,
                        cr.x + 6, y + 3 + this.textRenderer.fontHeight + 2, statusColor, false);

                y += ROW_H;
            }
            y += 4;
        }
    }

    private void positionRowButtons(Rect cr) {
        int btnAreaX = cr.right() - BUTTON_W * 2 - 6;
        for (RowButton rb : collectButtons) {
            int y = cr.y + rb.relY - (int) contentScroll + 2;
            rb.widget.setX(btnAreaX);
            rb.widget.setY(y);
            boolean visible = y + BUTTON_H > cr.y && y < cr.bottom();
            rb.widget.visible = visible;
            rb.widget.active = visible && !rb.entry.destroyed;
        }
        for (RowButton rb : dismissButtons) {
            int y = cr.y + rb.relY - (int) contentScroll + 2;
            rb.widget.setX(btnAreaX + BUTTON_W + 4);
            rb.widget.setY(y);
            boolean visible = y + BUTTON_H > cr.y && y < cr.bottom();
            rb.widget.visible = visible;
            rb.widget.active = visible;
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
        } catch (Exception ignored) {}
    }

    private static UiPrefs loadUiPrefs() {
        try {
            if (Files.exists(UI_PREFS_PATH)) {
                try (Reader r = Files.newBufferedReader(UI_PREFS_PATH)) {
                    return GSON.fromJson(r, UiPrefs.class);
                }
            }
        } catch (Exception ignored) {}
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
