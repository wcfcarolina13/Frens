package net.shasankp000.FilingSystem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.shasankp000.Exception.ollamaNotReachableException;
import net.shasankp000.ServiceLLMClients.*;
import net.shasankp000.LauncherDetection.LauncherEnvironment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles all mod configuration settings using a plain JSON file.
 * This class replaces the owo-lib config wrapper to provide manual control
 * over saving and loading, resolving race conditions and initialization issues.
 */
public class ManualConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("ManualConfig");
    private static final String FILE_NAME = "settings.json5";

    // Use a static method to get the correct, dynamically-resolved file path
    private static final String FILE_PATH = getFilePath();

    // --- Configuration fields (same as before) ---
    private List<String> modelList = new ArrayList<>();
    private String selectedLanguageModel;
    private String llmMode = System.getProperty("aiplayer.llmMode", "ollama");
    private String openAIKey = "";
    private String claudeKey = "";
    private String geminiKey = "";
    private String grokKey = "";
    private String customApiKey = "";
    private String customApiUrl = "";
    private String ollamaBaseUrl = System.getProperty("aiplayer.ollamaHost", "http://127.0.0.1:11434");
    private Map<String, String> botGameProfile = new HashMap<>();
    private Map<String, BotOwnership> botOwnership = new HashMap<>();
    private Map<String, BotSpawn> botSpawnPoints = new HashMap<>();
    private boolean defaultLlmWorldEnabled = true;
    private Map<String, BotControlSettings> botControls = new HashMap<>();

    /**
     * Private constructor to prevent direct instantiation.
     * Use the static load() method instead.
     */
    private ManualConfig() {
        // Initialize with default values
        this.selectedLanguageModel = System.getProperty("aiplayer.llmModel", null);
    }

    /**
     * Helper method to get the correct file path using the LauncherEnvironment class.
     * @return The absolute path to the settings file.
     */
    private static String getFilePath() {
        return LauncherEnvironment.getStorageDirectory("config") + File.separator + FILE_NAME;
    }

    /**
     * Asynchronously updates the list of available models based on the selected provider.
     * This method fetches the model list and then saves the updated configuration to the file.
     */
    public void updateModels() {
        // Run the network operation on a separate thread to prevent freezing.
        CompletableFuture.runAsync(() -> {
            try {
                List<String> fetchedModels = new ArrayList<>();
                ModelFetcher modelFetcher = null;
                String apiKey = "";

                switch (llmMode) {
                    case "ollama":
                        try {
                            LOGGER.info("Using ollama");
                            fetchedModels = getLanguageModels.get();
                            this.modelList = fetchedModels;
                            LOGGER.info("Fetched models: {}", this.modelList);
                            this.save();
                            return;
                        } catch (ollamaNotReachableException e) {
                            LOGGER.error("Ollama is not reachable: {}", e.getMessage());
                            fetchedModels.add("Ollama is not reachable!");
                        }
                        break;
                    case "openai":
                        modelFetcher = new OpenAIModelFetcher();
                        apiKey = this.openAIKey;
                        break;
                    case "claude":
                        modelFetcher = new ClaudeModelFetcher();
                        apiKey = this.claudeKey;
                        break;
                    case "gemini":
                        modelFetcher = new GeminiModelFetcher();
                        apiKey = this.geminiKey;
                        break;
                    case "grok":
                        modelFetcher = new GrokModelFetcher();
                        apiKey = this.grokKey;
                        break;
                    case "custom":
                        if (!this.customApiUrl.isEmpty()) {
                            modelFetcher = new GenericOpenAIModelFetcher(this.customApiUrl);
                            apiKey = this.customApiKey;
                        } else {
                            LOGGER.error("Custom provider selected but no API URL configured");
                            return;
                        }
                        break;
                    default:
                        LOGGER.error("Unsupported provider: {}", llmMode);
                        return;
                }

                if (llmMode.equals("ollama")) {
                    // ollama is handled above, so we just skip API key check.
                    LOGGER.info("Skipping API key check for ollama");
                    this.modelList = fetchedModels;
                    LOGGER.info("ollama modelList: {}", this.modelList);
                    this.save();
                }
                else {
                    if (modelFetcher != null) {
                        if(apiKey.isEmpty()) {
                            // in the event that a user removes their api key but still have a service based provider set.
                            fetchedModels = new ArrayList<>();
                            selectedLanguageModel="No models available. Please enter an API key";
                        }
                        else {
                            try {
                                fetchedModels = modelFetcher.fetchModels(apiKey);
                                LOGGER.info("Retrieved models {} for provider: {}", fetchedModels , llmMode);
                                if (selectedLanguageModel != null && selectedLanguageModel.equals("No models available. Please enter an API key")) {
                                    selectedLanguageModel="";
                                }
                            } catch (Exception e) {
                                LOGGER.error("Error fetching models: {}", e.getMessage(), e);
                                fetchedModels = new ArrayList<>();
                            }
                        }
                    }
                    this.modelList = fetchedModels;
                    LOGGER.debug("this.modelList: {}", this.modelList);
                    LOGGER.info("modelList: {}", this.modelList);
                    this.save();
                }
            } catch (Exception e) {
                LOGGER.error("Exception in updateModels: {}", e.getMessage(), e);
                this.modelList = new ArrayList<>();
                this.save();
            }

        });
    }

    /**
     * Saves the current configuration to the settings.json5 file.
     */
    public void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(FILE_PATH)) {
            gson.toJson(this, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save config file: {}", e.getMessage());
        }
    }

    /**
     * Loads the configuration from the settings.json5 file. If the file does not exist,
     * it creates and returns a new default configuration instance.
     *
     * @return A loaded ManualConfig instance, or a new one if the file is not found.
     */
    public static ManualConfig load() {
        File file = new File(FILE_PATH);
        // Ensure the directory for the file exists before attempting to write.
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        if (!file.exists()) {
            LOGGER.info("Config file not found. Creating a new one.");
            ManualConfig defaultConfig = new ManualConfig();
            defaultConfig.save(); // Save the new config to create the file
            return defaultConfig;
        }

        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<ManualConfig>(){}.getType();
            ManualConfig loadedConfig = gson.fromJson(reader, type);
            // After loading, ensure the model list is updated.
            String currentProvider = System.getProperty("aiplayer.llmMode", "ollama");
            loadedConfig.checkAndUpdateProvider(currentProvider);
            if (loadedConfig.botControls == null) {
                loadedConfig.botControls = new HashMap<>();
            }
            return loadedConfig;
        } catch (IOException e) {
            LOGGER.error("Failed to load config file. Using default config.", e);
            return new ManualConfig();
        }
    }

    /**
     * Checks if the provider has changed, and if so, updates llmMode, clears modelList, and fetches new models.
     * @param newProvider The newly selected provider (llmMode)
     */
    public void checkAndUpdateProvider(String newProvider) {
        if (!this.llmMode.equals(newProvider)) {
            LOGGER.info("Provider changed from {} to {}. Invalidating modelList and updating config.", this.llmMode, newProvider);
            this.llmMode = newProvider;
            this.modelList = new ArrayList<>();
            this.selectedLanguageModel = null;
            this.save();
            this.updateModels();
        }
    }

    // --- Getters and setters (same as before) ---
    public String getOpenAIKey() {
        return openAIKey;
    }

    public void setOpenAIKey(String openAIKey) {
        this.openAIKey = openAIKey != null ? openAIKey.trim() : "";
    }

    public String getClaudeKey() {
        return claudeKey;
    }

    public void setClaudeKey(String claudeKey) {
        this.claudeKey = claudeKey != null ? claudeKey.trim() : "";
    }

    public String getGeminiKey() {
        return geminiKey;
    }

    public void setGeminiKey(String geminiKey) {
        this.geminiKey = geminiKey != null ? geminiKey.trim() : "";
    }

    public String getGrokKey() {
        return grokKey;
    }

    public void setGrokKey(String grokKey) {
        this.grokKey = grokKey != null ? grokKey.trim() : "";
    }

    public String getCustomApiKey() {
        return customApiKey;
    }

    public void setCustomApiKey(String customApiKey) {
        this.customApiKey = customApiKey != null ? customApiKey.trim() : "";
    }

    public String getCustomApiUrl() {
        return customApiUrl;
    }

    public void setCustomApiUrl(String customApiUrl) {
        this.customApiUrl = customApiUrl != null ? customApiUrl.trim() : "";
    }

    public String getOllamaBaseUrl() {
        return (ollamaBaseUrl == null || ollamaBaseUrl.isBlank())
                ? "http://127.0.0.1:11434"
                : ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        if (ollamaBaseUrl == null || ollamaBaseUrl.isBlank()) {
            this.ollamaBaseUrl = "http://127.0.0.1:11434";
        } else {
            this.ollamaBaseUrl = ollamaBaseUrl.trim();
        }
    }

    public List<String> getModelList() {
        return modelList;
    }

    public void setModelList(List<String> modelList) {
        this.modelList = modelList;
    }

    public String getSelectedLanguageModel() {
        return selectedLanguageModel;
    }

    public void setSelectedLanguageModel(String selectedLanguageModel) {
        this.selectedLanguageModel = selectedLanguageModel;
        if (selectedLanguageModel != null) {
            System.setProperty("aiplayer.llmModel", selectedLanguageModel);
        }
    }

    public String getLlmMode() {
        return llmMode;
    }

    public Map<String, String> getBotGameProfile() {
        return botGameProfile;
    }

    public void setBotGameProfile(Map<String, String> botGameProfile) {
        this.botGameProfile = botGameProfile;
    }

    public Map<String, BotOwnership> getBotOwnership() {
        if (botOwnership == null) {
            botOwnership = new HashMap<>();
        }
        return botOwnership;
    }

    public void setBotOwnership(Map<String, BotOwnership> botOwnership) {
        this.botOwnership = botOwnership != null ? new HashMap<>(botOwnership) : new HashMap<>();
    }

    public void setOwner(String alias, BotOwnership owner) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        getBotOwnership().put(alias.trim(), owner);
    }

    public BotOwnership getOwner(String alias) {
        if (alias == null) {
            return null;
        }
        return getBotOwnership().get(alias.trim());
    }

    public Map<String, BotSpawn> getBotSpawnPoints() {
        if (botSpawnPoints == null) {
            botSpawnPoints = new HashMap<>();
        }
        return botSpawnPoints;
    }

    public void setBotSpawnPoints(Map<String, BotSpawn> botSpawnPoints) {
        this.botSpawnPoints = botSpawnPoints != null ? new HashMap<>(botSpawnPoints) : new HashMap<>();
    }

    public void setBotSpawn(String alias, BotSpawn spawn) {
        if (alias == null || alias.isBlank() || spawn == null) {
            return;
        }
        getBotSpawnPoints().put(alias.trim(), spawn);
    }

    public BotSpawn getBotSpawn(String alias) {
        if (alias == null) {
            return null;
        }
        return getBotSpawnPoints().get(alias.trim());
    }

    public boolean isDefaultLlmWorldEnabled() {
        return defaultLlmWorldEnabled;
    }

    public void setDefaultLlmWorldEnabled(boolean defaultLlmWorldEnabled) {
        this.defaultLlmWorldEnabled = defaultLlmWorldEnabled;
    }

    public Map<String, BotControlSettings> getBotControls() {
        if (botControls == null) {
            botControls = new HashMap<>();
        }
        return botControls;
    }

    public void setBotControls(Map<String, BotControlSettings> botControls) {
        this.botControls = botControls != null ? new HashMap<>(botControls) : new HashMap<>();
    }

    public BotControlSettings getOrCreateBotControl(String alias) {
        if (alias == null || alias.isBlank()) {
            alias = "default";
        }
        String key = alias.trim();
        botControls = getBotControls();
        return botControls.computeIfAbsent(key, ignored -> new BotControlSettings());
    }

    public BotControlSettings getEffectiveBotControl(String alias) {
        if (alias != null) {
            BotControlSettings specific = getBotControls().get(alias.trim());
            if (specific != null) {
                return specific;
            }
        }
        return getBotControls().get("default");
    }

    public void ensureOwner(String alias, UUID ownerUuid, String ownerName) {
        if (alias == null || ownerUuid == null) {
            return;
        }
        BotOwnership existing = getOwner(alias);
        if (existing != null && existing.ownerUuid() != null && !existing.ownerUuid().isBlank()) {
            return;
        }
        BotOwnership updated = new BotOwnership(ownerUuid.toString(), ownerName);
        setOwner(alias, updated);
        save();
    }

    public static class BotOwnership {
        private String ownerUuid;
        private String ownerName;

        public BotOwnership() {
        }

        public BotOwnership(String ownerUuid, String ownerName) {
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
        }

        public String ownerUuid() {
            return ownerUuid;
        }

        public String ownerName() {
            return ownerName;
        }

        public void setOwnerUuid(String ownerUuid) {
            this.ownerUuid = ownerUuid;
        }

        public void setOwnerName(String ownerName) {
            this.ownerName = ownerName;
        }
    }

    public static class BotSpawn {
        private String dimension;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        public BotSpawn() {
        }

        public BotSpawn(String dimension, double x, double y, double z, float yaw, float pitch) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public String dimension() {
            return dimension;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }
    }

    public static class BotControlSettings {
        private boolean autoSpawn;
        private String spawnMode = "training";
        private boolean teleportDuringSkills = true;
        private boolean pauseOnFullInventory;
        private boolean llmEnabled = true;

        public boolean isAutoSpawn() {
            return autoSpawn;
        }

        public void setAutoSpawn(boolean autoSpawn) {
            this.autoSpawn = autoSpawn;
        }

        public String getSpawnMode() {
            return (spawnMode == null || spawnMode.isBlank()) ? "training" : spawnMode;
        }

        public void setSpawnMode(String spawnMode) {
            if (spawnMode == null) {
                this.spawnMode = "training";
                return;
            }
            String normalized = spawnMode.trim().toLowerCase();
            this.spawnMode = normalized.equals("play") ? "play" : "training";
        }

        public boolean isTeleportDuringSkills() {
            return teleportDuringSkills;
        }

        public void setTeleportDuringSkills(boolean teleportDuringSkills) {
            this.teleportDuringSkills = teleportDuringSkills;
        }

        public boolean isPauseOnFullInventory() {
            return pauseOnFullInventory;
        }

        public void setPauseOnFullInventory(boolean pauseOnFullInventory) {
            this.pauseOnFullInventory = pauseOnFullInventory;
        }

        public boolean isLlmEnabled() {
            return llmEnabled;
        }

        public void setLlmEnabled(boolean llmEnabled) {
            this.llmEnabled = llmEnabled;
        }
    }
}
