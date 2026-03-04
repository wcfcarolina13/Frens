package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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
 * Scope can be switched between:
 * - Global defaults
 * - Per-player overrides (known users from server snapshot)
 */
public class AdminPlayerSettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 18;
    private static final int TOGGLE_W = 50;

    private static final List<PermissionDef> PERMISSIONS = List.of(
            new PermissionDef("open_bot_controls", "Open Bot Controls"),
            new PermissionDef("open_spells", "Open Spells"),
            new PermissionDef("open_skin_chooser", "Open Skin Chooser"),
            new PermissionDef("gameplay_tips", "Toggle Gameplay Tips"),
            new PermissionDef("idle_hobbies_anywhere", "Toggle Idle Hobbies Anywhere"),
            new PermissionDef("baritone_pathfinder", "Toggle Baritone Pathfinder"),
            new PermissionDef("skin_policy_everyone", "Allow Skin Changes for Everyone"),
            new PermissionDef("skin_policy_custom", "Allow Custom URL Skins"),
            new PermissionDef("wizard_tome", "Give Wizard's Tome"),
            new PermissionDef("learning_manage", "Manage Learning Mode"),
            new PermissionDef("recruit_manage", "Manage Recruitment"),
            new PermissionDef("recruit_reset", "Reset Recruitment"),
            new PermissionDef("village_anchor", "Manage Village Anchor"),
            new PermissionDef("stage_debug", "Set Quest Stage (Debug)")
    );

    private final Screen parent;
    private final String botAlias;

    private List<UserRef> knownUsers = List.of();
    private String selectedUserUuid = ""; // blank = global defaults
    private int scrollIndex = 0;

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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (rowsRect == null || !rowsRect.contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int visibleRows = Math.max(1, rowsRect.h / ROW_HEIGHT);
        int maxScroll = Math.max(0, PERMISSIONS.size() - visibleRows);
        if (maxScroll <= 0) {
            return true;
        }
        int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
        if (delta != 0) {
            scrollIndex = MathHelper.clamp(scrollIndex + delta, 0, maxScroll);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        double mouseX = click.x();
        double mouseY = click.y();

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

        if (rowsRect != null && rowsRect.contains(mouseX, mouseY)) {
            int row = (int) ((mouseY - rowsRect.y) / ROW_HEIGHT);
            int index = scrollIndex + row;
            if (index >= 0 && index < PERMISSIONS.size()) {
                PermissionDef def = PERMISSIONS.get(index);
                if (def != null) {
                    togglePermission(def.key());
                }
            }
            return true;
        }

        return super.mouseClicked(click, isInside);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        refreshKnownUsers();

        int panelW = Math.min(460, this.width - 24);
        int panelH = Math.min(370, this.height - 24);
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101010);
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF000000);
        context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF000000);
        context.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF000000);
        context.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF000000);

        context.drawText(this.textRenderer, "Player Permissions • " + botAlias, panelX + 10, panelY + 8, 0xFFFFE08A, false);
        context.drawText(this.textRenderer, "Set global defaults, then add per-player overrides.", panelX + 10, panelY + 22, 0xFFD8C7A0, false);

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

        int rowsX = panelX + 10;
        int rowsY = panelY + 76;
        int rowsW = panelW - 20;
        int rowsH = panelH - 88;
        rowsRect = new Rect(rowsX, rowsY, rowsW, rowsH);

        context.fill(rowsX, rowsY, rowsX + rowsW, rowsY + rowsH, 0xAA141414);
        context.fill(rowsX, rowsY, rowsX + rowsW, rowsY + 1, 0xFF2C2C2C);
        context.fill(rowsX, rowsY + rowsH - 1, rowsX + rowsW, rowsY + rowsH, 0xFF2C2C2C);
        context.fill(rowsX, rowsY, rowsX + 1, rowsY + rowsH, 0xFF2C2C2C);
        context.fill(rowsX + rowsW - 1, rowsY, rowsX + rowsW, rowsY + rowsH, 0xFF2C2C2C);

        int visibleRows = Math.max(1, rowsH / ROW_HEIGHT);
        int maxScroll = Math.max(0, PERMISSIONS.size() - visibleRows);
        scrollIndex = MathHelper.clamp(scrollIndex, 0, maxScroll);

        Map<String, Boolean> defaults = BotPlayerInventoryScreen.getDefaultAdminPermissionGlobalsSnapshot();
        Map<String, Boolean> globals = BotPlayerInventoryScreen.getAdminPermissionGlobalsSnapshot(botAlias);
        Map<String, Map<String, Boolean>> overrides = BotPlayerInventoryScreen.getAdminPermissionUserOverridesSnapshot(botAlias);
        Map<String, Boolean> userMap = selectedUserUuid.isBlank() ? null : overrides.get(selectedUserUuid);

        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollIndex + i;
            if (idx >= PERMISSIONS.size()) {
                break;
            }
            PermissionDef def = PERMISSIONS.get(idx);
            int rowY = rowsY + i * ROW_HEIGHT;
            boolean hover = mouseX >= rowsX && mouseX < rowsX + rowsW && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            context.fill(rowsX + 1, rowY + 1, rowsX + rowsW - 1, rowY + ROW_HEIGHT - 1, hover ? 0xFF1F1F1F : 0xFF171717);

            boolean globalValue = globals.getOrDefault(def.key(), defaults.getOrDefault(def.key(), false));
            boolean hasOverride = userMap != null && userMap.containsKey(def.key());
            boolean effectiveValue = hasOverride ? Boolean.TRUE.equals(userMap.get(def.key())) : globalValue;

            int labelMaxW = rowsW - TOGGLE_W - 70;
            String label = elideToWidth(def.label(), Math.max(20, labelMaxW));
            context.drawText(this.textRenderer, label, rowsX + 6, rowY + 5, 0xFFD8C7A0, false);

            String source = selectedUserUuid.isBlank() ? "Global" : (hasOverride ? "Override" : "Global");
            context.drawText(this.textRenderer, "[" + source + "]", rowsX + rowsW - TOGGLE_W - 58, rowY + 5, 0xFF8E8E8E, false);

            int toggleX = rowsX + rowsW - TOGGLE_W - 6;
            int toggleY = rowY + 2;
            int toggleH = ROW_HEIGHT - 4;
            int onColor = 0xFF2E5A2E;
            int offColor = 0xFF4A2A2A;
            context.fill(toggleX, toggleY, toggleX + TOGGLE_W, toggleY + toggleH, effectiveValue ? onColor : offColor);
            context.fill(toggleX, toggleY, toggleX + TOGGLE_W, toggleY + 1, 0xFF000000);
            context.fill(toggleX, toggleY + toggleH - 1, toggleX + TOGGLE_W, toggleY + toggleH, 0xFF000000);
            context.fill(toggleX, toggleY, toggleX + 1, toggleY + toggleH, 0xFF000000);
            context.fill(toggleX + TOGGLE_W - 1, toggleY, toggleX + TOGGLE_W, toggleY + toggleH, 0xFF000000);
            String state = effectiveValue ? "ON" : "OFF";
            int stateX = toggleX + (TOGGLE_W - this.textRenderer.getWidth(state)) / 2;
            context.drawText(this.textRenderer, state, stateX, rowY + 5, 0xFFEFEFEF, false);
        }

        if (maxScroll > 0) {
            context.drawText(this.textRenderer,
                    "Scroll: " + (scrollIndex + 1) + "/" + (maxScroll + 1),
                    rowsX + 6,
                    rowsY + rowsH - this.textRenderer.fontHeight - 2,
                    0xFF8E8E8E,
                    false);
        }

        super.render(context, mouseX, mouseY, delta);
    }

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

    private void togglePermission(String permissionKey) {
        Map<String, Boolean> defaults = BotPlayerInventoryScreen.getDefaultAdminPermissionGlobalsSnapshot();
        Map<String, Boolean> globals = BotPlayerInventoryScreen.getAdminPermissionGlobalsSnapshot(botAlias);
        Map<String, Map<String, Boolean>> overrides = BotPlayerInventoryScreen.getAdminPermissionUserOverridesSnapshot(botAlias);

        boolean globalValue = globals.getOrDefault(permissionKey, defaults.getOrDefault(permissionKey, false));
        if (selectedUserUuid.isBlank()) {
            BotPlayerInventoryScreen.sendAdminPermissionGlobalUpdate(botAlias, permissionKey, !globalValue);
            BotPlayerInventoryScreen.requestAdminPermissionsSnapshot(botAlias);
            return;
        }

        Map<String, Boolean> userMap = overrides.get(selectedUserUuid);
        boolean effective = userMap != null && userMap.containsKey(permissionKey)
                ? Boolean.TRUE.equals(userMap.get(permissionKey))
                : globalValue;
        BotPlayerInventoryScreen.sendAdminPermissionUserUpdate(botAlias, selectedUserUuid, permissionKey, !effective);
        BotPlayerInventoryScreen.requestAdminPermissionsSnapshot(botAlias);
    }

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

    private String shortUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return "unknown";
        }
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }

    private String elideToWidth(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisW = this.textRenderer.getWidth(ellipsis);
        if (ellipsisW >= maxWidth) {
            return ellipsis;
        }

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

    private record PermissionDef(String key, String label) {}

    private record UserRef(String uuid, String name) {}

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
