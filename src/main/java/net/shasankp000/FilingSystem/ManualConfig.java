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
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

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
    private boolean gameplayTipsEnabled = true;
    private Map<String, BotControlSettings> botControls = new HashMap<>();
    // Seed-agnostic, bot-persistent quest continuity (non-power progression).
    private Map<String, BotQuestMemory> botQuestMemory = new HashMap<>();

    // === Survival recruitment mode ("find a village, then recruit") ===
    // When enabled, bots do not auto-spawn / restore until the world has been "recruited".
    private boolean survivalRecruitmentMode = false;
    // Per-world (level-name key) recruitment state.
    private Map<String, SurvivalRecruitmentState> survivalRecruitment = new HashMap<>();

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
            if (loadedConfig.botQuestMemory == null) {
                loadedConfig.botQuestMemory = new HashMap<>();
            }
            if (loadedConfig.survivalRecruitment == null) {
                loadedConfig.survivalRecruitment = new HashMap<>();
            }
            loadedConfig.normalizeAliasBackedMaps();
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
        if (botGameProfile == null) {
            botGameProfile = new HashMap<>();
        }
        return botGameProfile;
    }

    public void setBotGameProfile(Map<String, String> botGameProfile) {
        this.botGameProfile = normalizeAliasMap(botGameProfile);
    }

    public String getBotProfileUuid(String alias) {
        String key = resolveAliasKey(getBotGameProfile(), alias);
        return key != null ? getBotGameProfile().get(key) : null;
    }

    public void setBotProfile(String alias, String uuid) {
        if (alias == null || alias.isBlank() || uuid == null || uuid.isBlank()) {
            return;
        }
        putAliasValue(getBotGameProfile(), alias, uuid.trim());
    }

    public Map<String, BotOwnership> getBotOwnership() {
        if (botOwnership == null) {
            botOwnership = new HashMap<>();
        }
        return botOwnership;
    }

    public void setBotOwnership(Map<String, BotOwnership> botOwnership) {
        this.botOwnership = normalizeAliasMap(botOwnership);
    }

    public void setOwner(String alias, BotOwnership owner) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        putAliasValue(getBotOwnership(), alias, owner);
    }

    public BotOwnership getOwner(String alias) {
        String key = resolveAliasKey(getBotOwnership(), alias);
        return key != null ? getBotOwnership().get(key) : null;
    }

    public Map<String, BotSpawn> getBotSpawnPoints() {
        if (botSpawnPoints == null) {
            botSpawnPoints = new HashMap<>();
        }
        return botSpawnPoints;
    }

    public void setBotSpawnPoints(Map<String, BotSpawn> botSpawnPoints) {
        this.botSpawnPoints = normalizeAliasMap(botSpawnPoints);
    }

    public void setBotSpawn(String alias, BotSpawn spawn) {
        if (alias == null || alias.isBlank() || spawn == null) {
            return;
        }
        putAliasValue(getBotSpawnPoints(), alias, spawn);
    }

    public BotSpawn getBotSpawn(String alias) {
        String key = resolveAliasKey(getBotSpawnPoints(), alias);
        return key != null ? getBotSpawnPoints().get(key) : null;
    }

    public boolean isDefaultLlmWorldEnabled() {
        return defaultLlmWorldEnabled;
    }

    public void setDefaultLlmWorldEnabled(boolean defaultLlmWorldEnabled) {
        this.defaultLlmWorldEnabled = defaultLlmWorldEnabled;
    }

    public boolean isGameplayTipsEnabled() {
        return gameplayTipsEnabled;
    }

    public void setGameplayTipsEnabled(boolean gameplayTipsEnabled) {
        this.gameplayTipsEnabled = gameplayTipsEnabled;
    }

    public Map<String, BotControlSettings> getBotControls() {
        if (botControls == null) {
            botControls = new HashMap<>();
        }
        return botControls;
    }

    public Map<String, BotQuestMemory> getBotQuestMemory() {
        if (botQuestMemory == null) {
            botQuestMemory = new HashMap<>();
        }
        return botQuestMemory;
    }

    // ===== Survival recruitment mode =====

    public boolean isSurvivalRecruitmentMode() {
        return survivalRecruitmentMode;
    }

    public void setSurvivalRecruitmentMode(boolean survivalRecruitmentMode) {
        this.survivalRecruitmentMode = survivalRecruitmentMode;
    }

    public Map<String, SurvivalRecruitmentState> getSurvivalRecruitment() {
        if (survivalRecruitment == null) {
            survivalRecruitment = new HashMap<>();
        }
        return survivalRecruitment;
    }

    public SurvivalRecruitmentState getOrCreateSurvivalRecruitmentState(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            worldKey = "default";
        }
        String key = worldKey.trim();
        survivalRecruitment = getSurvivalRecruitment();
        return survivalRecruitment.computeIfAbsent(key, ignored -> new SurvivalRecruitmentState());
    }

    public void setSurvivalRecruitmentState(String worldKey, SurvivalRecruitmentState state) {
        if (worldKey == null || worldKey.isBlank() || state == null) {
            return;
        }
        getSurvivalRecruitment().put(worldKey.trim(), state);
    }

    public BotQuestMemory getOrCreateBotQuestMemory(String alias) {
        if (alias == null || alias.isBlank()) {
            alias = "default";
        }
        String key = resolveAliasKey(getBotQuestMemory(), alias);
        if (key == null) {
            key = alias.trim();
        }
        botQuestMemory = getBotQuestMemory();
        return botQuestMemory.computeIfAbsent(key, ignored -> new BotQuestMemory());
    }

    public void setBotControls(Map<String, BotControlSettings> botControls) {
        this.botControls = normalizeAliasMap(botControls);
    }

    public BotControlSettings getOrCreateBotControl(String alias) {
        if (alias == null || alias.isBlank()) {
            alias = "default";
        }
        String key = resolveAliasKey(getBotControls(), alias);
        if (key == null) {
            key = alias.trim();
        }
        botControls = getBotControls();
        return botControls.computeIfAbsent(key, ignored -> new BotControlSettings());
    }

    public BotControlSettings getEffectiveBotControl(String alias) {
        if (alias != null) {
            String key = resolveAliasKey(getBotControls(), alias);
            BotControlSettings specific = key != null ? getBotControls().get(key) : null;
            if (specific != null) {
                return specific;
            }
        }
        String defaultKey = resolveAliasKey(getBotControls(), "default");
        return defaultKey != null ? getBotControls().get(defaultKey) : null;
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

    public void removeBotEntry(String alias) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        removeAliasVariants(botGameProfile, alias);
        removeAliasVariants(botOwnership, alias);
        removeAliasVariants(botSpawnPoints, alias);
        removeAliasVariants(botControls, alias);
        removeAliasVariants(botQuestMemory, alias);
    }

    private void normalizeAliasBackedMaps() {
        botGameProfile = normalizeAliasMap(botGameProfile);
        botOwnership = normalizeAliasMap(botOwnership);
        botSpawnPoints = normalizeAliasMap(botSpawnPoints);
        botControls = normalizeAliasMap(botControls);
        botQuestMemory = normalizeAliasMap(botQuestMemory);
    }

    private static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> String resolveAliasKey(Map<String, T> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) {
            return null;
        }
        String trimmed = alias.trim();
        if (map.containsKey(trimmed)) {
            return trimmed;
        }
        String normalized = normalizeAlias(trimmed);
        for (String key : map.keySet()) {
            if (normalizeAlias(key).equals(normalized)) {
                return key;
            }
        }
        return null;
    }

    private static <T> Map<String, T> normalizeAliasMap(Map<String, T> source) {
        Map<String, T> out = new HashMap<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        Map<String, String> canonicalByNormalized = new HashMap<>();
        for (Map.Entry<String, T> entry : source.entrySet()) {
            String rawKey = entry.getKey();
            if (rawKey == null || rawKey.isBlank()) {
                continue;
            }
            String trimmed = rawKey.trim();
            String normalized = normalizeAlias(trimmed);
            String canonical = canonicalByNormalized.computeIfAbsent(normalized, ignored -> trimmed);
            out.putIfAbsent(canonical, entry.getValue());
        }
        return out;
    }

    private static <T> void putAliasValue(Map<String, T> map, String alias, T value) {
        if (map == null || alias == null || alias.isBlank()) {
            return;
        }
        String key = resolveAliasKey(map, alias);
        if (key == null) {
            key = alias.trim();
        }
        map.put(key, value);
        removeAliasVariantsExcept(map, key);
    }

    private static <T> void removeAliasVariants(Map<String, T> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) {
            return;
        }
        String normalized = normalizeAlias(alias);
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            if (normalizeAlias(it.next()).equals(normalized)) {
                it.remove();
            }
        }
    }

    private static <T> void removeAliasVariantsExcept(Map<String, T> map, String canonicalAlias) {
        if (map == null || map.isEmpty() || canonicalAlias == null || canonicalAlias.isBlank()) {
            return;
        }
        String normalized = normalizeAlias(canonicalAlias);
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (!key.equals(canonicalAlias) && normalizeAlias(key).equals(normalized)) {
                it.remove();
            }
        }
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
        private String gameMode = "survival";
        private boolean teleportDuringSkills = true;
        private boolean pauseOnFullInventory;
        private boolean teleportDuringDropSweep = false;
        private boolean llmEnabled = true;
        private boolean voicedDialogue = true;

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

        public String getGameMode() {
            return (gameMode == null || gameMode.isBlank()) ? "survival" : gameMode;
        }

        public void setGameMode(String gameMode) {
            if (gameMode == null) {
                this.gameMode = "survival";
                return;
            }
            String normalized = gameMode.trim().toLowerCase();
            this.gameMode = normalized.equals("creative") ? "creative" : "survival";
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

        public boolean isTeleportDuringDropSweep() {
            return teleportDuringDropSweep;
        }

        public void setTeleportDuringDropSweep(boolean teleportDuringDropSweep) {
            this.teleportDuringDropSweep = teleportDuringDropSweep;
        }

        public boolean isLlmEnabled() {
            return llmEnabled;
        }

        public void setLlmEnabled(boolean llmEnabled) {
            this.llmEnabled = llmEnabled;
        }

        public boolean isVoicedDialogue() {
            return voicedDialogue;
        }

        public void setVoicedDialogue(boolean voicedDialogue) {
            this.voicedDialogue = voicedDialogue;
        }
    }

    /**
     * Persistent, non-power quest continuity for a bot.
     *
     * <p>Intentionally minimal. Quests are templates; continuity lives here.
     */
    public static class BotQuestMemory {
        private List<String> completedQuestIds = new ArrayList<>();
        private Map<String, Integer> tags = new HashMap<>();

        public List<String> getCompletedQuestIds() {
            if (completedQuestIds == null) {
                completedQuestIds = new ArrayList<>();
            }
            return completedQuestIds;
        }

        public Map<String, Integer> getTags() {
            if (tags == null) {
                tags = new HashMap<>();
            }
            return tags;
        }

        public void recordCompletion(String questId, boolean success) {
            if (questId == null || questId.isBlank()) {
                return;
            }
            // Keep order (most-recent last) but avoid duplicates.
            List<String> list = getCompletedQuestIds();
            list.removeIf(id -> id != null && id.equals(questId));
            list.add(questId);
            // Bound history to avoid config bloat.
            while (list.size() > 64) {
                list.remove(0);
            }

            // Lightweight tag hooks (optional). No power progression.
            // Example: track reliability/patience signals without schema rewrite.
            if (success) {
                bumpTag("quest_success");
            } else {
                bumpTag("quest_failure");
            }
        }

        private void bumpTag(String tag) {
            if (tag == null || tag.isBlank()) {
                return;
            }
            Map<String, Integer> map = getTags();
            map.put(tag, map.getOrDefault(tag, 0) + 1);
        }
    }

    /** Per-world gating info for the survival recruitment flow. */
    public static class SurvivalRecruitmentState {
        private boolean recruited;
        private String recruitedByUuid;
        private String recruitedByName;
        private String botAlias = "Jake";
        private long recruitedAtEpochMs;
        // Whether this world has gone through the first-time mode selection prompt.
        private boolean modeSelectionDone;
        // Last selected world mode ("questing" or "admin") for UI/status transparency.
        private String selectedWorldMode;
        private long modeSelectedAtEpochMs;
        private String modeSelectedByName;

        // ===== Companion questline (per-world, survival recruitment mode) =====
        // Anchor location for village improvement checks.
        private boolean companionAnchorSet;
        private String companionAnchorDimension;
        private long companionAnchorPos;

        // Progression stage for the village/companion arc.
        // 0..N (implementation-defined); stage advances only via server-side validation.
        private int companionQuestStage;
        private boolean permanentCompanion;

        // ===== Companion death / resurrection (survival recruitment mode) =====
        // When true, the recruited companion has died and must be resurrected via the ritual.
        private boolean companionDead;
        private long companionDiedAtEpochMs;
        private String companionDiedDimension;
        private long companionDiedPos;
        private int companionDeathCount;

        public boolean isRecruited() {
            return recruited;
        }

        public void setRecruited(boolean recruited) {
            this.recruited = recruited;
        }

        public String getRecruitedByUuid() {
            return recruitedByUuid;
        }

        public void setRecruitedByUuid(String recruitedByUuid) {
            this.recruitedByUuid = recruitedByUuid;
        }

        public String getRecruitedByName() {
            return recruitedByName;
        }

        public void setRecruitedByName(String recruitedByName) {
            this.recruitedByName = recruitedByName;
        }

        public String getBotAlias() {
            return (botAlias == null || botAlias.isBlank()) ? "Jake" : botAlias;
        }

        public void setBotAlias(String botAlias) {
            this.botAlias = (botAlias == null || botAlias.isBlank()) ? "Jake" : botAlias.trim();
        }

        public long getRecruitedAtEpochMs() {
            return recruitedAtEpochMs;
        }

        public void setRecruitedAtEpochMs(long recruitedAtEpochMs) {
            this.recruitedAtEpochMs = recruitedAtEpochMs;
        }

        public boolean isModeSelectionDone() {
            return modeSelectionDone;
        }

        public void setModeSelectionDone(boolean modeSelectionDone) {
            this.modeSelectionDone = modeSelectionDone;
        }

        public String getSelectedWorldMode() {
            return selectedWorldMode;
        }

        public void setSelectedWorldMode(String selectedWorldMode) {
            if (selectedWorldMode == null || selectedWorldMode.isBlank()) {
                this.selectedWorldMode = null;
                return;
            }
            String normalized = selectedWorldMode.trim().toLowerCase(Locale.ROOT);
            if (!"questing".equals(normalized) && !"admin".equals(normalized)) {
                this.selectedWorldMode = null;
                return;
            }
            this.selectedWorldMode = normalized;
        }

        public long getModeSelectedAtEpochMs() {
            return modeSelectedAtEpochMs;
        }

        public void setModeSelectedAtEpochMs(long modeSelectedAtEpochMs) {
            this.modeSelectedAtEpochMs = Math.max(0L, modeSelectedAtEpochMs);
        }

        public String getModeSelectedByName() {
            return modeSelectedByName;
        }

        public void setModeSelectedByName(String modeSelectedByName) {
            this.modeSelectedByName = (modeSelectedByName == null || modeSelectedByName.isBlank())
                    ? null
                    : modeSelectedByName.trim();
        }

        public boolean isCompanionAnchorSet() {
            return companionAnchorSet;
        }

        public void setCompanionAnchorSet(boolean companionAnchorSet) {
            this.companionAnchorSet = companionAnchorSet;
        }

        public String getCompanionAnchorDimension() {
            return companionAnchorDimension;
        }

        public void setCompanionAnchorDimension(String companionAnchorDimension) {
            this.companionAnchorDimension = companionAnchorDimension;
        }

        public long getCompanionAnchorPos() {
            return companionAnchorPos;
        }

        public void setCompanionAnchorPos(long companionAnchorPos) {
            this.companionAnchorPos = companionAnchorPos;
        }

        public int getCompanionQuestStage() {
            return companionQuestStage;
        }

        public void setCompanionQuestStage(int companionQuestStage) {
            this.companionQuestStage = Math.max(0, companionQuestStage);
        }

        public boolean isPermanentCompanion() {
            return permanentCompanion;
        }

        public void setPermanentCompanion(boolean permanentCompanion) {
            this.permanentCompanion = permanentCompanion;
        }

        public boolean isCompanionDead() {
            return companionDead;
        }

        public void setCompanionDead(boolean companionDead) {
            this.companionDead = companionDead;
        }

        public long getCompanionDiedAtEpochMs() {
            return companionDiedAtEpochMs;
        }

        public void setCompanionDiedAtEpochMs(long companionDiedAtEpochMs) {
            this.companionDiedAtEpochMs = Math.max(0L, companionDiedAtEpochMs);
        }

        public String getCompanionDiedDimension() {
            return companionDiedDimension;
        }

        public void setCompanionDiedDimension(String companionDiedDimension) {
            this.companionDiedDimension = companionDiedDimension;
        }

        public long getCompanionDiedPos() {
            return companionDiedPos;
        }

        public void setCompanionDiedPos(long companionDiedPos) {
            this.companionDiedPos = companionDiedPos;
        }

        public int getCompanionDeathCount() {
            return companionDeathCount;
        }

        public void setCompanionDeathCount(int companionDeathCount) {
            this.companionDeathCount = Math.max(0, companionDeathCount);
        }
    }
}
