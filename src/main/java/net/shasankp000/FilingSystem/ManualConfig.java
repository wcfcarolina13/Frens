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
    private Map<String, String> botGameProfile = new HashMap<>();

    /**
     * Private constructor to prevent direct instantiation.
     * Use the static load() method instead.
     */
    private ManualConfig() {
        // Initialize with default values
        this.selectedLanguageModel = null;
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
                    LOGGER.info("Using Gemini");
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

            if (modelFetcher != null) {
                if(apiKey.isEmpty()) {
                    // in the event that a user removes their api key but still have a service based provider set.
                    fetchedModels = new ArrayList<>();
                    selectedLanguageModel="No models available. Please enter an API key";
                }
                else {
                    fetchedModels = modelFetcher.fetchModels(apiKey);
                    if (selectedLanguageModel.equals("No models available. Please enter an API key")) {
                        selectedLanguageModel="";
                    }
                }

            }

            this.modelList = fetchedModels;
            LOGGER.info("modelList: {}", this.modelList);
            this.save();
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
        if (file.getParentFile() != null) {
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
            loadedConfig.updateModels();
            return loadedConfig;
        } catch (IOException e) {
            LOGGER.error("Failed to load config file. Using default config.", e);
            return new ManualConfig();
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
}
