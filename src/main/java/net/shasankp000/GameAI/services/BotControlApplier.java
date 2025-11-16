package net.shasankp000.GameAI.services;

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
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.llm.LLMOrchestrator;
import net.shasankp000.GameAI.skills.SkillPreferences;

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
        if (server == null || AIPlayer.CONFIG == null) {
            return;
        }
        applyWorldToggle(server);
        refreshBotPreferences(server);
        scheduleAutoSpawns(server);
    }

    public static void applyWorldToggle(MinecraftServer server) {
        if (server == null || AIPlayer.CONFIG == null) {
            return;
        }
        String levelName = server.getSaveProperties().getLevelName();
        String overworldKey = levelName + ":" + World.OVERWORLD.getValue().toString();
        LLMOrchestrator.setWorldEnabled(overworldKey, AIPlayer.CONFIG.isDefaultLlmWorldEnabled());
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
        if (bot == null || AIPlayer.CONFIG == null) {
            return;
        }
        ManualConfig.BotControlSettings settings = AIPlayer.CONFIG.getEffectiveBotControl(bot.getName().getString());
        if (settings == null) {
            return;
        }
        SkillPreferences.setTeleportDuringSkills(bot.getUuid(), settings.isTeleportDuringSkills());
        SkillPreferences.setPauseOnFullInventory(bot.getUuid(), settings.isPauseOnFullInventory());
        LLMOrchestrator.setBotEnabled(bot.getUuid(), settings.isLlmEnabled());
    }

    public static void scheduleAutoSpawns(MinecraftServer server) {
        if (server == null || AIPlayer.CONFIG == null) {
            return;
        }
        for (Map.Entry<String, ManualConfig.BotControlSettings> entry : AIPlayer.CONFIG.getBotControls().entrySet()) {
            String alias = entry.getKey();
            if (alias == null || alias.equalsIgnoreCase("default")) {
                continue;
            }
            ManualConfig.BotControlSettings settings = entry.getValue();
            if (!settings.isAutoSpawn()) {
                continue;
            }
            ServerPlayerEntity existing = server.getPlayerManager().getPlayer(alias);
            String normalizedAlias = alias.toLowerCase(Locale.ROOT);
            if (existing != null || !AUTO_SPAWNED_THIS_SESSION.add(normalizedAlias)) {
                continue;
            }
            String mode = settings.getSpawnMode();
            Runnable task = () -> {
                CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
                ServerCommandSource source = server.getCommandSource().withSilent().withMaxLevel(4);
                ManualConfig.BotSpawn spawn = AIPlayer.CONFIG.getBotSpawn(alias);
                if (spawn != null) {
                    Identifier id = Identifier.tryParse(spawn.dimension());
                    if (id != null) {
                        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
                        ServerWorld targetWorld = server.getWorld(key);
                        if (targetWorld != null) {
                            source = source.withWorld(targetWorld)
                                    .withPosition(new Vec3d(spawn.x(), spawn.y(), spawn.z()))
                                    .withRotation(new Vec2f(spawn.yaw(), spawn.pitch()));
                        }
                    }
                }
                try {
                    dispatcher.execute("bot spawn " + alias + " " + mode, source);
                } catch (CommandSyntaxException e) {
                    AIPlayer.LOGGER.warn("Failed to auto-spawn {} via config: {}", alias, e.getMessage());
                }
            };
            server.execute(task);
        }
    }
}
