package net.wcfcarolina13;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.*;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.wcfcarolina13.GraphicalUserInterface.BaseManagerScreen;
import net.wcfcarolina13.GraphicalUserInterface.BotRestoreScreen;
import net.wcfcarolina13.GraphicalUserInterface.BotGuideScreen;
import net.wcfcarolina13.GraphicalUserInterface.BotPlayerInventoryScreen;
import net.wcfcarolina13.GraphicalUserInterface.CookablesScreen;
import net.wcfcarolina13.GraphicalUserInterface.CraftingHistoryScreen;
import net.wcfcarolina13.GraphicalUserInterface.ConfigManager;
import net.wcfcarolina13.GraphicalUserInterface.CompanionHotkeyOverlayHud;
import net.wcfcarolina13.GraphicalUserInterface.HuntablesScreen;
import net.wcfcarolina13.GraphicalUserInterface.RecruitmentDialogueScreen;
import net.wcfcarolina13.GraphicalUserInterface.SleepCommandHintHud;
import net.wcfcarolina13.GraphicalUserInterface.WorldModeSelectionScreen;
import net.wcfcarolina13.network.CookablesPayload;
import net.wcfcarolina13.network.CraftingHistoryPayload;
import net.wcfcarolina13.network.BasesListPayload;
import net.wcfcarolina13.network.ConfigJsonUtil;
import net.wcfcarolina13.network.ConfirmRecruitmentPayload;
import net.wcfcarolina13.network.HuntablesPayload;
import net.wcfcarolina13.network.OpenRecruitmentDialoguePayload;
import net.wcfcarolina13.network.RecruitmentPromptPayload;
import net.wcfcarolina13.network.RecruitmentStatePayload;
import net.wcfcarolina13.network.RequestRecruitmentDialoguePayload;
import net.wcfcarolina13.network.ResumeDecisionPayload;
import net.wcfcarolina13.network.OpenConfigPayload;
import net.wcfcarolina13.network.RecruitmentAdminPermissionsPayload;
import net.wcfcarolina13.network.RecruitmentAdminStatusPayload;
import net.wcfcarolina13.network.BotTaskPeekRequestPayload;
import net.wcfcarolina13.network.BotTaskPeekStatusPayload;
import net.wcfcarolina13.network.LearningInputSamplePayload;
import net.wcfcarolina13.network.LearningSessionStatusPayload;
import net.wcfcarolina13.network.PlayerPreserveStatePayload;
import net.wcfcarolina13.network.PlayerAutoAcceptPreciousStatePayload;
import net.wcfcarolina13.items.ModItems;
import org.lwjgl.glfw.GLFW;

public class FrensClient implements ClientModInitializer {

    /**
     * Snapshot of the local (on-disk) global config, taken on the FIRST config sync of a remote
     * connection. Restored on disconnect so a dedicated server's globals never leak into the
     * player's own settings.json5 or a later single-player session.
     */
    private static net.wcfcarolina13.FilingSystem.SharedConfig preSyncSnapshot = null;

    /** Marks Frens.CONFIG as remote-authoritative, capturing the local globals once per connection. */
    private static void enterRemoteConfig() {
        net.wcfcarolina13.FilingSystem.ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
        if (cfg == null) {
            return;
        }
        if (preSyncSnapshot == null) {
            preSyncSnapshot = net.wcfcarolina13.FilingSystem.SharedConfig.capture(cfg);
        }
        cfg.setRemoteAuthoritative(true);
    }

    /** Restores the pre-sync local globals and re-enables local saving. */
    private static void leaveRemoteConfig() {
        net.wcfcarolina13.FilingSystem.ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
        if (cfg == null) {
            preSyncSnapshot = null;
            return;
        }
        if (preSyncSnapshot != null) {
            preSyncSnapshot.applyTo(cfg);
            preSyncSnapshot = null;
        }
        cfg.setRemoteAuthoritative(false);
    }


    // Note: overhead dialogue is best-effort UX; keep logging light.

    public enum CompanionShortcut {
        GUIDE,
        SPELLS,
        STOP,
        RESUME,
        FOLLOW,
        GO_TO_LOOK,
        HOME,
        SLEEP,
        REGROUP,
        CLEANUP,
        STRIPMINE,
        ASCENT,
        DESCENT
    }

    private static KeyBinding KEY_FOLLOW_TOGGLE_LOOK;
    private static KeyBinding KEY_GO_TO_LOOK;
    private static KeyBinding KEY_OPEN_GUIDE;
    private static KeyBinding KEY_OPEN_SPELLS;
    private static KeyBinding KEY_RESUME;
    private static KeyBinding KEY_STOP_LOOK;
    private static KeyBinding KEY_LOCK_PREVIEW;
    private static KeyBinding KEY_LEASH;
    private static KeyBinding KEY_RECRUIT_CONTACT;
    private static KeyBinding KEY_ZONE_CONFIRM;
    private static KeyBinding KEY_RESCUE_TELEPORT;
    private static KeyBinding KEY_STAND_DOWN_LOOK;

    /** Set at mod init if the upstream "ai-player" mod is also installed. Triggers a
     *  one-shot chat warning once the player joins — see onInitializeClient for details. */
    private static volatile boolean aiPlayerConflictDetected = false;
    private static volatile boolean aiPlayerConflictWarned = false;

    private static final long STOP_HOLD_THRESHOLD_MS = 350L;
    private static final long HOTKEY_OVERLAY_DURATION_MS = 4500L;
    private static boolean stopKeyDown = false;
    private static long stopKeyDownAtMs = 0L;
    private static boolean stopKeyHoldConsumed = false;
    private static boolean previewLockBindingMigrationChecked = false;

    private enum PlacementPreviewKind {
        CONSTRUCTION,
        FARM
    }

    private static final class PlacementPreviewSession {
        private final PlacementPreviewKind kind;
        private final String type;
        private final String label;
        private final String botTarget;
        private final boolean allowRotation;
        private final boolean allowVerticalAdjust;
        private final net.wcfcarolina13.GameAI.schematic.SchematicData schematicData;
        private BlockPos targetPos;
        private int rotationQuarterTurns;
        private boolean locked;
        private Box primaryBox;
        private java.util.List<Box> secondaryBoxes = java.util.List.of();

        private PlacementPreviewSession(PlacementPreviewKind kind,
                                       String type,
                                       String label,
                                       String botTarget,
                                       boolean allowRotation,
                                       boolean allowVerticalAdjust,
                                       net.wcfcarolina13.GameAI.schematic.SchematicData schematicData) {
            this.kind = kind;
            this.type = type;
            this.label = label;
            this.botTarget = botTarget;
            this.allowRotation = allowRotation;
            this.allowVerticalAdjust = allowVerticalAdjust;
            this.schematicData = schematicData;
        }
    }

    private static final int FARM_PREVIEW_SIZE = 10;
    private static final int FARM_IRRIGATION_SIZE = 2;
    private static PlacementPreviewSession pendingPlacementPreview = null;
    private static boolean previewUpKeyDown = false;
    private static boolean previewDownKeyDown = false;
    private static boolean previewLeftKeyDown = false;
    private static boolean previewRightKeyDown = false;
    private static String pendingDirectionalActionLabel = null;
    private static String pendingDirectionalCommand = null;
    private static int directionalPreviewTick = 0;

    // Zone wand selection state
    private static BlockPos pendingZonePos1 = null;
    private static BlockPos pendingZonePos2 = null;
    private static net.minecraft.registry.RegistryKey<net.minecraft.world.World> pendingZoneDimension = null;
    private static boolean pendingZonePreviewActive = false;
    private static int zonePreviewTickCounter = 0;

    private static boolean resumeDecisionActive = false;
    private static String resumeDecisionBotName = null;

    // Leash button state - shows when looking at a bot that has leashed animals
    private static boolean leashButtonVisible = false;
    private static String leashButtonBotName = null;
    // Mounted leash hint - shows when there's a mounted bot the player might want to
    // dismount and tether. Populated in two cases:
    //   (a) player is mounted AND a nearby bot is also mounted (classic "ride-along")
    //   (b) player is looking at a mounted bot (works whether player is mounted or not)
    // Both cases fire the same action: /bot stop <name> + /bot leash <name>.
    private static boolean mountedLeashHintVisible = false;
    private static String mountedLeashBotName = null;
    // True when the mounted bot's vehicle is a boat — changes wording from
    // horse/tether to boat/dismount in the HUD hint.
    private static boolean mountedLeashBotOnBoat = false;

    // ===== Survival recruitment mode (client UI) =====
    private static boolean survivalRecruitmentEnabled = false;
    private static boolean survivalRecruitmentCompleted = false;
    private static boolean recruitmentPromptVisible = false;
    private static String recruitmentBotAlias = "Jake";
    private static boolean modeSelectionRequired = false;
    private static boolean modeSelectionCanChoose = false;
    private static String modeSelectionWorldKey = "default";
    private static boolean modeSelectionAutoOpenPending = false;
    private static boolean modeSelectionOpenedThisConnection = false;
    private static boolean allowEveryoneSkinChange = false;
    private static boolean allowCustomSkins = false;

    // Auto-open recruitment dialogue once when entering a village (prompt transitions hidden -> visible).
    private static boolean recruitmentAutoOpenPending = false;
    private static boolean recruitmentAutoOpenedThisSession = false;

    // ===== Companion spells (client UX) =====
    // Client-side only (UX hint). Server remains authoritative.
    private static long eyeSpellCooldownUntilMs = 0L;

    // One-shot (per acquisition) top-right hint shown when the player gains an item that enables spells.
    private static long spellsAcquireHintUntilMs = 0L;
    private static String spellsAcquireHintLine1 = null;
    private static String spellsAcquireHintLine2 = null;
    private static boolean lastHasEyeToken = false;
    private static boolean lastHasHornToken = false;
    private static boolean lastHasEnchantingTableToken = false;
    private static boolean lastHasWizardTomeToken = false;
    /** Skip the very first inventory scan so items already in inventory don't re-trigger the hint. */
    private static boolean spellHintFirstScanDone = false;

    // Simple per-bot dialogue log used by the Topics overlay.
    private static final java.util.Map<String, java.util.ArrayDeque<String>> DIALOGUE_LOG = new java.util.HashMap<>();

    // Server-authoritative companion quest state snapshot (for stage-gated dialogue topics).
    private static final java.util.Map<String, Integer> COMPANION_STAGE = new java.util.HashMap<>();
    private static final java.util.Set<String> COMPANION_PERMANENT = new java.util.HashSet<>();

    // Hovered bot task hint state (debounced peek requests).
    private static java.util.UUID lookedAtBotUuid = null;
    private static long lookedAtBotPeekLastRequestMs = 0L;
    private static BotTaskPeekStatusPayload lookedAtBotStatus = null;
    private static long lookedAtBotStatusAtMs = 0L;

    // Learning mode client telemetry stream state (server-authoritative).
    private static boolean learningInputStreamActive = false;
    private static long learningInputSessionToken = 0L;
    private static int learningInputTickHz = 0;
    private static long learningInputLastSentMs = 0L;
    private static int learningInputSampleSeq = 0;

    // While the hotkey overlay is visible, keep the player's selected slot locked so number-key
    // selections do not leak through to vanilla hotbar switching.
    private static int overlayHotbarLockedSlot = -1;

    private static final int TOP_TIP_MARGIN = 4;
    private static final int TOP_TIP_GAP = 4;
    private static final long NO_BOTS_HINT_DEBOUNCE_MS = 2_000L;
    private static final long NO_BOTS_HINT_DURATION_MS = 10_000L;
    private static int topTipLeftY = TOP_TIP_MARGIN;
    private static int topTipCenterY = TOP_TIP_MARGIN;
    private static int topTipRightY = TOP_TIP_MARGIN;
    private static long noBotsDetectedSinceMs = 0L;
    private static long noBotsHintUntilMs = 0L;
    /** Session-only HUD dismissal.  Resets when bots reappear in the world so
     *  the hint shows again next time everyone goes missing. */
    private static boolean noBotsHintDismissed = false;
    /** Click target for the HUD X button (rendered inside renderNoBotsPresentHint). */
    private static int noBotsHintCloseX, noBotsHintCloseY, noBotsHintCloseW, noBotsHintCloseH;

    private enum TopTipLane {
        LEFT,
        CENTER,
        RIGHT
    }

    // Expose recruitment UI state to other client UI surfaces (e.g., bot inventory overlay).
    public static boolean isSurvivalRecruitmentEnabled() {
        return survivalRecruitmentEnabled;
    }

    public static boolean isSurvivalRecruitmentCompleted() {
        return survivalRecruitmentCompleted;
    }

    public static boolean isRecruitmentPromptVisible() {
        return recruitmentPromptVisible;
    }

    public static String getRecruitmentBotAlias() {
        return recruitmentBotAlias;
    }

    public static boolean isSkinChangeForEveryoneEnabled() {
        return allowEveryoneSkinChange;
    }

    public static boolean isCustomSkinsEnabled() {
        return allowCustomSkins;
    }

    public static boolean isEyeSpellOnCooldown() {
        return getEyeSpellCooldownRemainingMs() > 0L;
    }

