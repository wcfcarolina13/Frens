package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recovery / quick-spawn menu shown when the player has no companions in the
 * world (fresh world after picking Admin Mode, or all bots despawned/dead in
 * an existing world).  Supports:
 * <ul>
 *   <li>Multi-select checkboxes over the saved alias roster.</li>
 *   <li>"Spawn Selected" with a small per-bot delay so they don't pile up.</li>
 *   <li>"Create new bot" sub-section with a name input (random skin assigned;
 *       customize later via the bot's inventory → skin chooser).</li>
 *   <li>Footer link to the Bot Controls screen for spawning rules &amp;
 *       per-bot preferences.</li>
 * </ul>
 */
public class BotRestoreScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 290;
    private static final int ALIASES_PER_PAGE = 6;
    private static final int CHECKBOX_SIZE = 12;
    private static final int ROW_H = 18;
    private static final long SPAWN_STAGGER_MS = 220L;
    private static final int MAX_NAME_LEN = 24;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_ \\-.']+$");

    private final Screen parent;
    private final List<String> aliases;
    private final Set<String> selected = new LinkedHashSet<>();
    private int page = 0;

    private TextFieldWidget createNameField;
    private ButtonWidget spawnSelectedButton;
    private ButtonWidget createButton;
    private final java.util.List<RowHit> rowHits = new java.util.ArrayList<>();
    private int botControlsLinkX, botControlsLinkY, botControlsLinkW, botControlsLinkH;

    public BotRestoreScreen(Screen parent, List<String> aliases) {
        super(Text.literal("Companions"));
        this.parent = parent;
        this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    @Override
    protected void init() {
        rowHits.clear();
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        int pageCount = Math.max(1, (int) Math.ceil((double) aliases.size() / (double) ALIASES_PER_PAGE));
        page = Math.max(0, Math.min(page, pageCount - 1));

        // ── Pagination buttons (or skipped if 1 page or empty) ──
        int navY = panelY + 56 + ALIASES_PER_PAGE * ROW_H + 4;
        if (pageCount > 1) {
            ButtonWidget prev = ButtonWidget.builder(Text.literal("◀ Prev"),
                    btn -> {
                        if (page > 0) {
                            page--;
                            this.clearAndInit();
                        }
                    })
                    .dimensions(panelX + 12, navY, 60, 18).build();
            prev.active = page > 0;
            this.addDrawableChild(prev);

            ButtonWidget next = ButtonWidget.builder(Text.literal("Next ▶"),
                    btn -> {
                        if (page + 1 < pageCount) {
                            page++;
                            this.clearAndInit();
                        }
                    })
                    .dimensions(panelX + PANEL_W - 72, navY, 60, 18).build();
            next.active = page + 1 < pageCount;
            this.addDrawableChild(next);
        }

        // ── Spawn Selected + Cancel buttons ──
        int actionsY = navY + 24;
        spawnSelectedButton = ButtonWidget.builder(Text.literal("Spawn Selected"),
                btn -> spawnSelected())
                .dimensions(panelX + 12, actionsY, 160, 20).build();
        spawnSelectedButton.active = !selected.isEmpty();
        this.addDrawableChild(spawnSelectedButton);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"),
                btn -> this.close())
                .dimensions(panelX + PANEL_W - 92, actionsY, 80, 20).build());

        // ── Create new bot section ──
        int createY = actionsY + 36;
        createNameField = new TextFieldWidget(this.textRenderer,
                panelX + 50, createY, 168, 20,
                Text.literal("Bot name"));
        createNameField.setMaxLength(MAX_NAME_LEN);
        createNameField.setChangedListener(s -> updateCreateButtonState());
        this.addSelectableChild(createNameField);

        createButton = ButtonWidget.builder(Text.literal("Create"),
                btn -> createNewBot())
                .dimensions(panelX + PANEL_W - 92, createY, 80, 20).build();
        createButton.active = false;
        this.addDrawableChild(createButton);

        // Update spawnSelectedButton label with count
        updateSpawnSelectedButtonLabel();

        // Bot Controls link rect (laid out for hover + click)
        int linkY = createY + 30;
        String linkText = "Manage settings → Bot Controls";
        botControlsLinkW = this.textRenderer.getWidth(linkText);
        botControlsLinkH = this.textRenderer.fontHeight + 2;
        botControlsLinkX = panelX + (PANEL_W - botControlsLinkW) / 2;
        botControlsLinkY = linkY;
    }

    private void updateCreateButtonState() {
        if (createButton == null || createNameField == null) return;
        String s = createNameField.getText().trim();
        boolean valid = !s.isEmpty()
                && s.length() <= MAX_NAME_LEN
                && NAME_PATTERN.matcher(s).matches()
                && !aliasExists(s);
        createButton.active = valid;
    }

    private boolean aliasExists(String candidate) {
        if (candidate == null) return false;
        String norm = candidate.trim().toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            if (alias.trim().toLowerCase(Locale.ROOT).equals(norm)) {
                return true;
            }
        }
        return false;
    }

    private void updateSpawnSelectedButtonLabel() {
        if (spawnSelectedButton == null) return;
        int n = selected.size();
        spawnSelectedButton.setMessage(n == 0
                ? Text.literal("Spawn Selected")
                : Text.literal("Spawn Selected (" + n + ")"));
        spawnSelectedButton.active = n > 0;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xD0101010);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        context.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + PANEL_H + 1, 0xFF2C2C2C);
        context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xDD101010);

        // Title + subtitle
        String title = aliases.isEmpty() ? "Welcome — no saved companions yet"
                : "Choose Companions to Restore";
        int titleW = this.textRenderer.getWidth(title);
        context.drawTextWithShadow(this.textRenderer, title,
                panelX + (PANEL_W - titleW) / 2, panelY + 14, 0xFFE6D7A3);

        String subtitle = aliases.isEmpty()
                ? "Pick a name below to spawn your first bot."
                : "Tick one or more saved bots, then click Spawn Selected.";
        int subW = this.textRenderer.getWidth(subtitle);
        context.drawTextWithShadow(this.textRenderer, subtitle,
                panelX + (PANEL_W - subW) / 2, panelY + 30, 0xFFB8A76A);

        // Roster rows
        rowHits.clear();
        int startIndex = page * ALIASES_PER_PAGE;
        int endIndex = Math.min(aliases.size(), startIndex + ALIASES_PER_PAGE);
        int rowY = panelY + 52;
        int rowX = panelX + 16;
        int rowW = PANEL_W - 32;
        int chkX = rowX + 4;

        if (aliases.isEmpty()) {
            String empty = "(No saved bots yet — create one below.)";
            int eW = this.textRenderer.getWidth(empty);
            context.drawTextWithShadow(this.textRenderer, empty,
                    panelX + (PANEL_W - eW) / 2, rowY + 18, 0xFF7C7C7C);
        } else {
            for (int i = startIndex; i < endIndex; i++) {
                String alias = aliases.get(i);
                boolean hover = mouseX >= rowX && mouseX <= rowX + rowW
                        && mouseY >= rowY && mouseY <= rowY + ROW_H;
                boolean checked = selected.contains(alias);

                context.fill(rowX, rowY, rowX + rowW, rowY + ROW_H,
                        hover ? 0xFF202020 : 0xFF181818);

                int cy = rowY + (ROW_H - CHECKBOX_SIZE) / 2;
                drawCheckbox(context, chkX, cy, checked);

                context.drawText(this.textRenderer, alias,
                        chkX + CHECKBOX_SIZE + 8,
                        rowY + (ROW_H - this.textRenderer.fontHeight) / 2,
                        0xFFEFEFEF, false);

                rowHits.add(new RowHit(alias, rowX, rowY, rowW, ROW_H));
                rowY += ROW_H;
            }
        }

        // Pagination caption
        if (aliases.size() > ALIASES_PER_PAGE) {
            int pageCount = Math.max(1, (int) Math.ceil((double) aliases.size() / (double) ALIASES_PER_PAGE));
            String cap = "Page " + (page + 1) + " / " + pageCount;
            int cW = this.textRenderer.getWidth(cap);
            int navY = panelY + 56 + ALIASES_PER_PAGE * ROW_H + 4;
            context.drawText(this.textRenderer, cap,
                    panelX + (PANEL_W - cW) / 2, navY + 5, 0xFF909090, false);
        }

        // Separator + create-new label
        int actionsY = (panelY + 56 + ALIASES_PER_PAGE * ROW_H + 4) + 24;
        int createY = actionsY + 36;
        int sepY = createY - 8;
        context.fill(panelX + 12, sepY, panelX + PANEL_W - 12, sepY + 1, 0xFF3A3A3A);
        context.drawText(this.textRenderer, "Name:",
                panelX + 14, createY + (20 - this.textRenderer.fontHeight) / 2,
                0xFFB8A76A, false);
        if (createNameField != null) {
            createNameField.render(context, mouseX, mouseY, delta);
        }

        // Bot Controls link
        boolean linkHover = mouseX >= botControlsLinkX
                && mouseX <= botControlsLinkX + botControlsLinkW
                && mouseY >= botControlsLinkY
                && mouseY <= botControlsLinkY + botControlsLinkH;
        int linkColor = linkHover ? 0xFFFFE08A : 0xFF8AB8FF;
        context.drawText(this.textRenderer, "Manage settings → Bot Controls",
                botControlsLinkX, botControlsLinkY, linkColor, false);
        if (linkHover) {
            context.fill(botControlsLinkX, botControlsLinkY + this.textRenderer.fontHeight,
                    botControlsLinkX + botControlsLinkW, botControlsLinkY + this.textRenderer.fontHeight + 1,
                    linkColor);
        }

        super.render(context, mouseX, mouseY, delta);

        // Tooltip for the link
        if (linkHover) {
            context.drawTooltip(this.textRenderer,
                    Text.literal("Open Bot Controls to set per-bot spawning rules, auto-respawn, and more."),
                    mouseX, mouseY);
        }
    }

    private static void drawCheckbox(DrawContext context, int x, int y, boolean checked) {
        int border = 0xFF6C6C6C;
        int bg = checked ? 0xFF2E5A2E : 0xFF181818;
        context.fill(x, y, x + CHECKBOX_SIZE, y + CHECKBOX_SIZE, bg);
        context.fill(x, y, x + CHECKBOX_SIZE, y + 1, border);
        context.fill(x, y + CHECKBOX_SIZE - 1, x + CHECKBOX_SIZE, y + CHECKBOX_SIZE, border);
        context.fill(x, y, x + 1, y + CHECKBOX_SIZE, border);
        context.fill(x + CHECKBOX_SIZE - 1, y, x + CHECKBOX_SIZE, y + CHECKBOX_SIZE, border);
        if (checked) {
            // Simple inset checkmark made of two small bars.
            context.fill(x + 3, y + 5, x + 5, y + 8, 0xFFCFF5CF);
            context.fill(x + 4, y + 7, x + 9, y + 9, 0xFFCFF5CF);
            context.fill(x + 7, y + 3, x + 9, y + 8, 0xFFCFF5CF);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        if (super.mouseClicked(click, isInside)) {
            return true;
        }
        if (click.button() != 0) {
            return false;
        }
        double mouseX = click.x();
        double mouseY = click.y();
        for (RowHit hit : rowHits) {
            if (hit.contains(mouseX, mouseY)) {
                if (selected.contains(hit.alias)) {
                    selected.remove(hit.alias);
                } else {
                    selected.add(hit.alias);
                }
                updateSpawnSelectedButtonLabel();
                return true;
            }
        }
        // Bot Controls link
        if (mouseX >= botControlsLinkX && mouseX <= botControlsLinkX + botControlsLinkW
                && mouseY >= botControlsLinkY && mouseY <= botControlsLinkY + botControlsLinkH) {
            if (this.client != null) {
                this.client.setScreen(new BotControlScreen(parent));
            }
            return true;
        }
        return false;
    }

    private void spawnSelected() {
        MinecraftClient client = this.client;
        if (client == null || client.getNetworkHandler() == null || selected.isEmpty()) {
            return;
        }
        java.util.List<String> ordered = new java.util.ArrayList<>(selected);
        scheduleStaggeredSpawn(client, ordered, 0);
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("Spawning " + ordered.size() + " bot" + (ordered.size() == 1 ? "" : "s") + "..."),
                    true);
        }
        client.setScreen(null);
    }

    /**
     * Sends one /bot spawn per tick-window so collision pushes them apart
     * naturally instead of all stacking at the player's look position.  Each
     * call schedules the next on the client's main thread.
     */
    private static void scheduleStaggeredSpawn(MinecraftClient client, java.util.List<String> aliases, int index) {
        if (client == null || index >= aliases.size()) return;
        String alias = aliases.get(index);
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand("bot spawn " + quoteAlias(alias) + " admin");
        }
        if (index + 1 < aliases.size()) {
            new Thread(() -> {
                try { Thread.sleep(SPAWN_STAGGER_MS); } catch (InterruptedException ignored) {}
                client.execute(() -> scheduleStaggeredSpawn(client, aliases, index + 1));
            }, "frens-spawn-stagger").start();
        }
    }

    private void createNewBot() {
        MinecraftClient client = this.client;
        if (client == null || client.getNetworkHandler() == null || createNameField == null) {
            return;
        }
        String name = createNameField.getText().trim();
        if (name.isEmpty() || !NAME_PATTERN.matcher(name).matches()) {
            return;
        }
        client.getNetworkHandler().sendChatCommand("bot spawn " + quoteAlias(name) + " admin");
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("Creating " + name + " (random skin — change later via inventory)."),
                    true);
        }
        client.setScreen(null);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private static String quoteAlias(String alias) {
        if (alias == null) return "";
        String trimmed = alias.trim();
        if (trimmed.contains(" ")) {
            return "\"" + trimmed + "\"";
        }
        return trimmed;
    }

    private record RowHit(String alias, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }
}
