package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.shasankp000.Entity.createFakePlayer;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    private static final ConcurrentMap<UUID, Integer> LAST_SAVE_TICK = new ConcurrentHashMap<>();

    private BotPersistenceService() {}

    public static void onBotJoin(ServerPlayerEntity bot) {
        MinecraftServer server = extractServer(bot);
        if (!(bot instanceof createFakePlayer) || server == null) {
            return;
        }

        if (restoreViaPlayerManager(server, bot)) {
            LOGGER.info("Restored fakeplayer '{}' using PlayerManager.", bot.getName().getString());
        } else {
            LOGGER.info("No persisted state for fakeplayer '{}'; starting fresh.", bot.getName().getString());
        }

        BotInventoryStorageService.load(bot);
        LAST_SAVE_TICK.put(bot.getUuid(), server.getTicks());
    }

    public static void onBotDisconnect(ServerPlayerEntity bot) {
        MinecraftServer server = extractServer(bot);
        if (!(bot instanceof createFakePlayer) || server == null) {
            return;
        }
        if (persistViaPlayerManager(server, bot, "disconnect")) {
            BotInventoryStorageService.save(bot);
        }
        LAST_SAVE_TICK.remove(bot.getUuid());
        BotEventHandler.unregisterBot(bot);
    }

    public static void onBotDeath(ServerPlayerEntity bot) {
        MinecraftServer server = extractServer(bot);
        if (!(bot instanceof createFakePlayer) || server == null) {
            return;
        }
        // Mirror "drop on death" by removing the persisted snapshot.
        Path path = resolvePlayerDataPath(server, bot);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
                LOGGER.info("Cleared persisted state for fakeplayer '{}' after death.", bot.getName().getString());
            } catch (IOException e) {
                LOGGER.warn("Unable to clear persisted state for fakeplayer '{}': {}", bot.getName().getString(), e.getMessage());
            }
        }
        BotInventoryStorageService.delete(bot);
        SkillResumeService.handleDeath(bot);
        LAST_SAVE_TICK.remove(bot.getUuid());
    }

    public static void onBotRespawn(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean alive) {
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
            int last = LAST_SAVE_TICK.getOrDefault(player.getUuid(), Integer.MIN_VALUE);
            if (currentTick - last >= AUTOSAVE_INTERVAL_TICKS) {
                if (persistViaPlayerManager(server, player, "autosave")) {
                    BotInventoryStorageService.save(player);
                    LAST_SAVE_TICK.put(player.getUuid(), currentTick);
                }
            }
        }

        LAST_SAVE_TICK.keySet().retainAll(active);
    }

    public static void saveAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long count = server.getPlayerManager().getPlayerList().stream()
                .filter(createFakePlayer.class::isInstance)
                .mapToLong(player -> {
                    if (persistViaPlayerManager(server, player, "shutdown")) {
                        BotInventoryStorageService.save(player);
                        return 1L;
                    }
                    return 0L;
                })
                .sum();
        LOGGER.info("saveAll invoked, persisted {} fakeplayer(s).", count);
    }

    private static MinecraftServer extractServer(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        ServerCommandSource source = bot.getCommandSource();
        return source != null ? source.getServer() : null;
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
}
