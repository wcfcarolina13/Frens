package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-bot, per-world tactical shelter records in {@code config/frens/bot_shelter_state.json}, so a
 * bot that was sheltered when the world closed gets the same shelter (and its type-correct dawn
 * exit) back on join. Keyed bot alias (lower case) to {@link BotWorldStateService#currentWorldKey}
 * to record, like {@code bot_mount_state.json}.
 *
 * <p>Written synchronously on the calling thread, as {@link MountPersistenceService} does (shelter
 * registration runs on the shelter worker, the join restore on the server thread). Every method is
 * synchronized because both threads use it.</p>
 */
public final class ShelterPersistenceStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("shelter-persistence");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "bot_shelter_state.json";
    private static final Type FILE_TYPE = new TypeToken<Map<String, Map<String, ShelterRecord>>>() {}.getType();

    private static final Map<String, Map<String, ShelterRecord>> STATE = new HashMap<>();
    private static boolean loaded = false;

    private ShelterPersistenceStore() {}

    /**
     * One saved shelter. Positions are {x, y, z}; {@code cap} and {@code entryDir} may be null.
     *
     * @param dimension   dimension id, e.g. {@code minecraft:overworld}
     * @param type        {@code BotFleeService.ShelterType} name
     * @param entryDir    {@code Direction} name, e.g. {@code NORTH}
     * @param interior    the cell the bot stands in
     * @param placedBlocks the wall-off ledger (empty for other types)
     */
    public record ShelterRecord(String dimension,
                                String type,
                                int[] cap,
                                String entryDir,
                                int[] interior,
                                int[][] placedBlocks) {}

    public static synchronized void save(String alias, String worldKey, ShelterRecord record) {
        if (alias == null || worldKey == null || record == null) {
            return;
        }
        ensureLoaded();
        STATE.computeIfAbsent(alias, k -> new HashMap<>()).put(worldKey, record);
        flush();
    }

    /** Removes and returns the record (the join restore consumes it whatever it decides). */
    public static synchronized ShelterRecord take(String alias, String worldKey) {
        if (alias == null || worldKey == null) {
            return null;
        }
        ensureLoaded();
        Map<String, ShelterRecord> worlds = STATE.get(alias);
        ShelterRecord record = worlds != null ? worlds.remove(worldKey) : null;
        if (record != null) {
            if (worlds.isEmpty()) {
                STATE.remove(alias);
            }
            flush();
        }
        return record;
    }

    /** Deletes the record; true when there was one. */
    public static synchronized boolean forget(String alias, String worldKey) {
        return take(alias, worldKey) != null;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        Path file = stateFile();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Map<String, Map<String, ShelterRecord>> raw = GSON.fromJson(reader, FILE_TYPE);
                if (raw != null) {
                    raw.forEach((alias, worlds) -> {
                        if (alias != null && worlds != null) {
                            STATE.put(alias, new HashMap<>(worlds));
                        }
                    });
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load shelter state: {}", e.getMessage());
            }
        }
        loaded = true;
    }

    private static void flush() {
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(STATE, FILE_TYPE, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save shelter state: {}", e.getMessage());
        }
    }

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }
}
