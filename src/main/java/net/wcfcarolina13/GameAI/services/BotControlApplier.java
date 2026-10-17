package net.wcfcarolina13.GameAI.services;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies persisted Bot Controls toggles to the running server so players do not need to repeat console commands.
 */
public final class BotControlApplier {

    private static final Set<String> AUTO_SPAWNED_THIS_SESSION = ConcurrentHashMap.newKeySet();
    /**
     * Integrated servers start before the host logs in; auto-spawn waits for the first real player
     * so bots never fill the player list ahead of the host (see {@link AutoSpawnPolicy}).
     */
    private static final AtomicBoolean AUTO_SPAWN_PENDING = new AtomicBoolean(false);

    private BotControlApplier() {
    }

    public static void resetSession() {
        AUTO_SPAWNED_THIS_SESSION.clear();
        AUTO_SPAWN_PENDING.set(false);
    }

    public static void applyPersistentSettings(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        refreshBotPreferences(server);
        if (AutoSpawnPolicy.deferUntilHostJoins(server.isDedicated())) {
            AUTO_SPAWN_PENDING.set(true);
            Frens.LOGGER.info("Auto-spawn deferred until the first real player joins (integrated server).");
        } else {
            scheduleAutoSpawns(server);
        }
    }

    /**
     * Called from the JOIN handler for real (non-fake) players, on the server thread after the
     * player has been added to the player list. Runs the deferred auto-spawn once per session.
     */
    public static void onRealPlayerJoin(MinecraftServer server) {
        if (server == null || !AUTO_SPAWN_PENDING.compareAndSet(true, false)) {
            return;
        }
        scheduleAutoSpawns(server);
    }

