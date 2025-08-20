package net.shasankp000.FilingSystem;

import io.wispforest.owo.config.annotation.Config;
import net.shasankp000.AIPlayer;
import net.shasankp000.Exception.ollamaNotReachableException;


import net.shasankp000.ServiceLLMClients.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Config(name = "settings", wrapperName = "AIPlayerConfig", saveOnModification = true)
public class AIPlayerConfigModel {

    public List<String> modelList = new ArrayList<>();
    public String selectedLanguageModel;
    public String selectedModel;

    public static String llmMode = System.getProperty("aiplayer.llmMode", "ollama"); // default to Ollama

    public String openAIKey = "";
    public String claudeKey = "";
    public String geminiKey = "";
    public String grokKey = "";

    public static final Logger LOGGER = LoggerFactory.getLogger("AIPlayerConfigModel");

    public Map<String, String> BotGameProfile = new HashMap<>();

    {
        updateModels();
    }

    public void updateModels() {
        // Run the network operation on a separate thread to prevent freezing.
        CompletableFuture.runAsync(() -> {
            List<String> fetchedModels = new ArrayList<>();
            ModelFetcher modelFetcher = null;

            String apiKey = "";

            switch (llmMode) {
                case "ollama":
                    try {
                        fetchedModels = getLanguageModels.get();
                    } catch (ollamaNotReachableException e) {
                        LOGGER.error("Ollama is not reachable: {}", e.getMessage());
                        fetchedModels.add("Ollama is not reachable!");
                    }
                    break;
                case "openai":
                    modelFetcher = new OpenAIModelFetcher();
                    apiKey = AIPlayer.CONFIG.openAIKey();
                    break;
                case "claude":
                    modelFetcher = new ClaudeModelFetcher();
                    apiKey = AIPlayer.CONFIG.claudeKey();
                    break;
                case "gemini":
                    modelFetcher = new GeminiModelFetcher();
                    apiKey = AIPlayer.CONFIG.geminiKey();
                    break;
                case "grok":
                    modelFetcher = new GrokModelFetcher();
                    apiKey = AIPlayer.CONFIG.grokKey();
                    break;
                default:
                    LOGGER.error("Unsupported provider: {}", llmMode);
                    return;
            }

            if (modelFetcher != null) {
                fetchedModels = modelFetcher.fetchModels(apiKey);
            }

            this.modelList = fetchedModels;
        });
    }


    // Getters and setters
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

    // Existing getters/setters for selectedLanguageModel and BotGameProfile
    public String getSelectedLanguageModel() {
        return selectedLanguageModel;
    }

    public void setSelectedLanguageModel(String selectedLanguageModel) {
        selectedModel = selectedLanguageModel;
    }

    public Map<String, String> getBotGameProfile() {
        return BotGameProfile;
    }

    public void setBotGameProfile(HashMap<String, String> botGameProfile) {
        BotGameProfile = botGameProfile;
    }

    public String getLlmMode() {
        return llmMode;
    }
}




