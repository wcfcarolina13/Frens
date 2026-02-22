package net.wcfcarolina13.FilingSystem;

import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ServiceLLMClients.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class LLMClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger("llm-client-factory");

    public static LLMClient createClient(String mode) {
        if (Frens.CONFIG == null) {
            LOGGER.error("Frens config not initialized; cannot create LLM client.");
            return null;
        }

        String normalized = (mode == null || mode.isBlank())
                ? System.getProperty("frens.llmMode", System.getProperty("aiplayer.llmMode", "ollama"))
                : mode.trim();
        normalized = normalized.toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "openai", "gpt" -> {
                if (Frens.CONFIG.getOpenAIKey().isEmpty()) {
                    LOGGER.error("OpenAI API key not set in config!");
                    yield null;
                }
                yield new OpenAIClient(
                    Frens.CONFIG.getOpenAIKey(),
                    Frens.CONFIG.getSelectedLanguageModel()
                );
            }
            case "anthropic", "claude" -> {
                if (Frens.CONFIG.getClaudeKey().isEmpty()) {
                    LOGGER.error("Claude API key not set in config!");
                    yield null;
                }
                yield new AnthropicClient(
                    Frens.CONFIG.getClaudeKey(),
                    Frens.CONFIG.getSelectedLanguageModel()
                );
            }
            case "google", "gemini" -> {
                if (Frens.CONFIG.getGeminiKey().isEmpty()) {
                    LOGGER.error("Gemini API key not set in config!");
                    yield null;
                }
                yield new GeminiClient(
                    Frens.CONFIG.getGeminiKey(),
                    Frens.CONFIG.getSelectedLanguageModel()
                );
            }
            case "xAI", "xai", "grok" -> {
                if (Frens.CONFIG.getGrokKey().isEmpty()) {
                    LOGGER.error("Grok API key not set in config!");
                    yield null;
                }
                yield new GrokClient(
                    Frens.CONFIG.getGrokKey(),
                    Frens.CONFIG.getSelectedLanguageModel()
                );
            }
            case "custom" -> {
                if (Frens.CONFIG.getCustomApiKey().isEmpty()) {
                    LOGGER.error("Custom API key not set in config!");
                    yield null;
                }
                if (Frens.CONFIG.getCustomApiUrl().isEmpty()) {
                    LOGGER.error("Custom API URL not set in config!");
                    yield null;
                }
                yield new GenericOpenAIClient(
                    Frens.CONFIG.getCustomApiKey(),
                    Frens.CONFIG.getSelectedLanguageModel(),
                    Frens.CONFIG.getCustomApiUrl()
                );
            }
            case "ollama" -> new OllamaLocalClient(
                    Frens.CONFIG.getOllamaBaseUrl(),
                    Frens.CONFIG.getSelectedLanguageModel()
            );
            default -> {
                LOGGER.error("Unknown LLM mode '{}'; no client created", mode);
                yield null;
            }
        };
    }
}
