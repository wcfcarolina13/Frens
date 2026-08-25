package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GraphicalUserInterface.Widgets.DropdownMenuWidget;
import net.wcfcarolina13.network.ConfigJsonUtil;
import net.wcfcarolina13.network.configNetworkManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unified Bot settings screen.
 *
 * Global toggles live in a collapsible panel at the top (starts collapsed to
 * maximise space for per-bot settings).  Per-bot settings scroll below in
 * sections (Spawning, Behavior, LLM).  The Permissions editor is reachable
 * via a footer button.
 */
public class BotControlScreen extends Screen {

    // ── Global toggle definitions ─────────────────────────────────────────

    private record GlobalToggleDef(String label, String hint) {}

    private static final List<GlobalToggleDef> GLOBAL_TOGGLES = List.of(
            new GlobalToggleDef("LLM World", "Master for the classic LLM chat path (per-bot LLM Chat must also be ON). Soul-bound bots ignore this — their conversation runs through Soul Chat below. Turn off to keep non-soul bots command and UI driven only."),
            new GlobalToggleDef("Recruitment", "Questing mode for this world. New companions must be recruited through village/settlement progression instead of acting like fully unlocked admin bots."),
            new GlobalToggleDef("Force-Place", "Lets some construction helpers use a non-vanilla placement fallback when normal block placement fails on awkward ledges, corners, or tight build edges."),
            new GlobalToggleDef("Teleport", "When off, no bot can teleport or snap during skills regardless of per-bot settings. When on, individual per-bot teleport settings apply."),
            new GlobalToggleDef("Text Chat", "Master kill switch for scripted bot text: chat lines, overhead text, and subtitles. While ON, Adv… mutes individual categories — same rule as Voice. Soul Chat replies always show. With this off and Voice on, lines with audio become voice-only."),
            new GlobalToggleDef("Voice", "Master for all bot audio: the baked voice lines AND the soul TTS voice. Per-bot Voiced Dialogue can additionally mute a single bot; Adv… mutes categories."),
            new GlobalToggleDef("Soul Chat", "Conversational soul pilot (local LLM). When on, talking to a soul-bound bot routes through its soul instead of the classic LLM path. Same switch as /bot soul enable."),
            new GlobalToggleDef("Soul Voice", "Text-to-speech for soul replies in the bot's cloned voice. Requires a configured TTS engine (check /bot soul voice status). Also obeys the Voice master toggle above.")
    );

    /** Indices in GLOBAL_TOGGLES (wired in init() and saveSettings() — move all three sites together). */
    private static final int TEXT_TOGGLE_INDEX = 4;
    private static final int VOICE_TOGGLE_INDEX = 5;
    private static final int SOUL_CHAT_TOGGLE_INDEX = 6;
    private static final int SOUL_VOICE_TOGGLE_INDEX = 7;
    private static final int VOICE_ADV_W = 34;

    // Layout constants
    private static final int BUTTON_H = 20;
    private static final int TOGGLE_W = 46;
    private static final int WIDE_TOGGLE_W = 100;
    private static final int ROW_H = 30;
    private static final int SECTION_H = 18;
    private static final int PANEL_PAD = 8;
    private static final int SCROLL_STEP = 14;
    private static final int SCROLLBAR_W = 10;
    private static final int THUMB_MIN_H = 20;
    private static final int GLOBAL_ROW_H = 18;
    private static final long TOOLTIP_DELAY_MS = 1700L;

    // Colors
    private static final int COL_BG        = 0xD0101010;
    private static final int COL_PANEL     = 0xFF121212;
    private static final int COL_BORDER    = 0xFF2A2A2A;
    private static final int COL_TITLE     = 0xFFFFE08A;
    private static final int COL_INFO      = 0xFFB0B0B0;
    private static final int COL_SECTION   = 0xFFE6D7A3;
    private static final int COL_SEC_LINE  = 0x606B522C;
    private static final int COL_LABEL     = 0xFFEFEFEF;
    private static final int COL_DESC      = 0xFF7F7F7F;
    private static final int COL_SUBTITLE  = 0xFFB0B0B0;
    private static final int COL_TRACK     = 0xFF171717;
    private static final int COL_THUMB     = 0xFF7A6240;
    private static final int COL_THUMB_HL  = 0xFFB08C40;
    private static final int COL_ROW       = 0xFF181818;
    private static final int COL_ROW_HL    = 0xFF202020;
    private static final int COL_CHIP      = 0xFF2B2B2B;
    private static final int COL_CHIP_HL   = 0xFF383838;

    private final Screen parent;
    private final String preferredAlias;

    // Global toggle state
    private boolean[] globalValues = new boolean[GLOBAL_TOGGLES.size()];
    private boolean globalsLoaded = false;
    private boolean globalsExpanded = false;

    // Bot selector
    private DropdownMenuWidget aliasDropdown;
    private List<String> aliasList = new ArrayList<>();
    private String selectedAlias;

    // Per-bot setting widgets (scroll panel only — no globals)
    private final List<SettingGroup> settingGroups = new ArrayList<>();
    private final List<CyclingButtonWidget<?>> settingWidgets = new ArrayList<>();

    // Dirty buffer for per-alias edits
    private final Map<String, SettingsSnapshot> dirtySettings = new LinkedHashMap<>();

    // Scroll state
    private double scrollOffset;
    private int outerPanelX, outerPanelY, outerPanelW, outerPanelH;
    private int panelX, panelY, panelW, panelH;
    private int scrollAreaH;
    private boolean draggingScroll;
    private int scrollGrabOffset;
    private boolean compactLayout;

    // Computed layout anchors
    private int contentX;
    private int contentW;
    private int titleY;
    private int subtitleY;
    private int globalsLabelY;
    private int globalsStartY;
    private int aliasLabelY;
    private int aliasY;
    private int footerY;

    // Custom click targets
    private Rect globalPanelRect;
    private final List<Rect> globalRowRects = new ArrayList<>();
    private final List<Rect> globalChipRects = new ArrayList<>();
    /** "Adv…" chip on the Voice row — opens the per-category voice mute screen. */
    private Rect voiceAdvancedRect;
    /** "Adv…" chip on the Text Chat row — opens the keep-visible text exceptions screen. */
    private Rect textAdvancedRect;
    /** "Eng…" chip on the Soul Voice row — opens the engine chooser (Dreamsleeve / Piper). */
    private Rect soulVoiceEngineRect;
    /** "LLM…" chip on the Soul Chat row — opens the soul model manager (Ollama). */
    private Rect soulChatModelRect;
    // Bulk-apply action buttons (only populated when panel is expanded).
    private Rect bulkAutoRespawnOnRect;
    private Rect bulkAutoRespawnOffRect;
    private Rect bulkAutoSpawnOnLoadOnRect;
    private Rect bulkAutoSpawnOnLoadOffRect;
    private Rect permissionsActionRect;
    private Rect spawnBotsActionRect;
    // Personal Preferences footer button — commented out 2026-04-07. Toggle
    // moved to BotPlayerInventoryScreen Admin → Behavior. Restore this block
    // (and the matching layout/render/click sites below) if you want the
    // dedicated screen back.
    private Rect closeRect;
    private final LinkedHashMap<CyclingButtonWidget<?>, Rect> settingChipRects = new LinkedHashMap<>();

    // Lock mode state (client-side, synced from server)
    private static boolean lockModeActive = false;

    public static void setLockModeActive(boolean active) {
        lockModeActive = active;
    }

    public static boolean isLockModeActive() {
        return lockModeActive;
    }

    // Tooltip state — computed each frame, rendered last
    private String tooltipText = null;
    private int tooltipX, tooltipY;
    private String tooltipHoverKey = null;
    private long tooltipHoverStartMs = 0L;
    private boolean tooltipHoverSeenThisFrame = false;

    // Bulk-action click feedback: identifier of the last-clicked button + the
    // absolute ms at which the pulse highlight ends.  Keeps the draw loop simple
    // — just compares current time to the expiry.
    private String bulkPulseTarget = null;
    private long bulkPulseEndMs = 0L;
    private static final long BULK_PULSE_DURATION_MS = 260L;

    // Subtitle text
    private String subtitleText = "";

