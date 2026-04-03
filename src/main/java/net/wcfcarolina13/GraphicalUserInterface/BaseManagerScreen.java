package net.wcfcarolina13.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.network.BaseClaimWallPayload;
import net.wcfcarolina13.network.BaseGoToPayload;
import net.wcfcarolina13.network.BaseGrantWallAccessPayload;
import net.wcfcarolina13.network.BaseMapVillagePayload;
import net.wcfcarolina13.network.BaseRemovePayload;
import net.wcfcarolina13.network.BaseRenamePayload;
import net.wcfcarolina13.network.BaseSetRadiusPayload;
import net.wcfcarolina13.network.BaseRevokeWallAccessPayload;
import net.wcfcarolina13.network.BaseSetHomePayload;
import net.wcfcarolina13.network.BaseSetPayload;
import net.wcfcarolina13.network.BaseUnclaimWallPayload;
import net.wcfcarolina13.network.RequestBasesPayload;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Base/fortification manager window with a resizable panel, persisted layout,
 * and draggable scrollbar for overflow content.
 */
public class BaseManagerScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Type BASE_LIST_TYPE = new TypeToken<List<BaseDto>>() {}.getType();

    private static final Path UI_PREFS_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("frens")
            .resolve("base_manager_ui.json");

    public record BaseDto(String kind, String label, int x, int y, int z, boolean home, String detailText, String ownerName, int radius) {
        boolean isBase() {
            return "base".equalsIgnoreCase(kind);
        }

        boolean isWall() {
            return "wall".equalsIgnoreCase(kind);
        }

        boolean isVillage() {
            return "village".equalsIgnoreCase(kind);
        }

        boolean isLodestone() {
            return "lodestone".equalsIgnoreCase(kind);
        }
    }

    private record UiPrefs(Integer x, Integer y, Integer w, Integer h) {}

    private record SectionHeader(String text, int relY, int color) {}

    private record Rect(int x, int y, int w, int h) {
        int right() {
            return x + w;
        }

        int bottom() {
            return y + h;
        }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private static final class ControlSlot {
        final ClickableWidget widget;
        final int col;
        final int colSpan;
        final int relY;

        private ControlSlot(ClickableWidget widget, int col, int colSpan, int relY) {
            this.widget = widget;
            this.col = col;
            this.colSpan = colSpan;
            this.relY = relY;
        }
    }

    private static List<BaseDto> LAST_BASES = List.of();
    private static String LAST_ZONE_LIST_JSON = "[]";
    private static UiPrefs CACHED_PREFS = loadUiPrefs();

    /** Called from client network receiver when zone list arrives. */
    public static void onZoneListReceived(String zonesJson) {
        LAST_ZONE_LIST_JSON = zonesJson != null ? zonesJson : "[]";
    }

    // Window + content constants
    private static final int PANEL_MARGIN = 8;
    private static final int PANEL_DEFAULT_W = 262;
    private static final int PANEL_DEFAULT_H = 468;
    private static final int PANEL_MIN_W = 250;
    private static final int PANEL_MIN_H = 220;
    private static final int PANEL_PAD = 8;
    private static final int HEADER_H = 24;
    private static final int HEADER_TEXT_PAD = 6;

    private static final int SCROLLBAR_W = 6;
    private static final int THUMB_MIN_H = 16;
    private static final int SCROLL_STEP = 14;

    // Resize / move hitboxes
    private static final int RESIZE_GRAB = 5;

    // Content layout constants
    private static final int TOP_Y = 0;
    private static final int SECTION_LABEL_H = 14;
    private static final int SECTION_GAP = 6;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_ROW_GAP = 2;
    private static final int LIST_TOP_GAP = 6;
    private static final int LIST_BOTTOM_PAD = 10;
    private static final int LIST_MIN_H = 72;
    private static final int ROW_H = 12;

    private static final int COL_W = 70;
    private static final int COL_GAP = 5;

    // Colors
    private static final int COL_BG = 0xB0101010;
    private static final int COL_PANEL = 0xEE111111;
    private static final int COL_BORDER = 0xFF2A2A2A;
    private static final int COL_HEADER = 0xFF1A1A1A;
    private static final int COL_TITLE = 0xFFFFE08A;
    private static final int COL_TRACK = 0xFF171717;
    private static final int COL_THUMB = 0xFF7A6240;
    private static final int COL_THUMB_HL = 0xFFB08C40;

    private final Screen parent;
    private final String botAlias;

    private final List<ControlSlot> controls = new ArrayList<>();
    private final List<SectionHeader> sectionHeaders = new ArrayList<>();

    private TextFieldWidget nameField;
    private ButtonWidget resetLayoutButton;
    private ButtonWidget constructionButton;
    private TextFieldWidget villagePromptField;
    private ButtonWidget villagePromptConfirmButton;
    private ButtonWidget villagePromptCancelButton;
    private boolean mapVillagePromptOpen;
    private int zoneListStartRelY;
    private String pendingZoneDeleteLabel = null;

    private static final int STATUS_WARN  = 0xFFFFCC44;
    private static final int STATUS_ERROR = 0xFFFF6666;
    private static final int STATUS_OK    = 0xFF66FF66;

    private int selectedIndex = -1;
    private String statusMessage = "";
    private long statusExpiry = 0;
    private int statusColor = STATUS_WARN;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private int controlsBottomRelY;

    private double contentScroll;
    private boolean draggingScroll;
    private int scrollGrabOffset;

    private boolean draggingResize;
    private boolean draggingMove;
    private int resizeMask;

    private int dragMouseStartX;
    private int dragMouseStartY;
    private int dragPanelStartX;
    private int dragPanelStartY;
    private int dragPanelStartW;
    private int dragPanelStartH;

    private static final int MODAL_W = 220;
    private static final int MODAL_H = 92;

    private static final int RESIZE_LEFT = 1;
    private static final int RESIZE_RIGHT = 2;
    private static final int RESIZE_TOP = 4;
    private static final int RESIZE_BOTTOM = 8;

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
        this.clearChildren();
        controls.clear();
        sectionHeaders.clear();

        applySavedOrDefaultPanel();
        buildControls();
        buildVillagePromptControls();
        contentScroll = MathHelper.clamp(contentScroll, 0.0D, maxScroll());
        requestRefresh();
    }

    private void buildControls() {
        resetLayoutButton = ButtonWidget.builder(Text.literal("↺ Reset"), b -> {
                    resetPanelToDefault();
                    persistPanelPrefs();
                })
            .dimensions(0, 0, 58, 18)
                .build();
        resetLayoutButton.setTooltip(Tooltip.of(Text.literal("Reset window layout to default size + position")));
        this.addDrawableChild(resetLayoutButton);

        constructionButton = ButtonWidget.builder(Text.literal("Builds"), b -> openConstructionScreen())
                .dimensions(0, 0, 58, 18)
                .build();
        constructionButton.setTooltip(Tooltip.of(Text.literal("Open Construction jobs and preview-based builds")));
        this.addDrawableChild(constructionButton);

        int y = TOP_Y;

        // Name field
        this.nameField = new TextFieldWidget(this.textRenderer, 0, 0,
                COL_W * 3 + COL_GAP * 2, 18, Text.literal("Base name"));
        this.nameField.setMaxLength(64);
        this.nameField.setPlaceholder(Text.literal("Base name or radius...").styled(s -> s.withColor(0xFF666666)));
        this.addDrawableChild(this.nameField);
        controls.add(new ControlSlot(nameField, 0, 3, y));
        y += 22;

        // Section 1: Base management
        addSectionHeader("⌂ Base Management", y, 0xFFE6D7A3);
        y += SECTION_LABEL_H;

        addBtn("Set here", "Save your current position as a named base (type name above first)", y, 0,
                this::sendSetHere);
        addBtn("Rename", "Rename the selected base (type new name above, select base below)", y, 1,
                this::sendRenameSelected);
        addBtn("Remove", "Delete the selected base permanently", y, 2,
                this::sendRemoveSelected);
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn("Set Home", "Mark the selected base as this bot's home (where it returns at sunset)", y, 0,
                this::sendSetHomeSelected);
        addBtn("Go To Base", "Send the bot to walk to the selected base", y, 1,
                this::sendGoToSelected);
        addBtn("Refresh", "Reload the base list from the server", y, 2,
                this::requestRefresh);
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn("Set Radius", "Set the protection radius for the selected base (type number above)", y, 0,
                this::sendSetRadiusSelected);
        y += BUTTON_H + SECTION_GAP;

        // Section 2: Fortification
        addSectionHeader("⚔ Fortification", y, 0xFFD4A3E6);
        y += SECTION_LABEL_H;

        addBtn("Fortify New", "Start building a new wall around the nearest village", y, 0,
                this::sendFortifyNew);
        addBtn("Resume Wall", "Continue building an unfinished wall (select a wall below)", y, 1,
                this::sendResumeWall);
        addBtn("Patch Wall", "Repair damaged or missing blocks in the selected wall", y, 2,
                this::sendPatchWall);
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn("Auto Patch", "Like Patch, but automatically repeats until the wall is fully repaired", y, 0,
                this::sendAutoPatchWall);
        addBtn("Wall Status", "Show completion % and missing block breakdown for the selected wall", y, 1,
                this::sendWallStatus);
        addBtn("Dig Moat", "Dig a defensive moat around the selected wall perimeter", y, 2,
                this::sendDigMoat);
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn("Drift Check", "Check if the village center has moved away from the wall", y, 0,
                this::sendDriftCheckWall);
        addBtn("Expand Wall", "Expand the selected wall to cover newly detected village boundaries", y, 1,
                this::sendExpandWall);
        y += BUTTON_H + SECTION_GAP;

        // Section 3: Ownership
        addSectionHeader("🔑 Ownership", y, 0xFFA3E6B4);
        y += SECTION_LABEL_H;

        addBtn("Claim Wall", "Claim ownership of the selected wall (ties it to your player)", y, 0,
                this::sendClaimWall);
        addBtn("Unclaim", "Release ownership of the selected wall so anyone can claim it", y, 1,
                this::sendUnclaimWall);
        y += BUTTON_H + BUTTON_ROW_GAP;

        addBtn("Permit", "Grant another player build access to your wall (type their name above)", y, 0,
                this::sendPermitWallAccess);
        addBtn("Revoke", "Remove a player's build access from your wall (type their name above)", y, 1,
                this::sendRevokeWallAccess);
        y += BUTTON_H + SECTION_GAP;

        // Section 4: Villages
        addSectionHeader("⌘ Villages", y, 0xFFA3D7E6);
        y += SECTION_LABEL_H;

        addBtn("Map Village", "Scan the nearby settlement, name it in a popup, and save it as a shared no-go zone", y, 0,
                this::openMapVillagePrompt);
        y += BUTTON_H + SECTION_GAP;

        // Section 5: Protected Zones
        addSectionHeader("⬡ Protected Zones", y, 0xFF66CCCC);
        y += SECTION_LABEL_H;

        addBtn("New Zone", "Gives you a Zone Wand to select an area (admin only)", y, 0,
                this::requestZoneWand);
        addBtn("Refresh", "Refresh the zone list", y, 1,
                this::requestZoneRefresh);
        y += BUTTON_H + 2;

        zoneListStartRelY = y;
        y += renderZoneListHeight();
        y += SECTION_GAP;

        addBtn("Close", "Close this screen and return to the inventory", y, 2,
                this::close);
        y += BUTTON_H + 4;

        controlsBottomRelY = y;
    }

    private void addSectionHeader(String text, int relY, int color) {
        sectionHeaders.add(new SectionHeader(text, relY, color));
    }

    private void addBtn(String label, String tooltip, int relY, int col, Runnable action) {
        ButtonWidget btn = ButtonWidget.builder(Text.literal(label), b -> action.run())
                .dimensions(0, 0, COL_W, BUTTON_H)
                .build();
        btn.setTooltip(Tooltip.of(Text.literal(tooltip)));
        this.addDrawableChild(btn);
        controls.add(new ControlSlot(btn, col, 1, relY));
    }

    private void buildVillagePromptControls() {
        villagePromptField = new TextFieldWidget(this.textRenderer, 0, 0, MODAL_W - 20, 18, Text.literal("Village name"));
        villagePromptField.setMaxLength(64);
        villagePromptField.setPlaceholder(Text.literal("Village name"));
        villagePromptField.visible = false;
        villagePromptField.active = false;
        this.addDrawableChild(villagePromptField);

        villagePromptConfirmButton = ButtonWidget.builder(Text.literal("Map"), b -> sendMapVillage())
                .dimensions(0, 0, 72, 20)
                .build();
        villagePromptConfirmButton.visible = false;
        villagePromptConfirmButton.active = false;
        this.addDrawableChild(villagePromptConfirmButton);

        villagePromptCancelButton = ButtonWidget.builder(Text.literal("Cancel"), b -> closeMapVillagePrompt())
                .dimensions(0, 0, 72, 20)
                .build();
        villagePromptCancelButton.visible = false;
        villagePromptCancelButton.active = false;
        this.addDrawableChild(villagePromptCancelButton);
    }

    private void showStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
        this.statusExpiry = System.currentTimeMillis() + 4000;
    }

    private static List<BaseDto> getBasesSnapshot() {
        List<BaseDto> bases = LAST_BASES;
        return bases != null ? bases : List.of();
    }

    /** Public snapshot for other screens (e.g. NavigationConfirmScreen base picker). */
    public static List<BaseDto> getCachedBases() {
        return getBasesSnapshot();
    }

    private BaseDto getSelected() {
        List<BaseDto> bases = getBasesSnapshot();
        if (selectedIndex < 0 || selectedIndex >= bases.size()) {
            return null;
        }
        return bases.get(selectedIndex);
    }

    private Rect contentRect() {
        int x = panelX + PANEL_PAD;
        int y = panelY + HEADER_H + 4;
        int w = Math.max(40, panelW - (PANEL_PAD * 2) - SCROLLBAR_W - 2);
        int h = Math.max(44, panelH - HEADER_H - 10);
        return new Rect(x, y, w, h);
    }

    private int contentHeight() {
        int listY = controlsBottomRelY + LIST_TOP_GAP;
        int listH = Math.max(LIST_MIN_H, getBasesSnapshot().size() * ROW_H + 4);
        return listY + listH + LIST_BOTTOM_PAD;
    }

    private int maxScroll() {
        Rect content = contentRect();
        return Math.max(0, contentHeight() - content.h);
    }

    private Rect listRect(Rect content) {
        int gridW = COL_W * 3 + COL_GAP * 2;
        int listW = gridW;
        int listX = content.x + Math.max(0, (content.w - listW) / 2);
        int listY = content.y + controlsBottomRelY + LIST_TOP_GAP - (int) contentScroll;
        int listH = Math.max(LIST_MIN_H, getBasesSnapshot().size() * ROW_H + 4);
        return new Rect(listX, listY, listW, listH);
    }

    private void layoutControls() {
        Rect content = contentRect();
        int maxScroll = maxScroll();
        contentScroll = MathHelper.clamp(contentScroll, 0.0D, maxScroll);

        int gridW = COL_W * 3 + COL_GAP * 2;
        int leftX = content.x + Math.max(0, (content.w - gridW) / 2);

        for (ControlSlot slot : controls) {
            ClickableWidget w = slot.widget;
            int x = leftX + slot.col * (COL_W + COL_GAP);
            int y = content.y + slot.relY - (int) contentScroll;
            if (slot.colSpan == 3) {
                x = leftX;
            }
            w.setX(x);
            w.setY(y);
            boolean visible = y + w.getHeight() > content.y && y < content.bottom();
            w.visible = visible;
            w.active = visible;
        }

        resetLayoutButton.setX(panelX + 6);
        resetLayoutButton.setY(panelY + Math.max(2, (HEADER_H - resetLayoutButton.getHeight()) / 2));
        resetLayoutButton.visible = true;
        resetLayoutButton.active = true;

        constructionButton.setX(panelX + panelW - constructionButton.getWidth() - 6);
        constructionButton.setY(panelY + Math.max(2, (HEADER_H - constructionButton.getHeight()) / 2));
        constructionButton.visible = true;
        constructionButton.active = true;

        layoutVillagePrompt();
    }

    private void layoutVillagePrompt() {
        Rect dialog = mapVillageDialogRect();
        int fieldX = dialog.x + 10;
        int fieldY = dialog.y + 26;
        int buttonY = dialog.bottom() - 28;

        villagePromptField.setX(fieldX);
        villagePromptField.setY(fieldY);
        villagePromptField.setWidth(dialog.w - 20);
        villagePromptField.visible = mapVillagePromptOpen;
        villagePromptField.active = mapVillagePromptOpen;

        villagePromptConfirmButton.setX(dialog.right() - 10 - villagePromptCancelButton.getWidth() - 6 - villagePromptConfirmButton.getWidth());
        villagePromptConfirmButton.setY(buttonY);
        villagePromptConfirmButton.visible = mapVillagePromptOpen;
        villagePromptConfirmButton.active = mapVillagePromptOpen;

        villagePromptCancelButton.setX(dialog.right() - 10 - villagePromptCancelButton.getWidth());
        villagePromptCancelButton.setY(buttonY);
        villagePromptCancelButton.visible = mapVillagePromptOpen;
        villagePromptCancelButton.active = mapVillagePromptOpen;
    }

    private Rect mapVillageDialogRect() {
        int w = Math.min(MODAL_W, this.width - 24);
        int h = MODAL_H;
        int x = (this.width - w) / 2;
        int y = (this.height - h) / 2;
        return new Rect(x, y, w, h);
    }

    private String elideForWidth(String raw, int maxWidth) {
        if (raw == null || raw.isBlank() || maxWidth <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(raw) <= maxWidth) {
            return raw;
        }
        int ellipsisW = this.textRenderer.getWidth("…");
        if (ellipsisW >= maxWidth) {
            return "";
        }
        return this.textRenderer.trimToWidth(raw, maxWidth - ellipsisW) + "…";
    }

    private String fitRowText(String raw, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        return elideForWidth(raw, maxWidth);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, COL_BG);

        // Keep persisted/moved layout snapped into the current viewport at all times.
        clampPanelToViewport();
        contentScroll = MathHelper.clamp(contentScroll, 0.0D, maxScroll());

        List<BaseDto> bases = getBasesSnapshot();
        if (selectedIndex >= bases.size()) {
            selectedIndex = bases.isEmpty() ? -1 : bases.size() - 1;
        }

        layoutControls();

        Rect content = contentRect();

        // Panel frame
        context.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COL_BORDER);
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);

        // Header
        context.fill(panelX, panelY, panelX + panelW, panelY + HEADER_H, COL_HEADER);
        int headerTextLeft = resetLayoutButton.getX() + resetLayoutButton.getWidth() + HEADER_TEXT_PAD;
        int headerTextRight = constructionButton.getX() - HEADER_TEXT_PAD;

        String titleRaw = this.title != null ? this.title.getString() : "Bases";
        String titleText = elideForWidth(titleRaw, Math.max(0, headerTextRight - headerTextLeft));
        int titleY = panelY + Math.max(1, (HEADER_H - this.textRenderer.fontHeight) / 2);
        int titleX = headerTextLeft;
        if (!titleText.isBlank()) {
            context.drawTextWithShadow(this.textRenderer, titleText, titleX, titleY, COL_TITLE);
        }

        boolean statusActive = !statusMessage.isEmpty() && System.currentTimeMillis() < statusExpiry;
        if (!statusActive && !statusMessage.isEmpty()) {
            statusMessage = "";
        }
        String hintRaw = statusActive ? statusMessage : "Drag edges to resize";
        int hintColor = statusActive ? statusColor : 0xFF999999;
        int hintMaxW = Math.max(0, headerTextRight - (titleX + this.textRenderer.getWidth(titleText) + 8));
        String hintText = elideForWidth(hintRaw, hintMaxW);
        if (!hintText.isBlank()) {
            int hintX = headerTextRight - this.textRenderer.getWidth(hintText);
            context.drawTextWithShadow(this.textRenderer, Text.literal(hintText), hintX, titleY, hintColor);
        }

        // Content background
        context.fill(content.x, content.y, content.right(), content.bottom(), 0xAA101010);

        // Section headers + list are custom-drawn and clipped to content
        context.enableScissor(content.x, content.y, content.right(), content.bottom());

        for (SectionHeader hdr : sectionHeaders) {
            int y = content.y + hdr.relY - (int) contentScroll;
            if (y + SECTION_LABEL_H < content.y || y > content.bottom()) {
                continue;
            }
            context.drawTextWithShadow(this.textRenderer, hdr.text(), content.x + 2, y + 2, hdr.color());
            int lineW = this.textRenderer.getWidth(hdr.text());
            context.fill(content.x + 2, y + SECTION_LABEL_H - 2,
                    content.x + 2 + lineW, y + SECTION_LABEL_H - 1,
                    (hdr.color() & 0x00FFFFFF) | 0x44000000);
        }

        Rect list = listRect(content);

        context.fill(list.x, list.y, list.right(), list.bottom(), 0xCC0F0F0F);

        if (bases.isEmpty()) {
            String hint1 = "No bases saved yet.";
            String hint2 = "Type a name above, then click 'Set here'.";
            int hintY = list.y + (list.h - ROW_H * 2) / 2;
            int hintX1 = list.x + (list.w - this.textRenderer.getWidth(hint1)) / 2;
            int hintX2 = list.x + (list.w - this.textRenderer.getWidth(hint2)) / 2;
            context.drawTextWithShadow(this.textRenderer, hint1, hintX1, hintY, 0xFF888888);
            context.drawTextWithShadow(this.textRenderer, hint2, hintX2, hintY + ROW_H, 0xFF666666);
        }

        for (int i = 0; i < bases.size(); i++) {
            BaseDto b = bases.get(i);
            int rowY = list.y + i * ROW_H;
            if (rowY + ROW_H < content.y || rowY > content.bottom()) {
                continue;
            }

            boolean selected = i == selectedIndex;
            int bg = selected ? 0xFF3A2C14 : 0xFF1A1A1A;
            context.fill(list.x + 2, rowY + 1, list.right() - 2, rowY + ROW_H - 1, bg);

            String rawLabel = b != null && b.label != null ? b.label : "(unnamed)";
            String prefix = "";
            if (b != null && b.home) {
                prefix = "[Home] ";
            }
            if (b != null && b.isWall()) {
                prefix = "§d[Wall] " + prefix;
            } else if (b != null && b.isVillage()) {
                prefix = "§b[Village] " + prefix;
            } else if (b != null && b.isBase()) {
                prefix = "§e[Base] " + prefix;
            }
            String label = prefix + rawLabel;

            String rightInfo;
            if (b != null && b.isWall()) {
                String owner = (b.ownerName != null && !b.ownerName.isBlank()) ? b.ownerName : "Unclaimed";
                String detail = b.detailText != null ? b.detailText : "";
                rightInfo = "(" + b.x + "," + b.z + ") §7[" + detail + "] §6{" + owner + "}";
            } else if (b != null && b.isVillage()) {
                String detail = b.detailText != null ? b.detailText : "";
                rightInfo = "(" + b.x + "," + b.z + ") §7[" + detail + "]";
            } else {
                rightInfo = b != null ? ("(" + b.x + ", " + b.y + ", " + b.z + ") §7r=" + b.radius) : "";
            }

            int innerLeft = list.x + 6;
            int innerRight = list.right() - 6;
            int totalInnerWidth = Math.max(24, innerRight - innerLeft);
            int gap = 8;

            int desiredRightWidth = this.textRenderer.getWidth(rightInfo);
            int maxRightWidth = Math.max(48, totalInnerWidth / 2);
            int minLabelWidth = Math.max(54, totalInnerWidth / 3);
            int rightWidth = Math.min(desiredRightWidth, maxRightWidth);
            int labelWidth = totalInnerWidth - rightWidth - gap;

            if (labelWidth < minLabelWidth) {
                labelWidth = minLabelWidth;
                rightWidth = Math.max(24, totalInnerWidth - labelWidth - gap);
            }

            String drawLabel = fitRowText(label, labelWidth);
            String drawRightInfo = fitRowText(rightInfo, rightWidth);
            int drawRightWidth = this.textRenderer.getWidth(drawRightInfo);
            int labelX = innerLeft;
            int rightX = innerRight - drawRightWidth;

            context.drawTextWithShadow(this.textRenderer, drawLabel, labelX, rowY + 2, 0xFFEFEFEF);
            context.drawTextWithShadow(this.textRenderer, drawRightInfo, rightX, rowY + 2, 0xFFB0B0B0);
        }

        // Protected zone entries
        renderZoneEntries(context, content, mouseX, mouseY);

        context.disableScissor();

        // Scrollbar track + thumb
        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int trackX = content.right() + 4;
            int trackY = content.y;
            int trackH = content.h;
            context.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, COL_TRACK);

            int[] thumb = computeThumb(trackY, trackH, maxScroll);
            if (thumb != null) {
                boolean hover = mouseX >= trackX && mouseX < trackX + SCROLLBAR_W
                        && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
                int col = (hover || draggingScroll) ? COL_THUMB_HL : COL_THUMB;
                context.fill(trackX + 1, thumb[0], trackX + SCROLLBAR_W - 1, thumb[0] + thumb[1], col);
            }
        }

        // Draw all content widgets clipped inside content area
        context.enableScissor(content.x, content.y, content.right(), content.bottom());
        super.render(context, mouseX, mouseY, delta);
        context.disableScissor();

        // Header widgets are outside content scissor
        resetLayoutButton.render(context, mouseX, mouseY, delta);
        constructionButton.render(context, mouseX, mouseY, delta);

        if (mapVillagePromptOpen) {
            renderVillagePrompt(context, mouseX, mouseY, delta);
        }
    }

    private void renderVillagePrompt(DrawContext context, int mouseX, int mouseY, float delta) {
        Rect dialog = mapVillageDialogRect();
        context.fill(0, 0, this.width, this.height, 0x90000000);
        context.fill(dialog.x - 1, dialog.y - 1, dialog.right() + 1, dialog.bottom() + 1, COL_BORDER);
        context.fill(dialog.x, dialog.y, dialog.right(), dialog.bottom(), 0xF0151515);
        context.drawTextWithShadow(this.textRenderer, "Map Local Village", dialog.x + 10, dialog.y + 8, COL_TITLE);
        context.drawTextWithShadow(this.textRenderer, "Enter a shared village name.", dialog.x + 10, dialog.y + 16, 0xFFBBBBBB);
        villagePromptField.render(context, mouseX, mouseY, delta);
        villagePromptConfirmButton.render(context, mouseX, mouseY, delta);
        villagePromptCancelButton.render(context, mouseX, mouseY, delta);
    }

    private int[] computeThumb(int trackTop, int trackH, int maxScroll) {
        int fullContentH = contentHeight();
        Rect content = contentRect();
        if (fullContentH <= content.h || trackH <= 0) {
            return null;
        }

        int thumbH = Math.max(THUMB_MIN_H, trackH * content.h / fullContentH);
        thumbH = Math.min(trackH, thumbH);
        int range = Math.max(0, trackH - thumbH);
        int clamped = MathHelper.clamp((int) contentScroll, 0, maxScroll);
        int thumbY = trackTop + (range <= 0 ? 0 : Math.round((float) range * clamped / maxScroll));
        return new int[]{thumbY, thumbH};
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mapVillagePromptOpen) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        Rect content = contentRect();
        if (content.contains(mouseX, mouseY)) {
            int maxScroll = maxScroll();
            if (maxScroll > 0) {
                contentScroll = MathHelper.clamp(contentScroll - verticalAmount * SCROLL_STEP, 0.0D, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean isInside) {
        double mx = click.x();
        double my = click.y();

        if (mapVillagePromptOpen) {
            if (isInsideMapVillageDialog(mx, my)) {
                return super.mouseClicked(click, isInside);
            }
            closeMapVillagePrompt();
            return true;
        }

        // Header buttons take priority.
        if (resetLayoutButton.isMouseOver(mx, my) || constructionButton.isMouseOver(mx, my)) {
            return super.mouseClicked(click, isInside);
        }

        // Resize handles take precedence.
        int mask = resizeMaskFor(mx, my);
        if (mask != 0) {
            draggingResize = true;
            draggingMove = false;
            draggingScroll = false;
            resizeMask = mask;
            dragMouseStartX = (int) mx;
            dragMouseStartY = (int) my;
            dragPanelStartX = panelX;
            dragPanelStartY = panelY;
            dragPanelStartW = panelW;
            dragPanelStartH = panelH;
            return true;
        }

        // Header move drag (excluding reset button area).
        if (isInHeader(mx, my) && !isInHeaderButtonRegion(mx, my)) {
            draggingMove = true;
            draggingResize = false;
            draggingScroll = false;
            dragMouseStartX = (int) mx;
            dragMouseStartY = (int) my;
            dragPanelStartX = panelX;
            dragPanelStartY = panelY;
            dragPanelStartW = panelW;
            dragPanelStartH = panelH;
            return true;
        }

        Rect content = contentRect();
        int maxScroll = maxScroll();

        // Scrollbar drag start.
        if (maxScroll > 0) {
            int trackX = content.right() + 4;
            int trackY = content.y;
            int trackH = content.h;
            if (mx >= trackX && mx < trackX + SCROLLBAR_W && my >= trackY && my < trackY + trackH) {
                int[] thumb = computeThumb(trackY, trackH, maxScroll);
                if (thumb != null && my >= thumb[0] && my < thumb[0] + thumb[1]) {
                    draggingScroll = true;
                    draggingResize = false;
                    draggingMove = false;
                    scrollGrabOffset = (int) my - thumb[0];
                    return true;
                }
                if (thumb != null) {
                    float frac = (float) (my - trackY) / (float) trackH;
                    contentScroll = MathHelper.clamp(frac * maxScroll, 0.0D, maxScroll);
                    draggingScroll = true;
                    scrollGrabOffset = thumb[1] / 2;
                    return true;
                }
            }
        }

        // List row click selection.
        Rect list = listRect(content);
        if (list.contains(mx, my)) {
            int idx = (int) ((my - list.y) / ROW_H);
            List<BaseDto> bases = getBasesSnapshot();
            if (idx >= 0 && idx < bases.size()) {
                selectedIndex = idx;
                return true;
            }
        }

        // Zone entry click handling
        if (handleZoneClick(content, mx, my)) return true;

        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (mapVillagePromptOpen) {
            return false;
        }
        int mx = (int) click.x();
        int my = (int) click.y();

        if (draggingResize) {
            int dx = mx - dragMouseStartX;
            int dy = my - dragMouseStartY;

            int nx = dragPanelStartX;
            int ny = dragPanelStartY;
            int nw = dragPanelStartW;
            int nh = dragPanelStartH;

            if ((resizeMask & RESIZE_LEFT) != 0) {
                nx = dragPanelStartX + dx;
                nw = dragPanelStartW - dx;
            }
            if ((resizeMask & RESIZE_RIGHT) != 0) {
                nw = dragPanelStartW + dx;
            }
            if ((resizeMask & RESIZE_TOP) != 0) {
                ny = dragPanelStartY + dy;
                nh = dragPanelStartH - dy;
            }
            if ((resizeMask & RESIZE_BOTTOM) != 0) {
                nh = dragPanelStartH + dy;
            }

            int maxW = Math.max(PANEL_MIN_W, this.width - PANEL_MARGIN * 2);
            int maxH = Math.max(PANEL_MIN_H, this.height - PANEL_MARGIN * 2);

            nw = MathHelper.clamp(nw, PANEL_MIN_W, maxW);
            nh = MathHelper.clamp(nh, PANEL_MIN_H, maxH);

            if ((resizeMask & RESIZE_LEFT) != 0) {
                nx = dragPanelStartX + (dragPanelStartW - nw);
            }
            if ((resizeMask & RESIZE_TOP) != 0) {
                ny = dragPanelStartY + (dragPanelStartH - nh);
            }

            panelX = nx;
            panelY = ny;
            panelW = nw;
            panelH = nh;
            clampPanelToViewport();
            contentScroll = MathHelper.clamp(contentScroll, 0.0D, maxScroll());
            return true;
        }

        if (draggingMove) {
            int dx = mx - dragMouseStartX;
            int dy = my - dragMouseStartY;
            panelX = dragPanelStartX + dx;
            panelY = dragPanelStartY + dy;
            clampPanelToViewport();
            return true;
        }

        if (draggingScroll) {
            int maxScroll = maxScroll();
            if (maxScroll <= 0) {
                return true;
            }
            Rect content = contentRect();
            int trackY = content.y;
            int trackH = content.h;

            int thumbH = Math.max(THUMB_MIN_H, trackH * content.h / Math.max(1, contentHeight()));
            thumbH = Math.min(trackH, thumbH);
            int minY = trackY;
            int maxY = trackY + trackH - thumbH;
            if (maxY <= minY) {
                return true;
            }

            int desiredY = my - scrollGrabOffset;
            desiredY = MathHelper.clamp(desiredY, minY, maxY);
            double ratio = (double) (desiredY - minY) / (double) (maxY - minY);
            contentScroll = MathHelper.clamp(ratio * maxScroll, 0.0D, maxScroll);
            return true;
        }

        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (mapVillagePromptOpen) {
            return super.mouseReleased(click);
        }
        boolean wasDragging = draggingResize || draggingMove || draggingScroll;
        draggingResize = false;
        draggingMove = false;
        draggingScroll = false;
        resizeMask = 0;

        if (wasDragging) {
            persistPanelPrefs();
            return true;
        }

        return super.mouseReleased(click);
    }

    private boolean isInHeader(double mx, double my) {
        return mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + HEADER_H;
    }

    private boolean isInHeaderButtonRegion(double mx, double my) {
        return isOverButton(resetLayoutButton, mx, my) || isOverButton(constructionButton, mx, my);
    }

    private boolean isOverButton(ButtonWidget button, double mx, double my) {
        return button != null
                && mx >= button.getX()
                && mx < button.getX() + button.getWidth()
                && my >= button.getY()
                && my < button.getY() + button.getHeight();
    }

    private int resizeMaskFor(double mx, double my) {
        boolean withinY = my >= panelY - RESIZE_GRAB && my <= panelY + panelH + RESIZE_GRAB;
        boolean withinX = mx >= panelX - RESIZE_GRAB && mx <= panelX + panelW + RESIZE_GRAB;

        if (!withinX || !withinY) {
            return 0;
        }

        int mask = 0;
        if (Math.abs(mx - panelX) <= RESIZE_GRAB) {
            mask |= RESIZE_LEFT;
        }
        if (Math.abs(mx - (panelX + panelW)) <= RESIZE_GRAB) {
            mask |= RESIZE_RIGHT;
        }
        if (Math.abs(my - panelY) <= RESIZE_GRAB) {
            mask |= RESIZE_TOP;
        }
        if (Math.abs(my - (panelY + panelH)) <= RESIZE_GRAB) {
            mask |= RESIZE_BOTTOM;
        }
        return mask;
    }

    private void applySavedOrDefaultPanel() {
        int maxW = Math.max(PANEL_MIN_W, this.width - PANEL_MARGIN * 2);
        int maxH = Math.max(PANEL_MIN_H, this.height - PANEL_MARGIN * 2);

        int defaultW = Math.min(PANEL_DEFAULT_W, maxW);
        int defaultH = Math.min(PANEL_DEFAULT_H, maxH);

        UiPrefs prefs = CACHED_PREFS;
        panelW = prefs != null && prefs.w() != null ? prefs.w() : defaultW;
        panelH = prefs != null && prefs.h() != null ? prefs.h() : defaultH;

        panelW = MathHelper.clamp(panelW, PANEL_MIN_W, maxW);
        panelH = MathHelper.clamp(panelH, PANEL_MIN_H, maxH);

        int defaultX = (this.width - panelW) / 2;
        int defaultY = Math.max(PANEL_MARGIN, (this.height - panelH) / 2);

        panelX = prefs != null && prefs.x() != null ? prefs.x() : defaultX;
        panelY = prefs != null && prefs.y() != null ? prefs.y() : defaultY;

        clampPanelToViewport();
    }

    private void resetPanelToDefault() {
        int maxW = Math.max(PANEL_MIN_W, this.width - PANEL_MARGIN * 2);
        int maxH = Math.max(PANEL_MIN_H, this.height - PANEL_MARGIN * 2);
        panelW = Math.min(PANEL_DEFAULT_W, maxW);
        panelH = Math.min(PANEL_DEFAULT_H, maxH);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(PANEL_MARGIN, (this.height - panelH) / 2);
        clampPanelToViewport();
        contentScroll = 0.0D;
    }

    private void clampPanelToViewport() {
        int maxW = Math.max(PANEL_MIN_W, this.width - PANEL_MARGIN * 2);
        int maxH = Math.max(PANEL_MIN_H, this.height - PANEL_MARGIN * 2);

        panelW = MathHelper.clamp(panelW, PANEL_MIN_W, maxW);
        panelH = MathHelper.clamp(panelH, PANEL_MIN_H, maxH);

        int minX = PANEL_MARGIN;
        int maxX = this.width - PANEL_MARGIN - panelW;
        int minY = PANEL_MARGIN;
        int maxY = this.height - PANEL_MARGIN - panelH;

        panelX = MathHelper.clamp(panelX, minX, Math.max(minX, maxX));
        panelY = MathHelper.clamp(panelY, minY, Math.max(minY, maxY));
    }

    private void persistPanelPrefs() {
        UiPrefs prefs = new UiPrefs(panelX, panelY, panelW, panelH);
        CACHED_PREFS = prefs;
        saveUiPrefs(prefs);
    }

    private static UiPrefs loadUiPrefs() {
        try {
            if (!Files.exists(UI_PREFS_PATH)) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(UI_PREFS_PATH)) {
                return GSON.fromJson(reader, UiPrefs.class);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveUiPrefs(UiPrefs prefs) {
        try {
            Files.createDirectories(UI_PREFS_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(UI_PREFS_PATH)) {
                GSON.toJson(prefs, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private void requestRefresh() {
        if (ClientPlayNetworking.canSend(RequestBasesPayload.ID)) {
            ClientPlayNetworking.send(new RequestBasesPayload(botAlias));
        }
        requestZoneRefresh();
    }

    private void sendSetHere() {
        String label = nameField != null ? nameField.getText() : "";
        if (label == null || label.isBlank()) {
            showStatus("Enter a base name first.", STATUS_WARN);
            return;
        }
        BaseDto selected = getSelected();
        if (selected != null && selected.isLodestone()) {
            showStatus("Lodestone entries are managed via compasses.", STATUS_WARN);
            return;
        }
        if (!ClientPlayNetworking.canSend(BaseSetPayload.ID)) {
            showStatus("Not connected to server.", STATUS_ERROR);
            return;
        }
        ClientPlayNetworking.send(new BaseSetPayload(label));
        showStatus("Saving base...", STATUS_OK);
        requestRefresh();
    }

    private void sendRemoveSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            showStatus("Select an entry to remove.", STATUS_WARN);
            return;
        }
        if (selected != null && selected.isLodestone()) {
            showStatus("Lodestone entries are managed via compasses.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseRemovePayload.ID)) {
            ClientPlayNetworking.send(new BaseRemovePayload(selected.label));
            showStatus("Removing...", STATUS_OK);
        }
        requestRefresh();
    }

    private void sendRenameSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            showStatus("Select an entry to rename.", STATUS_WARN);
            return;
        }
        if (selected != null && selected.isLodestone()) {
            showStatus("Lodestone entries are managed via compasses.", STATUS_WARN);
            return;
        }
        String newLabel = nameField != null ? nameField.getText() : "";
        if (newLabel == null || newLabel.isBlank()) {
            showStatus("Enter a new name first.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseRenamePayload.ID)) {
            ClientPlayNetworking.send(new BaseRenamePayload(selected.label, newLabel));
            showStatus("Renaming...", STATUS_OK);
        }
        requestRefresh();
    }

    private void sendSetRadiusSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a base first.", STATUS_WARN);
            return;
        }
        if (selected != null && selected.isLodestone()) {
            showStatus("Lodestone entries are managed via compasses.", STATUS_WARN);
            return;
        }
        if (!selected.isBase()) {
            showStatus("Only bases use radius.", STATUS_WARN);
            return;
        }
        String text = nameField != null ? nameField.getText() : "";
        if (text == null || text.isBlank()) {
            showStatus("Enter a number (1-128) above.", STATUS_WARN);
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            showStatus("Enter a number (1-128) above.", STATUS_WARN);
            return;
        }
        if (radius < 1 || radius > 128) {
            showStatus("Radius must be 1-128.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseSetRadiusPayload.ID)) {
            ClientPlayNetworking.send(new BaseSetRadiusPayload(selected.label, radius));
            showStatus("Updating radius...", STATUS_OK);
        }
        requestRefresh();
    }

    private void sendSetHomeSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a base first.", STATUS_WARN);
            return;
        }
        // Lodestone: set as home compass (must come BEFORE isBase check)
        if (selected.isLodestone()) {
            sendChatCommand(this.client, "bot compass home " + botAlias + " " + selected.label);
            showStatus("Setting home compass...", STATUS_OK);
            requestRefresh();
            return;
        }
        if (!selected.isBase()) {
            showStatus("Only bases can be set as home.", STATUS_WARN);
            return;
        }
        if (botAlias.isBlank()) {
            showStatus("No bot selected.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseSetHomePayload.ID)) {
            ClientPlayNetworking.send(new BaseSetHomePayload(botAlias, selected.label));
            showStatus("Setting home...", STATUS_OK);
        }
        requestRefresh();
    }

    private void sendGoToSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a base first.", STATUS_WARN);
            return;
        }
        // Lodestone: use compass travel (must come BEFORE isBase check)
        if (selected.isLodestone()) {
            sendChatCommand(this.client, "bot compass travel " + botAlias + " " + selected.label);
            showStatus("Sending bot via compass...", STATUS_OK);
            requestRefresh();
            return;
        }
        if (!selected.isBase()) {
            showStatus("Only bases are valid navigation targets here.", STATUS_WARN);
            return;
        }
        if (botAlias.isBlank()) {
            showStatus("No bot selected.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseGoToPayload.ID)) {
            ClientPlayNetworking.send(new BaseGoToPayload(botAlias, selected.label));
            showStatus("Sending bot...", STATUS_OK);
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
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify resume " + selected.label);
        close();
    }

    private void sendPatchWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify patch " + selected.label);
        close();
    }

    private void sendAutoPatchWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify patch " + selected.label + " auto");
        close();
    }

    private void sendWallStatus() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify status " + selected.label);
        close();
    }

    private void sendDigMoat() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify moat " + selected.label);
        close();
    }

    private void sendDriftCheckWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify drift " + selected.label);
        close();
    }

    private void sendExpandWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        MinecraftClient mc = this.client;
        sendChatCommand(mc, "bot fortify expand " + selected.label);
        close();
    }

    private void sendClaimWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseClaimWallPayload.ID)) {
            ClientPlayNetworking.send(new BaseClaimWallPayload(selected.label));
            showStatus("Claiming wall...", STATUS_OK);
        }
        requestRefresh();
    }

    private void sendUnclaimWall() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseUnclaimWallPayload.ID)) {
            ClientPlayNetworking.send(new BaseUnclaimWallPayload(selected.label));
            showStatus("Unclaiming wall...", STATUS_OK);
        }
        requestRefresh();
    }

    private void sendPermitWallAccess() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        String grantee = nameField != null ? nameField.getText() : "";
        if (grantee == null || grantee.isBlank()) {
            showStatus("Enter player name above.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseGrantWallAccessPayload.ID)) {
            ClientPlayNetworking.send(new BaseGrantWallAccessPayload(selected.label, grantee.trim()));
            showStatus("Granting access...", STATUS_OK);
        }
        requestRefresh();
    }

    private void sendRevokeWallAccess() {
        BaseDto selected = getSelected();
        if (selected == null || !selected.isWall() || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a wall first.", STATUS_WARN);
            return;
        }
        String grantee = nameField != null ? nameField.getText() : "";
        if (grantee == null || grantee.isBlank()) {
            showStatus("Enter player name above.", STATUS_WARN);
            return;
        }
        if (ClientPlayNetworking.canSend(BaseRevokeWallAccessPayload.ID)) {
            ClientPlayNetworking.send(new BaseRevokeWallAccessPayload(selected.label, grantee.trim()));
            showStatus("Revoking access...", STATUS_OK);
        }
        requestRefresh();
    }

    private void openMapVillagePrompt() {
        mapVillagePromptOpen = true;
        if (villagePromptField != null) {
            villagePromptField.setText("");
            setFocused(villagePromptField);
        }
    }

    private void closeMapVillagePrompt() {
        mapVillagePromptOpen = false;
        if (villagePromptField != null) {
            villagePromptField.setText("");
        }
        setFocused(null);
    }

    private boolean isInsideMapVillageDialog(double mouseX, double mouseY) {
        return mapVillageDialogRect().contains(mouseX, mouseY);
    }

    private void sendMapVillage() {
        String label = villagePromptField != null ? villagePromptField.getText() : "";
        if (label == null || label.isBlank()) {
            showStatus("Enter a village name first.", STATUS_WARN);
            return;
        }
        if (!ClientPlayNetworking.canSend(BaseMapVillagePayload.ID)) {
            showStatus("Not connected to server.", STATUS_ERROR);
            return;
        }
        ClientPlayNetworking.send(new BaseMapVillagePayload(label.trim()));
        closeMapVillagePrompt();
        showStatus("Mapping village...", STATUS_OK);
        requestRefresh();
    }

    // ===== Protected Zones =====

    private void requestZoneWand() {
        if (!ClientPlayNetworking.canSend(net.wcfcarolina13.network.ZoneWandRequestPayload.ID)) {
            showStatus("Not connected to server.", STATUS_ERROR);
            return;
        }
        ClientPlayNetworking.send(new net.wcfcarolina13.network.ZoneWandRequestPayload(""));
        close();
    }

    private void requestZoneRefresh() {
        if (!ClientPlayNetworking.canSend(net.wcfcarolina13.network.RequestZoneListPayload.ID)) return;
        ClientPlayNetworking.send(new net.wcfcarolina13.network.RequestZoneListPayload(""));
    }

    private int renderZoneListHeight() {
        List<net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto> zones = parseZoneList();
        if (zones.isEmpty()) return 12; // "No zones" text
        return zones.size() * 32; // Each zone entry: label line + buttons line
    }

    private boolean handleZoneClick(Rect content, double mx, double my) {
        List<net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto> zones = parseZoneList();
        if (zones.isEmpty()) return false;

        int baseY = content.y + zoneListStartRelY - (int) contentScroll;
        for (int i = 0; i < zones.size(); i++) {
            net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto z = zones.get(i);
            int rowY = baseY + i * 32;
            int btnY = rowY + 14;
            int btnH = 10;
            if (my < btnY || my > btnY + btnH) continue;

            // Check Show/Hide button
            boolean viewing = net.wcfcarolina13.GameAI.services.ZoneVisualizerService.isViewing(
                    z.label(), MinecraftClient.getInstance().player != null
                            ? MinecraftClient.getInstance().player.getUuid() : java.util.UUID.randomUUID());
            String showLabel = viewing ? "\u00A7e[Hide]" : "\u00A7a[Show]";
            int showW = this.textRenderer.getWidth(showLabel);
            int showX = content.x + 6;
            if (mx >= showX && mx < showX + showW) {
                if (ClientPlayNetworking.canSend(net.wcfcarolina13.network.ZoneToggleViewPayload.ID)) {
                    ClientPlayNetworking.send(new net.wcfcarolina13.network.ZoneToggleViewPayload(z.label(), !viewing));
                }
                pendingZoneDeleteLabel = null;
                return true;
            }

            // Check Rename button
            String renameLabel = "\u00A77[Rename]";
            int renameW = this.textRenderer.getWidth(renameLabel);
            int renameX = showX + showW + 8;
            if (mx >= renameX && mx < renameX + renameW) {
                String input = nameField != null ? nameField.getText().trim() : "";
                if (input.isEmpty()) {
                    showStatus("Type new name in the text field above first.", STATUS_WARN);
                } else if (ClientPlayNetworking.canSend(net.wcfcarolina13.network.ZoneEditPayload.ID)) {
                    ClientPlayNetworking.send(new net.wcfcarolina13.network.ZoneEditPayload(z.label(), input));
                }
                pendingZoneDeleteLabel = null;
                return true;
            }

            // Check Delete button (two-click confirm)
            int deleteX = renameX + renameW + 8;
            String deleteLabel = (pendingZoneDeleteLabel != null && pendingZoneDeleteLabel.equals(z.label()))
                    ? "\u00A7c[Confirm?]" : "\u00A7c[Delete]";
            int deleteW = this.textRenderer.getWidth(deleteLabel);
            if (mx >= deleteX && mx < deleteX + deleteW) {
                if (pendingZoneDeleteLabel != null && pendingZoneDeleteLabel.equals(z.label())) {
                    if (ClientPlayNetworking.canSend(net.wcfcarolina13.network.ZoneDeletePayload.ID)) {
                        ClientPlayNetworking.send(new net.wcfcarolina13.network.ZoneDeletePayload(z.label()));
                    }
                    pendingZoneDeleteLabel = null;
                } else {
                    pendingZoneDeleteLabel = z.label();
                }
                return true;
            }
        }
        pendingZoneDeleteLabel = null;
        return false;
    }

    private void renderZoneEntries(DrawContext context, Rect content, int mouseX, int mouseY) {
        List<net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto> zones = parseZoneList();
        int baseY = content.y + zoneListStartRelY - (int) contentScroll;

        if (zones.isEmpty()) {
            if (baseY >= content.y && baseY + 10 <= content.bottom()) {
                context.drawTextWithShadow(this.textRenderer, "No protected zones.", content.x + 6, baseY + 2, 0xFF888888);
            }
            return;
        }

        for (int i = 0; i < zones.size(); i++) {
            net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto z = zones.get(i);
            int rowY = baseY + i * 32;
            if (rowY + 30 < content.y || rowY > content.bottom()) continue;

            // Background
            context.fill(content.x + 2, rowY, content.right() - 2, rowY + 30, 0xFF1A2A2A);

            // Zone label and coordinates
            String label = "\u00A7b" + z.label() + " \u00A77(" + z.minX() + "," + z.minZ()
                    + " \u2192 " + z.maxX() + "," + z.maxZ() + ") \u00A78Y:" + z.minY() + "\u2192" + z.maxY();
            String drawLabel = fitRowText(label, content.w - 12);
            context.drawTextWithShadow(this.textRenderer, drawLabel, content.x + 6, rowY + 2, 0xFFEFEFEF);

            // Inline buttons as clickable text
            int btnY = rowY + 14;
            boolean viewing = net.wcfcarolina13.GameAI.services.ZoneVisualizerService.isViewing(
                    z.label(), MinecraftClient.getInstance().player != null
                            ? MinecraftClient.getInstance().player.getUuid() : java.util.UUID.randomUUID());
            String showLabel = viewing ? "\u00A7e[Hide]" : "\u00A7a[Show]";
            int showW = this.textRenderer.getWidth(showLabel);
            context.drawTextWithShadow(this.textRenderer, showLabel, content.x + 6, btnY, 0xFFFFFFFF);

            String renameLabel = "\u00A77[Rename]";
            int renameX = content.x + 6 + showW + 8;
            context.drawTextWithShadow(this.textRenderer, renameLabel, renameX, btnY, 0xFFFFFFFF);

            String deleteLabel = (pendingZoneDeleteLabel != null && pendingZoneDeleteLabel.equals(z.label()))
                    ? "\u00A7c[Confirm?]" : "\u00A7c[Delete]";
            int deleteX = renameX + this.textRenderer.getWidth(renameLabel) + 8;
            context.drawTextWithShadow(this.textRenderer, deleteLabel, deleteX, btnY, 0xFFFFFFFF);
        }
    }

    private static List<net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto> parseZoneList() {
        try {
            java.lang.reflect.Type type = new TypeToken<List<net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto>>() {}.getType();
            List<net.wcfcarolina13.network.ZoneNetworkManager.ZoneDto> result =
                    new GsonBuilder().disableHtmlEscaping().create().fromJson(LAST_ZONE_LIST_JSON, type);
            return result != null ? result : List.of();
        } catch (Exception e) {
            return List.of();
        }
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

    private void openConstructionScreen() {
        MinecraftClient client = this.client;
        if (client == null) {
            return;
        }
        if (this.parent instanceof ConstructionScreen) {
            client.setScreen(this.parent);
            return;
        }
        client.setScreen(new ConstructionScreen(this, formatBotTarget(botAlias)));
    }

    private static String formatBotTarget(String alias) {
        String value = alias != null ? alias.trim() : "";
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value;
        }
        return value.contains(" ") ? "\"" + value + "\"" : value;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (mapVillagePromptOpen) {
            int key = input != null ? input.key() : -1;
            if (key == 256) {
                closeMapVillagePrompt();
                return true;
            }
            if (key == 257 || key == 335) {
                sendMapVillage();
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        closeMapVillagePrompt();
        persistPanelPrefs();
        MinecraftClient client = this.client;
        if (client != null) {
            client.setScreen(parent);
        }
    }
}
