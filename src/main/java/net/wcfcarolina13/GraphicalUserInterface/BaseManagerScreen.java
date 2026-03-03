package net.wcfcarolina13.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.network.BaseClaimWallPayload;
import net.wcfcarolina13.network.BaseGoToPayload;
import net.wcfcarolina13.network.BaseGrantWallAccessPayload;
import net.wcfcarolina13.network.BaseRemovePayload;
import net.wcfcarolina13.network.BaseRevokeWallAccessPayload;
import net.wcfcarolina13.network.BaseRenamePayload;
import net.wcfcarolina13.network.BaseSetPayload;
import net.wcfcarolina13.network.BaseSetHomePayload;
import net.wcfcarolina13.network.BaseUnclaimWallPayload;
import net.wcfcarolina13.network.RequestBasesPayload;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Simple bases manager window (list/set/remove/rename) opened from the shared bot inventory screen.
 *
 * <p>Backed by lightweight networking payloads; no chat parsing required.</p>
 */
public class BaseManagerScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type BASE_LIST_TYPE = new TypeToken<List<BaseDto>>() {}.getType();

    public record BaseDto(String label, int x, int y, int z, boolean home, String wallStatus, String ownerName) {
        /** True when this entry represents a fortification wall rather than a simple base. */
        boolean isWall() { return wallStatus != null && !wallStatus.isBlank(); }
    }

    private static List<BaseDto> LAST_BASES = List.of();

    public static void applyBasesJson(String json) {
        if (json == null) {
            LAST_BASES = List.of();
            return;
        }
        try {
            List<BaseDto> parsed = GSON.fromJson(json, BASE_LIST_TYPE);
            LAST_BASES = parsed != null ? parsed : List.of();
        } catch (Exception ignored) {
            LAST_BASES = List.of();
        }
    }

    private final Screen parent;
    private final String botAlias;

    private TextFieldWidget nameField;

    private int scroll;
    private int selectedIndex = -1;

    private static final int ROW_H = 12;

    // Layout constants (keep in sync with init() / listRect()).
    private static final int TOP_Y = 28;
    private static final int SECTION_LABEL_H = 14;
    private static final int SECTION_GAP = 6;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_ROW_GAP = 2;
    private static final int LIST_TOP_GAP = 6;
    private static final int LIST_BOTTOM_MARGIN = 32;
    private static final int LIST_MIN_H = 60;

    /** Tracks the Y coordinate after all button rows for list positioning. */
    private int controlsBottomY;

    /** Section header labels to draw (populated in init). */
    private final java.util.List<int[]> sectionHeaders = new java.util.ArrayList<>(); // [x, y, color], text stored separately
    private final java.util.List<String> sectionHeaderTexts = new java.util.ArrayList<>();

    public BaseManagerScreen(Screen parent) {
        this(parent, "");
    }

    public BaseManagerScreen(Screen parent, String botAlias) {
        super(Text.literal("Bases"));
        this.parent = parent;
        this.botAlias = botAlias != null ? botAlias.trim() : "";
    }

    @Override
    protected void init() {
        sectionHeaders.clear();
        sectionHeaderTexts.clear();
        int cx = this.width / 2;
        int y = TOP_Y;
        int colW = 70;
        int leftX = cx - 110;
        int midX = cx - 36;
        int rightX = cx + 38;

        // ── Text field ──────────────────────────────────────────────────
        this.nameField = new TextFieldWidget(this.textRenderer, leftX, y, 220, 18, Text.literal("Base name"));
        this.nameField.setMaxLength(64);
        this.addDrawableChild(this.nameField);
        y += 22;

        // ── Section 1: Base Management ──────────────────────────────────
        addSectionHeader(leftX, y, "\u2302 Base Management", 0xFFE6D7A3);
        y += SECTION_LABEL_H;

        addBtn(leftX, y, colW, "Set here",   "Save your current position as a named base (type name above first)",     () -> sendSetHere());
        addBtn(midX,  y, colW, "Rename",     "Rename the selected base (type new name above, select base below)",       () -> sendRenameSelected());
        addBtn(rightX,y, colW, "Remove",     "Delete the selected base permanently",                                    () -> sendRemoveSelected());
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn(leftX, y, colW, "Set Home",   "Mark the selected base as this bot's home (where it returns at sunset)",  () -> sendSetHomeSelected());
        addBtn(midX,  y, colW, "Go To Base", "Send the bot to walk to the selected base",                               () -> sendGoToSelected());
        addBtn(rightX,y, colW, "Refresh",    "Reload the base list from the server",                                    () -> requestRefresh());
        y += BUTTON_H + SECTION_GAP;

        // ── Section 2: Fortification ────────────────────────────────────
        addSectionHeader(leftX, y, "\u2694 Fortification", 0xFFD4A3E6);
        y += SECTION_LABEL_H;

        addBtn(leftX, y, colW, "Fortify New",  "Start building a new wall around the nearest village",                  () -> sendFortifyNew());
        addBtn(midX,  y, colW, "Resume Wall",  "Resume building an unfinished wall (select a wall below)",              () -> sendResumeWall());
        addBtn(rightX,y, colW, "Patch Wall",   "Repair damaged or missing blocks in the selected wall",                 () -> sendPatchWall());
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn(leftX, y, colW, "Auto Patch",   "Like Patch, but automatically repeats until the wall is fully repaired",() -> sendAutoPatchWall());
        addBtn(midX,  y, colW, "Wall Status",  "Show completion % and missing block breakdown for the selected wall",   () -> sendWallStatus());
        addBtn(rightX,y, colW, "Dig Moat",     "Dig a defensive moat around the selected wall perimeter",               () -> sendDigMoat());
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn(leftX, y, colW, "Drift Check",  "Check if the village center has moved away from the wall",              () -> sendDriftCheckWall());
        addBtn(midX,  y, colW, "Expand Wall",  "Expand the selected wall to cover newly detected village boundaries",   () -> sendExpandWall());
        y += BUTTON_H + SECTION_GAP;

        // ── Section 3: Ownership & Access ───────────────────────────────
        addSectionHeader(leftX, y, "\uD83D\uDD11 Ownership", 0xFFA3E6B4);
        y += SECTION_LABEL_H;

        addBtn(leftX, y, colW, "Claim Wall",   "Claim ownership of the selected wall (ties it to your player)",         () -> sendClaimWall());
        addBtn(midX,  y, colW, "Unclaim",      "Release ownership of the selected wall so anyone can claim it",         () -> sendUnclaimWall());
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn(leftX, y, colW, "Permit",       "Grant another player build access to your wall (type their name above)",() -> sendPermitWallAccess());
        addBtn(midX,  y, colW, "Revoke",       "Remove a player's build access from your wall (type their name above)", () -> sendRevokeWallAccess());
        y += BUTTON_H + SECTION_GAP;

        // ── Footer ─────────────────────────────────────────────────────
        addBtn(rightX, y, colW, "Close",       "Close this screen and return to the inventory",                          () -> close());
        y += BUTTON_H + 4;

        controlsBottomY = y;
        requestRefresh();
    }

    /** Adds a section header label to be drawn later. */
    private void addSectionHeader(int x, int y, String text, int color) {
        sectionHeaders.add(new int[]{x, y, color});
        sectionHeaderTexts.add(text);
    }

    /** Convenience: creates a button with tooltip and adds it as a child. */
    private void addBtn(int x, int y, int w, String label, String tooltip, Runnable action) {
        ButtonWidget btn = ButtonWidget.builder(Text.literal(label), b -> action.run())
                .dimensions(x, y, w, BUTTON_H)
                .build();
        btn.setTooltip(Tooltip.of(Text.literal(tooltip)));
        this.addDrawableChild(btn);
    }

    private void requestRefresh() {
        if (ClientPlayNetworking.canSend(RequestBasesPayload.ID)) {
            ClientPlayNetworking.send(new RequestBasesPayload(botAlias));
        }
    }

    private void sendSetHere() {
        String label = nameField != null ? nameField.getText() : "";
        if (label == null || label.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseSetPayload.ID)) {
            ClientPlayNetworking.send(new BaseSetPayload(label));
        }
        requestRefresh();
    }

    private void sendRemoveSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseRemovePayload.ID)) {
            ClientPlayNetworking.send(new BaseRemovePayload(selected.label));
        }
        requestRefresh();
    }

    private void sendRenameSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            return;
        }
        String newLabel = nameField != null ? nameField.getText() : "";
        if (newLabel == null || newLabel.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseRenamePayload.ID)) {
            ClientPlayNetworking.send(new BaseRenamePayload(selected.label, newLabel));
        }
        requestRefresh();
    }

    private void sendSetHomeSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank() || botAlias.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseSetHomePayload.ID)) {
            ClientPlayNetworking.send(new BaseSetHomePayload(botAlias, selected.label));
        }
        requestRefresh();
    }

    private void sendGoToSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank() || botAlias.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseGoToPayload.ID)) {
            ClientPlayNetworking.send(new BaseGoToPayload(botAlias, selected.label));
        }
        requestRefresh();
    }

    private void sendFortifyNew() {
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify");
        close();
    }

    private void sendResumeWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify resume " + selected.label);
        close();
    }

    private void sendPatchWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify patch " + selected.label);
        close();
    }

    private void sendAutoPatchWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify patch " + selected.label + " auto");
        close();
    }

    private void sendWallStatus() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify status " + selected.label);
        close();
    }

    private void sendDigMoat() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify moat " + selected.label);
        close();
    }

    private void sendDriftCheckWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify drift " + selected.label);
        close();
    }

    private void sendExpandWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify expand " + selected.label);
        close();
    }

    private void sendClaimWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseClaimWallPayload.ID)) {
            ClientPlayNetworking.send(new BaseClaimWallPayload(selected.label));
        }
        requestRefresh();
    }

    private void sendUnclaimWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseUnclaimWallPayload.ID)) {
            ClientPlayNetworking.send(new BaseUnclaimWallPayload(selected.label));
        }
        requestRefresh();
    }

    private void sendPermitWallAccess() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        String grantee = nameField != null ? nameField.getText() : "";
        if (grantee == null || grantee.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseGrantWallAccessPayload.ID)) {
            ClientPlayNetworking.send(new BaseGrantWallAccessPayload(selected.label, grantee.trim()));
        }
        requestRefresh();
    }

    private void sendRevokeWallAccess() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        String grantee = nameField != null ? nameField.getText() : "";
        if (grantee == null || grantee.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseRevokeWallAccessPayload.ID)) {
            ClientPlayNetworking.send(new BaseRevokeWallAccessPayload(selected.label, grantee.trim()));
        }
        requestRefresh();
    }

    private static void sendChatCommand(MinecraftClient client, String command) {
        if (client == null) {
            return;
        }
        var player = client.player;
        if (player == null || player.networkHandler == null) {
            return;
        }
        player.networkHandler.sendChatCommand(command);
    }

    private BaseDto getSelected() {
        List<BaseDto> bases = getBasesSnapshot();
        if (selectedIndex < 0 || selectedIndex >= bases.size()) {
            return null;
        }
        return bases.get(selectedIndex);
    }

    private static List<BaseDto> getBasesSnapshot() {
        List<BaseDto> bases = LAST_BASES;
        return bases != null ? bases : List.of();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Rect list = listRect();
        if (list.contains(mouseX, mouseY)) {
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                int maxScroll = Math.max(0, getBasesSnapshot().size() - visibleRows(list.h));
                scroll = MathHelper.clamp(scroll + delta, 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        Rect list = listRect();
        if (list.contains(click.x(), click.y())) {
            int row = (int) ((click.y() - list.y) / ROW_H);
            List<BaseDto> bases = getBasesSnapshot();
            int idx = scroll + row;
            if (idx >= 0 && idx < bases.size()) {
                selectedIndex = idx;
                return true;
            }
        }
        return super.mouseClicked(click, isInside);
    }

    @Override
    public void close() {
        MinecraftClient client = this.client;
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 10, 0xFFFFFF);

        // Draw section headers
        for (int i = 0; i < sectionHeaders.size(); i++) {
            int[] hdr = sectionHeaders.get(i);
            String text = sectionHeaderTexts.get(i);
            context.drawTextWithShadow(this.textRenderer, text, hdr[0], hdr[1] + 2, hdr[2]);
            // Subtle separator line under the label
            int lineW = this.textRenderer.getWidth(text);
            context.fill(hdr[0], hdr[1] + SECTION_LABEL_H - 2, hdr[0] + lineW, hdr[1] + SECTION_LABEL_H - 1, (hdr[2] & 0x00FFFFFF) | 0x44000000);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);

        Rect list = listRect();
        context.fill(list.x, list.y, list.right(), list.bottom(), 0xAA101010);

        List<BaseDto> bases = getBasesSnapshot();
        int rows = visibleRows(list.h);
        int maxScroll = Math.max(0, bases.size() - rows);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        for (int i = 0; i < rows; i++) {
            int idx = scroll + i;
            if (idx >= bases.size()) break;
            BaseDto b = bases.get(idx);
            int rowY = list.y + i * ROW_H;
            boolean selected = idx == selectedIndex;
            int bg = selected ? 0xFF3A2C14 : 0xFF1A1A1A;
            context.fill(list.x + 2, rowY + 1, list.right() - 2, rowY + ROW_H - 1, bg);

            String rawLabel = b != null && b.label != null ? b.label : "(unnamed)";
            String prefix = "";
            if (b != null && b.home) prefix = "[Home] ";
            if (b != null && b.isWall()) prefix = "§d[Wall] " + prefix;
            String label = prefix + rawLabel;
            // Compact right-side info: XZ only for walls (Y is less useful), include status
            String rightInfo;
            if (b != null && b.isWall()) {
                String owner = (b.ownerName != null && !b.ownerName.isBlank()) ? b.ownerName : "Unclaimed";
                rightInfo = "(" + b.x + "," + b.z + ") §7[" + b.wallStatus + "] §6{" + owner + "}";
            } else {
                rightInfo = b != null ? ("(" + b.x + ", " + b.y + ", " + b.z + ")") : "";
            }
            int rightW = this.textRenderer.getWidth(rightInfo);
            // Truncate label if it would overlap with right info (leave 8px gap)
            int maxLabelW = list.w - 12 - rightW - 8;
            String drawLabel = label;
            if (this.textRenderer.getWidth(drawLabel) > maxLabelW && maxLabelW > 20) {
                drawLabel = this.textRenderer.trimToWidth(drawLabel, maxLabelW - this.textRenderer.getWidth("..")) + "..";
            }
            context.drawTextWithShadow(this.textRenderer, drawLabel, list.x + 6, rowY + 2, 0xFFEFEFEF);
            context.drawTextWithShadow(this.textRenderer, rightInfo, list.right() - 6 - rightW, rowY + 2, 0xFFB0B0B0);
        }
    }

    private Rect listRect() {
        int cx = this.width / 2;
        int x = cx - 110;
        int y = controlsBottomY + LIST_TOP_GAP;
        int w = 220;
        int h = Math.max(LIST_MIN_H, this.height - y - LIST_BOTTOM_MARGIN);
        return new Rect(x, y, w, h);
    }

    private static int visibleRows(int h) {
        return Math.max(1, h / ROW_H);
    }

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
