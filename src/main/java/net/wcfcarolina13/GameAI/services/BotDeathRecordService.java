package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The last death of every bot (and every real player the mod saw die), per save world:
 * dimension, block position, wall-clock time, damage type and killer. Read by
 * {@code /bot retrieve} to send a bot to the drops.
 *
 * <p>Separate from the questing {@code companionDied*} fields on purpose: those gate resurrection
 * and are zeroed on recruit, reset and resurrection. Nothing clears this store except the next
 * death of the same bot or player in the same world.
 *
 * <p>Stored at {@code config/frens/bot_death_state.json} as {@code key -> worldKey -> record},
 * where the key is the lower-case bot alias, or {@code player:<uuid>} for a real player.
 * Server thread only (written from the death hook, read from a command executor).
 */
public final class BotDeathRecordService {
    private static final Logger LOGGER = LoggerFactory.getLogger("death-record");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_death_state.json";
    private static final Type STATE_TYPE = new TypeToken<Map<String, Map<String, DeathRecord>>>() {}.getType();

    private static final Map<String, Map<String, DeathRecord>> STATE = new HashMap<>();
    private static boolean loaded = false;

    private BotDeathRecordService() {}

    /**
     * One death. {@code cause} is the damage type id ({@code minecraft:lava}), {@code killer} the
     * entity type id of a non-player attacker, or null.
     */
    public record DeathRecord(String dimension, int x, int y, int z, long epochMs, String cause, String killer) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** Records a bot's death. Called from {@code BotPersistenceService.onBotDeath} for every bot. */
    public static synchronized void recordBotDeath(MinecraftServer server, ServerPlayerEntity bot, DamageSource source) {
        if (server == null || bot == null) {
            return;
        }
        record(server, botKey(bot.getName().getString()), bot, source);
    }

    /** Records a real player's death, so {@code /bot retrieve <bot> me} knows its cause and time. */
    public static synchronized void recordPlayerDeath(MinecraftServer server, ServerPlayerEntity player, DamageSource source) {
        if (server == null || player == null) {
            return;
        }
        record(server, playerKey(player.getUuid()), player, source);
    }

    /** The bot's last death in the current save world, or null. */
    public static synchronized DeathRecord getBotRecord(MinecraftServer server, String alias) {
        return get(server, botKey(alias));
    }

    /** The player's last death in the current save world as the mod saw it, or null. */
    public static synchronized DeathRecord getPlayerRecord(MinecraftServer server, UUID playerUuid) {
        return playerUuid == null ? null : get(server, playerKey(playerUuid));
    }

    private static void record(MinecraftServer server, String key, ServerPlayerEntity who, DamageSource source) {
        if (key == null) {
            return;
        }
        try {
            ensureLoaded();
            String worldKey = BotWorldStateService.currentWorldKey(server);
            BlockPos pos = who.getBlockPos();
            String dimension = who.getEntityWorld().getRegistryKey().getValue().toString();
            String cause = causeId(source);
            String killer = killerId(source);
            DeathRecord rec = new DeathRecord(dimension, pos.getX(), pos.getY(), pos.getZ(),
                    System.currentTimeMillis(), cause, killer);
            STATE.computeIfAbsent(key, k -> new HashMap<>()).put(worldKey, rec);
            flush();
            LOGGER.info("[DeathRecord] {} died at {} in {} cause={} killer={} world={}",
                    who.getName().getString(), pos.toShortString(), dimension, cause, killer, worldKey);
        } catch (Throwable t) {
            LOGGER.warn("[DeathRecord] could not record the death of {}: {}", who.getName().getString(), t.toString());
        }
    }

    private static DeathRecord get(MinecraftServer server, String key) {
        if (server == null || key == null) {
            return null;
        }
        ensureLoaded();
        Map<String, DeathRecord> perWorld = STATE.get(key);
        return perWorld == null ? null : perWorld.get(BotWorldStateService.currentWorldKey(server));
    }

    private static String causeId(DamageSource source) {
        if (source == null) {
            return "unknown";
        }
        return source.getTypeRegistryEntry().getKey()
                .map(k -> k.getValue().toString())
                .orElse("unknown");
    }

    /** Entity type id of a non-player attacker, or null. */
    private static String killerId(DamageSource source) {
        Entity attacker = source == null ? null : source.getAttacker();
        if (attacker == null || attacker instanceof PlayerEntity) {
            return null;
        }
        return EntityType.getId(attacker.getType()).toString();
    }

    private static String botKey(String alias) {
        return alias == null || alias.isBlank() ? null : alias.toLowerCase(Locale.ROOT);
    }

    private static String playerKey(UUID uuid) {
        return "player:" + uuid;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = stateFile();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Map<String, DeathRecord>> raw = GSON.fromJson(reader, STATE_TYPE);
            if (raw != null) {
                raw.forEach((k, v) -> {
                    if (k != null && v != null) {
                        STATE.put(k, new HashMap<>(v));
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load death records: {}", e.getMessage());
        }
    }

    private static void flush() {
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(STATE, STATE_TYPE, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save death records: {}", e.getMessage());
        }
    }

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }
}
