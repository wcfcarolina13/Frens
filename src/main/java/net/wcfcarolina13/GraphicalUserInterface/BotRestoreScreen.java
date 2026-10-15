package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.BotActionClientSender;
import net.wcfcarolina13.network.BotActionPolicy;

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
    private static final int CHECKBOX_SIZE = 12;
    private static final int ROW_H = RosterLayoutPolicy.ROW_H;
    private static final long SPAWN_STAGGER_MS = 220L;
    private static final int MAX_NAME_LEN = 24;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_ \\-.']+$");

    private final Screen parent;
    private final List<String> aliases;
    /** Companions saved in other worlds; shown in place of {@link #aliases} when opened. */
    private final List<String> otherWorlds;
    private boolean showingOtherWorlds = false;
    private ButtonWidget otherWorldsButton;
    private final Set<String> selected = new LinkedHashSet<>();
    /** Index of the first bot row shown; the list scrolls by row. */
    private int firstRow = 0;
    private ButtonWidget prevButton;
    private ButtonWidget nextButton;

    private TextFieldWidget createNameField;
    private ButtonWidget spawnSelectedButton;
    private ButtonWidget createButton;
    private final java.util.List<RowHit> rowHits = new java.util.ArrayList<>();
    private int botControlsLinkX, botControlsLinkY, botControlsLinkW, botControlsLinkH;

    private final RosterScreenCopy.State copyState;

    public BotRestoreScreen(Screen parent, List<String> aliases) {
        this(parent, aliases, false);
    }

    /**
     * @param allSavedPresent true when the list is empty only because every saved companion is
     *                        already in the world (the roster hides those)
     */
    public BotRestoreScreen(Screen parent, List<String> aliases, boolean allSavedPresent) {
        this(parent, aliases, List.of(), allSavedPresent);
    }

    /**
     * @param aliases         this world's companions that can be restored
     * @param otherWorlds     companions saved in other worlds, offered behind the Other worlds button
     * @param allSavedPresent true when the list is empty only because every saved companion of
     *                        this world is already in it
     */
    public BotRestoreScreen(Screen parent, List<String> aliases, List<String> otherWorlds, boolean allSavedPresent) {
        super(Text.literal("Companions"));
        this.parent = parent;
        this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
        this.otherWorlds = otherWorlds == null ? List.of() : List.copyOf(otherWorlds);
        this.copyState = RosterScreenCopy.state(this.aliases.size(), allSavedPresent, this.otherWorlds.size());
    }

    /** The bot rows currently listed: this world's, or other worlds' while that list is open. */
    private List<String> listed() {
        return showingOtherWorlds ? otherWorlds : aliases;
    }

    private String titleText() {
        return showingOtherWorlds ? RosterScreenCopy.otherWorldsTitle() : RosterScreenCopy.title(copyState);
    }

    private String subtitleText() {
        return showingOtherWorlds ? RosterScreenCopy.otherWorldsSubtitle() : RosterScreenCopy.subtitle(copyState);
    }

    /** One place for every y/x the roster uses, so init, render and clicks agree. */
    private record Layout(int panelX, int panelY, int panelW, int panelH, boolean compact,
                          int titleY, int subtitleY, List<String> subtitle, int toggleY, int rowsTop, int rows,
                          int navY, int actionsY, int createY, int linkY) {
    }

    private Layout layout() {
        int panelW = Math.min(PANEL_W, this.width - 16);
        List<String> subtitle = UiFitPolicy.wrap(subtitleText(), panelW - 16,
                this.textRenderer::getWidth, 2);
        int toggleH = otherWorlds.isEmpty() ? 0 : RosterLayoutPolicy.TOGGLE_ROW_H;
        RosterLayoutPolicy.Fit fit = RosterLayoutPolicy.fit(this.height, 8, Math.max(1, subtitle.size()), toggleH);
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(4, (this.height - fit.panelH()) / 2);
        boolean compact = fit.compact();
        RosterLayoutPolicy.Spacing sp = RosterLayoutPolicy.spacing(compact);
        int titleY = panelY + sp.titleTop();
        int subtitleY = titleY + sp.subtitleGap();
        int toggleY = subtitleY + Math.max(1, subtitle.size()) * RosterLayoutPolicy.SUBTITLE_LINE_H + sp.rowsGap();
        int rowsTop = toggleY + toggleH;
        int navY = rowsTop + fit.rows() * ROW_H + sp.navGap();
        int actionsY = navY + sp.actionsGap();
        int createY = actionsY + sp.createGap();
        int linkY = createY + sp.linkGap();
        return new Layout(panelX, panelY, panelW, fit.panelH(), compact, titleY, subtitleY, subtitle,
                toggleY, rowsTop, fit.rows(), navY, actionsY, createY, linkY);
    }

    @Override
    protected void init() {
        rowHits.clear();
        Layout l = layout();
        int panelX = l.panelX();
        int panelW = l.panelW();
        int visible = l.rows();
        firstRow = RosterLayoutPolicy.clampFirstRow(firstRow, listed().size(), visible);

        // ── Other worlds toggle (only when other worlds have companions to bring in) ──
        if (!otherWorlds.isEmpty()) {
            int toggleW = Math.min(160, panelW - 24);
            otherWorldsButton = ButtonWidget.builder(
                            Text.literal(RosterScreenCopy.otherWorldsToggle(otherWorlds.size(), showingOtherWorlds)),
                            btn -> toggleOtherWorlds())
                    .dimensions(panelX + (panelW - toggleW) / 2, l.toggleY(), toggleW, 18).build();
            this.addDrawableChild(otherWorldsButton);
        } else {
            otherWorldsButton = null;
        }

        // ── Scroll buttons (skipped when every bot fits) ──
        int navY = l.navY();
        if (listed().size() > visible) {
            prevButton = ButtonWidget.builder(Text.literal("◀ Prev"),
                    btn -> scrollRows(-visible))
                    .dimensions(panelX + 12, navY, 60, 18).build();
            this.addDrawableChild(prevButton);

            nextButton = ButtonWidget.builder(Text.literal("Next ▶"),
                    btn -> scrollRows(visible))
                    .dimensions(panelX + panelW - 72, navY, 60, 18).build();
            this.addDrawableChild(nextButton);
            updateScrollButtons(visible);
        } else {
            prevButton = null;
            nextButton = null;
        }

        // ── Spawn Selected + Cancel buttons ──
        int actionsY = l.actionsY();
        spawnSelectedButton = ButtonWidget.builder(Text.literal("Spawn Selected"),
                btn -> spawnSelected())
                .dimensions(panelX + 12, actionsY, 160, 20).build();
        spawnSelectedButton.active = !selected.isEmpty();
        this.addDrawableChild(spawnSelectedButton);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"),
                btn -> this.close())
                .dimensions(panelX + panelW - 92, actionsY, 80, 20).build());

        // ── Create new bot section ──
        int createY = l.createY();
        int nameFieldW = Math.min(168, panelW - 50 - 92 - 6);
        createNameField = new TextFieldWidget(this.textRenderer,
                panelX + 50, createY, nameFieldW, 20,
                Text.literal("Bot name"));
        createNameField.setMaxLength(MAX_NAME_LEN);
        createNameField.setChangedListener(s -> updateCreateButtonState());
        this.addSelectableChild(createNameField);

        createButton = ButtonWidget.builder(Text.literal("Create"),
                btn -> createNewBot())
                .dimensions(panelX + panelW - 92, createY, 80, 20).build();
        createButton.active = false;
        this.addDrawableChild(createButton);

        // Update spawnSelectedButton label with count
        updateSpawnSelectedButtonLabel();

        // Bot Controls link rect (laid out for hover + click)
        String linkText = "Manage settings → Bot Controls";
        botControlsLinkW = this.textRenderer.getWidth(linkText);
        botControlsLinkH = this.textRenderer.fontHeight + 2;
        botControlsLinkX = panelX + (panelW - botControlsLinkW) / 2;
        botControlsLinkY = l.linkY();
    }

    private void toggleOtherWorlds() {
        showingOtherWorlds = !showingOtherWorlds;
        firstRow = 0;
        this.clearAndInit();
    }

    private void scrollRows(int delta) {
        int visible = layout().rows();
        int next = RosterLayoutPolicy.clampFirstRow(firstRow + delta, listed().size(), visible);
        if (next != firstRow) {
            firstRow = next;
            updateScrollButtons(visible);
        }
    }

    private void updateScrollButtons(int visible) {
        if (prevButton != null) {
            prevButton.active = firstRow > 0;
        }
        if (nextButton != null) {
            nextButton.active = firstRow + visible < listed().size();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (listed().size() > layout().rows() && verticalAmount != 0) {
            scrollRows(verticalAmount > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void updateCreateButtonState() {
        if (createButton == null || createNameField == null) return;
        String s = createNameField.getText().trim();
        boolean inOtherWorld = !s.isEmpty() && aliasIn(otherWorlds, s);
        boolean valid = !s.isEmpty()
                && s.length() <= MAX_NAME_LEN
                && NAME_PATTERN.matcher(s).matches()
                && !aliasExists(s);
        createButton.active = valid;
        // Greyed because the name is taken in another world: say where to find that companion.
        createButton.setTooltip(inOtherWorld
                ? Tooltip.of(Text.literal(RosterScreenCopy.createNameInOtherWorldHint()))
                : null);
    }

    private boolean aliasExists(String candidate) {
        return aliasIn(aliases, candidate) || aliasIn(otherWorlds, candidate);
    }

    private static boolean aliasIn(List<String> list, String candidate) {
        if (candidate == null) return false;
        String norm = candidate.trim().toLowerCase(Locale.ROOT);
        for (String alias : list) {
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

        Layout l = layout();
        int panelX = l.panelX();
        int panelY = l.panelY();
        int panelW = l.panelW();
        int panelH = l.panelH();
        context.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xFF2C2C2C);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xDD101010);

        // Title + subtitle
        String title = UiFitPolicy.elide(titleText(), panelW - 16, this.textRenderer::getWidth);
        int titleW = this.textRenderer.getWidth(title);
        context.drawTextWithShadow(this.textRenderer, title,
                panelX + (panelW - titleW) / 2, l.titleY(), 0xFFE6D7A3);

        int subY = l.subtitleY();
        for (String subtitle : l.subtitle()) {
            int subW = this.textRenderer.getWidth(subtitle);
            context.drawTextWithShadow(this.textRenderer, subtitle,
                    panelX + (panelW - subW) / 2, subY, 0xFFB8A76A);
            subY += RosterLayoutPolicy.SUBTITLE_LINE_H;
        }

        // Roster rows (the list scrolls when it has more bots than rows)
        rowHits.clear();
        int visible = l.rows();
        List<String> rows = listed();
        firstRow = RosterLayoutPolicy.clampFirstRow(firstRow, rows.size(), visible);
        int startIndex = firstRow;
        int endIndex = Math.min(rows.size(), startIndex + visible);
        int rowY = l.rowsTop();
        int rowX = panelX + 16;
        int rowW = panelW - 32;
        int chkX = rowX + 4;

        if (rows.isEmpty()) {
            String empty = UiFitPolicy.elide(RosterScreenCopy.emptyListLine(copyState), panelW - 16,
                    this.textRenderer::getWidth);
            int eW = this.textRenderer.getWidth(empty);
            context.drawTextWithShadow(this.textRenderer, empty,
                    panelX + (panelW - eW) / 2, rowY + 18, 0xFF7C7C7C);
        } else {
            for (int i = startIndex; i < endIndex; i++) {
                String alias = rows.get(i);
                boolean hover = mouseX >= rowX && mouseX <= rowX + rowW
                        && mouseY >= rowY && mouseY <= rowY + ROW_H;
                boolean checked = selected.contains(alias);

                context.fill(rowX, rowY, rowX + rowW, rowY + ROW_H,
                        hover ? 0xFF202020 : 0xFF181818);

                int cy = rowY + (ROW_H - CHECKBOX_SIZE) / 2;
                drawCheckbox(context, chkX, cy, checked);

                context.drawText(this.textRenderer,
                        UiFitPolicy.elide(alias, rowW - CHECKBOX_SIZE - 16, this.textRenderer::getWidth),
                        chkX + CHECKBOX_SIZE + 8,
                        rowY + (ROW_H - this.textRenderer.fontHeight) / 2,
                        0xFFEFEFEF, false);

                rowHits.add(new RowHit(alias, rowX, rowY, rowW, ROW_H));
                rowY += ROW_H;
            }
        }

        // Scroll caption
        if (rows.size() > visible) {
            String cap = (startIndex + 1) + "-" + endIndex + " of " + rows.size();
            int cW = this.textRenderer.getWidth(cap);
            context.drawText(this.textRenderer, cap,
                    panelX + (panelW - cW) / 2, l.navY() + 5, 0xFF909090, false);
        }

        // Separator + create-new label
        int createY = l.createY();
        int sepY = createY - 8;
        context.fill(panelX + 12, sepY, panelX + panelW - 12, sepY + 1, 0xFF3A3A3A);
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
            BotActionClientSender.send(BotActionPolicy.Action.SPAWN, alias, List.of(),
                    "bot spawn " + quoteAlias(alias) + " admin");
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
        BotActionClientSender.send(BotActionPolicy.Action.SPAWN, name, List.of(),
                "bot spawn " + quoteAlias(name) + " admin");
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
