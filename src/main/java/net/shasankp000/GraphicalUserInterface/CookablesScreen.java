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
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.shasankp000.network.CookablesPayload;
import net.shasankp000.network.RequestCookablesPayload;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Cookables list window (items currently cookable by the bot).
 */
public class CookablesScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private record CookEntry(String id, String label) {}
    private record FuelEntry(String id, String label) {}

    private static List<CookEntry> LAST_COOKABLES = List.of();
    private static List<FuelEntry> LAST_FUELS = List.of();

    public static void applyCookablesJson(String json) {
        if (json == null) {
            LAST_COOKABLES = List.of();
            LAST_FUELS = List.of();
            return;
        }
        try {
            List<String> parsed = null;
            List<String> fuels = null;
            if (json.trim().startsWith("{")) {
                java.util.Map<String, Object> data = GSON.fromJson(json, java.util.Map.class);
                if (data != null) {
                    Object cookObj = data.get("cookables");
                    Object fuelObj = data.get("fuels");
                    if (cookObj instanceof java.util.List) {
                        parsed = (List<String>) cookObj;
                    }
                    if (fuelObj instanceof java.util.List) {
                        fuels = (List<String>) fuelObj;
                    }
                }
            } else {
                parsed = GSON.fromJson(json, STRING_LIST_TYPE);
            }
            if (parsed == null) {
                LAST_COOKABLES = List.of();
                LAST_FUELS = List.of();
                return;
            }
            List<CookEntry> out = new ArrayList<>(parsed.size());
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
                out.add(new CookEntry(idStr, label));
            }
            LAST_COOKABLES = out;

            List<FuelEntry> fuelOut = new ArrayList<>();
            fuelOut.add(new FuelEntry("none", "No fuel"));
            fuelOut.add(new FuelEntry("auto", "Auto fuel"));
            if (fuels != null) {
                for (String idStr : fuels) {
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
                    fuelOut.add(new FuelEntry(idStr, label));
                }
            }
            LAST_FUELS = fuelOut;
        } catch (Exception ignored) {
            LAST_COOKABLES = List.of();
            LAST_FUELS = List.of();
        }
    }

    private final Screen parent;
    private final String botTarget;
    private int scroll;
    private int selectedIndex = -1;
    private int fuelScroll;
    private int selectedFuelIndex = 0;
    private static final int ROW_H = 12;

    private static final int TOP_Y = 28;
    private static final int CONTROL_ROW_DY = 24;
    private static final int BUTTON_H = 20;
    private static final int LIST_TOP_GAP = 6;
    private static final int LIST_BOTTOM_MARGIN = 32;
    private static final int LIST_MIN_H = 60;

    public CookablesScreen(Screen parent, String botTarget) {
        super(Text.literal("Cooking"));
        this.parent = parent;
        this.botTarget = botTarget;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = TOP_Y;

        int btnY = top;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), (btn) -> requestRefresh())
                .dimensions(cx - 160, btnY, 70, BUTTON_H)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cook"), (btn) -> cookSelected())
                .dimensions(cx - 40, btnY, 50, BUTTON_H)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), (btn) -> close())
                .dimensions(cx + 18, btnY, 70, BUTTON_H)
                .build());

        requestRefresh();
    }

    private void requestRefresh() {
        if (ClientPlayNetworking.canSend(RequestCookablesPayload.ID)) {
            ClientPlayNetworking.send(new RequestCookablesPayload(""));
        }
    }

    private static List<CookEntry> getCookablesSnapshot() {
        List<CookEntry> items = LAST_COOKABLES;
        return items != null ? items : List.of();
    }

    private static List<FuelEntry> getFuelsSnapshot() {
        List<FuelEntry> fuels = LAST_FUELS;
        return fuels != null ? fuels : List.of();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Rect list = listRect();
        Rect fuelList = fuelRect();
        if (list.contains(mouseX, mouseY)) {
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                int maxScroll = Math.max(0, getCookablesSnapshot().size() - visibleRows(list.h));
                scroll = MathHelper.clamp(scroll + delta, 0, maxScroll);
                return true;
            }
        } else if (fuelList.contains(mouseX, mouseY)) {
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                int maxScroll = Math.max(0, getFuelsSnapshot().size() - visibleRows(fuelList.h));
                fuelScroll = MathHelper.clamp(fuelScroll + delta, 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        Rect list = listRect();
        Rect fuelList = fuelRect();
        if (list.contains(click.x(), click.y())) {
            int row = (int) ((click.y() - list.y - 2) / ROW_H);
            if (row >= 0) {
                int idx = scroll + row;
                List<CookEntry> entries = getCookablesSnapshot();
                if (idx >= 0 && idx < entries.size()) {
                    selectedIndex = idx;
                    return true;
                }
            }
        } else if (fuelList.contains(click.x(), click.y())) {
            int row = (int) ((click.y() - fuelList.y - 2) / ROW_H);
            if (row >= 0) {
                int idx = fuelScroll + row;
                List<FuelEntry> fuels = getFuelsSnapshot();
                if (idx >= 0 && idx < fuels.size()) {
                    selectedFuelIndex = idx;
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
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        Rect list = listRect();
        Rect fuelList = fuelRect();
        context.fill(list.x, list.y, list.x + list.w, list.y + list.h, 0x2F000000);
        drawBorder(context, list, 0xFFB8A76A);
        context.fill(fuelList.x, fuelList.y, fuelList.x + fuelList.w, fuelList.y + fuelList.h, 0x2F000000);
        drawBorder(context, fuelList, 0xFFB8A76A);

        List<CookEntry> entries = getCookablesSnapshot();
        if (selectedIndex < 0 && !entries.isEmpty()) {
            selectedIndex = 0;
        }
        List<FuelEntry> fuels = getFuelsSnapshot();
        if (selectedFuelIndex < 0 && !fuels.isEmpty()) {
            selectedFuelIndex = 0;
        }
        int visible = visibleRows(list.h);
        clampScroll(visible);
        int start = scroll;
        int end = Math.min(entries.size(), start + visible);
        int rowY = list.y + 2;
        for (int i = start; i < end; i++) {
            CookEntry entry = entries.get(i);
            if (i == selectedIndex) {
                context.fill(list.x + 1, rowY, list.x + list.w - 1, rowY + ROW_H, 0x553A2C14);
            }
            int color = 0xFFE6D7A3;
            context.drawText(this.textRenderer, entry.label(), list.x + 6, rowY + 1, color, false);
            rowY += ROW_H;
        }

        if (entries.isEmpty()) {
            context.drawText(this.textRenderer, "No cookable items.", list.x + 6, list.y + 6, 0xFFB8A76A, false);
        } else {
            int hintY = list.y + list.h + 6;
            String hint = "Select item + fuel, then Cook.";
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), this.width / 2, hintY, 0xFFB0B0B0);
        }

        int fuelVisible = visibleRows(fuelList.h);
        clampFuelScroll(fuelVisible);
        int fuelStart = fuelScroll;
        int fuelEnd = Math.min(fuels.size(), fuelStart + fuelVisible);
        int fuelY = fuelList.y + 2;
        for (int i = fuelStart; i < fuelEnd; i++) {
            FuelEntry entry = fuels.get(i);
            if (i == selectedFuelIndex) {
                context.fill(fuelList.x + 1, fuelY, fuelList.x + fuelList.w - 1, fuelY + ROW_H, 0x553A2C14);
            }
            int color = 0xFFE6D7A3;
            context.drawText(this.textRenderer, entry.label(), fuelList.x + 6, fuelY + 1, color, false);
            fuelY += ROW_H;
        }

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Cookables"), list.x + list.w / 2, list.y - 12, 0xFFE6D7A3);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Fuel"), fuelList.x + fuelList.w / 2, fuelList.y - 12, 0xFFE6D7A3);
    }

    private void cookSelected() {
        List<CookEntry> entries = getCookablesSnapshot();
        if (selectedIndex < 0 || selectedIndex >= entries.size()) {
            if (this.client != null && this.client.player != null) {
                this.client.player.sendMessage(Text.literal("Select a cookable item first."), true);
            }
            return;
        }
        FuelEntry fuel = getSelectedFuel();
        sendCookCommand(entries.get(selectedIndex), fuel);
    }

    private FuelEntry getSelectedFuel() {
        List<FuelEntry> fuels = getFuelsSnapshot();
        if (selectedFuelIndex < 0 || selectedFuelIndex >= fuels.size()) {
            return new FuelEntry("none", "No fuel");
        }
        return fuels.get(selectedFuelIndex);
    }

    private void sendCookCommand(CookEntry entry, FuelEntry fuel) {
        if (this.client == null || this.client.getNetworkHandler() == null || entry == null) {
            return;
        }
        String item = entry.id();
        Identifier id = Identifier.tryParse(item);
        if (id != null && "minecraft".equals(id.getNamespace())) {
            item = id.getPath();
        }
        String command = "bot cook " + item;
        String fuelItem = fuel != null && fuel.id() != null ? fuel.id() : "none";
        if (!"auto".equalsIgnoreCase(fuelItem) && !"none".equalsIgnoreCase(fuelItem)) {
            Identifier fuelId = Identifier.tryParse(fuelItem);
            if (fuelId != null && "minecraft".equals(fuelId.getNamespace())) {
                fuelItem = fuelId.getPath();
            }
        }
        command = command + " " + fuelItem;
        if (botTarget != null && !botTarget.isBlank()) {
            command = command + " " + botTarget;
        }
        this.client.getNetworkHandler().sendChatCommand(command);
    }

    private Rect listRect() {
        int cx = this.width / 2;
        int listY = TOP_Y + CONTROL_ROW_DY + LIST_TOP_GAP;
        int listW = 140;
        int listH = MathHelper.clamp(this.height - listY - LIST_BOTTOM_MARGIN, LIST_MIN_H, 220);
        return new Rect(cx - listW - 8, listY, listW, listH);
    }

    private Rect fuelRect() {
        int cx = this.width / 2;
        int listY = TOP_Y + CONTROL_ROW_DY + LIST_TOP_GAP;
        int listW = 140;
        int listH = MathHelper.clamp(this.height - listY - LIST_BOTTOM_MARGIN, LIST_MIN_H, 220);
        return new Rect(cx + 8, listY, listW, listH);
    }

    private int visibleRows(int listH) {
        return Math.max(1, (listH - 4) / ROW_H);
    }

    private void clampScroll(int visibleRows) {
        int maxScroll = Math.max(0, getCookablesSnapshot().size() - visibleRows);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);
    }

    private void clampFuelScroll(int visibleRows) {
        int maxScroll = Math.max(0, getFuelsSnapshot().size() - visibleRows);
        fuelScroll = MathHelper.clamp(fuelScroll, 0, maxScroll);
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
