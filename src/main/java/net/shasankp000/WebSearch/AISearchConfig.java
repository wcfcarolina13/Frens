package net.shasankp000.WebSearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.shasankp000.AIPlayer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public class AISearchConfig {

    public static String GEMINI_API_KEY = "";
    public static String SERPER_API_KEY = "";
    public static String PREFERRED_PROVIDER = "gemini";

    static {
        if (!AIPlayer.CONFIG.geminiKey().isEmpty()) {
            GEMINI_API_KEY = AIPlayer.CONFIG.geminiKey(); // using the same api key for web search purposes as well.
        }

        loadConfig();
    }

    private static void loadConfig() {
        try {
            Path configPath = Paths.get("config", "ai_search_config.json");
            if (!Files.exists(configPath)) {
                setupIfMissing(); // Auto-create if not found
            }
            String json = Files.readString(configPath);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(json);
            GEMINI_API_KEY = node.path("gemini_api_key").asText();
            SERPER_API_KEY = node.path("serper_api_key").asText();
            PREFERRED_PROVIDER = node.path("preferred_provider").asText();
        } catch (Exception e) {
            System.err.println("❌ Failed to load AI Search config: " + e.getMessage());
        }
    }

    public static void setupIfMissing() {
        try {
            Path configDir = Paths.get("config");
            Path configPath = configDir.resolve("ai_search_config.json");

            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            if (!Files.exists(configPath)) {
                ObjectMapper mapper = new ObjectMapper();
                ObjectNode root = mapper.createObjectNode();
                root.put("gemini_api_key", "YOUR_GEMINI_API_KEY_HERE");
                root.put("serper_api_key", "YOUR_SERPER_API_KEY_HERE");
                root.put("preferred_provider", "gemini");

                Files.writeString(
                        configPath,
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                        StandardCharsets.UTF_8
                );
                System.out.println("✅ Created default ai_search_config.json in config folder.");
            } else {
                System.out.println("ℹ️ ai_search_config.json already exists, no setup needed.");
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to create AI Search config: " + e.getMessage());
        }
    }
}