    public static void refreshBotPreferences(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            applyToBot(bot);
        }
    }

    public static void applyToBot(ServerPlayerEntity bot) {
        if (bot == null || Frens.CONFIG == null) {
            return;
        }
        String worldKey = BotWorldStateService.currentWorldKey(bot.getCommandSource().getServer());
        ManualConfig.BotControlSettings settings = Frens.CONFIG.getEffectiveBotControl(bot.getName().getString(), worldKey);
        if (settings == null) {
            return;
        }
        SkillPreferences.setTeleportDuringSkills(bot.getUuid(), settings.isTeleportDuringSkills());
        SkillPreferences.setFollowTeleport(bot.getUuid(), settings.isFollowTeleport());
        SkillPreferences.setPauseOnFullInventory(bot.getUuid(), settings.isPauseOnFullInventory());
        SkillPreferences.setTeleportDuringDropSweep(bot.getUuid(), settings.isTeleportDuringDropSweep());
    }

    public static void scheduleAutoSpawns(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }

        // Survival recruitment mode: don't auto-spawn any bots until the world has been recruited.
        if (net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.isEnabled(server)
                && !net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService.isWorldRecruited(server)) {
            return;
        }

        String currentWorldKey = BotWorldStateService.currentWorldKey(server);

        // Collect aliases that explicitly have spawn data or world-state in the current world.
        java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
        Map<String, Map<String, ManualConfig.BotSpawn>> spawnsByWorld = Frens.CONFIG.getBotSpawnPointsByWorld();
        Map<String, Map<String, ManualConfig.BotControlSettings>> controlsByWorld = Frens.CONFIG.getBotControlsByWorld();
        // Only include aliases that have an entry specifically for the current world key
        // (not just any world). This prevents bots from auto-spawning into worlds
        // they've never visited.
        if (spawnsByWorld != null) {
            for (Map.Entry<String, Map<String, ManualConfig.BotSpawn>> e : spawnsByWorld.entrySet()) {
                if (e.getValue() != null && e.getValue().containsKey(currentWorldKey)) {
                    candidates.add(e.getKey());
                }
            }
        }
        if (controlsByWorld != null) {
            for (Map.Entry<String, Map<String, ManualConfig.BotControlSettings>> e : controlsByWorld.entrySet()) {
                if (e.getValue() != null && e.getValue().containsKey(currentWorldKey)) {
                    candidates.add(e.getKey());
                }
            }
        }
        // Also check BotWorldStateService for bots that have position saved in this world
        // but may not have a BotSpawn entry yet (e.g., manually spawned bots).
        Map<String, Integer> worldCounts = BotWorldStateService.debugAliasWorldCounts();
        if (worldCounts != null) {
            for (String wAlias : worldCounts.keySet()) {
                if (BotWorldStateService.loadState(server, wAlias).isPresent()) {
                    candidates.add(wAlias);
                }
            }
        }

        AtomicInteger baseline = new AtomicInteger(-1);
        AtomicInteger dispatched = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        boolean queuedAny = false;
        for (String alias : candidates) {
            if (alias == null || alias.equalsIgnoreCase("default")) {
                continue;
            }
            // Must have a saved spawn point for the current world, OR world state for the current world.
            ManualConfig.BotSpawn spawn = Frens.CONFIG.getBotSpawn(alias, currentWorldKey);
            boolean hasWorldState = false;
            if (spawn == null) {
                // Check BotWorldStateService for position in this world
                hasWorldState = BotWorldStateService.loadState(server, alias).isPresent();
                if (!hasWorldState) continue;
            }
            ServerPlayerEntity existing = server.getPlayerManager().getPlayer(alias);
            String normalizedAlias = alias.toLowerCase(Locale.ROOT);
            if (existing != null || !AUTO_SPAWNED_THIS_SESSION.add(normalizedAlias)) {
                continue;
            }
            // Resolve spawn mode from per-world controls, falling back to "admin"
            ManualConfig.BotControlSettings settings = Frens.CONFIG.getOrCreateBotControl(alias, currentWorldKey);
            if (settings != null && !settings.isAutoSpawnOnLoad()) {
                continue;
            }
            String mode = settings != null ? settings.getSpawnMode() : "admin";
            ManualConfig.BotSpawn finalSpawn = spawn;
            boolean finalHasWorldState = hasWorldState;
            Runnable task = () -> {
                // A bot restored mid-trip arrives by itself: /bot spawn would cancel the trip and
                // drop its post-arrival action. Checked here, not at queue time, because on a
                // dedicated server the queue is built before loadPendingTravels runs.
                NavigationArtifactService.PendingTravel trip = NavigationArtifactService.getPendingTravelByName(alias);
                if (trip != null) {
                    Frens.LOGGER.info("Auto-spawn skipped for {}: in transit to {}, it arrives there instead",
                            alias, trip.destination().toShortString());
                    return;
                }
                int observed = server.getPlayerManager().getCurrentPlayerCount();
                int max = server.getPlayerManager().getMaxPlayerCount();
                baseline.compareAndSet(-1, observed);
                int effective = AutoSpawnPolicy.effectivePlayerCount(observed, baseline.get(), dispatched.get());
                if (!AutoSpawnPolicy.hasRoomForAutoSpawn(effective, max)) {
                    skipped.incrementAndGet();
                    Frens.LOGGER.info("Auto-spawn skipped for {}: {}/{} players and {} slot(s) kept free",
                            alias, effective, max, AutoSpawnPolicy.RESERVED_SLOTS);
                    return;
                }
                dispatched.incrementAndGet();
                CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
                ServerCommandSource source = server.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);
                if (finalSpawn != null) {
                    Identifier id = Identifier.tryParse(finalSpawn.dimension());
                    if (id != null) {
                        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
                        ServerWorld targetWorld = server.getWorld(key);
                        if (targetWorld != null) {
                            source = source.withWorld(targetWorld)
                                    .withPosition(new Vec3d(finalSpawn.x(), finalSpawn.y(), finalSpawn.z()))
                                    .withRotation(new Vec2f(finalSpawn.pitch(), finalSpawn.yaw()));
                        }
                    }
                }
                // If no BotSpawn but world state exists, the bot's position will be
                // restored by BotWorldStateService during onBotJoin, so just spawn at world spawn.
                try {
                    dispatcher.execute("bot spawn " + alias + " " + mode, source);
                } catch (CommandSyntaxException e) {
                    Frens.LOGGER.warn("Failed to auto-spawn {} via config: {}", alias, e.getMessage());
                }
            };
            server.execute(task);
            queuedAny = true;
        }
        if (queuedAny) {
            // Runs after every per-alias task above (server.execute is FIFO on the server thread).
            server.execute(() -> notifySkipped(server, skipped.get()));
        }
    }

    private static void notifySkipped(MinecraftServer server, int skipped) {
        if (skipped <= 0) {
            return;
        }
        int max = server.getPlayerManager().getMaxPlayerCount();
        String message = "\u00a7e" + skipped + " companion" + (skipped == 1 ? " was" : "s were")
                + " not auto-spawned: this world allows " + max
                + " players and one slot is kept free. Use /bot spawn to bring one in.";
        Frens.LOGGER.info("Auto-spawn skipped {} companion(s); world allows {} players", skipped, max);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player instanceof net.wcfcarolina13.Entity.createFakePlayer) {
                continue;
            }
            if (Frens.isOperator(player) || net.wcfcarolina13.network.BotAccessGate.isHost(player)) {
                player.sendMessage(net.minecraft.text.Text.literal(message), false);
            }
        }
    }
}
