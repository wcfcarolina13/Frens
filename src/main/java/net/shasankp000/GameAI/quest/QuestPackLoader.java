package net.shasankp000.GameAI.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads quest definitions from a bundled JSON pack.
 */
public final class QuestPackLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("quest-pack");

    private static final String DEFAULT_RESOURCE_PATH = "assets/ai-player/quests/default_quests.json";

    private static final Gson GSON = new GsonBuilder().create();

    private static volatile List<QuestDefinition> CACHED_DEFAULT;

    private QuestPackLoader() {
    }

    public static void resetCache() {
        CACHED_DEFAULT = null;
    }

    public static List<QuestDefinition> getDefaultPack() {
        List<QuestDefinition> cached = CACHED_DEFAULT;
        if (cached != null) {
            return cached;
        }

        List<QuestDefinition> loaded = loadFromResource(DEFAULT_RESOURCE_PATH);
        if (loaded.isEmpty()) {
            LOGGER.warn("Default quest pack missing/empty (path={}) — quests disabled.", DEFAULT_RESOURCE_PATH);
            loaded = List.of();
        }

        CACHED_DEFAULT = loaded;
        return loaded;
    }

    private static List<QuestDefinition> loadFromResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return List.of();
        }

        try (InputStream in = QuestPackLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOGGER.warn("Quest pack not found on classpath: {}", resourcePath);
                return List.of();
            }

            Type listType = new TypeToken<List<QuestDefinition>>() {
            }.getType();

            List<QuestDefinition> parsed = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), listType);
            if (parsed == null || parsed.isEmpty()) {
                return List.of();
            }

            List<QuestDefinition> valid = new ArrayList<>();
            for (QuestDefinition q : parsed) {
                if (q != null && q.isValid()) {
                    valid.add(q);
                }
            }

            LOGGER.info("Loaded {} quest(s) from {} ({} valid)", parsed.size(), resourcePath, valid.size());
            return Collections.unmodifiableList(valid);
        } catch (Exception e) {
            LOGGER.warn("Failed to load quest pack {}: {}", resourcePath, e.getMessage());
            return List.of();
        }
    }
}
