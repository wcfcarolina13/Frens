package net.shasankp000.FilingSystem;

import net.shasankp000.AIPlayer;
import net.shasankp000.ServiceLLMClients.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class LLMClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("llm-client-factory");

    public static LLMClient createClient(String mode) {
        if (AIPlayer.CONFIG == null) {
            LOGGER.error("AIPlayer config not initialized; cannot create LLM client.");
            return null;
        }

        String normalized = (mode == null || mode.isBlank())
                ? System.getProperty("aiplayer.llmMode", "ollama")
                : mode.trim();
        normalized = normalized.toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "openai", "gpt" -> {
                if (AIPlayer.CONFIG.getOpenAIKey().isEmpty()) {
                    LOGGER.error("OpenAI API key not set in config!");
                    yield null;
                }
                yield new OpenAIClient(
                    AIPlayer.CONFIG.getOpenAIKey(),
                    AIPlayer.CONFIG.getSelectedLanguageModel()
                );
            }
            case "anthropic", "claude" -> {
                if (AIPlayer.CONFIG.getClaudeKey().isEmpty()) {
                    LOGGER.error("Claude API key not set in config!");
                    yield null;
                }
                yield new AnthropicClient(
                    AIPlayer.CONFIG.getClaudeKey(),
                    AIPlayer.CONFIG.getSelectedLanguageModel()
                );
            }
            case "google", "gemini" -> {
                if (AIPlayer.CONFIG.getGeminiKey().isEmpty()) {
                    LOGGER.error("Gemini API key not set in config!");
                    yield null;
                }
                yield new GeminiClient(
                    AIPlayer.CONFIG.getGeminiKey(),
                    AIPlayer.CONFIG.getSelectedLanguageModel()
                );
            }
            case "xAI", "xai", "grok" -> {
                if (AIPlayer.CONFIG.getGrokKey().isEmpty()) {
                    LOGGER.error("Grok API key not set in config!");
                    yield null;
                }
                yield new GrokClient(
                    AIPlayer.CONFIG.getGrokKey(),
                    AIPlayer.CONFIG.getSelectedLanguageModel()
                );
            }
            case "custom" -> {
                if (AIPlayer.CONFIG.getCustomApiKey().isEmpty()) {
                    LOGGER.error("Custom API key not set in config!");
                    yield null;
                }
                if (AIPlayer.CONFIG.getCustomApiUrl().isEmpty()) {
                    LOGGER.error("Custom API URL not set in config!");
                    yield null;
                }
                yield new GenericOpenAIClient(
                    AIPlayer.CONFIG.getCustomApiKey(),
                    AIPlayer.CONFIG.getSelectedLanguageModel(),
                    AIPlayer.CONFIG.getCustomApiUrl()
                );
            }
            case "ollama" -> new OllamaLocalClient(
                    AIPlayer.CONFIG.getOllamaBaseUrl(),
                    AIPlayer.CONFIG.getSelectedLanguageModel()
            );
            default -> {
                LOGGER.error("Unknown LLM mode '{}'; no client created", mode);
                yield null;
            }
        };
    }
}
