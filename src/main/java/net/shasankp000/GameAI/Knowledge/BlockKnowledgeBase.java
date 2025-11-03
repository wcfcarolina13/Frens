package net.shasankp000.GameAI.Knowledge;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class BlockKnowledgeBase {

    private static final Logger LOGGER = LoggerFactory.getLogger("block-knowledge");
    private static final String RESOURCE_PATH = "/block_metadata.json";
    private static final Map<String, BlockMetadata> BY_ID = new HashMap<>();
    private static final Map<String, BlockMetadata> BY_NAME = new HashMap<>();
    private static volatile boolean loaded = false;

    private BlockKnowledgeBase() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try (InputStream stream = BlockKnowledgeBase.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                LOGGER.warn("Block metadata resource {} not found; knowledge base is empty.", RESOURCE_PATH);
                loaded = true;
                return;
            }
            Type listType = new TypeToken<List<BlockMetadata>>() {}.getType();
            List<BlockMetadata> entries = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), listType);
            if (entries == null) {
                LOGGER.warn("Block metadata resource {} parsed to null list.", RESOURCE_PATH);
                loaded = true;
                return;
            }
            entries.forEach(entry -> {
                BY_ID.put(entry.id().toLowerCase(Locale.ROOT), entry);
                BY_NAME.put(entry.name().toLowerCase(Locale.ROOT), entry);
            });
            loaded = true;
            LOGGER.info("Loaded {} block metadata entries.", entries.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load block metadata: {}", e.getMessage(), e);
            loaded = true;
        }
    }

    public static Optional<BlockMetadata> findById(String idOrName) {
        ensureLoaded();
        if (idOrName == null) {
            return Optional.empty();
        }
        String key = idOrName.toLowerCase(Locale.ROOT);
        BlockMetadata metadata = BY_ID.get(key);
        if (metadata != null) {
            return Optional.of(metadata);
        }
        return Optional.ofNullable(BY_NAME.get(key));
    }

    public static List<BlockMetadata> all() {
        ensureLoaded();
        return Collections.unmodifiableList(BY_ID.values().stream().toList());
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }
}
