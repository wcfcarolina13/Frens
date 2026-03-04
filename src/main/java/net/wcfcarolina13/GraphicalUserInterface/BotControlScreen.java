package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GraphicalUserInterface.Widgets.DropdownMenuWidget;
import net.wcfcarolina13.network.ConfigJsonUtil;
import net.wcfcarolina13.network.configNetworkManager;

import java.util.*;

/**
 * Single-bot configuration screen. A dropdown selects the alias; settings are
 * laid out vertically in labelled groups inside a dark bordered panel.
 * Edits are buffered per-alias so switching bots doesn't lose unsaved changes.
 */
public class BotControlScreen extends Screen {

    // ── Layout constants (matching codebase style) ──────────────────────
    private static final int BUTTON_H = 20;
    private static final int TOGGLE_W = 46;           // compact: just "ON"/"OFF"
    private static final int WIDE_TOGGLE_W = 78;      // for multi-word values (Questing/Admin/Training)
    private static final int ROW_H = 30;              // each setting row
    private static final int SECTION_H = 18;          // section header height
    private static final int PANEL_PAD = 8;           // inner padding of settings panel
    private static final int SCROLL_STEP = 14;        // pixels per wheel tick

    // ── Colors (matching BotGuideScreen / BaseManagerScreen palette) ────
    private static final int COL_BG        = 0xD0101010; // full-screen overlay
    private static final int COL_PANEL     = 0xFF121212; // panel fill
    private static final int COL_BORDER    = 0xFF2A2A2A; // panel border
    private static final int COL_TITLE     = 0xFFFFE08A; // gold title
    private static final int COL_INFO      = 0xFFB0B0B0; // light gray info text
    private static final int COL_SECTION   = 0xFFE6D7A3; // tan section header
    private static final int COL_SEC_LINE  = 0x606B522C; // section divider line
    private static final int COL_LABEL     = 0xFFEFEFEF; // setting label (near-white)
    private static final int COL_DESC      = 0xFF7F7F7F; // description (muted gray)
    private static final int COL_SUBTITLE  = 0xFFB0B0B0; // subtitle below dropdown
    private static final int COL_HELPER    = 0xFF9FB6CD; // helper text (blue-gray)
    private static final int COL_TRACK     = 0xFF171717; // scrollbar track
    private static final int COL_THUMB     = 0xFF7A6240; // scrollbar thumb
    private static final int COL_THUMB_HL  = 0xFFB08C40; // scrollbar thumb hover/drag
    private static final int SCROLLBAR_W   = 6;          // scrollbar width
    private static final int THUMB_MIN_H   = 16;         // minimum thumb height

    private final Screen parent;

    // ── Global toggles ──────────────────────────────────────────────────
    private CyclingButtonWidget<Boolean> worldToggle;
    private CyclingButtonWidget<Boolean> survivalRecruitmentToggle;
    private CyclingButtonWidget<Boolean> forcePlaceToggle;
    private CyclingButtonWidget<Boolean> textDialogueToggle;
    private CyclingButtonWidget<Boolean> voicedDialogueGlobalToggle;

    // ── Bot selector ────────────────────────────────────────────────────
    private DropdownMenuWidget aliasDropdown;
    private List<String> aliasList = new ArrayList<>();
    private String selectedAlias;

    // ── Per-bot setting widgets (rebuilt on alias switch) ────────────────
    private final List<SettingGroup> settingGroups = new ArrayList<>();
    private final List<CyclingButtonWidget<?>> settingWidgets = new ArrayList<>();

    // ── Dirty buffer: preserves edits when switching aliases ─────────────
    private final Map<String, SettingsSnapshot> dirtySettings = new LinkedHashMap<>();

    // ── Scroll state ────────────────────────────────────────────────────
    private double scrollOffset;
    private int panelX, panelY, panelW, panelH; // settings panel bounds
    private int scrollAreaH;                     // visible height inside panel
    private boolean draggingScroll;              // scrollbar thumb drag active
    private int scrollGrabOffset;                // mouse Y offset within thumb

    // ── Non-panel widgets (re-rendered outside scissor) ─────────────
    private ButtonWidget saveButton;
    private ButtonWidget closeButton;

    // ── Owner/helper text for current alias ─────────────────────────────
    private String subtitleText = "";
    private String helperText = "";

    public BotControlScreen(Screen parent) {
        super(Text.of("Bot Controls"));
        this.parent = parent;
    }

