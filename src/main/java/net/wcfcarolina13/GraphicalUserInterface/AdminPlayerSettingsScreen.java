package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin-only screen for granular player permission management.
 *
 * Features:
 * - Pixel-smooth scrolling with draggable scrollbar
 * - Hover tooltips for each permission
 * - Admin self-lock protection (cannot disable own "Open Bot Controls")
 * - Scope switching: global defaults vs. per-player overrides
 */
public class AdminPlayerSettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int TOGGLE_W = 50;
    private static final int SCROLLBAR_W = 10;
    private static final int THUMB_MIN_H = 20;
    private static final double SCROLL_STEP = 18.0;
    private static final long TOOLTIP_DELAY_MS = 1700L;

    /** Permissions that cannot be toggled OFF when editing your own access. */
    private static final List<String> SELF_LOCK_KEYS = List.of("open_bot_controls");

    private static final List<PermissionDef> PERMISSIONS = List.of(
            new PermissionDef("open_bot_controls", "Open Bot Controls",
                "Lets this player open the Bot Controls panel to change spawn mode and per-bot settings. Your own access cannot be turned off here."),
            new PermissionDef("open_spells", "Open Spells",
                "Lets this player open the companion spell menu when the normal in-game spell requirements are met."),
            new PermissionDef("open_skin_chooser", "Open Skin Chooser",
                "Lets this player open the skin chooser and change companion appearance, subject to skin policy rules."),
            new PermissionDef("gameplay_tips", "Toggle Gameplay Tips",
                "Lets this player turn gameplay tip popups on or off for their own client experience."),
            new PermissionDef("idle_hobbies_anywhere", "Toggle Idle Hobbies Anywhere",
                "Lets this player allow passive idle hobbies even when the bot is away from home/base areas."),
            new PermissionDef("baritone_pathfinder", "Toggle Baritone Pathfinder",
                "Lets this player switch between the classic pathfinder and the newer Baritone-style pathfinding option."),
            new PermissionDef("follow_teleport", "Toggle Follow Teleport",
                "Lets this player enable or disable the wolf-style follow teleport for their companion in the Bot Controls panel."),
            new PermissionDef("post_task_return", "Post-Task Return",
                "After finishing or being stopped from an assigned task underground, the bot fast-travels to the nearest bed, base, or commander. Hunger cost applies."),
            new PermissionDef("skin_policy_everyone", "Allow Skin Changes for Everyone",
                "Lets this player change whether non-admin players may change companion skins."),
            new PermissionDef("skin_policy_custom", "Allow Custom URL Skins",
                "Lets this player change whether custom URL skins are allowed or restricted to safer preset-based choices."),
            new PermissionDef("wizard_tome", "Give Wizard's Tome",
                "Lets this player use the admin action that grants the Wizard's Tome item for testing or setup."),
            new PermissionDef("learning_manage", "Manage Learning Mode",
                    "Lets this player use operator learning-mode controls that record demonstration traces for bot tuning and future ML/LLM roadmap work. See Guide > Learning Mode / Roadmap."),
            new PermissionDef("recruit_manage", "Manage Recruitment",
                "Lets this player view and change recruitment / questing-mode state for this world and companion flow."),
            new PermissionDef("rescue_manage", "Manage Autonomous Rescue Policy",
                "Lets this player manage the server-wide autonomous rescue policy. Operators still control whether the toggle is visible and usable in the admin menu."),
            new PermissionDef("recruit_reset", "Reset Recruitment",
                "Lets this player reset recruitment progress for testing. This affects world and companion progression, not just local UI state."),
            new PermissionDef("village_anchor", "Manage Village Anchor",
                "Lets this player set or clear the village/settlement anchor used for recruitment and progression checks."),
            new PermissionDef("stage_debug", "Set Quest Stage (Debug)",
                "Lets this player use debug stage-set tools to advance or rewind companion progression without normal gameplay checks.")
    );

    private final Screen parent;
    private final String botAlias;

    private List<UserRef> knownUsers = List.of();
    private String selectedUserUuid = ""; // blank = global defaults
    private double scrollOffset = 0;

    // Scrollbar drag state
    private boolean draggingScroll = false;
    private int scrollGrabOffset = 0;

    // Tooltip state
    private String tooltipText = null;
    private int tooltipX, tooltipY;
    private String tooltipHoverKey = null;
    private long tooltipHoverStartMs = 0L;
    private boolean tooltipHoverSeenThisFrame = false;

    // Flash message for self-lock denial
    private String flashMessage = null;
    private long flashExpire = 0;

    private Rect userPrevRect;
    private Rect userNextRect;
    private Rect refreshRect;
    private Rect doneRect;
    private Rect rowsRect;

    public AdminPlayerSettingsScreen(Screen parent, String botAlias) {
        super(Text.literal("Player Permissions"));
        this.parent = parent;
        this.botAlias = botAlias == null ? "" : botAlias;
    }

    @Override
    protected void init() {
        super.init();
        BotPlayerInventoryScreen.requestAdminPermissionsSnapshot(botAlias);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    // ── Scroll input ──────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (rowsRect == null || !rowsRect.contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        resetTooltipHoverState();
        int contentH = PERMISSIONS.size() * ROW_HEIGHT;
        int maxScroll = Math.max(0, contentH - rowsRect.h);
        if (maxScroll <= 0) return true;
        scrollOffset = MathHelper.clamp(scrollOffset - verticalAmount * SCROLL_STEP, 0, maxScroll);
        return true;
    }

    // ── Click handling ────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        double mouseX = click.x();
        double mouseY = click.y();
        resetTooltipHoverState();

        if (userPrevRect != null && userPrevRect.contains(mouseX, mouseY)) {
            cycleScope(-1);
            return true;
        }
        if (userNextRect != null && userNextRect.contains(mouseX, mouseY)) {
            cycleScope(1);
            return true;
        }
        if (refreshRect != null && refreshRect.contains(mouseX, mouseY)) {
            BotPlayerInventoryScreen.requestAdminPermissionsSnapshot(botAlias);
            return true;
        }
        if (doneRect != null && doneRect.contains(mouseX, mouseY)) {
            close();
            return true;
        }

        // Scrollbar drag
        if (rowsRect != null) {
            int contentH = PERMISSIONS.size() * ROW_HEIGHT;
            int maxScroll = Math.max(0, contentH - rowsRect.h);
            if (maxScroll > 0) {
                int trackX = rowsRect.x + rowsRect.w - SCROLLBAR_W;
                int trackT = rowsRect.y;
                int trackH = rowsRect.h;
                if (mouseX >= trackX - 2 && mouseX < trackX + SCROLLBAR_W + 2
                        && mouseY >= trackT && mouseY < trackT + trackH) {
                    int[] thumb = computeThumb(trackT, trackH, maxScroll);
                    if (thumb != null && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1]) {
                        draggingScroll = true;
                        scrollGrabOffset = (int) mouseY - thumb[0];
                        return true;
                    }
                    // Click outside thumb: jump to position
                    if (thumb != null) {
                        float frac = (float) (mouseY - trackT) / trackH;
                        scrollOffset = MathHelper.clamp(frac * maxScroll, 0, maxScroll);
                        draggingScroll = true;
                        int[] newThumb = computeThumb(trackT, trackH, maxScroll);
                        scrollGrabOffset = newThumb != null ? (int) mouseY - newThumb[0] : 0;
                        return true;
                    }
                }
            }
        }

        // Row click (toggle permission)
        if (rowsRect != null && rowsRect.contains(mouseX, mouseY)) {
            int clickableW = rowsRect.w - SCROLLBAR_W - 2;
            if (mouseX < rowsRect.x + clickableW) {
                int localY = (int) (mouseY - rowsRect.y + scrollOffset);
                int index = localY / ROW_HEIGHT;
                if (index >= 0 && index < PERMISSIONS.size()) {
                    PermissionDef def = PERMISSIONS.get(index);
                    if (def != null) {
                        togglePermission(def.key());
                    }
                }
            }
            return true;
        }

        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click,
                                double deltaX, double deltaY) {
        if (draggingScroll && rowsRect != null) {
            resetTooltipHoverState();
            int contentH = PERMISSIONS.size() * ROW_HEIGHT;
            int maxScroll = Math.max(0, contentH - rowsRect.h);
            if (maxScroll <= 0) return true;
            int trackT = rowsRect.y;
            int trackH = rowsRect.h;
            int thumbH = Math.max(THUMB_MIN_H, trackH * rowsRect.h / Math.max(1, contentH));
            thumbH = Math.min(trackH, thumbH);
            int minY = trackT;
            int maxY = trackT + trackH - thumbH;
            if (maxY <= minY) return true;
            int desiredY = (int) click.y() - scrollGrabOffset;
            desiredY = MathHelper.clamp(desiredY, minY, maxY);
            double ratio = (double) (desiredY - minY) / (double) (maxY - minY);
            scrollOffset = MathHelper.clamp(ratio * maxScroll, 0, maxScroll);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    // ── Render ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        tooltipText = null;
        tooltipHoverSeenThisFrame = false;

        // Manual dim instead of renderBackground() to avoid double-blur crash
        context.fill(0, 0, this.width, this.height, 0xD0101010);

        refreshKnownUsers();

        int panelW = Math.min(480, this.width - 24);
        int panelH = Math.min(400, this.height - 24);
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        // Panel background and border
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101010);
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF000000);
        context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF000000);
        context.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF000000);
        context.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF000000);

        // Title and subtitle
        context.drawText(this.textRenderer, "Player Permissions • " + botAlias, panelX + 10, panelY + 8, 0xFFFFE08A, false);
        context.drawText(this.textRenderer, "Set global defaults, then add per-player overrides.", panelX + 10, panelY + 22, 0xFFD8C7A0, false);

        // Controls row
        int controlsY = panelY + 40;
        userPrevRect = new Rect(panelX + 10, controlsY, 16, 14);
        userNextRect = new Rect(panelX + panelW - 26, controlsY, 16, 14);
        refreshRect = new Rect(panelX + panelW - 88, controlsY + 20, 38, 14);
        doneRect = new Rect(panelX + panelW - 46, controlsY + 20, 36, 14);

        drawSmallButton(context, userPrevRect, "<", mouseX, mouseY);
        drawSmallButton(context, userNextRect, ">", mouseX, mouseY);
        drawSmallButton(context, refreshRect, "Sync", mouseX, mouseY);
        drawSmallButton(context, doneRect, "Done", mouseX, mouseY);

        String scopeLabel = scopeDisplayLabel();
        int scopeX = userPrevRect.right() + 6;
        int scopeMaxW = Math.max(10, userNextRect.x - 6 - scopeX);
        context.drawText(this.textRenderer, elideToWidth(scopeLabel, scopeMaxW), scopeX, controlsY + 3, 0xFFE6D7A3, false);
        context.drawText(this.textRenderer, selectedUserUuid.isBlank() ? "Editing global defaults" : "Editing per-player overrides", panelX + 10, controlsY + 22, 0xFFB0B0B0, false);

        // Rows area
        int rowsX = panelX + 10;
        int rowsY = panelY + 76;
        int rowsW = panelW - 20;
        int rowsH = panelH - 88;
        rowsRect = new Rect(rowsX, rowsY, rowsW, rowsH);

        // Rows background and border
        context.fill(rowsX, rowsY, rowsX + rowsW, rowsY + rowsH, 0xAA141414);
        context.fill(rowsX, rowsY, rowsX + rowsW, rowsY + 1, 0xFF2C2C2C);
        context.fill(rowsX, rowsY + rowsH - 1, rowsX + rowsW, rowsY + rowsH, 0xFF2C2C2C);
        context.fill(rowsX, rowsY, rowsX + 1, rowsY + rowsH, 0xFF2C2C2C);
        context.fill(rowsX + rowsW - 1, rowsY, rowsX + rowsW, rowsY + rowsH, 0xFF2C2C2C);

        // Compute scroll bounds
        int contentH = PERMISSIONS.size() * ROW_HEIGHT;
        int maxScroll = Math.max(0, contentH - rowsH);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, maxScroll));

        // Permission data snapshots
        Map<String, Boolean> defaults = BotPlayerInventoryScreen.getDefaultAdminPermissionGlobalsSnapshot();
        Map<String, Boolean> globals = BotPlayerInventoryScreen.getAdminPermissionGlobalsSnapshot(botAlias);
        Map<String, Map<String, Boolean>> overrides = BotPlayerInventoryScreen.getAdminPermissionUserOverridesSnapshot(botAlias);
        Map<String, Boolean> userMap = selectedUserUuid.isBlank() ? null : overrides.get(selectedUserUuid);

        // Scissor for scrollable rows
        context.enableScissor(rowsX + 1, rowsY + 1, rowsX + rowsW - 1, rowsY + rowsH - 1);

        int innerW = rowsW - SCROLLBAR_W - 4;  // leave space for scrollbar
        for (int idx = 0; idx < PERMISSIONS.size(); idx++) {
            PermissionDef def = PERMISSIONS.get(idx);
            int rowY = rowsY + idx * ROW_HEIGHT - (int) scrollOffset;
            int rowBottom = rowY + ROW_HEIGHT;

            // Skip rows entirely outside visible area
            if (rowBottom < rowsY || rowY > rowsY + rowsH) continue;

            boolean hover = mouseX >= rowsX && mouseX < rowsX + innerW
                    && mouseY >= Math.max(rowY, rowsY) && mouseY < Math.min(rowBottom, rowsY + rowsH)
                    && mouseY >= rowY && mouseY < rowBottom;
            context.fill(rowsX + 1, rowY + 1, rowsX + 1 + innerW, rowBottom - 1,
                    hover ? 0xFF1F1F1F : 0xFF171717);

            // Row separator
            if (idx > 0) {
                context.fill(rowsX + 1, rowY, rowsX + 1 + innerW, rowY + 1, 0x18FFFFFF);
            }

            boolean globalValue = globals.getOrDefault(def.key(), defaults.getOrDefault(def.key(), false));
            boolean hasOverride = userMap != null && userMap.containsKey(def.key());
            Boolean overrideValue = hasOverride && userMap != null ? userMap.get(def.key()) : null;
            boolean effectiveValue = hasOverride ? Boolean.TRUE.equals(overrideValue) : globalValue;

            // Label (full width, no elision needed with wider panel)
            int labelMaxW = innerW - TOGGLE_W - 70;
            String label = elideToWidth(def.label(), Math.max(20, labelMaxW));
            context.drawText(this.textRenderer, label, rowsX + 6, rowY + 6, 0xFFD8C7A0, false);

            // Source tag
            String source = selectedUserUuid.isBlank() ? "Global" : (hasOverride ? "Override" : "Global");
            int sourceW = this.textRenderer.getWidth("[" + source + "]");
            context.drawText(this.textRenderer, "[" + source + "]",
                    rowsX + 1 + innerW - TOGGLE_W - sourceW - 12, rowY + 6, 0xFF8E8E8E, false);

            // Toggle chip
            int toggleX = rowsX + 1 + innerW - TOGGLE_W - 6;
            int toggleY = rowY + 3;
            int toggleH = ROW_HEIGHT - 6;
            int onColor = 0xFF2E5A2E;
            int offColor = 0xFF4A2A2A;
            boolean toggleHover = mouseX >= toggleX && mouseX < toggleX + TOGGLE_W
                    && mouseY >= toggleY && mouseY < toggleY + toggleH;
            int chipColor = effectiveValue ? onColor : offColor;
            if (toggleHover) chipColor = effectiveValue ? 0xFF3C713C : 0xFF613333;
            context.fill(toggleX, toggleY, toggleX + TOGGLE_W, toggleY + toggleH, chipColor);
            context.fill(toggleX, toggleY, toggleX + TOGGLE_W, toggleY + 1, 0xFF000000);
            context.fill(toggleX, toggleY + toggleH - 1, toggleX + TOGGLE_W, toggleY + toggleH, 0xFF000000);
            context.fill(toggleX, toggleY, toggleX + 1, toggleY + toggleH, 0xFF000000);
            context.fill(toggleX + TOGGLE_W - 1, toggleY, toggleX + TOGGLE_W, toggleY + toggleH, 0xFF000000);
            String state = effectiveValue ? "ON" : "OFF";
            int stateX = toggleX + (TOGGLE_W - this.textRenderer.getWidth(state)) / 2;
            context.drawText(this.textRenderer, state, stateX, rowY + 6, 0xFFEFEFEF, false);

            // Tooltip on hover
            if (hover && tooltipText == null) {
                updateTooltipCandidate("permission:" + idx, def.hint(), mouseX, mouseY);
            }
        }

        context.disableScissor();

        if (!tooltipHoverSeenThisFrame) {
            tooltipHoverKey = null;
            tooltipHoverStartMs = 0L;
        }

        // Scrollbar
        if (maxScroll > 0) {
            int trackX = rowsX + rowsW - SCROLLBAR_W;
            int trackT = rowsY + 1;
            int trackB = rowsY + rowsH - 1;
            int trackH = trackB - trackT;
            context.fill(trackX, trackT, trackX + SCROLLBAR_W, trackB, 0xFF1A1A1A);

            int[] thumb = computeThumb(trackT, trackH, maxScroll);
            if (thumb != null) {
                boolean thumbHover = mouseX >= trackX - 2 && mouseX < trackX + SCROLLBAR_W + 2
                        && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
                int col = (thumbHover || draggingScroll) ? 0xFF606060 : 0xFF404040;
                context.fill(trackX + 1, thumb[0], trackX + SCROLLBAR_W - 1,
                        thumb[0] + thumb[1], col);
            }
        }

        // Flash message (self-lock denial)
        if (flashMessage != null && System.currentTimeMillis() < flashExpire) {
            int fw = this.textRenderer.getWidth(flashMessage);
            int fx = (this.width - fw) / 2;
            int fy = panelY + panelH + 4;
            context.fill(fx - 4, fy - 2, fx + fw + 4, fy + this.textRenderer.fontHeight + 2, 0xE0300000);
            context.drawText(this.textRenderer, flashMessage, fx, fy, 0xFFFF6666, false);
        } else {
            flashMessage = null;
        }

        super.render(context, mouseX, mouseY, delta);

        // Tooltip rendered last (on top of everything)
        if (tooltipText != null) {
            renderTooltip(context, tooltipText, tooltipX, tooltipY);
        }
    }

    // ── Scrollbar computation ─────────────────────────────────────────────

    private int[] computeThumb(int trackT, int trackH, int maxScroll) {
        if (maxScroll <= 0 || trackH <= 0) return null;
        int contentH = PERMISSIONS.size() * ROW_HEIGHT;
        int viewH = rowsRect != null ? rowsRect.h : trackH;
        int thumbH = Math.max(THUMB_MIN_H, trackH * viewH / Math.max(1, contentH));
        thumbH = Math.min(trackH, thumbH);
        int scrollRange = trackH - thumbH;
        int thumbY = trackT + (int) (scrollRange * scrollOffset / maxScroll);
        return new int[]{thumbY, thumbH};
    }

    // ── Tooltip ───────────────────────────────────────────────────────────

    private void updateTooltipCandidate(String key, String text, int mouseX, int mouseY) {
        if (key == null || key.isEmpty() || text == null || text.isEmpty()) {
            return;
        }
        tooltipHoverSeenThisFrame = true;
        long now = System.currentTimeMillis();
        if (!key.equals(tooltipHoverKey)) {
            tooltipHoverKey = key;
            tooltipHoverStartMs = now;
            return;
        }
        if (now - tooltipHoverStartMs >= TOOLTIP_DELAY_MS) {
            tooltipText = text;
            tooltipX = mouseX + 12;
            tooltipY = mouseY - 10;
        }
    }

    private void resetTooltipHoverState() {
        tooltipText = null;
        tooltipHoverKey = null;
        tooltipHoverSeenThisFrame = false;
        tooltipHoverStartMs = System.currentTimeMillis();
    }

    private void renderTooltip(DrawContext context, String text, int x, int y) {
        if (text == null || text.isEmpty()) return;
        // Word-wrap long tooltips
        int maxTooltipW = Math.min(240, this.width - 20);
        List<String> lines = wrapText(text, maxTooltipW);
        int lineH = this.textRenderer.fontHeight + 1;
        int pad = 4;
        int maxLineW = 0;
        for (String line : lines) {
            maxLineW = Math.max(maxLineW, this.textRenderer.getWidth(line));
        }
        int boxW = maxLineW + pad * 2;
        int boxH = lines.size() * lineH + pad * 2 - 1;
        int tx = Math.min(x, this.width - boxW - 2);
        int ty = Math.max(y, 2);
        if (ty + boxH > this.height - 2) ty = this.height - boxH - 2;
        context.fill(tx - 1, ty - 1, tx + boxW + 1, ty + boxH + 1, 0xFF000000);
        context.fill(tx, ty, tx + boxW, ty + boxH, 0xF0200E00);
        context.fill(tx, ty, tx + boxW, ty + 1, 0xFF504020);
        context.fill(tx, ty + boxH - 1, tx + boxW, ty + boxH, 0xFF504020);
        context.fill(tx, ty, tx + 1, ty + boxH, 0xFF504020);
        context.fill(tx + boxW - 1, ty, tx + boxW, ty + boxH, 0xFF504020);
        for (int i = 0; i < lines.size(); i++) {
            context.drawText(this.textRenderer, lines.get(i), tx + pad, ty + pad + i * lineH, 0xFFEFEFEF, false);
        }
    }

    private List<String> wrapText(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String test = current.isEmpty() ? word : current + " " + word;
            if (this.textRenderer.getWidth(test) > maxW && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(test);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    // ── Chip / button rendering ───────────────────────────────────────────

    private void drawSmallButton(DrawContext context, Rect rect, String label, int mouseX, int mouseY) {
        boolean hover = rect.contains(mouseX, mouseY);
        context.fill(rect.x, rect.y, rect.right(), rect.bottom(), hover ? 0xFF2A2A2A : 0xFF1A1A1A);
        context.fill(rect.x, rect.y, rect.right(), rect.y + 1, 0xFF000000);
        context.fill(rect.x, rect.bottom() - 1, rect.right(), rect.bottom(), 0xFF000000);
        context.fill(rect.x, rect.y, rect.x + 1, rect.bottom(), 0xFF000000);
        context.fill(rect.right() - 1, rect.y, rect.right(), rect.bottom(), 0xFF000000);

        int tx = rect.x + (rect.w - this.textRenderer.getWidth(label)) / 2;
        int ty = rect.y + (rect.h - this.textRenderer.fontHeight) / 2;
        context.drawText(this.textRenderer, label, tx, ty, 0xFFD8C7A0, false);
    }

    // ── Permission toggle with self-lock protection ───────────────────────

    private void togglePermission(String permissionKey) {
        Map<String, Boolean> defaults = BotPlayerInventoryScreen.getDefaultAdminPermissionGlobalsSnapshot();
        Map<String, Boolean> globals = BotPlayerInventoryScreen.getAdminPermissionGlobalsSnapshot(botAlias);
        Map<String, Map<String, Boolean>> overrides = BotPlayerInventoryScreen.getAdminPermissionUserOverridesSnapshot(botAlias);

        boolean globalValue = globals.getOrDefault(permissionKey, defaults.getOrDefault(permissionKey, false));

        if (selectedUserUuid.isBlank()) {
            // Global scope toggle
            boolean newValue = !globalValue;
            if (!newValue && isSelfLockKey(permissionKey)) {
                showSelfLockDenied(permissionKey);
                return;
            }
            BotPlayerInventoryScreen.sendAdminPermissionGlobalUpdate(botAlias, permissionKey, newValue);
            BotPlayerInventoryScreen.requestAdminPermissionsSnapshot(botAlias);
            return;
        }

        // Per-player scope toggle
        Map<String, Boolean> userMap = overrides.get(selectedUserUuid);
        boolean effective = userMap != null && userMap.containsKey(permissionKey)
                ? Boolean.TRUE.equals(userMap.get(permissionKey))
                : globalValue;
        boolean newValue = !effective;

        // Self-lock check: is the admin editing their own user entry?
        if (!newValue && isSelfLockKey(permissionKey) && isEditingSelf()) {
            showSelfLockDenied(permissionKey);
            return;
        }

        BotPlayerInventoryScreen.sendAdminPermissionUserUpdate(botAlias, selectedUserUuid, permissionKey, newValue);
        BotPlayerInventoryScreen.requestAdminPermissionsSnapshot(botAlias);
    }

    private boolean isSelfLockKey(String key) {
        return SELF_LOCK_KEYS.contains(key);
    }

    private boolean isEditingSelf() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return false;
        var player = mc.player;
        String uuidString = player != null ? player.getUuidAsString() : null;
        if (uuidString == null || uuidString.isBlank()) return false;
        String myUuid = uuidString.toLowerCase(Locale.ROOT);
        return myUuid.equalsIgnoreCase(selectedUserUuid);
    }

    private void showSelfLockDenied(String key) {
        flashMessage = "Cannot disable \"" + key + "\" for yourself!";
        flashExpire = System.currentTimeMillis() + 2500;
        // Also show a toast for extra visibility
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.getToastManager().add(SystemToast.create(mc,
                    SystemToast.Type.NARRATOR_TOGGLE,
                    Text.of("Self-lock prevented"),
                    Text.of("You can't disable your own admin access")));
        }
    }

    // ── Scope navigation ──────────────────────────────────────────────────

    private void cycleScope(int direction) {
        refreshKnownUsers();
        int count = 1 + knownUsers.size();
        if (count <= 1) {
            selectedUserUuid = "";
            return;
        }

        int currentIndex = 0;
        if (!selectedUserUuid.isBlank()) {
            for (int i = 0; i < knownUsers.size(); i++) {
                if (knownUsers.get(i).uuid().equalsIgnoreCase(selectedUserUuid)) {
                    currentIndex = i + 1;
                    break;
                }
            }
        }

        int next = Math.floorMod(currentIndex + direction, count);
        selectedUserUuid = next == 0 ? "" : knownUsers.get(next - 1).uuid();
        scrollOffset = 0;  // reset scroll on scope change
    }

    private String scopeDisplayLabel() {
        if (selectedUserUuid == null || selectedUserUuid.isBlank()) {
            return "Scope: Global Defaults";
        }
        for (UserRef user : knownUsers) {
            if (user.uuid().equalsIgnoreCase(selectedUserUuid)) {
                return "Scope: " + user.name() + " (" + shortUuid(user.uuid()) + ")";
            }
        }
        return "Scope: " + shortUuid(selectedUserUuid);
    }

    private void refreshKnownUsers() {
        Map<String, String> known = BotPlayerInventoryScreen.getAdminPermissionKnownUsersSnapshot(botAlias);
        List<UserRef> users = new ArrayList<>();
        if (known != null && !known.isEmpty()) {
            for (Map.Entry<String, String> entry : known.entrySet()) {
                String uuid = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
                String name = entry.getValue() == null ? "" : entry.getValue().trim();
                if (uuid.isBlank() || name.isBlank()) {
                    continue;
                }
                users.add(new UserRef(uuid, name));
            }
        }
        users.sort(Comparator.comparing(UserRef::name, String.CASE_INSENSITIVE_ORDER).thenComparing(UserRef::uuid));
        knownUsers = List.copyOf(users);

        if (!selectedUserUuid.isBlank()) {
            boolean exists = false;
            for (UserRef user : knownUsers) {
                if (user.uuid().equalsIgnoreCase(selectedUserUuid)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                selectedUserUuid = "";
            }
        }
    }

    // ── Text utilities ────────────────────────────────────────────────────

    private String shortUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "unknown";
        }
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }

    private String elideToWidth(String text, int maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        int ellipsisW = this.textRenderer.getWidth(ellipsis);
        if (ellipsisW >= maxWidth) return ellipsis;

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

    // ── Records ───────────────────────────────────────────────────────────

    private record PermissionDef(String key, String label, String hint) {}

    private record UserRef(String uuid, String name) {}

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
