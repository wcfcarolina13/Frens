package net.wcfcarolina13.GraphicalUserInterface;

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
import net.wcfcarolina13.network.RequestHuntablesPayload;
import net.wcfcarolina13.network.SaveHuntConfigPayload;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Huntables list window — multi-select targets, depopulation toggle, zone size cycling.
 */
public class HuntablesScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private record HuntEntry(String id, String label, boolean unlocked, boolean food) {}

    private static List<HuntEntry> LAST_HUNTABLES = List.of();

    // ── Config state received from server ───────────────────────────────
    private static boolean configDepopulation = true;
    private static String configZone = "STANDARD";
    private static List<String> configSelectedTargets = List.of();

    private static final String[] ZONE_NAMES = {"STANDARD", "EXPANDED", "SPRAWLING"};
    private static final String[] ZONE_LABELS = {"Standard", "Expanded", "Sprawling"};

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

    public static void applyHuntConfigJson(String json) {
        if (json == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json, MAP_TYPE);
            if (parsed == null) return;
            if (parsed.get("depopulationEnabled") instanceof Boolean b) configDepopulation = b;
            if (parsed.get("zone") instanceof String s) configZone = s;
            if (parsed.get("selectedTargets") instanceof List<?> list) {
                configSelectedTargets = list.stream()
                        .filter(o -> o instanceof String)
                        .map(o -> (String) o)
                        .toList();
            }
        } catch (Exception ignored) {}
    }

    private final Screen parent;
    private final String botTarget;
    private int scroll;
    private TextFieldWidget countField;

    // ── Local config state (mirrors server config, editable in UI) ──────
    private boolean depopulationEnabled = configDepopulation;
    private int zoneIndex = zoneIndexOf(configZone);
    private final Set<String> selectedTargets = new HashSet<>(configSelectedTargets);

    private static final int ROW_H = 14;
    private static final int TOP_Y = 24;
    private static final int BUTTON_H = 20;
    private static final int LIST_BOTTOM_MARGIN = 32;
    private static final int LIST_MIN_H = 60;

    public HuntablesScreen(Screen parent, String botTarget) {
        super(Text.literal("Hunting"));
        this.parent = parent;
        this.botTarget = botTarget;
    }

    @Override
    protected void init() {
        // Sync from latest server config
        depopulationEnabled = configDepopulation;
        zoneIndex = zoneIndexOf(configZone);
        selectedTargets.clear();
        selectedTargets.addAll(configSelectedTargets);

        int cx = this.width / 2;
        int y = TOP_Y;

        // Row 1: Count field + Refresh
        this.countField = new TextFieldWidget(this.textRenderer, cx - 110, y, 100, 18, Text.literal("Count"));
        this.countField.setMaxLength(5);
        this.countField.setPlaceholder(Text.literal("Count"));
        this.addDrawableChild(this.countField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), btn -> requestRefresh())
                .dimensions(cx + 10, y, 60, BUTTON_H).build());

        // Row 2: Depopulation toggle + Zone size
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal(depopLabel()), btn -> {
                    depopulationEnabled = !depopulationEnabled;
                    btn.setMessage(Text.literal(depopLabel()));
                    saveConfig();
                }).dimensions(cx - 110, y, 110, BUTTON_H).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Zone: " + ZONE_LABELS[zoneIndex]), btn -> {
                    zoneIndex = (zoneIndex + 1) % ZONE_NAMES.length;
                    btn.setMessage(Text.literal("Zone: " + ZONE_LABELS[zoneIndex]));
                    saveConfig();
                }).dimensions(cx + 10, y, 100, BUTTON_H).build());

        // Row 3: Hunt + Target + All Edible + Close
        y += 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Hunt"), btn -> huntSelected())
                .dimensions(cx - 110, y, 50, BUTTON_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Target"), btn -> activateTargetPicker())
                .dimensions(cx - 52, y, 52, BUTTON_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("All Edible"), btn -> selectAllEdible())
                .dimensions(cx + 8, y, 70, BUTTON_H).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> close())
                .dimensions(cx + 86, y, 50, BUTTON_H).build());

        requestRefresh();
    }

    private String depopLabel() {
        return depopulationEnabled ? "Depop: ON" : "Depop: OFF";
    }

    private static int zoneIndexOf(String name) {
        for (int i = 0; i < ZONE_NAMES.length; i++) {
            if (ZONE_NAMES[i].equalsIgnoreCase(name)) return i;
        }
        return 0;
    }

    private void selectAllEdible() {
        selectedTargets.clear();
        for (HuntEntry entry : getHuntablesSnapshot()) {
            if (entry.unlocked()) {
                selectedTargets.add(entry.id());
            }
        }
        saveConfig();
    }

    private void saveConfig() {
        if (!ClientPlayNetworking.canSend(SaveHuntConfigPayload.ID)) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botName", botTarget != null ? botTarget : "");
        out.put("depopulationEnabled", depopulationEnabled);
        out.put("zone", ZONE_NAMES[zoneIndex]);
        out.put("selectedTargets", new ArrayList<>(selectedTargets));
        String json = GSON.toJson(out);
        ClientPlayNetworking.send(new SaveHuntConfigPayload(json));
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
                    HuntEntry entry = entries.get(idx);
                    if (entry.unlocked()) {
                        // Toggle multi-select
                        if (selectedTargets.contains(entry.id())) {
                            selectedTargets.remove(entry.id());
                        } else {
                            selectedTargets.add(entry.id());
                        }
                        saveConfig();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, isInside);
    }

    private void activateTargetPicker() {
        HuntTargetPickerOverlay.activate(botTarget);
        // Close the screen so the player can look around and click a mob
        MinecraftClient client = this.client;
        if (client != null) {
            client.setScreen(null);
        }
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
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 8, 0xFFFFFF);

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
            boolean selected = selectedTargets.contains(entry.id());

            // Highlight selected rows
            if (selected) {
                context.fill(list.x + 1, y, list.x + list.w - 1, y + ROW_H, 0x44567832);
            }

            // Checkmark for selected
            String prefix = selected ? "\u2714 " : "  ";
            int color = entry.unlocked() ? (selected ? 0xFFA8D86A : 0xFFE6D7A3) : 0xFF7F6F5A;
            String label = prefix + entry.label();
            if (!entry.unlocked() && entry.food()) {
                label = label + " (locked)";
            }
            context.drawText(this.textRenderer, label, list.x + 4, y + 2, color, false);
            y += ROW_H;
        }

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Huntable Mobs"), list.x + list.w / 2, list.y - 12, 0xFFE6D7A3);

        // Status line
        int selCount = selectedTargets.size();
        String hint = selCount == 0
                ? "Click mobs to select. Empty = hunt anything edible."
                : selCount + " target" + (selCount != 1 ? "s" : "") + " selected.";
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
        if (this.client == null || this.client.getNetworkHandler() == null) return;

        String count = countField != null ? countField.getText() : "";
        StringBuilder command = new StringBuilder("bot skill hunt ");
        if (count != null && !count.isBlank()) {
            String cleaned = count.trim();
            if (cleaned.matches("\\d+")) {
                command.append(cleaned).append(" ");
            }
        }

        // If specific targets selected, pick the first as the explicit target for the command.
        // The config service stores the full multi-select list, so the skill will respect it.
        if (!selectedTargets.isEmpty()) {
            String firstTarget = selectedTargets.iterator().next();
            Identifier id = Identifier.tryParse(firstTarget);
            if (id != null && "minecraft".equals(id.getNamespace())) {
                firstTarget = id.getPath();
            }
            command.append(firstTarget);
        }

        if (botTarget != null && !botTarget.isBlank()) {
            command.append(" ").append(botTarget);
        }
        this.client.getNetworkHandler().sendChatCommand(command.toString().trim());
    }

    private Rect listRect() {
        int cx = this.width / 2;
        // Below the 3 control rows
        int listY = TOP_Y + 24 + 24 + BUTTON_H + 8;
        int listW = 220;
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