    // ════════════════════════════════════════════════════════════════════
    //  init
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        // Capture edits before resize clears widgets
        if (selectedAlias != null && !settingWidgets.isEmpty()) {
            captureCurrentWidgets();
        }
        String previousAlias = selectedAlias;

        clearChildren();
        settingGroups.clear();
        settingWidgets.clear();
        scrollOffset = 0;

        int cx = this.width / 2;
        int contentW = Math.min(this.width - 40, 280);
        int lx = cx - contentW / 2;

        // ── Title at y=8 ────────────────────────────────────────────────
        // (drawn in render, not a widget)

        // ── Global toggles ──────────────────────────────────────────────
        int y = 24;
        boolean worldEnabled = Frens.CONFIG.isDefaultLlmWorldEnabled();
        worldToggle = CyclingButtonWidget.onOffBuilder(worldEnabled)
                .build(lx, y, contentW, BUTTON_H,
                        Text.of("LLM World Toggle"), (b, v) -> {});
        worldToggle.setTooltip(Tooltip.of(Text.of(
                "Master switch for LLM chat control in this world.")));
        this.addDrawableChild(worldToggle);

        y += BUTTON_H + 4;
        boolean survivalMode = Frens.CONFIG.isSurvivalRecruitmentMode();
        survivalRecruitmentToggle = CyclingButtonWidget.onOffBuilder(survivalMode)
                .build(lx, y, contentW, BUTTON_H,
                        Text.of("Survival Recruitment"), (b, v) -> {});
        survivalRecruitmentToggle.setTooltip(Tooltip.of(Text.of(
                "When ON: bots won't spawn until you find a village and recruit.")));
        this.addDrawableChild(survivalRecruitmentToggle);

        y += BUTTON_H + 4;
        boolean forcePlace = Frens.CONFIG.isFortifyForcePlaceEnabled();
        forcePlaceToggle = CyclingButtonWidget.onOffBuilder(forcePlace)
                .build(lx, y, contentW, BUTTON_H,
                        Text.of("Fortify Force-Place"), (b, v) -> {});
        forcePlaceToggle.setTooltip(Tooltip.of(Text.of(
                "When ON: bots will force-place edge blocks that have no valid line-of-sight. Bypasses vanilla placement rules.")));
        this.addDrawableChild(forcePlaceToggle);

        y += BUTTON_H + 4;
        boolean textDialogueEnabled = Frens.CONFIG.isTextDialogueEnabled();
        textDialogueToggle = CyclingButtonWidget.onOffBuilder(textDialogueEnabled)
            .build(lx, y, contentW, BUTTON_H,
                Text.of("Global Text Dialogue"), (b, v) -> {});
        textDialogueToggle.setTooltip(Tooltip.of(Text.of(
            "Master toggle for companion dialogue text (chat + overhead lines).")));
        this.addDrawableChild(textDialogueToggle);

        y += BUTTON_H + 4;
        boolean voicedDialogueEnabled = Frens.CONFIG.isVoicedDialogueEnabled();
        voicedDialogueGlobalToggle = CyclingButtonWidget.onOffBuilder(voicedDialogueEnabled)
            .build(lx, y, contentW, BUTTON_H,
                Text.of("Global Voiced Dialogue"), (b, v) -> {});
        voicedDialogueGlobalToggle.setTooltip(Tooltip.of(Text.of(
            "Master toggle for companion voice clips. Current voiced lines use a male voice set. " +
                "If you'd like female/alternate voices, request it and we'll prioritize it on the roadmap.")));
        this.addDrawableChild(voicedDialogueGlobalToggle);

        // ── Alias list & dropdown ───────────────────────────────────────
        y += BUTTON_H + 10;
        aliasList = buildAliasList();
        if (previousAlias != null && aliasList.contains(previousAlias)) {
            selectedAlias = previousAlias;
        } else {
            selectedAlias = aliasList.isEmpty() ? "default" : aliasList.get(0);
        }

        aliasDropdown = new DropdownMenuWidget(
                lx, y, contentW, BUTTON_H,
                Text.of("Select bot"), aliasList);
        aliasDropdown.setSelectedOption(selectedAlias);
        this.addDrawableChild(aliasDropdown);
        updateSubtitleText();

