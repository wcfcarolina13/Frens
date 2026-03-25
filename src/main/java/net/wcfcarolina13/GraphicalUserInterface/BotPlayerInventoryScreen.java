package net.wcfcarolina13.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FrensClient;
import net.wcfcarolina13.items.ModItems;
import net.wcfcarolina13.network.CompanionQuestStateRequestPayload;
import net.wcfcarolina13.network.CompanionQuestTopicPayload;
import net.wcfcarolina13.network.RecruitmentAdminActionPayload;
import net.wcfcarolina13.network.RequestRecruitmentReplayPayload;
import net.wcfcarolina13.network.RequestRecruitmentDialoguePayload;
import net.wcfcarolina13.ui.BotPlayerInventoryScreenHandler;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.network.PlayerListEntry;

public class BotPlayerInventoryScreen extends HandledScreen<BotPlayerInventoryScreenHandler> {
    private static final Identifier BACKGROUND_TEXTURE = Identifier.of("minecraft", "textures/gui/container/inventory.png");
    private static final int SECTION_WIDTH = 176;
    private static final int SECTION_HEIGHT = 166;
    private static final int BLOCK_GAP = 12;
    private static final int STATS_AREA_HEIGHT = 64;
    private static final int TOPIC_PADDING = 6;
    // Row height must comfortably fit the font + padding; 10px is too tight and causes visual overlap.
    private static final int TOPIC_ROW_HEIGHT = 12;
    private static final int TOPIC_CONTROL_GAP = 2;
    private static final int SKILL_ICON_SLOT_W = 16;

    /** Set by BotGuideScreen before sending GuideOpenInventoryPayload to start on Admin tab. */
    static boolean pendingAdminTab = false;
    /** Cursor position saved by BotGuideScreen so the guide-open transition doesn't center it. */
    static double pendingAdminCursorX = -1, pendingAdminCursorY = -1;

    /** Set by the GuideInventoryAccessPayload S2C handler to indicate a guide-initiated remote open. */
    public static boolean guideRemoteOpen = false;
    /** Whether the guide remote open has full inventory access (proximity/operator/artifacts). */
    public static boolean guideRemoteFullAccess = false;
    /** Human-readable reason for remote access (e.g. "Remote Inventory active — Wizard's Tome"). */
    public static String guideRemoteAccessReason = "";

    // Collapsed (shared inventory) quick-actions grid.
    private static final String TOPIC_PANEL_TITLE = "Actions";
    private static final int QUICK_TOPIC_COLS = 3;
    private static final int QUICK_TOPIC_MAX_ROWS = 2;
    private static final int QUICK_TOPIC_GAP = 2;
    private static final int QUICK_TOPIC_MIN_BUTTON_H = 12;
    private static final float QUICK_TOPIC_TEXT_SCALE = 0.85f;
    private static final double FOLLOW_DISTANCE_STEP = 1.0D;
    private static final double FOLLOW_DISTANCE_MIN = 1.0D;
    private static final double FOLLOW_DISTANCE_MAX = 64.0D;
    private static final double FOLLOW_DISTANCE_DEFAULT = 4.0D;
    private static final int WOODCUT_TREE_COUNT_MIN = 1;
    private static final int WOODCUT_TREE_COUNT_MAX = 64;
    private static final int WOODCUT_TREE_COUNT_DEFAULT = 4;
    private static final int SKILL_COUNT_UNSET = 0;
    private static final int SKILL_FISH_COUNT_MIN = 1;
    private static final int SKILL_FISH_COUNT_MAX = 128;
    private static final int SKILL_FISH_COUNT_DEFAULT = 8;
    private static final int SKILL_WOOL_COUNT_MIN = 1;
    private static final int SKILL_WOOL_COUNT_MAX = 256;
    private static final int SKILL_WOOL_COUNT_DEFAULT = 16;
    private static final int SKILL_WOOL_RANGE_MIN = 16;
    private static final int SKILL_WOOL_RANGE_MAX = 128;
    private static final int SKILL_WOOL_RANGE_DEFAULT = 48;
    private static final int SKILL_STRIPMINE_COUNT_MIN = 1;
    private static final int SKILL_STRIPMINE_COUNT_MAX = 128;
    private static final int SKILL_STRIPMINE_COUNT_DEFAULT = 8;
    private static final int SKILL_ASCENT_COUNT_MIN = 1;
    private static final int SKILL_ASCENT_COUNT_MAX = 128;
    private static final int SKILL_ASCENT_COUNT_DEFAULT = 5;
    private static final int SKILL_DESCENT_COUNT_MIN = 1;
    private static final int SKILL_DESCENT_COUNT_MAX = 128;
    private static final int SKILL_DESCENT_COUNT_DEFAULT = 5;
    private static final int TOPICS_OVERLAY_SEARCH_H = 14;
    private static final int TOPICS_OVERLAY_SEARCH_GAP = 4;
    private static final int TOPICS_OVERLAY_SCROLLBAR_W = 10;
    private static final int TOPICS_OVERLAY_SCROLLBAR_MIN_THUMB_H = 18;
    private static final int ACTIONS_TWO_COLUMN_GAP = 8;
    private static final int BOT_SWITCH_CONTROL_H = 12;
    private static final int BOT_SWITCH_CONTROL_W = 12;
    private static final int BOT_SWITCH_CONTROL_GAP = 2;
    private static final long BOT_SWITCH_HINT_COOLDOWN_MS = 1200L;
    private static final long BOT_SWITCH_STATE_RESTORE_WINDOW_MS = 5_000L;
    private OtherClientPlayerEntity fallbackBot;
    private final String botAlias;
    private float lastMouseX;
    private float lastMouseY;
    private boolean topicsExpanded;

    // Overlay column split (0..1) for Dialogue vs Topics list.
    private double overlaySplitRatio = 0.56;
    private boolean overlayDraggingSplit = false;
    private boolean overlayDraggingListScroll = false;
    private int overlayListScrollGrabOffsetY = 0;

    // Hover tooltip (delayed) for entries in the expanded overlay.
    private TopicEntry overlayHoveredEntry = null;
    private long overlayHoverStartedAtMs = 0L;
    private static final long OVERLAY_HOVER_TOOLTIP_DELAY_MS = 1700L;
    private TopicEntry quickHoveredEntry = null;
    private long quickHoverStartedAtMs = 0L;
    private static final long QUICK_HOVER_TOOLTIP_DELAY_MS = 1700L;
    private String overlayHoveredControlKey = null;
    private long overlayControlHoverStartedAtMs = 0L;
    private long lastBotSwitchHintAtMs = 0L;

    private static final Gson GSON = new Gson();
    private static final Type ADMIN_PERMISSIONS_CACHE_TYPE = new TypeToken<AdminPermissionsCache>() {}.getType();
    private static final Map<String, AdminPermissionsCache> ADMIN_PERMISSIONS_BY_ALIAS = new HashMap<>();

    private static boolean adminPreviewAsNonAdmin = false;

    // Guards guide-flag init so resize re-inits don't reset guideRemoteOpen.
    private boolean guideStateInitialized = false;

    // Browse state for scrolling past blocked bots in the switcher.
    private int switchBrowseOffset = 0;
    private String switchBrowsedBlockedAlias = null;
    private String switchBrowsedBlockReason = null;

    // Save/restore cursor position across bot-switch screen transitions.
    // Static so they survive the old→new screen instance transition.
    private static double savedSwitchCursorX = -1;
    private static double savedSwitchCursorY = -1;
    private static BotSwitchUiState pendingBotSwitchUiState = null;

    // Skills list scroll (always used for the small panel; also used for overlay when Skills tab is selected).
    private int skillScrollIndex;
    // Dialogue list scroll (only used for overlay when Dialogue tab is selected).
    private int dialogueScrollIndex;
    // Admin list scroll (only used for overlay when Admin tab is selected).
    private int adminScrollIndex;
    // Spell list scroll (only used for overlay when Spell tab is selected).
    private int spellScrollIndex;
    private TopicCategory overlayCategory = TopicCategory.SKILL;
    private int woodcutTreeCount = WOODCUT_TREE_COUNT_DEFAULT;
    private int fishTargetCount = SKILL_COUNT_UNSET;
    private int woolTargetCount = SKILL_COUNT_UNSET;
    private int woolSearchRange = SKILL_WOOL_RANGE_DEFAULT;
    private boolean woolAdjustingRange = false;
    private int stripmineLength = SKILL_COUNT_UNSET;
    private int ascentBlocks = SKILL_COUNT_UNSET;
    private int descentBlocks = SKILL_COUNT_UNSET;
    private boolean ascentSurfaceMode = false;
    private String topicSearchQuery = "";
    private boolean topicSearchFocused = false;
    private boolean spellsTabHovered = false;
    private long spellsTabHoverStartMs = 0L;

    // Hold-repeat state for +/- control buttons.
    private Runnable heldAdjustAction = null;
    private int heldAdjustTicks = 0;
    private static final int HOLD_REPEAT_INITIAL_DELAY = 10; // ticks before first repeat (0.5s)
    private static final int HOLD_REPEAT_SLOW_INTERVAL = 4;  // ticks between repeats at first
    private static final int HOLD_REPEAT_FAST_AT = 40;       // tick count to switch to fast
    private static final int HOLD_REPEAT_FAST_INTERVAL = 2;  // ticks between repeats when fast
    private static final int HOLD_REPEAT_FASTEST_AT = 80;    // tick count to switch to fastest
    private static final int HOLD_REPEAT_FASTEST_INTERVAL = 1; // every tick

    // Double-click direct input for value labels.
    private TopicAction directInputAction = null;
    private String directInputBuffer = "";
    private long lastValueLabelClickMs = 0L;
    private TopicAction lastValueLabelClickAction = null;
    private static final long DOUBLE_CLICK_MS = 400L;

    // Best-effort: request server stage/permanent snapshot once per overlay open (used for stage-gated dialogue topics).
    private boolean companionQuestStateRequested = false;

    // Simple safety guard for destructive admin actions.
    private boolean adminResetConfirmArmed = false;
    private long adminResetConfirmArmedAtMs = 0L;

    private static final int TOPICS_OVERLAY_MAX_WIDTH = 440;
    // Give the expanded overlay more vertical real estate so rows are not cramped.
    private static final int TOPICS_OVERLAY_MAX_HEIGHT = 320;
    private static final int TOPICS_OVERLAY_MIN_WIDTH = 320;
    private static final int TOPICS_OVERLAY_MIN_HEIGHT = 160;
    private static final int TOPICS_OVERLAY_PADDING = 10;
    private static final int TOPICS_OVERLAY_HEADER_PAD = 6;
    private static final int TOPICS_OVERLAY_FOOTER_PAD = 6;
    private static final int TOPICS_OVERLAY_COLUMN_GAP = 10;
    private static final int TOPICS_OVERLAY_LIST_HEADER_H = 18; // tab bar height for right column

    // Palette (Skyrim/Morrowind-ish: warmer parchment, less harsh whites).
    private static final int COLOR_TEXT_PARCHMENT = 0xFFD8C7A0;
    private static final int COLOR_TEXT_PLAYER = 0xFFFFE08A;
    private static final int COLOR_TEXT_SYSTEM = 0xFFB0B0B0;
    private static final int COLOR_TEXT_DISABLED = 0xFF6F6F6F;
    private static final int COLOR_TEXT_SUBTLE = 0xFF8E8E8E;
    // Morrowind-inspired accents.
    private static final int COLOR_TEXT_TOPIC = 0xFF5BA6FF;
    private static final int COLOR_TEXT_RESPONSE = 0xFFCC4B4B;

    // Last selected dialogue topic (for a topic-header style dialogue column).
    private String lastDialogueTopicLabel = "";
    private String lastDialogueTopicKey = "";

    private record DialogueResponseHitbox(Rect rect, TopicEntry entry) {}
    private final java.util.List<DialogueResponseHitbox> dialogueResponseHitboxes = new java.util.ArrayList<>();

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record BotSwitchLayout(Rect prevRect,
                                   Rect labelRect,
                                   Rect nextRect,
                                   List<String> aliases,
                                   int currentIndex) {
        boolean canSwitch() {
            return aliases != null && aliases.size() > 1;
        }
    }

    private static final class AdminPermissionsCache {
        boolean requesterOperator;
        Map<String, Boolean> globalDefaults = new HashMap<>();
        Map<String, Map<String, Boolean>> userOverrides = new HashMap<>();
        Map<String, String> knownUsers = new HashMap<>();
        boolean autonomousRescuesEnabled;
        int ownedSunsetSelfSufficientState = -2;
    }

    private record BotSwitchUiState(String targetAlias,
                                    long createdAtMs,
                                    boolean topicsExpanded,
                                    TopicCategory overlayCategory,
                                    double overlaySplitRatio,
                                    int skillScrollIndex,
                                    int dialogueScrollIndex,
                                    int adminScrollIndex,
                                    String topicSearchQuery,
                                    boolean topicSearchFocused,
                                    String lastDialogueTopicLabel,
                                    String lastDialogueTopicKey,
                                    boolean adminPreviewAsNonAdmin) {}

    public static void applyAdminPermissionsJson(String botAlias, String jsonData) {
        String aliasKey = normalizedAliasKey(botAlias);
        if (aliasKey.isBlank() || jsonData == null || jsonData.isBlank()) {
            return;
        }
        try {
            AdminPermissionsCache parsed = GSON.fromJson(jsonData, ADMIN_PERMISSIONS_CACHE_TYPE);
            if (parsed == null) {
                return;
            }
            AdminPermissionsCache cache = new AdminPermissionsCache();
            cache.requesterOperator = parsed.requesterOperator;

            if (parsed.globalDefaults != null) {
                for (Map.Entry<String, Boolean> entry : parsed.globalDefaults.entrySet()) {
                    String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
                    if (key.isBlank()) {
                        continue;
                    }
                    cache.globalDefaults.put(key, Boolean.TRUE.equals(entry.getValue()));
                }
            }

            if (parsed.userOverrides != null) {
                for (Map.Entry<String, Map<String, Boolean>> userEntry : parsed.userOverrides.entrySet()) {
                    String userUuid = normalizeUuidKey(userEntry.getKey());
                    if (userUuid.isBlank()) {
                        continue;
                    }
                    Map<String, Boolean> src = userEntry.getValue();
                    if (src == null || src.isEmpty()) {
                        continue;
                    }
                    HashMap<String, Boolean> dst = new HashMap<>();
                    for (Map.Entry<String, Boolean> permEntry : src.entrySet()) {
                        String permKey = permEntry.getKey() == null ? "" : permEntry.getKey().trim().toLowerCase(Locale.ROOT);
                        if (permKey.isBlank()) {
                            continue;
                        }
                        dst.put(permKey, Boolean.TRUE.equals(permEntry.getValue()));
                    }
                    if (!dst.isEmpty()) {
                        cache.userOverrides.put(userUuid, dst);
                    }
                }
            }

            if (parsed.knownUsers != null) {
                for (Map.Entry<String, String> entry : parsed.knownUsers.entrySet()) {
                    String userUuid = normalizeUuidKey(entry.getKey());
                    String userName = entry.getValue() != null ? entry.getValue().trim() : "";
                    if (userUuid.isBlank() || userName.isBlank()) {
                        continue;
                    }
                    cache.knownUsers.put(userUuid, userName);
                }
            }
            cache.autonomousRescuesEnabled = parsed.autonomousRescuesEnabled;
            cache.ownedSunsetSelfSufficientState = parsed.ownedSunsetSelfSufficientState;

            synchronized (ADMIN_PERMISSIONS_BY_ALIAS) {
                ADMIN_PERMISSIONS_BY_ALIAS.put(aliasKey, cache);
            }
        } catch (Exception ignored) {
            // Ignore malformed payloads; next server sync can refresh this cache.
        }
    }

    public static Map<String, Boolean> getDefaultAdminPermissionGlobalsSnapshot() {
        return new HashMap<>(DEFAULT_ADMIN_PERMISSION_GLOBALS);
    }

    public static Map<String, Boolean> getAdminPermissionGlobalsSnapshot(String botAlias) {
        AdminPermissionsCache cache;
        synchronized (ADMIN_PERMISSIONS_BY_ALIAS) {
            cache = ADMIN_PERMISSIONS_BY_ALIAS.get(normalizedAliasKey(botAlias));
        }
        if (cache == null || cache.globalDefaults == null || cache.globalDefaults.isEmpty()) {
            return getDefaultAdminPermissionGlobalsSnapshot();
        }
        return new HashMap<>(cache.globalDefaults);
    }

