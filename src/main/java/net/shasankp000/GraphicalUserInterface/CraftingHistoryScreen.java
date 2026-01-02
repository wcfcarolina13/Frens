package net.shasankp000.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.shasankp000.Network.CraftingHistoryPayload;
import net.shasankp000.Network.RequestCraftingHistoryPayload;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Crafting history window (per-world, commander-crafted list).
 * Mirrors the Bases menu surface from the Topics panel.
 */
public class CraftingHistoryScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private record CraftEntry(String id, String label) {}

    private static List<CraftEntry> LAST_HISTORY = List.of();

    public static void applyHistoryJson(String json) {
        if (json == null) {
            LAST_HISTORY = List.of();
            return;
        }
        try {
            List<String> parsed = GSON.fromJson(json, STRING_LIST_TYPE);
            if (parsed == null) {
                LAST_HISTORY = List.of();
                return;
            }
            List<CraftEntry> out = new ArrayList<>(parsed.size());
            for (String idStr : parsed) {
                if (idStr == null || idStr.isBlank()) {
                    continue;
                }
                String label = idStr;
                try {
                    Identifier id = Identifier.of(idStr);
                    var item = Registries.ITEM.get(id);
                    if (item != null && item != net.minecraft.item.Items.AIR) {
                        label = item.getName().getString();
                    }
                } catch (Exception ignored) {
                }
                out.add(new CraftEntry(idStr, label));
            }
            LAST_HISTORY = out;
        } catch (Exception ignored) {
            LAST_HISTORY = List.of();
        }
    }

    private final Screen parent;
    private TextFieldWidget amountField;
    private int scroll;
    private int selectedIndex = -1;
    private static final int ROW_H = 12;

    private static final int TOP_Y = 28;
    private static final int CONTROL_ROW_DY = 24;
    private static final int BUTTON_H = 20;
    private static final int LIST_TOP_GAP = 6;
    private static final int LIST_BOTTOM_MARGIN = 32;
    private static final int LIST_MIN_H = 60;

    public CraftingHistoryScreen(Screen parent) {
        super(Text.literal("Crafting"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = TOP_Y;

        int btnY = top;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), (btn) -> requestRefresh())
                .dimensions(cx - 160, btnY, 70, BUTTON_H)
                .build());
        this.amountField = new TextFieldWidget(this.textRenderer, cx - 84, btnY + 1, 40, BUTTON_H - 2, Text.literal("Amt"));
        this.amountField.setMaxLength(3);
        this.amountField.setText("1");
        this.addDrawableChild(this.amountField);
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Craft"), (btn) -> craftSelected())
                .dimensions(cx - 36, btnY, 60, BUTTON_H)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), (btn) -> close())
                .dimensions(cx + 30, btnY, 70, BUTTON_H)
                .build());

        requestRefresh();
    }

    private void requestRefresh() {
        if (ClientPlayNetworking.canSend(RequestCraftingHistoryPayload.ID)) {
            ClientPlayNetworking.send(new RequestCraftingHistoryPayload(""));
        }
    }

    private static List<CraftEntry> getHistorySnapshot() {
        List<CraftEntry> history = LAST_HISTORY;
        return history != null ? history : List.of();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Rect list = listRect();
        if (list.contains(mouseX, mouseY)) {
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                int maxScroll = Math.max(0, getHistorySnapshot().size() - visibleRows(list.h));
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
            int row = (int) ((click.y() - list.y - 2) / ROW_H);
            if (row >= 0) {
                int idx = scroll + row;
                List<CraftEntry> entries = getHistorySnapshot();
                if (idx >= 0 && idx < entries.size()) {
                    selectedIndex = idx;
                    return true;
                }
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

        Rect list = listRect();
        context.fill(list.x, list.y, list.x + list.w, list.y + list.h, 0x2F000000);
        drawBorder(context, list, 0xFFB8A76A);

        List<CraftEntry> entries = getHistorySnapshot();
        int visible = visibleRows(list.h);
        clampScroll(visible);
        int start = scroll;
        int end = Math.min(entries.size(), start + visible);
        int rowY = list.y + 2;
        for (int i = start; i < end; i++) {
            CraftEntry entry = entries.get(i);
            if (i == selectedIndex) {
                context.fill(list.x + 1, rowY, list.x + list.w - 1, rowY + ROW_H, 0x553A2C14);
            }
            int color = 0xFFE6D7A3;
            context.drawText(this.textRenderer, entry.label(), list.x + 6, rowY + 1, color, false);
            rowY += ROW_H;
        }

        if (entries.isEmpty()) {
            context.drawText(this.textRenderer, "No crafting history yet.", list.x + 6, list.y + 6, 0xFFB8A76A, false);
        }
    }

    private Rect listRect() {
        int cx = this.width / 2;
        int listY = TOP_Y + CONTROL_ROW_DY + LIST_TOP_GAP;
        int listW = 220;
        int listH = MathHelper.clamp(this.height - listY - LIST_BOTTOM_MARGIN, LIST_MIN_H, 220);
        return new Rect(cx - listW / 2, listY, listW, listH);
    }

    private int visibleRows(int listH) {
        return Math.max(1, (listH - 4) / ROW_H);
    }

    private void clampScroll(int visibleRows) {
        int maxScroll = Math.max(0, getHistorySnapshot().size() - visibleRows);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);
    }

    private void craftSelected() {
        List<CraftEntry> entries = getHistorySnapshot();
        if (selectedIndex < 0 || selectedIndex >= entries.size()) {
            return;
        }
        int amount = parseAmount();
        sendCraftCommand(entries.get(selectedIndex), amount);
    }

    private int parseAmount() {
        String raw = amountField != null ? amountField.getText() : null;
        int amount = 1;
        if (raw != null) {
            try {
                amount = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        amount = MathHelper.clamp(amount, 1, 64);
        if (amountField != null && !String.valueOf(amount).equals(raw)) {
            amountField.setText(String.valueOf(amount));
        }
        return amount;
    }

    private void sendCraftCommand(CraftEntry entry, int amount) {
        if (this.client == null || this.client.getNetworkHandler() == null || entry == null) {
            return;
        }
        String item = entry.id();
        Identifier id = Identifier.tryParse(item);
        if (id != null && "minecraft".equals(id.getNamespace())) {
            item = id.getPath();
        }
        String command = "bot craft " + item + " " + amount;
        this.client.getNetworkHandler().sendChatCommand(command);
    }

    private void drawBorder(DrawContext context, Rect list, int color) {
        int x0 = list.x;
        int y0 = list.y;
        int x1 = list.x + list.w;
        int y1 = list.y + list.h;
        context.fill(x0, y0, x1, y0 + 1, color);
        context.fill(x0, y1 - 1, x1, y1, color);
        context.fill(x0, y0, x0 + 1, y1, color);
        context.fill(x1 - 1, y0, x1, y1, color);
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
