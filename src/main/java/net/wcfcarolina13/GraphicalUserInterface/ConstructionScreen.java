package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Construction menu window showing available building options grouped by category.
 * Categories: SHELTER, DEFENSIVE, STRONGHOLD, UTILITY.
 * Features scrolling support for many options.
 */
public class ConstructionScreen extends Screen {

    private record ConstructionEntry(String category, String command, String label,
                                     String description, String structureType) {}

    /** Category display names and colors. */
    private static final Map<String, Integer> CATEGORY_COLORS = new LinkedHashMap<>();
    static {
        CATEGORY_COLORS.put("SHELTER",    0xFFA0D0A0); // soft green
        CATEGORY_COLORS.put("DEFENSIVE",  0xFFC0A060); // tan/gold
        CATEGORY_COLORS.put("STRONGHOLD", 0xFFFFE08A); // bright gold
        CATEGORY_COLORS.put("UTILITY",    0xFF9FB6CD); // blue-gray
    }

    private static final List<ConstructionEntry> CONSTRUCTION_OPTIONS = List.of(
            // ── Shelter ──
            new ConstructionEntry("SHELTER", "shelter hovel", "Hovel", "9x5x9 emergency shelter", "hovel"),
            new ConstructionEntry("SHELTER", "shelter burrow", "Burrow", "Underground 5x8x5 chamber", "burrow"),
            new ConstructionEntry("SHELTER", "build small_shelter", "Small Shelter", "5x5 cobblestone shelter", "small_shelter"),
            new ConstructionEntry("SHELTER", "build small_hut", "Small Hut", "5x5 wooden hut", "small_hut"),
            // ── Defensive ──
            new ConstructionEntry("DEFENSIVE", "build watchtower", "Watchtower", "4x4x8 observation tower", "watchtower"),
            new ConstructionEntry("DEFENSIVE", "build defensive_wall_section", "Wall Section", "5-block crenellated wall", "defensive_wall_section"),
            new ConstructionEntry("DEFENSIVE", "build defensive_wall_corner", "Wall Corner", "L-shaped corner piece", "defensive_wall_corner"),
            new ConstructionEntry("DEFENSIVE", "build defensive_gatehouse", "Gatehouse", "Archway with pillars", "defensive_gatehouse"),
            // ── Stronghold (village fortification) ──
            new ConstructionEntry("STRONGHOLD", "fortify", "Fortify Village", "Build defensive wall around nearby village", null),
            new ConstructionEntry("STRONGHOLD", "fortify patch", "Fortify Patch", "Scan & repair existing wall", null),
            new ConstructionEntry("STRONGHOLD", "fortify moat", "Fortify Moat", "Dig moat around existing wall", null),
            new ConstructionEntry("STRONGHOLD", "fortify status", "Fortify Status", "View wall completion stats", null),
            new ConstructionEntry("STRONGHOLD", "fortify list", "Fortify List", "List all saved walls", null),
            // ── Utility ──
            new ConstructionEntry("UTILITY", "build bridge", "Bridge", "9-block bridge with railings", "bridge"),
            new ConstructionEntry("UTILITY", "build test_platform", "Platform", "3x3 test platform", "test_platform")
    );

    private final Screen parent;
    private final String botTarget;
    private static final int COLUMNS = 2;
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 24;
    private static final int GAP = 6;
    private static final int SECTION_HEADER_H = 18;
    private static final int SECTION_GAP = 8;
    private static final int SCROLL_SPEED = 12;

    // Scrolling state
    private int scrollOffset = 0;
    private int maxScrollOffset = 0;
    private int contentHeight = 0;
    private int visibleHeight = 0;

    /** Cached section layout info for rendering headers. */
    private final List<SectionRenderInfo> sectionRenderInfos = new ArrayList<>();

    private record SectionRenderInfo(String category, int y) {}

    public ConstructionScreen(Screen parent, String botTarget) {
        super(Text.literal("Construction"));
        this.parent = parent;
        this.botTarget = botTarget;
    }