    public static Map<String, Map<String, Boolean>> getAdminPermissionUserOverridesSnapshot(String botAlias) {
        AdminPermissionsCache cache;
        synchronized (ADMIN_PERMISSIONS_BY_ALIAS) {
            cache = ADMIN_PERMISSIONS_BY_ALIAS.get(normalizedAliasKey(botAlias));
        }
        HashMap<String, Map<String, Boolean>> out = new HashMap<>();
        if (cache == null || cache.userOverrides == null || cache.userOverrides.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Map<String, Boolean>> entry : cache.userOverrides.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            out.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return out;
    }

    public static Map<String, String> getAdminPermissionKnownUsersSnapshot(String botAlias) {
        AdminPermissionsCache cache;
        synchronized (ADMIN_PERMISSIONS_BY_ALIAS) {
            cache = ADMIN_PERMISSIONS_BY_ALIAS.get(normalizedAliasKey(botAlias));
        }
        if (cache == null || cache.knownUsers == null || cache.knownUsers.isEmpty()) {
            return new HashMap<>();
        }
        return new HashMap<>(cache.knownUsers);
    }

    public static void requestAdminPermissionsSnapshot(String botAlias) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || botAlias == null || botAlias.isBlank()) {
            return;
        }
        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "permissions_snapshot", 0, false));
    }

    public static void sendAdminPermissionGlobalUpdate(String botAlias, String permissionKey, boolean allowed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || botAlias == null || botAlias.isBlank()) {
            return;
        }
        String key = permissionKey == null ? "" : permissionKey.trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) {
            return;
        }
        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "perm_global:" + key, 0, allowed));
    }

    public static void sendAdminPermissionUserUpdate(String botAlias, String userUuid, String permissionKey, boolean allowed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || botAlias == null || botAlias.isBlank()) {
            return;
        }
        String uuid = normalizeUuidKey(userUuid);
        String key = permissionKey == null ? "" : permissionKey.trim().toLowerCase(Locale.ROOT);
        if (uuid.isBlank() || key.isBlank()) {
            return;
        }
        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "perm_user:" + uuid + ":" + key, 0, allowed));
    }

    private record OverlayColumns(int contentX, int contentY, int contentW, int contentH,
                                  int dialogueX, int dialogueW,
                                  int dividerX, int dividerW,
                                  int listX, int listW,
                                  Rect dividerRect) {}

    private enum SkillVisualRowKind {
        HEADER,
        FULL_WIDTH,
        TWO_COLUMN
    }

    private record SkillVisualRow(SkillVisualRowKind kind, TopicEntry left, TopicEntry right) {}

    private record SkillEntryHit(TopicEntry entry, Rect rect) {}

    private enum TopicAction {
        COMPANION_COME,
        COMPANION_SUMMON,
        COMPANION_HOME,
        OPEN_SPELLS,
        SPELL_REMOTE_GUIDANCE,
        SPELL_CHORUS_RECALL,
        SPELL_SOUL_OF_ENDER,
        SPELL_REMOTE_INVENTORY,
        OPEN_GUIDE,
        STOP,
        RESUME,
        FOLLOW,
        GUARD,
        PATROL,
        RETURN_HOME,
        SLEEP,
        AUTO_RETURN_SUNSET,
        AUTO_RETURN_SELF_SUFFICIENT,
        TACTICAL_SHELTER,
        AUTO_RETURN_SUNSET_GUARD_PATROL,
        AUTO_RETURN_SKIP_PERMISSION,
        IDLE_HOBBIES,
        AUTO_HUNT_STARVING,
        GAMEPLAY_TIPS,
        IDLE_HOBBIES_ANYWHERE,
        BARITONE_PATHFINDER,
        UNLEASH_TETHERED,
        LEASH_ON_DISMOUNT,
        DROP_SWEEP,
        BASES,
        CRAFTING,
        COOKING,
        HUNTING,
        SKILL_FISH,
        SKILL_WOODCUT,
        SKILL_WOODCUT_CLEANUP,
        SKILL_WOOL,
        CONSTRUCTION,
        SKILL_HOVEL,
        SKILL_BURROW,
        SKILL_FARM,
        SKILL_COLLECT_DIRT,
        SKILL_MINING,
        SKILL_STRIPMINE,
        SKILL_ASCENT,
        SKILL_DESCENT,
        OPEN_BOT_CONTROLS,
        OPEN_PLAYER_SETTINGS,
        ADMIN_PREVIEW_NON_ADMIN,
        AUTONOMOUS_RESCUES,
        OWNED_SUNSET_SS,
        OPEN_SKIN_CHOOSER,
        SKIN_POLICY_EVERYONE,
        SKIN_POLICY_CUSTOM,
        STORAGE,
        QUICK_STORE,
        QUICK_FETCH
    }

    private enum TopicCategory {
        SKILL,
        DIALOGUE,
        SPELL,
        ADMIN
    }

    private static final class TopicEntry {
        private final String label;
        private final TopicCategory category;
        private final TopicAction action;
        private final boolean toggle;
        private final int indent;

        // For dialogue topics, identifies which scripted response to use.
        private final String dialogueKey;

        private TopicEntry(String label, TopicCategory category, TopicAction action, boolean toggle, int indent, String dialogueKey) {
            this.label = label;
            this.category = category;
            this.action = action;
            this.toggle = toggle;
            this.indent = indent;
            this.dialogueKey = dialogueKey;
        }

        private static TopicEntry skill(String label, TopicAction action, boolean toggle, int indent) {
            return new TopicEntry(label, TopicCategory.SKILL, action, toggle, indent, null);
        }

        private static TopicEntry skillHeader(String label) {
            return new TopicEntry(label, TopicCategory.SKILL, null, false, 0, "__header__");
        }

        private static TopicEntry dialogue(String label, String dialogueKey) {
            return new TopicEntry(label, TopicCategory.DIALOGUE, null, false, 0, dialogueKey);
        }

        private static TopicEntry admin(String label, String adminKey) {
            // Reuse dialogueKey field to carry the admin action key.
            return new TopicEntry(label, TopicCategory.ADMIN, null, false, 0, adminKey);
        }

        private static TopicEntry adminHeader(String label) {
            return new TopicEntry(label, TopicCategory.ADMIN, null, false, 0, "__header__");
        }

        private static TopicEntry spell(String label, TopicAction action) {
            return new TopicEntry(label, TopicCategory.SPELL, action, false, 0, null);
        }

        private static TopicEntry spellHeader(String label) {
            return new TopicEntry(label, TopicCategory.SPELL, null, false, 0, "__header__");
        }
    }

        private static final List<TopicEntry> SKILL_TOPIC_ENTRIES = List.of(
            TopicEntry.skillHeader("Core Actions"),
            TopicEntry.skill("Guide", TopicAction.OPEN_GUIDE, false, 0),
            TopicEntry.skill("Stop", TopicAction.STOP, false, 0),
            TopicEntry.skill("Resume", TopicAction.RESUME, false, 0),
            TopicEntry.skill("Quick Store", TopicAction.QUICK_STORE, false, 0),
            TopicEntry.skill("Quick Fetch", TopicAction.QUICK_FETCH, false, 0),

            TopicEntry.skillHeader("Orders & Travel"),
            TopicEntry.skill("Regroup", TopicAction.COMPANION_COME, false, 0),
            TopicEntry.skill("Follow", TopicAction.FOLLOW, true, 0),
            TopicEntry.skill("Guard", TopicAction.GUARD, true, 0),
            TopicEntry.skill("Patrol", TopicAction.PATROL, true, 0),
            TopicEntry.skill("Return Home", TopicAction.RETURN_HOME, true, 0),
            TopicEntry.skill("Sleep", TopicAction.SLEEP, false, 0),

            TopicEntry.skillHeader("Automation"),
            TopicEntry.skill("Auto Home @ Sunset", TopicAction.AUTO_RETURN_SUNSET, true, 0),
            TopicEntry.skill("Self-Sufficiency", TopicAction.AUTO_RETURN_SELF_SUFFICIENT, true, 1),
            TopicEntry.skill("Tactical Shelter", TopicAction.TACTICAL_SHELTER, true, 1),
            TopicEntry.skill("Guard/Patrol Eligible", TopicAction.AUTO_RETURN_SUNSET_GUARD_PATROL, true, 1),
            TopicEntry.skill("Skip Permission", TopicAction.AUTO_RETURN_SKIP_PERMISSION, true, 1),
            TopicEntry.skill("Idle Hobbies", TopicAction.IDLE_HOBBIES, true, 0),
            TopicEntry.skill("Auto Hunt (Starving)", TopicAction.AUTO_HUNT_STARVING, true, 1),
            TopicEntry.skill("Unleash Tethered", TopicAction.UNLEASH_TETHERED, true, 0),
            TopicEntry.skill("Leash on Dismount", TopicAction.LEASH_ON_DISMOUNT, true, 0),

            TopicEntry.skillHeader("Utilities"),
            TopicEntry.skill("Cleanup", TopicAction.DROP_SWEEP, false, 0),
            TopicEntry.skill("Bases >", TopicAction.BASES, false, 0),
            TopicEntry.skill("Crafting >", TopicAction.CRAFTING, false, 0),
            TopicEntry.skill("Construction >", TopicAction.CONSTRUCTION, false, 0),
            TopicEntry.skill("Cooking >", TopicAction.COOKING, false, 0),
            TopicEntry.skill("Hunting >", TopicAction.HUNTING, false, 0),
            TopicEntry.skill("Storage >", TopicAction.STORAGE, false, 0),

            TopicEntry.skillHeader("Skills"),
            TopicEntry.skill("Fishing", TopicAction.SKILL_FISH, false, 0),
            TopicEntry.skill("Woodcut", TopicAction.SKILL_WOODCUT, false, 0),
            TopicEntry.skill("Woodcut Cleanup", TopicAction.SKILL_WOODCUT_CLEANUP, false, 1),
            TopicEntry.skill("Wool", TopicAction.SKILL_WOOL, false, 0),
            TopicEntry.skill("Farming", TopicAction.SKILL_FARM, false, 0),
            TopicEntry.skill("Collect Dirt", TopicAction.SKILL_COLLECT_DIRT, false, 0),
            TopicEntry.skill("Mining", TopicAction.SKILL_MINING, false, 0),
            TopicEntry.skill("Stripmine", TopicAction.SKILL_STRIPMINE, false, 1),
            TopicEntry.skill("Ascent", TopicAction.SKILL_ASCENT, false, 1),
            TopicEntry.skill("Descent", TopicAction.SKILL_DESCENT, false, 1)
    );

    private static final List<TopicEntry> SPELL_TOPIC_ENTRIES = List.of(
            TopicEntry.spell("Remote Guidance", TopicAction.SPELL_REMOTE_GUIDANCE),
            TopicEntry.spell("Chorus Recall", TopicAction.SPELL_CHORUS_RECALL),
            TopicEntry.spell("Soul of Ender", TopicAction.SPELL_SOUL_OF_ENDER),
            TopicEntry.spell("Remote Inventory", TopicAction.SPELL_REMOTE_INVENTORY)
    );

            // Curated, non-scroll quick actions for the collapsed panel.
            // (These are intentionally short labels; the expanded overlay still shows the full list.)
            private static final List<TopicEntry> QUICK_TOPIC_ENTRIES = List.of(
                TopicEntry.skill("Guide", TopicAction.OPEN_GUIDE, false, 0),
                TopicEntry.skill("Stop", TopicAction.STOP, false, 0),
                TopicEntry.skill("Follow", TopicAction.FOLLOW, true, 0),
                TopicEntry.skill("Home", TopicAction.RETURN_HOME, true, 0),
                TopicEntry.skill("Sleep", TopicAction.SLEEP, false, 0),
                TopicEntry.skill("Guard", TopicAction.GUARD, true, 0)
            );

    // Dialogue/quest topics are intentionally local/scripted: they feed the dialogue panel without
    // triggering bot skills or commands.
    private static final List<TopicEntry> DIALOGUE_TOPIC_ENTRIES = List.of(
            TopicEntry.dialogue("Start a talk", "recruit_contact"),
            TopicEntry.dialogue("Replay intro", "recruit_replay"),
            TopicEntry.dialogue("How are we?", "companion_status"),
            TopicEntry.dialogue("Check progress", "companion_check"),
            TopicEntry.dialogue("Ask: Biomes", "batch3_biomes"),
            TopicEntry.dialogue("Ask: Structures", "batch3_structures"),
            TopicEntry.dialogue("Ask: Dimensions", "batch3_dimensions"),
            TopicEntry.dialogue("Ask: Traders & Mounts", "batch3_traders_mounts"),
            TopicEntry.dialogue("Ask: Travel", "batch3_travel"),
            TopicEntry.dialogue("Make this home", "companion_anchor_set"),
            TopicEntry.dialogue("About the village", "village_about"),
            TopicEntry.dialogue("Why stay?", "stay_conditions"),
            TopicEntry.dialogue("What's missing?", "village_missing"),
            TopicEntry.dialogue("Next projects", "village_projects"),
            TopicEntry.dialogue("Your past", "bot_past"),
            TopicEntry.dialogue("A promise", "promise"),
            TopicEntry.dialogue("Goodbye", "goodbye")
    );

            // Admin tools organized by category headers.
            private static final List<TopicEntry> ADMIN_TOPIC_ENTRIES = List.of(
                TopicEntry.adminHeader("🧭 Controls"),
                new TopicEntry("Bot Controls >", TopicCategory.ADMIN, TopicAction.OPEN_BOT_CONTROLS, false, 0, null),
                new TopicEntry("Player Permissions >", TopicCategory.ADMIN, TopicAction.OPEN_PLAYER_SETTINGS, false, 0, null),
                new TopicEntry("Preview as Non-Admin", TopicCategory.ADMIN, TopicAction.ADMIN_PREVIEW_NON_ADMIN, true, 0, null),

                TopicEntry.adminHeader("🧙 Magic & Skins"),
                new TopicEntry("Change Skin >", TopicCategory.ADMIN, TopicAction.OPEN_SKIN_CHOOSER, false, 0, null),
                new TopicEntry("Allow Skin Changes for Everyone", TopicCategory.ADMIN, TopicAction.SKIN_POLICY_EVERYONE, true, 0, null),
                new TopicEntry("Allow Custom URL Skins", TopicCategory.ADMIN, TopicAction.SKIN_POLICY_CUSTOM, true, 0, null),
                TopicEntry.admin("Give Wizard's Tome", "give_wizard_tome"),

                TopicEntry.adminHeader("⚙ Behavior"),
                new TopicEntry("Gameplay Tips (Hints)", TopicCategory.ADMIN, TopicAction.GAMEPLAY_TIPS, true, 0, null),
                new TopicEntry("Idle Hobbies Anywhere", TopicCategory.ADMIN, TopicAction.IDLE_HOBBIES_ANYWHERE, true, 0, null),
                new TopicEntry("Baritone Pathfinder", TopicCategory.ADMIN, TopicAction.BARITONE_PATHFINDER, true, 0, null),
                new TopicEntry("Allow Autonomous Rescues", TopicCategory.ADMIN, TopicAction.AUTONOMOUS_RESCUES, true, 0, null),
                new TopicEntry("Owned Sunset SS", TopicCategory.ADMIN, TopicAction.OWNED_SUNSET_SS, true, 0, null),

                TopicEntry.adminHeader("🎓 Learning"),
                TopicEntry.admin("Learning Status", "learning_status"),
                TopicEntry.admin("Learning Start", "learning_start"),
                TopicEntry.admin("Learning Stop (Success)", "learning_stop_success"),
                TopicEntry.admin("Learning Stop (Failure)", "learning_stop_fail"),
                TopicEntry.admin("Learning Stop (Abort)", "learning_stop_abort"),

                TopicEntry.adminHeader("🏘 Recruitment"),
                TopicEntry.admin("Recruitment Status", "recruit_status"),
                TopicEntry.admin("Recruitment Enable", "recruit_enable"),
                TopicEntry.admin("Recruitment Disable", "recruit_disable"),
                TopicEntry.admin("Recruitment Reset", "recruit_reset"),
                TopicEntry.admin("Set Village Anchor", "anchor_set"),
                TopicEntry.admin("Clear Village Anchor", "anchor_clear"),

                TopicEntry.adminHeader("🔧 Debug"),
                TopicEntry.admin("Set Stage 0", "setstage:0"),
                TopicEntry.admin("Set Stage 1", "setstage:1"),
                TopicEntry.admin("Set Stage 2", "setstage:2"),
                TopicEntry.admin("Set Stage 3", "setstage:3"),
                TopicEntry.admin("Set Stage 4", "setstage:4")
            );

    public BotPlayerInventoryScreen(BotPlayerInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = SECTION_WIDTH * 2 + BLOCK_GAP;
        this.backgroundHeight = SECTION_HEIGHT + STATS_AREA_HEIGHT;
        this.titleX = 8;
        this.titleY = 6;
        this.playerInventoryTitleX = SECTION_WIDTH + BLOCK_GAP + 8;
        this.playerInventoryTitleY = 6;
        this.botAlias = extractAlias(title.getString());
    }

    @Override
    protected void init() {
        super.init();
        // Restore cursor position after a bot-switch screen transition.
        // setScreen() calls mouse.unlockCursor() (which centres the cursor)
        // BEFORE init(), so by the time we get here the cursor has been
        // centred.  We must update BOTH the physical GLFW cursor AND the
        // internal Mouse.x/y fields; glfwSetCursorPos alone only moves the
        // physical cursor — the onCursorPos callback won't fire until the
        // next glfwPollEvents, leaving Mouse.x/y at the centre and causing
        // the first Click after a switch to resolve to wrong coordinates.
        if (savedSwitchCursorX >= 0 && this.client != null) {
            long window = this.client.getWindow().getHandle();
            GLFW.glfwSetCursorPos(window, savedSwitchCursorX, savedSwitchCursorY);
            ((net.wcfcarolina13.mixin.MouseAccessor) (Object) this.client.mouse)
                    .setX(savedSwitchCursorX);
            ((net.wcfcarolina13.mixin.MouseAccessor) (Object) this.client.mouse)
                    .setY(savedSwitchCursorY);
            savedSwitchCursorX = -1;
            savedSwitchCursorY = -1;
        }

        restorePendingBotSwitchUiState();

        if (isAdminUser()) {
            requestAdminPermissionsSnapshot(this.botAlias);
        }

        // Guide requested opening directly to Admin tab (via ] hotkey remote open).
        // Start restricted (Admin-only) until the S2C handler (GuideInventoryAccessPayload)
        // confirms the actual access level — avoids a frame of unrestricted tabs.
        // Guarded so that resize re-inits (same instance, init() called again) don't reset flags.
        if (!guideStateInitialized) {
            guideStateInitialized = true;
            if (pendingAdminTab) {
                pendingAdminTab = false;
                topicsExpanded = true;
                overlayCategory = TopicCategory.ADMIN;
                guideRemoteOpen = true;
                guideRemoteFullAccess = false; // Assume restricted until S2C confirms otherwise
                // Restore cursor position saved by BotGuideScreen (setScreen centres it).
                if (pendingAdminCursorX >= 0 && this.client != null) {
                    long window = this.client.getWindow().getHandle();
                    GLFW.glfwSetCursorPos(window, pendingAdminCursorX, pendingAdminCursorY);
                    ((net.wcfcarolina13.mixin.MouseAccessor) (Object) this.client.mouse)
                            .setX(pendingAdminCursorX);
                    ((net.wcfcarolina13.mixin.MouseAccessor) (Object) this.client.mouse)
                            .setY(pendingAdminCursorY);
                    pendingAdminCursorX = -1;
                    pendingAdminCursorY = -1;
                }
            } else {
                // Not a guide open — clear any leftover guide-remote state.
                guideRemoteOpen = false;
                guideRemoteFullAccess = false;
                guideRemoteAccessReason = "";
            }
        }
    }

    private void captureBotSwitchUiState(String targetAlias) {
        if (targetAlias == null || targetAlias.isBlank()) {
            pendingBotSwitchUiState = null;
            return;
        }
        pendingBotSwitchUiState = new BotSwitchUiState(
                targetAlias,
                System.currentTimeMillis(),
                this.topicsExpanded,
                this.overlayCategory,
                this.overlaySplitRatio,
                this.skillScrollIndex,
                this.dialogueScrollIndex,
                this.adminScrollIndex,
                this.topicSearchQuery != null ? this.topicSearchQuery : "",
                this.topicSearchFocused,
                this.lastDialogueTopicLabel != null ? this.lastDialogueTopicLabel : "",
                this.lastDialogueTopicKey != null ? this.lastDialogueTopicKey : "",
                this.adminPreviewAsNonAdmin
        );
    }

    private void restorePendingBotSwitchUiState() {
        BotSwitchUiState state = pendingBotSwitchUiState;
        if (state == null) {
            return;
        }
        long ageMs = System.currentTimeMillis() - state.createdAtMs();
        boolean aliasMatches = state.targetAlias() != null && state.targetAlias().equalsIgnoreCase(this.botAlias);
        if (!aliasMatches) {
            if (ageMs > BOT_SWITCH_STATE_RESTORE_WINDOW_MS) {
                pendingBotSwitchUiState = null;
            }
            return;
        }
        pendingBotSwitchUiState = null;

        this.overlayCategory = state.overlayCategory() != null ? state.overlayCategory() : TopicCategory.SKILL;
        this.overlaySplitRatio = MathHelper.clamp(state.overlaySplitRatio(), 0.25, 0.75);
        this.skillScrollIndex = Math.max(0, state.skillScrollIndex());
        this.dialogueScrollIndex = Math.max(0, state.dialogueScrollIndex());
        this.adminScrollIndex = Math.max(0, state.adminScrollIndex());
        this.topicSearchQuery = state.topicSearchQuery() != null ? state.topicSearchQuery() : "";
        this.topicSearchFocused = state.topicSearchFocused();
        this.lastDialogueTopicLabel = state.lastDialogueTopicLabel() != null ? state.lastDialogueTopicLabel() : "";
        this.lastDialogueTopicKey = state.lastDialogueTopicKey() != null ? state.lastDialogueTopicKey() : "";
        this.adminPreviewAsNonAdmin = state.adminPreviewAsNonAdmin();

        if (state.topicsExpanded()) {
            toggleTopicsExpanded(true);
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        // Guide Admin-only mode: don't draw inventory textures, stats, or topic panel.
        if (guideRemoteOpen && !guideRemoteFullAccess) {
            return;
        }
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x + SECTION_WIDTH + BLOCK_GAP, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);

        int statsTop = y + SECTION_HEIGHT + 2;
        context.fill(x, statsTop, x + SECTION_WIDTH, statsTop + STATS_AREA_HEIGHT - 4, 0xC0101010);
        drawBotStats(context, x + 6, statsTop + 6);
        drawCollapsedBotSwitchControls(context, mouseX, mouseY, statsTop + STATS_AREA_HEIGHT - BOT_SWITCH_CONTROL_H - 5);
        // When the expanded overlay is open, let it consume this space (don't render the small panel behind it).
        if (!topicsExpanded) {
            drawTopicPanel(context, x + SECTION_WIDTH + BLOCK_GAP, statsTop, mouseX, mouseY);
        }
    }

    private void drawBotStats(DrawContext context, int x, int y) {
        BotPlayerInventoryScreenHandler handler = this.handler;
        float health = handler.getBotHealth();
        float maxHealth = handler.getBotMaxHealth();
        int hunger = handler.getBotHunger();
        int level = handler.getBotLevel();
        float xpProgress = handler.getBotXpProgress();

        String healthLabel = String.format("Health: %.1f / %.1f", health, maxHealth);
        context.drawText(this.textRenderer, healthLabel, x, y, 0xFFEFEFEF, false);
        drawBar(context, x, y + 10, 120, 6, health / maxHealth, 0xFFB83E3E);
        String hungerLabel = "Hunger: " + hunger;
        context.drawText(this.textRenderer, hungerLabel, x, y + 20, 0xFFEFEFEF, false);
        drawBar(context, x, y + 30, 120, 6, MathHelper.clamp(hunger / 20f, 0.0f, 1.0f), 0xFFE3C05C);

        int xpAreaX = x + 130;
        int xpAreaW = Math.max(40, SECTION_WIDTH - 136);
        String xpLabel = "XP L" + level;
        context.drawText(this.textRenderer, xpLabel, xpAreaX, y, 0xFFEFEFEF, false);
        drawBar(context, xpAreaX, y + 10, xpAreaW, 6, xpProgress, 0xFF4FA3E3);
    }

    private void drawBar(DrawContext context, int x, int y, int width, int height, float value, int color) {
        int border = 0xFF000000;
        context.fill(x, y, x + width, y + height, 0xFF1A1A1A);
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
        context.fill(x, y, x + 1, y + height, border);
        context.fill(x + width - 1, y, x + width, y + height, border);
        int filled = Math.round((width - 2) * MathHelper.clamp(value, 0.0f, 1.0f));
        context.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
    }

    private void drawCollapsedBotSwitchControls(DrawContext context, int mouseX, int mouseY, int rowY) {
        BotSwitchLayout layout = computeBotSwitchLayout(this.x + 6, this.x + SECTION_WIDTH - 6, rowY);
        if (layout == null) {
            return;
        }
        drawBotSwitchControls(context, layout, mouseX, mouseY, false);
    }

    private void drawOverlayBotSwitchControls(DrawContext context, Rect overlayRect, int closeX, int mouseX, int mouseY) {
        // Guide Admin-only mode: hide bot switch controls entirely.
        if (guideRemoteOpen && !guideRemoteFullAccess) {
            return;
        }
        BotSwitchLayout layout = computeOverlayBotSwitchLayout(overlayRect, closeX);
        if (layout == null) {
            return;
        }
        drawBotSwitchControls(context, layout, mouseX, mouseY, true);
    }

    private BotSwitchLayout computeOverlayBotSwitchLayout(Rect overlayRect, int closeX) {
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int rowY = overlayRect.y + Math.max(1, (headerH - BOT_SWITCH_CONTROL_H) / 2);
        int leftBound = overlayRect.x + TOPICS_OVERLAY_PADDING + this.textRenderer.getWidth(getOverlayHeaderTitle()) + 10;
        int rightBound = closeX - 8;
        return computeBotSwitchLayout(leftBound, rightBound, rowY);
    }

    private BotSwitchLayout getActiveBotSwitchLayout() {
        if (topicsExpanded) {
            Rect overlayRect = computeTopicsOverlayRect();
            int closeSize = 12;
            int closeX = overlayRect.right() - TOPICS_OVERLAY_PADDING - closeSize;
            return computeOverlayBotSwitchLayout(overlayRect, closeX);
        }
        int rowY = this.y + SECTION_HEIGHT + 2 + STATS_AREA_HEIGHT - BOT_SWITCH_CONTROL_H - 5;
        return computeBotSwitchLayout(this.x + 6, this.x + SECTION_WIDTH - 6, rowY);
    }

    private boolean isActionsOverlayFullWidth() {
        return overlayCategory == TopicCategory.SKILL || overlayCategory == TopicCategory.SPELL;
    }

    private String getOverlayHeaderTitle() {
        return switch (overlayCategory) {
            case DIALOGUE -> "Dialogue";
            case SPELL -> "Spells";
            case ADMIN -> "Admin";
            default -> TOPIC_PANEL_TITLE;
        };
    }

    private String getOverlayFooterHint() {
        if (guideRemoteOpen && !guideRemoteFullAccess) {
            return "Scroll or drag scrollbar; click an admin action; Esc closes";
        }
        return switch (overlayCategory) {
            case SKILL -> "Scroll or drag scrollbar; click an action; [ and ] switch bot; Esc closes";
            case DIALOGUE -> "Scroll or drag scrollbar; click a topic; [ and ] switch bot; Esc closes";
            case SPELL -> buildSpellStatusBar();
            case ADMIN -> "Scroll or drag scrollbar; click an admin action; [ and ] switch bot; Esc closes";
        };
    }

    private String buildSpellStatusBar() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null) {
            return "Spells require artifacts. No artifacts detected.";
        }

        StringBuilder sb = new StringBuilder();
        String alias = this.botAlias != null ? this.botAlias.trim() : "";

        // Player artifacts.
        java.util.List<String> playerArtifacts = new java.util.ArrayList<>();
        if (isNearEnchantingTable(mc, 4)) playerArtifacts.add("Enchanting Table");
        if (hasSpellbookToken(mc)) playerArtifacts.add("Wizard's Tome");
        if (hasEyeOfEnderToken(mc)) playerArtifacts.add("Eye of Ender");
        if (hasEnderPearlInInventory(mc)) playerArtifacts.add("Pearl");
        if (hasChorusFruitInInventory(mc)) playerArtifacts.add("Chorus");

        // Bot artifacts from actual inventory slots.
        java.util.List<String> botArtifacts = new java.util.ArrayList<>();
        if (this.handler != null) {
            boolean botHasEye = false, botHasPearl = false, botHasChorus = false;
            for (int i = 0; i < 41; i++) {
                var stack = this.handler.getSlot(i).getStack();
                if (stack == null || stack.isEmpty()) continue;
                if (stack.isOf(Items.ENDER_EYE)) botHasEye = true;
                if (stack.isOf(Items.ENDER_PEARL)) botHasPearl = true;
                if (stack.isOf(Items.CHORUS_FRUIT)) botHasChorus = true;
            }
            if (botHasEye) botArtifacts.add("Eye of Ender");
            if (botHasPearl) botArtifacts.add("Pearl");
            if (botHasChorus) botArtifacts.add("Chorus");
        }

        if (playerArtifacts.isEmpty() && botArtifacts.isEmpty()) {
            return "Spells require artifacts. No artifacts detected.";
        }

        // Format: "You: Pearl, Eye \u00B7 Jake: Eye of Ender"
        if (!playerArtifacts.isEmpty()) {
            sb.append("You: ").append(String.join(", ", playerArtifacts));
        }
        if (!botArtifacts.isEmpty()) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(alias.isEmpty() ? "Bot" : alias).append(": ").append(String.join(", ", botArtifacts));
        }

        return sb.toString();
    }

    private int getOverlayListX(OverlayColumns cols) {
        return isActionsOverlayFullWidth() ? cols.contentX : cols.listX;
    }

    private int getOverlayListW(OverlayColumns cols) {
        return isActionsOverlayFullWidth() ? cols.contentW : cols.listW;
    }

    private int getOverlayListContentWidth(OverlayColumns cols) {
        return getOverlayListContentWidth(getOverlayListW(cols));
    }

    private void resetInventoryTooltipHoverState() {
        long now = System.currentTimeMillis();
        quickHoveredEntry = null;
        quickHoverStartedAtMs = now;
        overlayHoveredEntry = null;
        overlayHoverStartedAtMs = now;
        overlayHoveredControlKey = null;
        overlayControlHoverStartedAtMs = now;
    }

    private BotSwitchLayout computeBotSwitchLayout(int leftBound, int rightBound, int y) {
        int width = rightBound - leftBound;
        int minWidth = BOT_SWITCH_CONTROL_W * 2 + BOT_SWITCH_CONTROL_GAP * 2 + 24;
        if (width < minWidth) {
            return null;
        }
        int buttonY = y;
        Rect prevRect = new Rect(leftBound, buttonY, BOT_SWITCH_CONTROL_W, BOT_SWITCH_CONTROL_H);
        Rect nextRect = new Rect(rightBound - BOT_SWITCH_CONTROL_W, buttonY, BOT_SWITCH_CONTROL_W, BOT_SWITCH_CONTROL_H);
        int labelX = prevRect.right() + BOT_SWITCH_CONTROL_GAP;
        int labelW = Math.max(24, nextRect.x - BOT_SWITCH_CONTROL_GAP - labelX);
        Rect labelRect = new Rect(labelX, buttonY, labelW, BOT_SWITCH_CONTROL_H);
        List<String> aliases = collectSwitchableBotAliases();
        int currentIndex = indexOfAliasIgnoreCase(aliases, botAlias);
        if (currentIndex < 0 && !aliases.isEmpty()) {
            currentIndex = 0;
        }
        return new BotSwitchLayout(prevRect, labelRect, nextRect, aliases, currentIndex);
    }

    private List<String> collectSwitchableBotAliases() {
        Set<String> onlineNames = getOnlinePlayerNames();
        Set<String> unique = new LinkedHashSet<>();
        addSwitchAlias(unique, botAlias); // current bot — always include

        // Only include other aliases if they are actually online in this world
        String recruitAlias = FrensClient.getRecruitmentBotAlias();
        if (recruitAlias != null && onlineNames.contains(recruitAlias.toLowerCase(Locale.ROOT))) {
            addSwitchAlias(unique, recruitAlias);
        }

        if (Frens.CONFIG != null) {
            for (String alias : Frens.CONFIG.getBotGameProfile().keySet()) {
                if (onlineNames.contains(alias.toLowerCase(Locale.ROOT))) {
                    addSwitchAlias(unique, alias);
                }
            }
            for (String alias : Frens.CONFIG.getAllBotAliases()) {
                if (onlineNames.contains(alias.toLowerCase(Locale.ROOT))) {
                    addSwitchAlias(unique, alias);
                }
            }
        }

        ArrayList<String> aliases = new ArrayList<>(unique);
        aliases.sort(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)));
        return aliases;
    }

    /** Returns lowercase names of all players currently in the tab list. */
    private Set<String> getOnlinePlayerNames() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) return Collections.emptySet();
        Set<String> names = new HashSet<>();
        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            if (entry.getProfile() != null && entry.getProfile().name() != null) {
                names.add(entry.getProfile().name().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private void addSwitchAlias(Set<String> unique, String alias) {
        if (alias == null) {
            return;
        }
        String trimmed = alias.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.equalsIgnoreCase("default")) {
            return;
        }
        if (containsAliasIgnoreCase(unique, trimmed)) {
            return;
        }
        unique.add(trimmed);
    }

    private boolean containsAliasIgnoreCase(Set<String> aliases, String alias) {
        if (aliases == null || aliases.isEmpty() || alias == null || alias.isBlank()) {
            return false;
        }
        for (String candidate : aliases) {
            if (candidate != null && candidate.equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }

    private int indexOfAliasIgnoreCase(List<String> aliases, String alias) {
        if (aliases == null || aliases.isEmpty() || alias == null || alias.isBlank()) {
            return -1;
        }
        for (int i = 0; i < aliases.size(); i++) {
            String candidate = aliases.get(i);
            if (candidate != null && candidate.equalsIgnoreCase(alias)) {
                return i;
            }
        }
        return -1;
    }

    private String switchLabel(BotSwitchLayout layout) {
        if (layout == null || layout.aliases() == null || layout.aliases().isEmpty()) {
            return botAlias;
        }
        int idx = MathHelper.clamp(layout.currentIndex(), 0, layout.aliases().size() - 1);
        String current = layout.aliases().get(idx);
        if (layout.aliases().size() <= 1) {
            return current;
        }
        return current + " (" + (idx + 1) + "/" + layout.aliases().size() + ")";
    }

    private void drawBotSwitchControls(DrawContext context, BotSwitchLayout layout, int mouseX, int mouseY, boolean overlayStyle) {
        if (layout == null) {
            return;
        }
        boolean canSwitch = layout.canSwitch();
        boolean blocked = switchBrowsedBlockedAlias != null;
        int labelFill = overlayStyle ? 0xFF171717 : 0xCC171717;
        int labelBorder = overlayStyle ? 0xFF2A2A2A : 0xFF000000;
        if (!canSwitch) {
            labelFill = overlayStyle ? 0xFF141414 : 0xCC141414;
        }
        if (blocked) {
            labelFill = 0xCC1E1508;
            labelBorder = 0xFF3D3018;
        }

        context.fill(layout.labelRect().x, layout.labelRect().y, layout.labelRect().right(), layout.labelRect().bottom(), labelFill);
        context.fill(layout.labelRect().x, layout.labelRect().y, layout.labelRect().right(), layout.labelRect().y + 1, labelBorder);
        context.fill(layout.labelRect().x, layout.labelRect().bottom() - 1, layout.labelRect().right(), layout.labelRect().bottom(), labelBorder);
        context.fill(layout.labelRect().x, layout.labelRect().y, layout.labelRect().x + 1, layout.labelRect().bottom(), labelBorder);
        context.fill(layout.labelRect().right() - 1, layout.labelRect().y, layout.labelRect().right(), layout.labelRect().bottom(), labelBorder);

        drawSwitchButton(context, layout.prevRect(), "<", canSwitch, mouseX, mouseY);
        drawSwitchButton(context, layout.nextRect(), ">", canSwitch, mouseX, mouseY);

        String label;
        int color;
        if (blocked) {
            String blockedName = switchBrowsedBlockedAlias;
            int aliasCount = layout.aliases() != null ? layout.aliases().size() : 0;
            int browseIdx = layout.aliases() != null
                    ? indexOfAliasIgnoreCase(layout.aliases(), blockedName) : -1;
            if (browseIdx >= 0 && aliasCount > 1) {
                blockedName = blockedName + " (" + (browseIdx + 1) + "/" + aliasCount + ")";
            }
            label = elideToWidth("\u26A0 " + blockedName, Math.max(1, layout.labelRect().w - 4));
            color = 0xFFD4A843;
        } else {
            label = elideToWidth(switchLabel(layout), Math.max(1, layout.labelRect().w - 4));
            color = canSwitch ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED;
        }
        int textX = layout.labelRect().x + (layout.labelRect().w - this.textRenderer.getWidth(label)) / 2;
        int textY = layout.labelRect().y + Math.max(1, (layout.labelRect().h - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, label, textX, textY, color, false);
    }

    private void drawSwitchButton(DrawContext context, Rect rect, String glyph, boolean enabled, int mouseX, int mouseY) {
        if (rect == null) {
            return;
        }
        boolean hover = rect.contains(mouseX, mouseY);
        int fill = enabled ? 0xFF1A1A1A : 0xFF151515;
        if (enabled && hover) {
            fill = 0xFF2F2F2F;
        }
        context.fill(rect.x, rect.y, rect.right(), rect.bottom(), fill);
        context.fill(rect.x, rect.y, rect.right(), rect.y + 1, 0xFF000000);
        context.fill(rect.x, rect.bottom() - 1, rect.right(), rect.bottom(), 0xFF000000);
        context.fill(rect.x, rect.y, rect.x + 1, rect.bottom(), 0xFF000000);
        context.fill(rect.right() - 1, rect.y, rect.right(), rect.bottom(), 0xFF000000);

        int textX = rect.x + (rect.w - this.textRenderer.getWidth(glyph)) / 2;
        int textY = rect.y + Math.max(1, (rect.h - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, glyph, textX, textY, enabled ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED, false);
    }

    private boolean handleBotSwitchClick(BotSwitchLayout layout, double mouseX, double mouseY) {
        if (layout == null) {
            return false;
        }
        // Block bot switching in guide Admin-only mode.
        if (guideRemoteOpen && !guideRemoteFullAccess) {
            return false;
        }
        boolean hitSwitchControl = layout.prevRect().contains(mouseX, mouseY)
                || layout.nextRect().contains(mouseX, mouseY)
                || layout.labelRect().contains(mouseX, mouseY);
        if (!hitSwitchControl) {
            return false;
        }
        if (!layout.canSwitch()) {
            showBotSwitchUnavailableHint();
            return true;
        }
        if (layout.prevRect().contains(mouseX, mouseY)) {
            return cycleBotInventory(layout, -1);
        }
        if (layout.nextRect().contains(mouseX, mouseY) || layout.labelRect().contains(mouseX, mouseY)) {
            return cycleBotInventory(layout, 1);
        }
        return false;
    }

    private boolean cycleBotInventory(BotSwitchLayout layout, int direction) {
        if (layout == null || !layout.canSwitch()) {
            return false;
        }
        resetInventoryTooltipHoverState();
        List<String> aliases = layout.aliases();
        if (aliases == null || aliases.isEmpty()) {
            return false;
        }
        int baseIndex = layout.currentIndex() >= 0 ? layout.currentIndex() : 0;
        int effectiveIndex = Math.floorMod(baseIndex + switchBrowseOffset, aliases.size());
        int step = direction < 0 ? -1 : 1;
        int nextIndex = Math.floorMod(effectiveIndex + step, aliases.size());
        // Skip the currently-open bot so browsing always lands on a
        // *different* bot.  Without this, a 2-bot setup would toggle between
        // showing the blocked warning and clearing it on every click, making
        // the button feel unresponsive.
        if (aliases.size() > 2 && aliases.get(nextIndex).equalsIgnoreCase(botAlias)) {
            nextIndex = Math.floorMod(nextIndex + step, aliases.size());
        }
        String targetAlias = aliases.get(nextIndex);
        if (targetAlias == null || targetAlias.isBlank()) {
            showBotSwitchUnavailableHint();
            return false;
        }
        // Wrapped all the way around back to current bot (possible when there
        // are only 2 bots, or only 1 other bot left).  Absorb the click
        // without changing browse state so the warning stays visible.
        if (targetAlias.equalsIgnoreCase(botAlias)) {
            return true;
        }
        // Gated switching: verify proximity, spell item, or admin status.
        String blockReason = canSwitchToBot(targetAlias);
        if (blockReason != null) {
            switchBrowseOffset = Math.floorMod(nextIndex - baseIndex, aliases.size());
            switchBrowsedBlockedAlias = targetAlias;
            switchBrowsedBlockReason = blockReason;
            showBotSwitchBlockedWarning(blockReason);
            return true;
        }
        // Allowed — save cursor position before screen transition, then switch.
        if (this.client != null) {
            long window = this.client.getWindow().getHandle();
            double[] xBuf = new double[1], yBuf = new double[1];
            GLFW.glfwGetCursorPos(window, xBuf, yBuf);
            savedSwitchCursorX = xBuf[0];
            savedSwitchCursorY = yBuf[0];
        }
        captureBotSwitchUiState(targetAlias);
        switchBrowseOffset = 0;
        switchBrowsedBlockedAlias = null;
        switchBrowsedBlockReason = null;
        sendChatCommand("bot open " + formatBotTarget(targetAlias));
        return true;
    }

    private void showBotSwitchUnavailableHint() {
        if (this.client == null) {
            return;
        }
        var player = this.client.player;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBotSwitchHintAtMs < BOT_SWITCH_HINT_COOLDOWN_MS) {
            return;
        }
        lastBotSwitchHintAtMs = now;
        player.sendMessage(Text.literal("No other bot is available to switch right now."), true);
    }

    private void drawTopicPanel(DrawContext context, int panelX, int panelY, int mouseX, int mouseY) {
        int panelWidth = SECTION_WIDTH;
        int panelHeight = STATS_AREA_HEIGHT - 4;
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0101010);

        boolean headerHover = isMouseOverTopicsHeader(mouseX, mouseY);
        int headerColor = headerHover || topicsExpanded ? 0xFFFFE08A : 0xFFE6D7A3;
        context.drawText(this.textRenderer, TOPIC_PANEL_TITLE, panelX + TOPIC_PADDING, panelY + 2, headerColor, false);
        String openLabel = "Open";
        int openX = panelX + panelWidth - TOPIC_PADDING - this.textRenderer.getWidth(openLabel);
        context.drawText(this.textRenderer, openLabel, openX, panelY + 2, headerColor, false);
        int rowX = panelX + TOPIC_PADDING;
        int rowY = panelY + 2 + this.textRenderer.fontHeight + 1;
        int rowW = panelWidth - TOPIC_PADDING * 2;
        int listHeight = panelHeight - (rowY - panelY);
        drawQuickTopicGrid(context, rowX, rowY, rowW, listHeight, mouseX, mouseY);
    }

    private record QuickGridLayout(int cols, int rows, int buttonW, int buttonH, int gap) {}

    private QuickGridLayout computeQuickGridLayout(int w, int h) {
        int cols = Math.max(1, QUICK_TOPIC_COLS);
        int gap = Math.max(0, QUICK_TOPIC_GAP);
        int maxRows = Math.max(1, (int) Math.ceil((double) QUICK_TOPIC_ENTRIES.size() / (double) cols));

        int rows = Math.min(maxRows, Math.max(1, QUICK_TOPIC_MAX_ROWS));
        int buttonW = (w - gap * (cols - 1)) / cols;
        int buttonH = (h - gap * (rows - 1)) / rows;
        if (rows > 1 && buttonH < QUICK_TOPIC_MIN_BUTTON_H) {
            rows = 1;
            buttonH = h;
        }
        return new QuickGridLayout(cols, rows, Math.max(1, buttonW), Math.max(1, buttonH), gap);
    }

    private void drawQuickTopicGrid(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (QUICK_TOPIC_ENTRIES == null || QUICK_TOPIC_ENTRIES.isEmpty() || w <= 0 || h <= 0) {
            quickHoveredEntry = null;
            return;
        }

        QuickGridLayout layout = computeQuickGridLayout(w, h);
        int cols = layout.cols;
        int rows = layout.rows;
        int bw = layout.buttonW;
        int bh = layout.buttonH;
        int gap = layout.gap;

        int maxButtons = Math.min(QUICK_TOPIC_ENTRIES.size(), cols * rows);
        for (int i = 0; i < maxButtons; i++) {
            int r = i / cols;
            int c = i % cols;
            int bx = x + c * (bw + gap);
            int by = y + r * (bh + gap);
            TopicEntry entry = QUICK_TOPIC_ENTRIES.get(i);
            boolean active = entry.action != null && isEntryActive(entry.action);
            drawQuickTopicButton(context, bx, by, bw, bh, entry, active, mouseX, mouseY);
        }

        drawQuickTopicHoverTooltip(context, mouseX, mouseY, x, y, w, h);
    }

    private TopicEntry getQuickTopicEntryAt(int mouseX, int mouseY, int gridX, int gridY, int gridW, int gridH) {
        if (mouseX < gridX || mouseX >= gridX + gridW || mouseY < gridY || mouseY >= gridY + gridH) {
            return null;
        }
        QuickGridLayout layout = computeQuickGridLayout(gridW, gridH);
        int cols = layout.cols;
        int rows = layout.rows;
        int bw = layout.buttonW;
        int bh = layout.buttonH;
        int gap = layout.gap;

        int col = (mouseX - gridX) / (bw + gap);
        int row = (mouseY - gridY) / (bh + gap);
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return null;
        }

        int cellX = gridX + col * (bw + gap);
        int cellY = gridY + row * (bh + gap);
        if (mouseX >= cellX + bw || mouseY >= cellY + bh) {
            return null;
        }

        int index = row * cols + col;
        int maxButtons = Math.min(QUICK_TOPIC_ENTRIES.size(), cols * rows);
        if (index < 0 || index >= maxButtons) {
            return null;
        }
        return QUICK_TOPIC_ENTRIES.get(index);
    }

    private void drawQuickTopicHoverTooltip(DrawContext context, int mouseX, int mouseY, int gridX, int gridY, int gridW, int gridH) {
        TopicEntry hovered = getQuickTopicEntryAt(mouseX, mouseY, gridX, gridY, gridW, gridH);
        if (hovered != quickHoveredEntry) {
            quickHoveredEntry = hovered;
            quickHoverStartedAtMs = System.currentTimeMillis();
        }

        if (quickHoveredEntry == null) {
            return;
        }

        if (System.currentTimeMillis() - quickHoverStartedAtMs < QUICK_HOVER_TOOLTIP_DELAY_MS) {
            return;
        }

        java.util.List<String> lines = getQuickTooltipLines(quickHoveredEntry);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        drawTooltipBox(context, mouseX, mouseY, lines);
    }

    private java.util.List<String> getQuickTooltipLines(TopicEntry entry) {
        return java.util.List.of();
    }

    private void drawQuickTopicButton(DrawContext context, int x, int y, int w, int h, TopicEntry entry,
                                      boolean active, int mouseX, int mouseY) {
        if (entry == null) {
            return;
        }
        boolean enabled = entry.action == null || isEntryEnabled(entry.action);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;

        int fillBase = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int fill = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : fillBase;
        if (!enabled) {
            fill = 0xFF151515;
        }

        int border = 0xFF000000;
        context.fill(x, y, x + w, y + h, fill);
        context.fill(x, y, x + w, y + 1, border);
        context.fill(x, y + h - 1, x + w, y + h, border);
        context.fill(x, y, x + 1, y + h, border);
        context.fill(x + w - 1, y, x + w, y + h, border);

        String label = entry.label != null ? entry.label : "";
        int maxTextW = Math.max(1, (int) ((w - 6) / QUICK_TOPIC_TEXT_SCALE));
        String drawn = elideToWidth(label, maxTextW);
        int textColor = enabled ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED;
        int textW = this.textRenderer.getWidth(drawn);
        int drawX = x + Math.max(2, (w - Math.round(textW * QUICK_TOPIC_TEXT_SCALE)) / 2);
        int drawY = y + Math.max(1, (h - Math.round(this.textRenderer.fontHeight * QUICK_TOPIC_TEXT_SCALE)) / 2);
        drawScaledText(context, drawn, drawX, drawY, textColor, false, QUICK_TOPIC_TEXT_SCALE);
    }

    private void drawScaledText(DrawContext context, String text, int x, int y, int color, boolean shadow, float scale) {
        if (text == null || text.isBlank()) {
            return;
        }
        float s = Math.max(0.1f, scale);
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.scale(s, s);
        int sx = (int) Math.floor(x / (double) s);
        int sy = (int) Math.floor(y / (double) s);
        context.drawText(this.textRenderer, text, sx, sy, color, shadow);
        matrices.popMatrix();
    }

    private Rect computeTopicsOverlayRect() {
        int desiredW = getTopicPanelWidth() + 260; // room for dialogue + topics columns
        int w = MathHelper.clamp(desiredW, TOPICS_OVERLAY_MIN_WIDTH, TOPICS_OVERLAY_MAX_WIDTH);
        w = Math.min(w, Math.max(TOPICS_OVERLAY_MIN_WIDTH, this.width - 24));
        int h = MathHelper.clamp(SECTION_HEIGHT + 14, TOPICS_OVERLAY_MIN_HEIGHT, TOPICS_OVERLAY_MAX_HEIGHT);
        h = Math.min(h, Math.max(TOPICS_OVERLAY_MIN_HEIGHT, this.height - 24));

        // Anchor to the existing Topics panel (bottom-right of the shared inventory), but align the overlay
        // so it actually covers that panel + stats area (i.e. it can "gain" that real estate).
        int anchorRight = getTopicPanelX() + getTopicPanelWidth();
        int x = anchorRight - w;
        int anchorBottom = getTopicPanelY() + getTopicPanelHeight();
        int y = anchorBottom - h;

        // Clamp within screen.
        x = MathHelper.clamp(x, 12, Math.max(12, this.width - w - 12));
        y = MathHelper.clamp(y, 12, Math.max(12, this.height - h - 12));
        return new Rect(x, y, w, h);
    }

    private OverlayColumns computeOverlayColumns(Rect r) {
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;

        int contentX = r.x + TOPICS_OVERLAY_PADDING;
        int contentY = r.y + headerH + 2;
        int contentW = r.w - TOPICS_OVERLAY_PADDING * 2;
        int contentH = (footerY - 2) - contentY;

        int dividerW = TOPICS_OVERLAY_COLUMN_GAP;

        // Width available for both columns, excluding the divider gap.
        int availableW = Math.max(1, contentW - dividerW);
        int minDialogueW = 160;
        int minListW = 140;

        double minRatio = MathHelper.clamp((double) minDialogueW / (double) availableW, 0.0, 1.0);
        double maxRatio = MathHelper.clamp((double) (availableW - minListW) / (double) availableW, 0.0, 1.0);
        if (maxRatio < minRatio) {
            maxRatio = minRatio;
        }
        overlaySplitRatio = MathHelper.clamp(overlaySplitRatio, minRatio, maxRatio);

        int dialogueW = (int) Math.round(availableW * overlaySplitRatio);
        dialogueW = MathHelper.clamp(dialogueW, minDialogueW, Math.max(minDialogueW, availableW - minListW));

        int dialogueX = contentX;
        int dividerX = contentX + dialogueW;
        int listX = dividerX + dividerW;
        int listW = Math.max(minListW, contentX + contentW - listX);

        Rect dividerRect = new Rect(dividerX, contentY, dividerW, Math.max(1, contentH));
        return new OverlayColumns(contentX, contentY, contentW, contentH, dialogueX, dialogueW, dividerX, dividerW, listX, listW, dividerRect);
    }

    private boolean isMouseOverTopicsHeader(double mouseX, double mouseY) {
        int panelX = getTopicPanelX();
        int panelY = getTopicPanelY();
        int panelW = getTopicPanelWidth();
        int headerH = this.textRenderer.fontHeight + 4;
        return mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + headerH;
    }

    private boolean isMouseOverTopicsOverlay(double mouseX, double mouseY) {
        if (!topicsExpanded) return false;
        return computeTopicsOverlayRect().contains(mouseX, mouseY);
    }

    private int getOverlayRowsStartY(int listY) {
        return listY + TOPICS_OVERLAY_LIST_HEADER_H + TOPICS_OVERLAY_SEARCH_H + TOPICS_OVERLAY_SEARCH_GAP;
    }

    private int getOverlayRowsHeight(int listH) {
        return Math.max(1, listH - TOPICS_OVERLAY_LIST_HEADER_H - TOPICS_OVERLAY_SEARCH_H - TOPICS_OVERLAY_SEARCH_GAP);
    }

    private Rect computeOverlaySearchRect(int listX, int listY, int listW) {
        int searchX = listX + 4;
        int searchY = listY + TOPICS_OVERLAY_LIST_HEADER_H + 1;
        int searchW = Math.max(40, listW - 8);
        return new Rect(searchX, searchY, searchW, TOPICS_OVERLAY_SEARCH_H);
    }

    private int getOverlayListContentWidth(int listW) {
        return Math.max(60, listW - TOPICS_OVERLAY_SCROLLBAR_W - 2);
    }

    private int getSkillLabelStartX(int rowX, TopicAction action, int indent) {
        int baseX = rowX + 4;
        return baseX + SKILL_ICON_SLOT_W + Math.max(0, indent) * 10;
    }

    private int getCompactControlRight(int rowX, int rowW, int labelX, int valueWidth, int controlCount) {
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlsWidth = Math.max(0, controlCount) * controlSize + Math.max(0, controlCount - 1) * TOPIC_CONTROL_GAP;
        int minRight = labelX + 44 + Math.max(0, valueWidth) + TOPIC_CONTROL_GAP + controlsWidth;
        int preferredRight = rowX + Math.min(Math.max(88, rowW - 18), 208);
        int maxRight = rowX + rowW - 1;
        return MathHelper.clamp(Math.max(preferredRight, minRight), rowX + controlsWidth + 8, maxRight);
    }

    private void drawValueClusterBox(DrawContext context, int left, int rowY, int right, boolean hover) {
        if (right - left < 12) {
            return;
        }
        int boxY = rowY + 1;
        int boxBottom = rowY + TOPIC_ROW_HEIGHT - 1;
        int fill = hover ? 0xFF171717 : 0xFF131313;
        int border = hover ? 0xFF2D2D2D : 0xFF222222;
        context.fill(left, boxY, right, boxBottom, fill);
        context.fill(left, boxY, right, boxY + 1, border);
        context.fill(left, boxBottom - 1, right, boxBottom, border);
        context.fill(left, boxY, left + 1, boxBottom, border);
        context.fill(right - 1, boxY, right, boxBottom, border);
    }

    private Rect computeOverlayListScrollbarTrack(int listX, int listStartY, int listW, int rowsHeight) {
        int rowW = getOverlayListContentWidth(listW);
        int trackX = listX + rowW + 1;
        return new Rect(trackX, listStartY, TOPICS_OVERLAY_SCROLLBAR_W, Math.max(1, rowsHeight));
    }

    private boolean shouldUseTwoColumnSkillRow(TopicEntry entry) {
        if (entry == null || entry.category != TopicCategory.SKILL || isSkillHeaderEntry(entry) || entry.action == null) {
            return false;
        }
        return switch (entry.action) {
            case FOLLOW,
                 SKILL_WOODCUT,
                 SKILL_FISH,
                 SKILL_WOOL,
                 SKILL_COLLECT_DIRT,
                 SKILL_STRIPMINE,
                 SKILL_ASCENT,
                 SKILL_DESCENT,
                 AUTO_RETURN_SUNSET,
                 AUTO_RETURN_SELF_SUFFICIENT,
                 TACTICAL_SHELTER,
                 AUTO_RETURN_SUNSET_GUARD_PATROL,
                 AUTO_RETURN_SKIP_PERMISSION -> false;
            default -> true;
        };
    }

    private List<SkillVisualRow> buildSkillVisualRows(List<TopicEntry> entries) {
        ArrayList<SkillVisualRow> rows = new ArrayList<>();
        TopicEntry pendingSimple = null;

        for (TopicEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (isSkillHeaderEntry(entry)) {
                if (pendingSimple != null) {
                    rows.add(new SkillVisualRow(SkillVisualRowKind.FULL_WIDTH, pendingSimple, null));
                    pendingSimple = null;
                }
                rows.add(new SkillVisualRow(SkillVisualRowKind.HEADER, entry, null));
                continue;
            }

            if (!shouldUseTwoColumnSkillRow(entry)) {
                if (pendingSimple != null) {
                    rows.add(new SkillVisualRow(SkillVisualRowKind.FULL_WIDTH, pendingSimple, null));
                    pendingSimple = null;
                }
                rows.add(new SkillVisualRow(SkillVisualRowKind.FULL_WIDTH, entry, null));
                continue;
            }

            if (pendingSimple == null) {
                pendingSimple = entry;
            } else {
                rows.add(new SkillVisualRow(SkillVisualRowKind.TWO_COLUMN, pendingSimple, entry));
                pendingSimple = null;
            }
        }

        if (pendingSimple != null) {
            rows.add(new SkillVisualRow(SkillVisualRowKind.FULL_WIDTH, pendingSimple, null));
        }

        return rows;
    }

    private int getOverlayVisualRowCount(List<TopicEntry> entries) {
        if (overlayCategory == TopicCategory.SKILL) {
            return buildSkillVisualRows(entries).size();
        }
        return entries != null ? entries.size() : 0;
    }

    private void drawSkillEntryRow(DrawContext context, int rowX, int rowY, int rowW, TopicEntry entry, int mouseX, int mouseY) {
        if (entry == null) {
            return;
        }
        if (entry.action == TopicAction.FOLLOW) {
            drawFollowRow(context, rowX, rowY, rowW, mouseX, mouseY);
            return;
        }
        if (entry.action == TopicAction.SKILL_WOODCUT) {
            drawWoodcutRow(context, rowX, rowY, rowW, mouseX, mouseY);
            return;
        }
        if (isAdjustableSkillAction(entry.action)) {
            drawAdjustableSkillRow(context, rowX, rowY, rowW, entry, mouseX, mouseY);
            return;
        }
        boolean active = entry.action != null && isEntryActive(entry.action);
        drawTopicRow(context, rowX, rowY, rowW, entry, active, mouseX, mouseY);
    }

    private void drawSkillVisualRow(DrawContext context, int rowX, int rowY, int rowW, SkillVisualRow row, int mouseX, int mouseY) {
        if (row == null || row.left() == null) {
            return;
        }
        if (row.kind() == SkillVisualRowKind.TWO_COLUMN && row.right() != null) {
            int leftW = Math.max(40, (rowW - ACTIONS_TWO_COLUMN_GAP) / 2);
            int rightX = rowX + leftW + ACTIONS_TWO_COLUMN_GAP;
            int rightW = Math.max(40, rowW - leftW - ACTIONS_TWO_COLUMN_GAP);
            drawSkillEntryRow(context, rowX, rowY, leftW, row.left(), mouseX, mouseY);
            drawSkillEntryRow(context, rightX, rowY, rightW, row.right(), mouseX, mouseY);
            return;
        }
        drawSkillEntryRow(context, rowX, rowY, rowW, row.left(), mouseX, mouseY);
    }

    private SkillEntryHit getSkillEntryHitAtOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return null;
        }
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;
        int contentY = r.y + headerH + 2;
        int contentH = (footerY - 2) - contentY;

        OverlayColumns cols = computeOverlayColumns(r);
        int listX = getOverlayListX(cols);
        int listY = getOverlayRowsStartY(cols.contentY);
        int listW = getOverlayListContentWidth(cols);
        int listH = getOverlayRowsHeight(contentH);
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        List<SkillVisualRow> rows = buildSkillVisualRows(entries);
        clampOverlayScroll(visibleRows);

        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return null;
        }

        int rowOffset = (int) ((mouseY - listY) / TOPIC_ROW_HEIGHT);
        if (rowOffset < 0 || rowOffset >= visibleRows) {
            return null;
        }
        int visualIndex = skillScrollIndex + rowOffset;
        if (visualIndex < 0 || visualIndex >= rows.size()) {
            return null;
        }

        int rowTop = listY + rowOffset * TOPIC_ROW_HEIGHT;
        SkillVisualRow row = rows.get(visualIndex);
        if (row.kind() == SkillVisualRowKind.TWO_COLUMN && row.right() != null) {
            int leftW = Math.max(40, (listW - ACTIONS_TWO_COLUMN_GAP) / 2);
            int rightX = listX + leftW + ACTIONS_TWO_COLUMN_GAP;
            int rightW = Math.max(40, listW - leftW - ACTIONS_TWO_COLUMN_GAP);
            Rect leftRect = new Rect(listX, rowTop, leftW, TOPIC_ROW_HEIGHT);
            Rect rightRect = new Rect(rightX, rowTop, rightW, TOPIC_ROW_HEIGHT);
            if (rightRect.contains(mouseX, mouseY)) {
                return new SkillEntryHit(row.right(), rightRect);
            }
            if (leftRect.contains(mouseX, mouseY)) {
                return new SkillEntryHit(row.left(), leftRect);
            }
            return null;
        }

        Rect fullRect = new Rect(listX, rowTop, listW, TOPIC_ROW_HEIGHT);
        return fullRect.contains(mouseX, mouseY) ? new SkillEntryHit(row.left(), fullRect) : null;
    }

    private Rect getSkillEntryRectInOverlay(TopicAction action) {
        if (overlayCategory != TopicCategory.SKILL || action == null) {
            return null;
        }
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;
        int contentY = r.y + headerH + 2;
        int contentH = (footerY - 2) - contentY;

        OverlayColumns cols = computeOverlayColumns(r);
        int listX = getOverlayListX(cols);
        int listY = getOverlayRowsStartY(cols.contentY);
        int listW = getOverlayListContentWidth(cols);
        int listH = getOverlayRowsHeight(contentH);
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        List<SkillVisualRow> rows = buildSkillVisualRows(entries);
        clampOverlayScroll(visibleRows);

        for (int i = 0; i < visibleRows; i++) {
            int visualIndex = skillScrollIndex + i;
            if (visualIndex < 0 || visualIndex >= rows.size()) {
                break;
            }
            int rowTop = listY + i * TOPIC_ROW_HEIGHT;
            SkillVisualRow row = rows.get(visualIndex);
            if (row.kind() == SkillVisualRowKind.TWO_COLUMN && row.right() != null) {
                int leftW = Math.max(40, (listW - ACTIONS_TWO_COLUMN_GAP) / 2);
                int rightX = listX + leftW + ACTIONS_TWO_COLUMN_GAP;
                int rightW = Math.max(40, listW - leftW - ACTIONS_TWO_COLUMN_GAP);
                if (row.left() != null && row.left().action == action) {
                    return new Rect(listX, rowTop, leftW, TOPIC_ROW_HEIGHT);
                }
                if (row.right().action == action) {
                    return new Rect(rightX, rowTop, rightW, TOPIC_ROW_HEIGHT);
                }
            } else if (row.left() != null && row.left().action == action) {
                return new Rect(listX, rowTop, listW, TOPIC_ROW_HEIGHT);
            }
        }
        return null;
    }

    private Rect computeOverlayListScrollbarThumb(Rect track, int totalRows, int visibleRows) {
        if (track == null || totalRows <= visibleRows || visibleRows <= 0) {
            return null;
        }
        int maxScroll = Math.max(1, totalRows - visibleRows);
        int thumbH = Math.max(TOPICS_OVERLAY_SCROLLBAR_MIN_THUMB_H, (track.h * visibleRows) / Math.max(1, totalRows));
        thumbH = Math.min(track.h, thumbH);
        int range = Math.max(0, track.h - thumbH);
        int scroll = MathHelper.clamp(getOverlayScrollIndex(), 0, maxScroll);
        int thumbY = track.y + (range <= 0 ? 0 : (int) Math.round((double) range * ((double) scroll / (double) maxScroll)));
        return new Rect(track.x, thumbY, track.w, thumbH);
    }

    private void updateOverlayScrollFromThumb(net.minecraft.client.gui.Click click, Rect track, Rect thumb, int totalRows, int visibleRows) {
        if (click == null || track == null || thumb == null || totalRows <= visibleRows || visibleRows <= 0) {
            return;
        }
        int maxScroll = Math.max(0, totalRows - visibleRows);
        if (maxScroll == 0) {
            setOverlayScrollIndex(0);
            return;
        }
        int minY = track.y;
        int maxY = track.bottom() - thumb.h;
        if (maxY <= minY) {
            setOverlayScrollIndex(0);
            return;
        }
        int desiredY = (int) click.y() - overlayListScrollGrabOffsetY;
        desiredY = MathHelper.clamp(desiredY, minY, maxY);
        double ratio = (double) (desiredY - minY) / (double) (maxY - minY);
        int nextScroll = (int) Math.round(ratio * maxScroll);
        setOverlayScrollIndex(MathHelper.clamp(nextScroll, 0, maxScroll));
    }

    private void drawTopicsOverlay(DrawContext context, int mouseX, int mouseY) {
        Rect r = computeTopicsOverlayRect();
        int border = 0xFF000000;
        // Opaque fill so underlying inventory/stats don't visually compete with the overlay.
        int fill = 0xFF101010;

        // Panel background + border.
        context.fill(r.x, r.y, r.right(), r.bottom(), fill);
        context.fill(r.x, r.y, r.right(), r.y + 1, border);
        context.fill(r.x, r.bottom() - 1, r.right(), r.bottom(), border);
        context.fill(r.x, r.y, r.x + 1, r.bottom(), border);
        context.fill(r.right() - 1, r.y, r.right(), r.bottom(), border);

        // Header.
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        context.fill(r.x + 1, r.y + 1, r.right() - 1, r.y + headerH, 0xFF161616);
        context.drawText(this.textRenderer, getOverlayHeaderTitle(), r.x + TOPICS_OVERLAY_PADDING, r.y + TOPICS_OVERLAY_HEADER_PAD + 1, 0xFFFFE08A, false);

        // Close box (top-right).
        String closeLabel = "X";
        int closeSize = 12;
        int closeX = r.right() - TOPICS_OVERLAY_PADDING - closeSize;
        int closeY = r.y + (headerH - closeSize) / 2;
        boolean closeHover = mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize;
        context.fill(closeX, closeY, closeX + closeSize, closeY + closeSize, closeHover ? 0xFF2A2A2A : 0xFF1A1A1A);
        int closeTextX = closeX + (closeSize - this.textRenderer.getWidth(closeLabel)) / 2;
        context.drawText(this.textRenderer, closeLabel, closeTextX, closeY + 2, 0xFFEFEFEF, false);

        // "Guide" button (left of X) — only in guide Admin-only mode.
        if (guideRemoteOpen && !guideRemoteFullAccess) {
            String guideLabel = "Guide";
            int guideLabelW = this.textRenderer.getWidth(guideLabel);
            int guideBtnW = guideLabelW + 8;
            int guideBtnH = closeSize;
            int guideBtnX = closeX - guideBtnW - 4;
            int guideBtnY = closeY;
            boolean guideHover = mouseX >= guideBtnX && mouseX < guideBtnX + guideBtnW
                    && mouseY >= guideBtnY && mouseY < guideBtnY + guideBtnH;
            context.fill(guideBtnX, guideBtnY, guideBtnX + guideBtnW, guideBtnY + guideBtnH,
                    guideHover ? 0xFF2A2A2A : 0xFF1A1A1A);
            context.drawText(this.textRenderer, guideLabel,
                    guideBtnX + (guideBtnW - guideLabelW) / 2, guideBtnY + 2, 0xFFB0B0B0, false);
        }

        drawOverlayBotSwitchControls(context, r, closeX, mouseX, mouseY);

        // Footer hint.
        String hint = getOverlayFooterHint();
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int footerY = r.bottom() - footerH;
        context.fill(r.x + 1, footerY, r.right() - 1, r.bottom() - 1, 0xFF161616);
        context.drawText(this.textRenderer, hint, r.x + TOPICS_OVERLAY_PADDING, footerY + TOPICS_OVERLAY_FOOTER_PAD, 0xFFB0B0B0, false);

        // Content region (two columns).
        OverlayColumns cols = computeOverlayColumns(r);

        if (!isActionsOverlayFullWidth()) {
            // Dialogue/Admin keep the transcript column visible.
            drawDialogueColumn(context, cols.dialogueX, cols.contentY, cols.dialogueW, cols.contentH);

            // Divider (draggable).
            boolean dividerHover = cols.dividerRect.contains(mouseX, mouseY);
            int dividerFill = dividerHover || overlayDraggingSplit ? 0xFF141414 : 0xFF101010;
            context.fill(cols.dividerX, cols.contentY, cols.dividerX + cols.dividerW, cols.contentY + cols.contentH, dividerFill);
            int dividerLineX = cols.dividerX + cols.dividerW / 2;
            int dividerLineColor = dividerHover || overlayDraggingSplit ? 0xFFB08C40 : 0xFF303030;
            context.fill(dividerLineX, cols.contentY + 2, dividerLineX + 1, cols.contentY + cols.contentH - 2, dividerLineColor);
        }

        // Topics list region.
        int listX = getOverlayListX(cols);
        int listY = cols.contentY;
        int listW = getOverlayListW(cols);
        int listH = cols.contentH;

        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        List<SkillVisualRow> skillRows = overlayCategory == TopicCategory.SKILL ? buildSkillVisualRows(entries) : null;
        int rowsHeight = getOverlayRowsHeight(listH);
        int visibleRows = Math.max(1, rowsHeight / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);
        int listContentW = getOverlayListContentWidth(cols);
        int listStartY = getOverlayRowsStartY(listY);
        Rect scrollTrack = computeOverlayListScrollbarTrack(listX, listStartY, listW, rowsHeight);
        int totalRows = skillRows != null ? skillRows.size() : entries.size();
        Rect scrollThumb = computeOverlayListScrollbarThumb(scrollTrack, totalRows, visibleRows);

        // Tabs: when Spells is active, show "Spells" text (4 equal tabs).
        // Otherwise, show ✦ icon (compact) so text tabs get more room.
        int tabY = listY + 2;
        int tabH = TOPICS_OVERLAY_LIST_HEADER_H - 4;
        int tabGap = 2;
        boolean spellsActive = overlayCategory == TopicCategory.SPELL;
        String spellsLabel = spellsActive ? "Spells" : "\u2726";
        boolean guideRestricted = guideRemoteOpen && !guideRemoteFullAccess;

        int spellsTabW, tabW;
        if (spellsActive) {
            // 4 equal-width text tabs.
            tabW = Math.max(30, (listW - 4 - 3 * tabGap) / 4);
            spellsTabW = tabW;
        } else {
            // Compact icon tab; 3 text tabs share the rest.
            spellsTabW = this.textRenderer.getWidth("\u2726") + 10;
            int textTabsSpace = listW - 4 - spellsTabW - 3 * tabGap;
            tabW = Math.max(30, textTabsSpace / 3);
        }
        int skillsTabX = listX + 2;
        int dialogueTabX = skillsTabX + tabW + tabGap;
        int spellsTabX = dialogueTabX + tabW + tabGap;
        int adminTabX = spellsTabX + spellsTabW + tabGap;
        drawOverlayTab(context, skillsTabX, tabY, tabW, tabH, TOPIC_PANEL_TITLE, overlayCategory == TopicCategory.SKILL, !guideRestricted);
        drawOverlayTab(context, dialogueTabX, tabY, tabW, tabH, "Dialogue", overlayCategory == TopicCategory.DIALOGUE, !guideRestricted);
        drawOverlayTab(context, spellsTabX, tabY, spellsTabW, tabH, spellsLabel, spellsActive, true);
        // Spells tab tooltip on hover (only when showing icon).
        if (!spellsActive && mouseX >= spellsTabX && mouseX < spellsTabX + spellsTabW
                && mouseY >= tabY && mouseY < tabY + tabH) {
            spellsTabHovered = true;
            if (spellsTabHoverStartMs == 0L) spellsTabHoverStartMs = System.currentTimeMillis();
        } else {
            spellsTabHovered = false;
            spellsTabHoverStartMs = 0L;
        }
        drawOverlayTab(context, adminTabX, tabY, tabW, tabH, "Admin", overlayCategory == TopicCategory.ADMIN, isAdminTabEnabled());

        Rect searchRect = computeOverlaySearchRect(listX, listY, listW);
        drawOverlaySearchBox(context, searchRect, mouseX, mouseY);

        // Clip to list area.
        context.enableScissor(listX, listStartY, listX + listContentW, listStartY + rowsHeight);
        for (int i = 0; i < visibleRows; i++) {
            int rowY = listStartY + i * TOPIC_ROW_HEIGHT;
            if (overlayCategory == TopicCategory.SKILL) {
                int visualIndex = getOverlayScrollIndex() + i;
                if (skillRows == null || visualIndex >= skillRows.size()) {
                    break;
                }
                drawSkillVisualRow(context, listX, rowY, listContentW, skillRows.get(visualIndex), mouseX, mouseY);
            } else {
                int entryIndex = getOverlayScrollIndex() + i;
                if (entryIndex >= entries.size()) {
                    break;
                }
                TopicEntry entry = entries.get(entryIndex);
                boolean active = entry.action != null && isEntryActive(entry.action);
                drawTopicRow(context, listX, rowY, listContentW, entry, active, mouseX, mouseY);
            }
        }
        context.disableScissor();

        if (scrollTrack != null) {
            context.fill(scrollTrack.x, scrollTrack.y, scrollTrack.right(), scrollTrack.bottom(), 0xFF161616);
            context.fill(scrollTrack.x, scrollTrack.y, scrollTrack.right(), scrollTrack.y + 1, 0xFF2C2C2C);
            context.fill(scrollTrack.x, scrollTrack.bottom() - 1, scrollTrack.right(), scrollTrack.bottom(), 0xFF2C2C2C);
            context.fill(scrollTrack.x, scrollTrack.y, scrollTrack.x + 1, scrollTrack.bottom(), 0xFF2C2C2C);
            context.fill(scrollTrack.right() - 1, scrollTrack.y, scrollTrack.right(), scrollTrack.bottom(), 0xFF2C2C2C);
        }
        if (scrollThumb != null) {
            boolean thumbHover = scrollThumb.contains(mouseX, mouseY);
            int thumbFill = (thumbHover || overlayDraggingListScroll) ? 0xFFB08C40 : 0xFF7A6240;
            context.fill(scrollThumb.x + 1, scrollThumb.y + 1, scrollThumb.right() - 1, scrollThumb.bottom() - 1, thumbFill);
        }

        // Delayed hover tooltip (after scissor, so it can draw over the list cleanly).
        drawOverlayHoverTooltip(context, mouseX, mouseY);

        // Spells tab icon tooltip (after main tooltips so it draws on top).
        if (spellsTabHovered && spellsTabHoverStartMs > 0L
                && System.currentTimeMillis() - spellsTabHoverStartMs >= OVERLAY_HOVER_TOOLTIP_DELAY_MS) {
            drawTooltipBox(context, mouseX, mouseY, java.util.List.of("Spells"));
        }
    }

    private void drawOverlaySearchBox(DrawContext context, Rect searchRect, int mouseX, int mouseY) {
        if (searchRect == null) {
            return;
        }
        boolean hover = searchRect.contains(mouseX, mouseY);
        int fill = topicSearchFocused ? 0xFF262015 : (hover ? 0xFF1F1F1F : 0xFF171717);
        int border = topicSearchFocused ? 0xFFB08C40 : 0xFF2A2A2A;
        context.fill(searchRect.x, searchRect.y, searchRect.right(), searchRect.bottom(), fill);
        context.fill(searchRect.x, searchRect.y, searchRect.right(), searchRect.y + 1, border);
        context.fill(searchRect.x, searchRect.bottom() - 1, searchRect.right(), searchRect.bottom(), border);
        context.fill(searchRect.x, searchRect.y, searchRect.x + 1, searchRect.bottom(), border);
        context.fill(searchRect.right() - 1, searchRect.y, searchRect.right(), searchRect.bottom(), border);

        String query = topicSearchQuery != null ? topicSearchQuery : "";
        boolean showCursor = topicSearchFocused && ((System.currentTimeMillis() / 500L) % 2L == 0L);
        String shown = query.isBlank() ? "Search topics..." : query;
        if (showCursor) {
            shown = shown + "|";
        }
        shown = elideToWidth(shown, Math.max(1, searchRect.w - 8));
        int textColor = query.isBlank() ? COLOR_TEXT_SUBTLE : COLOR_TEXT_PARCHMENT;
        int textY = searchRect.y + Math.max(1, (searchRect.h - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, shown, searchRect.x + 4, textY, textColor, false);
    }

    private void drawOverlayTab(DrawContext context, int x, int y, int w, int h, String label, boolean active, boolean enabled) {
        int fill;
        if (!enabled) {
            fill = 0xFF141414;
        } else {
            fill = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        }
        int border = 0xFF000000;

        context.fill(x, y, x + w, y + h, fill);
        context.fill(x, y, x + w, y + 1, border);
        context.fill(x, y + h - 1, x + w, y + h, border);
        context.fill(x, y, x + 1, y + h, border);
        context.fill(x + w - 1, y, x + w, y + h, border);

        int color;
        if (!enabled) {
            color = 0xFF6F6F6F;
        } else {
            color = active ? 0xFFFFE08A : 0xFFE6D7A3;
        }
        int textX = x + (w - this.textRenderer.getWidth(label)) / 2;
        context.drawText(this.textRenderer, label, textX, y + 2, color, false);
    }

    private boolean isAdminUser() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }

        // Singleplayer host is always admin — they own the world.
        if (client.isInSingleplayer()) {
            return true;
        }

        var player = client.player;
        if (player == null) {
            return false;
        }

        // Match server-side command permission gates (/bot ... requires level 2/op equivalent).
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("hasPermissionLevel", int.class);
            Object r = m.invoke(player, 2);
            if (r instanceof Boolean b) {
                return b;
            }
        } catch (Throwable ignored) {
            // Fall through to stricter fallback below.
        }

        // When permission-level APIs are unavailable in current mappings, fail closed.
        return false;
    }

    private void drawDialogueColumn(DrawContext context, int x, int y, int w, int h) {
        // Updated each frame while the overlay is open; used for click hit-testing.
        dialogueResponseHitboxes.clear();

        // Background.
        context.fill(x, y, x + w, y + h, 0x55101010);
        context.fill(x, y, x + w, y + 1, 0xFF000000);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF000000);
        context.fill(x, y, x + 1, y + h, 0xFF000000);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF000000);

        // Topic header (Morrowind-style).
        String topicHeader = (lastDialogueTopicLabel != null && !lastDialogueTopicLabel.isBlank())
            ? lastDialogueTopicLabel
            : "Select a topic";
        context.drawText(this.textRenderer, topicHeader, x + 4, y + 2, COLOR_TEXT_TOPIC, false);

        int textX = x + 4;
        int textY = y + 2 + this.textRenderer.fontHeight + 4;
        int textW = Math.max(40, w - 8);

        // Player response prompts (in red), pinned to the bottom of the dialogue column.
        // Only show responses once a dialogue topic is active; selecting a topic itself is not a player line.
        boolean topicActive = lastDialogueTopicKey != null
            && !lastDialogueTopicKey.isBlank()
            && !lastDialogueTopicKey.trim().equalsIgnoreCase("goodbye");
        java.util.List<TopicEntry> responses = (overlayCategory == TopicCategory.DIALOGUE && topicActive)
            ? getDialogueResponseEntries(6)
            : java.util.List.of();
        int respLineH = this.textRenderer.fontHeight;
        int respCount = responses.size();
        int respPad = 4;
        int respAreaH = respCount > 0 ? (respCount * respLineH + respPad) : 0;
        int respStartY = y + h - respAreaH;

        // Leave room for the response area.
        int textH = Math.max(20, (respAreaH > 0 ? (respStartY - 2) : (y + h)) - textY);

        java.util.List<String> allLines = net.wcfcarolina13.FrensClient.getDialogueLines(botAlias, 18);
        // When on the Dialogue tab, hide admin action logs so they don't mix with quest conversation.
        java.util.List<String> lines;
        if (overlayCategory == TopicCategory.DIALOGUE) {
            lines = new java.util.ArrayList<>();
            for (String l : allLines) {
                if (l != null) {
                    String trimmed = l.trim();
                    if (trimmed.startsWith("Admin:") || trimmed.startsWith("You (Admin):")) {
                        continue;
                    }
                }
                lines.add(l);
            }
        } else {
            lines = allLines;
        }
        java.util.List<StyledLine> wrapped = new java.util.ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            int color = getDialogueLineColor(line);
            String display = formatDialogueLineForDisplay(line);
            if (display == null || display.isBlank()) continue;
            var segments = this.textRenderer.wrapLines(net.minecraft.text.Text.literal(display), textW);
            for (var seg : segments) {
                wrapped.add(new StyledLine(seg, color));
            }
        }

        int maxLines = Math.max(1, textH / this.textRenderer.fontHeight);
        int start = Math.max(0, wrapped.size() - maxLines);
        int rowY = textY;
        for (int i = start; i < wrapped.size(); i++) {
            StyledLine line = wrapped.get(i);
            context.drawText(this.textRenderer, line.text(), textX, rowY, line.color(), false);
            rowY += this.textRenderer.fontHeight;
            if (rowY >= textY + textH) break;
        }

        // Draw response prompts at the bottom (red) and record hitboxes.
        if (respCount > 0) {
            // subtle divider
            int divY = respStartY - 2;
            if (divY > y + 2) {
                context.fill(x + 2, divY, x + w - 2, divY + 1, 0xFF222222);
            }

            int ry = respStartY + 2;
            for (TopicEntry e : responses) {
                if (e == null) continue;
                String prompt = getDialoguePrompt(e.dialogueKey, e.label);
                String text = prompt != null ? prompt : (e.label != null ? e.label : "");
                text = elideToWidth(text, textW);
                context.drawText(this.textRenderer, text, textX, ry, COLOR_TEXT_RESPONSE, false);
                int tw = this.textRenderer.getWidth(text);
                dialogueResponseHitboxes.add(new DialogueResponseHitbox(new Rect(textX, ry, Math.max(1, tw), respLineH), e));
                ry += respLineH;
            }
        }
    }

    private record StyledLine(net.minecraft.text.OrderedText text, int color) {}

    private int getDialogueLineColor(String line) {
        if (line == null) {
            return COLOR_TEXT_PARCHMENT;
        }
        String s = line.trim();
        if (s.isEmpty()) {
            return COLOR_TEXT_PARCHMENT;
        }
        String botPrefix = (botAlias != null && !botAlias.isBlank()) ? (botAlias + ":") : null;
        if (s.startsWith("You:")) {
            return COLOR_TEXT_RESPONSE;
        }
        if (s.startsWith("You (")) {
            return COLOR_TEXT_SYSTEM;
        }
        if (s.startsWith("Admin:")) {
            return COLOR_TEXT_SYSTEM;
        }
        if (botPrefix != null && s.startsWith(botPrefix)) {
            return COLOR_TEXT_PARCHMENT;
        }
        if (s.startsWith("...")) {
            return COLOR_TEXT_SUBTLE;
        }
        return COLOR_TEXT_PARCHMENT;
    }

    private void toggleTopicsExpanded(boolean open) {
        this.topicsExpanded = open;
        if (!open) {
            overlayDraggingSplit = false;
            overlayDraggingListScroll = false;
            clearHeldAdjust();
        }
        if (!open) {
            topicSearchFocused = false;
            cancelDirectInput();
            companionQuestStateRequested = false;
            adminResetConfirmArmed = false;
            adminResetConfirmArmedAtMs = 0L;
            return;
        }

        if (!companionQuestStateRequested) {
            requestCompanionQuestState();
        }
        if (isAdminUser()) {
            requestAdminPermissionsSnapshot(this.botAlias);
        }

        // Ensure the scroll is valid for the (larger) overlay view.
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
        int contentY = r.y + headerH + 2;
        int footerY = r.bottom() - footerH;
        int contentH = (footerY - 2) - contentY;
        int listH = Math.max(1, contentH);
        int visibleRows = Math.max(1, getOverlayRowsHeight(listH) / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);
    }

    private boolean clickTopicsOverlay(net.minecraft.client.gui.Click click) {
        double mouseX = click.x();
        double mouseY = click.y();
        boolean guideRestricted = guideRemoteOpen && !guideRemoteFullAccess;
        Rect r = computeTopicsOverlayRect();
        if (!r.contains(mouseX, mouseY)) {
            if (guideRestricted) {
                this.close(); // Admin-only mode: clicking outside closes the screen entirely.
            } else {
                toggleTopicsExpanded(false);
            }
            return true;
        }

        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int closeSize = 12;
        int closeX = r.right() - TOPICS_OVERLAY_PADDING - closeSize;
        int closeY = r.y + (headerH - closeSize) / 2;
        if (mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize) {
            if (guideRestricted) {
                this.close(); // Admin-only mode: X closes the screen entirely.
            } else {
                toggleTopicsExpanded(false);
            }
            return true;
        }

        // "Guide" button (left of X) — navigate back to guide screen.
        if (guideRestricted) {
            String guideLabel = "Guide";
            int guideLabelW = this.textRenderer.getWidth(guideLabel);
            int guideBtnW = guideLabelW + 8;
            int guideBtnH = closeSize;
            int guideBtnX = closeX - guideBtnW - 4;
            int guideBtnY = closeY;
            if (mouseX >= guideBtnX && mouseX < guideBtnX + guideBtnW
                    && mouseY >= guideBtnY && mouseY < guideBtnY + guideBtnH) {
                openGuideMenu();
                return true;
            }
        }

        BotSwitchLayout overlaySwitch = computeOverlayBotSwitchLayout(r, closeX);
        if (handleBotSwitchClick(overlaySwitch, mouseX, mouseY)) {
            return true;
        }

        OverlayColumns cols = computeOverlayColumns(r);

        // Divider drag start.
        if (!isActionsOverlayFullWidth() && click.button() == 0 && cols.dividerRect.contains(mouseX, mouseY)) {
            overlayDraggingSplit = true;
            updateOverlaySplitFromMouse(mouseX);
            return true;
        }

        // Tabs (Skills / Dialogue / ✦ or Spells / Admin).
        int listX = getOverlayListX(cols);
        int listY = cols.contentY;
        int listW = getOverlayListW(cols);

        int tabY = listY + 2;
        int tabH = TOPICS_OVERLAY_LIST_HEADER_H - 4;
        int tabGap = 2;
        boolean spellsActive = overlayCategory == TopicCategory.SPELL;
        int spellsTabW, tabW;
        if (spellsActive) {
            tabW = Math.max(30, (listW - 4 - 3 * tabGap) / 4);
            spellsTabW = tabW;
        } else {
            spellsTabW = this.textRenderer.getWidth("\u2726") + 10;
            int textTabsSpace = listW - 4 - spellsTabW - 3 * tabGap;
            tabW = Math.max(30, textTabsSpace / 3);
        }
        int skillsTabX = listX + 2;
        int dialogueTabX = skillsTabX + tabW + tabGap;
        int spellsTabX = dialogueTabX + tabW + tabGap;
        int adminTabX = spellsTabX + spellsTabW + tabGap;

        if (mouseY >= tabY && mouseY < tabY + tabH) {
            if (mouseX >= skillsTabX && mouseX < skillsTabX + tabW) {
                if (guideRestricted) return true; // Admin-only mode — block Actions tab
                overlayCategory = TopicCategory.SKILL;
                overlayDraggingSplit = false;
                return true;
            }
            if (mouseX >= dialogueTabX && mouseX < dialogueTabX + tabW) {
                if (guideRestricted) return true; // Admin-only mode — block Dialogue tab
                overlayCategory = TopicCategory.DIALOGUE;
                if (!companionQuestStateRequested) {
                    requestCompanionQuestState();
                }
                return true;
            }
            if (mouseX >= spellsTabX && mouseX < spellsTabX + spellsTabW) {
                // Spells tab is always accessible — spells grey out individually.
                overlayCategory = TopicCategory.SPELL;
                return true;
            }
            if (mouseX >= adminTabX && mouseX < adminTabX + tabW) {
                if (isAdminTabEnabled()) {
                    overlayCategory = TopicCategory.ADMIN;
                    if (isAdminUser()) {
                        requestAdminPermissionsSnapshot(this.botAlias);
                    }
                    return true;
                }
                return false;
            }
        }

        Rect searchRect = computeOverlaySearchRect(listX, listY, listW);
        if (searchRect.contains(mouseX, mouseY)) {
            topicSearchFocused = true;
            cancelDirectInput();
            return true;
        }
        topicSearchFocused = false;

        int rowsHeight = getOverlayRowsHeight(cols.contentH);
        int visibleRows = Math.max(1, rowsHeight / TOPIC_ROW_HEIGHT);
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        int totalRows = getOverlayVisualRowCount(entries);
        Rect scrollTrack = computeOverlayListScrollbarTrack(listX, getOverlayRowsStartY(listY), listW, rowsHeight);
        Rect scrollThumb = computeOverlayListScrollbarThumb(scrollTrack, totalRows, visibleRows);
        if (scrollTrack != null && scrollTrack.contains(mouseX, mouseY) && click.button() == 0) {
            if (scrollThumb != null && scrollThumb.contains(mouseX, mouseY)) {
                overlayDraggingListScroll = true;
                overlayListScrollGrabOffsetY = (int) mouseY - scrollThumb.y;
                return true;
            }
            if (scrollThumb != null) {
                int page = Math.max(1, visibleRows - 1);
                int delta = mouseY < scrollThumb.y ? -page : page;
                setOverlayScrollIndex(MathHelper.clamp(getOverlayScrollIndex() + delta, 0, Math.max(0, totalRows - visibleRows)));
                return true;
            }
            return true;
        }

        // Follow +/- controls.
        if (overlayCategory == TopicCategory.SKILL) {
            int adjust = getFollowAdjustDirectionInOverlay(mouseX, mouseY);
            if (adjust != 0) {
                adjustFollowDistance(adjust);
                beginHeldAdjust(() -> adjustFollowDistance(adjust));
                return true;
            }
            int woodcutAdjust = getWoodcutAdjustDirectionInOverlay(mouseX, mouseY);
            if (woodcutAdjust != 0) {
                adjustWoodcutTreeCount(woodcutAdjust);
                beginHeldAdjust(() -> adjustWoodcutTreeCount(woodcutAdjust));
                return true;
            }
            SkillAdjustHit skillAdjust = getAdjustableSkillHitInOverlay(mouseX, mouseY);
            if (skillAdjust != null) {
                applySkillAdjust(skillAdjust);
                if (skillAdjust.control() != SkillAdjustControl.TOGGLE_MODE) {
                    beginHeldAdjust(() -> applySkillAdjust(skillAdjust));
                }
                return true;
            }

            // Double-click on value label → direct numeric input.
            TopicAction valueLabelHit = getValueLabelHitInOverlay(mouseX, mouseY);
            if (valueLabelHit != null) {
                long now = System.currentTimeMillis();
                if (lastValueLabelClickAction == valueLabelHit
                        && (now - lastValueLabelClickMs) <= DOUBLE_CLICK_MS) {
                    activateDirectInput(valueLabelHit);
                    lastValueLabelClickAction = null;
                    lastValueLabelClickMs = 0L;
                } else {
                    lastValueLabelClickAction = valueLabelHit;
                    lastValueLabelClickMs = now;
                }
                return true;
            }
        }

        // If direct input is active and the click landed outside the value label, close it.
        if (directInputAction != null) {
            commitDirectInput();
        }

        // Action control box hit test — only fires when the click lands on the
        // explicit ON/OFF or ▸ button, not the row label area.
        TopicEntry controlHit = getActionControlHitInOverlay(mouseX, mouseY);
        if (controlHit != null) {
            handleTopicEntry(controlHit);
            return true;
        }

        // Dialogue response prompts (left column). Only active when the Dialogue tab is selected.
        if (overlayCategory == TopicCategory.DIALOGUE) {
            TopicEntry response = getDialogueResponseEntryAtOverlay(mouseX, mouseY);
            if (response != null) {
                // Red responses are explicit player dialogue lines.
                handleDialogueTopicEntry(response, true);
                return true;
            }
        }

        TopicEntry entry = getTopicEntryAtOverlay(mouseX, mouseY);
        if (entry != null) {
            if ((entry.category == TopicCategory.ADMIN && isAdminHeaderEntry(entry))
                    || (entry.category == TopicCategory.SKILL && isSkillHeaderEntry(entry))) {
                return true;
            }
            // Entries with explicit control boxes are only actionable via the
            // control box click (handled above). Row clicks consume the event
            // but do NOT execute the action.
            if (hasActionControlBox(entry)) {
                return true;
            }
            boolean enabled;
            if (entry.category == TopicCategory.DIALOGUE) {
                enabled = isDialogueEntryEnabled(entry);
            } else if (entry.category == TopicCategory.ADMIN) {
                enabled = isAdminEntryEnabled(entry);
            } else if (entry.category == TopicCategory.SPELL) {
                enabled = isSpellEntryEnabled(entry);
            } else {
                enabled = (entry.action == null || isEntryEnabled(entry.action));
            }
            if (enabled) {
                handleTopicEntry(entry);
                return true;
            }
            return true;
        }
        return true;
    }

    private void updateOverlaySplitFromMouse(double mouseX) {
        Rect r = computeTopicsOverlayRect();
        OverlayColumns cols = computeOverlayColumns(r);

        int dividerW = cols.dividerW;
        int availableW = Math.max(1, cols.contentW - dividerW);
        int minDialogueW = 160;
        int minListW = 140;
        int minDialogue = Math.min(minDialogueW, availableW);
        int maxDialogue = Math.max(minDialogue, availableW - minListW);

        double desiredDialogue = mouseX - cols.contentX - (dividerW / 2.0);
        int dialogueW = (int) Math.round(desiredDialogue);
        dialogueW = MathHelper.clamp(dialogueW, minDialogue, maxDialogue);
        overlaySplitRatio = MathHelper.clamp((double) dialogueW / (double) availableW, 0.0, 1.0);
    }

    private TopicEntry getTopicEntryAtOverlay(double mouseX, double mouseY) {
        if (overlayCategory == TopicCategory.SKILL) {
            SkillEntryHit hit = getSkillEntryHitAtOverlay(mouseX, mouseY);
            return hit != null ? hit.entry() : null;
        }
        Rect r = computeTopicsOverlayRect();
        int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
        int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;

        int footerY = r.bottom() - footerH;
        int contentY = r.y + headerH + 2;
        int contentH = (footerY - 2) - contentY;

        OverlayColumns cols = computeOverlayColumns(r);
        int listX = getOverlayListX(cols);
        int listY = getOverlayRowsStartY(cols.contentY);
        int listW = getOverlayListContentWidth(cols);
        int listH = getOverlayRowsHeight(contentH);
        int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
        clampOverlayScroll(visibleRows);
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());

        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return null;
        }
        int rowIndex = (int) ((mouseY - listY) / TOPIC_ROW_HEIGHT);
        if (rowIndex < 0 || rowIndex >= visibleRows) {
            return null;
        }
        int entryIndex = getOverlayScrollIndex() + rowIndex;
        if (entryIndex < 0 || entryIndex >= entries.size()) {
            return null;
        }
        return entries.get(entryIndex);
    }

    private enum SkillAdjustControl {
        INCREMENT,
        DECREMENT,
        TOGGLE_MODE
    }

    private record SkillAdjustHit(TopicAction action, SkillAdjustControl control) {}

    private record OverlayControlHover(String key, java.util.List<String> lines) {}

    private int getFollowAdjustDirectionInOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return 0;
        }
        Rect rowRect = getSkillEntryRectInOverlay(TopicAction.FOLLOW);
        if (rowRect == null) {
            return 0;
        }
        int labelX = getSkillLabelStartX(rowRect.x, TopicAction.FOLLOW, 0);
        String status = isFollowActive() ? "ON" : "OFF";
        String distanceLabel = formatFollowDistance();
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowRect.y + 1;
        int controlRight = getCompactControlRight(
                rowRect.x,
                rowRect.w,
                labelX,
                this.textRenderer.getWidth(distanceLabel) + TOPIC_CONTROL_GAP + this.textRenderer.getWidth(status),
                2
        );
        int plusX = controlRight - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        if (mouseY >= controlY && mouseY < controlY + controlSize) {
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return 1;
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return -1;
            }
        }
        return 0;
    }

    private int getWoodcutAdjustDirectionInOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return 0;
        }
        Rect rowRect = getSkillEntryRectInOverlay(TopicAction.SKILL_WOODCUT);
        if (rowRect == null) {
            return 0;
        }
        int labelX = getSkillLabelStartX(rowRect.x, TopicAction.SKILL_WOODCUT, 0);
        String countLabel = "Trees " + woodcutTreeCount;
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowRect.y + 1;
        int controlRight = getCompactControlRight(rowRect.x, rowRect.w, labelX, this.textRenderer.getWidth(countLabel), 2);
        int plusX = controlRight - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;

        if (mouseY >= controlY && mouseY < controlY + controlSize) {
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return 1;
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return -1;
            }
        }
        return 0;
    }

    /**
     * Returns true if the given entry should be controlled via an explicit action
     * control box (ON/OFF or ▸) rather than a full-row click.
     */
    private boolean hasActionControlBox(TopicEntry entry) {
        if (entry == null || entry.category == TopicCategory.DIALOGUE) {
            return false;
        }
        if (entry.action == null) {
            return false;
        }
        // Headers have no control box.
        if (isAdminHeaderEntry(entry) || isSkillHeaderEntry(entry)) {
            return false;
        }
        // Adjustable skills have their own +/- controls, not action control boxes.
        if (entry.action == TopicAction.FOLLOW || entry.action == TopicAction.SKILL_WOODCUT
                || isAdjustableSkillAction(entry.action)) {
            return false;
        }
        // Entry must be enabled to have an active control box.
        if (entry.category == TopicCategory.ADMIN) {
            return isAdminEntryEnabled(entry);
        }
        if (entry.category == TopicCategory.SPELL) {
            return isSpellEntryEnabled(entry);
        }
        return isEntryEnabled(entry.action);
    }

    /**
     * Hit-tests the explicit action control box (ON/OFF toggle or ▸ button) for the
     * entry under the mouse. Returns the TopicEntry if the click lands on its control
     * box, null otherwise.
     */
    private TopicEntry getActionControlHitInOverlay(double mouseX, double mouseY) {
        if (overlayCategory == TopicCategory.SKILL) {
            SkillEntryHit hit = getSkillEntryHitAtOverlay(mouseX, mouseY);
            if (hit == null || hit.entry() == null) {
                return null;
            }
            TopicEntry entry = hit.entry();
            if (!hasActionControlBox(entry)) {
                return null;
            }
            Rect rowRect = hit.rect();
            return isClickOnActionTarget(mouseX, mouseY, rowRect, entry) ? entry : null;
        }

        if (overlayCategory == TopicCategory.ADMIN || overlayCategory == TopicCategory.SPELL) {
            // Compute the entry and its row rect at the mouse position.
            Rect r = computeTopicsOverlayRect();
            int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
            int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
            int footerY = r.bottom() - footerH;
            int contentY = r.y + headerH + 2;
            int contentH = (footerY - 2) - contentY;

            OverlayColumns cols = computeOverlayColumns(r);
            int listX = getOverlayListX(cols);
            int listY = getOverlayRowsStartY(cols.contentY);
            int listW = getOverlayListContentWidth(cols);
            int listH = getOverlayRowsHeight(contentH);
            int visibleRows = Math.max(1, listH / TOPIC_ROW_HEIGHT);
            clampOverlayScroll(visibleRows);
            List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());

            if (mouseX < listX || mouseX >= listX + listW
                    || mouseY < listY || mouseY >= listY + listH) {
                return null;
            }
            int rowIndex = (int) ((mouseY - listY) / TOPIC_ROW_HEIGHT);
            if (rowIndex < 0 || rowIndex >= visibleRows) {
                return null;
            }
            int entryIndex = getOverlayScrollIndex() + rowIndex;
            if (entryIndex < 0 || entryIndex >= entries.size()) {
                return null;
            }
            TopicEntry entry = entries.get(entryIndex);
            if (!hasActionControlBox(entry)) {
                return null;
            }
            int rowTop = listY + rowIndex * TOPIC_ROW_HEIGHT;
            Rect rowRect = new Rect(listX, rowTop, listW, TOPIC_ROW_HEIGHT);
            return isClickOnActionTarget(mouseX, mouseY, rowRect, entry) ? entry : null;
        }

        return null;
    }

    /**
     * Checks whether the mouse position falls within the control box area of a row.
     * Uses the same geometry as drawTopicRow's control box rendering.
     */
    private boolean isClickOnActionTarget(double mouseX, double mouseY, Rect rowRect, TopicEntry entry) {
        int controlSize = TOPIC_ROW_HEIGHT;
        int controlY = rowRect.y;
        int controlBoxRight = rowRect.x + rowRect.w - 2;

        // Check control box (right side).
        if (entry.toggle) {
            String status = toggleStatusLabelForEntry(entry);
            int boxW = this.textRenderer.getWidth(status) + 16;
            int boxX = controlBoxRight - boxW;
            if (mouseX >= boxX && mouseX < boxX + boxW
                    && mouseY >= controlY && mouseY < controlY + controlSize) {
                return true;
            }
        } else {
            int boxW = controlSize + 8;
            int boxX = controlBoxRight - boxW;
            if (mouseX >= boxX && mouseX < boxX + boxW
                    && mouseY >= controlY && mouseY < controlY + controlSize) {
                return true;
            }
        }

        // Check label text area (left side) — title + icon doubles as a button.
        int labelX = rowRect.x + 4 + entry.indent * 8;
        if (entry.category == TopicCategory.SKILL) {
            // Include the icon in the clickable area — start at rowX + 4 (where icon is drawn).
            labelX = rowRect.x + 4;
        }
        String label = displayLabelForEntry(entry);
        int labelEndX;
        if (entry.category == TopicCategory.SKILL) {
            // Icon starts at rowRect.x + 4, label text starts after the icon slot.
            int textStartX = rowRect.x + 4 + SKILL_ICON_SLOT_W + entry.indent * 10;
            labelEndX = textStartX + this.textRenderer.getWidth(label);
        } else {
            labelEndX = labelX + this.textRenderer.getWidth(label);
        }
        if (mouseX >= labelX && mouseX < labelEndX
                && mouseY >= rowRect.y && mouseY < rowRect.y + TOPIC_ROW_HEIGHT) {
            return true;
        }

        return false;
    }

    private SkillAdjustHit getAdjustableSkillHitInOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return null;
        }
        int controlSize = TOPIC_ROW_HEIGHT - 2;

        for (TopicAction action : getAdjustableSkillActions()) {
            Rect rowRect = getSkillEntryRectInOverlay(action);
            if (rowRect == null) {
                continue;
            }
            TopicEntry entry = null;
            for (TopicEntry candidate : SKILL_TOPIC_ENTRIES) {
                if (candidate != null && candidate.action == action) {
                    entry = candidate;
                    break;
                }
            }
            int labelX = getSkillLabelStartX(rowRect.x, action, entry != null ? entry.indent : 0);
            int controlCount = (action == TopicAction.SKILL_ASCENT || action == TopicAction.SKILL_WOOL) ? 3 : 2;
            int controlY = rowRect.y + 1;
            int controlRight = getCompactControlRight(rowRect.x, rowRect.w, labelX, this.textRenderer.getWidth(getAdjustableSkillValueLabel(action)), controlCount);
            int plusX = controlRight - controlSize;
            int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;
            if (mouseY < controlY || mouseY >= controlY + controlSize) {
                continue;
            }
            if (mouseX >= plusX && mouseX < plusX + controlSize) {
                return new SkillAdjustHit(action, SkillAdjustControl.INCREMENT);
            }
            if (mouseX >= minusX && mouseX < minusX + controlSize) {
                return new SkillAdjustHit(action, SkillAdjustControl.DECREMENT);
            }
            if (action == TopicAction.SKILL_ASCENT || action == TopicAction.SKILL_WOOL) {
                int modeX = minusX - TOPIC_CONTROL_GAP - controlSize;
                if (mouseX >= modeX && mouseX < modeX + controlSize) {
                    return new SkillAdjustHit(action, SkillAdjustControl.TOGGLE_MODE);
                }
            }
        }
        return null;
    }

    /**
     * Returns the TopicAction whose value label was clicked, or null.
     * Only matches clicks inside the value cluster box but outside any +/- or toggle control.
     */
    private TopicAction getValueLabelHitInOverlay(double mouseX, double mouseY) {
        if (overlayCategory != TopicCategory.SKILL) {
            return null;
        }
        int controlSize = TOPIC_ROW_HEIGHT - 2;

        // Check woodcut.
        {
            Rect rowRect = getSkillEntryRectInOverlay(TopicAction.SKILL_WOODCUT);
            if (rowRect != null) {
                int labelX = getSkillLabelStartX(rowRect.x, TopicAction.SKILL_WOODCUT, 0);
                String countLabel = "Trees " + woodcutTreeCount;
                int controlRight = getCompactControlRight(rowRect.x, rowRect.w, labelX, this.textRenderer.getWidth(countLabel), 2);
                int plusX = controlRight - controlSize;
                int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;
                int countX = minusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(countLabel);
                if (countX < labelX + 44) countX = labelX + 44;
                int clusterLeft = countX - 4;
                int clusterRight = minusX; // up to but not including the first control
                int controlY = rowRect.y + 1;
                if (mouseX >= clusterLeft && mouseX < clusterRight
                        && mouseY >= controlY && mouseY < controlY + controlSize) {
                    return TopicAction.SKILL_WOODCUT;
                }
            }
        }

        // Check generic adjustable skills (fish, wool, stripmine, ascent, descent).
        for (TopicAction action : getAdjustableSkillActions()) {
            Rect rowRect = getSkillEntryRectInOverlay(action);
            if (rowRect == null) continue;
            TopicEntry entry = null;
            for (TopicEntry candidate : SKILL_TOPIC_ENTRIES) {
                if (candidate != null && candidate.action == action) {
                    entry = candidate;
                    break;
                }
            }
            int labelX = getSkillLabelStartX(rowRect.x, action, entry != null ? entry.indent : 0);
            int controlCount = (action == TopicAction.SKILL_ASCENT || action == TopicAction.SKILL_WOOL) ? 3 : 2;
            String valueLabel = getAdjustableSkillValueLabel(action);
            int controlRight = getCompactControlRight(rowRect.x, rowRect.w, labelX, this.textRenderer.getWidth(valueLabel), controlCount);
            int plusX = controlRight - controlSize;
            int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;
            int leftmostControl = minusX;
            if (action == TopicAction.SKILL_ASCENT || action == TopicAction.SKILL_WOOL) {
                leftmostControl = minusX - TOPIC_CONTROL_GAP - controlSize;
            }
            int valueX = leftmostControl - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(valueLabel);
            if (valueX < labelX + 42) valueX = labelX + 42;
            int clusterLeft = valueX - 4;
            int controlY = rowRect.y + 1;
            if (mouseX >= clusterLeft && mouseX < leftmostControl
                    && mouseY >= controlY && mouseY < controlY + controlSize) {
                return action;
            }
        }
        return null;
    }

    private void drawFollowRow(DrawContext context, int rowX, int rowY, int rowW, int mouseX, int mouseY) {
        boolean active = isFollowActive();
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int baseRow = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int rowColor = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : baseRow;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int labelX = drawSkillRowIcon(context, rowX, rowY, TopicAction.FOLLOW);

        String status = active ? "ON" : "OFF";
        String distanceLabel = formatFollowDistance();
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int statusW = this.textRenderer.getWidth(status);
        int distanceW = this.textRenderer.getWidth(distanceLabel);
        int controlRight = getCompactControlRight(rowX, rowW, labelX, distanceW + TOPIC_CONTROL_GAP + statusW, 2);
        int plusX = controlRight - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;
        int statusX = minusX - TOPIC_CONTROL_GAP - statusW;
        int distX = statusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(distanceLabel);

        if (distX < labelX + 40) {
            distX = labelX + 40;
        }

        drawValueClusterBox(context, distX - 4, rowY, plusX + controlSize + 3, hover);

        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, "Follow", labelX, textY, COLOR_TEXT_PARCHMENT, false);
        context.drawText(this.textRenderer, distanceLabel, distX, textY, 0xFFE6D7A3, false);
        context.drawText(this.textRenderer, status, statusX, textY, active ? 0xFFE6D7A3 : 0xFFB0B0B0, false);

        drawControlBox(context, minusX, controlY, controlSize, "-", mouseX, mouseY);
        drawControlBox(context, plusX, controlY, controlSize, "+", mouseX, mouseY);
    }

    private void drawWoodcutRow(DrawContext context, int rowX, int rowY, int rowW, int mouseX, int mouseY) {
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int rowColor = hover ? 0xFF2A2A2A : 0xFF1A1A1A;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int labelX = drawSkillRowIcon(context, rowX, rowY, TopicAction.SKILL_WOODCUT);

        String countLabel = "Trees " + woodcutTreeCount;
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int controlRight = getCompactControlRight(rowX, rowW, labelX, this.textRenderer.getWidth(countLabel), 2);
        int plusX = controlRight - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;
        int countX = minusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(countLabel);
        if (countX < labelX + 44) {
            countX = labelX + 44;
        }

        drawValueClusterBox(context, countX - 4, rowY, plusX + controlSize + 3, hover);

        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, "Woodcut", labelX, textY, COLOR_TEXT_PARCHMENT, false);

        if (directInputAction == TopicAction.SKILL_WOODCUT) {
            drawDirectInputField(context, countX, controlY, minusX - TOPIC_CONTROL_GAP - countX, controlSize, textY);
        } else {
            context.drawText(this.textRenderer, countLabel, countX, textY, 0xFFE6D7A3, false);
        }

        drawControlBox(context, minusX, controlY, controlSize, "-", mouseX, mouseY);
        drawControlBox(context, plusX, controlY, controlSize, "+", mouseX, mouseY);
    }

    private boolean isAdjustableSkillAction(TopicAction action) {
        if (action == null) {
            return false;
        }
        return action == TopicAction.SKILL_FISH
                || action == TopicAction.SKILL_WOOL
                || action == TopicAction.SKILL_STRIPMINE
                || action == TopicAction.SKILL_ASCENT
                || action == TopicAction.SKILL_DESCENT;
    }

    private TopicAction[] getAdjustableSkillActions() {
        return new TopicAction[] {
                TopicAction.SKILL_FISH,
                TopicAction.SKILL_WOOL,
                TopicAction.SKILL_STRIPMINE,
                TopicAction.SKILL_ASCENT,
                TopicAction.SKILL_DESCENT
        };
    }

    private int getSkillEntryIndex(List<TopicEntry> entries, TopicAction action) {
        if (entries == null || action == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            TopicEntry entry = entries.get(i);
            if (entry != null && entry.action == action) {
                return i;
            }
        }
        return -1;
    }

    private void drawAdjustableSkillRow(DrawContext context, int rowX, int rowY, int rowW, TopicEntry entry, int mouseX, int mouseY) {
        if (entry == null || entry.action == null) {
            return;
        }
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int rowColor = hover ? 0xFF2A2A2A : 0xFF1A1A1A;
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        String label = entry.label;
        String valueLabel = getAdjustableSkillValueLabel(entry.action);
        int labelX = drawSkillRowIcon(context, rowX, rowY, entry.action) + entry.indent * 10;
        int controlCount = (entry.action == TopicAction.SKILL_ASCENT || entry.action == TopicAction.SKILL_WOOL) ? 3 : 2;
        int controlSize = TOPIC_ROW_HEIGHT - 2;
        int controlY = rowY + 1;
        int controlRight = getCompactControlRight(rowX, rowW, labelX, this.textRenderer.getWidth(valueLabel), controlCount);
        int plusX = controlRight - controlSize;
        int minusX = plusX - TOPIC_CONTROL_GAP - controlSize;
        int valueX = minusX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(valueLabel);
        int modeX = -1;
        if (entry.action == TopicAction.SKILL_ASCENT || entry.action == TopicAction.SKILL_WOOL) {
            modeX = minusX - TOPIC_CONTROL_GAP - controlSize;
            valueX = modeX - TOPIC_CONTROL_GAP - this.textRenderer.getWidth(valueLabel);
        }

        if (valueX < labelX + 42) {
            valueX = labelX + 42;
        }
        int leftmostControl = modeX >= 0 ? modeX : minusX;
        drawValueClusterBox(context, valueX - 4, rowY, plusX + controlSize + 3, hover);
        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, label, labelX, textY, COLOR_TEXT_PARCHMENT, false);

        if (directInputAction == entry.action) {
            drawDirectInputField(context, valueX, controlY, leftmostControl - TOPIC_CONTROL_GAP - valueX, controlSize, textY);
        } else {
            int valueLabelColor = (entry.action == TopicAction.SKILL_WOOL && woolAdjustingRange)
                    ? 0xFF8CB8D0   // blue tint when showing range
                    : 0xFFE6D7A3;  // default parchment
            context.drawText(this.textRenderer, valueLabel, valueX, textY, valueLabelColor, false);
        }

        if (modeX >= 0) {
            if (entry.action == TopicAction.SKILL_WOOL) {
                drawControlBox(context, modeX, controlY, controlSize, "\u2194", mouseX, mouseY, woolAdjustingRange);
            } else {
                drawControlBox(context, modeX, controlY, controlSize, "☀", mouseX, mouseY, ascentSurfaceMode);
            }
        }
        drawControlBox(context, minusX, controlY, controlSize, "-", mouseX, mouseY);
        drawControlBox(context, plusX, controlY, controlSize, "+", mouseX, mouseY);
    }

    private String getAdjustableSkillValueLabel(TopicAction action) {
        return switch (action) {
            case SKILL_FISH -> fishTargetCount > 0 ? "Catches " + fishTargetCount : "Until sunset";
            case SKILL_WOOL -> woolAdjustingRange
                    ? "Range " + woolSearchRange
                    : "Wool " + (woolTargetCount > 0 ? woolTargetCount : SKILL_WOOL_COUNT_DEFAULT);
            case SKILL_STRIPMINE -> "Length " + (stripmineLength > 0 ? stripmineLength : SKILL_STRIPMINE_COUNT_DEFAULT);
            case SKILL_ASCENT -> ascentSurfaceMode
                    ? "Surface"
                : "Blocks " + (ascentBlocks > 0 ? ascentBlocks : SKILL_ASCENT_COUNT_DEFAULT);
            case SKILL_DESCENT -> "Blocks " + (descentBlocks > 0 ? descentBlocks : SKILL_DESCENT_COUNT_DEFAULT);
            default -> "";
        };
    }

    private void drawTopicRow(DrawContext context, int rowX, int rowY, int rowW, TopicEntry entry,
                              boolean active, int mouseX, int mouseY) {
        if (entry == null) {
            return;
        }

        if (isAdminHeaderEntry(entry) || isSkillHeaderEntry(entry)) {
            context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, 0xFF141414);
            int accentY = rowY + TOPIC_ROW_HEIGHT - 3;
            int accentColor = entry.category == TopicCategory.SKILL ? 0xFF35546C : 0xFF5A4728;
            int textColor = entry.category == TopicCategory.SKILL ? 0xFFB9D6EC : 0xFFB08C40;
            context.fill(rowX + 6, accentY, rowX + rowW - 6, accentY + 1, accentColor);
            int textY = rowY + Math.max(0, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2 - 1);
            context.drawText(this.textRenderer, entry.label != null ? entry.label : "", rowX + 4, textY, textColor, false);
            return;
        }

        boolean enabled;
        if (entry.category == TopicCategory.DIALOGUE) {
            enabled = isDialogueEntryEnabled(entry);
        } else if (entry.category == TopicCategory.ADMIN) {
            enabled = isAdminEntryEnabled(entry);
        } else if (entry.category == TopicCategory.SPELL) {
            enabled = isSpellEntryEnabled(entry);
        } else {
            enabled = entry.action == null || isEntryEnabled(entry.action);
        }
        boolean hover = mouseX >= rowX && mouseX < rowX + rowW
                && mouseY >= rowY && mouseY < rowY + TOPIC_ROW_HEIGHT;
        int baseRow = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        int rowColor = hover ? (active ? 0xFF4A3720 : 0xFF2A2A2A) : baseRow;
        if (!enabled) {
            rowColor = 0xFF151515;
        }
        context.fill(rowX, rowY, rowX + rowW, rowY + TOPIC_ROW_HEIGHT, rowColor);

        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        int labelX = rowX + 4 + entry.indent * 8;
        if (entry.category == TopicCategory.SKILL) {
            labelX = drawSkillRowIcon(context, rowX, rowY, entry.action) + entry.indent * 10;
        }
        String label = displayLabelForEntry(entry);

        // Draw explicit control box for actionable entries (SKILL and ADMIN categories).
        // Toggles get an ON/OFF button box; non-toggle actions get a "▸" button box.
        // Dialogue entries have no control box (they use a different click path).
        boolean hasControlBox = entry.category != TopicCategory.DIALOGUE
                && entry.action != null && enabled;
        int controlSize = TOPIC_ROW_HEIGHT;
        int controlY = rowY;
        int controlBoxRight = rowX + rowW - 2;

        if (hasControlBox && entry.toggle) {
            String status = toggleStatusLabelForEntry(entry);
            int boxW = this.textRenderer.getWidth(status) + 16;
            int boxX = controlBoxRight - boxW;
            drawActionControlBox(context, boxX, controlY, boxW, controlSize, status, mouseX, mouseY, active);
            int labelMaxW = Math.max(0, (boxX - TOPIC_CONTROL_GAP) - labelX);
            String drawnLabel = elideToWidth(label, labelMaxW);
            int labelColor = enabled ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED;
            context.drawText(this.textRenderer, drawnLabel, labelX, textY, labelColor, false);
        } else if (hasControlBox) {
            String btnLabel = "\u25B8"; // ▸
            int boxW = controlSize + 8;
            int boxX = controlBoxRight - boxW;
            drawActionControlBox(context, boxX, controlY, boxW, controlSize, btnLabel, mouseX, mouseY, active);
            int labelMaxW = Math.max(0, (boxX - TOPIC_CONTROL_GAP) - labelX);
            String drawnLabel = elideToWidth(label, labelMaxW);
            int labelColor = enabled ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED;
            context.drawText(this.textRenderer, drawnLabel, labelX, textY, labelColor, false);
        } else {
            int labelMaxW = Math.max(0, (rowX + rowW - 4) - labelX);
            String drawnLabel = elideToWidth(label, labelMaxW);
            int labelColor = enabled ? COLOR_TEXT_PARCHMENT : COLOR_TEXT_DISABLED;
            context.drawText(this.textRenderer, drawnLabel, labelX, textY, labelColor, false);
        }
    }

    private String toggleStatusLabelForEntry(TopicEntry entry) {
        if (entry != null && entry.action == TopicAction.OWNED_SUNSET_SS) {
            return ownedSunsetSelfSufficientStatusLabel();
        }
        boolean active = entry != null && entry.action != null && isEntryActive(entry.action);
        return active ? "ON" : "OFF";
    }

    private int drawSkillRowIcon(DrawContext context, int rowX, int rowY, TopicAction action) {
        if (action == TopicAction.SKILL_COLLECT_DIRT) {
            int baseX = rowX + 4;
            int iconSize = 7;
            int iconX = baseX + Math.max(0, (SKILL_ICON_SLOT_W - iconSize) / 2);
            int iconY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - iconSize) / 2);

            context.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF5E4327);
            context.fill(iconX, iconY, iconX + iconSize, iconY + 2, 0xFF7D5A33);
            context.fill(iconX, iconY + iconSize - 1, iconX + iconSize, iconY + iconSize, 0xFF3A2816);
            context.fill(iconX, iconY, iconX + 1, iconY + iconSize, 0xFF3A2816);
            context.fill(iconX + iconSize - 1, iconY, iconX + iconSize, iconY + iconSize, 0xFF3A2816);
            context.fill(iconX + 2, iconY + 3, iconX + 3, iconY + 4, 0xFF8E6A42);
            context.fill(iconX + 4, iconY + 4, iconX + 5, iconY + 5, 0xFF8E6A42);
            return getSkillLabelStartX(rowX, action, 0);
        }

        String icon = iconForAction(action);
        int baseX = rowX + 4;
        if (icon == null || icon.isBlank()) {
            return baseX;
        }
        int textY = rowY + Math.max(1, (TOPIC_ROW_HEIGHT - this.textRenderer.fontHeight) / 2);
        int iconX = baseX + Math.max(0, (SKILL_ICON_SLOT_W - this.textRenderer.getWidth(icon)) / 2);
        context.drawText(this.textRenderer, icon, iconX, textY, 0xFFB9D6EC, false);
        return getSkillLabelStartX(rowX, action, 0);
    }

    private String iconForAction(TopicAction action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case OPEN_GUIDE -> "📘";
            case STOP -> "🛑";
            case RESUME -> "▶";
            case FOLLOW -> "👣";
            case GUARD -> "🛡";
            case PATROL -> "◆";
            case RETURN_HOME -> "🏠";
            case SLEEP -> "🛌";
            case AUTO_RETURN_SUNSET -> "☾";
            case AUTO_RETURN_SELF_SUFFICIENT -> "🏕";
            case TACTICAL_SHELTER -> "⛏";
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> "↔";
            case AUTO_RETURN_SKIP_PERMISSION -> "⏩";
            case IDLE_HOBBIES -> "✦";
            case AUTO_HUNT_STARVING -> "⚔";
            case UNLEASH_TETHERED -> "✂";
            case LEASH_ON_DISMOUNT -> "⌁";
            case DROP_SWEEP -> "🧹";
            case BASES -> "⌂";
            case CRAFTING -> "⚒";
            case CONSTRUCTION -> "▧";
            case COOKING -> "♨";
            case HUNTING -> "🏹";
            case STORAGE -> "📦";
            case QUICK_STORE -> "📥";
            case QUICK_FETCH -> "📤";
            case SKILL_FISH -> "🎣";
            case SKILL_WOODCUT -> "🪓";
            case SKILL_WOODCUT_CLEANUP -> "•";
            case SKILL_WOOL -> "✂";
            case SKILL_FARM -> "❀";
            case SKILL_COLLECT_DIRT -> "";
            case SKILL_MINING -> "⛏";
            case SKILL_STRIPMINE -> "↦";
            case SKILL_ASCENT -> "↑";
            case SKILL_DESCENT -> "↓";
            default -> "";
        };
    }

    private String displayLabelForEntry(TopicEntry entry) {
        if (entry == null) {
            return "";
        }
        if (entry.category == TopicCategory.ADMIN && entry.action == TopicAction.ADMIN_PREVIEW_NON_ADMIN) {
            return "Preview as Non-Admin";
        }
        return entry.label != null ? entry.label : "";
    }

    private void drawOverlayHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        if (!topicsExpanded || overlayDraggingSplit || overlayDraggingListScroll) {
            overlayHoveredEntry = null;
            overlayHoveredControlKey = null;
            return;
        }

        OverlayControlHover hoveredControl = getOverlayControlHover(mouseX, mouseY);
        String controlKey = hoveredControl != null ? hoveredControl.key() : null;
        if (!java.util.Objects.equals(controlKey, overlayHoveredControlKey)) {
            overlayHoveredControlKey = controlKey;
            overlayControlHoverStartedAtMs = System.currentTimeMillis();
        }

        if (hoveredControl != null) {
            long now = System.currentTimeMillis();
            if (now - overlayControlHoverStartedAtMs >= OVERLAY_HOVER_TOOLTIP_DELAY_MS) {
                drawTooltipBox(context, mouseX, mouseY, hoveredControl.lines());
                return;
            }
        }

        // Only consider entries in the list area (below tabs).
        TopicEntry hovered = getTopicEntryAtOverlay(mouseX, mouseY);
        if (hovered != overlayHoveredEntry) {
            overlayHoveredEntry = hovered;
            overlayHoverStartedAtMs = System.currentTimeMillis();
        }

        if (overlayHoveredEntry == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - overlayHoverStartedAtMs < OVERLAY_HOVER_TOOLTIP_DELAY_MS) {
            return;
        }

        java.util.List<String> lines = getOverlayTooltipLines(overlayHoveredEntry);
        if (lines == null || lines.isEmpty()) {
            return;
        }

        drawTooltipBox(context, mouseX, mouseY, lines);
    }

    private OverlayControlHover getOverlayControlHover(double mouseX, double mouseY) {
        if (!topicsExpanded || overlayCategory != TopicCategory.SKILL) {
            return null;
        }

        int followDir = getFollowAdjustDirectionInOverlay(mouseX, mouseY);
        if (followDir != 0) {
            if (followDir > 0) {
                return new OverlayControlHover(
                        "follow:+",
                        java.util.List.of(
                                "Increase Follow Distance",
                                "Adds " + String.format(Locale.ROOT, "%.1f", FOLLOW_DISTANCE_STEP) + " blocks.",
                                "Current: " + formatFollowDistance()
                        )
                );
            }
            return new OverlayControlHover(
                    "follow:-",
                    java.util.List.of(
                            "Decrease Follow Distance",
                            "Reduces follow spacing by " + String.format(Locale.ROOT, "%.1f", FOLLOW_DISTANCE_STEP) + ".",
                            "Current: " + formatFollowDistance()
                    )
            );
        }

        int woodcutDir = getWoodcutAdjustDirectionInOverlay(mouseX, mouseY);
        if (woodcutDir != 0) {
            if (woodcutDir > 0) {
                return new OverlayControlHover(
                        "woodcut:+",
                        java.util.List.of("Woodcut Trees +1", "Increase tree target for the next woodcut run.")
                );
            }
            return new OverlayControlHover(
                    "woodcut:-",
                    java.util.List.of("Woodcut Trees -1", "Decrease tree target. Reaching minimum keeps runs compact.")
            );
        }

        SkillAdjustHit hit = getAdjustableSkillHitInOverlay(mouseX, mouseY);
        if (hit == null || hit.action() == null || hit.control() == null) {
            return null;
        }

        String key = hit.action().name() + ":" + hit.control().name();
        return switch (hit.control()) {
            case INCREMENT -> new OverlayControlHover(
                    key,
                    java.util.List.of(
                            "Increase Amount",
                            "Raises the value for " + readableSkillLabel(hit.action()) + "."
                    )
            );
            case DECREMENT -> new OverlayControlHover(
                    key,
                    java.util.List.of(
                            "Decrease Amount",
                            "Lowers the value for " + readableSkillLabel(hit.action()) + "."
                    )
            );
            case TOGGLE_MODE -> {
                if (hit.action() == TopicAction.SKILL_WOOL) {
                    yield new OverlayControlHover(
                            key,
                            java.util.List.of(
                                    "Range Mode",
                                    "Toggle to adjust search range instead of wool count.",
                                    "Range: " + SKILL_WOOL_RANGE_MIN + "–" + SKILL_WOOL_RANGE_MAX + " blocks (steps of 16).",
                                    "Default range is " + SKILL_WOOL_RANGE_DEFAULT + " blocks."
                            )
                    );
                }
                yield new OverlayControlHover(
                        key,
                        java.util.List.of(
                                "Surface Mode",
                                "Ascent digs upward until open sky when ON.",
                                "Default ascent is " + SKILL_ASCENT_COUNT_DEFAULT + " blocks."
                        )
                );
            }
        };
    }

    private String readableSkillLabel(TopicAction action) {
        if (action == null) {
            return "this action";
        }
        return switch (action) {
            case SKILL_WOODCUT -> "Woodcut";
            case SKILL_FISH -> "Fishing";
            case SKILL_WOOL -> "Wool";
            case SKILL_STRIPMINE -> "Stripmine";
            case SKILL_ASCENT -> "Ascent";
            case SKILL_DESCENT -> "Descent";
            default -> "this action";
        };
    }

    private java.util.List<String> getOverlayTooltipLines(TopicEntry entry) {
        if (entry == null) {
            return java.util.List.of();
        }

        if (isSkillHeaderEntry(entry)) {
            return java.util.List.of();
        }

        // Spell tooltips.
        if (entry.category == TopicCategory.SPELL) {
            if (entry.action == TopicAction.SPELL_REMOTE_GUIDANCE) {
                return java.util.List.of(
                        "Remote Guidance",
                        "Consumes an Ender Pearl from both you and your companion.",
                        "The bot fast-travels to your current location \u2014",
                        "disappearing briefly, then reappearing at your coordinates."
                );
            }
            if (entry.action == TopicAction.SPELL_CHORUS_RECALL) {
                return java.util.List.of(
                        "Chorus Recall",
                        "Consumes an Ender Pearl and Chorus Fruit from both",
                        "you and your companion. Instantly teleports the bot",
                        "to you, or you to the bot."
                );
            }
            if (entry.action == TopicAction.SPELL_SOUL_OF_ENDER) {
                return java.util.List.of(
                        "Soul of Ender",
                        "Consumes a Chorus Fruit while the bot holds an Eye of Ender.",
                        "Grants 75 seconds of teleportation freedom \u2014",
                        "all movement locks are bypassed."
                );
            }
            if (entry.action == TopicAction.SPELL_REMOTE_INVENTORY) {
                return java.util.List.of(
                        "Remote Inventory",
                        "Open your companion's inventory remotely.",
                        "Requires full access (Enchanting Table or Wizard's Tome)."
                );
            }
            return java.util.List.of();
        }

        // Prefer richer tooltips for Admin entries (these are the ones that get truncated / ambiguous).
        if (entry.category == TopicCategory.ADMIN) {
            if (isAdminHeaderEntry(entry)) {
                return java.util.List.of(entry.label != null ? entry.label : "Admin", "Category section");
            }
            if (entry.action == TopicAction.OPEN_PLAYER_SETTINGS) {
                return java.util.List.of(
                        "Player Permissions",
                        "Configure global defaults and per-player overrides.",
                        "Admins only."
                );
            }
            if (entry.action == TopicAction.ADMIN_PREVIEW_NON_ADMIN) {
                return java.util.List.of(
                        "Preview as Non-Admin",
                        "Shows the Admin tab exactly as non-admin players see it.",
                        "Current: " + (adminPreviewAsNonAdmin ? "ON" : "OFF")
                );
            }
            if (entry.action == TopicAction.AUTONOMOUS_RESCUES) {
                return java.util.List.of(
                        "Allow Autonomous Rescues",
                        "When ON, bots may perform emergency self-sufficiency rescues for same-owner bots.",
                        "This affects all players on the server.",
                        "Current: " + (isAutonomousRescuesActive() ? "ON" : "OFF")
                );
            }
            if (entry.action == TopicAction.OWNED_SUNSET_SS) {
                return java.util.List.of(
                        "Owned Sunset SS",
                        "Sets sunset self-sufficiency for all bots you own that are currently active on this server.",
                        "Current: " + ownedSunsetSelfSufficientStatusLabel()
                );
            }
            if (entry.action == TopicAction.SKIN_POLICY_EVERYONE) {
                return java.util.List.of(
                        "Allow Skin Changes for Everyone",
                        "When ON, non-admin players may change bot skins.",
                        "When OFF, skin changes remain admin-only."
                );
            }
            if (entry.action == TopicAction.SKIN_POLICY_CUSTOM) {
                return java.util.List.of(
                        "Allow Custom URL Skins",
                        "When OFF, non-admin custom skins are reverted to safe preset 'steve'.",
                        "Admins can still apply custom skins."
                );
            }
            if (entry.action == TopicAction.OPEN_SPELLS) {
                MinecraftClient client = MinecraftClient.getInstance();
                boolean nearTable = isNearEnchantingTable(client, 4);
                boolean hasBook = hasSpellbookToken(client);
                boolean hasEye = hasEyeOfEnderToken(client);

                if (nearTable || hasBook) {
                    return java.util.List.of(
                            "Spells",
                            "Full access: Enchanting Table (4 blocks) or Wizard's Tome.",
                            "Eye of Ender can also open (Summon-only; cooldown)."
                    );
                }

                if (hasEye) {
                    return java.util.List.of(
                            "Spells",
                            "Eye of Ender: limited access (Summon-only; cooldown).",
                        "Full access: Enchanting Table nearby (4 blocks) or Wizard's Tome."
                    );
                }
                return java.util.List.of(
                        "Spells",
                        "Requires: Enchanting Table nearby (4 blocks)",
                    "or a Wizard's Tome.",
                        "(Also: Eye of Ender for limited Summon-only access; cooldown applies.)"
                );
            }

            String k = entry.dialogueKey == null ? "" : entry.dialogueKey.trim().toLowerCase(Locale.ROOT);
            if (k.equals("give_wizard_tome")) {
                return java.util.List.of(
                        "Give Wizard's Tome",
                        "Operator-only: grants you the Wizard's Tome quest item.",
                        "Equivalent to: /bot wizard_tome"
                );
            }
            if (k.equals("recruit_status")) {
                return java.util.List.of("Recruitment Status", "Shows the current survival recruitment state.");
            }
            if (k.equals("recruit_reset")) {
                return java.util.List.of(
                        "Recruitment Reset (This World)",
                        "Clears recruitment progress and state for testing.",
                        "UI safety: click twice within 5s to confirm."
                );
            }
            if (k.equals("recruit_enable")) {
                return java.util.List.of(
                        "Recruitment Enable",
                        "Sets world mode to Questing (recruitment ON).",
                        "If switching from Admin mode, click again within 12s to confirm."
                );
            }
            if (k.equals("recruit_disable")) {
                return java.util.List.of(
                        "Recruitment Disable",
                        "Sets world mode to Admin (recruitment OFF).",
                        "If switching from Questing mode, click again within 12s to confirm."
                );
            }
            if (k.equals("anchor_set")) {
                return java.util.List.of("Set Village Anchor Here", "Sets the village/settlement anchor at your position.");
            }
            if (k.equals("anchor_clear")) {
                return java.util.List.of("Clear Village Anchor", "Clears the saved village/settlement anchor.");
            }
            if (k.equals("learning_status")) {
                return java.util.List.of(
                        "Learning Status",
                        "Shows whether operator learning mode is armed or actively recording a demonstration trace.",
                        "Use this before starting or stopping a capture run. See Guide > Learning Mode / Roadmap for the bigger picture."
                );
            }
            if (k.equals("learning_start")) {
                return java.util.List.of(
                        "Learning Start",
                        "Starts operator-only learning mode capture for the current demonstration session.",
                        "This records movement, camera, interactions, and local context for later bot-control tuning."
                );
            }
            if (k.equals("learning_stop_success")) {
                return java.util.List.of(
                        "Learning Stop (Success)",
                        "Stops learning mode and marks the demonstration as a successful example to keep.",
                        "Use when the player performed the intended behavior correctly."
                );
            }
            if (k.equals("learning_stop_fail")) {
                return java.util.List.of(
                        "Learning Stop (Failure)",
                        "Stops learning mode and marks the demonstration as a failed attempt for analysis.",
                        "Useful when testing recovery logic, bad paths, or broken build movement."
                );
            }
            if (k.equals("learning_stop_abort")) {
                return java.util.List.of(
                        "Learning Stop (Abort)",
                        "Stops learning mode without treating the run as a clean success.",
                        "Use when the demo should end early because the setup changed or the run became invalid."
                );
            }
            if (k.startsWith("setstage:")) {
                String n = k.substring("setstage:".length()).trim();
                return java.util.List.of("Set Companion Stage: " + n, "Advances or rewinds companion quest stage.");
            }

            // Fallback: show the label.
            return java.util.List.of(entry.label);
        }

        // Dialogue topics: show the full prompt (the transcript also uses this).
        if (entry.category == TopicCategory.DIALOGUE) {
            String prompt = getDialoguePrompt(entry.dialogueKey, entry.label);
            if (prompt != null && !prompt.isBlank()) {
                return java.util.List.of(prompt);
            }
        }

        if (entry.category == TopicCategory.SKILL) {
            if (entry.action == null) {
            return java.util.List.of();
            }
            return switch (entry.action) {
            case STOP -> java.util.List.of(
                "Stop",
                "Cancels the bot's current task, movement, or queued action right away."
            );
            case AUTO_RETURN_SUNSET -> java.util.List.of(
                "Auto Home @ Sunset",
                "At dusk, the bot heads back toward home instead of staying out overnight."
            );
            case AUTO_RETURN_SELF_SUFFICIENT -> java.util.List.of(
                "Self-Sufficiency",
                "If home is too far or unreachable at dusk, the bot falls back to nearby safe anchors like bases, beds, allied bots, remembered chests, or tactical shelter."
            );
            case TACTICAL_SHELTER -> java.util.List.of(
                "Tactical Shelter",
                "Allows improvised night sheltering and the local tactical-shelter fallback. Turn this OFF if you do not want the bot digging in or building emergency shelter at night."
            );
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> java.util.List.of(
                "Guard/Patrol Eligible",
                "When ON, Guard and Patrol bots may still auto-return home at sunset."
            );
            case AUTO_RETURN_SKIP_PERMISSION -> java.util.List.of(
                "Skip Permission",
                "Bots return home at sunset without asking permission first."
            );
            case IDLE_HOBBIES -> java.util.List.of(
                "Idle Hobbies",
                "Lets the bot do light background activities when it has nothing urgent to work on."
            );
            case AUTO_HUNT_STARVING -> java.util.List.of(
                "Auto Hunt (Starving)",
                "If the bot gets desperate for food, it may hunt for meat on its own."
            );
            case UNLEASH_TETHERED -> java.util.List.of(
                "Unleash Tethered",
                "Allows the bot to free itself from leads when it needs to keep moving or resume work."
            );
            case LEASH_ON_DISMOUNT -> java.util.List.of(
                "Leash on Dismount",
                "Reattaches a lead after getting off a mount so the bot stays tethered."
            );
            case DROP_SWEEP -> java.util.List.of(
                "Cleanup",
                "Runs a quick item sweep to pick up nearby drops after combat, mining, or building."
            );
            case BASES -> java.util.List.of(
                "Bases",
                "Open the base manager for saved homes, wall claims, and base travel helpers."
            );
            case CRAFTING -> java.util.List.of(
                "Crafting",
                "Open crafting-related history and helper tools for the selected bot."
            );
            case CONSTRUCTION -> java.util.List.of(
                "Construction",
                "Open shelter, fortify, and other building-focused helper actions."
            );
            case COOKING -> java.util.List.of(
                "Cooking",
                "Open the cooking helper menu for food preparation tasks."
            );
            case HUNTING -> java.util.List.of(
                "Hunting",
                "Open the hunting target menu for meat, leather, and mob-hunt tasks."
            );
            case QUICK_STORE -> java.util.List.of(
                "Quick Store",
                "Point at a nearby chest and click to deposit items.",
                "The bot walks to the chest and deposits. It will not remember this chest."
            );
            case QUICK_FETCH -> java.util.List.of(
                "Quick Fetch",
                "Point at a nearby chest and click to take items from it.",
                "The bot walks to the chest and withdraws. It will not remember this chest."
            );
            case STORAGE -> java.util.List.of(
                "Storage",
                "View all chests placed by this bot. Collect items or dismiss records."
            );
            case SKILL_WOODCUT -> java.util.List.of(
                "Woodcut",
                "Fells natural trees and collects the wood.",
                "Set how many trees to cut before starting; standalone runs stop at sunset."
            );
            case SKILL_FISH -> java.util.List.of(
                "Fishing",
                "Casts and catches fish from a nearby shoreline.",
                "Set a catch target, or leave it at the default to fish until sunset."
            );
            case SKILL_WOODCUT_CLEANUP -> java.util.List.of(
                "Woodcut Cleanup",
                "Cleans up after tree cutting by grabbing leftover drops and breaking floating leftover logs or dirt scaffold bits."
            );
            case SKILL_WOOL -> java.util.List.of(
                "Wool",
                "Shears adult sheep and gathers wool without killing them.",
                "Set a wool target before starting; standalone runs stop at sunset.",
                "Toggle [\u2194] to switch +/- between wool count and search range."
            );
            case SKILL_FARM -> java.util.List.of(
                "Farming",
                "Runs the bot's general farm work routine for planting and harvesting."
            );
            case SKILL_COLLECT_DIRT -> java.util.List.of(
                "Collect Dirt",
                "Gathers common soft terrain blocks like dirt, gravel, sand, and similar shovel-friendly material."
            );
            case SKILL_MINING -> java.util.List.of(
                "Mining",
                "Starts the bot's standard mining routine for nearby ores and underground resources."
            );
            case SKILL_STRIPMINE -> java.util.List.of(
                "Stripmine",
                "Digs a straight tunnel in the direction you pick.",
                "Set the length first, then look where you want it to go and confirm from the world."
            );
            case SKILL_ASCENT -> java.util.List.of(
                "Ascent",
                "Climbs upward by digging and building as needed.",
                "Use ☀ for surface mode, or set a block count, then look in the direction to start from."
            );
            case SKILL_DESCENT -> java.util.List.of(
                "Descent",
                "Digs a safe downward staircase in the direction you choose.",
                "Set the depth first, then look where you want it to begin and confirm from the world."
            );
            default -> java.util.List.of();
            };
        }

        return java.util.List.of(entry.label);
    }

    private void drawTooltipBox(DrawContext context, int mouseX, int mouseY, java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }

        int pad = 6;
        int lineH = this.textRenderer.fontHeight + 1;
        int maxTextW = Math.max(120, Math.min(260, this.width - 32));
        java.util.List<StyledLine> wrapped = new java.util.ArrayList<>();
        int maxW = 0;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s == null || s.isBlank()) {
                continue;
            }
            int color = (i == 0) ? COLOR_TEXT_PLAYER : COLOR_TEXT_PARCHMENT;
            java.util.List<net.minecraft.text.OrderedText> parts = this.textRenderer.wrapLines(Text.literal(s), maxTextW);
            if (parts.isEmpty()) {
                continue;
            }
            for (net.minecraft.text.OrderedText part : parts) {
                wrapped.add(new StyledLine(part, color));
                maxW = Math.max(maxW, this.textRenderer.getWidth(part));
            }
        }
        if (wrapped.isEmpty()) {
            return;
        }
        int boxW = maxW + pad * 2;
        int boxH = wrapped.size() * lineH + pad * 2 - 1;

        int x = mouseX + 12;
        int y = mouseY + 10;
        // Keep inside screen bounds.
        if (x + boxW > this.width - 8) {
            x = mouseX - 12 - boxW;
        }
        if (y + boxH > this.height - 8) {
            y = this.height - 8 - boxH;
        }
        x = MathHelper.clamp(x, 8, Math.max(8, this.width - 8 - boxW));
        y = MathHelper.clamp(y, 8, Math.max(8, this.height - 8 - boxH));

        int bg = 0xEE101010;
        int border = 0xFF000000;
        context.fill(x, y, x + boxW, y + boxH, bg);
        context.fill(x, y, x + boxW, y + 1, border);
        context.fill(x, y + boxH - 1, x + boxW, y + boxH, border);
        context.fill(x, y, x + 1, y + boxH, border);
        context.fill(x + boxW - 1, y, x + boxW, y + boxH, border);

        int ty = y + pad;
        for (StyledLine line : wrapped) {
            context.drawText(this.textRenderer, line.text(), x + pad, ty, line.color(), false);
            ty += lineH;
        }
    }

    private String elideToWidth(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisW = this.textRenderer.getWidth(ellipsis);
        if (ellipsisW >= maxWidth) {
            return ellipsis;
        }
        // Binary search the longest prefix that fits.
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

    private void requestCompanionQuestState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(new CompanionQuestStateRequestPayload(botAlias));
        companionQuestStateRequested = true;
    }

    private boolean isDialogueEntryEnabled(TopicEntry entry) {
        if (entry == null || entry.category != TopicCategory.DIALOGUE) {
            return false;
        }
        String key = entry.dialogueKey;
        if (key == null || key.isBlank()) {
            return true;
        }

        // Server snapshot (defaults to stage 0 until we hear back).
        int stage = Math.max(0, net.wcfcarolina13.FrensClient.getCompanionQuestStage(botAlias));
        boolean permanent = net.wcfcarolina13.FrensClient.isCompanionPermanent(botAlias);

        String k = key.trim().toLowerCase(Locale.ROOT);

        // Recruitment entry: available only while survival recruitment is active and not completed.
        if (k.equals("recruit_contact")) {
            return net.wcfcarolina13.FrensClient.isSurvivalRecruitmentEnabled()
                && !net.wcfcarolina13.FrensClient.isSurvivalRecruitmentCompleted();
        }

        // Recruitment replay/reset: useful for testing; server will enforce authorization.
        if (k.equals("recruit_replay")) {
            return net.wcfcarolina13.FrensClient.isSurvivalRecruitmentEnabled();
        }

        // Always-available talk options.
        if (k.equals("village_about") || k.equals("stay_conditions") || k.equals("goodbye")) {
            return true;
        }

        // Core quest topics are only meaningful after recruitment.
        if (k.equals("companion_status") || k.equals("companion_check") || k.equals("companion_anchor_set")
                || k.equals("village_missing") || k.equals("village_projects")
                || k.equals("batch3_biomes") || k.equals("batch3_structures")
                || k.equals("batch3_dimensions") || k.equals("batch3_traders_mounts")
                || k.equals("batch3_travel")) {
            return net.wcfcarolina13.FrensClient.isSurvivalRecruitmentCompleted();
        }

        // Relationship / story beats unlock as the settlement improves.
        return switch (k) {
            case "bot_past" -> stage >= 2 || permanent;
            case "promise" -> stage >= 3 || permanent;
            default -> stage >= 1 || permanent;
        };
    }

    private void drawControlBox(DrawContext context, int x, int y, int size, String label, int mouseX, int mouseY) {
        drawControlBox(context, x, y, size, label, mouseX, mouseY, false);
    }

    private void drawControlBox(DrawContext context, int x, int y, int size, String label, int mouseX, int mouseY, boolean active) {
        boolean hover = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
        int fill = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        if (hover) {
            fill = active ? 0xFF4A3720 : 0xFF2F2F2F;
        }
        context.fill(x, y, x + size, y + size, fill);
        int textX = x + (size - this.textRenderer.getWidth(label)) / 2;
        int textY = y + Math.max(1, (size - this.textRenderer.fontHeight) / 2);
        context.drawText(this.textRenderer, label, textX, textY, COLOR_TEXT_PARCHMENT, false);
    }

    /**
     * Draws a variable-width bordered control box — used for toggle ON/OFF buttons and action triggers.
     * Returns the rect for hit-testing.
     */
    private Rect drawActionControlBox(DrawContext context, int x, int y, int w, int h,
                                       String label, int mouseX, int mouseY, boolean active) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int fill = active ? 0xFF3A2C14 : 0xFF1A1A1A;
        if (hover) {
            fill = active ? 0xFF4A3720 : 0xFF2F2F2F;
        }
        context.fill(x, y, x + w, y + h, fill);
        // 1px border
        int border = hover ? 0xFF4A4A4A : 0xFF2A2A2A;
        context.fill(x, y, x + w, y + 1, border);
        context.fill(x, y + h - 1, x + w, y + h, border);
        context.fill(x, y, x + 1, y + h, border);
        context.fill(x + w - 1, y, x + w, y + h, border);
        int textColor = active ? 0xFFE6D7A3 : COLOR_TEXT_PARCHMENT;
        if (label.equals("\u25B8")) {
            // Render ▸ at 1.5x scale to fill the button height.
            float scale = 1.5f;
            int centerX = x + w / 2;
            int centerY = y + h / 2;
            int glyphW = this.textRenderer.getWidth(label);
            int glyphH = this.textRenderer.fontHeight;
            // Target position in scaled coordinates.
            int sx = (int) Math.floor((centerX - (glyphW * scale) / 2.0) / scale);
            int sy = (int) Math.floor((centerY - (glyphH * scale) / 2.0) / scale);
            var matrices = context.getMatrices();
            matrices.pushMatrix();
            matrices.scale(scale, scale);
            context.drawText(this.textRenderer, label, sx, sy, textColor, false);
            matrices.popMatrix();
        } else {
            int textX = x + (w - this.textRenderer.getWidth(label)) / 2;
            int textY = y + Math.max(1, (h - this.textRenderer.fontHeight) / 2);
            context.drawText(this.textRenderer, label, textX, textY, textColor, false);
        }
        return new Rect(x, y, w, h);
    }

    private void drawDirectInputField(DrawContext context, int x, int y, int w, int h, int textY) {
        context.fill(x, y, x + w, y + h, 0xFF0A0A0A);
        context.fill(x, y, x + w, y + 1, 0xFFB08C40);
        context.fill(x, y + h - 1, x + w, y + h, 0xFFB08C40);
        context.fill(x, y, x + 1, y + h, 0xFFB08C40);
        context.fill(x + w - 1, y, x + w, y + h, 0xFFB08C40);
        String display = directInputBuffer;
        boolean showCursor = (System.currentTimeMillis() / 500L) % 2L == 0L;
        if (showCursor) {
            display = display + "_";
        }
        context.drawText(this.textRenderer, display, x + 2, textY, 0xFFFFFFFF, false);
    }

    private String formatFollowDistance() {
        double value = getFollowDistance();
        if (value <= 0.0D) {
            return "Distance --";
        }
        return "Distance " + String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Guide Admin-only mode: don't draw inventory labels.
        if (guideRemoteOpen && !guideRemoteFullAccess) {
            return;
        }
        context.drawText(this.textRenderer, this.botAlias, this.titleX, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, "Level: " + this.handler.getBotLevel(), this.titleX + 90, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        resetInventoryTooltipHoverState();
        if (!topicsExpanded) {
            int rowY = this.y + SECTION_HEIGHT + 2 + STATS_AREA_HEIGHT - BOT_SWITCH_CONTROL_H - 5;
            BotSwitchLayout collapsedSwitch = computeBotSwitchLayout(this.x + 6, this.x + SECTION_WIDTH - 6, rowY);
            if (handleBotSwitchClick(collapsedSwitch, click.x(), click.y())) {
                return true;
            }
        }

        if (topicsExpanded) {
            return clickTopicsOverlay(click);
        }

        if (isMouseOverTopicsHeader(click.x(), click.y())) {
            toggleTopicsExpanded(true);
            return true;
        }
        TopicEntry entry = getTopicEntryAt(click.x(), click.y());
        if (entry != null) {
            boolean enabled = entry.category == TopicCategory.DIALOGUE
                    ? isDialogueEntryEnabled(entry)
                    : (entry.action == null || isEntryEnabled(entry.action));
            if (enabled) {
                handleTopicEntry(entry);
                return true;
            }
            return false;
        }
        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (topicsExpanded && overlayDraggingSplit && click.button() == 0) {
            updateOverlaySplitFromMouse(click.x());
            return true;
        }
        if (topicsExpanded && overlayDraggingListScroll && click.button() == 0) {
            Rect r = computeTopicsOverlayRect();
            OverlayColumns cols = computeOverlayColumns(r);
            int rowsHeight = getOverlayRowsHeight(cols.contentH);
            int visibleRows = Math.max(1, rowsHeight / TOPIC_ROW_HEIGHT);
            List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
            int listX = getOverlayListX(cols);
            int listW = getOverlayListW(cols);
            Rect track = computeOverlayListScrollbarTrack(listX, getOverlayRowsStartY(cols.contentY), listW, rowsHeight);
            int totalRows = getOverlayVisualRowCount(entries);
            Rect thumb = computeOverlayListScrollbarThumb(track, totalRows, visibleRows);
            updateOverlayScrollFromThumb(click, track, thumb, totalRows, visibleRows);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        clearHeldAdjust();
        if (overlayDraggingSplit) {
            overlayDraggingSplit = false;
            return true;
        }
        if (overlayDraggingListScroll) {
            overlayDraggingListScroll = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (topicsExpanded && isMouseOverTopicsOverlay(mouseX, mouseY)) {
            resetInventoryTooltipHoverState();
            Rect r = computeTopicsOverlayRect();
            int headerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_HEADER_PAD * 2;
            int footerH = this.textRenderer.fontHeight + TOPICS_OVERLAY_FOOTER_PAD * 2;
            int listY = r.y + headerH + 2;
            int listH = (r.bottom() - footerH - 2) - listY;
            List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
            int visibleRows = Math.max(1, getOverlayRowsHeight(listH) / TOPIC_ROW_HEIGHT);
            int maxScroll = Math.max(0, getOverlayVisualRowCount(entries) - visibleRows);
            if (maxScroll == 0) {
                return true;
            }
            int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
            if (delta != 0) {
                setOverlayScrollIndex(MathHelper.clamp(getOverlayScrollIndex() + delta, 0, maxScroll));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input != null && !(topicsExpanded && topicSearchFocused) && directInputAction == null) {
            int key = input.key();
            if (key == GLFW.GLFW_KEY_LEFT_BRACKET || key == GLFW.GLFW_KEY_RIGHT_BRACKET) {
                // Block bot switching in guide Admin-only mode.
                if (guideRemoteOpen && !guideRemoteFullAccess) {
                    return true;
                }
                int direction = key == GLFW.GLFW_KEY_LEFT_BRACKET ? -1 : 1;
                BotSwitchLayout keyboardSwitch = getActiveBotSwitchLayout();
                resetInventoryTooltipHoverState();
                if (cycleBotInventory(keyboardSwitch, direction)) {
                    return true;
                }
                showBotSwitchUnavailableHint();
                return true;
            }
        }

        if (input != null && topicsExpanded) {
            int key = input.key();
            if (directInputAction != null) {
                if (key == 259 /* BACKSPACE */) {
                    if (!directInputBuffer.isEmpty()) {
                        directInputBuffer = directInputBuffer.substring(0, directInputBuffer.length() - 1);
                    }
                    return true;
                }
                if (key >= 48 && key <= 57) { // 0-9
                    if (directInputBuffer.length() < 5) {
                        directInputBuffer += (char) ('0' + (key - 48));
                    }
                    return true;
                }
                if (key >= 320 && key <= 329) { // numpad 0-9
                    if (directInputBuffer.length() < 5) {
                        directInputBuffer += (char) ('0' + (key - 320));
                    }
                    return true;
                }
                if (key == 257 || key == 335) { // ENTER / KP_ENTER
                    commitDirectInput();
                    return true;
                }
                if (key == 256 /* ESC */) {
                    cancelDirectInput();
                    return true;
                }
                return true; // absorb all other keys while input is active
            }
            if (topicSearchFocused) {
                if (key == 259 /* BACKSPACE */) {
                    if (!topicSearchQuery.isEmpty()) {
                        setTopicSearchQuery(topicSearchQuery.substring(0, topicSearchQuery.length() - 1));
                    }
                    return true;
                }
                char typed = keyToSearchCharacter(input);
                if (typed != 0) {
                    setTopicSearchQuery(topicSearchQuery + typed);
                    return true;
                }
                if (key == 257 || key == 335) { // ENTER
                    topicSearchFocused = false;
                    return true;
                }
                if (key == 256 /* ESC */) {
                    if (!topicSearchQuery.isEmpty()) {
                        setTopicSearchQuery("");
                    } else {
                        topicSearchFocused = false;
                    }
                    return true;
                }
            }
            if (key == 256 /* ESC */) {
                // Guide Admin-only: ESC closes the screen entirely (no inventory behind it).
                if (guideRemoteOpen && !guideRemoteFullAccess) {
                    this.close();
                    return true;
                }
                toggleTopicsExpanded(false);
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private char keyToSearchCharacter(KeyInput input) {
        if (input == null) {
            return 0;
        }
        int key = input.key();
        if (key >= 65 && key <= 90) {
            return (char) ('a' + (key - 65));
        }
        if (key >= 48 && key <= 57) {
            return (char) ('0' + (key - 48));
        }
        return switch (key) {
            case 32 -> ' ';
            case 44 -> ',';
            case 45 -> '-';
            case 46 -> '.';
            case 47 -> '/';
            case 95 -> '_';
            default -> 0;
        };
    }

    private TopicEntry getTopicEntryAt(double mouseX, double mouseY) {
        if (!isMouseOverTopicPanel(mouseX, mouseY)) {
            return null;
        }
        int gridX = getTopicRowX();
        int gridY = getTopicListTop();
        int gridW = getTopicRowWidth();
        int gridH = getTopicListHeight();
        if (mouseX < gridX || mouseX >= gridX + gridW || mouseY < gridY || mouseY >= gridY + gridH) {
            return null;
        }

        QuickGridLayout layout = computeQuickGridLayout(gridW, gridH);
        int cols = layout.cols;
        int rows = layout.rows;
        int bw = layout.buttonW;
        int bh = layout.buttonH;
        int gap = layout.gap;

        int col = (int) ((mouseX - gridX) / (bw + gap));
        int row = (int) ((mouseY - gridY) / (bh + gap));
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return null;
        }
        int cellX = gridX + col * (bw + gap);
        int cellY = gridY + row * (bh + gap);
        if (mouseX >= cellX + bw || mouseY >= cellY + bh) {
            // Click landed in the gap between buttons.
            return null;
        }
        int index = row * cols + col;
        int maxButtons = Math.min(QUICK_TOPIC_ENTRIES.size(), cols * rows);
        if (index < 0 || index >= maxButtons) {
            return null;
        }
        return QUICK_TOPIC_ENTRIES.get(index);
    }


    private int getFollowEntryIndex(List<TopicEntry> entries) {
        return getSkillEntryIndex(entries, TopicAction.FOLLOW);
    }

    private int getWoodcutEntryIndex(List<TopicEntry> entries) {
        return getSkillEntryIndex(entries, TopicAction.SKILL_WOODCUT);
    }

    private void handleTopicEntry(TopicEntry entry) {
        if (entry == null) {
            return;
        }

        if (isSkillHeaderEntry(entry)) {
            return;
        }

        // Admin topics include both operator-only utilities and gated companion commands.
        if (entry.category == TopicCategory.ADMIN) {
            if (isAdminHeaderEntry(entry)) {
                return;
            }
            if (!isAdminEntryEnabled(entry)) {
                return;
            }
            if (entry.label != null && !entry.label.isBlank()) {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias,
                        "You (Admin): " + entry.label);
            }

            if (entry.action != null) {
                handleTopicAction(entry.action);
            } else {
                handleAdminTopic(entry.dialogueKey);
            }
            return;
        }

        // Dialogue topics are lore/quest conversation and should not trigger bot skills.
        if (entry.category == TopicCategory.DIALOGUE) {
            // Selecting a topic from the list should NOT auto-append a player line.
            // Player lines are reserved for explicit red responses.
            handleDialogueTopicEntry(entry, false);
            return;
        }

        if (entry.action == null) {
            return;
        }

        handleTopicAction(entry.action);
    }

    private void handleDialogueTopicEntry(TopicEntry entry, boolean appendPlayerResponse) {
        if (entry == null) {
            return;
        }
        if (entry.category != TopicCategory.DIALOGUE) {
            return;
        }
        if (!isDialogueEntryEnabled(entry)) {
            return;
        }

        // Morrowind-style: Goodbye simply closes the dialogue overlay.
        String key = entry.dialogueKey != null ? entry.dialogueKey.trim().toLowerCase(Locale.ROOT) : "";
        if (key.equals("goodbye")) {
            toggleTopicsExpanded(false);
            return;
        }

        // Remember selected topic for the topic-header style dialogue column.
        lastDialogueTopicLabel = entry.label != null ? entry.label : "";
        lastDialogueTopicKey = entry.dialogueKey != null ? entry.dialogueKey : "";

        // Only append player dialogue when the player explicitly clicks a red response.
        if (appendPlayerResponse) {
            String prompt = getDialoguePrompt(entry.dialogueKey, entry.label);
            if (prompt != null && !prompt.isBlank()) {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "You: " + prompt);
            }
        }

        handleDialogueTopic(entry.dialogueKey);
    }

    private void handleTopicAction(TopicAction action) {
        if (action == null) {
            return;
        }
        switch (action) {
            case COMPANION_COME -> runCompanionCome();
            case COMPANION_SUMMON -> runCompanionSummon();
            case COMPANION_HOME -> runCompanionHome();
            case OPEN_SPELLS -> openSpellsMenu();
            case SPELL_REMOTE_GUIDANCE -> openSpellGuidanceConfirm();
            case SPELL_CHORUS_RECALL -> openSpellRecallConfirm();
            case SPELL_SOUL_OF_ENDER -> castSoulOfEnder();
            case SPELL_REMOTE_INVENTORY -> sendChatCommand("bot companion open " + formatBotTarget());
            case OPEN_GUIDE -> openGuideMenu();
            case OPEN_BOT_CONTROLS -> openBotControls();
            case OPEN_PLAYER_SETTINGS -> openAdminPlayerSettings();
            case ADMIN_PREVIEW_NON_ADMIN -> toggleAdminPreviewAsNonAdmin();
            case AUTONOMOUS_RESCUES -> toggleAutonomousRescues();
            case OWNED_SUNSET_SS -> toggleOwnedSunsetSelfSufficientBulk();
            case OPEN_SKIN_CHOOSER -> openSkinChooser();
            case SKIN_POLICY_EVERYONE -> toggleSkinPolicyEveryone();
            case SKIN_POLICY_CUSTOM -> toggleSkinPolicyCustom();
            case STOP -> runStop();
            case RESUME -> runResume();
            case FOLLOW -> toggleFollow();
            case GUARD -> toggleGuard();
            case PATROL -> togglePatrol();
            case RETURN_HOME -> runReturnHome();
            case SLEEP -> runSleep();
            case AUTO_RETURN_SUNSET -> toggleAutoReturnSunset();
            case AUTO_RETURN_SELF_SUFFICIENT -> toggleAutoReturnSelfSufficient();
            case TACTICAL_SHELTER -> toggleTacticalShelter();
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> toggleAutoReturnSunsetGuardPatrol();
            case AUTO_RETURN_SKIP_PERMISSION -> toggleAutoReturnSkipPermission();
            case IDLE_HOBBIES -> toggleIdleHobbies();
            case AUTO_HUNT_STARVING -> toggleAutoHuntStarving();
            case GAMEPLAY_TIPS -> toggleGameplayTips();
            case IDLE_HOBBIES_ANYWHERE -> toggleIdleHobbiesAnywhere();
            case BARITONE_PATHFINDER -> toggleBaritonePathfinder();
            case UNLEASH_TETHERED -> toggleUnleashTethered();
            case LEASH_ON_DISMOUNT -> toggleLeashOnDismount();
            case DROP_SWEEP -> runSkillCommand("drop_sweep", null);
            case BASES -> openBasesManager();
            case CRAFTING -> openCraftingHistory();
            case COOKING -> openCookingMenu();
            case HUNTING -> openHuntingMenu();
            case QUICK_STORE -> {
                StoreTargetPickerOverlay.activate(formatBotTarget(), "store");
                if (this.client != null) this.client.setScreen(null);
            }
            case QUICK_FETCH -> {
                StoreTargetPickerOverlay.activate(formatBotTarget(), "fetch");
                if (this.client != null) this.client.setScreen(null);
            }
            case STORAGE -> openStorageMenu();
            case CONSTRUCTION -> openConstructionMenu();
            case SKILL_FISH -> runFishSkillCommand();
            case SKILL_WOODCUT -> runWoodcutSkillCommand();
            case SKILL_WOODCUT_CLEANUP -> runSkillCommand("woodcut_cleanup", null);
            case SKILL_WOOL -> runWoolSkillCommand();
            case SKILL_HOVEL -> runShelterWithLook("hovel");
            case SKILL_BURROW -> runShelterWithLook("burrow");
            case SKILL_FARM -> runSkillCommand("farm", null);
            case SKILL_COLLECT_DIRT -> runSkillCommand("collect_dirt", null);
            case SKILL_MINING -> runSkillCommand("mining", null);
            case SKILL_STRIPMINE -> runStripmineSkillCommand();
            case SKILL_ASCENT -> runAscentSkillCommand();
            case SKILL_DESCENT -> runDescentSkillCommand();
        }
    }

    private static String getDialoguePrompt(String key, String fallbackLabel) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "stay_conditions" -> "What would make you stay?";
            case "village_missing" -> "What's missing here?";
            case "village_projects" -> "What projects should we build next?";
            case "companion_status" -> "How are we doing here?";
            case "companion_check" -> "Check our progress.";
            case "companion_anchor_set" -> "Let's make this our home.";
            case "village_about" -> "Tell me about the village.";
            case "batch3_biomes" -> "Tell me about these biomes.";
            case "batch3_structures" -> "Tell me about the structures we've seen.";
            case "batch3_dimensions" -> "Tell me about the dimensions.";
            case "batch3_traders_mounts" -> "Tell me about traders and mounts.";
            case "batch3_travel" -> "Tell me about travel.";
            case "bot_past" -> "Tell me about your past.";
            case "promise" -> "I have a promise to make.";
            case "goodbye" -> "Goodbye.";
            case "recruit_contact" -> "Can we talk?";
            case "recruit_replay" -> "Let's start over.";
            default -> fallbackLabel;
        };
    }

    private void handleDialogueTopic(String key) {
        String k = key == null ? "" : key;
        String normalized = k.trim().toLowerCase(Locale.ROOT);

        // Morrowind-style: Goodbye just closes the dialogue.
        if (normalized.equals("goodbye")) {
            toggleTopicsExpanded(false);
            return;
        }

        // Recruitment: ask the server to open the recruitment dialogue (server validates village proximity).
        if (normalized.equals("recruit_contact")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                ClientPlayNetworking.send(new RequestRecruitmentDialoguePayload());
            } else {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "... (not connected)");
            }
            return;
        }

        // Recruitment replay/reset: asks server to despawn/reset recruitment state (server validates auth).
        if (normalized.equals("recruit_replay")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                String alias = net.wcfcarolina13.FrensClient.getRecruitmentBotAlias();
                ClientPlayNetworking.send(new RequestRecruitmentReplayPayload(alias));
            } else {
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "... (not connected)");
            }
            return;
        }

        java.util.function.Consumer<String> bot = (line) -> {
            if (line == null || line.isBlank()) return;
            net.wcfcarolina13.FrensClient.appendDialogue(botAlias, botAlias + ": " + line);
        };

        // Server-authoritative companion quest topics (real checks + persistent stage changes).
        if (normalized.equals("companion_status")
                || normalized.equals("companion_check")
                || normalized.equals("companion_anchor_set")
                || normalized.equals("village_missing")
                || normalized.equals("village_projects")
                || normalized.equals("batch3_biomes")
                || normalized.equals("batch3_structures")
                || normalized.equals("batch3_dimensions")
                || normalized.equals("batch3_traders_mounts")
                || normalized.equals("batch3_travel")) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                ClientPlayNetworking.send(new CompanionQuestTopicPayload(botAlias, normalized));
            } else {
                bot.accept("...");
            }
            return;
        }

        switch (normalized) {
            case "village_about" -> {
                bot.accept("Villages are fragile.");
                bot.accept("But they're the closest thing this world has to a promise.");
                bot.accept("If you want me to stay… make it safe. Make it real.");
            }
            case "stay_conditions" -> {
                bot.accept("I don't pledge myself to wandering.");
                bot.accept("Give me a bed that stays mine. Food that doesn't run out. A place I can defend.");
                bot.accept("And proof that when trouble comes… you don't run.");
            }
            case "bot_past" -> {
                bot.accept("I used to think I could do everything alone.");
                bot.accept("Turns out the world doesn't reward pride.");
                bot.accept("If you want a companion, build a home worth returning to.");
            }
            case "promise" -> {
                bot.accept("Words are cheap.");
                bot.accept("Bring me results. Then we'll see what you get in return.");
            }
            default -> bot.accept("...");
        }
    }

    private TopicEntry getDialogueResponseEntryAtOverlay(double mouseX, double mouseY) {
        if (dialogueResponseHitboxes.isEmpty()) {
            return null;
        }
        for (int i = 0; i < dialogueResponseHitboxes.size(); i++) {
            DialogueResponseHitbox h = dialogueResponseHitboxes.get(i);
            if (h == null) continue;
            Rect r = h.rect;
            if (r != null && r.contains(mouseX, mouseY)) {
                return h.entry;
            }
        }
        return null;
    }

    private java.util.List<TopicEntry> getDialogueResponseEntries(int max) {
        int limit = Math.max(0, max);
        String current = lastDialogueTopicKey != null ? lastDialogueTopicKey.trim().toLowerCase(Locale.ROOT) : "";
        java.util.ArrayList<TopicEntry> out = new java.util.ArrayList<>();
        for (TopicEntry e : DIALOGUE_TOPIC_ENTRIES) {
            if (e == null) continue;
            String k = e.dialogueKey != null ? e.dialogueKey.trim().toLowerCase(Locale.ROOT) : "";
            if (k.equals("goodbye")) continue;
            if (!current.isEmpty() && k.equals(current)) continue;
            if (!isDialogueEntryEnabled(e)) continue;
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private String formatDialogueLineForDisplay(String line) {
        if (line == null) return "";
        String s = line.trim();
        if (s.isEmpty()) return "";

        String botPrefix = (botAlias != null && !botAlias.isBlank()) ? (botAlias + ":") : null;
        if (botPrefix != null && s.startsWith(botPrefix)) {
            s = s.substring(botPrefix.length()).trim();
            return s;
        }
        if (s.startsWith("You:")) {
            return s.substring("You:".length()).trim();
        }
        // Keep system/admin prefixes for clarity.
        return s;
    }

    private boolean isAdminEntryEnabled(TopicEntry entry) {
        if (entry == null || entry.category != TopicCategory.ADMIN) {
            return false;
        }
        if (isAdminHeaderEntry(entry)) {
            return false;
        }

        boolean isAdmin = isAdminUser();
        if (entry.action == TopicAction.OPEN_PLAYER_SETTINGS || entry.action == TopicAction.ADMIN_PREVIEW_NON_ADMIN) {
            return isAdmin;
        }

        if (isAdmin && !adminPreviewAsNonAdmin) {
            return true;
        }

        return adminPermissionAllowedForEntry(entry, isAdmin && adminPreviewAsNonAdmin);
    }

    private boolean isAdminTabEnabled() {
        return !getVisibleAdminEntries().isEmpty();
    }

    /** Called by BotGuideScreen to jump straight to the Admin tab. */
    public boolean canShowAdminTab() {
        return isAdminTabEnabled();
    }

    /** Opens the overlay on the Admin tab. Called by BotGuideScreen. */
    public void switchToAdminTab() {
        if (isAdminTabEnabled()) {
            topicsExpanded = true;
            overlayCategory = TopicCategory.ADMIN;
            if (isAdminUser()) {
                requestAdminPermissionsSnapshot(this.botAlias);
            }
        }
    }

    private boolean canOpenSpellsMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return false;
        }
        // Spells are normally cast at an Enchanting Table. A later-stage quest reward (Wizard's Tome)
        // can allow access anywhere. An Eye of Ender can grant limited, cooldown-gated access.
        return isNearEnchantingTable(client, 4) || hasSpellbookToken(client) || hasEyeOfEnderToken(client);
    }

    /**
     * Determines whether the player can switch to the given bot alias.
     * Returns {@code null} if switching is allowed, or a reason string if blocked.
     *
     * Allowed if: admin, has Wizard's Tome / near enchanting table (full spell access),
     * or within 8 blocks of the target bot entity.
     * Goat Horn and Eye of Ender do NOT grant switch access.
     */
    private String canSwitchToBot(String alias) {
        if (alias == null || alias.isBlank()) {
            return "Invalid bot";
        }
        if (alias.equalsIgnoreCase(botAlias)) {
            return null;
        }
        if (isAdminUser()) {
            return null;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && (hasSpellbookToken(client) || isNearEnchantingTable(client, 4))) {
            return null;
        }
        if (client != null && client.player != null && client.world != null) {
            for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                if (p == null) continue;
                String name = p.getName() != null ? p.getName().getString() : "";
                if (name.equalsIgnoreCase(alias)) {
                    if (client.player.squaredDistanceTo(p) <= 64.0) {
                        return null;
                    }
                    return "Too far from " + alias;
                }
            }
            return "Too far from " + alias;
        }
        return "Cannot switch right now";
    }

    private void showBotSwitchBlockedWarning(String reason) {
        if (this.client != null && this.client.player != null) {
            long now = System.currentTimeMillis();
            if (now - lastBotSwitchHintAtMs >= BOT_SWITCH_HINT_COOLDOWN_MS) {
                lastBotSwitchHintAtMs = now;
                this.client.player.sendMessage(Text.literal(reason).styled(s -> s.withColor(0xFFAA00)), true);
            }
        }
    }

    private boolean hasEyeOfEnderToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        var player = client.player;
        if (player == null) {
            return false;
        }
        var inv = player.getInventory();
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

    private boolean isNearEnchantingTable(MinecraftClient client, int radius) {
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

    private boolean hasSpellbookToken(MinecraftClient client) {
        if (client == null || client.player == null) {
            return false;
        }
        // Preferred token: the dedicated quest item Wizard's Tome.
        // Back-compat: older builds used a renamed book token containing "spellbook".
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
            String lower = name != null ? name.toLowerCase(Locale.ROOT) : "";
            if (lower.contains("spellbook")) {
                return true;
            }
            if (lower.contains("wizard") && lower.contains("tome")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasItem(MinecraftClient client, net.minecraft.item.Item item) {
        if (client == null || client.player == null || item == null) {
            return false;
        }
        var inv = client.player.getInventory();
        int n = inv.size();
        for (int i = 0; i < n; i++) {
            if (inv.getStack(i).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private void handleAdminTopic(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "Admin: not connected.");
            return;
        }

        // Non-recruitment admin convenience actions.
        if (k.equals("give_wizard_tome")) {
            ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "give_wizard_tome", 1, false));
            return;
        }

        // Confirm reset: require a second click within a short window.
        if (k.equals("recruit_reset")) {
            long now = System.currentTimeMillis();
            long windowMs = 5000L;
            boolean within = adminResetConfirmArmed && (now - adminResetConfirmArmedAtMs) <= windowMs;
            if (!within) {
                adminResetConfirmArmed = true;
                adminResetConfirmArmedAtMs = now;
                net.wcfcarolina13.FrensClient.appendDialogue(botAlias, "Admin: Click again within 5s to CONFIRM reset.");
                return;
            }
            // Proceed and disarm.
            adminResetConfirmArmed = false;
            adminResetConfirmArmedAtMs = 0L;
        } else {
            // Any other admin action disarms the reset confirmation.
            adminResetConfirmArmed = false;
            adminResetConfirmArmedAtMs = 0L;
        }

        String action;
        int intArg = 0;
        boolean boolArg = false;

        if (k.equals("recruit_status")) {
            action = "status";
        } else if (k.equals("recruit_reset")) {
            action = "reset";
        } else if (k.equals("recruit_enable")) {
            action = "enable";
        } else if (k.equals("recruit_disable")) {
            action = "disable";
        } else if (k.equals("anchor_set")) {
            action = "setanchor_here";
        } else if (k.equals("anchor_clear")) {
            action = "clearanchor";
        } else if (k.startsWith("setstage:")) {
            action = "setstage";
            try {
                intArg = Integer.parseInt(k.substring("setstage:".length()).trim());
            } catch (NumberFormatException ignored) {
                intArg = 0;
            }
        } else {
            action = k;
        }

        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, action, intArg, boolArg));
    }

    private boolean isEntryActive(TopicAction action) {
        return switch (action) {
            case FOLLOW -> isFollowActive();
            case GUARD -> isGuardActive();
            case PATROL -> isPatrolActive();
            case RETURN_HOME -> isReturningToBase();
            case AUTO_RETURN_SUNSET -> isAutoReturnAtSunsetActive();
            case AUTO_RETURN_SELF_SUFFICIENT -> isAutoReturnSelfSufficientActive();
            case TACTICAL_SHELTER -> isTacticalShelterActive();
            case AUTO_RETURN_SUNSET_GUARD_PATROL -> isAutoReturnGuardPatrolEligibleActive();
            case AUTO_RETURN_SKIP_PERMISSION -> isAutoReturnSkipPermissionActive();
            case IDLE_HOBBIES -> isIdleHobbiesActive();
            case AUTO_HUNT_STARVING -> isAutoHuntStarvingActive();
            case GAMEPLAY_TIPS -> isGameplayTipsActive();
            case IDLE_HOBBIES_ANYWHERE -> isIdleHobbiesAnywhereActive();
            case BARITONE_PATHFINDER -> isBaritonePathfinderActive();
            case ADMIN_PREVIEW_NON_ADMIN -> adminPreviewAsNonAdmin;
            case AUTONOMOUS_RESCUES -> isAutonomousRescuesActive();
            case OWNED_SUNSET_SS -> ownedSunsetSelfSufficientAggregateState() == 1;
            case SKIN_POLICY_EVERYONE -> isSkinPolicyEveryoneActive();
            case SKIN_POLICY_CUSTOM -> isSkinPolicyCustomActive();
            case UNLEASH_TETHERED -> isUnleashTetheredActive();
            case LEASH_ON_DISMOUNT -> isLeashOnDismountActive();
            default -> false;
        };
    }

    private boolean isSkinPolicyEveryoneActive() {
        return FrensClient.isSkinChangeForEveryoneEnabled();
    }

    private boolean isSkinPolicyCustomActive() {
        return FrensClient.isCustomSkinsEnabled();
    }

    private boolean isAutonomousRescuesActive() {
        AdminPermissionsCache cache = currentAdminPermissions();
        return cache != null && cache.autonomousRescuesEnabled;
    }

    private int ownedSunsetSelfSufficientAggregateState() {
        AdminPermissionsCache cache = currentAdminPermissions();
        return cache != null ? cache.ownedSunsetSelfSufficientState : -2;
    }

    private String ownedSunsetSelfSufficientStatusLabel() {
        return switch (ownedSunsetSelfSufficientAggregateState()) {
            case 1 -> "ON";
            case 0 -> "OFF";
            case -1 -> "MIX";
            default -> "--";
        };
    }

    private boolean isEntryEnabled(TopicAction action) {
        return switch (action) {
            // Stop should always be available (e.g., cancel return-to-base, follow, guard/patrol, etc.).
            case STOP -> true;
            case RESUME -> this.handler != null && this.handler.isBotTaskPaused();
            default -> true;
        };
    }

    private boolean isSpellEntryEnabled(TopicEntry entry) {
        if (entry == null || entry.action == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        // Player-side access.
        boolean full = isNearEnchantingTable(mc, 4) || hasSpellbookToken(mc);
        boolean eye = !full && hasEyeOfEnderToken(mc);
        boolean playerHasPearl = hasEnderPearlInInventory(mc);
        boolean playerHasChorus = playerHasPearl && hasChorusFruitInInventory(mc);
        // Bot-side access: check bot inventory + proximity to enchanting table.
        if (!full && this.handler != null) {
            for (int i = 0; i < 41; i++) {
                var stack = this.handler.getSlot(i).getStack();
                if (stack == null || stack.isEmpty()) continue;
                if (stack.isOf(net.wcfcarolina13.items.ModItems.WIZARD_TOME)) { full = true; break; }
                if (stack.isOf(Items.ENDER_EYE)) { eye = true; }
            }
        }
        if (!full) {
            full = isBotNearEnchantingTable(mc, 4);
        }
        return switch (entry.action) {
            case SPELL_REMOTE_GUIDANCE -> full || playerHasPearl;
            case SPELL_CHORUS_RECALL -> full || playerHasChorus;
            case SPELL_SOUL_OF_ENDER -> full || eye || playerHasPearl;
            case SPELL_REMOTE_INVENTORY -> full;
            default -> false;
        };
    }

    /** Check if the bot entity (looked up by alias) is near an enchanting table in the client world. */
    private boolean isBotNearEnchantingTable(MinecraftClient client, int radius) {
        if (client == null || client.world == null || botAlias == null || botAlias.isBlank()) {
            return false;
        }
        // Find bot entity by name in the client world.
        net.minecraft.util.math.BlockPos botPos = null;
        for (net.minecraft.client.network.AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p != null && botAlias.equalsIgnoreCase(p.getName().getString())) {
                botPos = p.getBlockPos();
                break;
            }
        }
        if (botPos == null) return false;
        int r = Math.max(1, radius);
        for (net.minecraft.util.math.BlockPos pos : net.minecraft.util.math.BlockPos.iterate(
                botPos.add(-r, -2, -r), botPos.add(r, 2, r))) {
            if (!client.world.isChunkLoaded(pos)) continue;
            if (client.world.getBlockState(pos).isOf(net.minecraft.block.Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEnderPearlInInventory(MinecraftClient client) {
        if (client == null || client.player == null) return false;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) return true;
        }
        return false;
    }

    private boolean hasChorusFruitInInventory(MinecraftClient client) {
        if (client == null || client.player == null) return false;
        var inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(Items.CHORUS_FRUIT)) return true;
        }
        return false;
    }

    private boolean isAutoReturnAtSunsetActive() {
        return this.handler != null && this.handler.isBotAutoReturnAtSunset();
    }

    private boolean isAutoReturnSelfSufficientActive() {
        return this.handler != null && this.handler.isBotAutoReturnSelfSufficientFallback();
    }

    private boolean isTacticalShelterActive() {
        return this.handler != null && this.handler.isBotTacticalShelterEnabled();
    }

    private boolean isIdleHobbiesActive() {
        return this.handler != null && this.handler.isBotIdleHobbiesEnabled();
    }

    private boolean isAutoHuntStarvingActive() {
        return this.handler != null && this.handler.isBotAutoHuntStarvingEnabled();
    }

    private boolean isAutoReturnGuardPatrolEligibleActive() {
        return this.handler != null && this.handler.isBotAutoReturnGuardPatrolEligible();
    }

    private boolean isAutoReturnSkipPermissionActive() {
        return this.handler != null && this.handler.isBotAutoReturnSkipPermission();
    }

    private String buildSkillCommand(String skillName, String action) {
        String botTarget = formatBotTarget();
        String args = action != null && !action.isBlank()
                ? action + " " + botTarget
                : botTarget;
        return "bot skill " + skillName + " " + args;
    }

    private void runSkillCommand(String skillName, String action) {
        String command = buildSkillCommand(skillName, action);
        sendChatCommand(command);
        this.close();
    }

    private void queueDirectionalMiningCommand(String actionLabel, String skillName, String action) {
        String command = buildSkillCommand(skillName, action);
        net.wcfcarolina13.FrensClient.setPendingDirectionalMining(actionLabel, command);
        this.close();
    }

    private void runWoodcutSkillCommand() {
        runSkillCommand("woodcut", Integer.toString(woodcutTreeCount));
    }

    private void runFishSkillCommand() {
        String arg = fishTargetCount > 0 ? Integer.toString(fishTargetCount) : null;
        runSkillCommand("fish", arg);
    }

    private void runWoolSkillCommand() {
        String arg = woolTargetCount > 0 ? Integer.toString(woolTargetCount) : null;
        if (woolSearchRange != SKILL_WOOL_RANGE_DEFAULT) {
            String rangeArg = "range=" + woolSearchRange;
            arg = arg != null ? arg + " " + rangeArg : rangeArg;
        }
        runSkillCommand("wool", arg);
    }

    private void runStripmineSkillCommand() {
        String arg = Integer.toString(stripmineLength > 0 ? stripmineLength : SKILL_STRIPMINE_COUNT_DEFAULT);
        queueDirectionalMiningCommand("stripmine", "stripmine", arg);
    }

    private void runAscentSkillCommand() {
        String arg;
        if (ascentSurfaceMode) {
            arg = "ascent surface";
        } else if (ascentBlocks > 0) {
            arg = "ascent " + ascentBlocks;
        } else {
            arg = "ascent " + SKILL_ASCENT_COUNT_DEFAULT;
        }
        queueDirectionalMiningCommand("ascent", "mining", arg);
    }

    private void runDescentSkillCommand() {
        String arg = "descent " + (descentBlocks > 0 ? descentBlocks : SKILL_DESCENT_COUNT_DEFAULT);
        queueDirectionalMiningCommand("descent", "mining", arg);
    }

    private boolean isMouseOverTopicPanel(double mouseX, double mouseY) {
        int panelX = getTopicPanelX();
        int panelY = getTopicPanelY();
        int panelW = getTopicPanelWidth();
        int panelH = getTopicPanelHeight();
        return mouseX >= panelX && mouseX < panelX + panelW
                && mouseY >= panelY && mouseY < panelY + panelH;
    }

    private int getTopicPanelX() {
        return this.x + SECTION_WIDTH + BLOCK_GAP;
    }

    private int getTopicPanelY() {
        return this.y + SECTION_HEIGHT + 2;
    }

    private int getTopicPanelWidth() {
        return SECTION_WIDTH;
    }

    private int getTopicPanelHeight() {
        return STATS_AREA_HEIGHT - 4;
    }

    private int getTopicRowX() {
        return getTopicPanelX() + TOPIC_PADDING;
    }

    private int getTopicRowWidth() {
        return getTopicPanelWidth() - TOPIC_PADDING * 2;
    }

    private int getTopicListTop() {
        return getTopicPanelY() + 2 + this.textRenderer.fontHeight + 1;
    }

    private int getTopicListHeight() {
        return getTopicPanelHeight() - (getTopicListTop() - getTopicPanelY());
    }


    private List<TopicEntry> getOverlayEntries() {
        return switch (overlayCategory) {
            case DIALOGUE -> DIALOGUE_TOPIC_ENTRIES;
            case SPELL -> SPELL_TOPIC_ENTRIES;
            case ADMIN -> getVisibleAdminEntries();
            default -> SKILL_TOPIC_ENTRIES;
        };
    }

    private static final Map<String, Boolean> DEFAULT_ADMIN_PERMISSION_GLOBALS = Map.ofEntries(
            Map.entry("open_bot_controls", true),
            Map.entry("open_spells", true),
            Map.entry("open_skin_chooser", true),
            Map.entry("gameplay_tips", true),
            Map.entry("idle_hobbies_anywhere", true),
            Map.entry("baritone_pathfinder", true),
            Map.entry("skin_policy_everyone", false),
            Map.entry("skin_policy_custom", false),
            Map.entry("wizard_tome", false),
            Map.entry("learning_manage", false),
            Map.entry("recruit_manage", false),
            Map.entry("rescue_manage", false),
            Map.entry("recruit_reset", false),
            Map.entry("village_anchor", false),
            Map.entry("stage_debug", false)
    );

    private static String normalizedAliasKey(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeUuidKey(String uuid) {
        return uuid == null ? "" : uuid.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isAdminHeaderEntry(TopicEntry entry) {
        return entry != null
                && entry.category == TopicCategory.ADMIN
                && entry.action == null
                && "__header__".equals(entry.dialogueKey);
    }

    private boolean isSkillHeaderEntry(TopicEntry entry) {
        return entry != null
                && entry.category == TopicCategory.SKILL
                && entry.action == null
                && "__header__".equals(entry.dialogueKey);
    }

    private String permissionKeyForAdminEntry(TopicEntry entry) {
        if (entry == null || entry.category != TopicCategory.ADMIN) {
            return null;
        }
        if (entry.action != null) {
            return switch (entry.action) {
                case OPEN_BOT_CONTROLS -> "open_bot_controls";
                case OPEN_SPELLS -> "open_spells";
                case OPEN_SKIN_CHOOSER -> "open_skin_chooser";
                case GAMEPLAY_TIPS -> "gameplay_tips";
                case IDLE_HOBBIES_ANYWHERE -> "idle_hobbies_anywhere";
                case BARITONE_PATHFINDER -> "baritone_pathfinder";
                case AUTONOMOUS_RESCUES -> "rescue_manage";
                case OWNED_SUNSET_SS -> "recruit_manage";
                case SKIN_POLICY_EVERYONE -> "skin_policy_everyone";
                case SKIN_POLICY_CUSTOM -> "skin_policy_custom";
                default -> null;
            };
        }

        String key = entry.dialogueKey != null ? entry.dialogueKey.trim().toLowerCase(Locale.ROOT) : "";
        if (key.startsWith("setstage:")) {
            return "stage_debug";
        }
        return switch (key) {
            case "give_wizard_tome" -> "wizard_tome";
            case "learning_status", "learning_start", "learning_stop_success", "learning_stop_fail", "learning_stop_abort" -> "learning_manage";
            case "recruit_status", "recruit_enable", "recruit_disable" -> "recruit_manage";
            case "recruit_reset" -> "recruit_reset";
            case "anchor_set", "anchor_clear" -> "village_anchor";
            default -> null;
        };
    }

    private AdminPermissionsCache currentAdminPermissions() {
        String key = normalizedAliasKey(botAlias);
        synchronized (ADMIN_PERMISSIONS_BY_ALIAS) {
            return ADMIN_PERMISSIONS_BY_ALIAS.get(key);
        }
    }

    private boolean adminPermissionAllowedForEntry(TopicEntry entry, boolean previewAsNonAdmin) {
        String permissionKey = permissionKeyForAdminEntry(entry);
        if (permissionKey == null || permissionKey.isBlank()) {
            return false;
        }

        boolean fallback = DEFAULT_ADMIN_PERMISSION_GLOBALS.getOrDefault(permissionKey, false);
        AdminPermissionsCache cache = currentAdminPermissions();
        if (cache == null) {
            return fallback;
        }

        boolean allowed = cache.globalDefaults != null
                ? cache.globalDefaults.getOrDefault(permissionKey, fallback)
                : fallback;

        if (!previewAsNonAdmin && this.client != null && this.client.player != null && cache.userOverrides != null) {
            String uuid = normalizeUuidKey(this.client.player.getUuidAsString());
            Map<String, Boolean> userMap = cache.userOverrides.get(uuid);
            if (userMap != null && userMap.containsKey(permissionKey)) {
                return Boolean.TRUE.equals(userMap.get(permissionKey));
            }
        }
        return allowed;
    }

    private List<TopicEntry> getVisibleAdminEntries() {
        boolean isAdmin = isAdminUser();
        boolean preview = isAdmin && adminPreviewAsNonAdmin;
        java.util.ArrayList<TopicEntry> visible = new java.util.ArrayList<>();
        TopicEntry pendingHeader = null;

        for (TopicEntry entry : ADMIN_TOPIC_ENTRIES) {
            if (entry == null) {
                continue;
            }

            if (isAdminHeaderEntry(entry)) {
                pendingHeader = entry;
                continue;
            }

            boolean show;
            if (entry.action == TopicAction.OPEN_PLAYER_SETTINGS
                    || entry.action == TopicAction.ADMIN_PREVIEW_NON_ADMIN
                    || entry.action == TopicAction.AUTONOMOUS_RESCUES) {
                show = isAdmin;
            } else if (isAdmin && !preview) {
                show = true;
            } else {
                show = adminPermissionAllowedForEntry(entry, preview);
            }

            if (!show) {
                continue;
            }

            if (pendingHeader != null) {
                visible.add(pendingHeader);
                pendingHeader = null;
            }
            visible.add(entry);
        }
        return visible;
    }

    private void setTopicSearchQuery(String raw) {
        String next = raw != null ? raw : "";
        if (next.length() > 64) {
            next = next.substring(0, 64);
        }
        if (next.equals(topicSearchQuery)) {
            return;
        }
        topicSearchQuery = next;
        skillScrollIndex = 0;
        dialogueScrollIndex = 0;
        adminScrollIndex = 0;
    }

    private List<TopicEntry> getFilteredOverlayEntries(List<TopicEntry> base) {
        if (base == null || base.isEmpty()) {
            return List.of();
        }
        String q = topicSearchQuery != null ? topicSearchQuery.trim().toLowerCase(Locale.ROOT) : "";
        if (q.isBlank()) {
            return base;
        }
        java.util.ArrayList<TopicEntry> out = new java.util.ArrayList<>();
        for (TopicEntry entry : base) {
            if (entry == null) {
                continue;
            }
            String label = entry.label != null ? entry.label.toLowerCase(Locale.ROOT) : "";
            String aux = "";
            if (entry.category == TopicCategory.DIALOGUE) {
                String prompt = getDialoguePrompt(entry.dialogueKey, entry.label);
                aux = prompt != null ? prompt.toLowerCase(Locale.ROOT) : "";
            } else if (entry.category == TopicCategory.ADMIN) {
                aux = entry.dialogueKey != null ? entry.dialogueKey.toLowerCase(Locale.ROOT) : "";
            }
            if (label.contains(q) || aux.contains(q)) {
                out.add(entry);
            }
        }
        return out;
    }

    private int getOverlayScrollIndex() {
        return switch (overlayCategory) {
            case DIALOGUE -> dialogueScrollIndex;
            case SPELL -> spellScrollIndex;
            case ADMIN -> adminScrollIndex;
            default -> skillScrollIndex;
        };
    }

    private void setOverlayScrollIndex(int value) {
        switch (overlayCategory) {
            case DIALOGUE -> dialogueScrollIndex = value;
            case SPELL -> spellScrollIndex = value;
            case ADMIN -> adminScrollIndex = value;
            default -> skillScrollIndex = value;
        }
    }

    private void clampOverlayScroll(int visibleRows) {
        List<TopicEntry> entries = getFilteredOverlayEntries(getOverlayEntries());
        int maxScroll = Math.max(0, getOverlayVisualRowCount(entries) - visibleRows);
        setOverlayScrollIndex(MathHelper.clamp(getOverlayScrollIndex(), 0, maxScroll));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void handledScreenTick() {
        super.handledScreenTick();
        if (heldAdjustAction != null) {
            heldAdjustTicks++;
            if (heldAdjustTicks >= HOLD_REPEAT_INITIAL_DELAY) {
                int elapsed = heldAdjustTicks - HOLD_REPEAT_INITIAL_DELAY;
                int interval;
                if (heldAdjustTicks >= HOLD_REPEAT_FASTEST_AT) {
                    interval = HOLD_REPEAT_FASTEST_INTERVAL;
                } else if (heldAdjustTicks >= HOLD_REPEAT_FAST_AT) {
                    interval = HOLD_REPEAT_FAST_INTERVAL;
                } else {
                    interval = HOLD_REPEAT_SLOW_INTERVAL;
                }
                if (elapsed % interval == 0) {
                    heldAdjustAction.run();
                }
            }
        }
    }

    private boolean isFollowActive() {
        return this.handler != null && this.handler.isBotFollowing();
    }

    private boolean isGuardActive() {
        return this.handler != null && this.handler.isBotGuarding();
    }

    private boolean isPatrolActive() {
        return this.handler != null && this.handler.isBotPatrolling();
    }

    private double getFollowDistance() {
        return this.handler != null ? this.handler.getBotFollowDistance() : 0.0D;
    }

    private String formatBotTarget() {
        return formatBotTarget(botAlias);
    }

    private String formatBotTarget(String alias) {
        String value = alias != null ? alias.trim() : "";
        if (value.contains(" ")) {
            return "\"" + value + "\"";
        }
        return value;
    }

    private void toggleFollow() {
        // Don't toggle off if bot is returning to base (uses FOLLOW mode internally)
        if (isReturningToBase()) {
            // Bot is returning home, starting follow would interrupt that
            // For now, just start following the player anyway (interrupts return-to-base)
        }
        String botTarget = formatBotTarget();
        String command = isFollowActive() ? "bot follow stop " + botTarget : "bot follow " + botTarget;
        sendChatCommand(command);
    }

    private boolean isReturningToBase() {
        return this.handler != null && this.handler.isBotReturningToBase();
    }

    private void adjustFollowDistance(int direction) {
        double current = getFollowDistance();
        double base = current > 0.0D ? current : FOLLOW_DISTANCE_DEFAULT;
        double next = base + (FOLLOW_DISTANCE_STEP * direction);
        next = MathHelper.clamp(next, FOLLOW_DISTANCE_MIN, FOLLOW_DISTANCE_MAX);
        String botTarget = formatBotTarget();
        String command = "bot follow-distance " + String.format(Locale.ROOT, "%.1f", next) + " " + botTarget;
        sendChatCommand(command);
    }

    private void adjustWoodcutTreeCount(int direction) {
        woodcutTreeCount = MathHelper.clamp(
                woodcutTreeCount + direction,
                WOODCUT_TREE_COUNT_MIN,
                WOODCUT_TREE_COUNT_MAX
        );
    }

    private void applySkillAdjust(SkillAdjustHit hit) {
        if (hit == null || hit.action() == null || hit.control() == null) {
            return;
        }
        if (hit.action() == TopicAction.SKILL_ASCENT && hit.control() == SkillAdjustControl.TOGGLE_MODE) {
            ascentSurfaceMode = !ascentSurfaceMode;
            if (ascentSurfaceMode) {
                ascentBlocks = SKILL_COUNT_UNSET;
            }
            return;
        }
        if (hit.action() == TopicAction.SKILL_WOOL && hit.control() == SkillAdjustControl.TOGGLE_MODE) {
            woolAdjustingRange = !woolAdjustingRange;
            return;
        }
        int delta = hit.control() == SkillAdjustControl.INCREMENT ? 1 : -1;
        switch (hit.action()) {
            case SKILL_FISH -> fishTargetCount = adjustOptionalCount(fishTargetCount, delta,
                    SKILL_FISH_COUNT_DEFAULT, SKILL_FISH_COUNT_MIN, SKILL_FISH_COUNT_MAX);
            case SKILL_WOOL -> {
                if (woolAdjustingRange) {
                    woolSearchRange = MathHelper.clamp(woolSearchRange + delta * 16,
                            SKILL_WOOL_RANGE_MIN, SKILL_WOOL_RANGE_MAX);
                } else {
                    woolTargetCount = adjustOptionalCount(woolTargetCount, delta,
                            SKILL_WOOL_COUNT_DEFAULT, SKILL_WOOL_COUNT_MIN, SKILL_WOOL_COUNT_MAX);
                }
            }
            case SKILL_STRIPMINE -> stripmineLength = adjustOptionalCount(stripmineLength, delta,
                    SKILL_STRIPMINE_COUNT_DEFAULT, SKILL_STRIPMINE_COUNT_MIN, SKILL_STRIPMINE_COUNT_MAX);
            case SKILL_ASCENT -> {
                if (ascentSurfaceMode) {
                    ascentSurfaceMode = false;
                }
                ascentBlocks = adjustOptionalCount(ascentBlocks, delta,
                        SKILL_ASCENT_COUNT_DEFAULT, SKILL_ASCENT_COUNT_MIN, SKILL_ASCENT_COUNT_MAX);
            }
            case SKILL_DESCENT -> descentBlocks = adjustOptionalCount(descentBlocks, delta,
                    SKILL_DESCENT_COUNT_DEFAULT, SKILL_DESCENT_COUNT_MIN, SKILL_DESCENT_COUNT_MAX);
            default -> {
            }
        }
    }

    private void beginHeldAdjust(Runnable action) {
        heldAdjustAction = action;
        heldAdjustTicks = 0;
    }

    private void clearHeldAdjust() {
        heldAdjustAction = null;
        heldAdjustTicks = 0;
    }

    private void activateDirectInput(TopicAction action) {
        directInputAction = action;
        directInputBuffer = "";
        topicSearchFocused = false;
        clearHeldAdjust();
    }

    private void cancelDirectInput() {
        directInputAction = null;
        directInputBuffer = "";
    }

    private void commitDirectInput() {
        if (directInputAction == null) {
            return;
        }
        TopicAction action = directInputAction;
        String text = directInputBuffer.trim();
        directInputAction = null;
        directInputBuffer = "";
        if (text.isEmpty()) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return;
        }
        if (value <= 0) {
            return;
        }
        switch (action) {
            case SKILL_WOODCUT -> woodcutTreeCount = MathHelper.clamp(value,
                    WOODCUT_TREE_COUNT_MIN, WOODCUT_TREE_COUNT_MAX);
            case SKILL_FISH -> fishTargetCount = MathHelper.clamp(value,
                    SKILL_FISH_COUNT_MIN, SKILL_FISH_COUNT_MAX);
            case SKILL_WOOL -> woolTargetCount = MathHelper.clamp(value,
                    SKILL_WOOL_COUNT_MIN, SKILL_WOOL_COUNT_MAX);
            case SKILL_STRIPMINE -> stripmineLength = MathHelper.clamp(value,
                    SKILL_STRIPMINE_COUNT_MIN, SKILL_STRIPMINE_COUNT_MAX);
            case SKILL_ASCENT -> {
                if (ascentSurfaceMode) {
                    ascentSurfaceMode = false;
                }
                ascentBlocks = MathHelper.clamp(value,
                        SKILL_ASCENT_COUNT_MIN, SKILL_ASCENT_COUNT_MAX);
            }
            case SKILL_DESCENT -> descentBlocks = MathHelper.clamp(value,
                    SKILL_DESCENT_COUNT_MIN, SKILL_DESCENT_COUNT_MAX);
            default -> {}
        }
    }

    private int adjustOptionalCount(int current, int direction, int defaultValue, int min, int max) {
        if (direction == 0) {
            return MathHelper.clamp(current, SKILL_COUNT_UNSET, max);
        }
        if (current <= SKILL_COUNT_UNSET) {
            return direction > 0 ? MathHelper.clamp(defaultValue, min, max) : SKILL_COUNT_UNSET;
        }
        int next = current + direction;
        if (next < min) {
            return SKILL_COUNT_UNSET;
        }
        return MathHelper.clamp(next, min, max);
    }

    private void toggleGuard() {
        String botTarget = formatBotTarget();
        String command = isGuardActive() ? "bot stop " + botTarget : "bot guard " + botTarget;
        sendChatCommand(command);
    }

    private void togglePatrol() {
        String botTarget = formatBotTarget();
        String command = isPatrolActive() ? "bot stop " + botTarget : "bot patrol " + botTarget;
        sendChatCommand(command);
    }

    private void runStop() {
        String botTarget = formatBotTarget();
        String command = "bot stop " + botTarget;
        sendChatCommand(command);
        
        // Also clear any pending shelter placement
        net.wcfcarolina13.FrensClient.clearPendingShelter();
        net.wcfcarolina13.FrensClient.clearPendingDirectionalMining();
    }

    private void runResume() {
        String botTarget = formatBotTarget();
        String command = "bot resume " + botTarget;
        sendChatCommand(command);
    }

    private void runReturnHome() {
        String botTarget = formatBotTarget();
        // Toggle: if already returning, stop; otherwise start return-to-base
        String command = isReturningToBase() ? "bot stop " + botTarget : "bot return " + botTarget;
        sendChatCommand(command);
    }

    private void runSleep() {
        String botTarget = formatBotTarget();
        String command = "bot sleep " + botTarget;
        sendChatCommand(command);
    }

    private void runCompanionCome() {
        sendChatCommand("bot companion come");
    }

    private void runCompanionSummon() {
        sendChatCommand("bot companion summon");
    }

    private void runCompanionHome() {
        sendChatCommand("bot companion home");
    }

    private void toggleAutoReturnSunset() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_sunset toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoReturnSelfSufficient() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_self_sufficient toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleTacticalShelter() {
        String botTarget = formatBotTarget();
        String command = "bot tactical_shelter toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoReturnSunsetGuardPatrol() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_sunset_guard_patrol toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoReturnSkipPermission() {
        String botTarget = formatBotTarget();
        String command = "bot auto_return_skip_permission toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleIdleHobbies() {
        String botTarget = formatBotTarget();
        String command = "bot idle_hobbies toggle " + botTarget;
        sendChatCommand(command);
    }

    private void toggleAutoHuntStarving() {
        String botTarget = formatBotTarget();
        String command = "bot auto_hunt_starving toggle " + botTarget;
        sendChatCommand(command);
    }

    private boolean isGameplayTipsActive() {
        return Frens.CONFIG == null || Frens.CONFIG.isGameplayTipsEnabled();
    }

    private void toggleGameplayTips() {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.setGameplayTipsEnabled(!Frens.CONFIG.isGameplayTipsEnabled());
        Frens.CONFIG.save();
    }

    private boolean isIdleHobbiesAnywhereActive() {
        return Frens.CONFIG != null && Frens.CONFIG.isIdleHobbiesAnywhereEnabled();
    }

    private void toggleIdleHobbiesAnywhere() {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.setIdleHobbiesAnywhereEnabled(!Frens.CONFIG.isIdleHobbiesAnywhereEnabled());
        Frens.CONFIG.save();
    }

    private boolean isBaritonePathfinderActive() {
        return Frens.CONFIG != null && Frens.CONFIG.isBaritonePathfinderEnabled();
    }

    private void toggleBaritonePathfinder() {
        if (Frens.CONFIG == null) return;
        boolean newValue = !Frens.CONFIG.isBaritonePathfinderEnabled();
        Frens.CONFIG.setBaritonePathfinderEnabled(newValue);
        Frens.CONFIG.save();
        net.wcfcarolina13.PathFinding.PathFinder.USE_BARITONE_STYLE = newValue;
    }

    private boolean isUnleashTetheredActive() {
        return this.handler != null && this.handler.isBotUnleashTetheredEnabled();
    }

    private void toggleUnleashTethered() {
        String botTarget = formatBotTarget();
        String command = "bot unleash_tethered toggle " + botTarget;
        sendChatCommand(command);
    }

    private boolean isLeashOnDismountActive() {
        return this.handler != null && this.handler.isBotLeashOnDismountEnabled();
    }

    private void toggleLeashOnDismount() {
        String botTarget = formatBotTarget();
        String command = "bot leash_on_dismount toggle " + botTarget;
        sendChatCommand(command);
    }

    private void openBasesManager() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BaseManagerScreen(this, botAlias));
    }

    private void openCraftingHistory() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new CraftingHistoryScreen(this));
    }

    private void openCookingMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new CookablesScreen(this, formatBotTarget()));
    }

    private void openHuntingMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new HuntablesScreen(this, formatBotTarget()));
    }

    private void openStorageMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BotStorageScreen(this, formatBotTarget()));
    }

    private void openConstructionMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new ConstructionScreen(this, formatBotTarget()));
    }

    private void openSpellsMenu() {
        if (this.client == null) {
            return;
        }
        if (this.client.player != null) {
            this.client.player.playSound(SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 0.28f, 1.8f);
            this.client.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.35f, 1.4f);
        }
        this.client.setScreen(new CompanionSpellsScreen(this, this.botAlias));
    }

    private void openSpellGuidanceConfirm() {
        if (this.client != null) {
            this.client.setScreen(new NavigationConfirmScreen(this, this.botAlias, "guidance"));
        }
    }

    private void openSpellRecallConfirm() {
        if (this.client != null) {
            this.client.setScreen(new NavigationConfirmScreen(this, this.botAlias, "recall"));
        }
    }

    private void castSoulOfEnder() {
        sendChatCommand("bot companion soulofender " + formatBotTarget());
    }

    private void openGuideMenu() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BotGuideScreen(this, this.botAlias));
    }

    private void openBotControls() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BotControlScreen(this));
    }

    private void openAdminPlayerSettings() {
        if (this.client == null || !isAdminUser()) {
            return;
        }
        requestAdminPermissionsSnapshot(this.botAlias);
        this.client.setScreen(new AdminPlayerSettingsScreen(this, this.botAlias));
    }

    private void toggleAdminPreviewAsNonAdmin() {
        if (!isAdminUser()) {
            return;
        }
        adminPreviewAsNonAdmin = !adminPreviewAsNonAdmin;
        net.wcfcarolina13.FrensClient.appendDialogue(
                botAlias,
            "Admin: Non-admin preview is now " + (adminPreviewAsNonAdmin ? "ON" : "OFF") + "."
        );
    }

    private void toggleAutonomousRescues() {
        if (this.client == null || this.client.getNetworkHandler() == null || !isAdminUser()) {
            return;
        }
        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "autonomous_rescues_toggle", 0, false));
    }

    private void toggleOwnedSunsetSelfSufficientBulk() {
        if (this.client == null || this.client.getNetworkHandler() == null || !isAdminUser()) {
            return;
        }
        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "owned_sunset_ss_toggle", 0, false));
    }

    private void openSkinChooser() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new BotSkinChooserScreen(this, botAlias));
    }

    private void toggleSkinPolicyEveryone() {
        if (this.client == null || this.client.getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "skin_everyone_toggle", 0, false));
    }

    private void toggleSkinPolicyCustom() {
        if (this.client == null || this.client.getNetworkHandler() == null) {
            return;
        }
        ClientPlayNetworking.send(new RecruitmentAdminActionPayload(botAlias, "skin_custom_toggle", 0, false));
    }

    /**
     * Closes the screen and sends a shelter command with @look flag.
     * The server will use the player's current look direction to determine placement.
     * For hovel: centered where player looks
     * For burrow: digs in the direction player looks
     */
    private void runShelterWithLook(String shelterType) {
        if (this.client == null) {
            return;
        }
        String botTarget = formatBotTarget();
        // Set the pending shelter type - will be used when player presses go_to_look keybind
        net.wcfcarolina13.FrensClient.setPendingShelter(shelterType, botTarget);
        // Close the screen first so player can see where they're looking
        this.close();
    }

    private void sendChatCommand(String command) {
        if (this.client == null || this.client.getNetworkHandler() == null) {
            return;
        }
        String raw = command.startsWith("/") ? command.substring(1) : command;
        this.client.getNetworkHandler().sendChatCommand(raw);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        // Guide Admin-only mode: skip inventory rendering entirely.
        // drawBackground() and drawForeground() early-return when restricted,
        // so renderBackground() only produces the dark world overlay.
        if (guideRemoteOpen && !guideRemoteFullAccess) {
            this.renderBackground(context, mouseX, mouseY, delta);
            if (topicsExpanded) {
                drawTopicsOverlay(context, mouseX, mouseY);
            }
            return;
        }

        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawEntities(context, mouseX, mouseY);
        if (topicsExpanded) {
            drawTopicsOverlay(context, mouseX, mouseY);
        }
        if (!topicsExpanded || !isMouseOverTopicsOverlay(mouseX, mouseY)) {
            this.drawMouseoverTooltip(context, mouseX, mouseY);
        }
        drawBotSwitchWarningTooltip(context, mouseX, mouseY);
        drawRemoteAccessBanner(context);
    }

    /** Draws a centred banner above the inventory when remote inventory is active via artifacts. */
    private void drawRemoteAccessBanner(DrawContext context) {
        if (!guideRemoteOpen || !guideRemoteFullAccess) {
            return;
        }
        String reason = guideRemoteAccessReason;
        if (reason == null || reason.isEmpty()) {
            return;
        }
        int padH = 4, padW = 8;
        int maxTextW = this.width - padW * 2 - 4;
        // Truncate if the text is too wide for the screen.
        if (this.textRenderer.getWidth(reason) > maxTextW) {
            reason = this.textRenderer.trimToWidth(reason, maxTextW - this.textRenderer.getWidth("...")) + "...";
        }
        int textW = this.textRenderer.getWidth(reason);
        int bannerW = textW + padW * 2;
        int bannerH = this.textRenderer.fontHeight + padH * 2;
        int bx = (this.width - bannerW) / 2;
        // Position just above the inventory panels; clamp to top of screen.
        int by = Math.max(1, this.y - bannerH - 2);
        context.fill(bx, by, bx + bannerW, by + bannerH, 0xC0101010);
        context.drawText(this.textRenderer, reason, bx + padW, by + padH, 0xFFE0C860, false);
    }

    /**
     * Shows a tooltip when hovering the label area while a blocked bot is displayed.
     */
    private void drawBotSwitchWarningTooltip(DrawContext context, int mouseX, int mouseY) {
        if (switchBrowsedBlockedAlias == null || switchBrowsedBlockReason == null) {
            return;
        }
        BotSwitchLayout layout = getActiveBotSwitchLayout();
        boolean hovering = layout != null && layout.labelRect().contains(mouseX, mouseY);
        if (!hovering) {
            return;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(switchBrowsedBlockReason);
        lines.add("Use a Wizard's Tome or move closer");
        lines.add("to this bot before switching.");
        drawTooltipBox(context, mouseX, mouseY, lines);
    }

    private void drawEntities(DrawContext context, int mouseX, int mouseY) {
        int leftX1 = this.x + 26;
        int leftY1 = this.y + 8;
        int leftX2 = this.x + 75;
        int leftY2 = this.y + 78;
        int rightX1 = this.x + SECTION_WIDTH + BLOCK_GAP + 26;
        int rightY1 = this.y + 8;
        int rightX2 = this.x + SECTION_WIDTH + BLOCK_GAP + 75;
        int rightY2 = this.y + 78;
        LivingEntity botEntity = findBotEntity();
        if (botEntity != null) {
            InventoryScreen.drawEntity(context, leftX1, leftY1, leftX2, leftY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, botEntity);
        }
        if (this.client != null && this.client.player != null) {
            InventoryScreen.drawEntity(context, rightX1, rightY1, rightX2, rightY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, this.client.player);
        }
    }

    private LivingEntity findBotEntity() {
        if (this.client == null || this.client.world == null) return null;
        String raw = this.title.getString();
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return null;
        String name = raw.substring(0, idx);
        for (AbstractClientPlayerEntity player : this.client.world.getPlayers()) {
            if (player.getGameProfile().name().equals(name)) {
                return player;
            }
        }
        if (this.client.player == null) return null;
        if (fallbackBot == null || !fallbackBot.getGameProfile().name().equals(name)) {
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("bot:" + name).getBytes(StandardCharsets.UTF_8)), name);
            fallbackBot = new OtherClientPlayerEntity(this.client.world, profile);
            fallbackBot.copyPositionAndRotation(this.client.player);
        }
        fallbackBot.tick();
        return fallbackBot;
    }

    private static String extractAlias(String raw) {
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return raw;
        return raw.substring(0, idx);
    }
}