        // ── Settings panel bounds ───────────────────────────────────────
        // subtitle takes ~2 lines below dropdown
        int panelTopY = y + BUTTON_H + 28;
        int panelBottomY = this.height - 42;
        panelX = lx - 2;
        panelY = panelTopY;
        panelW = contentW + 4;
        panelH = Math.max(40, panelBottomY - panelTopY);
        scrollAreaH = panelH - 2; // 1px border each side

        // ── Build setting widgets ───────────────────────────────────────
        rebuildSettingsWidgets();

        // ── Save / Close ────────────────────────────────────────────────
        int btnW = 70;
        int btnGap = 10;
        saveButton = ButtonWidget.builder(Text.of("Save"), button -> {
            saveSettings();
            if (this.client != null) {
                this.client.getToastManager().add(SystemToast.create(this.client,
                        SystemToast.Type.NARRATOR_TOGGLE,
                        Text.of("Bot settings saved"),
                        Text.of("Applied new bot preferences")));
            }
        }).dimensions(cx - btnW - btnGap / 2, this.height - 30, btnW, BUTTON_H).build();
        this.addDrawableChild(saveButton);

        closeButton = ButtonWidget.builder(Text.of("Close"), button -> close())
                .dimensions(cx + btnGap / 2, this.height - 30, btnW, BUTTON_H).build();
        this.addDrawableChild(closeButton);
    }

    private List<String> buildAliasList() {
        LinkedHashSet<String> aliasSet = new LinkedHashSet<>(Frens.CONFIG.getBotGameProfile().keySet());
        aliasSet.add("Jake");
        aliasSet.add("Bob");
        aliasSet.add("default");
        List<String> sorted = new ArrayList<>(aliasSet);
        sorted.sort(Comparator.comparing(name ->
                name.equalsIgnoreCase("default") ? "\u0000" : name.toLowerCase(Locale.ROOT)));
        return sorted;
    }

    private void updateSubtitleText() {
        boolean isDefault = selectedAlias.equalsIgnoreCase("default");
        if (isDefault) {
            subtitleText = "Fallback profile — used when a bot has no override.";
            helperText = "";
        } else {
            ManualConfig.BotOwnership ownership = Frens.CONFIG.getOwner(selectedAlias);
            String ownerName = ownership != null && ownership.ownerName() != null
                    && !ownership.ownerName().isBlank()
                    ? ownership.ownerName() : "Unassigned";
            subtitleText = "Owner: " + ownerName;
            helperText = "Customize how " + selectedAlias + " behaves.";
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Settings snapshot / widget lifecycle
    // ════════════════════════════════════════════════════════════════════

    private record SettingsSnapshot(
            boolean autoRespawnOnDeath, String spawnMode, String gameMode,
            String failsafeSpawnMode,
            boolean teleportDuringSkills, boolean pauseOnFullInventory,
            boolean teleportDuringDropSweep, boolean llmEnabled,
            boolean voicedDialogue) {}

    private record SettingEntry(String label, String desc,
                                CyclingButtonWidget<?> widget, int widgetW) {}
    private record SettingGroup(String name, List<SettingEntry> entries) {}

    @SuppressWarnings("unchecked")
    private void captureCurrentWidgets() {
        if (settingGroups.isEmpty() || selectedAlias == null) return;
        List<CyclingButtonWidget<?>> ws = settingWidgets;
        if (ws.size() < 9) return;
        dirtySettings.put(selectedAlias, new SettingsSnapshot(
                (Boolean) ws.get(0).getValue(),
                (String)  ws.get(1).getValue(),
                (String)  ws.get(2).getValue(),
                (String)  ws.get(3).getValue(),
                (Boolean) ws.get(4).getValue(),
                (Boolean) ws.get(5).getValue(),
                (Boolean) ws.get(6).getValue(),
                (Boolean) ws.get(7).getValue(),
                (Boolean) ws.get(8).getValue()
        ));
    }

    private void rebuildSettingsWidgets() {
        for (CyclingButtonWidget<?> w : settingWidgets) {
            this.remove(w);
        }
        settingWidgets.clear();
        settingGroups.clear();

        SettingsSnapshot snap = dirtySettings.get(selectedAlias);
        ManualConfig.BotControlSettings cfg =
                Frens.CONFIG.getOrCreateBotControl(selectedAlias);
        boolean autoRespawn = snap != null ? snap.autoRespawnOnDeath : cfg.isAutoRespawnOnDeath();
        String  spawnMode  = snap != null ? snap.spawnMode  : cfg.getSpawnMode();
        String  spawnModeUi = canonicalSpawnModeForUi(spawnMode);
        String  gameMode   = snap != null ? snap.gameMode   : cfg.getGameMode();
        String  failsafe   = snap != null ? snap.failsafeSpawnMode : cfg.getFailsafeSpawnMode();
        boolean teleSkills = snap != null ? snap.teleportDuringSkills : cfg.isTeleportDuringSkills();
        boolean pauseInv   = snap != null ? snap.pauseOnFullInventory : cfg.isPauseOnFullInventory();
        boolean teleDrop   = snap != null ? snap.teleportDuringDropSweep : cfg.isTeleportDuringDropSweep();
        boolean llmEnabled = snap != null ? snap.llmEnabled : cfg.isLlmEnabled();
        boolean voiced     = snap != null ? snap.voicedDialogue : cfg.isVoicedDialogue();

        // ── Spawning ────────────────────────────────────────────────────
        List<SettingEntry> spawning = new ArrayList<>();
        spawning.add(makeOnOff("Auto Respawn", "Respawn on death (skip resurrection ritual).", autoRespawn, TOGGLE_W));
        spawning.add(makeString("Spawn Mode", "Training is sandboxed. Questing/Admin use full gameplay presets.",
            spawnModeUi, "training", "questing", "admin",
            v -> Text.of(switch (v) {
                case "admin" -> "Admin";
                case "questing" -> "Questing";
                default -> "Training";
            }), WIDE_TOGGLE_W));
        spawning.add(makeString("Game Mode", "Minecraft gamemode (inventory, damage, etc).",
                gameMode, "survival", "creative",
                v -> Text.of("creative".equals(v) ? "Creative" : "Survival"), WIDE_TOGGLE_W));
        spawning.add(makeString("Failsafe Spawn", "Fallback spawn point when bed/anchor are unavailable.",
                failsafe, "world_spawn", "owner_bed", "saved_base",
                v -> Text.of(switch (v) {
                    case "owner_bed" -> "Owner Bed";
                    case "saved_base" -> "Saved Base";
                    default -> "World Spawn";
                }), WIDE_TOGGLE_W));
        settingGroups.add(new SettingGroup("Spawning", spawning));

        // ── Behavior ────────────────────────────────────────────────────
        List<SettingEntry> behavior = new ArrayList<>();
        behavior.add(makeOnOff("Teleport During Skills", "Emergency teleports in tight shafts.", teleSkills, TOGGLE_W));
        behavior.add(makeOnOff("Pause on Full Inventory", "Pause job when full; /bot resume.", pauseInv, TOGGLE_W));
        behavior.add(makeOnOff("Teleport During Sweeps", "Allow teleporting during drop-sweep cleanup.", teleDrop, TOGGLE_W));
        settingGroups.add(new SettingGroup("Behavior", behavior));

        // ── LLM ─────────────────────────────────────────────────────────
        List<SettingEntry> llm = new ArrayList<>();
        llm.add(makeOnOff("LLM Enabled", "Natural-language control for this bot.", llmEnabled, TOGGLE_W));
        llm.add(makeOnOff("Voiced Dialogue", "Text-to-speech voiced dialogue.", voiced, TOGGLE_W));
        settingGroups.add(new SettingGroup("LLM", llm));
    }

    private String canonicalSpawnModeForUi(String raw) {
        if (raw == null || raw.isBlank()) {
            return "training";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "admin", "play" -> "admin";
            case "questing", "quest" -> "questing";
            case "training", "train" -> "training";
            default -> "training";
        };
    }

    /** Create a compact ON/OFF toggle showing just "ON" or "OFF". */
    private SettingEntry makeOnOff(String label, String desc, boolean value, int w) {
        CyclingButtonWidget<Boolean> btn = CyclingButtonWidget.<Boolean>builder(
                        v -> Text.of(v ? "ON" : "OFF"), () -> value)
                .values(List.of(Boolean.TRUE, Boolean.FALSE))
                .build(0, 0, w, BUTTON_H, Text.empty(), (b, v) -> {});
        this.addDrawableChild(btn);
        settingWidgets.add(btn);
        return new SettingEntry(label, desc, btn, w);
    }

    /** Create a compact string-cycling toggle (value only, no label prefix). */
    private SettingEntry makeString(String label, String desc,
                                    String value, String opt1, String opt2,
                                    java.util.function.Function<String, Text> formatter,
                                    int w) {
        CyclingButtonWidget<String> btn = CyclingButtonWidget.<String>builder(
                        formatter::apply, () -> value)
                .values(opt1, opt2)
                .build(0, 0, w, BUTTON_H, Text.empty(), (b, v) -> {});
        this.addDrawableChild(btn);
        settingWidgets.add(btn);
        return new SettingEntry(label, desc, btn, w);
    }

    /** Create a compact string-cycling toggle with three options. */
    private SettingEntry makeString(String label, String desc,
                                    String value, String opt1, String opt2, String opt3,
                                    java.util.function.Function<String, Text> formatter,
                                    int w) {
        CyclingButtonWidget<String> btn = CyclingButtonWidget.<String>builder(
                        formatter::apply, () -> value)
                .values(List.of(opt1, opt2, opt3))
                .build(0, 0, w, BUTTON_H, Text.empty(), (b, v) -> {});
        this.addDrawableChild(btn);
        settingWidgets.add(btn);
        return new SettingEntry(label, desc, btn, w);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Save
    // ════════════════════════════════════════════════════════════════════

    private void saveSettings() {
        captureCurrentWidgets();

        ManualConfig config = Frens.CONFIG;
        config.setDefaultLlmWorldEnabled(worldToggle.getValue());
        config.setSurvivalRecruitmentMode(survivalRecruitmentToggle.getValue());
        config.setFortifyForcePlaceEnabled(forcePlaceToggle.getValue());
        config.setTextDialogueEnabled(textDialogueToggle.getValue());
        config.setVoicedDialogueEnabled(voicedDialogueGlobalToggle.getValue());

        for (Map.Entry<String, SettingsSnapshot> entry : dirtySettings.entrySet()) {
            ManualConfig.BotControlSettings s = config.getOrCreateBotControl(entry.getKey());
            SettingsSnapshot v = entry.getValue();
            s.setAutoRespawnOnDeath(v.autoRespawnOnDeath);
            s.setSpawnMode(v.spawnMode);
            s.setGameMode(v.gameMode);
            s.setFailsafeSpawnMode(v.failsafeSpawnMode);
            s.setTeleportDuringSkills(v.teleportDuringSkills);
            s.setPauseOnFullInventory(v.pauseOnFullInventory);
            s.setTeleportDuringDropSweep(v.teleportDuringDropSweep);
            s.setLlmEnabled(v.llmEnabled);
            s.setVoicedDialogue(v.voicedDialogue);
        }
        config.save();
        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());
    }

    // ════════════════════════════════════════════════════════════════════
    //  Render
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Full-screen dark overlay
        context.fill(0, 0, this.width, this.height, COL_BG);

        int cx = this.width / 2;
        int contentW = Math.min(this.width - 40, 280);
        int lx = cx - contentW / 2;

        // Title (gold, centered)
        int titleW = this.textRenderer.getWidth(this.title);
        context.drawText(this.textRenderer, this.title,
                cx - titleW / 2, 10, COL_TITLE, true);

        // ── Subtitle / helper below dropdown ────────────────────────────
        int subY = aliasDropdown.getY() + BUTTON_H + 4;
        if (!subtitleText.isEmpty()) {
            context.drawText(this.textRenderer, subtitleText,
                    lx + 2, subY, COL_SUBTITLE, false);
            subY += this.textRenderer.fontHeight + 1;
        }
        if (!helperText.isEmpty()) {
            context.drawText(this.textRenderer, helperText,
                    lx + 2, subY, COL_HELPER, false);
        }

        // ── Settings panel (dark bordered box + text + scrollbar) ────────
        renderSettingsPanel(context, lx, contentW, mouseX, mouseY);

        // ── Render all widgets clipped to the panel ─────────────────────
        // This makes setting buttons slide smoothly under the panel edge
        // instead of vanishing abruptly.
        context.enableScissor(panelX, panelY, panelX + panelW, panelY + panelH);
        super.render(context, mouseX, mouseY, delta);
        context.disableScissor();

        // ── Re-render non-panel widgets outside scissor ─────────────────
        worldToggle.render(context, mouseX, mouseY, delta);
        survivalRecruitmentToggle.render(context, mouseX, mouseY, delta);
        forcePlaceToggle.render(context, mouseX, mouseY, delta);
        textDialogueToggle.render(context, mouseX, mouseY, delta);
        voicedDialogueGlobalToggle.render(context, mouseX, mouseY, delta);
        aliasDropdown.render(context, mouseX, mouseY, delta);
        saveButton.render(context, mouseX, mouseY, delta);
        closeButton.render(context, mouseX, mouseY, delta);

        // Dropdown on top when open (z-order fix)
        if (aliasDropdown.isOpen()) {
            aliasDropdown.renderOnTop(context, mouseX, mouseY, delta);
        }
    }

    private void renderSettingsPanel(DrawContext context, int lx, int contentW,
                                      int mouseX, int mouseY) {
        int maxScroll = Math.max(0, getSettingsContentHeight() - scrollAreaH);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);

        // Panel background + border
        context.fill(panelX - 1, panelY - 1,
                panelX + panelW + 1, panelY + panelH + 1, COL_BORDER);
        context.fill(panelX, panelY,
                panelX + panelW, panelY + panelH, COL_PANEL);

        // Scissor: clip to panel interior
        int clipL = panelX + 1;
        int clipR = panelX + panelW - 1;
        int clipT = panelY + 1;
        int clipB = panelY + panelH - 1;
        context.enableScissor(clipL, clipT, clipR, clipB);

        int innerL = panelX + PANEL_PAD;
        int innerR = panelX + panelW - PANEL_PAD;
        int innerW = innerR - innerL;
        int currentY = clipT + 4 - (int) scrollOffset;

        for (SettingGroup group : settingGroups) {
            // Section header: "Spawning" with divider line
            int headerY = currentY + 4;
            String headerText = group.name;
            int textW = this.textRenderer.getWidth(headerText);
            context.drawText(this.textRenderer, headerText,
                    innerL, headerY, COL_SECTION, false);
            int lineY = headerY + this.textRenderer.fontHeight / 2;
            context.fill(innerL + textW + 6, lineY,
                    innerR, lineY + 1, COL_SEC_LINE);
            currentY += SECTION_H;

            for (int ei = 0; ei < group.entries.size(); ei++) {
                SettingEntry entry = group.entries.get(ei);
                int btnY = currentY + 4;

                // Row separator (subtle line between rows, not before the first)
                if (ei > 0) {
                    context.fill(innerL, currentY, innerR - SCROLLBAR_W - 2,
                            currentY + 1, 0x20FFFFFF);
                }

                // Label (near-white, left)
                context.drawText(this.textRenderer, entry.label,
                        innerL + 2, currentY + 4, COL_LABEL, false);
                // Description (muted gray, truncated to avoid button)
                int descMaxW = innerW - entry.widgetW - SCROLLBAR_W - 12;
                String desc = entry.desc;
                if (this.textRenderer.getWidth(desc) > descMaxW) {
                    desc = this.textRenderer.trimToWidth(desc, descMaxW - 6) + "..";
                }
                context.drawText(this.textRenderer, desc,
                        innerL + 2,
                        currentY + 4 + this.textRenderer.fontHeight + 2,
                        COL_DESC, false);

                // Position button right-aligned, leaving room for scrollbar
                // (always visible — scissor in render() handles clipping)
                entry.widget.setX(innerR - entry.widgetW - SCROLLBAR_W - 2);
                entry.widget.setY(btnY);
                entry.widget.visible = true;

                currentY += ROW_H;
            }
        }

        context.disableScissor();

        // ── Scrollbar (only if content overflows) ───────────────────────
        if (maxScroll > 0) {
            int trackX = panelX + panelW - SCROLLBAR_W - 1;
            int trackT = panelY + 1;
            int trackB = panelY + panelH - 1;
            int trackH = trackB - trackT;
            context.fill(trackX, trackT, trackX + SCROLLBAR_W, trackB, COL_TRACK);

            int[] thumb = computeThumb(trackT, trackH, maxScroll);
            if (thumb != null) {
                boolean hover = mouseX >= trackX && mouseX < trackX + SCROLLBAR_W
                        && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
                int col = (hover || draggingScroll) ? COL_THUMB_HL : COL_THUMB;
                context.fill(trackX + 1, thumb[0], trackX + SCROLLBAR_W - 1,
                        thumb[0] + thumb[1], col);
            }
        }
    }

    /** Returns [thumbY, thumbH] or null if no scrollbar needed. */
    private int[] computeThumb(int trackTop, int trackH, int maxScroll) {
        int contentH = getSettingsContentHeight();
        if (contentH <= scrollAreaH || trackH <= 0) return null;
        int thumbH = Math.max(THUMB_MIN_H, trackH * scrollAreaH / contentH);
        thumbH = Math.min(trackH, thumbH);
        int range = Math.max(0, trackH - thumbH);
        int clamped = MathHelper.clamp((int) scrollOffset, 0, maxScroll);
        int thumbY = trackTop + (range <= 0 ? 0
                : Math.round((float) range * clamped / maxScroll));
        return new int[]{thumbY, thumbH};
    }

    private int getSettingsContentHeight() {
        int h = 4; // top padding
        for (SettingGroup group : settingGroups) {
            h += SECTION_H;
            h += group.entries.size() * ROW_H;
        }
        return h;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Input handling
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        double mx = click.x();
        double my = click.y();

        // Dropdown gets first priority
        if (aliasDropdown.isMouseOver(mx, my)) {
            String before = aliasDropdown.getSelectedOption();
            boolean handled = super.mouseClicked(click, isInside);
            String after = aliasDropdown.getSelectedOption();
            if (after != null && !after.equals(before)) {
                onAliasChanged(before, after);
            }
            return handled;
        }

        // When dropdown is open, swallow clicks (let it close)
        if (aliasDropdown.isOpen()) {
            return super.mouseClicked(click, isInside);
        }

        // Scrollbar thumb grab
        int maxScroll = Math.max(0, getSettingsContentHeight() - scrollAreaH);
        if (maxScroll > 0) {
            int trackX = panelX + panelW - SCROLLBAR_W - 1;
            int trackT = panelY + 1;
            int trackH = panelH - 2;
            if (mx >= trackX && mx < trackX + SCROLLBAR_W
                    && my >= trackT && my < trackT + trackH) {
                int[] thumb = computeThumb(trackT, trackH, maxScroll);
                if (thumb != null && my >= thumb[0] && my < thumb[0] + thumb[1]) {
                    draggingScroll = true;
                    scrollGrabOffset = (int) my - thumb[0];
                    return true;
                }
                // Click on track but outside thumb — jump to position
                if (thumb != null) {
                    float frac = (float) (my - trackT) / trackH;
                    scrollOffset = MathHelper.clamp(frac * maxScroll, 0, maxScroll);
                    draggingScroll = true;
                    int[] newThumb = computeThumb(trackT, trackH, maxScroll);
                    scrollGrabOffset = newThumb != null ? (int) my - newThumb[0] : 0;
                    return true;
                }
            }
        }

        return super.mouseClicked(click, isInside);
    }

    private void onAliasChanged(String oldAlias, String newAlias) {
        captureCurrentWidgets();
        selectedAlias = newAlias;
        updateSubtitleText();
        scrollOffset = 0;
        rebuildSettingsWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                  double horizontalAmount, double verticalAmount) {
        // Dropdown scroll priority when open
        if (aliasDropdown.isOpen() && aliasDropdown.isMouseOver(mouseX, mouseY)) {
            return aliasDropdown.mouseScrolled(mouseX, mouseY,
                    horizontalAmount, verticalAmount);
        }

        // Settings panel scroll
        if (mouseX >= panelX && mouseX <= panelX + panelW
                && mouseY >= panelY && mouseY <= panelY + panelH) {
            int maxScroll = Math.max(0, getSettingsContentHeight() - scrollAreaH);
            scrollOffset = MathHelper.clamp(
                    scrollOffset - verticalAmount * SCROLL_STEP, 0, maxScroll);
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click,
                                 double deltaX, double deltaY) {
        if (draggingScroll) {
            int maxScroll = Math.max(0, getSettingsContentHeight() - scrollAreaH);
            if (maxScroll <= 0) return true;
            int trackT = panelY + 1;
            int trackH = panelH - 2;
            int thumbH = Math.max(THUMB_MIN_H, trackH * scrollAreaH
                    / Math.max(1, getSettingsContentHeight()));
            thumbH = Math.min(trackH, thumbH);
            int minY = trackT;
            int maxY = trackT + trackH - thumbH;
            if (maxY <= minY) return true;
            int desiredY = (int) click.y() - scrollGrabOffset;
            desiredY = MathHelper.clamp(desiredY, minY, maxY);
            double ratio = (double) (desiredY - minY) / (double) (maxY - minY);
            scrollOffset = MathHelper.clamp(ratio * maxScroll, 0, maxScroll);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
