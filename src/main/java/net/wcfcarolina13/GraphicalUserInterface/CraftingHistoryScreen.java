package net.wcfcarolina13.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.GraphicalUserInterface.CraftingListPolicy.Entry;
import net.wcfcarolina13.GraphicalUserInterface.CraftingListPolicy.Row;
import net.wcfcarolina13.GraphicalUserInterface.CraftingListPolicy.SortMode;
import net.wcfcarolina13.network.CraftingHistoryPayload;
import net.wcfcarolina13.network.RequestCraftingHistoryPayload;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Crafting history window (per-world, commander-crafted list).
 * Mirrors the Bases menu surface from the Topics panel. Search and sort rules live in
 * {@link CraftingListPolicy}.
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

    private static SortMode sortMode = SortMode.NAME_ASC;

    // item -> index of its first creative-tab category, rebuilt when the tab contents change
    private static final Map<Item, Integer> CATEGORY_INDEX = new HashMap<>();
    private static List<ItemGroup> categoryGroups = List.of();

    private final Screen parent;
    private TextFieldWidget amountField;
    private TextFieldWidget searchField;
    private ButtonWidget sortButton;
    private String query = "";
    private int scroll;
    private String selectedId;
    private static final int ROW_H = 12;

    private List<Row> rows = List.of();
    private List<CraftEntry> rowsSource;
    private String rowsQuery;
    private SortMode rowsMode;

    private static final int TOP_Y = 28;
    private static final int CONTROL_ROW_DY = 24;
    private static final int BUTTON_H = 20;
    private static final int LIST_TOP_GAP = 6;
    private static final int LIST_W = 220;
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

        int searchY = top + CONTROL_ROW_DY;
        int listLeft = cx - LIST_W / 2;
        int sortW = 96;
        this.searchField = new TextFieldWidget(this.textRenderer, listLeft + 1, searchY + 1,
                LIST_W - sortW - 6, BUTTON_H - 2, Text.literal("Search"));
        this.searchField.setMaxLength(48);
        this.searchField.setPlaceholder(Text.literal("Search..."));
        this.searchField.setText(query);
        this.searchField.setChangedListener(text -> {
            query = text;
            scroll = 0;
        });
        this.addDrawableChild(this.searchField);
        this.sortButton = ButtonWidget.builder(sortLabel(), (btn) -> {
                    sortMode = sortMode.next();
                    scroll = 0;
                    btn.setMessage(sortLabel());
                })
                .dimensions(listLeft + LIST_W - sortW, searchY, sortW, BUTTON_H)
                .build();
        this.sortButton.setTooltip(Tooltip.of(Text.literal(
                "Sort order: A-Z, Z-A, Category (creative tab), Base type (material), Learned (first crafted)")));
        this.addDrawableChild(this.sortButton);
        setInitialFocus(this.searchField);

        requestRefresh();
    }

    private static Text sortLabel() {
        return Text.literal("Sort: " + sortMode.label());
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

    /** Filtered, sorted rows; rebuilt only when the history, query or sort mode changes. */
    private List<Row> view() {
        List<CraftEntry> history = getHistorySnapshot();
        if (history != rowsSource || !query.equals(rowsQuery) || sortMode != rowsMode) {
            rows = CraftingListPolicy.arrange(toPolicyEntries(history), query, sortMode);
            rowsSource = history;
            rowsQuery = query;
            rowsMode = sortMode;
        }
        return rows;
    }

    private List<Entry> toPolicyEntries(List<CraftEntry> history) {
        refreshCategoryIndex();
        List<Entry> out = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            CraftEntry e = history.get(i);
            int order = Integer.MAX_VALUE;
            String category = "";
            Identifier id = Identifier.tryParse(e.id());
            if (id != null) {
                Integer idx = CATEGORY_INDEX.get(Registries.ITEM.get(id));
                if (idx != null) {
                    order = idx;
                    category = categoryGroups.get(idx).getDisplayName().getString();
                }
            }
            out.add(new Entry(e.id(), e.label(), category, order, i));
        }
        return out;
    }

    /**
     * Creative tabs only hold their items once the display context has been built (the creative
     * inventory does this on open); build it here too so categories work without opening it first.
     */
    private void refreshCategoryIndex() {
        MinecraftClient client = this.client;
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        var handler = client.getNetworkHandler();
        boolean changed = ItemGroups.updateDisplayContext(handler.getEnabledFeatures(), false, handler.getRegistryManager());
        if (!changed && !CATEGORY_INDEX.isEmpty()) {
            return;
        }
        CATEGORY_INDEX.clear();
        List<ItemGroup> groups = new ArrayList<>();
        for (ItemGroup group : ItemGroups.getGroupsToDisplay()) {
            if (group.getType() != ItemGroup.Type.CATEGORY) {
                continue;
            }
            int idx = groups.size();
            groups.add(group);
            for (ItemStack stack : group.getDisplayStacks()) {
                CATEGORY_INDEX.putIfAbsent(stack.getItem(), idx);
            }
        }
        categoryGroups = groups;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Rect list = listRect();
        if (list.contains(mouseX, mouseY)) {
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                int maxScroll = Math.max(0, view().size() - visibleRows(list.h));
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
                List<Row> view = view();
                if (idx >= 0 && idx < view.size() && !view.get(idx).isHeader()) {
                    selectedId = view.get(idx).entry().id();
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
        context.fill(list.x, list.y, list.x + list.w, list.y + list.h, 0x2F000000);
        drawBorder(context, list, 0xFFB8A76A);

        List<Row> view = view();
        int visible = visibleRows(list.h);
        clampScroll(visible);
        int start = scroll;
        int end = Math.min(view.size(), start + visible);
        int rowY = list.y + 2;
        for (int i = start; i < end; i++) {
            Row row = view.get(i);
            if (row.isHeader()) {
                context.drawText(this.textRenderer, row.header(), list.x + 4, rowY + 2, 0xFFB8A76A, false);
                context.fill(list.x + 4, rowY + ROW_H - 1, list.x + list.w - 4, rowY + ROW_H, 0x66B8A76A);
            } else {
                if (row.entry().id().equals(selectedId)) {
                    context.fill(list.x + 1, rowY, list.x + list.w - 1, rowY + ROW_H, 0x553A2C14);
                }
                context.drawText(this.textRenderer, row.entry().label(), list.x + 10, rowY + 1, 0xFFE6D7A3, false);
            }
            rowY += ROW_H;
        }

        if (getHistorySnapshot().isEmpty()) {
            context.drawText(this.textRenderer, "No crafting history yet.", list.x + 6, list.y + 6, 0xFFB8A76A, false);
        } else if (view.isEmpty()) {
            context.drawText(this.textRenderer, "Nothing matches \"" + query.trim() + "\".", list.x + 6, list.y + 6, 0xFFB8A76A, false);
        }
    }

    private Rect listRect() {
        int cx = this.width / 2;
        int listY = TOP_Y + 2 * CONTROL_ROW_DY + LIST_TOP_GAP;
        int listH = MathHelper.clamp(this.height - listY - LIST_BOTTOM_MARGIN, LIST_MIN_H, 220);
        return new Rect(cx - LIST_W / 2, listY, LIST_W, listH);
    }

    private int visibleRows(int listH) {
        return Math.max(1, (listH - 4) / ROW_H);
    }

    private void clampScroll(int visibleRows) {
        int maxScroll = Math.max(0, view().size() - visibleRows);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);
    }

    private void craftSelected() {
        if (selectedId == null) {
            return;
        }
        // Only craft what is still visible, so a filtered-out selection is never crafted by surprise.
        for (Row row : view()) {
            if (!row.isHeader() && row.entry().id().equals(selectedId)) {
                sendCraftCommand(row.entry().id(), parseAmount());
                return;
            }
        }
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

    private void sendCraftCommand(String itemId, int amount) {
        if (this.client == null || this.client.getNetworkHandler() == null || itemId == null) {
            return;
        }
        String item = itemId;
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