    public static long getEyeSpellCooldownRemainingMs() {
        long remaining = eyeSpellCooldownUntilMs - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    public static void armEyeSpellCooldown() {
        // Keep aligned with server default (currently 60s). This is just a UX hint.
        eyeSpellCooldownUntilMs = System.currentTimeMillis() + 60_000L;
    }

    private static int cachedBotNavTier = 0;

    /**
     * Returns the cached navigation tier for the current companion bot.
     * 0 = NONE, 1 = BASIC, 2 = ENHANCED.
     */
    public static int getCachedBotNavTier() {
        return cachedBotNavTier;
    }

    public static void setCachedBotNavTier(int tier) {
        cachedBotNavTier = tier;
    }

    private static boolean isGameplayTipsEnabled() {
        return Frens.CONFIG == null || Frens.CONFIG.isGameplayTipsEnabled();
    }

    private static void playMagicUiSound(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        client.player.playSound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 0.28f, 1.8f);
        client.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.4f);
    }

    private static void playMagicPickupSound(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        client.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.38f, 1.6f);
        client.player.playSound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 0.22f, 1.9f);
    }

    public static int getCompanionQuestStage(String botAlias) {
        if (botAlias == null || botAlias.isBlank()) {
            return -1;
        }
        Integer v = COMPANION_STAGE.get(botAlias.trim());
        return v != null ? v : -1;
    }

    public static boolean isCompanionPermanent(String botAlias) {
        if (botAlias == null || botAlias.isBlank()) {
            return false;
        }
        return COMPANION_PERMANENT.contains(botAlias.trim());
    }

    public static void applyCompanionQuestState(String botAlias, int stage, boolean permanent) {
        if (botAlias == null || botAlias.isBlank()) {
            return;
        }
        String key = botAlias.trim();
        COMPANION_STAGE.put(key, Math.max(0, stage));
        if (permanent) {
            COMPANION_PERMANENT.add(key);
        } else {
            COMPANION_PERMANENT.remove(key);
        }
    }

    public static void appendDialogue(String botAlias, String line) {
        if (botAlias == null || botAlias.isBlank() || line == null || line.isBlank()) {
            return;
        }
        String key = botAlias.trim();
        java.util.ArrayDeque<String> q = DIALOGUE_LOG.computeIfAbsent(key, ignored -> new java.util.ArrayDeque<>());
        q.addLast(line);
        while (q.size() > 48) {
            q.removeFirst();
        }
    }

    public static java.util.List<String> getDialogueLines(String botAlias, int maxLines) {
        if (botAlias == null || botAlias.isBlank()) {
            return java.util.List.of();
        }
        java.util.ArrayDeque<String> q = DIALOGUE_LOG.get(botAlias.trim());
        if (q == null || q.isEmpty()) {
            return java.util.List.of();
        }
        int limit = Math.max(1, maxLines);
        java.util.ArrayList<String> out = new java.util.ArrayList<>(Math.min(limit, q.size()));
        int skip = Math.max(0, q.size() - limit);
        int i = 0;
        for (String s : q) {
            if (i++ < skip) continue;
            out.add(s);
        }
        return out;
    }

    public static void setPendingShelter(String type, String botTarget) {
        net.wcfcarolina13.GameAI.schematic.SchematicData schematicData = null;
        if (type != null) {
            var schematicOpt = net.wcfcarolina13.GameAI.schematic.SchematicReader.loadFromResources(type);
            if (schematicOpt.isPresent()) {
                schematicData = schematicOpt.get();
            } else {
                schematicData = getProceduralShelterDimensions(type);
            }
        }
        pendingPlacementPreview = new PlacementPreviewSession(
            PlacementPreviewKind.CONSTRUCTION,
            type,
            humanizeShelterPreviewLabel(type),
            botTarget,
            true,
            true,
            schematicData
        );
        resetPendingPreviewTransform();
    }

    public static void setPendingFarm(String botTarget) {
        pendingPlacementPreview = new PlacementPreviewSession(
            PlacementPreviewKind.FARM,
            "farm",
            "Farm",
            botTarget,
            false,
            false,
            null
        );
        resetPendingPreviewTransform();
    }
    
    /**
     * Get hard-coded dimensions for all structure types for particle preview.
     * These dimensions match the actual schematic sizes or are approximations.
     */
    private static net.wcfcarolina13.GameAI.schematic.SchematicData getProceduralShelterDimensions(String type) {
        return switch (type.toLowerCase()) {
            // Procedural shelters
            case "hovel" -> new net.wcfcarolina13.GameAI.schematic.SchematicData("hovel", 9, 5, 9, java.util.List.of(), java.util.List.of());
            case "burrow" -> new net.wcfcarolina13.GameAI.schematic.SchematicData("burrow", 5, 8, 5, java.util.List.of(), java.util.List.of());
            // Schematic-based structures (get from SimpleSchematicBuilder if available)
            case "test_platform" -> getSchematicOrFallback("test_platform", 3, 1, 3);
            case "small_hut" -> getSchematicOrFallback("small_hut", 5, 4, 6);
            case "small_shelter" -> getSchematicOrFallback("small_shelter", 5, 5, 5);
            case "watchtower" -> getSchematicOrFallback("watchtower", 4, 9, 4);
            case "bridge" -> getSchematicOrFallback("bridge", 9, 3, 3);
            case "defensive_wall_section" -> getSchematicOrFallback("defensive_wall_section", 5, 5, 2);
            case "defensive_wall_corner" -> getSchematicOrFallback("defensive_wall_corner", 3, 6, 3);
            case "defensive_gatehouse" -> getSchematicOrFallback("defensive_gatehouse", 5, 5, 3);
            default -> null;
        };
    }
    
    /**
     * Try to get schematic from SimpleSchematicBuilder, fallback to dimensions if not available.
     */
    private static net.wcfcarolina13.GameAI.schematic.SchematicData getSchematicOrFallback(String name, int x, int y, int z) {
        net.wcfcarolina13.GameAI.schematic.SchematicData data = net.wcfcarolina13.GameAI.schematic.SimpleSchematicBuilder.getBuiltIn(name);
        if (data != null) {
            return data;
        }
        return new net.wcfcarolina13.GameAI.schematic.SchematicData(name, x, y, z, java.util.List.of(), java.util.List.of());
    }

    public static void clearPendingShelter() {
        pendingPlacementPreview = null;
        resetPendingPreviewTransform();
    }
    
    public static boolean hasPendingShelter() {
        return pendingPlacementPreview != null;
    }

    public static void setPendingDirectionalMining(String actionLabel, String command) {
        pendingDirectionalActionLabel = actionLabel;
        pendingDirectionalCommand = command;
        directionalPreviewTick = 0;
    }

    public static void clearPendingDirectionalMining() {
        pendingDirectionalActionLabel = null;
        pendingDirectionalCommand = null;
        directionalPreviewTick = 0;
    }

    private static boolean hasPendingDirectionalMining() {
        return pendingDirectionalCommand != null && !pendingDirectionalCommand.isBlank();
    }

    @Override
    public void onInitializeClient() {
        HandledScreens.register(Frens.BOT_PLAYER_INV_HANDLER, BotPlayerInventoryScreen::new);
        HandledScreens.register(Frens.BOT_ENCHANT_HANDLER, net.minecraft.client.gui.screen.ingame.EnchantmentScreen::new);
        HandledScreens.register(Frens.BOT_ANVIL_HANDLER, net.minecraft.client.gui.screen.ingame.AnvilScreen::new);

        // Conflict check: this is a fork of the upstream "ai-player" mod. If both are installed
        // simultaneously, every keybind shows up twice in the controls menu (both mods register
        // "Frens: ..." display strings), the /bot command tree collides, and key presses are
        // dispatched non-deterministically between the two mods — which is how "follow go-to"
        // silently stops working. Detect it at init and flag a chat warning after the player
        // joins. See {@link #aiPlayerConflictWarned} for the one-shot guard.
        try {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("ai-player")) {
                aiPlayerConflictDetected = true;
                org.slf4j.LoggerFactory.getLogger("frens").error(
                        "[Frens] CONFLICT: The upstream 'ai-player' mod is installed alongside Frens. "
                                + "Close the game and delete 'ai-player-*.jar' from your mods folder. "
                                + "Frens is a drop-in replacement — keeping both causes duplicate keybinds "
                                + "and breaks commands like follow go-to.");
            }
        } catch (Throwable ignored) {
            // FabricLoader lookup is best-effort; never block mod init on the check.
        }

        // Keybind fallback for Shift+F1 / Shift+F2.
        // Notes:
        // - The mixin-based shortcut (Shift+F1/Shift+F2) still exists and suppresses vanilla F1/F2 side effects.
        // - These are regular keybinds (no modifier support), intended as a reliable, rebindable alternative
        //   especially on macOS where F-keys may be captured by the OS unless the user holds Fn or enables
        //   "Use F1, F2, etc. keys as standard function keys".
        // - Defaults: [`] follow-toggle, [-] go-to-look, [\] stop-look.
        KEY_FOLLOW_TOGGLE_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.follow_toggle_look",
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            KeyBinding.Category.MISC
        ));
        KEY_GO_TO_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.go_to_look",
            // Default '-' as a single, memorable "context" key:
            // - before recruitment: opens recruitment dialogue
            // - after recruitment (when spells are available and companion is far away): opens spells
            // - otherwise: go-to-look
            GLFW.GLFW_KEY_MINUS,
            KeyBinding.Category.MISC
        ));
        KEY_OPEN_GUIDE = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.open_guide",
            GLFW.GLFW_KEY_RIGHT_BRACKET,
            KeyBinding.Category.MISC
        ));
        KEY_OPEN_SPELLS = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.open_spells",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        KEY_RESUME = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.resume",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        KEY_STOP_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.stop_look",
            GLFW.GLFW_KEY_BACKSLASH,
            KeyBinding.Category.MISC
        ));

        KEY_LOCK_PREVIEW = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.lock_preview",
            // Default ';' to avoid colliding with vanilla Advancements ('L') and this mod's other defaults.
            GLFW.GLFW_KEY_SEMICOLON,
            KeyBinding.Category.MISC
        ));

        KEY_LEASH = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.leash",
            GLFW.GLFW_KEY_APOSTROPHE,
            KeyBinding.Category.MISC
        ));

        KEY_RECRUIT_CONTACT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.recruit_contact",
            // Optional dedicated key; unbound by default (the go-to key provides the main context behavior).
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        KEY_ZONE_CONFIRM = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.zone_confirm",
            GLFW.GLFW_KEY_EQUAL,
            KeyBinding.Category.MISC
        ));

        // Unbound by default — rescue teleport is an emergency un-stick. User binds it in Controls
        // so it's a deliberate opt-in and never conflicts with other keybinds.
        KEY_RESCUE_TELEPORT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.rescue_teleport",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        // Unbound by default — stand-down pauses follow + drop-sweep for 60s without
        // canceling the active task. Useful when sniping mobs and you don't want the
        // bot running out to fetch the XP orbs / drops. Already reachable as slot 1 in
        // the hotkey overlay; this gives users a direct keybind option.
        KEY_STAND_DOWN_LOOK = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.frens.stand_down_look",
            GLFW.GLFW_KEY_UNKNOWN,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.getNetworkHandler() == null) {
                modeSelectionRequired = false;
                modeSelectionCanChoose = false;
                modeSelectionWorldKey = "default";
                modeSelectionAutoOpenPending = false;
                modeSelectionOpenedThisConnection = false;
                allowEveryoneSkinChange = false;
                allowCustomSkins = false;
                spellHintFirstScanDone = false;
                learningInputStreamActive = false;
                learningInputSessionToken = 0L;
                learningInputTickHz = 0;
                previewLockBindingMigrationChecked = false;
                resetNoBotsRestoreState();
                return;
            }
            migrateLegacyPreviewLockBinding(client);
            tickLearningInputTelemetry(client);
            tickNoBotsRestoreState(client);
            maybeWarnAboutAiPlayerConflict(client);
            if (client.currentScreen != null) {
                return;
            }

            if (net.wcfcarolina13.GraphicalUserInterface.NavigationHudOverlay.isVisible()) {
                long window = MinecraftClient.getInstance().getWindow().getHandle();
                if (org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_Y) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                    net.wcfcarolina13.GraphicalUserInterface.NavigationHudOverlay.accept();
                } else if (org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_N) == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                    net.wcfcarolina13.GraphicalUserInterface.NavigationHudOverlay.dismiss();
                }
            }

            if (modeSelectionAutoOpenPending && modeSelectionRequired && modeSelectionCanChoose) {
                client.setScreen(new WorldModeSelectionScreen(modeSelectionWorldKey, true));
                modeSelectionAutoOpenPending = false;
                modeSelectionOpenedThisConnection = true;
                return;
            }

            // If the server just told us we're eligible (entered a village), auto-open the dialogue once.
            if (recruitmentAutoOpenPending) {
                if (survivalRecruitmentEnabled
                        && !survivalRecruitmentCompleted
                        && recruitmentPromptVisible
                        && !recruitmentAutoOpenedThisSession) {
                    ClientPlayNetworking.send(new RequestRecruitmentDialoguePayload());
                    recruitmentAutoOpenedThisSession = true;
                }
                recruitmentAutoOpenPending = false;
            }

            if (KEY_FOLLOW_TOGGLE_LOOK.wasPressed()) {
                handleFollowToggleLookedAt(client);
            }
            boolean goToPressed = false;
            while (KEY_GO_TO_LOOK.wasPressed()) {
                goToPressed = true;
            }

            if (goToPressed) {
                if (tryOpenModeSelectionFromContextKey(client)) {
                    return;
                }
                if (shouldOpenNoBotsRestoreFromContextKey(client)) {
                    openNoBotsRestoreScreen(client);
                    return;
                }
                if (hasPendingDirectionalMining() || hasPendingShelter()) {
                    // Pending look-confirm commands must consume '-' before contextual spells.
                    handleGoToLook(client);
                } else {
                    // Contextual behavior: before recruitment is complete and no companion is present,
                    // reuse the user's go-to key to initiate recruitment dialogue (prevents keybind conflicts).
                    if (shouldUseGoToKeyForRecruitmentContact(client)) {
                        handleRecruitContactKey(client);
                    } else {
                        boolean forceGoToStorage = shouldForceGoToLookStorageContext(client);
                        // '-' is only temporarily rebound to spells while holding a spell trigger
                        // item (tome/horn/eye) or when near an enchanting table.
                        if (!forceGoToStorage && isTemporaryGoToSpellsOverrideActive(client)) {
                            handleSpellsContextKey(client);
                        } else {
                            handleGoToLook(client);
                        }
                    }
                }
            }
            if (KEY_OPEN_GUIDE.wasPressed()) {
                handleOpenGuideKey(client);
            }
            if (KEY_OPEN_SPELLS.wasPressed()) {
                handleSpellsContextKey(client);
            }
            if (KEY_RESUME.wasPressed()) {
                handleResumeKey(client);
            }
            if (KEY_STAND_DOWN_LOOK.wasPressed()) {
                handleStandDownLook(client);
            }
            handlePendingPreviewControls(client);
            tickStopHoldBehavior(client);
            if (KEY_LEASH.wasPressed()) {
                handleLeashKey(client);
            }
            if (KEY_RECRUIT_CONTACT.wasPressed()) {
                // When go-to key and recruit key share the same physical key, the go-to branch above
                // already handled this press. Avoid a second contextual pass that can open spells.
                if (goToPressed) {
                    // no-op
                } else if (shouldForceGoToLookStorageContext(client)) {
                    handleGoToLook(client);
                } else {
                // Context key (default '-'):
                // - During recruitment: initiates contact.
                // - After recruitment: opens companion spells when available.
                    if (!handleSpellsContextKey(client)) {
                        handleRecruitContactKey(client);
                    }
                }
            }

            // Zone wand: confirm key and preview particles
            if (KEY_ZONE_CONFIRM != null && KEY_ZONE_CONFIRM.wasPressed()) {
                handleZoneConfirmKey(client);
            }
            tickZonePreview(client);

            // Rescue teleport: pull a nearby following bot to your feet when it's wedged.
            // All gating (distance, sight, vertical offset, follow state) happens server-side
            // in RescueTeleportNetworkManager — client just signals the intent.
            if (KEY_RESCUE_TELEPORT != null && KEY_RESCUE_TELEPORT.wasPressed()) {
                ClientPlayNetworking.send(new net.wcfcarolina13.network.RescueTeleportRequestPayload());
            }

            if (CompanionHotkeyOverlayHud.isVisible() && client.player != null) {
                int selected = client.player.getInventory().getSelectedSlot();
                if (overlayHotbarLockedSlot < 0) {
                    overlayHotbarLockedSlot = selected;
                } else if (selected != overlayHotbarLockedSlot) {
                    client.player.getInventory().setSelectedSlot(overlayHotbarLockedSlot);
                }
            } else {
                overlayHotbarLockedSlot = -1;
            }
            tickHotkeyOverlaySelection(client);

            // If the overlay was closed by a key selection this tick, restore the locked slot once.
            if (!CompanionHotkeyOverlayHud.isVisible() && overlayHotbarLockedSlot >= 0 && client.player != null) {
                client.player.getInventory().setSelectedSlot(overlayHotbarLockedSlot);
                overlayHotbarLockedSlot = -1;
            }

            // Update leash button visibility based on whether we're looking at a bot with leashed animals
            updateLeashButtonVisibility(client);

            // Show a short, top-right hint when we *first* acquire a spell-enabling item.
            tickSpellsAcquireHint(client);
            tickDirectionalMiningPreview(client);
            tickLookedAtBotStatusPeek(client);
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.ConfigSyncPayload.ID, (payload, context) -> {
            String configJson = payload.configJson();
            MinecraftClient syncClient = context.client();
            syncClient.execute(() -> {
                if (!syncClient.isInSingleplayer()) {
                    enterRemoteConfig();
                }
                ConfigJsonUtil.applyConfigJson(configJson);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(OpenConfigPayload.ID, (payload, context) -> {
            String configJson = payload.configData();
            MinecraftClient openClient = context.client();
            openClient.execute(() -> {
                // Same guard as the ConfigSyncPayload path: a remote server's globals must never
                // become this client's local settings. No-op once the JOIN sync already ran.
                if (!openClient.isInSingleplayer()) {
                    enterRemoteConfig();
                }
                ConfigJsonUtil.applyConfigJson(configJson);
                MinecraftClient client = MinecraftClient.getInstance();
                Screen parent = client.currentScreen;
                client.setScreen(new ConfigManager(Text.literal("AI Player Configuration"), parent));
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(BasesListPayload.ID, (payload, context) -> {
            String json = payload.basesJson();
            context.client().execute(() -> BaseManagerScreen.applyBasesJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.SoulVoicePayload.ID, (payload, context) ->
                net.wcfcarolina13.ui.SoulVoiceClientPlayer.onPayload(payload));

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.AdminMaxBaseRadiusStatePayload.ID, (payload, context) -> {
            int current = payload.current();
            int hardLimit = payload.hardLimit();
            context.client().execute(() -> net.wcfcarolina13.GraphicalUserInterface.AdminWorldSettingsScreen.pushState(current, hardLimit));
        });

        ClientPlayNetworking.registerGlobalReceiver(CraftingHistoryPayload.ID, (payload, context) -> {
            String json = payload.historyJson();
            context.client().execute(() -> CraftingHistoryScreen.applyHistoryJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(CookablesPayload.ID, (payload, context) -> {
            String json = payload.cookablesJson();
            context.client().execute(() -> CookablesScreen.applyCookablesJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(HuntablesPayload.ID, (payload, context) -> {
            String json = payload.huntablesJson();
            context.client().execute(() -> HuntablesScreen.applyHuntablesJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.HuntConfigPayload.ID, (payload, context) -> {
            String json = payload.configJson();
            context.client().execute(() -> HuntablesScreen.applyHuntConfigJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.ChestRegistryPayload.ID, (payload, context) -> {
            String json = payload.json();
            context.client().execute(() -> net.wcfcarolina13.GraphicalUserInterface.BotStorageScreen.applyChestRegistryJson(json));
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.HuntDiscoveryPayload.ID, (payload, context) -> {
            String label = payload.mobLabel();
            context.client().execute(() -> {
                MinecraftClient mc = context.client();
                mc.getToastManager().add(
                    net.minecraft.client.toast.SystemToast.create(mc,
                        net.minecraft.client.toast.SystemToast.Type.NARRATOR_TOGGLE,
                        net.minecraft.text.Text.of("New Hunting Target!"),
                        net.minecraft.text.Text.of(label + " added to the hunting menu")));
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(ResumeDecisionPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                resumeDecisionActive = payload.active();
                resumeDecisionBotName = payload.active() ? payload.botName() : null;
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RecruitmentStatePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                survivalRecruitmentEnabled = payload.enabled();
                survivalRecruitmentCompleted = payload.recruited();
                modeSelectionRequired = payload.modeSelectionRequired();
                modeSelectionCanChoose = payload.modeSelectionCanChoose();
                modeSelectionWorldKey = (payload.worldKey() == null || payload.worldKey().isBlank())
                        ? "default"
                        : payload.worldKey();
                allowEveryoneSkinChange = payload.allowEveryoneSkinChange();
                allowCustomSkins = payload.allowCustomSkins();
                if (modeSelectionRequired && modeSelectionCanChoose && !modeSelectionOpenedThisConnection) {
                    modeSelectionAutoOpenPending = true;
                }
                if (!modeSelectionRequired) {
                    modeSelectionAutoOpenPending = false;
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.currentScreen instanceof WorldModeSelectionScreen) {
                        client.setScreen(null);
                    }
                }
                if (payload.botAlias() != null && !payload.botAlias().isBlank()) {
                    recruitmentBotAlias = payload.botAlias();
                }
                if (survivalRecruitmentCompleted) {
                    recruitmentPromptVisible = false;
                    recruitmentAutoOpenPending = false;
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.currentScreen instanceof RecruitmentDialogueScreen) {
                        client.setScreen(null);
                    }
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RecruitmentPromptPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                boolean wasVisible = recruitmentPromptVisible;
                if (payload.botAlias() != null && !payload.botAlias().isBlank()) {
                    recruitmentBotAlias = payload.botAlias();
                }
                boolean dialogOpen = client != null && client.currentScreen instanceof RecruitmentDialogueScreen;
                if (dialogOpen) {
                    recruitmentPromptVisible = false;
                    recruitmentAutoOpenPending = false;
                    return;
                }
                recruitmentPromptVisible = payload.visible();
                if (survivalRecruitmentCompleted) {
                    recruitmentPromptVisible = false;
                }

                // Trigger auto-open only on the edge: hidden -> visible.
                if (!survivalRecruitmentCompleted
                        && survivalRecruitmentEnabled
                        && recruitmentPromptVisible
                        && !wasVisible
                        && !recruitmentAutoOpenedThisSession) {
                    recruitmentAutoOpenPending = true;
                }
                if (!recruitmentPromptVisible) {
                    recruitmentAutoOpenPending = false;
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(OpenRecruitmentDialoguePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) {
                    return;
                }
                String alias = payload.botAlias();
                String flavor = payload.villageFlavor();
                client.setScreen(new RecruitmentDialogueScreen(alias, flavor));
                recruitmentPromptVisible = false;
                recruitmentAutoOpenPending = false;
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.CompanionQuestResponsePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                String alias = payload.botAlias();
                String joined = payload.linesJoined();
                applyCompanionQuestState(alias, payload.stage(), payload.permanent());
                if (alias == null || alias.isBlank() || joined == null || joined.isBlank()) {
                    return;
                }
                // Each line is already "bot-authored" server-side; we prefix it with the bot alias here.
                String[] lines = joined.split("\\n");
                for (String line : lines) {
                    if (line != null && !line.isBlank()) {
                        appendDialogue(alias, alias + ": " + line);
                    }
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.CompanionQuestStatePayload.ID, (payload, context) -> {
            context.client().execute(() -> applyCompanionQuestState(payload.botAlias(), payload.stage(), payload.permanent()));
        });

        ClientPlayNetworking.registerGlobalReceiver(RecruitmentAdminStatusPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                String alias = payload.botAlias();
                String joined = payload.linesJoined();
                if (alias == null || alias.isBlank() || joined == null || joined.isBlank()) {
                    return;
                }
                String[] lines = joined.split("\\n");
                for (String line : lines) {
                    if (line == null || line.isBlank()) continue;
                    appendDialogue(alias, "Admin: " + line);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RecruitmentAdminPermissionsPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                String alias = payload.botAlias();
                String json = payload.jsonData();
                if (alias == null || alias.isBlank() || json == null || json.isBlank()) {
                    return;
                }
                BotPlayerInventoryScreen.applyAdminPermissionsJson(alias, json);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(BotTaskPeekStatusPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                lookedAtBotStatus = payload;
                lookedAtBotStatusAtMs = System.currentTimeMillis();
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(LearningSessionStatusPayload.ID, (payload, context) -> {
            context.client().execute(() -> applyLearningSessionStatus(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.NavigationRequestPayload.ID, (payload, context) -> {
            net.wcfcarolina13.GraphicalUserInterface.NavigationHudOverlay.show(payload.botAlias(), payload.destination(), payload.estimatedSeconds());
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.0f);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.BotNavTierPayload.ID, (payload, context) -> {
            FrensClient.setCachedBotNavTier(payload.tier());
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.GuideInventoryAccessPayload.ID, (payload, context) -> {
            // Set flags that the inventory screen reads to restrict tab navigation.
            net.wcfcarolina13.GraphicalUserInterface.BotPlayerInventoryScreen.guideRemoteOpen = true;
            net.wcfcarolina13.GraphicalUserInterface.BotPlayerInventoryScreen.guideRemoteFullAccess = payload.fullAccess();
            net.wcfcarolina13.GraphicalUserInterface.BotPlayerInventoryScreen.guideRemoteAccessReason =
                    payload.fullAccess() ? payload.reason() : "";
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.ZoneCornerSetPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                var player = context.client().player;
                if (player == null) return;
                BlockPos pos = new BlockPos(payload.posX(), payload.posY(), payload.posZ());
                if (payload.cornerIndex() == 1) {
                    pendingZonePos1 = pos;
                    pendingZonePos2 = null;
                    pendingZoneDimension = context.client().world != null
                            ? context.client().world.getRegistryKey() : null;
                    pendingZonePreviewActive = false;
                    player.sendMessage(net.minecraft.text.Text.literal(
                            "\u00A7aCorner 1 set at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                            + " \u2014 right-click to set Corner 2"), true);
                } else if (payload.cornerIndex() == 2 && pendingZonePos1 != null) {
                    pendingZonePos2 = pos;
                    pendingZonePreviewActive = true;
                    BlockPos p1 = pendingZonePos1;
                    player.sendMessage(net.minecraft.text.Text.literal(
                            "\u00A7aZone selected (" + p1.getX() + "," + p1.getY() + "," + p1.getZ()
                            + " \u2192 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                            + ") \u2014 [=] confirm | right-click to re-select"), true);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.LockModeStatePayload.ID, (payload, context) -> {
            net.wcfcarolina13.GraphicalUserInterface.BotControlScreen.setLockModeActive(payload.active());
        });

        ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.ZoneListPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                net.wcfcarolina13.GraphicalUserInterface.BaseManagerScreen.onZoneListReceived(payload.zonesJson());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(PlayerPreserveStatePayload.ID, (payload, context) -> {
            net.wcfcarolina13.GraphicalUserInterface.BotPlayerPreferencesScreen.setServerValue(payload.enabled());
        });

        ClientPlayNetworking.registerGlobalReceiver(PlayerAutoAcceptPreciousStatePayload.ID, (payload, context) -> {
            net.wcfcarolina13.GraphicalUserInterface.BotPlayerPreferencesScreen.setAutoAcceptPreciousServerValue(payload.enabled());
        });

        HudRenderCallback.EVENT.register((context, tickDelta) -> resetTopTipLayout(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderResumeDecisionHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderLeashButton(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderPendingShelterHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderModeSelectionReminder(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderNoBotsPresentHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderRecruitmentPrompt(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderSpellsAcquireHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderSpellsPrompt(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderSchematicPreview(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderDirectionalMiningHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> CompanionHotkeyOverlayHud.render(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderLookedAtBotStatusHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) -> renderLookedAtBotInventoryHint(context));
        HudRenderCallback.EVENT.register((context, tickDelta) ->
                net.wcfcarolina13.GraphicalUserInterface.HuntTargetPickerOverlay.render(context));
        HudRenderCallback.EVENT.register((context, tickDelta) ->
                net.wcfcarolina13.GraphicalUserInterface.StoreTargetPickerOverlay.render(context));
        HudRenderCallback.EVENT.register((context, tickDelta) ->
                net.wcfcarolina13.GraphicalUserInterface.NavigationHudOverlay.render(context));
        SleepCommandHintHud.register();

        // Update schematic preview box every client tick
        ClientTickEvents.END_CLIENT_TICK.register(FrensClient::updateSchematicPreviewBox);
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                net.wcfcarolina13.GraphicalUserInterface.HuntTargetPickerOverlay.onClientTick(client));
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                net.wcfcarolina13.GraphicalUserInterface.StoreTargetPickerOverlay.onClientTick(client));
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                net.wcfcarolina13.ui.SoulVoiceClientPlayer.onClientTick(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            net.wcfcarolina13.ui.SoulVoiceClientPlayer.stopAll();
            leaveRemoteConfig();
        });
        // Push this client's personal voice-category mute mask on join so the server can skip
        // muted categories per recipient instead of muting them for everyone.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                net.wcfcarolina13.FilingSystem.ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                if (cfg != null) {
                    net.wcfcarolina13.network.configNetworkManager.sendVoiceMuteMask(cfg.getMutedVoiceCategories());
                }
            } catch (Throwable t) {
                net.wcfcarolina13.Frens.LOGGER.debug("voice mute mask join send failed: {}", String.valueOf(t));
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
                net.wcfcarolina13.ui.SoulVoiceClientPlayer.shutdown());

    }

    private static void resetTopTipLayout(DrawContext context) {
        topTipLeftY = TOP_TIP_MARGIN;
        topTipCenterY = TOP_TIP_MARGIN;
        topTipRightY = TOP_TIP_MARGIN;
    }

    private static void applyLearningSessionStatus(LearningSessionStatusPayload payload) {
        if (payload == null) {
            learningInputStreamActive = false;
            learningInputSessionToken = 0L;
            learningInputTickHz = 0;
            return;
        }
        learningInputStreamActive = payload.active();
        learningInputSessionToken = payload.sessionToken();
        learningInputTickHz = Math.max(0, payload.tickHz());
        learningInputLastSentMs = 0L;
        learningInputSampleSeq = 0;
    }

    private static void tickLearningInputTelemetry(MinecraftClient client) {
        if (!learningInputStreamActive || learningInputSessionToken <= 0L) {
            return;
        }
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(LearningInputSamplePayload.ID)) {
            return;
        }
        int hz = Math.max(2, Math.min(20, learningInputTickHz > 0 ? learningInputTickHz : 20));
        long minIntervalMs = Math.max(25L, 1000L / hz);
        long now = System.currentTimeMillis();
        if (now - learningInputLastSentMs < minIntervalMs) {
            return;
        }

        int flags = 0;
        if (client.options.forwardKey.isPressed()) flags |= (1 << 0);
        if (client.options.backKey.isPressed()) flags |= (1 << 1);
        if (client.options.leftKey.isPressed()) flags |= (1 << 2);
        if (client.options.rightKey.isPressed()) flags |= (1 << 3);
        if (client.options.jumpKey.isPressed()) flags |= (1 << 4);
        if (client.options.sneakKey.isPressed()) flags |= (1 << 5);
        if (client.options.sprintKey.isPressed()) flags |= (1 << 6);
        if (client.options.useKey.isPressed()) flags |= (1 << 7);
        if (client.options.attackKey.isPressed()) flags |= (1 << 8);

        LearningInputSamplePayload payload = new LearningInputSamplePayload(
                learningInputSessionToken,
                ++learningInputSampleSeq,
                flags,
                client.player.getInventory().getSelectedSlot(),
                client.player.getYaw(),
                client.player.getPitch()
        );
        ClientPlayNetworking.send(payload);
        learningInputLastSentMs = now;
    }

    private static int reserveTopTipY(TopTipLane lane, int tipHeight) {
        int height = Math.max(1, tipHeight);
        int y;
        switch (lane) {
            case LEFT -> {
                y = topTipLeftY;
                topTipLeftY += height + TOP_TIP_GAP;
            }
            case RIGHT -> {
                y = topTipRightY;
                topTipRightY += height + TOP_TIP_GAP;
            }
            case CENTER -> {
                y = topTipCenterY;
                topTipCenterY += height + TOP_TIP_GAP;
            }
            default -> y = TOP_TIP_MARGIN;
        }
        return y;
    }

    public static String getGuideHotkeyDisplayName() {
        String keyName = keyNameOrNull(KEY_OPEN_GUIDE);
        return keyName != null ? keyName : "]";
    }

    public static String getCompanionShortcutInstruction(CompanionShortcut shortcut) {
        if (shortcut == null) {
            return formatCompanionShortcutInstruction(null, null, null);
        }

        String directKey = switch (shortcut) {
            case GUIDE -> keyNameOrNull(KEY_OPEN_GUIDE);
            case SPELLS -> keyNameOrNull(KEY_OPEN_SPELLS);
            case STOP -> keyNameOrNull(KEY_STOP_LOOK);
            case RESUME -> keyNameOrNull(KEY_RESUME);
            case FOLLOW -> keyNameOrNull(KEY_FOLLOW_TOGGLE_LOOK);
            case GO_TO_LOOK -> keyNameOrNull(KEY_GO_TO_LOOK);
            default -> null;
        };
        Integer overlaySlot = overlaySlotFor(shortcut);
        String overlayKey = overlaySlot != null ? keyNameOrNull(KEY_STOP_LOOK) : null;
        return formatCompanionShortcutInstruction(directKey, overlayKey, overlaySlot);
    }

    public static String getCompanionShortcutTooltipLine(CompanionShortcut shortcut) {
        return "World shortcut: " + getCompanionShortcutInstruction(shortcut);
    }

    static String formatCompanionShortcutInstruction(String directKey, String overlayKey, Integer overlaySlot) {
        String direct = normalizeShortcutKey(directKey);
        if (direct != null) {
            return "[" + direct + "]";
        }

        String overlay = normalizeShortcutKey(overlayKey);
        if (overlay != null && overlaySlot != null) {
            return "Hold [" + overlay + "], then [" + overlaySlot + "]";
        }
        return "Unbound — configure in Controls";
    }

    static Integer overlaySlotFor(CompanionShortcut shortcut) {
        if (shortcut == null) {
            return null;
        }
        return switch (shortcut) {
            case RESUME -> 2;
            case SPELLS -> 3;
            case HOME -> 4;
            case SLEEP -> 5;
            case REGROUP -> 6;
            case STRIPMINE -> 7;
            case ASCENT -> 8;
            case DESCENT -> 9;
            case CLEANUP -> 0;
            default -> null;
        };
    }

    private static String normalizeShortcutKey(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return null;
        }
        return keyName.trim();
    }

    private static boolean tryOpenModeSelectionFromContextKey(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) {
            return false;
        }
        if (!modeSelectionRequired || !modeSelectionCanChoose) {
            return false;
        }
        if (client.currentScreen instanceof WorldModeSelectionScreen) {
            return true;
        }
        client.setScreen(new WorldModeSelectionScreen(modeSelectionWorldKey, true));
        modeSelectionAutoOpenPending = false;
        modeSelectionOpenedThisConnection = true;
        return true;
    }

    private static void handleOpenGuideKey(MinecraftClient client) {
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        String alias = resolveQuickBotTarget(client);
        if (alias == null || alias.isBlank()) {
            alias = (recruitmentBotAlias == null || recruitmentBotAlias.isBlank()) ? "bot" : recruitmentBotAlias;
        }
        client.setScreen(new BotGuideScreen(null, alias));
    }

    private static void renderModeSelectionReminder(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        if (!modeSelectionRequired || !modeSelectionCanChoose) {
            return;
        }

        String openKey = keyNameOrNull(KEY_GO_TO_LOOK);
        if (openKey == null) {
            openKey = "-";
        }
        String line1 = "World setup pending: choose Questing or Admin mode.";
        String line2 = "Press [" + openKey + "] to reopen setup, or use /bot spawn <name> admin";

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int x = 12;
        int boxH = client.textRenderer.fontHeight * 2 + 6;
        int y = reserveTopTipY(TopTipLane.LEFT, boxH + 7);

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
    }

    public static java.util.List<String> getKnownRestorableBotAliases() {
        java.util.LinkedHashSet<String> aliasSet = new java.util.LinkedHashSet<>();
        if (Frens.CONFIG != null) {
            aliasSet.addAll(Frens.CONFIG.getBotGameProfile().keySet());
            aliasSet.addAll(Frens.CONFIG.getBotOwnership().keySet());
            aliasSet.addAll(Frens.CONFIG.getAllBotAliases());
            aliasSet.addAll(Frens.CONFIG.getBotQuestMemory().keySet());
        }
        if (recruitmentBotAlias != null && !recruitmentBotAlias.isBlank()) {
            aliasSet.add(recruitmentBotAlias.trim());
        }

        java.util.ArrayList<String> aliases = new java.util.ArrayList<>();
        for (String alias : aliasSet) {
            if (alias == null) {
                continue;
            }
            String trimmed = alias.trim();
            if (trimmed.isEmpty() || "default".equalsIgnoreCase(trimmed)) {
                continue;
            }
            aliases.add(trimmed);
        }
        aliases.sort(String.CASE_INSENSITIVE_ORDER);
        return aliases;
    }

    private static void resetNoBotsRestoreState() {
        noBotsDetectedSinceMs = 0L;
        noBotsHintUntilMs = 0L;
        // Reset session dismissal so the hint shows again next time everyone goes missing.
        noBotsHintDismissed = false;
        noBotsHintCloseW = 0;
        noBotsHintCloseH = 0;
    }

    private static void tickNoBotsRestoreState(MinecraftClient client) {
        if (!shouldOfferNoBotsRestore(client)) {
            resetNoBotsRestoreState();
            return;
        }
        long now = System.currentTimeMillis();
        if (noBotsDetectedSinceMs == 0L) {
            noBotsDetectedSinceMs = now;
            noBotsHintUntilMs = 0L;
            return;
        }
        if (noBotsHintUntilMs == 0L && now - noBotsDetectedSinceMs >= NO_BOTS_HINT_DEBOUNCE_MS) {
            noBotsHintUntilMs = now + NO_BOTS_HINT_DURATION_MS;
        }
    }

    private static boolean shouldOpenNoBotsRestoreFromContextKey(MinecraftClient client) {
        if (!shouldOfferNoBotsRestore(client)) {
            return false;
        }
        if (client == null || client.currentScreen != null) {
            return false;
        }
        return noBotsDetectedSinceMs > 0L
                && System.currentTimeMillis() - noBotsDetectedSinceMs >= NO_BOTS_HINT_DEBOUNCE_MS;
    }

    private static boolean shouldOfferNoBotsRestore(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        if (survivalRecruitmentEnabled && !survivalRecruitmentCompleted) {
            return false;
        }
        java.util.List<String> knownAliases = getKnownRestorableBotAliases();
        if (knownAliases.isEmpty()) {
            return false;
        }
        java.util.HashSet<String> normalizedAliases = new java.util.HashSet<>();
        for (String alias : knownAliases) {
            normalizedAliases.add(alias.toLowerCase(java.util.Locale.ROOT));
        }
        // Use the TAB player list (all connected players regardless of distance) instead of
        // client.world.getPlayers() which only returns entities within tracking range (~128 blocks).
        try {
            if (client.getNetworkHandler() != null) {
                for (net.minecraft.client.network.PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                    if (entry.getProfile() != null && entry.getProfile().name() != null) {
                        String name = entry.getProfile().name().trim().toLowerCase(java.util.Locale.ROOT);
                        if (normalizedAliases.contains(name)) {
                            return false;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    private static void openNoBotsRestoreScreen(MinecraftClient client) {
        if (client == null) {
            return;
        }
        java.util.List<String> aliases = getKnownRestorableBotAliases();
        if (aliases.isEmpty()) {
            return;
        }
        client.setScreen(new BotRestoreScreen(null, aliases));
    }

    private static void renderNoBotsPresentHint(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        if (!shouldOfferNoBotsRestore(client)) {
            return;
        }
        long now = System.currentTimeMillis();
        // Gate rendering on the debounce only — once the "no bots" condition has
        // held long enough to confirm it, keep showing the hint indefinitely until
        // the user dismisses it (X button) or bots reappear (resetNoBotsRestoreState).
        if (noBotsDetectedSinceMs == 0L
                || now - noBotsDetectedSinceMs < NO_BOTS_HINT_DEBOUNCE_MS) {
            return;
        }
        if (noBotsHintDismissed) {
            return;
        }

        String openKey = keyNameOrNull(KEY_GO_TO_LOOK);
        if (openKey == null) {
            openKey = "-";
        }
        String line1 = "No companions are currently present in this world.";
        String line2 = "Press [" + openKey + "] to open the roster — click [x] to hide this hint";

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int textMaxW = Math.max(w1, w2);
        int closeBtnSize = 10;
        int closeBtnPad = 6; // gap between text and close button
        int maxW = textMaxW + closeBtnPad + closeBtnSize;
        int x = (context.getScaledWindowWidth() - maxW) / 2;
        int boxH = client.textRenderer.fontHeight * 2 + 6;
        int y = reserveTopTipY(TopTipLane.CENTER, boxH + 7);

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);

        // ── Dismiss [X] button (top-right corner of the hint box) ──
        noBotsHintCloseX = x + maxW - closeBtnSize + 2;
        noBotsHintCloseY = y - 2;
        noBotsHintCloseW = closeBtnSize;
        noBotsHintCloseH = closeBtnSize;
        context.fill(noBotsHintCloseX, noBotsHintCloseY,
                noBotsHintCloseX + closeBtnSize, noBotsHintCloseY + closeBtnSize,
                0xFF6B2A2A);
        context.fill(noBotsHintCloseX, noBotsHintCloseY,
                noBotsHintCloseX + closeBtnSize, noBotsHintCloseY + 1, 0xFF000000);
        context.fill(noBotsHintCloseX, noBotsHintCloseY + closeBtnSize - 1,
                noBotsHintCloseX + closeBtnSize, noBotsHintCloseY + closeBtnSize, 0xFF000000);
        context.fill(noBotsHintCloseX, noBotsHintCloseY,
                noBotsHintCloseX + 1, noBotsHintCloseY + closeBtnSize, 0xFF000000);
        context.fill(noBotsHintCloseX + closeBtnSize - 1, noBotsHintCloseY,
                noBotsHintCloseX + closeBtnSize, noBotsHintCloseY + closeBtnSize, 0xFF000000);
        context.drawText(client.textRenderer, "x",
                noBotsHintCloseX + (closeBtnSize - client.textRenderer.getWidth("x")) / 2,
                noBotsHintCloseY + (closeBtnSize - client.textRenderer.fontHeight) / 2 + 1,
                0xFFEFEFEF, false);
    }

    /**
     * Mouse click intercept entry point — invoked from the Mouse mixin BEFORE the
     * click reaches Minecraft's normal in-game handling.  Returns {@code true} if
     * the click landed on the no-bots hint X button (and was consumed).
     */
    public static boolean handleHudClick(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (noBotsHintDismissed) return false;
        if (noBotsHintCloseW <= 0 || noBotsHintCloseH <= 0) return false;
        // Only react when no Screen is open (HUD-level click).
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen != null) return false;
        // Mouse positions reported by Minecraft are pixel coords; HUD coords are scaled
        // by GUI scale.  Convert by dividing.  The framebuffer→GUI ratio equals the
        // window's height / scaled height.
        double scale = client.getWindow().getScaleFactor();
        if (scale <= 0) scale = 1.0;
        double sx = mouseX / scale;
        double sy = mouseY / scale;
        if (sx >= noBotsHintCloseX && sx <= noBotsHintCloseX + noBotsHintCloseW
                && sy >= noBotsHintCloseY && sy <= noBotsHintCloseY + noBotsHintCloseH) {
            noBotsHintDismissed = true;
            return true;
        }
        return false;
    }

    private static void handleRecruitContactKey(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        var player = client.player;
        if (player == null) {
            return;
        }
        if (!survivalRecruitmentEnabled || survivalRecruitmentCompleted) {
            return;
        }
        // Always allow an attempt; the server remains authoritative and will respond with an explanation
        // (e.g., not in a village / mode disabled). This avoids a soft-lock if the prompt desyncs.
        if (!recruitmentPromptVisible) {
            player.sendMessage(Text.literal("You try to make contact..."), true);
        }
        ClientPlayNetworking.send(new RequestRecruitmentDialoguePayload());
    }

    private static void renderRecruitmentPrompt(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        if (!survivalRecruitmentEnabled || survivalRecruitmentCompleted || !recruitmentPromptVisible) {
            return;
        }

        // Prefer showing the key that the player is most likely to have already bound:
        // their go-to key (which we treat as a contextual "contact" key when no companion is present).
        // Fall back to the dedicated recruit-contact binding if go-to is unbound.
        String keyName = resolveRecruitmentPromptKeyName();
        String line1 = "You sense someone watching...";
        String line2 = "Press [" + keyName + "] to initiate contact";

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int x = 12;
        int boxH = client.textRenderer.fontHeight * 2 + 6;
        int y = reserveTopTipY(TopTipLane.LEFT, boxH + 7);

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
    }

    private static void tickSpellsAcquireHint(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }

        // Only meaningful once a companion exists.
        if (!survivalRecruitmentEnabled || !survivalRecruitmentCompleted) {
            lastHasEyeToken = false;
            lastHasHornToken = false;
            lastHasEnchantingTableToken = false;
            lastHasWizardTomeToken = false;
            return;
        }

        boolean hasEye = hasEyeOfEnderToken(client);
        boolean hasHorn = hasGoatHornToken(client);
        boolean hasTable = hasEnchantingTableToken(client);
        boolean hasWizardTome = hasWizardTomeToken(client);

        // On the very first scan after joining, just seed the flags so items already in
        // inventory don't falsely trigger the "newly acquired" hint + sound.
        if (!spellHintFirstScanDone) {
            spellHintFirstScanDone = true;
            lastHasWizardTomeToken = hasWizardTome;
            lastHasEyeToken = hasEye;
            lastHasHornToken = hasHorn;
            lastHasEnchantingTableToken = hasTable;
            return;
        }

        String newlyAcquired = null;
        if (!lastHasWizardTomeToken && hasWizardTome) newlyAcquired = "Wizard's Tome";
        else if (!lastHasEyeToken && hasEye) newlyAcquired = "Eye of Ender";
        else if (!lastHasHornToken && hasHorn) newlyAcquired = "Goat Horn";
        else if (!lastHasEnchantingTableToken && hasTable) newlyAcquired = "Enchanting Table";

        lastHasWizardTomeToken = hasWizardTome;
        lastHasEyeToken = hasEye;
        lastHasHornToken = hasHorn;
        lastHasEnchantingTableToken = hasTable;

        if (newlyAcquired == null) {
            return;
        }

        if ("Wizard's Tome".equals(newlyAcquired)) {
            playMagicPickupSound(client);
        }

        if (!isGameplayTipsEnabled()) {
            return;
        }

        // Prefer the context key (default '-') since that's what actually opens spells in practice.
        String keyName = keyNameOrNull(KEY_GO_TO_LOOK);
        if (keyName == null) {
            keyName = "-";
        }

        spellsAcquireHintLine1 = "Spells available: " + newlyAcquired;
        spellsAcquireHintLine2 = "Press [" + keyName + "] to open spells";
        spellsAcquireHintUntilMs = System.currentTimeMillis() + 8_000L;
    }

    private static void renderSpellsAcquireHint(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }

        if (!survivalRecruitmentEnabled || !survivalRecruitmentCompleted) {
            return;
        }

        long now = System.currentTimeMillis();
        if (spellsAcquireHintUntilMs <= now) {
            return;
        }
        if (spellsAcquireHintLine1 == null || spellsAcquireHintLine2 == null) {
            return;
        }

        String line1 = spellsAcquireHintLine1;
        String line2 = spellsAcquireHintLine2;

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);

        int margin = 12;
        int x = Math.max(margin, client.getWindow().getScaledWidth() - margin - maxW);
        int boxH = client.textRenderer.fontHeight * 2 + 6;
        int y = reserveTopTipY(TopTipLane.RIGHT, boxH + 7);

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
    }

    private static boolean handleSpellsContextKey(MinecraftClient client) {
        if (client == null || client.player == null || client.currentScreen != null) {
            return false;
        }
        if (shouldForceGoToLookStorageContext(client)) {
            return false;
        }
        // If '-' is currently reserved for a pending directional/shelter confirm flow,
        // never open spells from contextual shortcuts.
        if (hasPendingDirectionalMining() || hasPendingShelter()) {
            return false;
        }

        // Don't interfere with recruitment flow.
        if (survivalRecruitmentEnabled && !survivalRecruitmentCompleted) {
            return false;
        }
        // Spells are only meaningful after a companion exists.
        if (!survivalRecruitmentEnabled || !survivalRecruitmentCompleted) {
            return false;
        }

        if (!canAccessCompanionSpells(client)) {
            return false;
        }

        // If Eye is the only access method and we are on cooldown, show an actionbar hint.
        if (isEyeOnlyAccess(client) && isEyeSpellOnCooldown()) {
            long sec = Math.max(1L, getEyeSpellCooldownRemainingMs() / 1000L);
            client.player.sendMessage(Text.literal("Eye of Ender spell is on cooldown (" + sec + "s)."), true);
            return true;
        }

        String alias = getRecruitmentBotAlias();
        playMagicUiSound(client);
        // Open the canonical bot inventory screen preselected to its Spells tab.
        // The screen is server-opened (BOT_PLAYER_INV_HANDLER), so flag the
        // request first, then ask the server to open the inventory.
        BotPlayerInventoryScreen.requestSpellsTab();
        String target = formatBotTarget(alias);
        sendChatCommand(client, target.isEmpty() ? "bot open" : ("bot open " + target));
        return true;
    }

    private static void renderSpellsPrompt(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        if (!survivalRecruitmentEnabled || !survivalRecruitmentCompleted) {
            return;
        }

        if (hasPendingDirectionalMining() || hasPendingShelter()) {
            return;
        }
        if (!isTemporaryGoToSpellsOverrideActive(client)) {
            return;
        }

        String keyName = keyNameOrNull(KEY_GO_TO_LOOK);
        if (keyName == null) {
            keyName = "-";
        }

        String line1 = "Spells are bound to [" + keyName + "] right now.";
        String line2;
        boolean holdingTrigger = isHoldingSpellTriggerItem(client);
        boolean nearTable = isNearEnchantingTable(client, 4);
        if (holdingTrigger && nearTable) {
            line2 = "Holding spell item + near enchanting table. Switch item to restore Go To Look.";
        } else if (holdingTrigger) {
            line2 = "Holding spell item. Switch hotbar item to restore Go To Look.";
        } else if (nearTable) {
            line2 = "Near enchanting table. Walk away to restore Go To Look.";
        } else {
            line2 = "Switch item or location to restore Go To Look.";
        }

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int x = 12;
        int boxH = client.textRenderer.fontHeight * 2 + 6;
        int y = reserveTopTipY(TopTipLane.LEFT, boxH + 7);

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
    }

    private static boolean isTemporaryGoToSpellsOverrideActive(MinecraftClient client) {
        if (client == null || client.player == null || client.currentScreen != null) {
            return false;
        }
        if (survivalRecruitmentEnabled && !survivalRecruitmentCompleted) {
            return false;
        }
        if (!survivalRecruitmentEnabled || !survivalRecruitmentCompleted) {
            return false;
        }
        return isHoldingSpellTriggerItem(client) || isNearEnchantingTable(client, 4);
    }

    /**
     * Keep '-' on go_to_look when the player is targeting storage and has a nearby bot.
     * This prevents accidental spellbook opens while trying to offload into chests/barrels.
     */
    private static boolean shouldForceGoToLookStorageContext(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        return isLookingAtChestLikeStorage(client, 64.0D) && hasNearbyBotCandidate(client, 24.0D);
    }

    private static boolean isLookingAtChestLikeStorage(MinecraftClient client, double maxDistance) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        HitResult hit = client.player.raycast(Math.max(4.0D, maxDistance), 1.0F, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        BlockPos pos = bhr.getBlockPos();
        if (pos == null || !client.world.isChunkLoaded(pos)) {
            return false;
        }
        var state = client.world.getBlockState(pos);
        return state != null
                && (state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.TRAPPED_CHEST)
                || state.isOf(Blocks.BARREL)
                || state.isOf(Blocks.ENDER_CHEST));
    }

    private static boolean hasNearbyBotCandidate(MinecraftClient client, double radius) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        double r = Math.max(8.0D, radius);
        double r2 = r * r;
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof PlayerEntity other) || other == client.player) {
                continue;
            }
            if (other.isRemoved() || !other.isAlive()) {
                continue;
            }
            if (other.squaredDistanceTo(client.player) <= r2) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHoldingSpellTriggerItem(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var main = client.player.getMainHandStack();
        if (main == null || main.isEmpty()) {
            return false;
        }
        if (main.isOf(ModItems.WIZARD_TOME) || main.isOf(Items.GOAT_HORN) || main.isOf(Items.ENDER_EYE)) {
            return true;
        }
        if (main.isOf(Items.WRITTEN_BOOK) || main.isOf(Items.ENCHANTED_BOOK)) {
            String name = main.getName() != null ? main.getName().getString() : "";
            String lower = name != null ? name.toLowerCase(java.util.Locale.ROOT) : "";
            return lower.contains("spellbook") || (lower.contains("wizard") && lower.contains("tome"));
        }
        return false;
    }

    private static boolean canAccessCompanionSpells(MinecraftClient client) {
        return hasSpellbookToken(client)
                || isNearEnchantingTable(client, 4)
                || hasEyeOfEnderToken(client)
                || hasGoatHornToken(client);
    }

    private static boolean isEyeOnlyAccess(MinecraftClient client) {
        boolean full = hasSpellbookToken(client) || isNearEnchantingTable(client, 4);
        boolean horn = !full && hasGoatHornToken(client);
        return !full && !horn && hasEyeOfEnderToken(client);
    }

    private static boolean isNearEnchantingTable(MinecraftClient client, int radius) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        BlockPos origin = client.player.getBlockPos();
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!client.world.isChunkLoaded(pos)) {
                continue;
            }
            var state = client.world.getBlockState(pos);
            if (state.isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSpellbookToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            // Preferred: real quest item (not enchantable).
            if (stack.isOf(ModItems.WIZARD_TOME)) {
                return true;
            }
            if (!(stack.isOf(Items.WRITTEN_BOOK) || stack.isOf(Items.ENCHANTED_BOOK))) {
                continue;
            }
            String name = stack.getName() != null ? stack.getName().getString() : "";
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            // Back-compat: older builds used a renamed book token containing "spellbook".
            if (lower.contains("spellbook")) {
                return true;
            }
            // New: allow renamed-book variants containing "wizard" + "tome".
            if (lower.contains("wizard") && lower.contains("tome")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWizardTomeToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(ModItems.WIZARD_TOME)) {
                return true;
            }
            if (!(stack.isOf(Items.WRITTEN_BOOK) || stack.isOf(Items.ENCHANTED_BOOK))) {
                continue;
            }
            String name = stack.getName() != null ? stack.getName().getString() : "";
            String lower = name != null ? name.toLowerCase(java.util.Locale.ROOT) : "";
            if (lower.contains("wizard") && lower.contains("tome")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEyeOfEnderToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.ENDER_EYE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGoatHornToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Items.GOAT_HORN)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnchantingTableToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            var stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.isOf(Blocks.ENCHANTING_TABLE.asItem())) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldUseGoToKeyForRecruitmentContact(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        if (!survivalRecruitmentEnabled || survivalRecruitmentCompleted) {
            return false;
        }
        if (!recruitmentPromptVisible) {
            return false;
        }
        // If the recruited companion is already nearby, don't steal the go-to key.
        return !isCompanionNearPlayer(client, 32.0D);
    }

    private static boolean isCompanionNearPlayer(MinecraftClient client, double maxDistanceBlocks) {
        if (client == null || client.player == null) {
            return false;
        }
        var world = client.world;
        if (world == null) {
            return false;
        }
        if (recruitmentBotAlias == null || recruitmentBotAlias.isBlank()) {
            return false;
        }
        String alias = recruitmentBotAlias.trim();
        double maxSq = maxDistanceBlocks * maxDistanceBlocks;
        try {
            for (PlayerEntity p : world.getPlayers()) {
                if (p == null || p == client.player) {
                    continue;
                }
                String name = p.getName() != null ? p.getName().getString() : null;
                if (name != null && name.equalsIgnoreCase(alias)) {
                    // "Near" is defined by distance, not mere client-side existence.
                    return p.squaredDistanceTo(client.player) <= maxSq;
                }
            }
        } catch (Throwable ignored) {
            // Best effort; if something changes in mappings, just don't gate on it.
        }
        return false;
    }

    private static boolean isCompanionPresentInClientWorld(MinecraftClient client) {
        if (client == null) {
            return false;
        }
        var world = client.world;
        if (world == null) {
            return false;
        }
        if (recruitmentBotAlias == null || recruitmentBotAlias.isBlank()) {
            return false;
        }
        String alias = recruitmentBotAlias.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (client.getNetworkHandler() != null) {
                for (net.minecraft.client.network.PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                    if (entry.getProfile() != null && entry.getProfile().name() != null) {
                        if (entry.getProfile().name().trim().equalsIgnoreCase(alias)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Best effort; if something changes in mappings, just don't gate on it.
        }
        return false;
    }

    private static String resolveRecruitmentPromptKeyName() {
        String goTo = keyNameOrNull(KEY_GO_TO_LOOK);
        if (goTo != null) {
            return goTo;
        }
        String recruit = keyNameOrNull(KEY_RECRUIT_CONTACT);
        if (recruit != null) {
            return recruit;
        }
        return "?";
    }

    private static String keyNameOrNull(KeyBinding binding) {
        if (binding == null) {
            return null;
        }
        try {
            String name = binding.getBoundKeyLocalizedText().getString();
            if (name == null || name.isBlank()) {
                return null;
            }
            // Heuristic: treat common "unbound" labels as unavailable.
            if ("unknown".equalsIgnoreCase(name) || "unassigned".equalsIgnoreCase(name)) {
                return null;
            }
            return name;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String keyTranslationOrNull(KeyBinding binding) {
        if (binding == null) {
            return null;
        }
        try {
            Object value = KeyBinding.class.getMethod("getBoundKeyTranslationKey").invoke(binding);
            if (value instanceof String translation && !translation.isBlank()) {
                return translation;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean invokeFirstNoArgMethod(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return false;
        }
        for (String methodName : methodNames) {
            if (methodName == null || methodName.isBlank()) {
                continue;
            }
            try {
                target.getClass().getMethod(methodName).invoke(target);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * One-shot chat warning when both Frens and the upstream {@code ai-player} mod are
     * installed simultaneously. Fires after the player joins a world (so the message
     * is actually visible in chat) and latches via {@link #aiPlayerConflictWarned} so
     * it only shows once per session.
     */
    private static void maybeWarnAboutAiPlayerConflict(MinecraftClient client) {
        if (!aiPlayerConflictDetected || aiPlayerConflictWarned) {
            return;
        }
        if (client == null || client.player == null) {
            return;
        }
        aiPlayerConflictWarned = true;
        client.player.sendMessage(Text.literal("§c[Frens] CONFLICT: 'ai-player' mod is installed alongside Frens."), false);
        client.player.sendMessage(Text.literal("§cClose the game and delete 'ai-player-*.jar' from your mods folder."), false);
        client.player.sendMessage(Text.literal("§cKeybinds will appear duplicated and commands like 'follow go-to' break until you remove it."), false);
    }

    private static void migrateLegacyPreviewLockBinding(MinecraftClient client) {
        if (previewLockBindingMigrationChecked || client == null || KEY_LOCK_PREVIEW == null) {
            return;
        }
        previewLockBindingMigrationChecked = true;

        String boundTranslation = keyTranslationOrNull(KEY_LOCK_PREVIEW);
        if (!"key.keyboard.l".equals(boundTranslation)) {
            return;
        }

        try {
            Object defaultKey = KeyBinding.class.getMethod("getDefaultKey").invoke(KEY_LOCK_PREVIEW);
            if (defaultKey == null) {
                return;
            }
            KeyBinding.class.getMethod("setBoundKey", defaultKey.getClass()).invoke(KEY_LOCK_PREVIEW, defaultKey);
            invokeFirstNoArgMethod(KeyBinding.class, "updateKeysByCode");
            invokeFirstNoArgMethod(client.options, "write", "save");
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Preview lock hotkey moved to ';' to avoid Advancements."), true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tickStopHoldBehavior(MinecraftClient client) {
        if (KEY_STOP_LOOK == null) {
            return;
        }
        boolean down = KEY_STOP_LOOK.isPressed();
        long now = System.currentTimeMillis();

        if (down) {
            if (!stopKeyDown) {
                stopKeyDown = true;
                stopKeyDownAtMs = now;
                stopKeyHoldConsumed = false;
            } else if (!stopKeyHoldConsumed && now - stopKeyDownAtMs >= STOP_HOLD_THRESHOLD_MS) {
                stopKeyHoldConsumed = true;
                CompanionHotkeyOverlayHud.show(HOTKEY_OVERLAY_DURATION_MS);
            }
            return;
        }

        if (!stopKeyDown) {
            return;
        }

        // Tap = legacy stop behavior. Hold = open overlay and consume stop tap.
        if (!stopKeyHoldConsumed) {
            handleStopLook(client);
        }
        stopKeyDown = false;
        stopKeyDownAtMs = 0L;
        stopKeyHoldConsumed = false;
    }

    private static void tickHotkeyOverlaySelection(MinecraftClient client) {
        Integer selected = CompanionHotkeyOverlayHud.pollSelection(client);
        if (selected == null) {
            return;
        }
        executeOverlayHotkeySelection(client, selected);
        CompanionHotkeyOverlayHud.hide();
    }

    private static void executeOverlayHotkeySelection(MinecraftClient client, int slot) {
        if (client == null || client.player == null) {
            return;
        }
        String target = resolveQuickBotTarget(client);
        String formattedTarget = target != null ? formatBotTarget(target) : null;

        switch (slot) {
            case 1 -> handleStandDownLook(client);
            case 2 -> {
                if (resumeDecisionActive) {
                    sendResumeDecision(client, true);
                } else if (formattedTarget != null) {
                    sendChatCommand(client, "bot resume " + formattedTarget);
                } else {
                    sendChatCommand(client, "bot resume");
                }
            }
            case 3 -> handleSpellsContextKey(client);
            case 4 -> handleReturnHomeLook(client);
            case 5 -> handleSleepLook(client);
            case 6 -> {
                if (formattedTarget != null) {
                    sendChatCommand(client, "bot come " + formattedTarget);
                } else {
                    sendChatCommand(client, "bot come");
                }
            }
            case 7 -> {
                if (formattedTarget != null) {
                    setPendingDirectionalMining("stripmine", "bot skill stripmine " + formattedTarget);
                } else {
                    setPendingDirectionalMining("stripmine", "bot skill stripmine");
                }
            }
            case 8 -> {
                if (formattedTarget != null) {
                    setPendingDirectionalMining("ascent", "bot skill mining ascent " + formattedTarget);
                } else {
                    setPendingDirectionalMining("ascent", "bot skill mining ascent");
                }
            }
            case 9 -> {
                if (formattedTarget != null) {
                    setPendingDirectionalMining("descent", "bot skill mining descent " + formattedTarget);
                } else {
                    setPendingDirectionalMining("descent", "bot skill mining descent");
                }
            }
            case 0 -> {
                if (formattedTarget != null) {
                    sendChatCommand(client, "bot skill drop_sweep " + formattedTarget);
                } else {
                    sendChatCommand(client, "bot skill drop_sweep");
                }
            }
            default -> {
            }
        }
    }

    private static String resolveQuickBotTarget(MinecraftClient client) {
        String looked = findLookedAtBotName(client);
        if (looked != null && !looked.isBlank()) {
            return looked;
        }
        if (recruitmentBotAlias != null && !recruitmentBotAlias.isBlank()) {
            return recruitmentBotAlias.trim();
        }
        return null;
    }

    private static String formatBotTarget(String botName) {
        if (botName == null || botName.isBlank()) {
            return "";
        }
        if (botName.length() >= 2 && botName.startsWith("\"") && botName.endsWith("\"")) {
            return botName;
        }
        return botName.contains(" ") ? "\"" + botName + "\"" : botName;
    }

    private static String humanizeShelterPreviewLabel(String type) {
        if (type == null || type.isBlank()) {
            return "Build";
        }
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "hovel" -> "Hovel";
            case "burrow" -> "Burrow";
            case "small_shelter" -> "Small Shelter";
            case "small_hut" -> "Small Hut";
            case "watchtower" -> "Watchtower";
            case "bridge" -> "Bridge";
            case "test_platform" -> "Platform";
            case "defensive_wall_section" -> "Wall Section";
            case "defensive_wall_corner" -> "Wall Corner";
            case "defensive_gatehouse" -> "Gatehouse";
            default -> {
                String[] parts = type.replace('_', ' ').trim().split("\\s+");
                StringBuilder builder = new StringBuilder();
                for (String part : parts) {
                    if (part.isBlank()) {
                        continue;
                    }
                    if (!builder.isEmpty()) {
                        builder.append(' ');
                    }
                    builder.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) {
                        builder.append(part.substring(1));
                    }
                }
                yield builder.isEmpty() ? "Build" : builder.toString();
            }
        };
    }

    private record PreviewPlacement(BlockPos targetPos,
                                    Box primaryBox,
                                    java.util.List<Box> secondaryBoxes) {
    }

    private static void resetPendingPreviewTransform() {
        if (pendingPlacementPreview != null) {
            pendingPlacementPreview.targetPos = null;
            pendingPlacementPreview.rotationQuarterTurns = 0;
            pendingPlacementPreview.locked = false;
            pendingPlacementPreview.primaryBox = null;
            pendingPlacementPreview.secondaryBoxes = java.util.List.of();
        }
        previewUpKeyDown = false;
        previewDownKeyDown = false;
        previewLeftKeyDown = false;
        previewRightKeyDown = false;
        pendingPreviewBox = null;
        previousPreviewBox = null;
        particleSpawnTick = 0;
        particlePauseTicks = 0;
    }

    private static boolean isProceduralShelterType(String type) {
        if (type == null) {
            return false;
        }
        return "hovel".equalsIgnoreCase(type) || "burrow".equalsIgnoreCase(type);
    }

    private static boolean hasPendingPlacementPreview() {
        return pendingPlacementPreview != null;
    }

    private static int normalizedPreviewRotationTurns() {
        return pendingPlacementPreview == null ? 0 : Math.floorMod(pendingPlacementPreview.rotationQuarterTurns, 4);
    }

    private static net.wcfcarolina13.GameAI.schematic.SchematicData getEffectivePendingPreviewSchematic() {
        if (pendingPlacementPreview == null || pendingPlacementPreview.schematicData == null) {
            return null;
        }
        return pendingPlacementPreview.schematicData.rotated(normalizedPreviewRotationTurns());
    }

    private static Box buildFarmPrimaryPreviewBox(BlockPos targetPos) {
        double minX = targetPos.getX() - (FARM_PREVIEW_SIZE / 2.0);
        double minZ = targetPos.getZ() - (FARM_PREVIEW_SIZE / 2.0);
        return new Box(minX, targetPos.getY(), minZ, minX + FARM_PREVIEW_SIZE, targetPos.getY() + 1, minZ + FARM_PREVIEW_SIZE);
    }

    private static java.util.List<Box> buildFarmSecondaryPreviewBoxes(BlockPos targetPos) {
        Box irrigationBox = new Box(
            targetPos.getX() - (FARM_IRRIGATION_SIZE / 2.0),
            targetPos.getY(),
            targetPos.getZ() - (FARM_IRRIGATION_SIZE / 2.0),
            targetPos.getX() - (FARM_IRRIGATION_SIZE / 2.0) + FARM_IRRIGATION_SIZE,
            targetPos.getY() + 1,
            targetPos.getZ() - (FARM_IRRIGATION_SIZE / 2.0) + FARM_IRRIGATION_SIZE
        );
        return java.util.List.of(irrigationBox);
    }

    private static PreviewPlacement buildPreviewPlacement(BlockPos targetPos) {
        if (pendingPlacementPreview == null || targetPos == null) {
            return null;
        }

        if (pendingPlacementPreview.kind == PlacementPreviewKind.FARM) {
            return new PreviewPlacement(targetPos, buildFarmPrimaryPreviewBox(targetPos), buildFarmSecondaryPreviewBoxes(targetPos));
        }

        net.wcfcarolina13.GameAI.schematic.SchematicData effectiveSchematic = getEffectivePendingPreviewSchematic();
        if (effectiveSchematic == null) {
            return null;
        }

        int offsetX = -effectiveSchematic.sizeX() / 2;
        int offsetZ = -effectiveSchematic.sizeZ() / 2;

        Box previewBox = new Box(
            targetPos.getX() + offsetX, targetPos.getY(), targetPos.getZ() + offsetZ,
            targetPos.getX() + offsetX + effectiveSchematic.sizeX(),
            targetPos.getY() + effectiveSchematic.sizeY(),
            targetPos.getZ() + offsetZ + effectiveSchematic.sizeZ()
        );
        return new PreviewPlacement(targetPos, previewBox, java.util.List.of());
    }

    private static PreviewPlacement resolveLivePreviewPlacement(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || pendingPlacementPreview == null) {
            return null;
        }

        HitResult hit = client.player.raycast(32.0, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos hitBlockPos = blockHit.getBlockPos();

        net.minecraft.block.BlockState hitState = client.world.getBlockState(hitBlockPos);
        boolean isReplaceable = hitState.isReplaceable()
                || hitState.isOf(net.minecraft.block.Blocks.SNOW)
                || hitState.isOf(net.minecraft.block.Blocks.SHORT_GRASS)
                || hitState.isOf(net.minecraft.block.Blocks.TALL_GRASS)
                || hitState.isOf(net.minecraft.block.Blocks.FERN);

        int baseX = hitBlockPos.getX();
        int baseY = hitBlockPos.getY();
        int baseZ = hitBlockPos.getZ();
        if (blockHit.getSide() == Direction.UP && !isReplaceable) {
            baseY += 1;
        }

        float yaw = client.player.getYaw();
        double yawRad = Math.toRadians(yaw);
        int forwardOffsetX = (int) Math.round(-Math.sin(yawRad) * 3.0);
        int forwardOffsetZ = (int) Math.round(Math.cos(yawRad) * 3.0);

        BlockPos targetPos = new BlockPos(baseX + forwardOffsetX, baseY, baseZ + forwardOffsetZ);
        if (pendingPlacementPreview.kind == PlacementPreviewKind.FARM
                || "hovel".equalsIgnoreCase(pendingPlacementPreview.type)) {
            targetPos = targetPos.withY(detectPreviewFloorBlockY(client, targetPos));
        }
        return buildPreviewPlacement(targetPos);
    }

    private static boolean isRawKeyDown(MinecraftClient client, int keyCode) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        long handle = client.getWindow().getHandle();
        return handle != 0L && GLFW.glfwGetKey(handle, keyCode) == GLFW.GLFW_PRESS;
    }

    private static boolean isPreviewShiftDown(MinecraftClient client) {
        return isRawKeyDown(client, GLFW.GLFW_KEY_LEFT_SHIFT) || isRawKeyDown(client, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static void nudgeLockedPreview(Direction direction) {
        if (pendingPlacementPreview == null || pendingPlacementPreview.targetPos == null
                || direction == null || !direction.getAxis().isHorizontal()) {
            return;
        }
        pendingPlacementPreview.targetPos = pendingPlacementPreview.targetPos.offset(direction);
    }

    private static void moveLockedPreviewVertical(int deltaY) {
        if (pendingPlacementPreview == null || pendingPlacementPreview.targetPos == null || deltaY == 0
                || !pendingPlacementPreview.allowVerticalAdjust) {
            return;
        }
        pendingPlacementPreview.targetPos = pendingPlacementPreview.targetPos.add(0, deltaY, 0);
    }

    private static void rotateLockedPreview(int deltaTurns) {
        if (pendingPlacementPreview == null || deltaTurns == 0 || !pendingPlacementPreview.allowRotation) {
            return;
        }
        pendingPlacementPreview.rotationQuarterTurns =
            Math.floorMod(pendingPlacementPreview.rotationQuarterTurns + deltaTurns, 4);
    }

    private static void togglePendingPreviewLock(MinecraftClient client) {
        if (client == null || client.player == null || !hasPendingPlacementPreview()) {
            return;
        }
        if (!pendingPlacementPreview.locked) {
            PreviewPlacement placement = resolveLivePreviewPlacement(client);
            if (placement == null) {
                client.player.sendMessage(Text.literal("Look at a block first to lock the preview."), true);
                return;
            }
            pendingPlacementPreview.targetPos = placement.targetPos();
            pendingPlacementPreview.primaryBox = placement.primaryBox();
            pendingPlacementPreview.secondaryBoxes = placement.secondaryBoxes();
            pendingPreviewBox = placement.primaryBox();
            pendingPlacementPreview.locked = true;
            previousPreviewBox = null;
            particleSpawnTick = 0;
            particlePauseTicks = 0;
            client.player.sendMessage(Text.literal("Preview locked. Use arrows to adjust it."), true);
            return;
        }

        pendingPlacementPreview.locked = false;
        previewUpKeyDown = false;
        previewDownKeyDown = false;
        previewLeftKeyDown = false;
        previewRightKeyDown = false;
        previousPreviewBox = null;
        particleSpawnTick = 0;
        particlePauseTicks = 0;
        client.player.sendMessage(Text.literal("Preview unlocked. It will follow your look again."), true);
    }

    private static void handlePendingPreviewControls(MinecraftClient client) {
        if (client == null || client.player == null || client.currentScreen != null || !hasPendingPlacementPreview()) {
            previewUpKeyDown = false;
            previewDownKeyDown = false;
            previewLeftKeyDown = false;
            previewRightKeyDown = false;
            return;
        }

        if (KEY_LOCK_PREVIEW != null && KEY_LOCK_PREVIEW.wasPressed()) {
            togglePendingPreviewLock(client);
        }

        if (!pendingPlacementPreview.locked || pendingPlacementPreview.targetPos == null) {
            previewUpKeyDown = false;
            previewDownKeyDown = false;
            previewLeftKeyDown = false;
            previewRightKeyDown = false;
            return;
        }

        boolean shiftDown = isPreviewShiftDown(client);
        Direction facing = client.player.getHorizontalFacing();
        boolean upDown = isRawKeyDown(client, GLFW.GLFW_KEY_UP);
        boolean downDown = isRawKeyDown(client, GLFW.GLFW_KEY_DOWN);
        boolean leftDown = isRawKeyDown(client, GLFW.GLFW_KEY_LEFT);
        boolean rightDown = isRawKeyDown(client, GLFW.GLFW_KEY_RIGHT);

        if (upDown && !previewUpKeyDown) {
            if (shiftDown && pendingPlacementPreview.allowVerticalAdjust) {
                moveLockedPreviewVertical(1);
            } else {
                nudgeLockedPreview(facing);
            }
        }
        if (downDown && !previewDownKeyDown) {
            if (shiftDown && pendingPlacementPreview.allowVerticalAdjust) {
                moveLockedPreviewVertical(-1);
            } else {
                nudgeLockedPreview(facing.getOpposite());
            }
        }
        if (leftDown && !previewLeftKeyDown) {
            if (shiftDown && pendingPlacementPreview.allowRotation) {
                rotateLockedPreview(-1);
            } else {
                nudgeLockedPreview(facing.rotateYCounterclockwise());
            }
        }
        if (rightDown && !previewRightKeyDown) {
            if (shiftDown && pendingPlacementPreview.allowRotation) {
                rotateLockedPreview(1);
            } else {
                nudgeLockedPreview(facing.rotateYClockwise());
            }
        }

        previewUpKeyDown = upDown;
        previewDownKeyDown = downDown;
        previewLeftKeyDown = leftDown;
        previewRightKeyDown = rightDown;
    }

    private static void renderPendingShelterHint(DrawContext context) {
        if (!hasPendingPlacementPreview()) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        String goToKey = keyNameOrNull(KEY_GO_TO_LOOK);
        if (goToKey == null) {
            goToKey = "-";
        }
        String stopKey = keyNameOrNull(KEY_STOP_LOOK);
        if (stopKey == null) {
            stopKey = "\\\\";
        }
        String lockKey = keyNameOrNull(KEY_LOCK_PREVIEW);
        if (lockKey == null) {
            lockKey = ";";
        }
        String label = pendingPlacementPreview.label != null && !pendingPlacementPreview.label.isBlank()
                ? pendingPlacementPreview.label
                : "Build";

        int rotationDegrees = normalizedPreviewRotationTurns() * 90;
        String lockState = pendingPlacementPreview.locked ? "Locked" : "Live";
        boolean showRotation = pendingPlacementPreview.allowRotation && rotationDegrees != 0;
        String line1 = showRotation
            ? "Previewing: " + label + " [" + lockState + " • " + rotationDegrees + "°]"
            : "Previewing: " + label + " [" + lockState + "]";
        String line2 = "[" + lockKey + "] " + (pendingPlacementPreview.locked ? "unlock" : "lock")
            + " • [" + goToKey + "] confirm • [" + stopKey + "] cancel";
        String line3;
        if (pendingPlacementPreview.kind == PlacementPreviewKind.FARM) {
            line3 = pendingPlacementPreview.locked
                ? "Arrows move 1 block"
                : "Look to place it, then walk around and press [" + lockKey + "] to edit in 3D";
        } else {
            line3 = pendingPlacementPreview.locked
                ? "Arrows move 1 block • Shift+Up/Down height • Shift+Left/Right rotate"
                : "Look to place it, then walk around and press [" + lockKey + "] to edit in 3D";
        }

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int w3 = client.textRenderer.getWidth(line3);
        int maxW = Math.max(w1, Math.max(w2, w3));
        int x = 12;
        int lineStep = client.textRenderer.fontHeight + 2;
        int boxH = client.textRenderer.fontHeight * 3 + 10;
        int y = reserveTopTipY(TopTipLane.LEFT, boxH + 7);

        context.fill(x - 6, y - 4, x + maxW + 6, y + boxH, 0xAA101010);
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4A4A4A);
        context.fill(x - 7, y + boxH, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x - 7, y - 5, x - 6, y + boxH + 1, 0xFF4A4A4A);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxH + 1, 0xFF4A4A4A);

        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + lineStep, 0xFFB8A76A);
        context.drawTextWithShadow(client.textRenderer, line3, x, y + lineStep * 2, 0xFFB8A76A);
    }

    private static void renderDirectionalMiningHint(DrawContext context) {
        if (!hasPendingDirectionalMining()) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        String action = pendingDirectionalActionLabel != null ? pendingDirectionalActionLabel : "task";
        String goToKey = keyNameOrNull(KEY_GO_TO_LOOK);
        if (goToKey == null) {
            goToKey = "-";
        }
        String stopKey = keyNameOrNull(KEY_STOP_LOOK);
        if (stopKey == null) {
            stopKey = "\\\\";
        }

        String line = "Select direction to " + action + ". Press [" + goToKey + "] to confirm or [" + stopKey + "] to cancel.";
        int w = client.textRenderer.getWidth(line);
        int x = (context.getScaledWindowWidth() - w) / 2;
        int y = reserveTopTipY(TopTipLane.CENTER, client.textRenderer.fontHeight + 9);

        context.fill(x - 6, y - 4, x + w + 6, y + client.textRenderer.fontHeight + 4, 0xAA101010);
        context.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFE6D7A3);
    }

    private static void tickDirectionalMiningPreview(MinecraftClient client) {
        if (!hasPendingDirectionalMining() || client == null || client.player == null || client.world == null || client.currentScreen != null) {
            return;
        }
        directionalPreviewTick++;
        if (directionalPreviewTick < 4) {
            return;
        }
        directionalPreviewTick = 0;

        HitResult hit = client.player.raycast(20.0, 1.0F, false);
        Vec3d point;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            point = hit.getPos();
        } else {
            Vec3d eye = client.player.getEyePos();
            Vec3d look = client.player.getRotationVec(1.0F);
            point = eye.add(look.multiply(6.0));
        }
        DustParticleEffect marker = new DustParticleEffect(0x4CB7FF, 1.1f);
        client.particleManager.addParticle(marker, point.x, point.y + 0.05, point.z, 0.0, 0.0, 0.0);
    }

    private static void tickLookedAtBotStatusPeek(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || client.getNetworkHandler() == null || client.currentScreen != null) {
            lookedAtBotUuid = null;
            return;
        }
        PlayerEntity looked = findLookedAtBotEntity(client);
        if (looked == null || looked == client.player) {
            lookedAtBotUuid = null;
            return;
        }
        java.util.UUID uuid = looked.getUuid();
        if (uuid == null) {
            lookedAtBotUuid = null;
            return;
        }
        lookedAtBotUuid = uuid;

        long now = System.currentTimeMillis();
        boolean botChanged = lookedAtBotStatus == null
                || lookedAtBotStatus.botUuid() == null
                || !uuid.toString().equalsIgnoreCase(lookedAtBotStatus.botUuid());
        if (botChanged || now - lookedAtBotPeekLastRequestMs >= 500L) {
            lookedAtBotPeekLastRequestMs = now;
            ClientPlayNetworking.send(new BotTaskPeekRequestPayload(uuid.toString()));
        }
    }

    private static void renderLookedAtBotStatusHint(DrawContext context) {
        if (lookedAtBotStatus == null || lookedAtBotUuid == null) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (System.currentTimeMillis() - lookedAtBotStatusAtMs > 1500L) {
            return;
        }
        if (lookedAtBotStatus.botUuid() == null
                || !lookedAtBotUuid.toString().equalsIgnoreCase(lookedAtBotStatus.botUuid())) {
            return;
        }

        String alias = (lookedAtBotStatus.botAlias() == null || lookedAtBotStatus.botAlias().isBlank())
                ? "Bot" : EntityUtil.safeDisplayName(lookedAtBotStatus.botAlias());
        String stopKey = keyNameOrNull(KEY_STOP_LOOK);
        if (stopKey == null) {
            stopKey = "\\\\";
        }
        String resumeKey = keyNameOrNull(KEY_RESUME);
        if (resumeKey == null) {
            resumeKey = "Resume";
        }

        String line;
        boolean offerStandDown = false;
        if (lookedAtBotStatus.paused()) {
            line = alias + " is paused. Press [" + resumeKey + "] to resume or [" + stopKey + "] to stop.";
        } else if (lookedAtBotStatus.returningHome()) {
            line = alias + " is returning home. Press [" + stopKey + "] to stop.";
        } else if (lookedAtBotStatus.active()) {
            String task = lookedAtBotStatus.taskLabel() != null && !lookedAtBotStatus.taskLabel().isBlank()
                    ? lookedAtBotStatus.taskLabel()
                    : "working";
            line = alias + " is " + task + ". Press [" + stopKey + "] to stop.";
            offerStandDown = true;
        } else {
            String mode = lookedAtBotStatus.modeName();
            if (mode != null && !mode.isBlank()) {
                String modeLabel = switch (mode) {
                    case "GUARD" -> "guarding";
                    case "PATROL" -> "patrolling";
                    case "FOLLOW" -> "following";
                    case "STAY" -> "staying";
                    default -> mode.toLowerCase(java.util.Locale.ROOT);
                };
                line = alias + " is " + modeLabel + ". Press [" + stopKey + "] to stop.";
                offerStandDown = "FOLLOW".equals(mode);
            } else {
                return;
            }
        }
        // Second line: stand-down hint, only when contextually useful (following or active task).
        // Hold [stopKey] opens the overlay; slot 1 is Stand Down.
        String secondLine = offerStandDown
                ? "Hold [" + stopKey + "] for Stand Down (60s pause + auto-resume follow)."
                : null;

        int fh = client.textRenderer.fontHeight;
        int w1 = client.textRenderer.getWidth(line);
        int w2 = secondLine != null ? client.textRenderer.getWidth(secondLine) : 0;
        int boxW = Math.max(w1, w2);
        int rowCount = secondLine != null ? 2 : 1;
        int reserveH = fh * rowCount + (rowCount > 1 ? 2 : 0) + 9;
        int y = reserveTopTipY(TopTipLane.CENTER, reserveH);
        int boxLeft = (context.getScaledWindowWidth() - boxW) / 2 - 6;
        int boxRight = boxLeft + boxW + 12;
        int boxBottom = y + fh * rowCount + (rowCount > 1 ? 2 : 0) + 4;
        context.fill(boxLeft, y - 4, boxRight, boxBottom, 0xAA101010);
        context.drawTextWithShadow(client.textRenderer, line,
                (context.getScaledWindowWidth() - w1) / 2, y, 0xFFE6D7A3);
        if (secondLine != null) {
            context.drawTextWithShadow(client.textRenderer, secondLine,
                    (context.getScaledWindowWidth() - w2) / 2, y + fh + 2, 0xFFB8A878);
        }
    }

    private static void renderLookedAtBotInventoryHint(DrawContext context) {
        if (!isGameplayTipsEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        PlayerEntity looked = findLookedAtBotEntity(client);
        if (looked == null || looked == client.player || looked.isRemoved() || !looked.isAlive()) {
            return;
        }
        if (lookedAtBotStatus == null || lookedAtBotStatus.botUuid() == null) {
            return;
        }
        if (System.currentTimeMillis() - lookedAtBotStatusAtMs > 1800L) {
            return;
        }
        if (!looked.getUuidAsString().equalsIgnoreCase(lookedAtBotStatus.botUuid())) {
            return;
        }

        String alias = looked.getName() != null ? EntityUtil.safeDisplayName(looked.getName().getString()) : "Bot";
        if (alias == null || alias.isBlank()) {
            alias = "Bot";
        }
        String guideKey = getGuideHotkeyDisplayName();
        String keyLabel = "]".equals(guideKey) ? "Right Bracket (])" : guideKey;
        String line = "Looking at " + alias + ". Right-click to open menu • Press " + keyLabel + " for Guide";

        int w = client.textRenderer.getWidth(line);
        int x = (context.getScaledWindowWidth() - w) / 2;
        int y = reserveTopTipY(TopTipLane.CENTER, client.textRenderer.fontHeight + 9);

        context.fill(x - 6, y - 4, x + w + 6, y + client.textRenderer.fontHeight + 4, 0xAA101010);
        context.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFE6D7A3);
    }
    
    /**
     * Called every client tick to render the schematic preview overlay.
     */
    private static void renderSchematicPreviewTick(MinecraftClient client) {
        if (!hasPendingPlacementPreview()) {
            return;
        }
        if (client == null || client.player == null || client.world == null || client.currentScreen != null) {
            return;
        }
        // Actual rendering will be done using debug renderer or a custom approach
        // For now, just ensure the preview state is tracked
    }

    private static void handleGoToLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        if (resumeDecisionActive) {
            sendResumeDecision(client, false);
            return;
        }
        if (hasPendingDirectionalMining()) {
            sendChatCommand(client, pendingDirectionalCommand);
            clearPendingDirectionalMining();
            return;
        }
        if (pendingPlacementPreview != null) {
            PreviewPlacement placement = pendingPlacementPreview.targetPos != null
                    ? buildPreviewPlacement(pendingPlacementPreview.targetPos)
                    : resolveLivePreviewPlacement(client);
            if (placement == null) {
                client.player.sendMessage(Text.literal("Look at a block first to place this build preview."), true);
                return;
            }

            pendingPlacementPreview.targetPos = placement.targetPos();
            pendingPlacementPreview.primaryBox = placement.primaryBox();
            pendingPlacementPreview.secondaryBoxes = placement.secondaryBoxes();
            String formattedTarget = pendingPlacementPreview.botTarget != null && !pendingPlacementPreview.botTarget.isBlank()
                    ? formatBotTarget(pendingPlacementPreview.botTarget)
                    : null;
            StringBuilder cmd = new StringBuilder("bot skill ");
            if (pendingPlacementPreview.kind == PlacementPreviewKind.FARM) {
                cmd.append("farm");
                if (formattedTarget != null && !formattedTarget.isBlank()) {
                    cmd.append(' ').append(formattedTarget);
                }
                cmd.append(" targetX=").append(placement.targetPos().getX())
                    .append(" targetY=").append(placement.targetPos().getY())
                    .append(" targetZ=").append(placement.targetPos().getZ())
                    .append(" manual=true");
            } else if (isProceduralShelterType(pendingPlacementPreview.type)) {
                cmd.append("shelter ").append(pendingPlacementPreview.type);
                if (formattedTarget != null && !formattedTarget.isBlank()) {
                    cmd.append(' ').append(formattedTarget);
                }
                if ("hovel".equalsIgnoreCase(pendingPlacementPreview.type)) {
                    cmd.append(" radius=4");
                }
                cmd.append(" targetX=").append(placement.targetPos().getX())
                    .append(" targetY=").append(placement.targetPos().getY())
                    .append(" targetZ=").append(placement.targetPos().getZ());
            } else {
                cmd.append("build ").append(pendingPlacementPreview.type);
                if (formattedTarget != null && !formattedTarget.isBlank()) {
                    cmd.append(' ').append(formattedTarget);
                }
                int turns = normalizedPreviewRotationTurns();
                if (turns != 0) {
                    cmd.append(" rotation=").append(turns);
                }
                cmd.append(" targetX=").append(placement.targetPos().getX())
                    .append(" targetY=").append(placement.targetPos().getY())
                    .append(" targetZ=").append(placement.targetPos().getZ());
            }

            sendChatCommand(client, cmd.toString());
            clearPendingShelter();
        } else {
            // Normal go_to_look behavior
            sendChatCommand(client, "bot go_to_look");
        }
    }

    private static void handleSleepLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        String target = resolveQuickBotTarget(client);
        if (target != null && !target.isBlank()) {
            sendChatCommand(client, "bot sleep " + formatBotTarget(target));
        } else {
            sendChatCommand(client, "bot sleep");
        }
    }

    private static void handleReturnHomeLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        String target = resolveQuickBotTarget(client);
        if (target != null && !target.isBlank()) {
            sendChatCommand(client, "bot return " + formatBotTarget(target));
        } else {
            sendChatCommand(client, "bot return");
        }
    }

    private static void handleFollowToggleLookedAt(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        if (resumeDecisionActive) {
            sendResumeDecision(client, true);
            return;
        }
        String name = findLookedAtBotName(client);
        if (name == null || name.isBlank()) {
            if (isGameplayTipsEnabled()) {
                client.player.sendMessage(Text.literal("Look at a bot to toggle follow (within 16 blocks)."), true);
            }
            return;
        }
        sendChatCommand(client, "bot follow toggle " + formatBotTarget(name));
    }

    private static void sendChatCommand(MinecraftClient client, String command) {
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        client.getNetworkHandler().sendChatCommand(raw);
    }

    private static void handleResumeKey(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        if (!resumeDecisionActive) {
            return;
        }
        sendResumeDecision(client, true);
    }

    private static void handleStandDownLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        CompanionHotkeyOverlayHud.hide();
        String name = findLookedAtBotName(client);
        if (name == null || name.isBlank()) {
            if (isGameplayTipsEnabled()) {
                client.player.sendMessage(Text.literal("Look at a bot to stand it down (within 16 blocks)."), true);
            }
            return;
        }
        sendChatCommand(client, "bot standdown " + formatBotTarget(name));
    }

    private static void handleStopLook(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        CompanionHotkeyOverlayHud.hide();
        if (resumeDecisionActive) {
            sendResumeDecision(client, false);
            return;
        }

        if (hasPendingDirectionalMining()) {
            clearPendingDirectionalMining();
            client.player.sendMessage(Text.literal("Canceled directional mining placement."), true);
            return;
        }
        
        if (pendingPlacementPreview != null) {
            String label = pendingPlacementPreview.kind == PlacementPreviewKind.FARM
                ? "farm placement"
                : "construction placement";
            clearPendingShelter();
            client.player.sendMessage(Text.literal("Canceled " + label + "."), true);
            return;
        }
        
        String name = findLookedAtBotName(client);
        if (name == null || name.isBlank()) {
            if (isGameplayTipsEnabled()) {
                client.player.sendMessage(Text.literal("Look at a bot to stop it (within 16 blocks)."), true);
            }
            return;
        }
        sendChatCommand(client, "bot stop " + formatBotTarget(name));
    }

    private static PlayerEntity findLookedAtBotEntity(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        final double maxDistance = 16.0;
        net.minecraft.util.math.Vec3d eyePos = client.player.getEyePos();
        net.minecraft.util.math.Vec3d lookVec = client.player.getRotationVec(1.0F);
        net.minecraft.util.math.Vec3d rayEnd = eyePos.add(lookVec.multiply(maxDistance));

        PlayerEntity foundBot = null;
        double closestDist = maxDistance;

        for (net.minecraft.entity.Entity entity : client.world.getEntities()) {
            if (!(entity instanceof PlayerEntity target) || target == client.player) {
                continue;
            }
            if (target.squaredDistanceTo(client.player) > maxDistance * maxDistance) {
                continue;
            }
            net.minecraft.util.math.Box entityBox = target.getBoundingBox().expand(0.3);
            java.util.Optional<net.minecraft.util.math.Vec3d> intersect = entityBox.raycast(eyePos, rayEnd);
            if (intersect.isPresent()) {
                double dist = eyePos.squaredDistanceTo(intersect.get());
                if (dist < closestDist * closestDist) {
                    closestDist = Math.sqrt(dist);
                    foundBot = target;
                }
            }
        }

        return foundBot;
    }

    private static String findLookedAtBotName(MinecraftClient client) {
        PlayerEntity foundBot = findLookedAtBotEntity(client);
        if (foundBot == null) {
            return null;
        }
        String name = foundBot.getName().getString();
        return name != null && !name.isBlank() ? name : null;
    }

    private static String resolveDecisionBotName(MinecraftClient client) {
        if (resumeDecisionBotName != null && !resumeDecisionBotName.isBlank()) {
            return resumeDecisionBotName;
        }
        return findLookedAtBotName(client);
    }

    private static void sendResumeDecision(MinecraftClient client, boolean resume) {
        String botName = resolveDecisionBotName(client);
        String command;
        if (botName != null && !botName.isBlank()) {
            String target = formatBotTarget(botName);
            command = resume ? ("bot resume " + target) : ("bot stop " + target);
        } else {
            command = resume ? "bot resume" : "bot stop";
        }
        sendChatCommand(client, command);
        resumeDecisionActive = false;
        resumeDecisionBotName = null;
    }

    private static void renderResumeDecisionHint(DrawContext context) {
        if (!resumeDecisionActive) {
            return;
        }
        if (!isGameplayTipsEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        String target = resumeDecisionBotName != null && !resumeDecisionBotName.isBlank()
                ? resumeDecisionBotName
                : "bot";
        String resumeKey = keyNameOrNull(KEY_FOLLOW_TOGGLE_LOOK);
        if (resumeKey == null) {
            resumeKey = keyNameOrNull(KEY_RESUME);
        }
        if (resumeKey == null) {
            resumeKey = "?";
        }
        String goToKey = keyNameOrNull(KEY_GO_TO_LOOK);
        if (goToKey == null) {
            goToKey = "?";
        }
        String stopKey = keyNameOrNull(KEY_STOP_LOOK);
        if (stopKey == null) {
            stopKey = "?";
        }

        String line1 = target + " is paused and waiting for your decision.";
        String line2 = resumeKey + " = Resume last task";
        String line3 = goToKey + " or " + stopKey + " = Stop and clear it";
        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int w3 = client.textRenderer.getWidth(line3);
        int maxWidth = Math.max(w1, Math.max(w2, w3));
        int x = (context.getScaledWindowWidth() - maxWidth) / 2;
        int y = reserveTopTipY(TopTipLane.CENTER, client.textRenderer.fontHeight * 3 + 13);
        context.fill(x - 6, y - 4, x + maxWidth + 6, y + client.textRenderer.fontHeight * 3 + 8, 0xAA101010);
        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFE6D7A3);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFFB8A76A);
        context.drawTextWithShadow(client.textRenderer, line3, x, y + (client.textRenderer.fontHeight + 2) * 2, 0xFFB8A76A);
    }

    // ===== Leash Button Feature =====

    /**
     * Update the visibility state of the leash button and mounted leash hint.
     * Called every tick to check if we're looking at a bot that has leashed animals,
     * or if the player is mounted near a mounted bot.
     */
    private static void updateLeashButtonVisibility(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            leashButtonVisible = false;
            leashButtonBotName = null;
            mountedLeashHintVisible = false;
            mountedLeashBotName = null;
            mountedLeashBotOnBoat = false;
            return;
        }

        // Reset state each tick.
        leashButtonVisible = false;
        leashButtonBotName = null;
        mountedLeashHintVisible = false;
        mountedLeashBotName = null;
        mountedLeashBotOnBoat = false;

        // Case 1: Looking at a bot with leashed animals.
        String lookAtBotName = findLookedAtBotName(client);
        PlayerEntity lookAtBotEntity = null;
        if (lookAtBotName != null && !lookAtBotName.isBlank()) {
            for (Entity entity : client.world.getEntities()) {
                if (entity instanceof PlayerEntity player && player != client.player) {
                    if (lookAtBotName.equals(player.getName().getString())) {
                        lookAtBotEntity = player;
                        break;
                    }
                }
            }
            if (lookAtBotEntity != null) {
                for (Entity entity : client.world.getEntities()) {
                    if (entity instanceof MobEntity mob) {
                        if (!mob.isAlive()) continue;
                        if (!mob.isLeashed()) continue;
                        if (mob.squaredDistanceTo(lookAtBotEntity) > 256.0) continue;
                        Entity holder = mob.getLeashHolder();
                        if (holder != null && holder == lookAtBotEntity) {
                            leashButtonVisible = true;
                            leashButtonBotName = lookAtBotName;
                            return; // Prefer this hint when we're looking at a bot with leashed animals.
                        }
                    }
                }
            }
        }

        // Case 2: Looking at a mounted bot. Works whether the player is
        // mounted or on foot — covers the edge case where a reload leaves
        // only the bot's horse remaining and the player wants to reclaim it.
        if (lookAtBotEntity != null && lookAtBotEntity.hasVehicle()) {
            mountedLeashHintVisible = true;
            mountedLeashBotName = lookAtBotName;
            mountedLeashBotOnBoat = lookAtBotEntity.getVehicle() instanceof AbstractBoatEntity;
            return;
        }

        // Case 3: Player is mounted and there's a nearby mounted bot (classic
        // ride-along case — commander and bot riding side-by-side).
        if (client.player.hasVehicle()) {
            PlayerEntity nearbyBot = findNearbyMountedBot(client);
            if (nearbyBot != null) {
                String name = nearbyBot.getName().getString();
                if (name != null && !name.isBlank()) {
                    mountedLeashHintVisible = true;
                    mountedLeashBotName = name;
                    mountedLeashBotOnBoat = nearbyBot.getVehicle() instanceof AbstractBoatEntity;
                }
            }
        }
    }

    /**
     * Handle the leash keybind press. Drives the HUD hint action directly:
     * - If the "leashed animals" hint is showing: send /bot leash to tie them to a fence.
     * - If the "mounted bot" hint is showing (look-at or ride-along): tell the bot to
     *   dismount and tether the horse.
     * - Otherwise: show a guidance message.
     */
    private static void handleLeashKey(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return;
        }

        // Case 1: Looking at a bot with leashed animals.
        if (leashButtonVisible && leashButtonBotName != null) {
            sendChatCommand(client, "bot leash " + leashButtonBotName);
            return;
        }

        // Case 2/3: There's a mounted bot the player wants dismounted + tethered.
        // updateLeashButtonVisibility already distinguished "looking at mounted
        // bot" vs "riding alongside a mounted bot" and chose mountedLeashBotName
        // accordingly, so we can act on the cached name directly without
        // re-scanning.
        if (mountedLeashHintVisible && mountedLeashBotName != null) {
            String name = mountedLeashBotName;
            // /bot stop cancels follow (prevents the bot from immediately
            // re-mounting via RideSync), then /bot leash runs the secure
            // fallback chain (lead -> fence -> hold).
            sendChatCommand(client, "bot stop " + name);
            sendChatCommand(client, "bot leash " + name);
            if (isGameplayTipsEnabled()) {
                client.player.sendMessage(Text.literal("Telling " + name + " to dismount and tether."), true);
            }
            return;
        }

        // No hint active — surface guidance.
        if (isGameplayTipsEnabled()) {
            client.player.sendMessage(Text.literal("Look at a bot with leashed animals or a mounted bot, or be mounted near a mounted bot."), true);
        }
    }

    /**
     * Find a nearby bot (player entity that isn't the client player) that is mounted.
     * Returns the closest mounted bot entity, or null if none found.
     */
    private static PlayerEntity findNearbyMountedBot(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        final double maxDistance = 32.0;
        PlayerEntity closest = null;
        double closestDistSq = maxDistance * maxDistance;

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof PlayerEntity target) || target == client.player) {
                continue;
            }
            if (!target.hasVehicle()) {
                continue;
            }
            double distSq = target.squaredDistanceTo(client.player);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = target;
            }
        }

        return closest;
    }

    /**
     * Render the leash button HUD element when visible.
     * Shows at the top of the screen when looking at a bot with leashed animals,
     * or when mounted near a mounted bot.
     */
    private static void renderLeashButton(DrawContext context) {
        if (!isGameplayTipsEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        String line1 = null;
        String line2 = null;

        if (leashButtonVisible && leashButtonBotName != null) {
            line1 = "🐴 " + leashButtonBotName + " is leading animals";
            line2 = "Press ['] to tie to nearby fence";
        } else if (mountedLeashHintVisible && mountedLeashBotName != null) {
            // Wording works whether player is on foot or mounted: bot is on
            // a mount, and the keybind tells it to dismount (and tether the
            // horse, if applicable — boats don't need securing).
            if (mountedLeashBotOnBoat) {
                line1 = "🛶 " + mountedLeashBotName + " is riding a boat";
                line2 = "Press ['] to dismount";
            } else {
                line1 = "🐴 " + mountedLeashBotName + " is riding a horse";
                line2 = "Press ['] to dismount & tether";
            }
        }

        if (line1 == null || line2 == null) {
            return;
        }

        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int x = (context.getScaledWindowWidth() - maxW) / 2;
        int boxHeight = client.textRenderer.fontHeight * 2 + 6;
        // Participate in the top-tip lane reservation system so the leash
        // HUD stacks cleanly below other CENTER hints ("Jake is following",
        // "Looking at Jake", etc.) instead of overlapping them at y=10.
        int y = reserveTopTipY(TopTipLane.CENTER, boxHeight + 12);

        // Draw a semi-transparent dark background
        context.fill(x - 6, y - 4, x + maxW + 6, y + boxHeight, 0xAA2a4a2a);
        // Border for visibility
        context.fill(x - 7, y - 5, x + maxW + 7, y - 4, 0xFF4a8a4a);
        context.fill(x - 7, y + boxHeight, x + maxW + 7, y + boxHeight + 1, 0xFF4a8a4a);
        context.fill(x - 7, y - 5, x - 6, y + boxHeight + 1, 0xFF4a8a4a);
        context.fill(x + maxW + 6, y - 5, x + maxW + 7, y + boxHeight + 1, 0xFF4a8a4a);

        // Draw the text
        context.drawTextWithShadow(client.textRenderer, line1, x, y, 0xFFB8E6B8);
        context.drawTextWithShadow(client.textRenderer, line2, x, y + client.textRenderer.fontHeight + 2, 0xFF90C890);
    }

    // Store the preview box to be rendered
    private static Box pendingPreviewBox = null;
    private static Box previousPreviewBox = null;
    private static float previousPlayerYaw = 0;
    private static float previousPlayerPitch = 0;
    private static int particleSpawnTick = 0;
    private static int particlePauseTicks = 0; // Pause spawning when preview moves to let old particles fade

    private static int detectPreviewFloorBlockY(MinecraftClient client, BlockPos center) {
        if (client == null || client.world == null || center == null) {
            return center != null ? center.getY() - 1 : 0;
        }
        int start = center.getY();
        for (int y = start; y >= start - 6; y--) {
            BlockPos pos = new BlockPos(center.getX(), y, center.getZ());
            if (!client.world.isChunkLoaded(pos)) {
                continue;
            }
            net.minecraft.block.BlockState state = client.world.getBlockState(pos);
            if (!client.world.getFluidState(pos).isEmpty()) {
                continue;
            }
            if (!state.getCollisionShape(client.world, pos).isEmpty()) {
                return y;
            }
        }
        return start - 1;
    }
    
    /**
     * Updates the schematic preview box every client tick based on player's look direction.
     */
    private static void updateSchematicPreviewBox(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || pendingPlacementPreview == null) {
            pendingPreviewBox = null;
            return;
        }
        
        // Don't show preview if a screen is open
        if (client.currentScreen != null) {
            pendingPreviewBox = null;
            return;
        }

        PreviewPlacement placement = pendingPlacementPreview.locked && pendingPlacementPreview.targetPos != null
                ? buildPreviewPlacement(pendingPlacementPreview.targetPos)
                : resolveLivePreviewPlacement(client);
        if (placement == null) {
            pendingPreviewBox = null;
            if (!pendingPlacementPreview.locked) {
                pendingPlacementPreview.targetPos = null;
            }
            pendingPlacementPreview.primaryBox = null;
            pendingPlacementPreview.secondaryBoxes = java.util.List.of();
            return;
        }

        pendingPlacementPreview.targetPos = placement.targetPos();
        pendingPlacementPreview.primaryBox = placement.primaryBox();
        pendingPlacementPreview.secondaryBoxes = placement.secondaryBoxes();
        pendingPreviewBox = placement.primaryBox();
    }
    
    /**
     * Renders the schematic preview box using particles along the edges.
     * Spawns green particles to show where the structure will be placed.
     */
    private static void renderSchematicPreview(DrawContext context) {
        if (pendingPreviewBox == null) {
            previousPreviewBox = null;
            particlePauseTicks = 0;
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        
        // Check if player view direction changed significantly (more than 2 degrees)
        float yawDiff = Math.abs(client.player.getYaw() - previousPlayerYaw);
        float pitchDiff = Math.abs(client.player.getPitch() - previousPlayerPitch);
        
        if ((pendingPlacementPreview == null || !pendingPlacementPreview.locked) && (yawDiff > 2.0f || pitchDiff > 2.0f)) {
            // View direction changed - clear preview and pause spawning to let old particles fade
            previousPreviewBox = null;
            previousPlayerYaw = client.player.getYaw();
            previousPlayerPitch = client.player.getPitch();
            particleSpawnTick = 0;
            particlePauseTicks = 10; // Pause 10 ticks (0.5 seconds) to let old particles fade
            return;
        }
        
        // If we're in pause mode after a view change, count down and skip spawning
        if (particlePauseTicks > 0) {
            particlePauseTicks--;
            return;
        }
        
        // Check if the preview box has moved (any movement at all)
        if (previousPreviewBox != null) {
            double dx = Math.abs(pendingPreviewBox.minX - previousPreviewBox.minX);
            double dy = Math.abs(pendingPreviewBox.minY - previousPreviewBox.minY);
            double dz = Math.abs(pendingPreviewBox.minZ - previousPreviewBox.minZ);
            
            if (dx > 0.1 || dy > 0.1 || dz > 0.1) {
                // Preview moved - clear it and pause spawning to let old particles fade
                previousPreviewBox = null;
                particleSpawnTick = 0;
                particlePauseTicks = 6; // Pause 6 ticks (0.3 seconds)
                return;
            }
        }
        
        // Update previous box and angles for next frame
        previousPreviewBox = pendingPreviewBox;
        previousPlayerYaw = client.player.getYaw();
        previousPlayerPitch = client.player.getPitch();
        
        // Only spawn particles every 3 ticks for balanced density
        particleSpawnTick++;
        if (particleSpawnTick < 3) {
            return;
        }
        particleSpawnTick = 0;
        
        renderPreviewBoxParticles(client, pendingPreviewBox, new DustParticleEffect(0x33CC4D, 1.5f));
        if (pendingPlacementPreview != null) {
            for (Box secondaryBox : pendingPlacementPreview.secondaryBoxes) {
                renderPreviewBoxParticles(client, secondaryBox, new DustParticleEffect(0x3399FF, 1.3f));
            }
        }
    }

    private static void renderPreviewBoxParticles(MinecraftClient client, Box box, DustParticleEffect particle) {
        if (client == null || box == null || particle == null) {
            return;
        }
        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;
        double faceSpacing = 1.0;

        spawnParticlesOnFace(client, particle, minX, minY, minZ, maxX, minY, maxZ, faceSpacing, true);
        spawnParticlesOnFace(client, particle, minX, maxY, minZ, maxX, maxY, maxZ, faceSpacing, true);
        spawnParticlesOnFace(client, particle, minX, minY, minZ, maxX, maxY, minZ, faceSpacing, false);
        spawnParticlesOnFace(client, particle, minX, minY, maxZ, maxX, maxY, maxZ, faceSpacing, false);
        spawnParticlesOnFaceZ(client, particle, minX, minY, minZ, minX, maxY, maxZ, faceSpacing);
        spawnParticlesOnFaceZ(client, particle, maxX, minY, minZ, maxX, maxY, maxZ, faceSpacing);
    }
    
    /**
     * Spawns particles across a horizontal or XY vertical face.
     */
    private static void spawnParticlesOnFace(MinecraftClient client, DustParticleEffect particle,
                                              double x1, double y1, double z1,
                                              double x2, double y2, double z2,
                                              double spacing, boolean horizontal) {
        int stepsX = (int) Math.ceil(Math.abs(x2 - x1) / spacing);
        int stepsY = horizontal ? 0 : (int) Math.ceil(Math.abs(y2 - y1) / spacing);
        int stepsZ = horizontal ? (int) Math.ceil(Math.abs(z2 - z1) / spacing) : 0;
        
        if (horizontal) {
            // Horizontal face (floor/ceiling) - fill X and Z
            for (int i = 0; i <= stepsX; i++) {
                for (int j = 0; j <= stepsZ; j++) {
                    double x = x1 + (x2 - x1) * i / Math.max(1, stepsX);
                    double z = z1 + (z2 - z1) * j / Math.max(1, stepsZ);
                    client.particleManager.addParticle(particle, x, y1, z, 0.0, 0.0, 0.0);
                }
            }
        } else {
            // Vertical face (XY plane) - fill X and Y
            for (int i = 0; i <= stepsX; i++) {
                for (int j = 0; j <= stepsY; j++) {
                    double x = x1 + (x2 - x1) * i / Math.max(1, stepsX);
                    double y = y1 + (y2 - y1) * j / Math.max(1, stepsY);
                    client.particleManager.addParticle(particle, x, y, z1, 0.0, 0.0, 0.0);
                }
            }
        }
    }
    
    /**
     * Spawns particles across a ZY vertical face (constant X).
     */
    private static void spawnParticlesOnFaceZ(MinecraftClient client, DustParticleEffect particle,
                                               double x, double y1, double z1,
                                               double x2, double y2, double z2,
                                               double spacing) {
        int stepsZ = (int) Math.ceil(Math.abs(z2 - z1) / spacing);
        int stepsY = (int) Math.ceil(Math.abs(y2 - y1) / spacing);
        
        for (int i = 0; i <= stepsZ; i++) {
            for (int j = 0; j <= stepsY; j++) {
                double z = z1 + (z2 - z1) * i / Math.max(1, stepsZ);
                double y = y1 + (y2 - y1) * j / Math.max(1, stepsY);
                client.particleManager.addParticle(particle, x, y, z, 0.0, 0.0, 0.0);
            }
        }
    }

    // ===== Zone Wand Preview =====

    public static void clearZoneWandState() {
        pendingZonePos1 = null;
        pendingZonePos2 = null;
        pendingZoneDimension = null;
        pendingZonePreviewActive = false;
        zonePreviewTickCounter = 0;
    }

    public static boolean hasZonePreviewActive() {
        return pendingZonePreviewActive && pendingZonePos1 != null && pendingZonePos2 != null;
    }

    public static BlockPos getZonePos1() { return pendingZonePos1; }
    public static BlockPos getZonePos2() { return pendingZonePos2; }

    private static void handleZoneConfirmKey(MinecraftClient client) {
        if (!hasZonePreviewActive() || client.player == null) return;
        // Open naming popup
        client.setScreen(new net.wcfcarolina13.GraphicalUserInterface.ZoneNamePopupScreen());
    }

    private static void tickZonePreview(MinecraftClient client) {
        if (client.player == null) {
            if (pendingZonePreviewActive) clearZoneWandState();
            return;
        }

        // Clear if dimension changed
        if (pendingZoneDimension != null && client.world != null
                && !pendingZoneDimension.equals(client.world.getRegistryKey())) {
            client.player.sendMessage(net.minecraft.text.Text.literal(
                    "\u00A77Zone selection cancelled (dimension changed)"), true);
            clearZoneWandState();
            return;
        }

        // Clear if player switched away from wand
        if (pendingZonePreviewActive) {
            net.minecraft.item.ItemStack held = client.player.getMainHandStack();
            if (!net.wcfcarolina13.network.ZoneNetworkManager.isZoneWand(held)) {
                client.player.sendMessage(net.minecraft.text.Text.literal(
                        "\u00A77Zone selection cancelled"), true);
                clearZoneWandState();
                return;
            }
        }

        // Render preview particles every 3 ticks
        if (!pendingZonePreviewActive || pendingZonePos1 == null || pendingZonePos2 == null) return;
        zonePreviewTickCounter++;
        if (zonePreviewTickCounter < 3) return;
        zonePreviewTickCounter = 0;

        renderZoneEdgeParticles(client);
    }

    private static void renderZoneEdgeParticles(MinecraftClient client) {
        if (client.player == null || pendingZonePos1 == null || pendingZonePos2 == null) return;

        int x1 = Math.min(pendingZonePos1.getX(), pendingZonePos2.getX());
        int y1 = Math.min(pendingZonePos1.getY(), pendingZonePos2.getY());
        int z1 = Math.min(pendingZonePos1.getZ(), pendingZonePos2.getZ());
        int x2 = Math.max(pendingZonePos1.getX(), pendingZonePos2.getX());
        int y2 = Math.max(pendingZonePos1.getY(), pendingZonePos2.getY());
        int z2 = Math.max(pendingZonePos1.getZ(), pendingZonePos2.getZ());

        int viewerY = client.player.getBlockY();
        int viewerX = client.player.getBlockX();
        int viewerZ = client.player.getBlockZ();

        int visMinY = Math.max(y1, viewerY - 64);
        int visMaxY = Math.min(y2, viewerY + 64);
        int visMinX = Math.max(x1, viewerX - 128);
        int visMaxX = Math.min(x2, viewerX + 128);
        int visMinZ = Math.max(z1, viewerZ - 128);
        int visMaxZ = Math.min(z2, viewerZ + 128);

        // Compute spacing to stay under 2000 particles
        int dx = Math.max(0, visMaxX - visMinX + 1);
        int dy = Math.max(0, visMaxY - visMinY + 1);
        int dz = Math.max(0, visMaxZ - visMinZ + 1);
        int estimated = 4 * dy + 4 * dx + 4 * dz;
        int spacing = 1;
        while (estimated / spacing > 2000 && spacing < 8) spacing *= 2;

        DustParticleEffect particle = new DustParticleEffect(0x00CCCC, 1.0f);

        // 4 vertical edges
        int[][] vEdges = {{x1, z1}, {x1, z2}, {x2, z1}, {x2, z2}};
        for (int[] e : vEdges) {
            if (e[0] < visMinX || e[0] > visMaxX || e[1] < visMinZ || e[1] > visMaxZ) continue;
            for (int y = visMinY; y <= visMaxY; y += spacing) {
                client.particleManager.addParticle(particle, e[0] + 0.5, y + 0.5, e[1] + 0.5, 0, 0, 0);
            }
        }
        // 4 X-axis edges
        int[][] xEdges = {{y1, z1}, {y1, z2}, {y2, z1}, {y2, z2}};
        for (int[] e : xEdges) {
            if (e[0] < visMinY || e[0] > visMaxY || e[1] < visMinZ || e[1] > visMaxZ) continue;
            for (int x = visMinX; x <= visMaxX; x += spacing) {
                client.particleManager.addParticle(particle, x + 0.5, e[0] + 0.5, e[1] + 0.5, 0, 0, 0);
            }
        }
        // 4 Z-axis edges
        int[][] zEdges = {{y1, x1}, {y1, x2}, {y2, x1}, {y2, x2}};
        for (int[] e : zEdges) {
            if (e[0] < visMinY || e[0] > visMaxY || e[1] < visMinX || e[1] > visMaxX) continue;
            for (int z = visMinZ; z <= visMaxZ; z += spacing) {
                client.particleManager.addParticle(particle, e[1] + 0.5, e[0] + 0.5, z + 0.5, 0, 0, 0);
            }
        }
    }

}
