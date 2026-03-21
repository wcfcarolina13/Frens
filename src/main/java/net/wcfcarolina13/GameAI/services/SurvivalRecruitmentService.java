package net.wcfcarolina13.GameAI.services;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.Entity.RespawnHandler;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.network.OpenRecruitmentDialoguePayload;
import net.wcfcarolina13.network.RecruitmentPromptPayload;
import net.wcfcarolina13.network.RecruitmentStatePayload;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-level "survival mode" gating: bots are only available after the player finds a village and recruits.
 *
 * <p>Implementation notes:
 * - Seed-agnostic: uses nearby villagers as the village signal.
 * - Server-authoritative: client requests opening the dialogue; server validates village proximity.
 */
public final class SurvivalRecruitmentService {

    public record VillageSignals(int villagers,
                                 int golems,
                                 boolean bellNearby,
                                 boolean bedNearby) {
        public boolean isVillageLike() {
            if (villagers >= MIN_VILLAGERS) {
                return true;
            }
            if (villagers >= 2 && golems >= MIN_GOLEMS) {
                return true;
            }
            return villagers >= 1 && (bellNearby || bedNearby);
        }
    }

    private static final int CHECK_INTERVAL_TICKS = 20; // 1 second.
    private static final double VILLAGE_RADIUS_XZ = 48.0;
    private static final double VILLAGE_RADIUS_Y = 16.0;
    // Stricter village definition: require a more "real" settlement.
    private static final int MIN_VILLAGERS = 3;
    private static final int MIN_GOLEMS = 1;

    // Interaction-gated recruitment: merely being near a village is not enough.
    // The player must interact with a village element (villager / bell / bed / trade).
    private static final int INTERACTION_WINDOW_TICKS = 20 * 90; // 90 seconds.

    // Once the server grants the recruitment dialogue, allow the player to confirm recruitment
    // even if eligibility flickers (e.g., moved a few blocks, prompt desynced, etc.).
    // This also prevents annoying false-negatives when the prompt was visible but confirm re-check fails.
    private static final int CONFIRM_WINDOW_TICKS = 20 * 300; // 5 minutes.

    // Alternative village signals (helps with rebuilt/raided villages and player-made villager hubs).
    private static final int RECENT_EVENT_TICKS = 20 * 120; // 2 minutes.
    private static final int BELL_RADIUS_XZ = 16;
    private static final int BELL_RADIUS_Y = 6;
    private static final int BED_RADIUS_XZ = 16;
    private static final int BED_RADIUS_Y = 6;

    private static final ConcurrentHashMap<UUID, Long> LAST_CHECK_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> LAST_PROMPT_VISIBLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> LAST_IN_VILLAGE = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<UUID, Boolean> LAST_SLEEPING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_SLEPT_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer> LAST_TRADE_STAT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_TRADED_TICK = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<UUID, Long> LAST_INTERACTION_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> LAST_INTERACTION_KIND = new ConcurrentHashMap<>();

    // Server-side handshake: when we open the dialogue we remember it for a short window.
    private static final ConcurrentHashMap<UUID, Long> LAST_DIALOGUE_GRANTED_TICK = new ConcurrentHashMap<>();

        // Default admin-tab permission policy for non-operators.
        // Operators always bypass these checks.
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

    // Integrated-server reload safety: when you exit to menu and re-enter a world, the JVM stays alive,
    // but Minecraft's tick counter resets. Any tick-based state stored in static maps becomes stale.
    private static volatile int LAST_SERVER_IDENTITY = -1;

    private SurvivalRecruitmentService() {}