    public BotControlScreen(Screen parent) {
        this(parent, null);
    }

    public BotControlScreen(Screen parent, String preferredAlias) {
        super(Text.of("Bot Controls"));
        this.parent = parent;
        this.preferredAlias = preferredAlias;
    }

    // Keep the 3-arg constructor for compatibility (tab arg is now ignored)
    @SuppressWarnings("unused")
    public BotControlScreen(Screen parent, String preferredAlias, Object ignoredTab) {
        this(parent, preferredAlias);
    }

    @Override
    protected void init() {
        if (selectedAlias != null && !settingWidgets.isEmpty()) {
            captureCurrentWidgets();
        }
        String previousAlias = selectedAlias;

        clearChildren();
        settingGroups.clear();
        settingWidgets.clear();
        scrollOffset = 0;

        // Load global values from config — once per screen instance. init() also runs on
        // resize and on returning from child screens (Adv…, Permissions); reloading there
        // silently discarded unsaved chip flips.
        if (!globalsLoaded) {
            globalValues[0] = Frens.CONFIG.isDefaultLlmWorldEnabled();
            globalValues[1] = Frens.CONFIG.isSurvivalRecruitmentMode();
            globalValues[2] = Frens.CONFIG.isFortifyForcePlaceEnabled();
            // Teleport toggle: ON = per-bot settings apply (null), OFF = globally disabled (false)
            globalValues[3] = Frens.CONFIG.getGlobalTeleportDuringSkills() == null;
            globalValues[4] = Frens.CONFIG.isTextDialogueEnabled();
            globalValues[5] = Frens.CONFIG.isVoicedDialogueEnabled();
            globalValues[6] = Frens.CONFIG.isSoulsEnabled();
            globalValues[7] = Frens.CONFIG.isSoulVoiceEnabled();
            globalsLoaded = true;
        }

        recomputeLayout();

        // Alias dropdown
        aliasList = buildAliasList();
        if (preferredAlias != null && aliasList.contains(preferredAlias)) {
            selectedAlias = preferredAlias;
        } else if (previousAlias != null && aliasList.contains(previousAlias)) {
            selectedAlias = previousAlias;
        } else {
            selectedAlias = aliasList.isEmpty() ? "default" : aliasList.get(0);
        }

        aliasDropdown = new DropdownMenuWidget(
                contentX,
                aliasY,
                contentW,
                BUTTON_H,
                Text.of("Select bot"),
                aliasList
        );
        aliasDropdown.setSelectedOption(selectedAlias);
        this.addDrawableChild(aliasDropdown);

        updateSubtitleText();

        // Settings panel bounds — starts after alias dropdown
        int panelTop = aliasY + BUTTON_H + 6;
        int panelBottom = footerY - 6;
        panelX = contentX - 2;
        panelW = contentW + 4;
        panelH = Math.max(0, panelBottom - panelTop);
        panelY = panelTop;
        scrollAreaH = Math.max(1, panelH - 2);

        rebuildSettingsWidgets();
    }

    private void recomputeLayout() {
        outerPanelW = Math.min(640, this.width - 24);
        outerPanelH = Math.min(480, this.height - 24);
        outerPanelX = (this.width - outerPanelW) / 2;
        outerPanelY = (this.height - outerPanelH) / 2;
        compactLayout = outerPanelH < 390;

        contentX = outerPanelX + 10;
        contentW = outerPanelW - 20;

        titleY = outerPanelY + 8;
        subtitleY = titleY + this.textRenderer.fontHeight + 3;

        // Global toggles section — collapsible
        globalsLabelY = subtitleY + this.textRenderer.fontHeight + 6;
        globalsStartY = globalsLabelY + this.textRenderer.fontHeight + 4;

        // When collapsed: just the header row (18px).  When expanded: header + rows
        // + bulk-apply section (separator + label + 2 button rows).
        int bulkSectionH = globalsExpanded ? getBulkSectionHeight() : 0;
        int globalPanelH = globalsExpanded
                ? (GLOBAL_TOGGLES.size() * getGlobalRowHeight() + 20 + bulkSectionH)
                : 18;
        globalPanelRect = new Rect(contentX - 2, globalsStartY - 1, contentW + 4, globalPanelH);

        // Alias dropdown after globals
        aliasLabelY = globalPanelRect.bottom() + 8;
        aliasY = aliasLabelY + this.textRenderer.fontHeight + 4;

        // Footer
        footerY = outerPanelY + outerPanelH - 30;

        int footerBtnW = 70;
        int footerGap = 8;
        closeRect = new Rect(outerPanelX + outerPanelW - 10 - footerBtnW, footerY, footerBtnW, BUTTON_H);
        permissionsActionRect = new Rect(contentX, footerY, 130, BUTTON_H);
        spawnBotsActionRect = new Rect(permissionsActionRect.right() + footerGap, footerY, 90, BUTTON_H);
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
        boolean isDefault = selectedAlias != null && selectedAlias.equalsIgnoreCase("default");
        if (isDefault) {
            subtitleText = "Editing fallback profile \u2014 used when a bot has no override.";
        } else {
            ManualConfig.BotOwnership ownership = Frens.CONFIG.getOwner(selectedAlias);
            String ownerName = ownership != null && ownership.ownerName() != null
                    && !ownership.ownerName().isBlank()
                    ? ownership.ownerName() : "Unassigned";
            subtitleText = "Editing " + selectedAlias + " \u2014 Owner: " + ownerName;
        }
    }

    // ── Data records ──────────────────────────────────────────────────────

    private record SettingsSnapshot(
            boolean autoRespawnOnDeath, boolean autoSpawnOnLoad,
            String spawnMode, String gameMode,
            String failsafeSpawnMode,
            boolean teleportDuringSkills, boolean followTeleport,
            boolean pauseOnFullInventory,
            boolean teleportDuringDropSweep, boolean autoRegroupOnLost,
            boolean llmEnabled, boolean voicedDialogue) {}

    private record SettingEntry(String label, String desc,
                                CyclingButtonWidget<?> widget, int widgetW) {}
    private record SettingGroup(String name, List<SettingEntry> entries) {}

    // ── Capture / Rebuild ─────────────────────────────────────────────────

    private void captureCurrentWidgets() {
        if (settingGroups.isEmpty() || selectedAlias == null) return;
        List<CyclingButtonWidget<?>> ws = settingWidgets;
        if (ws.size() < 12) return;

        boolean autoRespawn = Boolean.TRUE.equals(ws.get(0).getValue());
        boolean autoSpawnOnLoad = Boolean.TRUE.equals(ws.get(1).getValue());
        Object spawnModeValue = ws.get(2).getValue();
        Object gameModeValue = ws.get(3).getValue();
        Object failsafeValue = ws.get(4).getValue();
        boolean teleportSkills = Boolean.TRUE.equals(ws.get(5).getValue());
        boolean followTeleport = Boolean.TRUE.equals(ws.get(6).getValue());
        boolean pauseInventory = Boolean.TRUE.equals(ws.get(7).getValue());
        boolean teleportSweep = Boolean.TRUE.equals(ws.get(8).getValue());
        boolean autoRegroup = Boolean.TRUE.equals(ws.get(9).getValue());
        boolean llmEnabled = Boolean.TRUE.equals(ws.get(10).getValue());
        boolean voicedDialogue = Boolean.TRUE.equals(ws.get(11).getValue());

        dirtySettings.put(selectedAlias, new SettingsSnapshot(
                autoRespawn,
                autoSpawnOnLoad,
                spawnModeValue instanceof String s ? s : "admin",
                gameModeValue instanceof String s ? s : "survival",
                failsafeValue instanceof String s ? s : "world_spawn",
                teleportSkills,
                followTeleport,
                pauseInventory,
                teleportSweep,
                autoRegroup,
                llmEnabled,
                voicedDialogue
        ));
    }

