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
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.shasankp000.network.RequestHuntablesPayload;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Huntables list window (mobs the bot can hunt, unlocked by prior kills).
 */
public class HuntablesScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    private record HuntEntry(String id, String label, boolean unlocked, boolean food) {}

    private static List<HuntEntry> LAST_HUNTABLES = List.of();

    public static void applyHuntablesJson(String json) {
        if (json == null) {
            LAST_HUNTABLES = List.of();
            return;
        }
        try {
            List<Map<String, Object>> parsed = GSON.fromJson(json, LIST_TYPE);
            if (parsed == null) {
                LAST_HUNTABLES = List.of();
                return;
            }
            List<HuntEntry> out = new ArrayList<>(parsed.size());
            for (Map<String, Object> map : parsed) {
                if (map == null) {
                    continue;
                }
                Object idObj = map.get("id");
                Object labelObj = map.get("label");
                Object unlockedObj = map.get("unlocked");
                Object foodObj = map.get("food");
                String id = idObj != null ? idObj.toString() : "";
                String label = labelObj != null ? labelObj.toString() : id;
                boolean unlocked = unlockedObj instanceof Boolean b && b;
                boolean food = foodObj instanceof Boolean b && b;
                if (id.isBlank()) {
                    continue;
                }
                out.add(new HuntEntry(id, label, unlocked, food));
            }
            LAST_HUNTABLES = out;
        } catch (Exception ignored) {
            LAST_HUNTABLES = List.of();
        }
    }

    private final Screen parent;
    private final String botTarget;
    private int scroll;
    private int selectedIndex = -1;
    private TextFieldWidget countField;

    private static final int ROW_H = 12;
    private static final int TOP_Y = 28;
    private static final int CONTROL_ROW_DY = 24;
    private static final int BUTTON_H = 20;
    private static final int LIST_TOP_GAP = 8;
    private static final int LIST_BOTTOM_MARGIN = 32;
    private static final int LIST_MIN_H = 60;

    public HuntablesScreen(Screen parent, String botTarget) {
        super(Text.literal("Hunting"));
        this.parent = parent;
        this.botTarget = botTarget;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = TOP_Y;

        this.countField = new TextFieldWidget(this.textRenderer, cx - 110, top, 120, 18, Text.literal("Count"));
        this.countField.setMaxLength(5);
        this.countField.setPlaceholder(Text.literal("Count (optional)"));
        this.addDrawableChild(this.countField);

        int btnY = top;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), (btn) -> requestRefresh())
                .dimensions(cx + 20, btnY, 70, BUTTON_H)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Hunt"), (btn) -> huntSelected())
                .dimensions(cx - 40, btnY + CONTROL_ROW_DY, 60, BUTTON_H)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), (btn) -> close())
                .dimensions(cx + 30, btnY + CONTROL_ROW_DY, 70, BUTTON_H)
                .build());

        requestRefresh();
    }

    private void requestRefresh() {
        if (ClientPlayNetworking.canSend(RequestHuntablesPayload.ID)) {
            ClientPlayNetworking.send(new RequestHuntablesPayload(""));
        }
    }

    private static List<HuntEntry> getHuntablesSnapshot() {
        List<HuntEntry> items = LAST_HUNTABLES;
        return items != null ? items : List.of();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Rect list = listRect();
        if (list.contains(mouseX, mouseY)) {
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                int maxScroll = Math.max(0, getHuntablesSnapshot().size() - visibleRows(list.h));
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
                List<HuntEntry> entries = getHuntablesSnapshot();
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

        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 10, 0xFFFFFF);

        Rect list = listRect();
        List<HuntEntry> entries = getHuntablesSnapshot();
        int rows = visibleRows(list.h);
        int maxScroll = Math.max(0, entries.size() - rows);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);

        if (entries.isEmpty()) {
            context.drawText(this.textRenderer, "No huntable mobs unlocked yet.", list.x + 6, list.y + 6, 0xFFB8A76A, false);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Kill a food mob first to unlock hunting."), cx, list.bottom() + 6, 0xFFB0B0B0);
            return;
        }

        int start = scroll;
        int end = Math.min(entries.size(), start + rows);
        int y = list.y + 2;
        for (int i = start; i < end; i++) {
            HuntEntry entry = entries.get(i);
            if (i == selectedIndex) {
                context.fill(list.x + 1, y, list.x + list.w - 1, y + ROW_H, 0x553A2C14);
            }
            int color = entry.unlocked() ? 0xFFE6D7A3 : 0xFF7F6F5A;
            String label = entry.label();
            if (!entry.unlocked() && entry.food()) {
                label = label + " (locked)";
            }
            context.drawText(this.textRenderer, label, list.x + 6, y + 1, color, false);
            y += ROW_H;
        }

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Huntable Mobs"), list.x + list.w / 2, list.y - 12, 0xFFE6D7A3);
        String hint = "Select a mob and Hunt. Blank count hunts until sunset.";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(hint), cx, list.bottom() + 6, 0xFFB0B0B0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        Rect list = listRect();
        context.fill(list.x, list.y, list.right(), list.bottom(), 0xAA101010);
    }

    private void huntSelected() {
        List<HuntEntry> entries = getHuntablesSnapshot();
        if (selectedIndex < 0 || selectedIndex >= entries.size()) {
            if (this.client != null && this.client.player != null) {
                this.client.player.sendMessage(Text.literal("Select a huntable mob first."), true);
            }
            return;
        }
        HuntEntry entry = entries.get(selectedIndex);
        sendHuntCommand(entry);
    }

    private void sendHuntCommand(HuntEntry entry) {
        if (this.client == null || this.client.getNetworkHandler() == null || entry == null) {
            return;
        }
        String target = entry.id();
        Identifier id = Identifier.tryParse(target);
        if (id != null && "minecraft".equals(id.getNamespace())) {
            target = id.getPath();
        }
        String count = countField != null ? countField.getText() : "";
        StringBuilder command = new StringBuilder("bot skill hunt ");
        if (count != null && !count.isBlank()) {
            String cleaned = count.trim();
            if (cleaned.matches("\\d+")) {
                command.append(cleaned).append(" ");
            }
        }
        command.append(target);
        if (botTarget != null && !botTarget.isBlank()) {
            command.append(" ").append(botTarget);
        }
        this.client.getNetworkHandler().sendChatCommand(command.toString());
    }

    private Rect listRect() {
        int cx = this.width / 2;
        int listY = TOP_Y + CONTROL_ROW_DY + BUTTON_H + LIST_TOP_GAP + 8;
        int listW = 180;
        int listH = MathHelper.clamp(this.height - listY - LIST_BOTTOM_MARGIN, LIST_MIN_H, 220);
        return new Rect(cx - listW / 2, listY, listW, listH);
    }

    private static int visibleRows(int height) {
        return Math.max(1, height / ROW_H);
    }

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
