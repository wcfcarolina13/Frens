package net.shasankp000.GraphicalUserInterface;

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
import net.shasankp000.Network.BaseRemovePayload;
import net.shasankp000.Network.BaseRenamePayload;
import net.shasankp000.Network.BaseSetPayload;
import net.shasankp000.Network.RequestBasesPayload;

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

    public record BaseDto(String label, int x, int y, int z) {}

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
        super(Text.literal("Bases"));
        this.parent = parent;
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
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), (btn) -> close())
                .dimensions(cx + 38, btnY2, 70, 20)
                .build());

        requestRefresh();
    }

    private void requestRefresh() {
        if (ClientPlayNetworking.canSend(RequestBasesPayload.ID)) {
            ClientPlayNetworking.send(new RequestBasesPayload(""));
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
    }

    private void sendRemoveSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            return;
        }
        if (ClientPlayNetworking.canSend(BaseRemovePayload.ID)) {
            ClientPlayNetworking.send(new BaseRemovePayload(selected.label));
        }
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
        String hint = "Tip: Select a base, edit the name field, then click Rename.";
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

            String label = b != null && b.label != null ? b.label : "(unnamed)";
            String coords = b != null ? ("(" + b.x + ", " + b.y + ", " + b.z + ")") : "";
            context.drawTextWithShadow(this.textRenderer, label, list.x + 6, rowY + 2, 0xFFEFEFEF);
            int coordsW = this.textRenderer.getWidth(coords);
            context.drawTextWithShadow(this.textRenderer, coords, list.right() - 6 - coordsW, rowY + 2, 0xFFB0B0B0);
        }
    }

    private Rect listRect() {
        int cx = this.width / 2;
        int x = cx - 110;
        // Below the 2nd button row (+ a small gap). Prevents list background from overlapping controls.
        int y = TOP_Y + (CONTROL_ROW_DY * 2) + BUTTON_H + LIST_TOP_GAP;
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