    @Override
    protected void init() {
        int gridWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * GAP;
        int startX = (this.width - gridWidth) / 2;
        int startY = 30; // Title area
        int backButtonHeight = 30; // Space for back button

        visibleHeight = this.height - startY - backButtonHeight - 10;

        // Group entries by category (preserving order)
        Map<String, List<ConstructionEntry>> grouped = new LinkedHashMap<>();
        for (ConstructionEntry entry : CONSTRUCTION_OPTIONS) {
            grouped.computeIfAbsent(entry.category(), k -> new ArrayList<>()).add(entry);
        }

        // Compute total content height and lay out buttons
        sectionRenderInfos.clear();
        int y = 0;
        for (Map.Entry<String, List<ConstructionEntry>> section : grouped.entrySet()) {
            String category = section.getKey();
            List<ConstructionEntry> entries = section.getValue();

            // Section header
            sectionRenderInfos.add(new SectionRenderInfo(category, y));
            y += SECTION_HEADER_H;

            // Buttons in 2-column grid
            int numRows = (entries.size() + COLUMNS - 1) / COLUMNS;
            for (int i = 0; i < entries.size(); i++) {
                ConstructionEntry entry = entries.get(i);
                int col = i % COLUMNS;
                int row = i / COLUMNS;

                int buttonX = startX + col * (BUTTON_WIDTH + GAP);
                int buttonY = startY + y + row * (BUTTON_HEIGHT + GAP) - scrollOffset;

                // Only add buttons that are visible
                if (buttonY + BUTTON_HEIGHT > startY - 10 && buttonY < startY + visibleHeight + 10) {
                    this.addDrawableChild(ButtonWidget.builder(
                            Text.literal(entry.label()),
                            btn -> buildConstruction(entry)
                    ).dimensions(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
                }
            }
            y += numRows * (BUTTON_HEIGHT + GAP);
            y += SECTION_GAP;
        }

        contentHeight = y;
        maxScrollOffset = Math.max(0, contentHeight - visibleHeight);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScrollOffset);

        // Back button (fixed at bottom)
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Back"),
                btn -> this.close()
        ).dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScrollOffset > 0) {
            scrollOffset = MathHelper.clamp(scrollOffset - (int)(verticalAmount * SCROLL_SPEED), 0, maxScrollOffset);
            this.clearAndInit(); // Rebuild buttons with new scroll position
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Title
        String title = "Construction";
        int titleW = this.textRenderer.getWidth(title);
        context.drawText(this.textRenderer, title, (this.width - titleW) / 2, 10, 0xFFFFFF, true);

        // Section headers
        int startY = 30;
        int gridWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * GAP;
        int startX = (this.width - gridWidth) / 2;

        for (SectionRenderInfo info : sectionRenderInfos) {
            int headerY = startY + info.y() - scrollOffset;
            if (headerY + SECTION_HEADER_H > startY - 10 && headerY < startY + visibleHeight + 10) {
                int color = CATEGORY_COLORS.getOrDefault(info.category(), 0xFFFFFFFF);
                String label = "── " + info.category() + " ──";
                int labelW = this.textRenderer.getWidth(label);
                context.drawText(this.textRenderer, label, (this.width - labelW) / 2, headerY + 4, color, true);
            }
        }

        // Scroll indicator if scrollable
        if (maxScrollOffset > 0) {
            String scrollHint = "↑↓ Scroll for more";
            int hintW = this.textRenderer.getWidth(scrollHint);
            context.drawText(this.textRenderer, scrollHint, (this.width - hintW) / 2, 20, 0x888888, false);
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(null);
        }
    }

    private void buildConstruction(ConstructionEntry entry) {
        if (this.client == null || this.client.player == null) {
            return;
        }

        // Get structure type directly from entry (already defined in CONSTRUCTION_OPTIONS)
        String structureType = entry.structureType();

        // No structure type means run as a direct skill command (e.g., fortify_village)
        if (structureType == null || structureType.isEmpty()) {
            String cmd = "bot " + entry.command() + (botTarget != null && !botTarget.isEmpty() ? " " + botTarget : "");
            this.client.player.networkHandler.sendChatCommand(cmd);
            this.close();
            return;
        }

        // All structure types use the preview system
        net.wcfcarolina13.FrensClient.setPendingShelter(structureType, botTarget);
        this.close();

        // Show instruction message
        String displayName = entry.label();
        String message = "Look where you want the " + displayName + " and press your 'Go To Look' keybind";
        this.client.player.sendMessage(Text.literal(message), true);

        // Schedule repeats to keep message visible
        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        final MinecraftClient clientRef = this.client;
        for (int delayMs : new int[]{500, 1000, 1500, 2000}) {
            scheduler.schedule(() -> {
                if (clientRef.player != null) {
                    clientRef.execute(() -> clientRef.player.sendMessage(Text.literal(message), true));
                }
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        scheduler.shutdown();
    }
}