    private void rebuildSettingsWidgets() {
        for (CyclingButtonWidget<?> w : settingWidgets) {
            this.remove(w);
        }
        settingWidgets.clear();
        settingGroups.clear();

        // Per-bot settings only (globals are handled separately)
        SettingsSnapshot snap = dirtySettings.get(selectedAlias);
        String clientWorldKey = null;
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null && mc.getServer() != null) {
            clientWorldKey = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(mc.getServer());
        }
        ManualConfig.BotControlSettings cfg =
                Frens.CONFIG.getOrCreateBotControl(selectedAlias, clientWorldKey);
        boolean autoRespawn = snap != null ? snap.autoRespawnOnDeath : cfg.isAutoRespawnOnDeath();
        boolean autoSpawnOnLoad = snap != null ? snap.autoSpawnOnLoad : cfg.isAutoSpawnOnLoad();
        String spawnMode = snap != null ? snap.spawnMode : cfg.getSpawnMode();
        String spawnModeUi = canonicalSpawnModeForUi(spawnMode);
        String gameMode = snap != null ? snap.gameMode : cfg.getGameMode();
        String failsafe = snap != null ? snap.failsafeSpawnMode : cfg.getFailsafeSpawnMode();
        boolean teleSkills = snap != null ? snap.teleportDuringSkills : cfg.isTeleportDuringSkills();
        boolean followTp = snap != null ? snap.followTeleport : cfg.isFollowTeleport();
        boolean pauseInv = snap != null ? snap.pauseOnFullInventory : cfg.isPauseOnFullInventory();
        boolean teleDrop = snap != null ? snap.teleportDuringDropSweep : cfg.isTeleportDuringDropSweep();
        boolean autoRegroup = snap != null ? snap.autoRegroupOnLost : cfg.isAutoRegroupOnLost();
        boolean llmEnabled = snap != null ? snap.llmEnabled : cfg.isLlmEnabled();
        boolean voiced = snap != null ? snap.voicedDialogue : cfg.isVoicedDialogue();

        List<SettingEntry> spawning = new ArrayList<>();
        spawning.add(makeOnOff("Auto Respawn", "Respawn on death (skip resurrection ritual).", autoRespawn, TOGGLE_W));
        spawning.add(makeOnOff("Auto Spawn on Load",
                "Automatically spawn this bot when the world loads. Turn off to keep the bot shelved until manually spawned.",
                autoSpawnOnLoad, TOGGLE_W));
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

        List<SettingEntry> behavior = new ArrayList<>();
        behavior.add(makeOnOff("Teleport During Skills", "Allows short recovery teleports if a bot gets badly stuck during skills like mining, building, or escape logic.", teleSkills, TOGGLE_W));
        behavior.add(makeOnOff("Follow Teleport", "Wolf-style catch-up: the bot teleports near you when it falls far behind or gets stuck during follow. Does not affect skill or sweep teleport.", followTp, TOGGLE_W));
        behavior.add(makeOnOff("Pause on Full Inventory", "Pauses the current job when inventory is full so the bot does not keep working and waste drops. Use resume after unloading.", pauseInv, TOGGLE_W));
        behavior.add(makeOnOff("Teleport During Sweeps", "Lets cleanup/drop-sweep runs use teleport shortcuts so the bot can gather scattered drops faster after combat, mining, or building.", teleDrop, TOGGLE_W));
        behavior.add(makeOnOff("Auto-Regroup on Lost", "When the bot loses you at a drop-off, it will automatically regroup after a delay instead of waiting indefinitely. Risky but keeps the bot moving.", autoRegroup, TOGGLE_W));
        settingGroups.add(new SettingGroup("Behavior", behavior));

