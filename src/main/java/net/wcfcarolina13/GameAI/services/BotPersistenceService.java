package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.MountPersistenceService;
import net.wcfcarolina13.GameAI.services.RideSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Delegates persistence of fake player aliases to vanilla's {@link net.minecraft.server.PlayerManager}.
 *
 * <p>By relying on Mojang's own serialization we avoid chasing internal API churn (inventory codecs,
 * ability data, etc.). We simply make sure the manager is called at the right lifecycle moments,
 * and we keep an autosave cadence so a bot's inventory is written periodically while it is active.</p>
 */
public final class BotPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-persistence");

    private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 30; // 30 seconds.

    private static final Method LOAD_PLAYER_DATA = resolveManagerHook("loadPlayerData", "loadPlayerData", "method_14600");
    private static final Method SAVE_PLAYER_DATA = resolveManagerHook("savePlayerData", "savePlayerData", "method_14577");
    private static final Constructor<?> PLAYER_CONFIG_ENTRY_CONSTRUCTOR = resolvePlayerConfigEntryConstructor();
    private static final Method REMOVE_PLAYER = resolveManagerHook("remove", "removePlayer", "method_2984", "method_14576"); // Common obfuscated name for PlayerManager#remove

    private static final ConcurrentMap<UUID, Integer> LAST_SAVE_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> RESTORE_SEQUENCE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_APPLIED_RESTORE_SEQUENCE = new ConcurrentHashMap<>();

    private BotPersistenceService() {}

    public static void onBotJoin(ServerPlayerEntity bot) {
        MinecraftServer server = extractServer(bot);
        if (!(bot instanceof createFakePlayer) || server == null) {
            return;
        }

        final long joinTick = server.getTicks();
        final long restoreSeq = RESTORE_SEQUENCE.merge(bot.getUuid(), 1L, Long::sum);
        LOGGER.info("[PersistCheck] join-start bot={} tick={} alive={} removed={} vitals={}",
                bot.getName().getString(),
                joinTick,
                bot.isAlive(),
                bot.isRemoved(),
                vitalsSnapshot(bot));
        LOGGER.info("[PersistCheck] join-restore-scheduled bot={} seq={} tick={}",
                bot.getName().getString(),
                restoreSeq,
                joinTick);

        boolean managerRestored = restoreViaPlayerManager(server, bot);
        if (managerRestored) {
            LOGGER.info("Restored fakeplayer '{}' using PlayerManager.", bot.getName().getString());
        } else {
            LOGGER.info("No persisted state for fakeplayer '{}'; starting fresh.", bot.getName().getString());
        }
        LOGGER.info("[PersistCheck] manager-restore bot={} restored={} tick={} vitals={}",
                bot.getName().getString(),
                managerRestored,
                server.getTicks(),
                vitalsSnapshot(bot));

        // Schedule inventory load for next tick to ensure vanilla restoration completes first
        server.execute(() -> {
            long latestSeq = RESTORE_SEQUENCE.getOrDefault(bot.getUuid(), restoreSeq);
            if (latestSeq != restoreSeq) {
                LOGGER.info("[PersistCheck] join-restore-skipped bot={} reason=stale-seq scheduledSeq={} latestSeq={} tick={}",
                        bot.getName().getString(),
                        restoreSeq,
                        latestSeq,
                        server.getTicks());
                return;
            }
            long lastAppliedSeq = LAST_APPLIED_RESTORE_SEQUENCE.getOrDefault(bot.getUuid(), 0L);
            if (lastAppliedSeq >= restoreSeq) {
                LOGGER.info("[PersistCheck] join-restore-skipped bot={} reason=duplicate-restore seq={} lastAppliedSeq={} tick={}",
                        bot.getName().getString(),
                        restoreSeq,
                        lastAppliedSeq,
                        server.getTicks());
                return;
            }
            long nowTick = server.getTicks();
            boolean entityReady = !bot.isRemoved() && bot.getCommandSource() != null;
            LOGGER.info("[PersistCheck] join-restore-run bot={} joinTick={} nowTick={} deltaTicks={} removedBefore={} vitalsBeforeLoad={}",
                    bot.getName().getString(),
                    joinTick,
                    nowTick,
                    Math.max(0L, nowTick - joinTick),
                    bot.isRemoved(),
                    vitalsSnapshot(bot));
            LOGGER.info("[PersistCheck] join-restore-order bot={} seq={} stage=inventory-load entityReady={}",
                    bot.getName().getString(),
                    restoreSeq,
                    entityReady);
            if (!bot.isRemoved()) {
                boolean shouldLoadSnapshot = BotInventoryStorageService.shouldRestoreOnJoin(server, bot, managerRestored);
                boolean loaded = false;
                if (shouldLoadSnapshot) {
                    loaded = BotInventoryStorageService.load(bot);
                } else {
                    LOGGER.info("[PersistCheck] inventory-load-skipped bot={} reason=stale-or-missing-snapshot managerRestored={} tick={} vitalsBeforeSkip={}",
                        bot.getName().getString(),
                        managerRestored,
                        server.getTicks(),
                        vitalsSnapshot(bot));
                }
                LOGGER.info("[PersistCheck] inventory-load bot={} loaded={} attempted={} tick={} vitalsAfterLoad={}",
                    bot.getName().getString(),
                    loaded,
                    shouldLoadSnapshot,
                    server.getTicks(),
                    vitalsSnapshot(bot));
                // Skip position restore if the bot is mid-travel (delayed teleport).
                // The travel system will teleport the bot to the destination on arrival.
                if (NavigationArtifactService.isTraveling(bot.getUuid())) {
                    LOGGER.info("Skipping position restore for {} — bot is mid-travel", bot.getName().getString());
                } else {
                    BotWorldStateService.loadState(server, bot.getName().getString()).ifPresentOrElse(state -> {
                        LOGGER.info("Restoring {} to last position in world {}: {},{},{}, yaw={}, pitch={}",
                                bot.getName().getString(),
                                BotWorldStateService.currentWorldKey(server),
                                state.x(), state.y(), state.z(), state.yaw(), state.pitch());
                        float clampedPitch = Math.max(-90.0F, Math.min(90.0F, state.pitch()));
                        bot.refreshPositionAndAngles(state.x(), state.y(), state.z(), state.yaw(), clampedPitch);
                    }, () -> {
                        LOGGER.info("No prior world-state for {} in world {}; resetting pitch to 0",
                                bot.getName().getString(), BotWorldStateService.currentWorldKey(server));
                        bot.setPitch(0.0F);
                    });
                }
                // Safety: if pitch is at an extreme after restore (looking straight up/down), normalize it.
                // This catches stale world-state files that saved a bugged -90 pitch.
                if (Math.abs(bot.getPitch()) > 80.0F) {
                    LOGGER.warn("Clamping extreme pitch {} for {} after join restore", bot.getPitch(), bot.getName().getString());
                    bot.setPitch(0.0F);
                }
                MountPersistenceService.onBotJoin(bot);
                LAST_APPLIED_RESTORE_SEQUENCE.put(bot.getUuid(), restoreSeq);
                LOGGER.info("[PersistCheck] join-restore-complete bot={} tick={} pos={} vitals={}",
                        bot.getName().getString(),
                        server.getTicks(),
                        bot.getBlockPos().toShortString(),
                        vitalsSnapshot(bot));
                LOGGER.info("[PersistCheck] post-respawn-restore bot={} seq={} tick={} pos={} vitals={} inv={}",
                        bot.getName().getString(),
                        restoreSeq,
                        server.getTicks(),
                        bot.getBlockPos().toShortString(),
                        vitalsSnapshot(bot),
                        inventorySnapshot(bot));
            } else {
                LOGGER.info("[PersistCheck] join-restore-skipped bot={} reason=removed tick={}",
                        bot.getName().getString(),
                        server.getTicks());
            }
        });
        
        LAST_SAVE_TICK.put(bot.getUuid(), server.getTicks());
    }

    public static void onBotDisconnect(ServerPlayerEntity bot) {
        MinecraftServer server = extractServer(bot);
        if (!(bot instanceof createFakePlayer) || server == null) {
            return;
        }
        if (bot.hasVehicle()) {
            Entity vehicle = bot.getVehicle();
            boolean wasMounted = true;
            bot.stopRiding();
            RideSyncService.secureMountAfterRejoin(bot, vehicle);
            MountPersistenceService.recordMount(bot, vehicle, wasMounted);
        } else {
            RideSyncService.secureLeashedMountOnDisconnect(bot);
        }
        if (persistViaPlayerManager(server, bot, "disconnect")) {
            BotInventoryStorageService.save(bot);
        }
        BotWorldStateService.saveState(bot);
        LAST_SAVE_TICK.remove(bot.getUuid());
    }

    public static void onBotDeath(ServerPlayerEntity bot) {
        MinecraftServer server = extractServer(bot);
        if (!(bot instanceof createFakePlayer) || server == null) {
            return;
        }

        LOGGER.info("[PersistCheck] death-pre-snapshot bot={} tick={} pos={} vitals={} inv={}",
                bot.getName().getString(),
                server.getTicks(),
                bot.getBlockPos().toShortString(),
                vitalsSnapshot(bot),
                inventorySnapshot(bot));
        
        // ALWAYS WIPE INVENTORY ON DEATH (vanilla behavior)
        // Both aliased and temporary bots lose inventory when they die
        Path path = resolvePlayerDataPath(server, bot);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
                LOGGER.info("Cleared persisted state for fakeplayer '{}' after death (vanilla behavior).", bot.getName().getString());
            } catch (IOException e) {
                LOGGER.warn("Unable to clear persisted state for fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            }
        }
        BotInventoryStorageService.delete(bot);

        // Survival recruitment: record companion death (for resurrection gating) and clear world-position snapshot.
        // If autoRespawnOnDeath is enabled for this bot, skip death-gating so the bot vanilla-respawns normally.
        boolean autoRespawn = false;
        try {
            String alias = bot.getName().getString();
            String wk = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(server);
            ManualConfig.BotControlSettings ctrl = Frens.CONFIG != null
                    ? Frens.CONFIG.getEffectiveBotControl(alias, wk) : null;
            if (ctrl != null) {
                autoRespawn = ctrl.isAutoRespawnOnDeath();
            }
        } catch (Throwable ignored) {}

        if (!autoRespawn) {
            try {
                SurvivalRecruitmentService.noteCompanionDeath(server, bot);
            } catch (Throwable t) {
                LOGGER.debug("Failed to note companion death for {}: {}", bot.getName().getString(), t.getMessage());
            }
        }
        try {
            BotWorldStateService.clearState(server, bot.getName().getString());
        } catch (Throwable t) {
            LOGGER.debug("Failed to clear world state for {} after death: {}", bot.getName().getString(), t.getMessage());
        }
        // Clear auto-saved BotSpawn so the respawn chain falls through to failsafe (world_spawn).
        // Without this, the bot respawns near its death location because BotSpawn was auto-recorded
        // during the last autosave tick.
        try {
            String deathAlias = bot.getName().getString();
            String deathWk = BotWorldStateService.currentWorldKey(server);
            Frens.CONFIG.clearBotSpawn(deathAlias, deathWk);
            Frens.CONFIG.save();
            LOGGER.info("Cleared BotSpawn config for {} after death.", deathAlias);
        } catch (Throwable t) {
            LOGGER.debug("Failed to clear BotSpawn for {}: {}", bot.getName().getString(), t.getMessage());
        }

        SkillResumeService.handleDeath(bot);
        LAST_SAVE_TICK.remove(bot.getUuid());
        RESTORE_SEQUENCE.remove(bot.getUuid());
        LAST_APPLIED_RESTORE_SEQUENCE.remove(bot.getUuid());
    }

    public static void onBotRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
        if (newPlayer instanceof createFakePlayer) {
            long expectedRestoreSeq = RESTORE_SEQUENCE.getOrDefault(newPlayer.getUuid(), 0L) + 1L;
            LOGGER.info("[PersistCheck] respawn-event bot={} oldUuid={} newUuid={} aliveFlag={} tick={} oldVitals={} newVitals={}",
                    newPlayer.getName().getString(),
                    oldPlayer != null ? oldPlayer.getUuidAsString() : "none",
                    newPlayer.getUuidAsString(),
                    alive,
                    extractServer(newPlayer) != null ? extractServer(newPlayer).getTicks() : -1,
                    vitalsSnapshot(oldPlayer),
                    vitalsSnapshot(newPlayer));
            LOGGER.info("[PersistCheck] respawn-restore-expected bot={} expectedSeq={} tick={} aliveFlag={}",
                    newPlayer.getName().getString(),
                    expectedRestoreSeq,
                    extractServer(newPlayer) != null ? extractServer(newPlayer).getTicks() : -1,
                    alive);
        }
        if (!alive) {
            onBotJoin(newPlayer);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || SAVE_PLAYER_DATA == null) {
            return;
        }

        int currentTick = server.getTicks();
        Set<UUID> active = new HashSet<>();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!(player instanceof createFakePlayer)) {
                continue;
            }
            active.add(player.getUuid());
            BotWorldStateService.saveState(player); // keep last-known position fresh
            int last = LAST_SAVE_TICK.getOrDefault(player.getUuid(), Integer.MIN_VALUE);
            if (currentTick - last >= AUTOSAVE_INTERVAL_TICKS) {
                if (persistViaPlayerManager(server, player, "autosave")) {
                    BotInventoryStorageService.save(player);
                    BotWorldStateService.saveState(player);
                    LAST_SAVE_TICK.put(player.getUuid(), currentTick);
                }
            }
        }

        LAST_SAVE_TICK.keySet().retainAll(active);
        MountPersistenceService.onServerTick(server);
    }

    public static void saveAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long count = 0;
        Map<UUID, ServerPlayerEntity> bots = new HashMap<>();
        // Prefer registered bots, since PlayerManager#getPlayerList may not include fake players at shutdown.
        for (ServerPlayerEntity player : net.wcfcarolina13.GameAI.BotEventHandler.getRegisteredBots(server)) {
            if (player instanceof createFakePlayer) {
                bots.put(player.getUuid(), player);
            }
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player instanceof createFakePlayer) {
                bots.putIfAbsent(player.getUuid(), player);
            }
        }
        for (ServerPlayerEntity player : bots.values()) {
            if (persistViaPlayerManager(server, player, "shutdown")) {
                BotInventoryStorageService.save(player);
                BotWorldStateService.saveState(player);
                count++;
            }
        }
        BotWorldStateService.saveAll(server);
        LOGGER.info("saveAll invoked, persisted {} fakeplayer(s).", count);
    }

    public static void saveBotsBeforeShutdown(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players == null || players.isEmpty()) {
            return;
        }
        long count = 0;
        for (ServerPlayerEntity player : players) {
            if (!(player instanceof createFakePlayer)) {
                continue;
            }
            if (player.hasVehicle()) {
                Entity vehicle = player.getVehicle();
                boolean wasMounted = true;
                player.stopRiding();
                RideSyncService.secureMountAfterRejoin(player, vehicle);
                MountPersistenceService.recordMount(player, vehicle, wasMounted);
            } else {
                RideSyncService.secureLeashedMountOnDisconnect(player);
            }
            if (persistViaPlayerManager(server, player, "pre-shutdown")) {
                BotInventoryStorageService.save(player);
                BotWorldStateService.saveState(player);
                count++;
            }
        }
        if (count > 0) {
            LOGGER.info("Pre-shutdown bot save captured {} fakeplayer(s).", count);
        }
    }

    private static MinecraftServer extractServer(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        ServerCommandSource source = bot.getCommandSource();
        return source != null ? source.getServer() : null;
    }

    private static String vitalsSnapshot(ServerPlayerEntity bot) {
        if (bot == null) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "hp=%.1f food=%d sat=%.2f xp=%d prog=%.3f total=%d",
                bot.getHealth(),
                bot.getHungerManager().getFoodLevel(),
                bot.getHungerManager().getSaturationLevel(),
                bot.experienceLevel,
                bot.experienceProgress,
                bot.totalExperience);
    }

    private static String inventorySnapshot(ServerPlayerEntity bot) {
        if (bot == null) {
            return "n/a";
        }
        PlayerInventory inventory = bot.getInventory();
        int nonEmptySlots = 0;
        int totalItems = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            nonEmptySlots++;
            totalItems += stack.getCount();
        }
        return String.format(Locale.ROOT, "slots=%d/%d items=%d selected=%d",
                nonEmptySlots,
                inventory.size(),
                totalItems,
                inventory.getSelectedSlot());
    }

    private static boolean restoreViaPlayerManager(MinecraftServer server, ServerPlayerEntity bot) {
        if (LOAD_PLAYER_DATA == null) {
            return false;
        }
        try {
            Object parameter = prepareLoadParameter(bot);
            if (parameter == null) {
                return false;
            }
            Object result = LOAD_PLAYER_DATA.invoke(server.getPlayerManager(), parameter);
            if (result instanceof Optional<?> optional) {
                return optional.isPresent();
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("PlayerManager load failed for fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            return false;
        }
    }

    private static boolean persistViaPlayerManager(MinecraftServer server, ServerPlayerEntity bot, String reason) {
        if (SAVE_PLAYER_DATA == null) {
            return false;
        }
        try {
            SAVE_PLAYER_DATA.invoke(server.getPlayerManager(), bot);
            LOGGER.debug("Persisted fakeplayer '{}' via PlayerManager ({})", bot.getName().getString(), reason);
            recordSpawnData(bot);
            return true;
        } catch (Exception e) {
            LOGGER.warn("PlayerManager save failed for fakeplayer '{}' ({}): {}", bot.getName().getString(), reason, e.getMessage());
            return false;
        }
    }

    private static Path resolvePlayerDataPath(MinecraftServer server, ServerPlayerEntity bot) {
        Path dir = server.getSavePath(WorldSavePath.PLAYERDATA);
        if (dir == null) {
            return null;
        }
        String uuid = bot.getUuidAsString();
        return uuid != null ? dir.resolve(uuid + ".dat") : null;
    }

    private static Method resolveManagerHook(String label, String... candidates) {
        Class<?> managerClass = net.minecraft.server.PlayerManager.class;
        LOGGER.info("Attempting to resolve PlayerManager#{}. Candidates: {}", label, Arrays.toString(candidates));
        // Log all single-ServerPlayerEntity methods to help identify the correct one
        for (Method method : managerClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ServerPlayerEntity.class) {
                LOGGER.info("Found single-ServerPlayerEntity method: {}{}", method.getName(), Arrays.toString(method.getParameterTypes()));
            }
        }
        for (Method method : managerClass.getDeclaredMethods()) {
            for (String candidate : candidates) {
                if (!method.getName().equals(candidate)) {
                    continue;
                }
                if (method.getParameterCount() == 1) {
                    method.setAccessible(true);
                    LOGGER.info("Resolved PlayerManager#{} via {}{}", label, method.getName(), Arrays.toString(method.getParameterTypes()));
                    return method;
                }
            }
        }
        LOGGER.warn("Unable to resolve PlayerManager#{} (candidates={})", label, Arrays.toString(candidates));
        return null;
    }

    private static Constructor<?> resolvePlayerConfigEntryConstructor() {
        try {
            Class<?> clazz = PlayerConfigEntry.class;
            Constructor<?> fallback = null;
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 1 && params[0].getName().equals("com.mojang.authlib.GameProfile")) {
                    constructor.setAccessible(true);
                    LOGGER.info("Resolved PlayerConfigEntry(GameProfile) constructor.");
                    return constructor;
                }
                if (params.length == 2 && params[0] == UUID.class && params[1] == String.class) {
                    constructor.setAccessible(true);
                    fallback = constructor;
                }
            }
            if (fallback != null) {
                LOGGER.info("Resolved PlayerConfigEntry(UUID, String) constructor.");
                return fallback;
            }
            LOGGER.warn("Unable to resolve suitable PlayerConfigEntry constructor.");
        } catch (Exception e) {
            LOGGER.warn("PlayerConfigEntry constructor lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private static Object prepareLoadParameter(ServerPlayerEntity bot) {
        if (LOAD_PLAYER_DATA == null) {
            return null;
        }
        Class<?>[] params = LOAD_PLAYER_DATA.getParameterTypes();
        if (params.length != 1) {
            return null;
        }
        Class<?> parameterType = params[0];
        if (ServerPlayerEntity.class.isAssignableFrom(parameterType)) {
            return bot;
        }
        if (parameterType == PlayerConfigEntry.class) {
            return createPlayerConfigEntry(bot);
        }
        LOGGER.warn("Unsupported load parameter type {} for fakeplayer '{}'.", parameterType.getName(), bot.getName().getString());
        return null;
    }

    private static Object createPlayerConfigEntry(ServerPlayerEntity bot) {
        if (PLAYER_CONFIG_ENTRY_CONSTRUCTOR == null) {
            return null;
        }
        try {
            Class<?>[] params = PLAYER_CONFIG_ENTRY_CONSTRUCTOR.getParameterTypes();
            if (params.length == 1) {
                return PLAYER_CONFIG_ENTRY_CONSTRUCTOR.newInstance(bot.getGameProfile());
            }
            if (params.length == 2) {
                return PLAYER_CONFIG_ENTRY_CONSTRUCTOR.newInstance(bot.getUuid(), bot.getName().getString());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to instantiate PlayerConfigEntry for '{}': {}", bot.getName().getString(), e.getMessage());
        }
        return null;
    }

    private static void recordSpawnData(ServerPlayerEntity bot) {
        if (bot == null || Frens.CONFIG == null) {
            return;
        }
        MinecraftServer server = extractServer(bot);
        if (server == null) {
            return;
        }
        ServerWorld world = bot.getCommandSource().getWorld();
        if (world == null && server != null) {
            world = server.getOverworld();
        }
        String dimensionId = world.getRegistryKey().getValue().toString();
        String worldKey = BotWorldStateService.currentWorldKey(server);
        ManualConfig.BotSpawn spawn = new ManualConfig.BotSpawn(
                server.getSaveProperties().getLevelName(),
                dimensionId,
                bot.getX(),
                bot.getY(),
                bot.getZ(),
                bot.getYaw(),
                Math.max(-90.0F, Math.min(90.0F, bot.getPitch()))
        );
        Frens.CONFIG.setBotSpawn(bot.getName().getString(), worldKey, spawn);
        Frens.CONFIG.save();
    }

    public static void removeBot(ServerPlayerEntity bot) {
        MinecraftServer server = extractServer(bot);
        if (server == null) {
            LOGGER.warn("Unable to remove fakeplayer '{}': bot not attached to a server.", bot.getName().getString());
            return;
        }

        if (bot.hasVehicle()) {
            Entity vehicle = bot.getVehicle();
            boolean wasMounted = true;
            bot.stopRiding();
            RideSyncService.secureMountAfterRejoin(bot, vehicle);
            MountPersistenceService.recordMount(bot, vehicle, wasMounted);
        } else {
            RideSyncService.secureLeashedMountOnDisconnect(bot);
        }

        // Fake players can leave "ghosts" client-side if they aren't explicitly disconnected.
        // Prefer the fake-player implementation's disconnect flow when available.
        if (bot instanceof net.wcfcarolina13.Entity.createFakePlayer fake) {
            try {
                fake.kill(Text.literal("Despawned"));
            } catch (Exception e) {
                LOGGER.warn("Failed to disconnect fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            }
        }

        boolean removed = tryRemoveFromPlayerManager(server, bot);
        if (!removed) {
            // Last-resort: ensure the entity is discarded so it is not still interactable.
            try {
                bot.discard();
            } catch (Exception e) {
                LOGGER.warn("Failed to discard fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            }
        }
    }

    private static boolean tryRemoveFromPlayerManager(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return false;
        }
        if (REMOVE_PLAYER != null) {
            try {
                REMOVE_PLAYER.invoke(server.getPlayerManager(), bot);
            } catch (Exception e) {
                LOGGER.warn("Failed to remove fakeplayer '{}' from PlayerManager: {}", bot.getName().getString(), e.getMessage());
            }
        } else {
            LOGGER.warn("Unable to remove fakeplayer '{}': PlayerManager#remove method not resolved.", bot.getName().getString());
        }

        boolean removed = server.getPlayerManager().getPlayer(bot.getUuid()) == null
                && !server.getPlayerManager().getPlayerList().contains(bot);
        if (removed) {
            LOGGER.info("Removed fakeplayer '{}' from PlayerManager.", bot.getName().getString());
            return true;
        }

        try {
            boolean listRemoved = server.getPlayerManager().getPlayerList().remove(bot);
            if (listRemoved && server.getPlayerManager().getPlayer(bot.getUuid()) == null) {
                LOGGER.info("Removed fakeplayer '{}' from PlayerManager player list.", bot.getName().getString());
                return true;
            }
        } catch (Exception ignored) {
        }

        // Fallback attempt: find any method that looks like a removal hook for this runtime.
        for (Method method : server.getPlayerManager().getClass().getMethods()) {
            if (method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getName().toLowerCase().contains("remove")) {
                continue;
            }
            if (!method.getParameterTypes()[0].isAssignableFrom(bot.getClass())
                    && !method.getParameterTypes()[0].isAssignableFrom(ServerPlayerEntity.class)) {
                continue;
            }
            try {
                method.invoke(server.getPlayerManager(), bot);
                if (server.getPlayerManager().getPlayer(bot.getUuid()) == null
                        && !server.getPlayerManager().getPlayerList().contains(bot)) {
                    LOGGER.info("Removed fakeplayer '{}' from PlayerManager using fallback method {}.", bot.getName().getString(), method.getName());
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        LOGGER.warn("Fakeplayer '{}' still present after removal attempt.", bot.getName().getString());
        return false;
    }

    public static void deletePlayerDataFile(MinecraftServer server, UUID playerUuid) {
        Path playerDataPath = server.getSavePath(WorldSavePath.PLAYERDATA).resolve(playerUuid.toString() + ".dat");
        try {
            if (Files.deleteIfExists(playerDataPath)) {
                LOGGER.info("Deleted player data file for UUID: {}", playerUuid);
            } else {
                LOGGER.warn("Player data file for UUID {} did not exist at {}.", playerUuid, playerDataPath);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to delete player data file for UUID {}: {}", playerUuid, e.getMessage());
        }
    }
}
