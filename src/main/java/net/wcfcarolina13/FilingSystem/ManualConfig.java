package net.wcfcarolina13.FilingSystem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.wcfcarolina13.Exception.ollamaNotReachableException;
import net.wcfcarolina13.ServiceLLMClients.*;
import net.wcfcarolina13.LauncherDetection.LauncherEnvironment;

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

    // Lazily-resolved file path. Deliberately NOT a static final field initializer: eager
    // resolution here calls into LauncherEnvironment -> FabricLoader.getInstance().getGameDir(),
    // which throws outside a real Fabric launch (e.g. class-loading ManualConfig for a plain
    // JUnit mock). Resolving on first real use (save()/load()) keeps behavior identical in the
    // running mod while letting the class load safely in a test JVM that never calls into it.
    private static volatile String filePath;

    private static String filePath() {
        String resolved = filePath;
        if (resolved == null) {
            synchronized (ManualConfig.class) {
                resolved = filePath;
                if (resolved == null) {
                    resolved = getFilePath();
                    filePath = resolved;
                }
            }
        }
        return resolved;
    }

    // --- Configuration fields (same as before) ---
    private List<String> modelList = new ArrayList<>();
    private String selectedLanguageModel;
    private String llmMode = System.getProperty("frens.llmMode", System.getProperty("aiplayer.llmMode", "ollama"));
    private String openAIKey = "";
    private String claudeKey = "";
    private String geminiKey = "";
    private String grokKey = "";
    private String customApiKey = "";
    private String customApiUrl = "";
    private String ollamaBaseUrl = System.getProperty("frens.ollamaHost", System.getProperty("aiplayer.ollamaHost", "http://127.0.0.1:11434"));
    private Map<String, String> botGameProfile = new HashMap<>();
    private Map<String, BotOwnership> botOwnership = new HashMap<>();
    private Map<String, Boolean> playerPreserveExpensiveGear = new HashMap<>();
    private Map<String, Boolean> playerAutoAcceptPreciousFoods = new HashMap<>();
    private Map<String, BotSpawn> botSpawnPoints = new HashMap<>();
    // Per-world bot spawn points: alias → worldKey → BotSpawn.
    private Map<String, Map<String, BotSpawn>> botSpawnPointsByWorld = new HashMap<>();
    private boolean defaultLlmWorldEnabled = true;
    private boolean textDialogueEnabled = true;
    private boolean voicedDialogueEnabled = true;
    // Muted voiced-dialogue categories (VoiceLineCategory ids). Empty = nothing muted.
    private List<String> mutedVoiceCategories = new ArrayList<>();
    // Muted text categories (VoiceLineCategory ids) — same semantics as the voice mask:
    // consulted only while textDialogueEnabled is ON; empty = all text shows.
    // (Replaced the short-lived inverse "keep-visible exceptions" model, which made the
    // two Adv menus behave as confusing duals of each other.)
    private List<String> mutedTextCategories = new ArrayList<>();
    private boolean gameplayTipsEnabled = true;
    private boolean idleHobbiesAnywhereEnabled = false;
    private boolean baritonePathfinderEnabled = false;
    private boolean fortifyForcePlaceEnabled = false;
    /** Global override for teleportDuringSkills. When non-null, overrides all per-bot settings. */
    private Boolean globalTeleportDuringSkills = null;
    private int fortBufferRadius = 8;
    private int undergroundLingerMinutes = 3;
    private int undergroundProximityBlocks = 32;
    private Map<String, String> botSkins = new HashMap<>();
    private Map<String, BotSkinSelection> botSkinSelections = new HashMap<>();
    private Map<String, BotControlSettings> botControls = new HashMap<>();
    // Per-world bot control settings: alias → worldKey → BotControlSettings.
    private Map<String, Map<String, BotControlSettings>> botControlsByWorld = new HashMap<>();
    // Seed-agnostic, bot-persistent quest continuity (non-power progression).
    private Map<String, BotQuestMemory> botQuestMemory = new HashMap<>();

    // === Survival recruitment mode ("find a village, then recruit") ===
    // When enabled, bots do not auto-spawn / restore until the world has been "recruited".
    private boolean survivalRecruitmentMode = false;
    // Per-world (level-name key) recruitment state.
    private Map<String, SurvivalRecruitmentState> survivalRecruitment = new HashMap<>();

    // === Soul communication (opt-in, local-Ollama-only conversational pilot) ===
    // Default-off and entirely separate from the legacy defaultLlmWorldEnabled toggle.
    // Non-secret fields only — no API keys belong here.
    private boolean soulsEnabled = false;
    private String soulProvider = "ollama";
    private String soulModel = "";
    private int soulRequestTimeoutSeconds = 60;
    private int soulQueueCapacity = 8;
    // Group scenes ("bots, ..." / "Jake and Sara, ...") kill switch. Default-on: it only takes
    // effect while the souls master above is on, so a souls-off install is unaffected.
    private boolean soulPartyEnabled = true;
    // Autonomous banter scenes. Default-OFF (explicit opt-in per the banter spec): companions
    // spending LLM/TTS time on their own initiative only happens after the user asks for it.
    private boolean soulBanterEnabled = false;
    // Ambient/local chat: bots overhearing unaddressed chat spoken near them. Default-OFF for the
    // same reason banter is — companions spending LLM/TTS time unprompted is opt-in. This toggle
    // also gates the overhear recorder's write, so off means nothing is recorded at all.
    private boolean soulLocalChatEnabled = false;

    // Soul generated-voice (TTS). Default-off; local engines only. See
    // docs/superpowers/specs/2026-08-24-soul-generated-voice-design.md.
    private boolean soulVoiceEnabled = false;
    // Engine: "piper" (CPU, simple) or "dreamsleeve" (the Qwen3-TTS voice-clone warm server
    // from ~/pontus/openmw-forge/dreamsleeve — same stack that voices Casca in OpenMW).
    private String soulVoiceEngine = "piper";
    private String soulVoicePiperBinary = "";
    private String soulVoiceModel = "";
    // Dreamsleeve engine: repo dir (holds scripts/tts_server.py), plus the voice anchor —
    // a reference clip + its transcript that Qwen3-TTS clones for Jake's voice.
    private String soulVoiceDreamsleeveDir = "";
    private String soulVoiceRefAudio = "";
    private String soulVoiceRefText = "";
    private int soulVoiceMaxChars = 400;
    private long soulVoiceSynthTimeoutMs = 8000L;
    private float soulVoiceRadioGain = 0.6f;

    /**
     * Private constructor to prevent direct instantiation.
     * Use the static load() method instead.
     */
    private ManualConfig() {
        // Initialize with default values
        this.selectedLanguageModel = System.getProperty("frens.llmModel", System.getProperty("aiplayer.llmModel", null));
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

    /** Lock object for save/load serialization — prevents concurrent writes corrupting the JSON. */
    private static final Object SAVE_LOCK = new Object();

    /**
     * Saves the current configuration to the settings.json5 file.
     */
    public void save() {
        synchronized (SAVE_LOCK) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(filePath())) {
                gson.toJson(this, writer);
            } catch (IOException e) {
                LOGGER.error("Failed to save config file: {}", e.getMessage());
            }
        }
    }

    /**
     * Loads the configuration from the settings.json5 file. If the file does not exist,
     * it creates and returns a new default configuration instance.
     *
     * @return A loaded ManualConfig instance, or a new one if the file is not found.
     */
    public static ManualConfig load() {
        File file = new File(filePath());
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
            String currentProvider = System.getProperty("frens.llmMode", System.getProperty("aiplayer.llmMode", "ollama"));
            loadedConfig.checkAndUpdateProvider(currentProvider);
            if (loadedConfig.botControls == null) {
                loadedConfig.botControls = new HashMap<>();
            }
            if (loadedConfig.botQuestMemory == null) {
                loadedConfig.botQuestMemory = new HashMap<>();
            }
            if (loadedConfig.botSkinSelections == null) {
                loadedConfig.botSkinSelections = new HashMap<>();
            }
            if (loadedConfig.survivalRecruitment == null) {
                loadedConfig.survivalRecruitment = new HashMap<>();
            }
            if (loadedConfig.botSpawnPointsByWorld == null) {
                loadedConfig.botSpawnPointsByWorld = new HashMap<>();
            }
            if (loadedConfig.botControlsByWorld == null) {
                loadedConfig.botControlsByWorld = new HashMap<>();
            }
            if (loadedConfig.mutedVoiceCategories == null) {
                loadedConfig.mutedVoiceCategories = new ArrayList<>();
            }
            if (loadedConfig.mutedTextCategories == null) {
                loadedConfig.mutedTextCategories = new ArrayList<>();
            }
            // ── Legacy migration: global → per-world ──
            // Old configs stored one BotSpawn per alias; migrate to per-world under "_legacy" key.
            // The first real access with a worldKey will resolve the _legacy entry.
            loadedConfig.migrateLegacyToPerWorld();
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
            System.setProperty("frens.llmModel", selectedLanguageModel);
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

    // ── Bot skin presets ──

    public Map<String, String> getBotSkins() {
        if (botSkins == null) {
            botSkins = new HashMap<>();
        }
        return botSkins;
    }

    public void setBotSkins(Map<String, String> botSkins) {
        this.botSkins = normalizeAliasMap(botSkins);
    }

    public Map<String, BotSkinSelection> getBotSkinSelections() {
        if (botSkinSelections == null) {
            botSkinSelections = new HashMap<>();
        }
        return botSkinSelections;
    }

    public void setBotSkinSelections(Map<String, BotSkinSelection> botSkinSelections) {
        this.botSkinSelections = normalizeAliasMap(botSkinSelections);
    }

    public BotSkinSelection getBotSkinSelection(String alias) {
        String key = resolveAliasKey(getBotSkinSelections(), alias);
        return key != null ? getBotSkinSelections().get(key) : null;
    }

    public void setBotSkinSelection(String alias, BotSkinSelection selection) {
        if (alias == null || alias.isBlank() || selection == null) {
            return;
        }
        putAliasValue(getBotSkinSelections(), alias, selection.sanitized());
        // Keep legacy preset map synchronized for backward compatibility.
        if (selection.hasPresetValue()) {
            putAliasValue(getBotSkins(), alias, selection.getValue());
        }
    }

    public String getBotSkin(String alias) {
        BotSkinSelection structured = getBotSkinSelection(alias);
        if (structured != null && structured.hasPresetValue()) {
            return structured.getValue();
        }
        String key = resolveAliasKey(getBotSkins(), alias);
        return key != null ? getBotSkins().get(key) : null;
    }

    public void setBotSkin(String alias, String presetId) {
        if (alias == null || alias.isBlank() || presetId == null || presetId.isBlank()) {
            return;
        }
        putAliasValue(getBotSkins(), alias, presetId.trim());
        setBotSkinSelection(alias, BotSkinSelection.preset(presetId.trim()));
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

    public boolean getPreserveExpensiveGear(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        if (playerPreserveExpensiveGear == null) {
            return false;
        }
        return playerPreserveExpensiveGear.getOrDefault(playerUuid.toString(), Boolean.FALSE);
    }

    public void setPreserveExpensiveGear(UUID playerUuid, boolean value) {
        if (playerUuid == null) {
            return;
        }
        if (playerPreserveExpensiveGear == null) {
            playerPreserveExpensiveGear = new HashMap<>();
        }
        playerPreserveExpensiveGear.put(playerUuid.toString(), value);
    }

    public boolean getAutoAcceptPreciousFoods(UUID playerUuid) {
        if (playerUuid == null || playerAutoAcceptPreciousFoods == null) {
            return false;
        }
        return playerAutoAcceptPreciousFoods.getOrDefault(playerUuid.toString(), Boolean.FALSE);
    }

    public void setAutoAcceptPreciousFoods(UUID playerUuid, boolean value) {
        if (playerUuid == null) {
            return;
        }
        if (playerAutoAcceptPreciousFoods == null) {
            playerAutoAcceptPreciousFoods = new HashMap<>();
        }
        playerAutoAcceptPreciousFoods.put(playerUuid.toString(), value);
    }

    /** @deprecated Legacy accessor. Use {@link #getBotSpawnPointsByWorld()} instead. */
    @Deprecated
    public Map<String, BotSpawn> getBotSpawnPoints() {
        if (botSpawnPoints == null) {
            botSpawnPoints = new HashMap<>();
        }
        return botSpawnPoints;
    }

    /** @deprecated Legacy setter. Use {@link #setBotSpawn(String, String, BotSpawn)} instead. */
    @Deprecated
    public void setBotSpawnPoints(Map<String, BotSpawn> botSpawnPoints) {
        this.botSpawnPoints = normalizeAliasMap(botSpawnPoints);
    }

    /** @deprecated Legacy setter. Use {@link #setBotSpawn(String, String, BotSpawn)} instead. */
    @Deprecated
    public void setBotSpawn(String alias, BotSpawn spawn) {
        if (alias == null || alias.isBlank() || spawn == null) {
            return;
        }
        putAliasValue(getBotSpawnPoints(), alias, spawn);
    }

    /** @deprecated Legacy getter. Use {@link #getBotSpawn(String, String)} instead. */
    @Deprecated
    public BotSpawn getBotSpawn(String alias) {
        String key = resolveAliasKey(getBotSpawnPoints(), alias);
        return key != null ? getBotSpawnPoints().get(key) : null;
    }

    // ── Per-world BotSpawn accessors ──

    public Map<String, Map<String, BotSpawn>> getBotSpawnPointsByWorld() {
        if (botSpawnPointsByWorld == null) {
            botSpawnPointsByWorld = new HashMap<>();
        }
        return botSpawnPointsByWorld;
    }

    /**
     * Returns the BotSpawn for the given alias in the given world.
     * If a legacy entry exists (from pre-per-world config) and no world-specific
     * entry is found, migrates the legacy entry to the provided worldKey.
     */
    public BotSpawn getBotSpawn(String alias, String worldKey) {
        if (alias == null || alias.isBlank()) return null;
        Map<String, BotSpawn> worldMap = resolveNestedAliasEntry(getBotSpawnPointsByWorld(), alias);
        if (worldMap == null) return null;
        if (worldKey != null && !worldKey.isBlank()) {
            BotSpawn spawn = worldMap.get(worldKey);
            if (spawn != null) return spawn;
            // Migrate legacy entry on first per-world access.
            BotSpawn legacy = worldMap.remove("_legacy");
            if (legacy != null) {
                worldMap.put(worldKey, legacy);
                return legacy;
            }
        }
        return null;
    }

    public void setBotSpawn(String alias, String worldKey, BotSpawn spawn) {
        if (alias == null || alias.isBlank() || worldKey == null || worldKey.isBlank() || spawn == null) {
            return;
        }
        String key = resolveAliasKey(getBotSpawnPointsByWorld(), alias);
        if (key == null) key = alias.trim();
        Map<String, BotSpawn> worldMap = getBotSpawnPointsByWorld().computeIfAbsent(key, ignored -> new HashMap<>());
        worldMap.put(worldKey, spawn);
        // Clear legacy entry if present since we now have a real worldKey.
        worldMap.remove("_legacy");
    }

    public void clearBotSpawn(String alias, String worldKey) {
        if (alias == null || alias.isBlank() || worldKey == null || worldKey.isBlank()) return;
        Map<String, BotSpawn> worldMap = resolveNestedAliasEntry(getBotSpawnPointsByWorld(), alias);
        if (worldMap != null) {
            worldMap.remove(worldKey);
        }
    }

    // ── Per-world BotControlSettings accessors ──

    public Map<String, Map<String, BotControlSettings>> getBotControlsByWorld() {
        if (botControlsByWorld == null) {
            botControlsByWorld = new HashMap<>();
        }
        return botControlsByWorld;
    }

    /**
     * Returns or creates per-world BotControlSettings for the given alias and worldKey.
     * If a legacy entry exists, migrates it to the provided worldKey.
     */
    public BotControlSettings getOrCreateBotControl(String alias, String worldKey) {
        if (alias == null || alias.isBlank()) alias = "default";
        if (worldKey != null && !worldKey.isBlank()) {
            String key = resolveAliasKey(getBotControlsByWorld(), alias);
            if (key == null) key = alias.trim();
            Map<String, BotControlSettings> worldMap = getBotControlsByWorld()
                    .computeIfAbsent(key, ignored -> new HashMap<>());
            BotControlSettings settings = worldMap.get(worldKey);
            if (settings != null) return settings;
            // Migrate legacy entry.
            BotControlSettings legacy = worldMap.remove("_legacy");
            if (legacy != null) {
                worldMap.put(worldKey, legacy);
                return legacy;
            }
            // Create new entry for this world.
            BotControlSettings created = new BotControlSettings();
            worldMap.put(worldKey, created);
            return created;
        }
        // No worldKey — fall back to old global accessor.
        return getOrCreateBotControl(alias);
    }

    /**
     * Returns the per-world effective bot control (specific alias, then "default" alias).
     */
    public BotControlSettings getEffectiveBotControl(String alias, String worldKey) {
        if (alias != null && worldKey != null && !worldKey.isBlank()) {
            Map<String, BotControlSettings> worldMap = resolveNestedAliasEntry(getBotControlsByWorld(), alias);
            if (worldMap != null) {
                BotControlSettings specific = worldMap.get(worldKey);
                if (specific != null) return specific;
                // Check legacy
                BotControlSettings legacy = worldMap.remove("_legacy");
                if (legacy != null) {
                    worldMap.put(worldKey, legacy);
                    return legacy;
                }
            }
        }
        // Fall back to "default" alias
        if (worldKey != null && !worldKey.isBlank()) {
            Map<String, BotControlSettings> defaultWorld = resolveNestedAliasEntry(getBotControlsByWorld(), "default");
            if (defaultWorld != null) {
                BotControlSettings d = defaultWorld.get(worldKey);
                if (d != null) return d;
            }
        }
        // Final fallback: old global lookup.
        return getEffectiveBotControl(alias);
    }

    /**
     * Returns the set of all bot aliases that have per-world spawn or control entries.
     * Useful for GUI alias lists and autocomplete where no world context is needed.
     */
    public java.util.Set<String> getAllBotAliases() {
        java.util.Set<String> aliases = new java.util.LinkedHashSet<>();
        // From per-world maps
        if (botSpawnPointsByWorld != null) aliases.addAll(botSpawnPointsByWorld.keySet());
        if (botControlsByWorld != null) aliases.addAll(botControlsByWorld.keySet());
        // From legacy maps (in case migration hasn't happened yet)
        if (botSpawnPoints != null) aliases.addAll(botSpawnPoints.keySet());
        if (botControls != null) aliases.addAll(botControls.keySet());
        // From identity maps
        if (botGameProfile != null) aliases.addAll(botGameProfile.keySet());
        aliases.remove("default");
        return aliases;
    }

    public boolean isDefaultLlmWorldEnabled() {
        return defaultLlmWorldEnabled;
    }

    public void setDefaultLlmWorldEnabled(boolean defaultLlmWorldEnabled) {
        this.defaultLlmWorldEnabled = defaultLlmWorldEnabled;
    }

    public boolean isTextDialogueEnabled() {
        return textDialogueEnabled;
    }

    public void setTextDialogueEnabled(boolean textDialogueEnabled) {
        this.textDialogueEnabled = textDialogueEnabled;
    }

    public boolean isVoicedDialogueEnabled() {
        return voicedDialogueEnabled;
    }

    public void setVoicedDialogueEnabled(boolean voicedDialogueEnabled) {
        this.voicedDialogueEnabled = voicedDialogueEnabled;
    }

    public boolean isVoiceCategoryMuted(String categoryId) {
        return categoryId != null
                && mutedVoiceCategories != null
                && mutedVoiceCategories.contains(categoryId);
    }

    public void setVoiceCategoryMuted(String categoryId, boolean muted) {
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }
        if (mutedVoiceCategories == null) {
            mutedVoiceCategories = new ArrayList<>();
        }
        if (muted) {
            if (!mutedVoiceCategories.contains(categoryId)) {
                mutedVoiceCategories.add(categoryId);
            }
        } else {
            mutedVoiceCategories.remove(categoryId);
        }
    }

    public List<String> getMutedVoiceCategories() {
        if (mutedVoiceCategories == null) {
            mutedVoiceCategories = new ArrayList<>();
        }
        return mutedVoiceCategories;
    }

    public boolean isTextCategoryMuted(String categoryId) {
        return categoryId != null
                && mutedTextCategories != null
                && mutedTextCategories.contains(categoryId);
    }

    public void setTextCategoryMuted(String categoryId, boolean muted) {
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }
        if (mutedTextCategories == null) {
            mutedTextCategories = new ArrayList<>();
        }
        if (muted) {
            if (!mutedTextCategories.contains(categoryId)) {
                mutedTextCategories.add(categoryId);
            }
        } else {
            mutedTextCategories.remove(categoryId);
        }
    }

    public boolean isGameplayTipsEnabled() {
        return gameplayTipsEnabled;
    }

    public void setGameplayTipsEnabled(boolean gameplayTipsEnabled) {
        this.gameplayTipsEnabled = gameplayTipsEnabled;
    }

    public boolean isIdleHobbiesAnywhereEnabled() {
        return idleHobbiesAnywhereEnabled;
    }

    public void setIdleHobbiesAnywhereEnabled(boolean idleHobbiesAnywhereEnabled) {
        this.idleHobbiesAnywhereEnabled = idleHobbiesAnywhereEnabled;
    }

    public boolean isBaritonePathfinderEnabled() { return baritonePathfinderEnabled; }
    public void setBaritonePathfinderEnabled(boolean v) { this.baritonePathfinderEnabled = v; }

    public boolean isFortifyForcePlaceEnabled() { return fortifyForcePlaceEnabled; }
    public void setFortifyForcePlaceEnabled(boolean v) { this.fortifyForcePlaceEnabled = v; }

    /** Returns the global teleport override, or null if per-bot settings should apply. */
    public Boolean getGlobalTeleportDuringSkills() { return globalTeleportDuringSkills; }
    public void setGlobalTeleportDuringSkills(Boolean v) { this.globalTeleportDuringSkills = v; }

    public int getFortBufferRadius() { return fortBufferRadius > 0 ? fortBufferRadius : 8; }
    public void setFortBufferRadius(int r) { this.fortBufferRadius = r; }

    public int getUndergroundLingerMinutes() { return undergroundLingerMinutes > 0 ? undergroundLingerMinutes : 3; }
    public void setUndergroundLingerMinutes(int m) { this.undergroundLingerMinutes = m; }

    public int getUndergroundProximityBlocks() { return undergroundProximityBlocks > 0 ? undergroundProximityBlocks : 32; }
    public void setUndergroundProximityBlocks(int b) { this.undergroundProximityBlocks = b; }

    // === Soul communication accessors ===
    // Default-off, non-secret, local-Ollama-only. Kept separate from defaultLlmWorldEnabled.

    public boolean isSoulsEnabled() { return soulsEnabled; }
    public void setSoulsEnabled(boolean soulsEnabled) { this.soulsEnabled = soulsEnabled; }

    public boolean isSoulPartyEnabled() { return soulPartyEnabled; }
    public void setSoulPartyEnabled(boolean v) { this.soulPartyEnabled = v; }

    public boolean isSoulBanterEnabled() { return soulBanterEnabled; }
    public void setSoulBanterEnabled(boolean v) { this.soulBanterEnabled = v; }

    public boolean isSoulLocalChatEnabled() { return soulLocalChatEnabled; }
    public void setSoulLocalChatEnabled(boolean v) { this.soulLocalChatEnabled = v; }

    public String getSoulProvider() {
        return (soulProvider == null || soulProvider.isBlank()) ? "ollama" : soulProvider;
    }

    public void setSoulProvider(String soulProvider) {
        this.soulProvider = (soulProvider == null || soulProvider.isBlank())
                ? "ollama" : soulProvider.trim();
    }

    public String getSoulModel() {
        return soulModel == null ? "" : soulModel;
    }

    public void setSoulModel(String soulModel) {
        this.soulModel = soulModel == null ? "" : soulModel.trim();
    }

    public int getSoulRequestTimeoutSeconds() {
        return Math.max(10, Math.min(180, soulRequestTimeoutSeconds));
    }

    public void setSoulRequestTimeoutSeconds(int soulRequestTimeoutSeconds) {
        this.soulRequestTimeoutSeconds = Math.max(10, Math.min(180, soulRequestTimeoutSeconds));
    }

    public int getSoulQueueCapacity() {
        return Math.max(1, Math.min(32, soulQueueCapacity));
    }

    public void setSoulQueueCapacity(int soulQueueCapacity) {
        this.soulQueueCapacity = Math.max(1, Math.min(32, soulQueueCapacity));
    }

    // === Soul generated-voice accessors ===

    public boolean isSoulVoiceEnabled() { return soulVoiceEnabled; }
    public void setSoulVoiceEnabled(boolean v) { this.soulVoiceEnabled = v; }

    public String getSoulVoiceEngine() {
        return (soulVoiceEngine == null || soulVoiceEngine.isBlank()) ? "piper" : soulVoiceEngine.trim();
    }
    public void setSoulVoiceEngine(String v) { this.soulVoiceEngine = v == null ? "piper" : v.trim(); }

    public String getSoulVoiceDreamsleeveDir() { return soulVoiceDreamsleeveDir == null ? "" : soulVoiceDreamsleeveDir; }
    public void setSoulVoiceDreamsleeveDir(String v) { this.soulVoiceDreamsleeveDir = v == null ? "" : v.trim(); }

    public String getSoulVoiceRefAudio() { return soulVoiceRefAudio == null ? "" : soulVoiceRefAudio; }
    public void setSoulVoiceRefAudio(String v) { this.soulVoiceRefAudio = v == null ? "" : v.trim(); }

    public String getSoulVoiceRefText() { return soulVoiceRefText == null ? "" : soulVoiceRefText; }
    public void setSoulVoiceRefText(String v) { this.soulVoiceRefText = v == null ? "" : v.trim(); }

    public String getSoulVoicePiperBinary() { return soulVoicePiperBinary == null ? "" : soulVoicePiperBinary; }
    public void setSoulVoicePiperBinary(String v) { this.soulVoicePiperBinary = v == null ? "" : v.trim(); }

    public String getSoulVoiceModel() { return soulVoiceModel == null ? "" : soulVoiceModel; }
    public void setSoulVoiceModel(String v) { this.soulVoiceModel = v == null ? "" : v.trim(); }

    public int getSoulVoiceMaxChars() { return Math.max(40, Math.min(1000, soulVoiceMaxChars)); }
    public void setSoulVoiceMaxChars(int v) { this.soulVoiceMaxChars = v; }

    public long getSoulVoiceSynthTimeoutMs() { return Math.max(1000L, Math.min(30_000L, soulVoiceSynthTimeoutMs)); }
    public void setSoulVoiceSynthTimeoutMs(long v) { this.soulVoiceSynthTimeoutMs = v; }

    public float getSoulVoiceRadioGain() { return Math.max(0.0f, Math.min(1.0f, soulVoiceRadioGain)); }
    public void setSoulVoiceRadioGain(float v) { this.soulVoiceRadioGain = v; }

    /** @deprecated Legacy accessor. Use {@link #getBotControlsByWorld()} or {@link #getAllBotAliases()} instead. */
    @Deprecated
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

    /** @deprecated Legacy setter. Use per-world methods instead. */
    @Deprecated
    public void setBotControls(Map<String, BotControlSettings> botControls) {
        this.botControls = normalizeAliasMap(botControls);
    }

    /** @deprecated Legacy global accessor. Use {@link #getOrCreateBotControl(String, String)} with worldKey. */
    @Deprecated
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

    /** @deprecated Legacy global accessor. Use {@link #getEffectiveBotControl(String, String)} with worldKey. */
    @Deprecated
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
        removeAliasVariants(botSkins, alias);
        removeAliasVariants(botSkinSelections, alias);
        removeNestedAliasVariants(botSpawnPointsByWorld, alias);
        removeNestedAliasVariants(botControlsByWorld, alias);
    }

    private void normalizeAliasBackedMaps() {
        botGameProfile = normalizeAliasMap(botGameProfile);
        botOwnership = normalizeAliasMap(botOwnership);
        botSpawnPoints = normalizeAliasMap(botSpawnPoints);
        botControls = normalizeAliasMap(botControls);
        botQuestMemory = normalizeAliasMap(botQuestMemory);
        botSkins = normalizeAliasMap(botSkins);
        botSkinSelections = normalizeAliasMap(botSkinSelections);

        // Legacy migration: seed structured skin selections from old preset-only map.
        if (botSkinSelections.isEmpty() && !botSkins.isEmpty()) {
            for (Map.Entry<String, String> entry : botSkins.entrySet()) {
                String alias = entry.getKey();
                String preset = entry.getValue();
                if (alias == null || alias.isBlank() || preset == null || preset.isBlank()) {
                    continue;
                }
                botSkinSelections.put(alias, BotSkinSelection.preset(preset));
            }
        }

        // Keep legacy map populated for preset selections to avoid breaking old callers.
        for (Map.Entry<String, BotSkinSelection> entry : botSkinSelections.entrySet()) {
            String alias = entry.getKey();
            BotSkinSelection selection = entry.getValue();
            if (alias == null || alias.isBlank() || selection == null || !selection.hasPresetValue()) {
                continue;
            }
            botSkins.putIfAbsent(alias, selection.getValue());
        }
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

    // ── Nested (per-world) map helpers ──

    /**
     * Resolve an alias key in a nested map (alias → worldKey → T).
     * Returns the inner map for the alias, or null.
     */
    private static <T> Map<String, T> resolveNestedAliasEntry(Map<String, Map<String, T>> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) return null;
        String key = resolveAliasKey(map, alias);
        return key != null ? map.get(key) : null;
    }

    private static <T> void removeNestedAliasVariants(Map<String, Map<String, T>> map, String alias) {
        if (map == null || map.isEmpty() || alias == null || alias.isBlank()) return;
        String normalized = normalizeAlias(alias);
        Iterator<String> it = map.keySet().iterator();
        while (it.hasNext()) {
            if (normalizeAlias(it.next()).equals(normalized)) {
                it.remove();
            }
        }
    }

    /**
     * Migrates legacy global botSpawnPoints and botControls into per-world maps.
     * Legacy entries are stored under the "_legacy" worldKey and resolved on first
     * per-world access (see getBotSpawn/getOrCreateBotControl).
     */
    private void migrateLegacyToPerWorld() {
        // Migrate botSpawnPoints → botSpawnPointsByWorld
        if (botSpawnPoints != null && !botSpawnPoints.isEmpty()) {
            if (botSpawnPointsByWorld == null) botSpawnPointsByWorld = new HashMap<>();
            for (Map.Entry<String, BotSpawn> entry : botSpawnPoints.entrySet()) {
                String alias = entry.getKey();
                BotSpawn spawn = entry.getValue();
                if (alias == null || alias.isBlank() || spawn == null) continue;
                // Only migrate if no per-world entry exists for this alias yet.
                Map<String, BotSpawn> existing = resolveNestedAliasEntry(botSpawnPointsByWorld, alias);
                if (existing == null || existing.isEmpty()) {
                    Map<String, BotSpawn> worldMap = new HashMap<>();
                    // If the BotSpawn has a levelName, use it as the worldKey hint;
                    // otherwise store under _legacy for deferred resolution.
                    String worldKey = (spawn.levelName() != null && !spawn.levelName().isBlank())
                            ? spawn.levelName()
                            : "_legacy";
                    worldMap.put(worldKey, spawn);
                    botSpawnPointsByWorld.put(alias.trim(), worldMap);
                }
            }
            botSpawnPoints = new HashMap<>(); // clear legacy
            LOGGER.info("[Frens] Migrated {} legacy BotSpawn entries to per-world format.", botSpawnPointsByWorld.size());
        }

        // Migrate botControls → botControlsByWorld
        if (botControls != null && !botControls.isEmpty()) {
            if (botControlsByWorld == null) botControlsByWorld = new HashMap<>();
            for (Map.Entry<String, BotControlSettings> entry : botControls.entrySet()) {
                String alias = entry.getKey();
                BotControlSettings settings = entry.getValue();
                if (alias == null || alias.isBlank() || settings == null) continue;
                Map<String, BotControlSettings> existing = resolveNestedAliasEntry(botControlsByWorld, alias);
                if (existing == null || existing.isEmpty()) {
                    Map<String, BotControlSettings> worldMap = new HashMap<>();
                    worldMap.put("_legacy", settings);
                    botControlsByWorld.put(alias.trim(), worldMap);
                }
            }
            botControls = new HashMap<>(); // clear legacy
            LOGGER.info("[Frens] Migrated {} legacy BotControlSettings entries to per-world format.", botControlsByWorld.size());
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

    public static class BotSkinSelection {
        private String source = "preset";
        private String value = "steve";
        private String lastSetByUuid;
        private String lastSetByName;
        private boolean lastSetByAdmin;
        private long lastSetAtEpochMs;

        public BotSkinSelection() {
        }

        public BotSkinSelection(String source,
                                String value,
                                String lastSetByUuid,
                                String lastSetByName,
                                boolean lastSetByAdmin,
                                long lastSetAtEpochMs) {
            this.source = source;
            this.value = value;
            this.lastSetByUuid = lastSetByUuid;
            this.lastSetByName = lastSetByName;
            this.lastSetByAdmin = lastSetByAdmin;
            this.lastSetAtEpochMs = lastSetAtEpochMs;
        }

        public static BotSkinSelection preset(String presetId) {
            return new BotSkinSelection("preset", presetId, null, null, false, System.currentTimeMillis());
        }

        public BotSkinSelection sanitized() {
            String normalizedSource = (source == null || source.isBlank())
                    ? "preset"
                    : source.trim().toLowerCase(Locale.ROOT);
            if (!normalizedSource.equals("preset") && !normalizedSource.equals("custom_url")) {
                normalizedSource = "preset";
            }
            String normalizedValue = (value == null || value.isBlank()) ? "steve" : value.trim();
            return new BotSkinSelection(
                    normalizedSource,
                    normalizedValue,
                    (lastSetByUuid == null || lastSetByUuid.isBlank()) ? null : lastSetByUuid.trim(),
                    (lastSetByName == null || lastSetByName.isBlank()) ? null : lastSetByName.trim(),
                    lastSetByAdmin,
                    Math.max(0L, lastSetAtEpochMs)
            );
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getLastSetByUuid() {
            return lastSetByUuid;
        }

        public void setLastSetByUuid(String lastSetByUuid) {
            this.lastSetByUuid = lastSetByUuid;
        }

        public String getLastSetByName() {
            return lastSetByName;
        }

        public void setLastSetByName(String lastSetByName) {
            this.lastSetByName = lastSetByName;
        }

        public boolean isLastSetByAdmin() {
            return lastSetByAdmin;
        }

        public void setLastSetByAdmin(boolean lastSetByAdmin) {
            this.lastSetByAdmin = lastSetByAdmin;
        }

        public long getLastSetAtEpochMs() {
            return lastSetAtEpochMs;
        }

        public void setLastSetAtEpochMs(long lastSetAtEpochMs) {
            this.lastSetAtEpochMs = lastSetAtEpochMs;
        }

        public boolean hasPresetValue() {
            return "preset".equalsIgnoreCase(source)
                    && value != null
                    && !value.isBlank();
        }

        public boolean isCustomUrl() {
            return "custom_url".equalsIgnoreCase(source);
        }
    }

    public static class BotSpawn {
        private String levelName;
        private String dimension;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        public BotSpawn() {
        }

        public BotSpawn(String levelName, String dimension, double x, double y, double z, float yaw, float pitch) {
            this.levelName = levelName;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public String levelName() {
            return levelName;
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
        /** @deprecated Legacy field kept for JSON backward compat; no longer drives any behavior. */
        @Deprecated @SuppressWarnings("unused")
        private boolean autoSpawn;
        private Boolean autoRespawnOnDeath;  // null = use mode-based default
        private String spawnMode = "admin";
        private String gameMode = "survival";
        private boolean teleportDuringSkills = false;
        private boolean followTeleport = false;
        private boolean pauseOnFullInventory;
        private boolean teleportDuringDropSweep = false;
        private boolean llmEnabled = false;
        private boolean voicedDialogue = true;
        private String failsafeSpawnMode = "world_spawn";  // owner_bed | world_spawn | saved_base
        private boolean autoRegroupOnLost = false;  // auto-regroup when bot loses contact at a drop-off
        private boolean autoSpawnOnLoad = true;  // shelved when false

        /** @deprecated Use {@link #isAutoRespawnOnDeath()} instead. Kept for JSON compat. */
        @Deprecated
        public boolean isAutoSpawn() {
            return autoSpawn;
        }

        /** @deprecated No-op. Kept for JSON compat. */
        @Deprecated
        public void setAutoSpawn(boolean autoSpawn) {
            this.autoSpawn = autoSpawn;
        }

        /**
         * Whether this bot should automatically respawn on death (skip resurrection ritual).
         * If the explicit value has not been set, derives a default from the spawn mode:
         * admin/training → true, questing/play → false.
         */
        public boolean isAutoRespawnOnDeath() {
            if (autoRespawnOnDeath != null) {
                return autoRespawnOnDeath;
            }
            // Mode-based default: admin/training bots auto-respawn; questing/play bots require ritual.
            String mode = getSpawnMode();
            return !"play".equalsIgnoreCase(mode);
        }

        public void setAutoRespawnOnDeath(Boolean autoRespawnOnDeath) {
            this.autoRespawnOnDeath = autoRespawnOnDeath;
        }

        public Boolean getRawAutoRespawnOnDeath() {
            return autoRespawnOnDeath;
        }

        public String getSpawnMode() {
            return (spawnMode == null || spawnMode.isBlank()) ? "admin" : spawnMode;
        }

        public void setSpawnMode(String spawnMode) {
            if (spawnMode == null) {
                this.spawnMode = "admin";
                return;
            }
            String normalized = spawnMode.trim().toLowerCase();
            // Preserve the actual mode (admin, questing, training, play).
            // Legacy 'play' is kept as-is for backward compat.
            switch (normalized) {
                case "admin", "questing", "training", "play" -> this.spawnMode = normalized;
                default -> this.spawnMode = "admin";
            }
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

        public boolean isFollowTeleport() { return followTeleport; }
        public void setFollowTeleport(boolean v) { this.followTeleport = v; }

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

        /**
         * Where the bot should spawn when all higher-priority checkpoints
         * (bed, recruitment anchor, BotSpawn config) fail.
         *
         * @return one of {@code "owner_bed"}, {@code "world_spawn"}, or {@code "saved_base"}.
         */
        public String getFailsafeSpawnMode() {
            if (failsafeSpawnMode == null || failsafeSpawnMode.isBlank()) {
                return "world_spawn";
            }
            String normalized = failsafeSpawnMode.trim().toLowerCase();
            return switch (normalized) {
                case "owner_bed", "saved_base" -> normalized;
                default -> "world_spawn";
            };
        }

        public void setFailsafeSpawnMode(String failsafeSpawnMode) {
            this.failsafeSpawnMode = failsafeSpawnMode;
        }

        public boolean isAutoRegroupOnLost() {
            return autoRegroupOnLost;
        }

        public void setAutoRegroupOnLost(boolean autoRegroupOnLost) {
            this.autoRegroupOnLost = autoRegroupOnLost;
        }

        /**
         * Whether this bot should automatically spawn when the world loads.
         * When {@code false}, the bot is "shelved" and must be manually spawned
         * with {@code /bot spawn}.  Defaults to {@code true}.
         */
        public boolean isAutoSpawnOnLoad() {
            return autoSpawnOnLoad;
        }

        public void setAutoSpawnOnLoad(boolean autoSpawnOnLoad) {
            this.autoSpawnOnLoad = autoSpawnOnLoad;
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
        // Skin policy controls (server-authoritative).
        private boolean allowEveryoneSkinChange;
        private boolean allowCustomSkins;
        private boolean autonomousRescuesEnabled;
        // Granular admin-tab permissions (server-authoritative).
        // Global defaults applied to all non-operators.
        private Map<String, Boolean> adminPermissionDefaultsByKey;
        // Per-user overrides: uuid -> (permissionKey -> allowed)
        private Map<String, Map<String, Boolean>> adminPermissionOverridesByUserUuid;
        // Optional delegated players (uuid -> last known name) that may choose world mode.
        // Operators are always allowed regardless of this map.
        private Map<String, String> modeSelectionDelegatesByUuid;

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

        public boolean isAllowEveryoneSkinChange() {
            return allowEveryoneSkinChange;
        }

        public void setAllowEveryoneSkinChange(boolean allowEveryoneSkinChange) {
            this.allowEveryoneSkinChange = allowEveryoneSkinChange;
        }

        public boolean isAllowCustomSkins() {
            return allowCustomSkins;
        }

        public void setAllowCustomSkins(boolean allowCustomSkins) {
            this.allowCustomSkins = allowCustomSkins;
        }

        public boolean isAutonomousRescuesEnabled() {
            return autonomousRescuesEnabled;
        }

        public void setAutonomousRescuesEnabled(boolean autonomousRescuesEnabled) {
            this.autonomousRescuesEnabled = autonomousRescuesEnabled;
        }

        public Map<String, Boolean> getAdminPermissionDefaultsByKey() {
            if (adminPermissionDefaultsByKey == null) {
                adminPermissionDefaultsByKey = new HashMap<>();
            }
            return adminPermissionDefaultsByKey;
        }

        public Map<String, Map<String, Boolean>> getAdminPermissionOverridesByUserUuid() {
            if (adminPermissionOverridesByUserUuid == null) {
                adminPermissionOverridesByUserUuid = new HashMap<>();
            }
            return adminPermissionOverridesByUserUuid;
        }

        public boolean getAdminPermissionDefault(String permissionKey, boolean fallbackDefault) {
            String key = normalizePermissionKey(permissionKey);
            if (key.isBlank()) {
                return fallbackDefault;
            }
            Boolean stored = getAdminPermissionDefaultsByKey().get(key);
            return stored != null ? stored : fallbackDefault;
        }

        public void setAdminPermissionDefault(String permissionKey, boolean allowed) {
            String key = normalizePermissionKey(permissionKey);
            if (key.isBlank()) {
                return;
            }
            getAdminPermissionDefaultsByKey().put(key, allowed);
        }

        public Boolean getAdminPermissionUserOverride(String uuid, String permissionKey) {
            String userKey = normalizeUuid(uuid);
            String permKey = normalizePermissionKey(permissionKey);
            if (userKey.isBlank() || permKey.isBlank()) {
                return null;
            }
            Map<String, Boolean> userMap = getAdminPermissionOverridesByUserUuid().get(userKey);
            if (userMap == null || userMap.isEmpty()) {
                return null;
            }
            return userMap.get(permKey);
        }

        public void setAdminPermissionUserOverride(String uuid, String permissionKey, boolean allowed) {
            String userKey = normalizeUuid(uuid);
            String permKey = normalizePermissionKey(permissionKey);
            if (userKey.isBlank() || permKey.isBlank()) {
                return;
            }
            Map<String, Boolean> userMap = getAdminPermissionOverridesByUserUuid()
                    .computeIfAbsent(userKey, ignored -> new HashMap<>());
            userMap.put(permKey, allowed);
        }

        public void clearAdminPermissionUserOverride(String uuid, String permissionKey) {
            String userKey = normalizeUuid(uuid);
            String permKey = normalizePermissionKey(permissionKey);
            if (userKey.isBlank() || permKey.isBlank()) {
                return;
            }
            Map<String, Boolean> userMap = getAdminPermissionOverridesByUserUuid().get(userKey);
            if (userMap == null) {
                return;
            }
            userMap.remove(permKey);
            if (userMap.isEmpty()) {
                getAdminPermissionOverridesByUserUuid().remove(userKey);
            }
        }

        public void clearAdminPermissionUserOverrides(String uuid) {
            String userKey = normalizeUuid(uuid);
            if (userKey.isBlank()) {
                return;
            }
            getAdminPermissionOverridesByUserUuid().remove(userKey);
        }

        public Map<String, String> getModeSelectionDelegatesByUuid() {
            if (modeSelectionDelegatesByUuid == null) {
                modeSelectionDelegatesByUuid = new HashMap<>();
            }
            return modeSelectionDelegatesByUuid;
        }

        public boolean canDelegateChooseWorldMode(String uuid) {
            if (uuid == null || uuid.isBlank()) {
                return false;
            }
            return getModeSelectionDelegatesByUuid().containsKey(normalizeUuid(uuid));
        }

        public void setDelegateWorldModeChoice(String uuid, String name, boolean allowed) {
            if (uuid == null || uuid.isBlank()) {
                return;
            }
            String key = normalizeUuid(uuid);
            if (!allowed) {
                getModeSelectionDelegatesByUuid().remove(key);
                return;
            }
            String displayName = (name == null || name.isBlank()) ? key : name.trim();
            getModeSelectionDelegatesByUuid().put(key, displayName);
        }

        public void clearWorldModeDelegates() {
            getModeSelectionDelegatesByUuid().clear();
        }

        private static String normalizeUuid(String uuid) {
            return uuid == null ? "" : uuid.trim().toLowerCase(Locale.ROOT);
        }

        private static String normalizePermissionKey(String key) {
            return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
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
