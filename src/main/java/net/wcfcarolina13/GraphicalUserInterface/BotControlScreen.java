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
 * laid out vertically in labelled groups. Edits are buffered per-alias so
 * switching bots doesn't lose unsaved changes.
 */
public class BotControlScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int SETTING_ROW_HEIGHT = 38;  // label + description + padding
    private static final int SECTION_HEADER_HEIGHT = 22;
    private static final int SETTING_BUTTON_WIDTH = 70;

    private final Screen parent;

    // ── Global toggles ──────────────────────────────────────────────────
    private CyclingButtonWidget<Boolean> worldToggle;
    private CyclingButtonWidget<Boolean> survivalRecruitmentToggle;

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
    private int settingsTopY;   // top of scrollable settings area
    private int settingsBottomY; // bottom of scrollable settings area

    // ── Owner/helper text for current alias ─────────────────────────────
    private String subtitleText = "";
    private String helperText = "";

    public BotControlScreen(Screen parent) {
        super(Text.of("Bot Controls"));
        this.parent = parent;
    }

    // ════════════════════════════════════════════════════════════════════
    //  init / rebuild
    // ════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        // If re-initializing (resize), capture current edits first
        if (selectedAlias != null && !settingWidgets.isEmpty()) {
            captureCurrentWidgets();
        }
        String previousAlias = selectedAlias;

        clearChildren();
        settingGroups.clear();
        settingWidgets.clear();
        scrollOffset = 0;

        int centerX = this.width / 2;
        int contentWidth = Math.min(this.width - 40, 340);
        int leftX = (this.width - contentWidth) / 2;

        // ── Global toggles ──────────────────────────────────────────────
        int globalY = 46;
        boolean worldEnabled = Frens.CONFIG.isDefaultLlmWorldEnabled();
        worldToggle = CyclingButtonWidget.onOffBuilder(worldEnabled)
                .build(leftX, globalY, contentWidth, 20,
                        Text.of("LLM World Toggle"), (button, value) -> {});
        worldToggle.setTooltip(Tooltip.of(Text.of("Master switch for LLM chat control in this world.")));
        this.addDrawableChild(worldToggle);

        boolean survivalMode = Frens.CONFIG.isSurvivalRecruitmentMode();
        survivalRecruitmentToggle = CyclingButtonWidget.onOffBuilder(survivalMode)
                .build(leftX, globalY + 24, contentWidth, 20,
                        Text.of("Survival Recruitment"), (button, value) -> {});
        survivalRecruitmentToggle.setTooltip(Tooltip.of(Text.of("When ON: bots won't spawn until you find a village and recruit.")));
        this.addDrawableChild(survivalRecruitmentToggle);

        // ── Alias list ──────────────────────────────────────────────────
        aliasList = buildAliasList();
        if (previousAlias != null && aliasList.contains(previousAlias)) {
            selectedAlias = previousAlias;
        } else {
            selectedAlias = aliasList.isEmpty() ? "default" : aliasList.get(0);
        }

        // ── Dropdown ────────────────────────────────────────────────────
        int dropdownY = globalY + 58;
        aliasDropdown = new DropdownMenuWidget(
                leftX, dropdownY, contentWidth, 20,
                Text.of("Select bot"), aliasList);
        aliasDropdown.setSelectedOption(selectedAlias);
        this.addDrawableChild(aliasDropdown);

        // ── Subtitle / helper ───────────────────────────────────────────
        updateSubtitleText();

        // ── Settings area bounds ────────────────────────────────────────
        settingsTopY = dropdownY + 48;
        settingsBottomY = this.height - 50;

        // ── Build setting widgets for selected alias ────────────────────
        rebuildSettingsWidgets();

        // ── Save / Close buttons ────────────────────────────────────────
        ButtonWidget saveButton = ButtonWidget.builder(Text.of("Save"), button -> {
            saveSettings();
            if (this.client != null) {
                this.client.getToastManager().add(SystemToast.create(this.client,
                        SystemToast.Type.NARRATOR_TOGGLE,
                        Text.of("Bot settings saved"), Text.of("Applied new bot preferences")));
            }
        }).dimensions(centerX - 100, this.height - 34, 90, 20).build();
        this.addDrawableChild(saveButton);

        ButtonWidget closeButton = ButtonWidget.builder(Text.of("Close"), button -> close())
                .dimensions(centerX + 10, this.height - 34, 90, 20).build();
        this.addDrawableChild(closeButton);
    }

    /** Build sorted alias list: "default" first, then alphabetical. */
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

    /** Update subtitle/helper text from config for the currently selected alias. */
    private void updateSubtitleText() {
        boolean isDefault = selectedAlias.equalsIgnoreCase("default");
        if (isDefault) {
            subtitleText = "Fallback profile used whenever a bot has no alias override.";
            helperText = "";
        } else {
            ManualConfig.BotOwnership ownership = Frens.CONFIG.getOwner(selectedAlias);
            String ownerName = ownership != null && ownership.ownerName() != null && !ownership.ownerName().isBlank()
                    ? ownership.ownerName() : "Unassigned";
            subtitleText = "Owner: " + ownerName;
            helperText = "Customize how " + selectedAlias + " behaves in-game.";
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Settings widget lifecycle
    // ════════════════════════════════════════════════════════════════════

    /** Snapshot: captures the 8 per-bot toggle values. */
    private record SettingsSnapshot(
            boolean autoSpawn, String spawnMode, String gameMode,
            boolean teleportDuringSkills, boolean pauseOnFullInventory,
            boolean teleportDuringDropSweep, boolean llmEnabled, boolean voicedDialogue) {}

    private record SettingEntry(String label, String description, CyclingButtonWidget<?> widget) {}
    private record SettingGroup(String name, List<SettingEntry> entries) {}

    /** Capture the 8 currently-displayed widget values into the dirty buffer. */
    private void captureCurrentWidgets() {
        if (settingGroups.isEmpty() || selectedAlias == null) return;
        // Flatten widgets: Spawning(3), Behavior(3), LLM(2)
        List<CyclingButtonWidget<?>> ws = settingWidgets;
        if (ws.size() < 8) return;
        dirtySettings.put(selectedAlias, new SettingsSnapshot(
                (Boolean) ws.get(0).getValue(),
                (String) ws.get(1).getValue(),
                (String) ws.get(2).getValue(),
                (Boolean) ws.get(3).getValue(),
                (Boolean) ws.get(4).getValue(),
                (Boolean) ws.get(5).getValue(),
                (Boolean) ws.get(6).getValue(),
                (Boolean) ws.get(7).getValue()
        ));
    }

    /** Remove old per-bot widgets, create new ones for selectedAlias. */
    private void rebuildSettingsWidgets() {
        // Remove old setting widgets from drawable children
        for (CyclingButtonWidget<?> w : settingWidgets) {
            this.remove(w);
        }
        settingWidgets.clear();
        settingGroups.clear();

        // Resolve initial values: dirty buffer first, then config
        SettingsSnapshot snap = dirtySettings.get(selectedAlias);
        ManualConfig.BotControlSettings cfg = Frens.CONFIG.getOrCreateBotControl(selectedAlias);
        boolean autoSpawn     = snap != null ? snap.autoSpawn     : cfg.isAutoSpawn();
        String  spawnMode     = snap != null ? snap.spawnMode     : cfg.getSpawnMode();
        String  gameMode      = snap != null ? snap.gameMode      : cfg.getGameMode();
        boolean teleSkills    = snap != null ? snap.teleportDuringSkills    : cfg.isTeleportDuringSkills();
        boolean pauseInv      = snap != null ? snap.pauseOnFullInventory   : cfg.isPauseOnFullInventory();
        boolean teleDrop      = snap != null ? snap.teleportDuringDropSweep: cfg.isTeleportDuringDropSweep();
        boolean llmEnabled    = snap != null ? snap.llmEnabled    : cfg.isLlmEnabled();
        boolean voicedDialogue= snap != null ? snap.voicedDialogue: cfg.isVoicedDialogue();

        int contentWidth = Math.min(this.width - 40, 340);
        int leftX = (this.width - contentWidth) / 2;
        int btnX = leftX + contentWidth - SETTING_BUTTON_WIDTH;

        // ── Spawning ────────────────────────────────────────────────────
        List<SettingEntry> spawning = new ArrayList<>();

        CyclingButtonWidget<Boolean> autoSpawnBtn = CyclingButtonWidget.onOffBuilder(autoSpawn)
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Auto Spawn"), (b, v) -> {});
        autoSpawnBtn.setTooltip(Tooltip.of(Text.of("Automatically spawn this bot at login.")));
        this.addDrawableChild(autoSpawnBtn);
        settingWidgets.add(autoSpawnBtn);
        spawning.add(new SettingEntry("Auto Spawn",
                "Automatically spawn this bot at login using the saved location.", autoSpawnBtn));

        CyclingButtonWidget<String> spawnModeBtn = CyclingButtonWidget.<String>builder(
                        v -> Text.of("play".equals(v) ? "Play" : "Training"),
                        () -> spawnMode)
                .values("training", "play")
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Mode"), (b, v) -> {});
        spawnModeBtn.setTooltip(Tooltip.of(Text.of("Training keeps the bot sandboxed. Play enables full AI.")));
        this.addDrawableChild(spawnModeBtn);
        settingWidgets.add(spawnModeBtn);
        spawning.add(new SettingEntry("Spawn Mode",
                "Training keeps the bot sandboxed. Play enables full AI behaviors.", spawnModeBtn));

        CyclingButtonWidget<String> gameModeBtn = CyclingButtonWidget.<String>builder(
                        v -> Text.of("creative".equals(v) ? "Creative" : "Survival"),
                        () -> gameMode)
                .values("survival", "creative")
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Game"), (b, v) -> {});
        gameModeBtn.setTooltip(Tooltip.of(Text.of("Minecraft gamemode for this bot.")));
        this.addDrawableChild(gameModeBtn);
        settingWidgets.add(gameModeBtn);
        spawning.add(new SettingEntry("Game Mode",
                "Minecraft gamemode for this bot (affects inventory, damage, etc).", gameModeBtn));

        settingGroups.add(new SettingGroup("SPAWNING", spawning));

        // ── Behavior ────────────────────────────────────────────────────
        List<SettingEntry> behavior = new ArrayList<>();

        CyclingButtonWidget<Boolean> teleSkillsBtn = CyclingButtonWidget.onOffBuilder(teleSkills)
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Teleport"), (b, v) -> {});
        teleSkillsBtn.setTooltip(Tooltip.of(Text.of("Allow emergency teleports during skills.")));
        this.addDrawableChild(teleSkillsBtn);
        settingWidgets.add(teleSkillsBtn);
        behavior.add(new SettingEntry("Teleport During Skills",
                "Allow emergency teleports during skills (needed for tight shafts).", teleSkillsBtn));

        CyclingButtonWidget<Boolean> pauseInvBtn = CyclingButtonWidget.onOffBuilder(pauseInv)
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Pause Inv"), (b, v) -> {});
        pauseInvBtn.setTooltip(Tooltip.of(Text.of("Pause when inventory is full.")));
        this.addDrawableChild(pauseInvBtn);
        settingWidgets.add(pauseInvBtn);
        behavior.add(new SettingEntry("Pause on Full Inventory",
                "Pause the job when inventory is full; resume with /bot resume.", pauseInvBtn));

        CyclingButtonWidget<Boolean> teleDropBtn = CyclingButtonWidget.onOffBuilder(teleDrop)
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Drop Teleport"), (b, v) -> {});
        teleDropBtn.setTooltip(Tooltip.of(Text.of("Allow teleports when collecting drops.")));
        this.addDrawableChild(teleDropBtn);
        settingWidgets.add(teleDropBtn);
        behavior.add(new SettingEntry("Teleport During Drop Sweep",
                "Allow teleports when collecting drops after mining.", teleDropBtn));

        settingGroups.add(new SettingGroup("BEHAVIOR", behavior));

        // ── LLM ─────────────────────────────────────────────────────────
        List<SettingEntry> llm = new ArrayList<>();

        CyclingButtonWidget<Boolean> llmBtn = CyclingButtonWidget.onOffBuilder(llmEnabled)
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("LLM Bot"), (b, v) -> {});
        llmBtn.setTooltip(Tooltip.of(Text.of("Enable natural-language control.")));
        this.addDrawableChild(llmBtn);
        settingWidgets.add(llmBtn);
        llm.add(new SettingEntry("LLM Enabled",
                "Enable natural-language control for this bot.", llmBtn));

        CyclingButtonWidget<Boolean> voiceBtn = CyclingButtonWidget.onOffBuilder(voicedDialogue)
                .build(btnX, 0, SETTING_BUTTON_WIDTH, BUTTON_HEIGHT, Text.of("Voiced"), (b, v) -> {});
        voiceBtn.setTooltip(Tooltip.of(Text.of("Enable text-to-speech voiced dialogue.")));
        this.addDrawableChild(voiceBtn);
        settingWidgets.add(voiceBtn);
        llm.add(new SettingEntry("Voiced Dialogue",
                "Enable text-to-speech voiced dialogue for this bot.", voiceBtn));

        settingGroups.add(new SettingGroup("LLM", llm));
    }

    // ════════════════════════════════════════════════════════════════════
    //  Save
    // ════════════════════════════════════════════════════════════════════

    private void saveSettings() {
        // Capture the currently displayed alias
        captureCurrentWidgets();

        ManualConfig config = Frens.CONFIG;
        config.setDefaultLlmWorldEnabled(worldToggle.getValue());
        config.setSurvivalRecruitmentMode(survivalRecruitmentToggle.getValue());

        // Write only aliases that were actually visited/edited
        for (Map.Entry<String, SettingsSnapshot> entry : dirtySettings.entrySet()) {
            ManualConfig.BotControlSettings settings = config.getOrCreateBotControl(entry.getKey());
            SettingsSnapshot s = entry.getValue();
            settings.setAutoSpawn(s.autoSpawn);
            settings.setSpawnMode(s.spawnMode);
            settings.setGameMode(s.gameMode);
            settings.setTeleportDuringSkills(s.teleportDuringSkills);
            settings.setPauseOnFullInventory(s.pauseOnFullInventory);
            settings.setTeleportDuringDropSweep(s.teleportDuringDropSweep);
            settings.setLlmEnabled(s.llmEnabled);
            settings.setVoicedDialogue(s.voicedDialogue);
        }
        config.save();
        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());
    }

    // ════════════════════════════════════════════════════════════════════
    //  Render
    // ════════════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Dark background
        context.fill(0, 0, this.width, this.height, 0xB0000000);

        int contentWidth = Math.min(this.width - 40, 340);
        int leftX = (this.width - contentWidth) / 2;
        int centerX = this.width / 2;

        // Title
        context.drawText(this.textRenderer, this.title,
                centerX - this.textRenderer.getWidth(this.title) / 2, 12, 0xFFFFFF, true);

        // Info text
        List<OrderedText> infoLines = this.textRenderer.wrapLines(
                Text.of("Per-bot customization. Switch bots with the dropdown; changes are buffered until you Save."),
                contentWidth);
        int infoY = 26;
        for (OrderedText line : infoLines) {
            context.drawText(this.textRenderer, line,
                    centerX - this.textRenderer.getWidth(line) / 2, infoY, 0xFFD5A6, false);
            infoY += this.textRenderer.fontHeight;
        }

        // ── Subtitle / helper below dropdown ────────────────────────────
        int subY = aliasDropdown.getY() + 24;
        if (!subtitleText.isEmpty()) {
            context.drawText(this.textRenderer, subtitleText, leftX + 2, subY, 0xFFC6C6C6, false);
            subY += this.textRenderer.fontHeight + 1;
        }
        if (!helperText.isEmpty()) {
            context.drawText(this.textRenderer, helperText, leftX + 2, subY, 0xFF9FB6CD, false);
        }

        // ── Scrollable settings area ────────────────────────────────────
        renderSettingsArea(context, leftX, contentWidth, mouseX, mouseY);

        // All standard widgets (global toggles, dropdown button, setting buttons, save/close)
        super.render(context, mouseX, mouseY, delta);

        // Re-render dropdown on top when open (z-order fix)
        if (aliasDropdown.isOpen()) {
            aliasDropdown.renderOnTop(context, mouseX, mouseY, delta);
        }
    }

    private void renderSettingsArea(DrawContext context, int leftX, int contentWidth, int mouseX, int mouseY) {
        int maxScroll = Math.max(0, getSettingsContentHeight() - (settingsBottomY - settingsTopY));
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);

        context.enableScissor(leftX - 4, settingsTopY, leftX + contentWidth + 4, settingsBottomY);

        int currentY = settingsTopY - (int) scrollOffset;
        int btnX = leftX + contentWidth - SETTING_BUTTON_WIDTH;

        for (SettingGroup group : settingGroups) {
            // Section header
            int headerTextY = currentY + 6;
            String headerLabel = "── " + group.name + " ";
            int headerTextWidth = this.textRenderer.getWidth(headerLabel);
            context.drawText(this.textRenderer, headerLabel, leftX, headerTextY, 0xFFE6C17B, false);
            // Horizontal line after header text
            int lineY = headerTextY + this.textRenderer.fontHeight / 2;
            context.fill(leftX + headerTextWidth + 2, lineY, leftX + contentWidth, lineY + 1, 0x80E6C17B);
            currentY += SECTION_HEADER_HEIGHT;

            for (SettingEntry entry : group.entries) {
                boolean visible = currentY + SETTING_ROW_HEIGHT > settingsTopY && currentY < settingsBottomY;

                // Label (white)
                if (visible) {
                    context.drawText(this.textRenderer, entry.label, leftX + 4, currentY + 4, 0xFFFFFFFF, false);
                    // Description (gray, below label)
                    context.drawText(this.textRenderer, entry.description, leftX + 4,
                            currentY + 4 + this.textRenderer.fontHeight + 1, 0xFF888888, false);
                }

                // Position the button right-aligned
                entry.widget.setX(btnX);
                entry.widget.setY(currentY + 2);
                entry.widget.visible = visible;

                currentY += SETTING_ROW_HEIGHT;
            }
        }

        context.disableScissor();
    }

    private int getSettingsContentHeight() {
        int height = 0;
        for (SettingGroup group : settingGroups) {
            height += SECTION_HEADER_HEIGHT;
            height += group.entries.size() * SETTING_ROW_HEIGHT;
        }
        return height;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Input handling
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        double mouseX = click.x();
        double mouseY = click.y();

        // Dropdown gets first priority
        if (aliasDropdown.isMouseOver(mouseX, mouseY)) {
            String before = aliasDropdown.getSelectedOption();
            boolean handled = super.mouseClicked(click, isInside);
            String after = aliasDropdown.getSelectedOption();
            if (after != null && !after.equals(before)) {
                onAliasChanged(before, after);
            }
            return handled;
        }

        // When dropdown is open, don't forward clicks to settings widgets
        if (aliasDropdown.isOpen()) {
            // Let the dropdown close itself
            return super.mouseClicked(click, isInside);
        }

        return super.mouseClicked(click, isInside);
    }

    private void onAliasChanged(String oldAlias, String newAlias) {
        // Save current widget state to dirty buffer
        captureCurrentWidgets();
        selectedAlias = newAlias;
        updateSubtitleText();
        scrollOffset = 0;
        rebuildSettingsWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Dropdown scroll priority when open
        if (aliasDropdown.isOpen() && aliasDropdown.isMouseOver(mouseX, mouseY)) {
            return aliasDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        // Settings area scroll
        if (mouseY >= settingsTopY && mouseY <= settingsBottomY) {
            int maxScroll = Math.max(0, getSettingsContentHeight() - (settingsBottomY - settingsTopY));
            scrollOffset = MathHelper.clamp(scrollOffset - verticalAmount * 12, 0, maxScroll);
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
