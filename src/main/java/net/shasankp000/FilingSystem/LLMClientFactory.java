    package net.shasankp000.FilingSystem;

    import net.shasankp000.AIPlayer;
    import net.shasankp000.ServiceLLMClients.*;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    public class LLMClientFactory {

        private static final Logger LOGGER = LoggerFactory.getLogger("llm-client-factory");

        public static LLMClient createClient(String mode) {
            return switch (mode) {
                case "openai", "gpt" -> {
                    if (AIPlayer.CONFIG.openAIKey().isEmpty()) {
                        LOGGER.error("OpenAI API key not set in config!");
                        yield null;
                    }
                    yield new OpenAIClient(AIPlayer.CONFIG.openAIKey(), AIPlayer.CONFIG.selectedLanguageModel());
                }
                case "anthropic", "claude" -> {
                    if (AIPlayer.CONFIG.claudeKey().isEmpty()) {
                        LOGGER.error("Claude API key not set in config!");
                        yield null;
                    }
                    yield new AnthropicClient(AIPlayer.CONFIG.claudeKey(), AIPlayer.CONFIG.selectedLanguageModel());
                }
                case "google", "gemini" -> {
                    if (AIPlayer.CONFIG.geminiKey().isEmpty()) {
                        LOGGER.error("Gemini API key not set in config!");
                        yield null;
                    }
                    yield new GeminiClient(AIPlayer.CONFIG.geminiKey(), AIPlayer.CONFIG.selectedLanguageModel());
                }
                case "xAI", "xai", "grok" -> {
                    if (AIPlayer.CONFIG.grokKey().isEmpty()) {
                        LOGGER.error("Grok API key not set in config!");
                        yield null;
                    }
                    yield new GrokClient(AIPlayer.CONFIG.grokKey(), AIPlayer.CONFIG.selectedLanguageModel());
                }
                default -> {
                    LOGGER.info("Defaulting to Ollama client");
                    yield null;
                }
            };
        }
    }
