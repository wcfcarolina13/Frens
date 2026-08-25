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
import net.wcfcarolina13.GameAI.llm.LLMOrchestrator;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies persisted /configMan toggles to the running server so players do not need to repeat console commands.
 */
public final class BotControlApplier {

    private static final Set<String> AUTO_SPAWNED_THIS_SESSION = ConcurrentHashMap.newKeySet();

    private BotControlApplier() {
    }

    public static void resetSession() {
        AUTO_SPAWNED_THIS_SESSION.clear();
    }

    public static void applyPersistentSettings(MinecraftServer server) {
        if (server == null || Frens.CONFIG == null) {
            return;
        }
        refreshBotPreferences(server);
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
        // LLM enablement is no longer pushed here — LLMOrchestrator reads ManualConfig lazily.
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
            // Resolve spawn mode from per-world controls, falling back to "training"
            ManualConfig.BotControlSettings settings = Frens.CONFIG.getOrCreateBotControl(alias, currentWorldKey);
            if (settings != null && !settings.isAutoSpawnOnLoad()) {
                continue;
            }
            String mode = settings != null ? settings.getSpawnMode() : "training";
            ManualConfig.BotSpawn finalSpawn = spawn;
            boolean finalHasWorldState = hasWorldState;
            Runnable task = () -> {
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
        }
    }
}
