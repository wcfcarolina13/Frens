package net.shasankp000.GameAI.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Simple per-world memory store used by the LLM orchestrator.
 * Phase 2 focuses on personas/quirks plus a rolling list of recent memories.
 */
public final class MemoryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("LLMMemoryStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type WORLD_TYPE = new TypeToken<WorldMemory>() {}.getType();
    private static final int MAX_MEMORIES = 50;
    private static final List<String> ARCHETYPES = List.of(
            "a pragmatic engineer who treats every expedition like a redstone project gone rogue",
            "a sardonic ranger with a knack for surviving ridiculous odds",
            "an excitable scholar documenting every biome like an Elder Scrolls bard",
            "a stoic guardian who quietly keeps tally of mob kills and near misses",
            "a thrill-seeking spelunker who compares every cave to ancient ruins"
    );
    private static final List<String> QUIRKS = List.of(
            "collects every lapis block as if it were enchanted lore",
            "grumbles about gravel avalanches",
            "counts creeper encounters under their breath",
            "names each mineshaft tunnel after the player",
            "keeps comparing this world to distant provinces they've 'visited'"
    );

    private final Path rootDir;
    private final Map<String, WorldMemory> cache = new HashMap<>();

    public MemoryStore() {
        this.rootDir = FabricLoader.getInstance().getGameDir().resolve("llm_memory");
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            LOGGER.error("Unable to create llm_memory directory: {}", e.getMessage());
        }
    }

    public BotProfile getOrCreateProfile(String worldKey, ServerPlayerEntity bot) {
        WorldMemory world = loadWorld(worldKey);
        String key = bot.getUuid().toString();
        BotProfile profile = world.bots().get(key);
        if (profile == null) {
            profile = generateProfile(bot, worldKey);
            world.bots().put(key, profile);
            saveWorld(worldKey, world);
        }
        return profile;
    }

    public void appendMemory(String worldKey, UUID botId, String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        WorldMemory world = loadWorld(worldKey);
        BotProfile profile = world.bots().computeIfAbsent(botId.toString(), id ->
                new BotProfile("", "", new ArrayList<>(), new ArrayDeque<>()));
        Deque<String> memories = profile.memories();
        memories.addFirst(Instant.now() + ": " + entry);
        while (memories.size() > MAX_MEMORIES) {
            memories.removeLast();
        }
        saveWorld(worldKey, world);
    }

    public String buildPersonaPrompt(String worldKey, ServerPlayerEntity bot) {
        BotProfile profile = getOrCreateProfile(worldKey, bot);
        StringBuilder builder = new StringBuilder();
        builder.append("You are ").append(bot.getName().getString()).append(", ")
                .append(profile.persona()).append(". ");
        if (!profile.quirks().isEmpty()) {
            builder.append("Your quirks: ").append(String.join(", ", profile.quirks())).append(". ");
        }
        if (!profile.memories().isEmpty()) {
            builder.append("Recent memories:\n");
            profile.memories().stream().limit(5).forEach(m -> builder.append("- ").append(m).append("\n"));
        }
        return builder.toString();
    }

    private BotProfile generateProfile(ServerPlayerEntity bot, String worldKey) {
        Random random = new Random(Objects.hash(bot.getUuid(), worldKey));
        String persona = ARCHETYPES.get(random.nextInt(ARCHETYPES.size()));
        List<String> shuffledQuirks = new ArrayList<>(QUIRKS);
        Collections.shuffle(shuffledQuirks, random);
        List<String> quirks = new ArrayList<>();
        for (int i = 0; i < Math.min(2, shuffledQuirks.size()); i++) {
            quirks.add(shuffledQuirks.get(i));
        }
        return new BotProfile(bot.getName().getString(), persona, quirks, new ArrayDeque<>());
    }

    private synchronized WorldMemory loadWorld(String worldKey) {
        return cache.computeIfAbsent(worldKey, key -> {
            Path file = resolveWorldPath(key);
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    WorldMemory memory = GSON.fromJson(reader, WORLD_TYPE);
                    return memory != null ? memory : new WorldMemory(new HashMap<>());
                } catch (IOException e) {
                    LOGGER.warn("Failed to read memory file {}: {}", file, e.getMessage());
                }
            }
            return new WorldMemory(new HashMap<>());
        });
    }

    private synchronized void saveWorld(String worldKey, WorldMemory memory) {
        Path file = resolveWorldPath(worldKey);
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(memory, WORLD_TYPE, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save memory file {}: {}", file, e.getMessage());
        }
    }

    private Path resolveWorldPath(String worldKey) {
        String safe = worldKey.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
        return rootDir.resolve(safe + ".json");
    }

    private record WorldMemory(Map<String, BotProfile> bots) {
    }

    public record BotProfile(String name,
                             String persona,
                             List<String> quirks,
                             Deque<String> memories) {
    }
}