        List<SettingEntry> llm = new ArrayList<>();
        llm.add(makeOnOff("LLM Chat (this bot)", "Per-bot override under the global LLM World master: both must be ON for this bot to use the classic LLM chat path. Soul-bound bots ignore this and talk through Soul Chat instead.", llmEnabled, TOGGLE_W));
        llm.add(makeOnOff("Voiced Dialogue (this bot)", "Per-bot override under the global Voice master: both must be ON for this bot's baked voice lines to play. Category-level muting lives under the Voice row's Adv… button.", voiced, TOGGLE_W));
        settingGroups.add(new SettingGroup("Dialogue & AI — per-bot overrides", llm));
    }

    private String canonicalSpawnModeForUi(String raw) {
        // Fresh/unknown bots default to "admin" to match the BotControlSettings
        // field default in ManualConfig.  Only explicit "training"/"train" maps
        // to training.  Anything else (null, blank, unknown) falls back to admin.
        if (raw == null || raw.isBlank()) return "admin";
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "training", "train" -> "training";
            case "questing", "quest" -> "questing";
            default -> "admin";
        };
    }

    private SettingEntry makeOnOff(String label, String desc, boolean value, int w) {
        CyclingButtonWidget<Boolean> btn = CyclingButtonWidget.<Boolean>builder(
                        v -> Text.of(v ? "ON" : "OFF"), () -> value)
                .values(List.of(Boolean.TRUE, Boolean.FALSE))
                .build(0, 0, w, BUTTON_H, Text.empty(), (b, v) -> saveSettings());
        settingWidgets.add(btn);
        return new SettingEntry(label, desc, btn, w);
    }

    private SettingEntry makeString(String label, String desc,
                                    String value, String opt1, String opt2,
                                    java.util.function.Function<String, Text> formatter,
                                    int w) {
        CyclingButtonWidget<String> btn = CyclingButtonWidget.<String>builder(
                        formatter::apply, () -> value)
                .values(opt1, opt2)
                .build(0, 0, w, BUTTON_H, Text.empty(), (b, v) -> saveSettings());
        settingWidgets.add(btn);
        return new SettingEntry(label, desc, btn, w);
    }

    private SettingEntry makeString(String label, String desc,
                                    String value, String opt1, String opt2, String opt3,
                                    java.util.function.Function<String, Text> formatter,
                                    int w) {
        CyclingButtonWidget<String> btn = CyclingButtonWidget.<String>builder(
                        formatter::apply, () -> value)
                .values(List.of(opt1, opt2, opt3))
                .build(0, 0, w, BUTTON_H, Text.empty(), (b, v) -> saveSettings());
        settingWidgets.add(btn);
        return new SettingEntry(label, desc, btn, w);
    }

    // ── Save ──────────────────────────────────────────────────────────────

    private void saveSettings() {
        captureCurrentWidgets();

        ManualConfig config = Frens.CONFIG;

        // Save global toggles
        config.setDefaultLlmWorldEnabled(globalValues[0]);
        config.setSurvivalRecruitmentMode(globalValues[1]);
        config.setFortifyForcePlaceEnabled(globalValues[2]);
        // Teleport toggle: ON (true) = clear global override (null), OFF (false) = globally disabled
        config.setGlobalTeleportDuringSkills(globalValues[3] ? null : Boolean.FALSE);
        config.setTextDialogueEnabled(globalValues[4]);
        config.setVoicedDialogueEnabled(globalValues[5]);
        // Soul toggles: same fields as /bot soul enable|disable and /bot soul voice on|off.
        // The live runtime reads a settings snapshot, so a change must trigger reloadSettings
        // (same pattern as BotSoulCommands.awaitReloadThenReport) or it would sit dormant
        // until the next server start.
        boolean soulTogglesChanged = config.isSoulsEnabled() != globalValues[6]
                || config.isSoulVoiceEnabled() != globalValues[7];
        config.setSoulsEnabled(globalValues[6]);
        config.setSoulVoiceEnabled(globalValues[7]);
        if (soulTogglesChanged) {
            net.wcfcarolina13.GameAI.souls.SoulRuntime.current().ifPresent(rt ->
                    rt.reloadSettings(config).exceptionally(ex -> {
                        net.wcfcarolina13.Frens.LOGGER.warn("[souls] reloadSettings failed after GUI toggle: {}", ex.toString());
                        return null;
                    }));
        }

        java.util.List<String> autoRespawnJustEnabled = new java.util.ArrayList<>();
        for (Map.Entry<String, SettingsSnapshot> entry : dirtySettings.entrySet()) {
            String saveWorldKey = null;
            net.minecraft.client.MinecraftClient mc2 = net.minecraft.client.MinecraftClient.getInstance();
            if (mc2 != null && mc2.getServer() != null) {
                saveWorldKey = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(mc2.getServer());
            }
            ManualConfig.BotControlSettings s = config.getOrCreateBotControl(entry.getKey(), saveWorldKey);
            SettingsSnapshot v = entry.getValue();
            boolean wasAutoRespawn = s.isAutoRespawnOnDeath();
            s.setAutoRespawnOnDeath(v.autoRespawnOnDeath);
            s.setAutoSpawnOnLoad(v.autoSpawnOnLoad);
            s.setSpawnMode(v.spawnMode);
            s.setGameMode(v.gameMode);
            s.setFailsafeSpawnMode(v.failsafeSpawnMode);
            s.setTeleportDuringSkills(v.teleportDuringSkills);
            s.setFollowTeleport(v.followTeleport);
            s.setPauseOnFullInventory(v.pauseOnFullInventory);
            s.setTeleportDuringDropSweep(v.teleportDuringDropSweep);
            s.setAutoRegroupOnLost(v.autoRegroupOnLost);
            s.setLlmEnabled(v.llmEnabled);
            s.setVoicedDialogue(v.voicedDialogue);
            if (!wasAutoRespawn && v.autoRespawnOnDeath) {
                autoRespawnJustEnabled.add(entry.getKey());
            }
        }
        config.save();
        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());

        // If the user toggled Auto Respawn ON for any bot that isn't active, spawn
        // them (handles the post-death "revive by toggling" flow).
        if (!autoRespawnJustEnabled.isEmpty()) {
            net.minecraft.client.MinecraftClient mc3 = net.minecraft.client.MinecraftClient.getInstance();
            net.minecraft.server.MinecraftServer srv = mc3 != null ? mc3.getServer() : null;
            if (srv != null) {
                for (String alias : autoRespawnJustEnabled) {
                    final String aliasFinal = alias;
                    srv.execute(() -> net.wcfcarolina13.GameAI.services.BotRespawnPromptService
                            .onAutoRespawnEnabled(srv, aliasFinal));
                }
            }
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        tooltipText = null;  // reset each frame
        tooltipHoverSeenThisFrame = false;
        context.fill(0, 0, this.width, this.height, COL_BG);

        // Outer frame
        context.fill(outerPanelX - 1, outerPanelY - 1,
                outerPanelX + outerPanelW + 1, outerPanelY + outerPanelH + 1, 0xFF000000);
        context.fill(outerPanelX, outerPanelY,
                outerPanelX + outerPanelW, outerPanelY + outerPanelH, 0xF0101010);

        // Title
        context.drawText(this.textRenderer, "Companion Settings", contentX, titleY, COL_TITLE, false);

        // Subtitle (owner info)
        if (!subtitleText.isEmpty()) {
            String elided = elideToWidth(subtitleText, contentW);
            context.drawText(this.textRenderer, elided, contentX, subtitleY, COL_SUBTITLE, false);
        }

        // Global toggles section (collapsible)
        context.drawText(this.textRenderer, "Global Settings", contentX + 2, globalsLabelY, COL_SECTION, false);
        int glLabelW = this.textRenderer.getWidth("Global Settings");
        int glLineY = globalsLabelY + this.textRenderer.fontHeight / 2;
        context.fill(contentX + 2 + glLabelW + 6, glLineY,
                contentX + contentW, glLineY + 1, COL_SEC_LINE);
        renderGlobalTogglePanel(context, mouseX, mouseY);

        // Bot Profile label
        context.drawText(this.textRenderer, "Bot Profile", contentX + 2, aliasLabelY, COL_SECTION, false);
        int bpLabelW = this.textRenderer.getWidth("Bot Profile");
        int bpLineY = aliasLabelY + this.textRenderer.fontHeight / 2;
        context.fill(contentX + 2 + bpLabelW + 6, bpLineY,
                contentX + contentW, bpLineY + 1, COL_SEC_LINE);

        // Per-bot settings panel (scrolling)
        renderSettingsPanel(context, mouseX, mouseY);

        // Footer buttons
        drawActionButton(context, permissionsActionRect,
                "Permissions Editor",
                false,
                this.client != null && selectedAlias != null && !selectedAlias.isBlank(),
                mouseX, mouseY);
        drawActionButton(context, spawnBotsActionRect,
                "Spawn Bots…",
                false,
                this.client != null,
                mouseX, mouseY);
        if (spawnBotsActionRect.contains(mouseX, mouseY) && tooltipText == null) {
            updateTooltipCandidate("footer:spawnBots",
                    "Opens the Bot Roster screen: multi-select saved bots to respawn, or create a new one.",
                    mouseX, mouseY);
        }
        if (permissionsActionRect.contains(mouseX, mouseY) && tooltipText == null) {
            updateTooltipCandidate("footer:permissions",
                    "Edit which players can command this bot and what they can make it do.",
                    mouseX, mouseY);
        }
        drawActionButton(context, closeRect, "Close", false, true, mouseX, mouseY);

        // Dropdown renders on top of everything else
        aliasDropdown.render(context, mouseX, mouseY, delta);
        if (aliasDropdown.isOpen()) {
            aliasDropdown.renderOnTop(context, mouseX, mouseY, delta);
        }

        if (!tooltipHoverSeenThisFrame) {
            tooltipHoverKey = null;
            tooltipHoverStartMs = 0L;
        }

        // Tooltip — rendered last so it's on top of everything
        if (tooltipText != null && !aliasDropdown.isOpen()) {
            renderTooltip(context, tooltipText, tooltipX, tooltipY);
        }
    }

    private void renderGlobalTogglePanel(DrawContext context, int mouseX, int mouseY) {
        globalRowRects.clear();
        globalChipRects.clear();
        voiceAdvancedRect = null;
        textAdvancedRect = null;
        soulVoiceEngineRect = null;
        soulChatModelRect = null;

        // Panel background
        context.fill(globalPanelRect.x, globalPanelRect.y,
                globalPanelRect.right(), globalPanelRect.bottom(), COL_PANEL);
        context.fill(globalPanelRect.x, globalPanelRect.y,
                globalPanelRect.right(), globalPanelRect.y + 1, COL_BORDER);
        context.fill(globalPanelRect.x, globalPanelRect.bottom() - 1,
                globalPanelRect.right(), globalPanelRect.bottom(), COL_BORDER);
        context.fill(globalPanelRect.x, globalPanelRect.y,
                globalPanelRect.x + 1, globalPanelRect.bottom(), COL_BORDER);
        context.fill(globalPanelRect.right() - 1, globalPanelRect.y,
                globalPanelRect.right(), globalPanelRect.bottom(), COL_BORDER);

        // --- Collapsed state: single summary row ---
        if (!globalsExpanded) {
            int onCount = 0;
            for (int i = 0; i < GLOBAL_TOGGLES.size(); i++) {
                if (globalValues[i]) onCount++;
            }
            String collapsedText = "Show global toggles (" + onCount + "/" + GLOBAL_TOGGLES.size() + " ON)";
            context.drawText(this.textRenderer,
                    elideToWidth(collapsedText, globalPanelRect.w - 24),
                    globalPanelRect.x + 6,
                    globalPanelRect.y + (18 - this.textRenderer.fontHeight) / 2,
                    COL_INFO,
                    false);
            context.drawText(this.textRenderer,
                    "\u25B8",
                    globalPanelRect.right() - 10,
                    globalPanelRect.y + (18 - this.textRenderer.fontHeight) / 2,
                    COL_SECTION,
                    false);
            return;
        }

        // --- Expanded state: header + toggle rows ---
        context.drawText(this.textRenderer,
                elideToWidth("Hide global toggles", globalPanelRect.w - 24),
                globalPanelRect.x + 6,
                globalPanelRect.y + (18 - this.textRenderer.fontHeight) / 2,
                COL_INFO,
                false);
        context.drawText(this.textRenderer,
                "\u25BE",
                globalPanelRect.right() - 10,
                globalPanelRect.y + (18 - this.textRenderer.fontHeight) / 2,
                COL_SECTION,
                false);

        int y = globalPanelRect.y + 19;
        int rowX = globalPanelRect.x + 1;
        int rowW = globalPanelRect.w - 2;
        int chipW = 46;
        int rowH = getGlobalRowHeight();

        for (int i = 0; i < GLOBAL_TOGGLES.size(); i++) {
            GlobalToggleDef def = GLOBAL_TOGGLES.get(i);
            Rect rowRect = new Rect(rowX, y, rowW, rowH);
            Rect chipRect = new Rect(rowRect.right() - chipW - 6, y + 1, chipW, rowH - 2);
            globalRowRects.add(rowRect);
            globalChipRects.add(chipRect);

            boolean hover = rowRect.contains(mouseX, mouseY);
            context.fill(rowRect.x, rowRect.y, rowRect.right(), rowRect.bottom(),
                    hover ? COL_ROW_HL : COL_ROW);

            if (i > 0) {
                context.fill(rowRect.x, rowRect.y, rowRect.right(), rowRect.y + 1, 0x30FFFFFF);
            }

            Rect advRect = null;
            if (i == VOICE_TOGGLE_INDEX || i == TEXT_TOGGLE_INDEX
                    || i == SOUL_VOICE_TOGGLE_INDEX || i == SOUL_CHAT_TOGGLE_INDEX) {
                advRect = new Rect(chipRect.x - VOICE_ADV_W - 4, y + 1, VOICE_ADV_W, rowH - 2);
                if (i == VOICE_TOGGLE_INDEX) {
                    voiceAdvancedRect = advRect;
                } else if (i == TEXT_TOGGLE_INDEX) {
                    textAdvancedRect = advRect;
                } else if (i == SOUL_CHAT_TOGGLE_INDEX) {
                    soulChatModelRect = advRect;
                } else {
                    soulVoiceEngineRect = advRect;
                }
            }

            int labelMaxW = (advRect != null ? advRect.x : chipRect.x) - rowRect.x - 12;
            String label = elideToWidth(def.label(), Math.max(30, labelMaxW));
            int labelY = rowRect.y + (rowRect.h - this.textRenderer.fontHeight) / 2;
            context.drawText(this.textRenderer, label, rowRect.x + 6, labelY, COL_LABEL, false);

            drawToggleChip(context, chipRect, globalValues[i], mouseX, mouseY);

            if (advRect != null) {
                boolean advHover = advRect.contains(mouseX, mouseY);
                context.fill(advRect.x, advRect.y, advRect.right(), advRect.bottom(),
                        advHover ? COL_CHIP_HL : COL_CHIP);
                context.drawText(this.textRenderer,
                        i == SOUL_VOICE_TOGGLE_INDEX ? "Eng…"
                                : i == SOUL_CHAT_TOGGLE_INDEX ? "LLM…" : "Adv…",
                        advRect.x + 6,
                        advRect.y + (advRect.h - this.textRenderer.fontHeight) / 2,
                        COL_LABEL, false);
                if (advHover && tooltipText == null) {
                    if (i == VOICE_TOGGLE_INDEX) {
                        updateTooltipCandidate("global-voice-adv",
                                "Mute individual categories of voiced lines (combat, ambient, reactions…). Audio only — text still shows.",
                                mouseX, mouseY);
                    } else if (i == TEXT_TOGGLE_INDEX) {
                        updateTooltipCandidate("global-text-adv",
                                "Mute individual categories of text lines (holograms, subtitles, chat) while Text Chat is on. Text only — audio unaffected. Same rule as the Voice Adv menu.",
                                mouseX, mouseY);
                    } else if (i == SOUL_CHAT_TOGGLE_INDEX) {
                        updateTooltipCandidate("global-soul-chat-llm",
                                "Manage the soul LLM: see what's installed in Ollama, download a smaller/faster model, and switch — sizes and RAM guidance shown up front.",
                                mouseX, mouseY);
                    } else {
                        updateTooltipCandidate("global-soul-voice-eng",
                                "Choose the soul voice engine: Dreamsleeve (cloned voice, GPU) or Piper (lightweight, CPU — one-time download via a transparent installer).",
                                mouseX, mouseY);
                    }
                }
            }

            // Tooltip for hovered global toggle
            if (hover && tooltipText == null) {
                updateTooltipCandidate("global:" + i, def.hint(), mouseX, mouseY);
            }

            y += rowH;
        }

        // ── Bulk Apply section ────────────────────────────────────────────────
        // Compact: thin separator line + 2 rows with [ALL ON] / [ALL OFF] action
        // buttons.  Kept short to avoid squeezing the per-bot settings panel below.
        y += 2;
        context.fill(globalPanelRect.x + 6, y, globalPanelRect.right() - 6, y + 1, COL_SEC_LINE);
        y += 2;

        int btnW = 52;
        int btnGap = 4;
        int bulkRowH = rowH;

        // Row 1: Auto Respawn
        {
            Rect r = new Rect(rowX, y, rowW, bulkRowH);
            context.fill(r.x, r.y, r.right(), r.bottom(), COL_ROW);
            context.drawText(this.textRenderer, "Auto Respawn — All Bots",
                    r.x + 6, r.y + (r.h - this.textRenderer.fontHeight) / 2, COL_LABEL, false);
            bulkAutoRespawnOffRect = new Rect(r.right() - btnW - 6, y + 1, btnW, bulkRowH - 2);
            bulkAutoRespawnOnRect  = new Rect(bulkAutoRespawnOffRect.x - btnGap - btnW, y + 1, btnW, bulkRowH - 2);
            drawBulkButton(context, bulkAutoRespawnOnRect,  "ALL ON",  true,  mouseX, mouseY, "ar_on");
            drawBulkButton(context, bulkAutoRespawnOffRect, "ALL OFF", false, mouseX, mouseY, "ar_off");
            if (bulkAutoRespawnOnRect.contains(mouseX, mouseY) && tooltipText == null) {
                updateTooltipCandidate("bulk:ar_on",
                        "Sets Auto Respawn = ON for every bot in this world. Any inactive bot whose flag flipped will also be re-spawned.",
                        mouseX, mouseY);
            } else if (bulkAutoRespawnOffRect.contains(mouseX, mouseY) && tooltipText == null) {
                updateTooltipCandidate("bulk:ar_off",
                        "Sets Auto Respawn = OFF for every bot in this world. Dying bots will prompt you instead of coming back instantly.",
                        mouseX, mouseY);
            }
            y += bulkRowH;
        }

        // Row 2: Auto Spawn on Load
        {
            Rect r = new Rect(rowX, y, rowW, bulkRowH);
            context.fill(r.x, r.y, r.right(), r.bottom(), COL_ROW);
            context.drawText(this.textRenderer, "Auto Spawn on Load — All Bots",
                    r.x + 6, r.y + (r.h - this.textRenderer.fontHeight) / 2, COL_LABEL, false);
            bulkAutoSpawnOnLoadOffRect = new Rect(r.right() - btnW - 6, y + 1, btnW, bulkRowH - 2);
            bulkAutoSpawnOnLoadOnRect  = new Rect(bulkAutoSpawnOnLoadOffRect.x - btnGap - btnW, y + 1, btnW, bulkRowH - 2);
            drawBulkButton(context, bulkAutoSpawnOnLoadOnRect,  "ALL ON",  true,  mouseX, mouseY, "sl_on");
            drawBulkButton(context, bulkAutoSpawnOnLoadOffRect, "ALL OFF", false, mouseX, mouseY, "sl_off");
            if (bulkAutoSpawnOnLoadOnRect.contains(mouseX, mouseY) && tooltipText == null) {
                updateTooltipCandidate("bulk:sl_on",
                        "Un-shelves every bot: they will auto-spawn next time you load the world.",
                        mouseX, mouseY);
            } else if (bulkAutoSpawnOnLoadOffRect.contains(mouseX, mouseY) && tooltipText == null) {
                updateTooltipCandidate("bulk:sl_off",
                        "Shelves every bot: none will auto-spawn on world load. Use /bot spawn or toggle back ON to bring a bot back.",
                        mouseX, mouseY);
            }
        }
    }

    /**
     * Apply {@code autoRespawnOnDeath = value} to every bot that has a settings
     * entry in the current world.  If {@code value == true}, any bot whose
     * autoRespawn flipped false→true and is not currently active will be
     * auto-spawned via {@link net.wcfcarolina13.GameAI.services.BotRespawnPromptService#onAutoRespawnEnabled}.
     */
    private void applyBulkAutoRespawn(boolean value) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.server.MinecraftServer srv = mc != null ? mc.getServer() : null;
        String worldKey = srv != null
                ? net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(srv)
                : null;

        List<String> justEnabled = new ArrayList<>();
        Map<String, Map<String, ManualConfig.BotControlSettings>> byWorld =
                Frens.CONFIG.getBotControlsByWorld();
        if (byWorld != null) {
            for (Map.Entry<String, Map<String, ManualConfig.BotControlSettings>> e : byWorld.entrySet()) {
                Map<String, ManualConfig.BotControlSettings> worldMap = e.getValue();
                if (worldMap == null) continue;
                ManualConfig.BotControlSettings s = worldMap.get(worldKey);
                if (s == null) continue;
                boolean was = s.isAutoRespawnOnDeath();
                s.setAutoRespawnOnDeath(value);
                if (!was && value) {
                    justEnabled.add(e.getKey());
                }
            }
        }
        Frens.CONFIG.save();

        // Drop any staged per-bot edits and rebuild so the settings panel reflects
        // the new live config values.
        dirtySettings.clear();
        rebuildSettingsWidgets();

        if (value && srv != null) {
            for (String alias : justEnabled) {
                final String aliasFinal = alias;
                srv.execute(() -> net.wcfcarolina13.GameAI.services.BotRespawnPromptService
                        .onAutoRespawnEnabled(srv, aliasFinal));
            }
        }
    }

    /**
     * Apply {@code autoSpawnOnLoad = value} to every bot that has a settings
     * entry in the current world.  This controls session-to-session auto-spawn
     * on world load only; it does not directly spawn or despawn any live bot.
     */
    private void applyBulkAutoSpawnOnLoad(boolean value) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.server.MinecraftServer srv = mc != null ? mc.getServer() : null;
        String worldKey = srv != null
                ? net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(srv)
                : null;

        Map<String, Map<String, ManualConfig.BotControlSettings>> byWorld =
                Frens.CONFIG.getBotControlsByWorld();
        if (byWorld != null) {
            for (Map.Entry<String, Map<String, ManualConfig.BotControlSettings>> e : byWorld.entrySet()) {
                Map<String, ManualConfig.BotControlSettings> worldMap = e.getValue();
                if (worldMap == null) continue;
                ManualConfig.BotControlSettings s = worldMap.get(worldKey);
                if (s == null) continue;
                s.setAutoSpawnOnLoad(value);
            }
        }
        Frens.CONFIG.save();

        dirtySettings.clear();
        rebuildSettingsWidgets();
    }

    private void triggerBulkPulse(String key) {
        bulkPulseTarget = key;
        bulkPulseEndMs = System.currentTimeMillis() + BULK_PULSE_DURATION_MS;
    }

    private void drawBulkButton(DrawContext context, Rect rect, String label, boolean onFlavor,
                                int mouseX, int mouseY, String pulseKey) {
        boolean hover = rect.contains(mouseX, mouseY);
        long now = System.currentTimeMillis();
        boolean pulsing = pulseKey != null
                && pulseKey.equals(bulkPulseTarget)
                && now < bulkPulseEndMs;

        int fill = onFlavor
                ? (hover ? 0xFF3C713C : 0xFF2E5A2E)
                : (hover ? 0xFF613333 : 0xFF4A2A2A);
        if (pulsing) {
            // Brighten fill + draw a 1px inset "pressed" outline.  Progress 1.0 → 0.0
            // over the pulse duration lets the flash decay smoothly.
            float progress = Math.max(0f, Math.min(1f,
                    (bulkPulseEndMs - now) / (float) BULK_PULSE_DURATION_MS));
            fill = onFlavor
                    ? blendToward(fill, 0xFFBDF0BD, progress)
                    : blendToward(fill, 0xFFF0BDBD, progress);
        }
        context.fill(rect.x, rect.y, rect.right(), rect.bottom(), fill);
        context.fill(rect.x, rect.y, rect.right(), rect.y + 1, 0xFF000000);
        context.fill(rect.x, rect.bottom() - 1, rect.right(), rect.bottom(), 0xFF000000);
        context.fill(rect.x, rect.y, rect.x + 1, rect.bottom(), 0xFF000000);
        context.fill(rect.right() - 1, rect.y, rect.right(), rect.bottom(), 0xFF000000);
        if (pulsing) {
            int accent = onFlavor ? 0xFFCFF5CF : 0xFFF5CFCF;
            context.fill(rect.x + 1, rect.y + 1, rect.right() - 1, rect.y + 2, accent);
            context.fill(rect.x + 1, rect.bottom() - 2, rect.right() - 1, rect.bottom() - 1, accent);
            context.fill(rect.x + 1, rect.y + 1, rect.x + 2, rect.bottom() - 1, accent);
            context.fill(rect.right() - 2, rect.y + 1, rect.right() - 1, rect.bottom() - 1, accent);
        }
        int tx = rect.x + (rect.w - this.textRenderer.getWidth(label)) / 2;
        // 1px text nudge while pulsing to simulate a "press" feel.
        int ty = rect.y + (rect.h - this.textRenderer.fontHeight) / 2 + (pulsing ? 1 : 0);
        context.drawText(this.textRenderer, label, tx, ty, 0xFFEFEFEF, false);
    }

    /**
     * Blend {@code base} toward {@code accent} by {@code progress} (0.0–1.0).
     * Used for the bulk-action click pulse so the flash decays smoothly.
     */
    private static int blendToward(int base, int accent, float progress) {
        int ba = (base >>> 24) & 0xFF, br = (base >>> 16) & 0xFF, bg = (base >>> 8) & 0xFF, bb = base & 0xFF;
        int aa = (accent >>> 24) & 0xFF, ar = (accent >>> 16) & 0xFF, ag = (accent >>> 8) & 0xFF, ab = accent & 0xFF;
        int a = (int) (ba + (aa - ba) * progress);
        int r = (int) (br + (ar - br) * progress);
        int g = (int) (bg + (ag - bg) * progress);
        int b = (int) (bb + (ab - bb) * progress);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void renderSettingsPanel(DrawContext context, int mouseX, int mouseY) {
        settingChipRects.clear();

        int maxScroll = Math.max(0, getSettingsContentHeight() - scrollAreaH);
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);
        int rowH = getSettingRowHeight();
        int sectionH = getSectionHeight();
        boolean showDescriptions = showSettingDescriptions();

        // Panel background
        context.fill(panelX - 1, panelY - 1,
                panelX + panelW + 1, panelY + panelH + 1, COL_BORDER);
        context.fill(panelX, panelY,
                panelX + panelW, panelY + panelH, COL_PANEL);

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
            // Section header
            int headerY = currentY + 4;
            String headerText = group.name;
            int textW = this.textRenderer.getWidth(headerText);
            context.drawText(this.textRenderer, headerText,
                    innerL, headerY, COL_SECTION, false);
            int lineY = headerY + this.textRenderer.fontHeight / 2;
            context.fill(innerL + textW + 6, lineY,
                    innerR, lineY + 1, COL_SEC_LINE);
            currentY += sectionH;

            for (int ei = 0; ei < group.entries.size(); ei++) {
                SettingEntry entry = group.entries.get(ei);
                int rowTop = currentY;
                int rowBottom = currentY + rowH;

                // Row separator
                if (ei > 0) {
                    context.fill(innerL, rowTop, innerR - SCROLLBAR_W - 2,
                            rowTop + 1, 0x20FFFFFF);
                }

                // Row background
                int rowRight = innerR - SCROLLBAR_W - 2;
                boolean rowHover = mouseX >= innerL && mouseX < rowRight
                        && mouseY >= rowTop && mouseY < rowBottom;
                context.fill(innerL, rowTop + 1, rowRight, rowBottom, rowHover ? COL_ROW_HL : COL_ROW);

                // Label
                int labelY = showDescriptions
                        ? rowTop + 4
                        : rowTop + (rowH - this.textRenderer.fontHeight) / 2;
                context.drawText(this.textRenderer, entry.label,
                        innerL + 4, labelY, COL_LABEL, false);

                // Value chip
                int chipX = innerR - entry.widgetW - SCROLLBAR_W - 2;
                int chipY = showDescriptions ? rowTop + 4 : rowTop + 3;
                int chipH = showDescriptions ? rowH - 8 : rowH - 6;
                Rect chipRect = new Rect(chipX, chipY, entry.widgetW, chipH);
                settingChipRects.put(entry.widget, chipRect);

                Object value = entry.widget.getValue();
                Boolean boolValue = value instanceof Boolean b ? b : null;
                String valueText = entry.widget.getMessage().getString();
                drawSettingValueChip(context, chipRect, valueText, boolValue, mouseX, mouseY);

                // Description line
                if (showDescriptions) {
                    int descMaxW = innerW - entry.widgetW - SCROLLBAR_W - 14;
                    String desc = entry.desc;
                    if (this.textRenderer.getWidth(desc) > descMaxW) {
                        desc = this.textRenderer.trimToWidth(desc, Math.max(8, descMaxW - 6)) + "..";
                    }
                    context.drawText(this.textRenderer, desc,
                            innerL + 4,
                            rowTop + 4 + this.textRenderer.fontHeight + 2,
                            COL_DESC, false);
                }

                // Position the hidden vanilla widget for cycling logic
                entry.widget.setX(chipX);
                entry.widget.setY(chipY);
                entry.widget.visible = false;
                entry.widget.active = false;

                // Tooltip for hovered setting row
                if (rowHover && tooltipText == null) {
                    updateTooltipCandidate(group.name + ":" + ei, entry.desc, mouseX, mouseY);
                }

                currentY += rowH;
            }
        }

        context.disableScissor();

        // Scrollbar
        if (maxScroll > 0) {
            int trackX = panelX + panelW - SCROLLBAR_W - 1;
            int trackT = panelY + 1;
            int trackB = panelY + panelH - 1;
            int trackH = trackB - trackT;
            context.fill(trackX, trackT, trackX + SCROLLBAR_W, trackB, COL_TRACK);

            int[] thumb = computeThumb(trackT, trackH, maxScroll);
            if (thumb != null) {
                boolean hover = mouseX >= trackX - 2 && mouseX < trackX + SCROLLBAR_W + 2
                        && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
                int col = (hover || draggingScroll) ? COL_THUMB_HL : COL_THUMB;
                context.fill(trackX + 1, thumb[0], trackX + SCROLLBAR_W - 1,
                        thumb[0] + thumb[1], col);
            }
        }
    }

    // ── Chip rendering ────────────────────────────────────────────────────

    private void drawToggleChip(DrawContext context, Rect rect, boolean value, int mouseX, int mouseY) {
        boolean hover = rect.contains(mouseX, mouseY);
        int fill = value ? 0xFF2E5A2E : 0xFF4A2A2A;
        if (hover) {
            fill = value ? 0xFF3C713C : 0xFF613333;
        }

        context.fill(rect.x, rect.y, rect.right(), rect.bottom(), fill);
        context.fill(rect.x, rect.y, rect.right(), rect.y + 1, 0xFF000000);
        context.fill(rect.x, rect.bottom() - 1, rect.right(), rect.bottom(), 0xFF000000);
        context.fill(rect.x, rect.y, rect.x + 1, rect.bottom(), 0xFF000000);
        context.fill(rect.right() - 1, rect.y, rect.right(), rect.bottom(), 0xFF000000);

        String text = value ? "ON" : "OFF";
        int tx = rect.x + (rect.w - this.textRenderer.getWidth(text)) / 2;
        int ty = rect.y + (rect.h - this.textRenderer.fontHeight) / 2;
        context.drawText(this.textRenderer, text, tx, ty, 0xFFEFEFEF, false);
    }

    private void drawSettingValueChip(DrawContext context, Rect rect, String label,
                                      Boolean boolValue, int mouseX, int mouseY) {
        boolean hover = rect.contains(mouseX, mouseY);
        int fill;
        if (boolValue != null) {
            fill = boolValue
                    ? (hover ? 0xFF3B6F3F : 0xFF2F5B33)
                    : (hover ? 0xFF684040 : 0xFF553434);
        } else {
            fill = hover ? COL_CHIP_HL : COL_CHIP;
        }

        context.fill(rect.x, rect.y, rect.right(), rect.bottom(), fill);
        context.fill(rect.x, rect.y, rect.right(), rect.y + 1, 0xFF000000);
        context.fill(rect.x, rect.bottom() - 1, rect.right(), rect.bottom(), 0xFF000000);
        context.fill(rect.x, rect.y, rect.x + 1, rect.bottom(), 0xFF000000);
        context.fill(rect.right() - 1, rect.y, rect.right(), rect.bottom(), 0xFF000000);

        int textMaxW = rect.w - 10;
        String shown = elideToWidth(label, Math.max(16, textMaxW));
        int tx = rect.x + Math.max(2, (rect.w - this.textRenderer.getWidth(shown)) / 2);
        int ty = rect.y + (rect.h - this.textRenderer.fontHeight) / 2;
        context.drawText(this.textRenderer, shown, tx, ty, 0xFFEFEFEF, false);
    }

    private void drawActionButton(DrawContext context, Rect rect, String label,
                                  boolean selected, boolean enabled,
                                  int mouseX, int mouseY) {
        boolean hover = rect.contains(mouseX, mouseY);

        int fill;
        if (!enabled) {
            fill = 0xFF151515;
        } else if (selected) {
            fill = hover ? 0xFF3A2D16 : 0xFF2F2412;
        } else {
            fill = hover ? 0xFF2A2A2A : 0xFF1A1A1A;
        }

        context.fill(rect.x, rect.y, rect.right(), rect.bottom(), fill);
        context.fill(rect.x, rect.y, rect.right(), rect.y + 1, 0xFF000000);
        context.fill(rect.x, rect.bottom() - 1, rect.right(), rect.bottom(), 0xFF4A4A4A);
        context.fill(rect.x, rect.y, rect.x + 1, rect.bottom(), 0xFF000000);
        context.fill(rect.right() - 1, rect.y, rect.right(), rect.bottom(), 0xFF000000);

        int color = enabled ? COL_SECTION : 0xFF6F6F6F;
        int tx = rect.x + (rect.w - this.textRenderer.getWidth(label)) / 2;
        int ty = rect.y + (rect.h - this.textRenderer.fontHeight) / 2;
        context.drawText(this.textRenderer, label, tx, ty, color, false);
    }

    // ── Scroll math ───────────────────────────────────────────────────────

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
        int h = 4;
        int sectionH = getSectionHeight();
        int rowH = getSettingRowHeight();
        for (SettingGroup group : settingGroups) {
            h += sectionH;
            h += group.entries.size() * rowH;
        }
        return h;
    }

    private int getGlobalRowHeight() {
        return compactLayout ? 15 : GLOBAL_ROW_H;
    }

    private int getBulkSectionHeight() {
        // Thin separator (2 gap + 1 line + 2 gap = 5) + 2 compact rows (rowH each).
        // No explicit section header — the row labels ("Auto Respawn — All Bots"
        // etc.) describe themselves and the buttons are visually distinct.
        int rowH = getGlobalRowHeight();
        return 5 + 2 * rowH;
    }

    private int getSectionHeight() {
        return compactLayout ? 16 : SECTION_H;
    }

    private int getSettingRowHeight() {
        return compactLayout ? 24 : ROW_H;
    }

    private boolean showSettingDescriptions() {
        return !compactLayout;
    }

    // ── Input handling ────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        double mx = click.x();
        double my = click.y();
        resetTooltipHoverState();

        // Dropdown first
        if (aliasDropdown.isMouseOver(mx, my)) {
            String before = aliasDropdown.getSelectedOption();
            boolean handled = super.mouseClicked(click, isInside);
            String after = aliasDropdown.getSelectedOption();
            if (after != null && !after.equals(before)) {
                onAliasChanged(before, after);
            }
            return handled;
        }

        // If dropdown is open, still allow footer buttons before consuming via widget system
        if (aliasDropdown.isOpen()) {
            if (closeRect.contains(mx, my)) {
                close();
                return true;
            }
            if (permissionsActionRect.contains(mx, my)) {
                aliasDropdown.setSelectedOption(aliasDropdown.getSelectedOption());
                if (this.client != null && selectedAlias != null && !selectedAlias.isBlank()) {
                    this.client.setScreen(new AdminPlayerSettingsScreen(this, selectedAlias));
                }
                return true;
            }
            return super.mouseClicked(click, isInside);
        }

        // Global panel click: toggle expand/collapse, or toggle individual values
        if (globalPanelRect.contains(mx, my)) {
            // Click on the header row (top 18px) always toggles expand/collapse
            if (!globalsExpanded || my < globalPanelRect.y + 18) {
                globalsExpanded = !globalsExpanded;
                this.init();  // relayout with new panel height
                return true;
            }

            // "Adv…" chips on the Voice/Text rows — must win over the row-wide toggle hit test.
            if (voiceAdvancedRect != null && voiceAdvancedRect.contains(mx, my)) {
                if (this.client != null) {
                    this.client.setScreen(new ConfigureVoiceCategoriesScreen(this));
                }
                return true;
            }
            if (textAdvancedRect != null && textAdvancedRect.contains(mx, my)) {
                if (this.client != null) {
                    this.client.setScreen(new ConfigureTextCategoriesScreen(this));
                }
                return true;
            }
            if (soulVoiceEngineRect != null && soulVoiceEngineRect.contains(mx, my)) {
                if (this.client != null) {
                    this.client.setScreen(new SoulVoiceEngineScreen(this));
                }
                return true;
            }
            if (soulChatModelRect != null && soulChatModelRect.contains(mx, my)) {
                if (this.client != null) {
                    this.client.setScreen(new SoulModelManagerScreen(this));
                }
                return true;
            }

            // Click on an expanded toggle row
            for (int i = 0; i < globalChipRects.size(); i++) {
                Rect chipRect = globalChipRects.get(i);
                Rect rowRect = globalRowRects.get(i);
                if (chipRect.contains(mx, my) || rowRect.contains(mx, my)) {
                    globalValues[i] = !globalValues[i];
                    saveSettings();
                    return true;
                }
            }

            // Bulk Apply buttons
            if (bulkAutoRespawnOnRect != null && bulkAutoRespawnOnRect.contains(mx, my)) {
                triggerBulkPulse("ar_on");
                applyBulkAutoRespawn(true);
                return true;
            }
            if (bulkAutoRespawnOffRect != null && bulkAutoRespawnOffRect.contains(mx, my)) {
                triggerBulkPulse("ar_off");
                applyBulkAutoRespawn(false);
                return true;
            }
            if (bulkAutoSpawnOnLoadOnRect != null && bulkAutoSpawnOnLoadOnRect.contains(mx, my)) {
                triggerBulkPulse("sl_on");
                applyBulkAutoSpawnOnLoad(true);
                return true;
            }
            if (bulkAutoSpawnOnLoadOffRect != null && bulkAutoSpawnOnLoadOffRect.contains(mx, my)) {
                triggerBulkPulse("sl_off");
                applyBulkAutoSpawnOnLoad(false);
                return true;
            }

            return true;  // consume click inside panel even if no row matched
        }

        // Footer: Permissions editor
        if (permissionsActionRect.contains(mx, my)) {
            if (this.client != null && selectedAlias != null && !selectedAlias.isBlank()) {
                this.client.setScreen(new AdminPlayerSettingsScreen(this, selectedAlias));
            }
            return true;
        }

        // Footer: Spawn Bots — opens BotRestoreScreen for multi-select spawning
        if (spawnBotsActionRect.contains(mx, my)) {
            if (this.client != null) {
                java.util.List<String> aliases = net.wcfcarolina13.FrensClient.getKnownRestorableBotAliases();
                this.client.setScreen(new BotRestoreScreen(this, aliases));
            }
            return true;
        }

        // Footer: Close
        if (closeRect.contains(mx, my)) {
            close();
            return true;
        }

        // Setting chip clicks (per-bot)
        for (Map.Entry<CyclingButtonWidget<?>, Rect> entry : settingChipRects.entrySet()) {
            if (entry.getValue().contains(mx, my)) {
                entry.getKey().onPress(click);
                return true;
            }
        }

        // Scrollbar drag
        int maxScroll = Math.max(0, getSettingsContentHeight() - scrollAreaH);
        if (maxScroll > 0) {
            int trackX = panelX + panelW - SCROLLBAR_W - 1;
            int trackT = panelY + 1;
            int trackH = panelH - 2;
            // Wider hit area for easier grabbing
            if (mx >= trackX - 2 && mx < trackX + SCROLLBAR_W + 2
                    && my >= trackT && my < trackT + trackH) {
                int[] thumb = computeThumb(trackT, trackH, maxScroll);
                if (thumb != null && my >= thumb[0] && my < thumb[0] + thumb[1]) {
                    draggingScroll = true;
                    scrollGrabOffset = (int) my - thumb[0];
                    return true;
                }
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
        if (aliasDropdown.isOpen() && aliasDropdown.isMouseOver(mouseX, mouseY)) {
            resetTooltipHoverState();
            return aliasDropdown.mouseScrolled(mouseX, mouseY,
                    horizontalAmount, verticalAmount);
        }

        if (mouseX >= panelX && mouseX <= panelX + panelW
                && mouseY >= panelY && mouseY <= panelY + panelH) {
            resetTooltipHoverState();
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
            resetTooltipHoverState();
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

    // ── Utilities ─────────────────────────────────────────────────────────

    private void updateTooltipCandidate(String key, String text, int mouseX, int mouseY) {
        if (key == null || key.isEmpty() || text == null || text.isEmpty()) {
            return;
        }
        tooltipHoverSeenThisFrame = true;
        long now = System.currentTimeMillis();
        if (!key.equals(tooltipHoverKey)) {
            tooltipHoverKey = key;
            tooltipHoverStartMs = now;
            return;
        }
        if (now - tooltipHoverStartMs >= TOOLTIP_DELAY_MS) {
            tooltipText = text;
            tooltipX = mouseX + 12;
            tooltipY = mouseY - 10;
        }
    }

    private void resetTooltipHoverState() {
        tooltipText = null;
        tooltipHoverKey = null;
        tooltipHoverSeenThisFrame = false;
        tooltipHoverStartMs = System.currentTimeMillis();
    }

    private void renderTooltip(DrawContext context, String text, int x, int y) {
        if (text == null || text.isEmpty()) return;
        int maxTooltipW = Math.max(140, Math.min(260, this.width - 24));
        List<net.minecraft.text.OrderedText> lines = this.textRenderer.wrapLines(Text.literal(text), maxTooltipW);
        if (lines.isEmpty()) {
            return;
        }
        int pad = 4;
        int lineH = this.textRenderer.fontHeight + 1;
        int maxLineW = 0;
        for (net.minecraft.text.OrderedText line : lines) {
            maxLineW = Math.max(maxLineW, this.textRenderer.getWidth(line));
        }
        int boxW = maxLineW + pad * 2;
        int boxH = lines.size() * lineH + pad * 2 - 1;
        // Keep tooltip on-screen
        int tx = Math.min(x, this.width - boxW - 2);
        int ty = Math.max(y, 2);
        if (ty + boxH > this.height - 2) ty = this.height - boxH - 2;
        context.fill(tx - 1, ty - 1, tx + boxW + 1, ty + boxH + 1, 0xFF000000);
        context.fill(tx, ty, tx + boxW, ty + boxH, 0xF0200E00);
        context.fill(tx, ty, tx + boxW, ty + 1, 0xFF504020);
        context.fill(tx, ty + boxH - 1, tx + boxW, ty + boxH, 0xFF504020);
        context.fill(tx, ty, tx + 1, ty + boxH, 0xFF504020);
        context.fill(tx + boxW - 1, ty, tx + boxW, ty + boxH, 0xFF504020);
        int lineY = ty + pad;
        for (net.minecraft.text.OrderedText line : lines) {
            context.drawText(this.textRenderer, line, tx + pad, lineY, 0xFFEFEFEF, false);
            lineY += lineH;
        }
    }

    private String elideToWidth(String text, int maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        String ellipsis = "\u2026";
        int ellipsisW = this.textRenderer.getWidth(ellipsis);
        if (ellipsisW >= maxWidth) return ellipsis;

        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String candidate = text.substring(0, mid) + ellipsis;
            if (this.textRenderer.getWidth(candidate) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
