package net.shasankp000.GameAI.services;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.shasankp000.Entity.createFakePlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared helper for resolving fake player command targets.
 */
public final class BotTargetingService {

    private static final SimpleCommandExceptionType NO_BOT_SELECTED =
            new SimpleCommandExceptionType(Text.literal("No bot selected. Specify a bot name, 'all', or target one first."));
    private static final SimpleCommandExceptionType BOT_NOT_FOUND =
            new SimpleCommandExceptionType(Text.literal("Could not find a matching bot."));
    private static final SimpleCommandExceptionType NO_BOTS_AVAILABLE =
            new SimpleCommandExceptionType(Text.literal("No active bots to target."));

    private static final Object CONSOLE_KEY = new Object();
    private static final Map<Object, String> LAST_TARGET = new ConcurrentHashMap<>();

    private BotTargetingService() {}

    /**
     * Resolve a list of bot entities based on the provided target string.
     *
     * @param source      command source
     * @param targetInput alias name, "all", or null to use the last targeted bot
     */
    public static List<ServerPlayerEntity> resolve(ServerCommandSource source, String targetInput) throws CommandSyntaxException {
        MinecraftServer server = source.getServer();
        if (server == null) {
            throw new SimpleCommandExceptionType(Text.literal("Command source is not attached to a server.")).create();
        }

        List<ServerPlayerEntity> activeBots = collectActiveBots(server);
        if (activeBots.isEmpty()) {
            throw NO_BOTS_AVAILABLE.create();
        }

        Object key = keyForSource(source);
        String normalizedInput = normalize(targetInput);

        if (normalizedInput == null) {
            String remembered = LAST_TARGET.get(key);
            if (remembered == null) {
                throw NO_BOT_SELECTED.create();
            }
            normalizedInput = remembered;
        }

        if ("all".equals(normalizedInput)) {
            return new ArrayList<>(activeBots);
        }

        final String targetAlias = normalizedInput;
        Optional<ServerPlayerEntity> match = activeBots.stream()
                .filter(bot -> normalize(bot.getGameProfile().name()).equals(targetAlias))
                .findFirst();

        if (match.isEmpty()) {
            throw BOT_NOT_FOUND.create();
        }

        LAST_TARGET.put(key, normalizedInput);
        return Collections.singletonList(match.get());
    }

    private static List<ServerPlayerEntity> collectActiveBots(MinecraftServer server) {
        List<ServerPlayerEntity> bots = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player instanceof createFakePlayer) {
                bots.add(player);
            }
        }
        return bots;
    }

    private static Object keyForSource(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getUuid();
        }
        // Distinguish between different command blocks/console by their position?
        // For now treat all non-player sources as the same logical sender.
        return CONSOLE_KEY;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isKnownTarget(MinecraftServer server, String token) {
        String normalized = normalize(token);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        if ("all".equals(normalized)) {
            return true;
        }
        return collectActiveBots(server).stream()
                .anyMatch(bot -> normalize(bot.getGameProfile().name()).equals(normalized));
    }

    /**
     * Clear any remembered target for a source. Useful when bot despawns permanently.
     */
    public static void forget(ServerCommandSource source) {
        Object key = keyForSource(source);
        LAST_TARGET.remove(key);
    }

    public static void forgetIfMatches(ServerCommandSource source, String alias) {
        Object key = keyForSource(source);
        String normalized = normalize(alias);
        if (normalized == null) {
            return;
        }
        String existing = LAST_TARGET.get(key);
        if (existing != null && existing.equals(normalized)) {
            LAST_TARGET.remove(key);
        }
    }

    /**
     * Explicitly remember that the provided source targeted the supplied alias.
     */
    public static void remember(ServerCommandSource source, String alias) {
        Object key = keyForSource(source);
        LAST_TARGET.put(key, normalize(alias));
    }

    public static String getRemembered(ServerCommandSource source) {
        Object key = keyForSource(source);
        return LAST_TARGET.get(key);
    }

    public static List<ServerPlayerEntity> resolveMany(ServerCommandSource source, List<String> aliases) throws CommandSyntaxException {
        if (aliases == null || aliases.isEmpty()) {
            return resolve(source, null);
        }
        List<ServerPlayerEntity> results = new ArrayList<>();
        for (String alias : aliases) {
            String trimmed = alias == null ? null : alias.trim();
            if (trimmed == null || trimmed.isEmpty()) {
                continue;
            }
            if ("all".equalsIgnoreCase(trimmed)) {
                return resolve(source, "all");
            }
            for (ServerPlayerEntity bot : resolve(source, trimmed)) {
                if (!results.contains(bot)) {
                    results.add(bot);
                }
            }
        }
        if (results.isEmpty()) {
            throw BOT_NOT_FOUND.create();
        }
        return results;
    }
}
