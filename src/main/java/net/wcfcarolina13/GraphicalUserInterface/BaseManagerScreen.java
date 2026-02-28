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
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.network.BaseGoToPayload;
import net.wcfcarolina13.network.BaseRemovePayload;
import net.wcfcarolina13.network.BaseRenamePayload;
import net.wcfcarolina13.network.BaseSetPayload;
import net.wcfcarolina13.network.BaseSetHomePayload;
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

    public record BaseDto(String label, int x, int y, int z, boolean home, String wallStatus) {
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
    private static final int CONTROL_ROW_DY = 24;
    private static final int BUTTON_H = 20;
    private static final int LIST_TOP_GAP = 6;
    private static final int LIST_BOTTOM_MARGIN = 32;
    private static final int LIST_MIN_H = 60;

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
        int cx = this.width / 2;
        int top = TOP_Y;

        this.nameField = new TextFieldWidget(this.textRenderer, cx - 110, top, 220, 18, Text.literal("Base name"));
        this.nameField.setMaxLength(64);
        this.addDrawableChild(this.nameField);

        int btnY = top + CONTROL_ROW_DY;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), (btn) -> requestRefresh())
                .dimensions(cx - 110, btnY, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Set here"), (btn) -> sendSetHere())
                .dimensions(cx - 36, btnY, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), (btn) -> sendRemoveSelected())
                .dimensions(cx + 38, btnY, 70, 20)
                .build());

        int btnY2 = btnY + CONTROL_ROW_DY;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), (btn) -> sendRenameSelected())
                .dimensions(cx - 110, btnY2, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Set Home"), (btn) -> sendSetHomeSelected())
                .dimensions(cx - 36, btnY2, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Go To Base"), (btn) -> sendGoToSelected())
                .dimensions(cx + 38, btnY2, 70, 20)
                .build());

        int btnY3 = btnY2 + CONTROL_ROW_DY;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Fortify New"), (btn) -> sendFortifyNew())
                .dimensions(cx - 110, btnY3, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Resume Wall"), (btn) -> sendResumeWall())
                .dimensions(cx - 36, btnY3, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Patch Wall"), (btn) -> sendPatchWall())
                .dimensions(cx + 38, btnY3, 70, 20)
                .build());

        int btnY4 = btnY3 + CONTROL_ROW_DY;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Wall Status"), (btn) -> sendWallStatus())
                .dimensions(cx - 110, btnY4, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Auto Patch"), (btn) -> sendAutoPatchWall())
                .dimensions(cx - 36, btnY4, 70, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Dig Moat"), (btn) -> sendDigMoat())
                .dimensions(cx + 38, btnY4, 70, 20)
                .build());

        int btnY5 = btnY4 + CONTROL_ROW_DY;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), (btn) -> close())
                .dimensions(cx - 35, btnY5, 70, 20)
                .build());

        requestRefresh();
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
        if (mc != null && mc.player != null) {
            mc.player.networkHandler.sendChatCommand("bot fortify");
        }
        close();
    }

    private void sendResumeWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        if (mc != null && mc.player != null) {
            mc.player.networkHandler.sendChatCommand("bot fortify resume " + selected.label);
        }
        close();
    }

    private void sendPatchWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        if (mc != null && mc.player != null) {
            mc.player.networkHandler.sendChatCommand("bot fortify patch " + selected.label);
        }
        close();
    }

    private void sendAutoPatchWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        if (mc != null && mc.player != null) {
            mc.player.networkHandler.sendChatCommand("bot fortify patch " + selected.label + " auto");
        }
        close();
    }

    private void sendWallStatus() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        if (mc != null && mc.player != null) {
            mc.player.networkHandler.sendChatCommand("bot fortify status " + selected.label);
        }
        close();
    }

    private void sendDigMoat() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            return;
        }
        MinecraftClient mc = this.client;
        if (mc != null && mc.player != null) {
            mc.player.networkHandler.sendChatCommand("bot fortify moat " + selected.label);
        }
        close();
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
                BaseDto sel = bases.get(idx);
                if (nameField != null && sel != null && sel.label != null) {
                    nameField.setText(sel.label);
                }
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
        // super.render() draws (our overridden) renderBackground first, then child widgets.
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 10, 0xFFFFFF);

        Rect list = listRect();
        String hint = "Tip: Select a wall, then use Resume, Patch, Status, or Dig Moat.";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), cx, list.bottom() + 6, 0xFFB0B0B0);
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
                rightInfo = "(" + b.x + "," + b.z + ") §7[" + b.wallStatus + "]";
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
        // Below the 5th button row (+ a small gap). Prevents list background from overlapping controls.
        int y = TOP_Y + (CONTROL_ROW_DY * 5) + BUTTON_H + LIST_TOP_GAP;
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