    public static boolean isEnabled(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return false;
        }
        migrateLegacyQuestingModeIfNeeded(server, st);
        String mode = st.getSelectedWorldMode();
        return mode != null && mode.equalsIgnoreCase("questing");
    }

    private static String worldKey(MinecraftServer server) {
        if (server == null) {
            return "default";
        }
        String key = server.getSaveProperties().getLevelName();
        return (key == null || key.isBlank()) ? "default" : key;
    }

    public static ManualConfig.SurvivalRecruitmentState getState(MinecraftServer server) {
        if (Frens.CONFIG == null) {
            return new ManualConfig.SurvivalRecruitmentState();
        }
        return Frens.CONFIG.getOrCreateSurvivalRecruitmentState(worldKey(server));
    }

    public static boolean isWorldRecruited(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        return getState(server).isRecruited();
    }

    public static boolean canChooseWorldMode(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return false;
        }
        if (Frens.hasBotCommandPermission(player.getCommandSource())) {
            return true;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        return st != null && st.canDelegateChooseWorldMode(player.getUuidAsString());
    }

    public static void sendRecruitmentState(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        boolean enabled = isEnabled(server);
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (server != null && st != null) {
            migrateLegacyQuestingModeIfNeeded(server, st);
        }
        boolean recruited = st != null && st.isRecruited();
        String alias = st != null ? st.getBotAlias() : "Jake";
        boolean canChooseMode = canChooseWorldMode(player);
        boolean modeSelectionRequired = canChooseMode && (st == null || !st.isModeSelectionDone() || st.getSelectedWorldMode() == null);
        boolean allowEveryoneSkinChange = st != null && st.isAllowEveryoneSkinChange();
        boolean allowCustomSkins = st != null && st.isAllowCustomSkins();
        ServerPlayNetworking.send(player, new RecruitmentStatePayload(
                enabled,
                recruited,
                alias,
                modeSelectionRequired,
                canChooseMode,
                allowEveryoneSkinChange,
                allowCustomSkins,
                worldKey(server)));
    }

    public static boolean isAllowEveryoneSkinChange(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        return st != null && st.isAllowEveryoneSkinChange();
    }

    public static boolean isAllowCustomSkins(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        return st != null && st.isAllowCustomSkins();
    }

    public static void setAllowEveryoneSkinChange(MinecraftServer server, boolean allow) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return;
        }
        st.setAllowEveryoneSkinChange(allow);
        Frens.CONFIG.save();
    }

    public static void setAllowCustomSkins(MinecraftServer server, boolean allow) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return;
        }
        st.setAllowCustomSkins(allow);
        Frens.CONFIG.save();
    }

    public static boolean isAutonomousRescuesEnabled(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        return st != null && st.isAutonomousRescuesEnabled();
    }

    public static void setAutonomousRescuesEnabled(MinecraftServer server, boolean enabled) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return;
        }
        st.setAutonomousRescuesEnabled(enabled);
        Frens.CONFIG.save();
    }

    public static Map<String, Boolean> defaultAdminPermissionGlobals() {
        return new LinkedHashMap<>(DEFAULT_ADMIN_PERMISSION_GLOBALS);
    }

    public static boolean defaultAdminPermissionAllowed(String permissionKey) {
        String normalized = normalizePermissionKey(permissionKey);
        return DEFAULT_ADMIN_PERMISSION_GLOBALS.getOrDefault(normalized, false);
    }

    public static boolean isAdminPermissionAllowed(ServerPlayerEntity player, String permissionKey) {
        if (player == null || player.isRemoved()) {
            return false;
        }
        if (Frens.isOperator(player)) {
            return true;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        return isAdminPermissionAllowed(server, player.getUuidAsString(), permissionKey);
    }

    public static boolean isAdminPermissionAllowed(MinecraftServer server, String playerUuid, String permissionKey) {
        if (server == null || Frens.CONFIG == null) {
            return false;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return defaultAdminPermissionAllowed(permissionKey);
        }
        String normalizedKey = normalizePermissionKey(permissionKey);
        boolean fallback = defaultAdminPermissionAllowed(normalizedKey);
        Boolean userOverride = st.getAdminPermissionUserOverride(playerUuid, normalizedKey);
        if (userOverride != null) {
            return userOverride;
        }
        return st.getAdminPermissionDefault(normalizedKey, fallback);
    }

    public static void setAdminPermissionGlobal(MinecraftServer server, String permissionKey, boolean allowed) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return;
        }
        st.setAdminPermissionDefault(permissionKey, allowed);
        Frens.CONFIG.save();
    }

    public static void setAdminPermissionUserOverride(MinecraftServer server,
                                                      String playerUuid,
                                                      String permissionKey,
                                                      boolean allowed) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return;
        }
        st.setAdminPermissionUserOverride(playerUuid, permissionKey, allowed);
        Frens.CONFIG.save();
    }

    public static Map<String, Boolean> getAdminPermissionGlobals(MinecraftServer server) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>(DEFAULT_ADMIN_PERMISSION_GLOBALS);
        if (server == null || Frens.CONFIG == null) {
            return out;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return out;
        }
        Map<String, Boolean> stored = st.getAdminPermissionDefaultsByKey();
        if (stored != null && !stored.isEmpty()) {
            for (Map.Entry<String, Boolean> entry : stored.entrySet()) {
                String key = normalizePermissionKey(entry.getKey());
                if (key.isBlank()) {
                    continue;
                }
                out.put(key, Boolean.TRUE.equals(entry.getValue()));
            }
        }
        return out;
    }

    public static Map<String, Map<String, Boolean>> getAdminPermissionUserOverrides(MinecraftServer server) {
        LinkedHashMap<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        if (server == null || Frens.CONFIG == null) {
            return out;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return out;
        }
        Map<String, Map<String, Boolean>> stored = st.getAdminPermissionOverridesByUserUuid();
        if (stored == null || stored.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Map<String, Boolean>> userEntry : stored.entrySet()) {
            String userKey = normalizeUuid(userEntry.getKey());
            if (userKey.isBlank()) {
                continue;
            }
            Map<String, Boolean> userMap = userEntry.getValue();
            if (userMap == null || userMap.isEmpty()) {
                continue;
            }
            LinkedHashMap<String, Boolean> normalizedUserMap = new LinkedHashMap<>();
            for (Map.Entry<String, Boolean> permEntry : userMap.entrySet()) {
                String permKey = normalizePermissionKey(permEntry.getKey());
                if (permKey.isBlank()) {
                    continue;
                }
                normalizedUserMap.put(permKey, Boolean.TRUE.equals(permEntry.getValue()));
            }
            if (!normalizedUserMap.isEmpty()) {
                out.put(userKey, normalizedUserMap);
            }
        }
        return out;
    }

    public static Map<String, String> collectKnownPermissionUsers(MinecraftServer server) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (server == null) {
            return out;
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                continue;
            }
            out.put(normalizeUuid(p.getUuidAsString()), p.getName().getString());
        }

        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st != null) {
            Map<String, String> delegates = st.getModeSelectionDelegatesByUuid();
            if (delegates != null) {
                for (Map.Entry<String, String> entry : delegates.entrySet()) {
                    String key = normalizeUuid(entry.getKey());
                    if (key.isBlank()) {
                        continue;
                    }
                    String name = entry.getValue();
                    out.putIfAbsent(key, (name == null || name.isBlank()) ? key : name.trim());
                }
            }
            Map<String, Map<String, Boolean>> overrides = st.getAdminPermissionOverridesByUserUuid();
            if (overrides != null) {
                for (String userKey : overrides.keySet()) {
                    String key = normalizeUuid(userKey);
                    if (!key.isBlank()) {
                        out.putIfAbsent(key, key);
                    }
                }
            }
        }
        return out;
    }

    private static String normalizePermissionKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeUuid(String uuid) {
        return uuid == null ? "" : uuid.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void noteModeSelectionConfigured(MinecraftServer server, boolean questingMode, String actorName) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null) {
            return;
        }
        st.setModeSelectionDone(true);
        st.setSelectedWorldMode(questingMode ? "questing" : "admin");
        st.setModeSelectedAtEpochMs(System.currentTimeMillis());
        st.setModeSelectedByName(actorName);
    }

    public static void setWorldMode(MinecraftServer server, boolean questingMode, String actorName) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        noteModeSelectionConfigured(server, questingMode, actorName);
        Frens.CONFIG.save();
    }

    public static void handleModeSelectionChoice(ServerPlayerEntity player, boolean questingMode) {
        if (player == null || player.isRemoved()) {
            return;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        if (!canChooseWorldMode(player)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "You are not allowed to change world mode.");
            sendRecruitmentState(player);
            return;
        }

        setWorldMode(server, questingMode, player.getName().getString());

        if (questingMode) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "World mode set to Questing.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "To begin: go to a village, interact with villager/bell/bed, then initiate contact.");
        } else {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "World mode set to Admin.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Spawn a bot with /bot spawn <name> admin.");
        }

        ManualConfig.SurvivalRecruitmentState st = getState(server);
        String alias = st != null ? st.getBotAlias() : "Jake";
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                continue;
            }
            sendRecruitmentState(p);
            if (!questingMode) {
                ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, alias));
            }
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        // Clear tick-based caches whenever the server instance changes (singleplayer world re-entry).
        int sid = System.identityHashCode(server);
        if (sid != LAST_SERVER_IDENTITY) {
            LAST_SERVER_IDENTITY = sid;
            resetSessionCaches();
        }

        boolean enabled = isEnabled(server);
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        boolean recruited = st != null && st.isRecruited();
        String botAlias = st != null ? st.getBotAlias() : "Jake";

        long nowTick = server.getTicks();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || player.isRemoved()) {
                continue;
            }
            // Only show prompts to real players.
            if (player instanceof createFakePlayer) {
                continue;
            }

            UUID id = player.getUuid();

            boolean shouldShow = false;
            if (enabled && !recruited) {
                long lastCheck = LAST_CHECK_TICK.getOrDefault(id, Long.MIN_VALUE);
                if (nowTick - lastCheck >= CHECK_INTERVAL_TICKS) {
                    boolean eligible = isEligibleForRecruitment(player, nowTick);
                    LAST_IN_VILLAGE.put(id, eligible);
                    LAST_CHECK_TICK.put(id, nowTick);
                }
                shouldShow = LAST_IN_VILLAGE.getOrDefault(id, false);
            }

            Boolean lastSent = LAST_PROMPT_VISIBLE.get(id);
            if (lastSent == null || lastSent != shouldShow) {
                LAST_PROMPT_VISIBLE.put(id, shouldShow);
                ServerPlayNetworking.send(player, new RecruitmentPromptPayload(shouldShow, botAlias));
            }
        }

        // Cleanup maps occasionally.
        if ((nowTick % (20 * 60)) == 0) {
            java.util.Set<UUID> live = new java.util.HashSet<>();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player != null && !(player instanceof createFakePlayer)) {
                    live.add(player.getUuid());
                }
            }
            LAST_CHECK_TICK.keySet().retainAll(live);
            LAST_PROMPT_VISIBLE.keySet().retainAll(live);
            LAST_IN_VILLAGE.keySet().retainAll(live);
            LAST_SLEEPING.keySet().retainAll(live);
            LAST_SLEPT_TICK.keySet().retainAll(live);
            LAST_TRADE_STAT.keySet().retainAll(live);
            LAST_TRADED_TICK.keySet().retainAll(live);
            LAST_INTERACTION_TICK.keySet().retainAll(live);
            LAST_INTERACTION_KIND.keySet().retainAll(live);
            LAST_DIALOGUE_GRANTED_TICK.keySet().retainAll(live);
        }
    }

    private static void resetSessionCaches() {
        LAST_CHECK_TICK.clear();
        LAST_PROMPT_VISIBLE.clear();
        LAST_IN_VILLAGE.clear();
        LAST_SLEEPING.clear();
        LAST_SLEPT_TICK.clear();
        LAST_TRADE_STAT.clear();
        LAST_TRADED_TICK.clear();
        LAST_INTERACTION_TICK.clear();
        LAST_INTERACTION_KIND.clear();
        LAST_DIALOGUE_GRANTED_TICK.clear();
    }

    private static void migrateLegacyQuestingModeIfNeeded(MinecraftServer server, ManualConfig.SurvivalRecruitmentState st) {
        if (server == null || st == null || Frens.CONFIG == null) {
            return;
        }
        String selected = st.getSelectedWorldMode();
        if (selected != null && !selected.isBlank()) {
            return;
        }
        if (st.isModeSelectionDone()) {
            return;
        }
        boolean legacyQuesting = st.isRecruited()
                || st.getRecruitedAtEpochMs() > 0L
                || st.getCompanionQuestStage() > 0
                || st.isCompanionAnchorSet()
                || st.isPermanentCompanion();
        if (!legacyQuesting) {
            return;
        }
        st.setModeSelectionDone(true);
        st.setSelectedWorldMode("questing");
        st.setModeSelectedAtEpochMs(System.currentTimeMillis());
        st.setModeSelectedByName("legacy-migration");
        Frens.CONFIG.save();
    }

    /**
     * Interaction-driven recruitment eligibility.
     *
     * <p>We intentionally do NOT grant eligibility just for being near a settlement.
     * Players must interact with a village element (villager/bell/bed/trade), then we validate
     * that the surroundings look like a village (villager count / golems / bell / beds).
     */
    private static boolean isEligibleForRecruitment(ServerPlayerEntity player, long nowTick) {
        if (player == null || player.isRemoved()) {
            return false;
        }
        ServerWorld world = player.getCommandSource().getWorld();

        UUID id = player.getUuid();

        // Track recent sleep edge (sleeping -> not sleeping) as an interaction signal.
        boolean sleeping = player.isSleeping();
        boolean wasSleeping = LAST_SLEEPING.getOrDefault(id, false);
        if (wasSleeping && !sleeping) {
            LAST_SLEPT_TICK.put(id, nowTick);
            noteInteractionTickOnly(id, nowTick, "sleep");
        }
        LAST_SLEEPING.put(id, sleeping);

        // Track villager trades (stat increment) as an interaction signal.
        int trades = safeGetTradeCount(player);
        Integer prevTrades = LAST_TRADE_STAT.put(id, trades);
        if (prevTrades != null && trades > prevTrades) {
            LAST_TRADED_TICK.put(id, nowTick);
            noteInteractionTickOnly(id, nowTick, "trade");
        }

        // Must have interacted with something village-related recently.
        long lastInteract = LAST_INTERACTION_TICK.getOrDefault(id, Long.MIN_VALUE);
        boolean interactedRecently = nowTick - lastInteract <= INTERACTION_WINDOW_TICKS;
        if (!interactedRecently) {
            return false;
        }

        // Surroundings must look like a village.
        int villagers = countNearbyVillagers(world, player.getBoundingBox());
        int golems = countNearbyGolems(world, player.getBoundingBox());
        BlockPos center = player.getBlockPos();
        boolean bellNearby = isBellNearby(world, center);
        boolean bedNearby = isBedNearby(world, center);

        // A "real" village: either enough villagers, or a smaller group with an iron golem.
        if (villagers >= MIN_VILLAGERS) {
            return true;
        }
        if (villagers >= 2 && golems >= MIN_GOLEMS) {
            return true;
        }

        // Fallback: typical village furniture + at least 2 villagers.
        if (villagers >= 2 && (bellNearby || bedNearby)) {
            return true;
        }

        return false;
    }

    private static void noteInteractionTickOnly(UUID id, long nowTick, String kind) {
        if (id == null) {
            return;
        }
        LAST_INTERACTION_TICK.put(id, nowTick);
        if (kind != null && !kind.isBlank()) {
            LAST_INTERACTION_KIND.put(id, kind);
        }
    }

    /**
     * Called by server-side interaction hooks (bell/villager/bed/etc).
     * This records the interaction and immediately updates the prompt visibility for the player.
     */
    public static void notePlayerInteraction(ServerPlayerEntity player, String kind) {
        if (player == null || player.isRemoved()) {
            return;
        }
        if (player instanceof createFakePlayer) {
            return;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        long nowTick = server.getTicks();
        UUID id = player.getUuid();
        noteInteractionTickOnly(id, nowTick, kind);

        // If recruitment isn't active, do not bother sending prompts.
        if (!isEnabled(server)) {
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st != null && st.isRecruited()) {
            return;
        }

        boolean shouldShow = isEligibleForRecruitment(player, nowTick);
        LAST_IN_VILLAGE.put(id, shouldShow);
        LAST_CHECK_TICK.put(id, nowTick);

        String botAlias = st != null ? st.getBotAlias() : "Jake";
        Boolean lastSent = LAST_PROMPT_VISIBLE.get(id);
        if (lastSent == null || lastSent != shouldShow) {
            LAST_PROMPT_VISIBLE.put(id, shouldShow);
            ServerPlayNetworking.send(player, new RecruitmentPromptPayload(shouldShow, botAlias));
        }
    }

    /**
     * Best-effort classification for recruitment dialogue flavor.
     *
     * <p>We don't (currently) do an expensive structure-start lookup for true seed-generated villages.
     * Instead, we infer:
     * <ul>
     *   <li><b>generated</b>: looks like a typical village center (multiple villagers and a bell) or many villagers.</li>
     *   <li><b>player</b>: the player triggered village eligibility via trade/sleep, or it's a small hub-like setup.</li>
     *   <li><b>unknown</b>: fallback when signals are ambiguous.</li>
     * </ul>
     */
    private static String computeVillageFlavor(ServerPlayerEntity player, long nowTick) {
        if (player == null || player.isRemoved()) {
            return "unknown";
        }

        ServerWorld world = player.getCommandSource().getWorld();
        UUID id = player.getUuid();

        int villagers = countNearbyVillagers(world, player.getBoundingBox());
        int golems = countNearbyGolems(world, player.getBoundingBox());

        boolean tradedRecently = nowTick - LAST_TRADED_TICK.getOrDefault(id, Long.MIN_VALUE) <= RECENT_EVENT_TICKS;
        boolean sleptRecently = nowTick - LAST_SLEPT_TICK.getOrDefault(id, Long.MIN_VALUE) <= RECENT_EVENT_TICKS;

        BlockPos center = player.getBlockPos();
        boolean bellNearby = isBellNearby(world, center);
        boolean bedNearby = isBedNearby(world, center);

        // If we only got here because of player-driven actions, treat it as player-made.
        if (tradedRecently || sleptRecently) {
            return "player";
        }

        // Typical generated-village vibe: bell + multiple villagers.
        if (villagers >= MIN_VILLAGERS && bellNearby) {
            return "generated";
        }

        // Golem + several villagers is a strong "generated" signal.
        if (villagers >= 2 && golems >= 1) {
            return "generated";
        }

        // Many villagers nearby usually implies an established settlement.
        if (villagers >= 4) {
            return "generated";
        }

        // Small hub-like setups: beds/bell with few villagers.
        if ((bellNearby && bedNearby) || (villagers >= 1 && bedNearby)) {
            return "player";
        }

        return "unknown";
    }

    private static int safeGetTradeCount(ServerPlayerEntity player) {
        try {
            return player.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TRADED_WITH_VILLAGER));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int countNearbyVillagers(ServerWorld world, Box playerBox) {
        Box box = playerBox.expand(VILLAGE_RADIUS_XZ, VILLAGE_RADIUS_Y, VILLAGE_RADIUS_XZ);
        return world.getEntitiesByClass(VillagerEntity.class, box, v -> v != null && v.isAlive()).size();
    }

    private static int countNearbyGolems(ServerWorld world, Box playerBox) {
        Box box = playerBox.expand(VILLAGE_RADIUS_XZ, VILLAGE_RADIUS_Y, VILLAGE_RADIUS_XZ);
        return world.getEntitiesByClass(IronGolemEntity.class, box, g -> g != null && g.isAlive()).size();
    }

    private static boolean isBellNearby(ServerWorld world, BlockPos center) {
        return isBlockNearby(world, center, BELL_RADIUS_XZ, BELL_RADIUS_Y, state -> state != null && state.isOf(Blocks.BELL));
    }

    private static boolean isBedNearby(ServerWorld world, BlockPos center) {
        return isBlockNearby(world, center, BED_RADIUS_XZ, BED_RADIUS_Y, state -> state != null && state.isIn(BlockTags.BEDS));
    }

    public static VillageSignals inspectVillageSignals(ServerWorld world, BlockPos center) {
        if (world == null || center == null) {
            return new VillageSignals(0, 0, false, false);
        }
        Box probe = Box.of(Vec3d.ofCenter(center), 1.0D, 2.0D, 1.0D);
        return new VillageSignals(
                countNearbyVillagers(world, probe),
                countNearbyGolems(world, probe),
                isBellNearby(world, center),
                isBedNearby(world, center));
    }

    public static boolean isVillageSignalNearby(ServerWorld world, BlockPos center) {
        return inspectVillageSignals(world, center).isVillageLike();
    }

    private static boolean isBlockNearby(ServerWorld world, BlockPos center, int radiusXZ, int radiusY, java.util.function.Predicate<BlockState> predicate) {
        if (world == null || center == null || predicate == null) {
            return false;
        }
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dy = -radiusY; dy <= radiusY; dy++) {
            int y = cy + dy;
            for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
                int x = cx + dx;
                for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                    int z = cz + dz;
                    p.set(x, y, z);
                    BlockState s = world.getBlockState(p);
                    if (predicate.test(s)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void handleRequestOpen(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (!isEnabled(server)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Survival recruitment mode is disabled on this server/world.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "(If you're in singleplayer, you can enable it with /bot recruit enable.)");
            return;
        }
        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st != null && st.isRecruited()) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Recruitment already completed in this world.");
            return;
        }
        long nowTick = server.getTicks();
        if (!isEligibleForRecruitment(player, nowTick)) {
            // Provide a little debug context since this is user-triggered and helps diagnose edge cases.
            ServerWorld world = player.getCommandSource().getWorld();
            int villagers = countNearbyVillagers(world, player.getBoundingBox());
            int golems = countNearbyGolems(world, player.getBoundingBox());
            BlockPos center = player.getBlockPos();
            boolean bellNearby = isBellNearby(world, center);
            boolean bedNearby = isBedNearby(world, center);
            UUID id = player.getUuid();
            long lastInteract = LAST_INTERACTION_TICK.getOrDefault(id, Long.MIN_VALUE);
            long age = (lastInteract == Long.MIN_VALUE) ? Long.MAX_VALUE : (nowTick - lastInteract);
            String kind = LAST_INTERACTION_KIND.getOrDefault(id, "none");

            ChatUtils.sendSystemMessage(player.getCommandSource(),
                    "To make contact, interact with a village element (right-click a villager, ring/use a bell, or click a bed), then try again.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "(debug: villagers=" + villagers
                    + " golems=" + golems
                    + " bell=" + bellNearby
                    + " bed=" + bedNearby
                    + " lastInteract=" + (age == Long.MAX_VALUE ? "never" : (age + "t"))
                    + " kind=" + kind + ")");
            return;
        }

        // Record that we granted the dialogue so confirm can succeed even if eligibility flickers.
        // (We still validate world state / recruited flags on confirm.)
        LAST_DIALOGUE_GRANTED_TICK.put(player.getUuid(), nowTick);

        String alias = st != null ? st.getBotAlias() : "Jake";
        String flavor = computeVillageFlavor(player, nowTick);
        ServerPlayNetworking.send(player, new OpenRecruitmentDialoguePayload(alias, flavor));
    }

    public static void handleConfirmRecruit(ServerPlayerEntity player, String botAlias) {
        if (player == null || player.isRemoved()) {
            return;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (!isEnabled(server)) {
            return;
        }

        try {
            Frens.LOGGER.info("Recruit confirm invoked by {} requestedAlias='{}' dim={} pos={}",
                    player.getName().getString(),
                    botAlias,
                    player.getCommandSource().getWorld().getRegistryKey().getValue(),
                    player.getBlockPos());
        } catch (Throwable ignored) {
        }

        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st != null && st.isRecruited()) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Recruitment already completed in this world.");
            return;
        }
        long nowTick = server.getTicks();

        // Eligibility can flicker between opening the dialogue and clicking "Recruit".
        // Prefer a short server-side handshake window to avoid frustrating false-negatives.
        boolean eligibleNow = isEligibleForRecruitment(player, nowTick);
        long grantedTick = LAST_DIALOGUE_GRANTED_TICK.getOrDefault(player.getUuid(), Long.MIN_VALUE);
        boolean grantedRecently = grantedTick != Long.MIN_VALUE && (nowTick - grantedTick) <= CONFIRM_WINDOW_TICKS;
        // One-shot: consume the grant so the window can't be reused indefinitely.
        LAST_DIALOGUE_GRANTED_TICK.remove(player.getUuid());

        try {
            Frens.LOGGER.info("Recruit confirm eligibility: eligibleNow={} grantedRecently={} grantedAgeTicks={}",
                    eligibleNow,
                    grantedRecently,
                    (grantedTick == Long.MIN_VALUE ? -1 : (nowTick - grantedTick)));
        } catch (Throwable ignored) {
        }

        if (!eligibleNow && !grantedRecently) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "To recruit, first make contact: interact with a villager, bell, or bed, then open the dialogue again.");
            return;
        }

        String alias = (botAlias == null || botAlias.isBlank()) ? (st != null ? st.getBotAlias() : "Jake") : botAlias.trim();
        ChatUtils.sendSystemMessage(player.getCommandSource(), "Summoning " + alias + "...");

        GameMode desiredMode = GameMode.SURVIVAL;
        if (Frens.CONFIG != null) {
            String wk = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(server);
            ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getEffectiveBotControl(alias, wk);
            if (ctrl != null && "creative".equalsIgnoreCase(ctrl.getGameMode())) {
                desiredMode = GameMode.CREATIVE;
            }
        }

        // If the bot already exists (e.g., spawned earlier), reuse it rather than forcing a duplicate-login respawn.
        RegistryKey<World> dim = player.getCommandSource().getWorld().getRegistryKey();
        ServerWorld targetWorld = player.getCommandSource().getWorld();
        Vec3d spawn = pickSafeSpawnNear(targetWorld, player.getBlockPos(), new Vec3d(player.getX() + 1.0, player.getY(), player.getZ() + 1.0));
        ServerPlayerEntity existing = server.getPlayerManager().getPlayer(alias);
        if (existing != null && !existing.isRemoved() && existing.isAlive()) {
            if (!(existing instanceof createFakePlayer)) {
                ChatUtils.sendSystemMessage(player.getCommandSource(), "Can't recruit '" + alias + "' because a real player with that name is online.");
                return;
            }
            existing.teleport(targetWorld, spawn.x, spawn.y, spawn.z, java.util.Set.of(), player.getYaw(), player.getPitch(), true);
            existing.setVelocity(Vec3d.ZERO);
            try {
                existing.interactionManager.changeGameMode(desiredMode);
            } catch (Throwable ignored) {
                // If mappings differ or gamemode can't be changed, keep current.
            }
        } else {
            Frens.LOGGER.info("Recruitment spawning bot alias={} dim={} at {}", alias, dim.getValue(), spawn);
            createFakePlayer.createFake(alias, server, spawn, player.getYaw(), player.getPitch(), dim, desiredMode, false);
        }

        // Ensure the bot is registered/active for the rest of the mod systems.
        // (Also helps catch async Mojang-auth spawn cases: retry a few times.)
        schedulePostSpawnSetup(server, player.getUuid(), alias, desiredMode, targetWorld, spawn, 0);

        // Persist ownership and mark recruited.
        if (Frens.CONFIG != null) {
            Frens.CONFIG.ensureOwner(alias, player.getUuid(), player.getName().getString());
            ManualConfig.SurvivalRecruitmentState updated = Frens.CONFIG.getOrCreateSurvivalRecruitmentState(worldKey(server));
            updated.setBotAlias(alias);
            updated.setRecruited(true);
            updated.setRecruitedByUuid(player.getUuidAsString());
            updated.setRecruitedByName(player.getName().getString());
            updated.setRecruitedAtEpochMs(System.currentTimeMillis());

            // Initialize the companion questline anchor at the recruitment contact location.
            // This lets us validate later village-improvement steps server-side.
            updated.setCompanionAnchorSet(true);
            updated.setCompanionAnchorDimension(dim.getValue().toString());
            updated.setCompanionAnchorPos(player.getBlockPos().asLong());
            updated.setCompanionQuestStage(0);
            updated.setPermanentCompanion(false);

            // Fresh start: companion is alive.
            updated.setCompanionDead(false);
            updated.setCompanionDiedAtEpochMs(0L);
            updated.setCompanionDiedDimension(null);
            updated.setCompanionDiedPos(0L);

            // Completing recruitment implicitly means this world is using questing mode.
            updated.setModeSelectionDone(true);
            updated.setSelectedWorldMode("questing");
            updated.setModeSelectedAtEpochMs(System.currentTimeMillis());
            updated.setModeSelectedByName(player.getName().getString());
            Frens.CONFIG.save();
        }

        // Notify and sync state.
        ChatUtils.sendSystemMessage(player.getCommandSource(), "\u00A7aYou recruited " + alias + ".");
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p != null && !(p instanceof createFakePlayer)) {
                sendRecruitmentState(p);
                ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, alias));
            }
        }
    }

    private static void schedulePostSpawnSetup(MinecraftServer server,
                                              UUID recruiterUuid,
                                              String alias,
                                              GameMode desiredMode,
                                              ServerWorld targetWorld,
                                              Vec3d spawn,
                                              int attempt) {
        if (server == null || alias == null || alias.isBlank()) {
            return;
        }
        int delay = (attempt == 0) ? 1 : 20; // 1 tick, then retry every second.
        int nextAttempt = attempt + 1;
        int runAt = server.getTicks() + delay;
        server.send(new net.minecraft.server.ServerTask(runAt, () -> {
            ServerPlayerEntity recruiter = null;
            try {
                recruiter = recruiterUuid != null ? server.getPlayerManager().getPlayer(recruiterUuid) : null;
            } catch (Throwable ignored) {
            }

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                if (nextAttempt <= 8) {
                    schedulePostSpawnSetup(server, recruiterUuid, alias, desiredMode, targetWorld, spawn, nextAttempt);
                } else if (recruiter != null && !recruiter.isRemoved()) {
                    ChatUtils.sendSystemMessage(recruiter.getCommandSource(), "\u00A7cFailed to spawn " + alias + ".\u00A7r");
                    ChatUtils.sendSystemMessage(recruiter.getCommandSource(), "Check the log for createFake/spawn errors.");
                }
                return;
            }

            if (!(bot instanceof createFakePlayer)) {
                if (recruiter != null && !recruiter.isRemoved()) {
                    ChatUtils.sendSystemMessage(recruiter.getCommandSource(),
                            "\u00A7cCan't recruit '" + alias + "': a real player with that name is online.\u00A7r");
                }
                return;
            }

            try {
                bot.interactionManager.changeGameMode(desiredMode);
            } catch (Throwable ignored) {
            }

            // Force the recruited companion to appear at the meeting site.
            // This also overrides any persistence restore that may teleport the bot away on join.
            try {
                if (targetWorld != null && spawn != null) {
                    bot.teleport(targetWorld, spawn.x, spawn.y, spawn.z, java.util.Set.of(), bot.getYaw(), bot.getPitch(), true);
                    bot.setVelocity(Vec3d.ZERO);
                }
            } catch (Throwable ignored) {
            }
            try {
                RespawnHandler.registerRespawnListener(bot);
            } catch (Throwable ignored) {
            }
            try {
                BotEventHandler.registerBot(bot);
            } catch (Throwable ignored) {
            }
            try {
                AutoFaceEntity.startAutoFace(bot);
            } catch (Throwable ignored) {
            }
            try {
                if (targetWorld != null && spawn != null) {
                    BotEventHandler.rememberSpawn(targetWorld, spawn, bot.getYaw(), bot.getPitch());
                }
            } catch (Throwable ignored) {
            }

            if (recruiter != null && !recruiter.isRemoved()) {
                ChatUtils.sendSystemMessage(recruiter.getCommandSource(), "\u00A7a" + alias + " has arrived.\u00A7r");
            }
        }));
    }

    private static Vec3d pickSafeSpawnNear(ServerWorld world, BlockPos base, Vec3d fallback) {
        if (world == null || base == null) {
            return fallback;
        }
        BlockPos.Mutable p = new BlockPos.Mutable();
        // Search a small column around the player for a two-block-tall air space with solid ground.
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    p.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (isSpawnableAt(world, p)) {
                        return Vec3d.ofCenter(p);
                    }
                }
            }
        }
        return fallback;
    }

    private static boolean isSpawnableAt(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockPos below = pos.down();
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState ground = world.getBlockState(below);
        if (feet == null || head == null || ground == null) {
            return false;
        }
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        if (!feet.isAir() || !head.isAir()) {
            return false;
        }
        return ground.isSolidBlock(world, below);
    }

    /**
     * Request to reset/replay recruitment. Intended for testing and singleplayer.
     *
     * <p>Authorization rules:
     * <ul>
     *   <li>Operators can always reset.</li>
     *   <li>On integrated servers (singleplayer/LAN), allow the local player to reset even without op.</li>
     *   <li>On dedicated servers, allow only the original recruiter (recruitedByUuid) to reset.</li>
     * </ul>
     */
    public static void handleRequestReplayRecruitment(ServerPlayerEntity player, String requestedAlias) {
        if (player == null || player.isRemoved()) {
            return;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) {
            return;
        }
        if (!isEnabled(server)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Survival recruitment mode is disabled in this world.");
            return;
        }
        if (Frens.CONFIG == null) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Recruitment state unavailable: config not ready.");
            return;
        }

        ManualConfig.SurvivalRecruitmentState st = getState(server);
        String alias = st != null ? st.getBotAlias() : "Jake";
        if (requestedAlias != null && !requestedAlias.isBlank() && !alias.equalsIgnoreCase(requestedAlias.trim())) {
            // Keep this world-level action scoped to the configured alias.
            ChatUtils.sendSystemMessage(player.getCommandSource(), "This world is configured to recruit '" + alias + "'.");
        }

        boolean authorized = false;
        if (Frens.isOperator(player)) {
            authorized = true;
        } else if (!server.isDedicated()) {
            // Singleplayer / integrated server convenience.
            authorized = true;
        } else if (st != null && st.isRecruited()) {
            String recruiter = st.getRecruitedByUuid();
            if (recruiter != null && !recruiter.isBlank() && recruiter.equals(player.getUuidAsString())) {
                authorized = true;
            }
        }

        if (!authorized) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "You aren't authorized to reset recruitment in this world.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "(Requires operator, or the original recruiter, or singleplayer/integrated server.)");
            return;
        }

        // Despawn the existing fake bot (if present) so the "first meeting" can be replayed cleanly.
        ServerPlayerEntity existing = server.getPlayerManager().getPlayer(alias);
        if (existing != null && !existing.isRemoved() && existing.isAlive()) {
            if (existing instanceof createFakePlayer) {
                BotEventHandler.unregisterBot(existing);
            } else {
                ChatUtils.sendSystemMessage(player.getCommandSource(), "Can't reset: a real player named '" + alias + "' is online.");
                return;
            }
        }

        // Reset world state.
        String worldKey = worldKey(server);
        ManualConfig.SurvivalRecruitmentState updated = Frens.CONFIG.getOrCreateSurvivalRecruitmentState(worldKey);
        updated.setRecruited(false);
        updated.setRecruitedByUuid(null);
        updated.setRecruitedByName(null);
        updated.setRecruitedAtEpochMs(0L);
        // Keep botAlias as-is.

        updated.setCompanionQuestStage(0);
        updated.setPermanentCompanion(false);
        updated.setCompanionAnchorSet(false);
        updated.setCompanionAnchorDimension(null);
        updated.setCompanionAnchorPos(0L);

        updated.setCompanionDead(false);
        updated.setCompanionDiedAtEpochMs(0L);
        updated.setCompanionDiedDimension(null);
        updated.setCompanionDiedPos(0L);

        Frens.CONFIG.save();

        // Sync clients.
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved() || (p instanceof createFakePlayer)) {
                continue;
            }
            sendRecruitmentState(p);
            ServerPlayNetworking.send(p, new RecruitmentPromptPayload(false, alias));
            // Companion quest UI state should reset as well.
            ServerPlayNetworking.send(p, new net.wcfcarolina13.network.CompanionQuestStatePayload(alias, 0, false));
        }

        ChatUtils.sendSystemMessage(player.getCommandSource(), "Recruitment has been reset. Visit a village to meet " + alias + " again.");
    }

    /**
     * Marks the recruited companion as dead in the current world (used to gate resurrection).
     *
     * <p>Called from bot death hooks. Safe to call even when survival recruitment is disabled.
     */
    public static void noteCompanionDeath(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null || bot.isRemoved()) {
            return;
        }
        if (!isEnabled(server) || Frens.CONFIG == null) {
            return;
        }

        ManualConfig.SurvivalRecruitmentState st = getState(server);
        if (st == null || !st.isRecruited()) {
            return;
        }

        String alias = st.getBotAlias();
        String botName = bot.getName().getString();
        if (alias == null || alias.isBlank() || botName == null) {
            return;
        }
        if (!alias.equalsIgnoreCase(botName)) {
            return; // only track the recruited companion
        }

        st.setCompanionDead(true);
        st.setCompanionDiedAtEpochMs(System.currentTimeMillis());
        try {
            RegistryKey<World> dim = bot.getEntityWorld().getRegistryKey();
            st.setCompanionDiedDimension(dim != null ? dim.getValue().toString() : null);
        } catch (Throwable ignored) {
            st.setCompanionDiedDimension(null);
        }
        try {
            st.setCompanionDiedPos(bot.getBlockPos().asLong());
        } catch (Throwable ignored) {
            st.setCompanionDiedPos(0L);
        }
        st.setCompanionDeathCount(st.getCompanionDeathCount() + 1);
        Frens.CONFIG.save();

        // Notify the recruiter (best effort).
        try {
            String recruiterUuid = st.getRecruitedByUuid();
            if (recruiterUuid != null && !recruiterUuid.isBlank()) {
                ServerPlayerEntity recruiter = server.getPlayerManager().getPlayer(UUID.fromString(recruiterUuid));
                if (recruiter != null && !recruiter.isRemoved()) {
                    ChatUtils.sendSystemMessage(recruiter.getCommandSource(), "\u00A7c" + alias + " died.\u00A7r");
                    ChatUtils.sendSystemMessage(recruiter.getCommandSource(), "A Nether ritual can bring him back.");
                    ChatUtils.sendSystemMessage(recruiter.getCommandSource(), "(Sneak-right-click a charged Respawn Anchor with a Ghast Tear in the Nether.)");
                    ChatUtils.sendSystemMessage(recruiter.getCommandSource(), "Cost: 4 gold ingots + 1 blaze powder.");
                }
            }
        } catch (Throwable ignored) {
            // No-op.
        }
    }
}
