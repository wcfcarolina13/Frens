package net.wcfcarolina13.GameAI.llm;

/**
 * When the legacy NLP intent stack (NLPProcessor, BERT/CART/LIDSNet models) may be used and its
 * models fetched. Nothing is acquired at startup: only a real legacy-LLM demand -- ollama4j on
 * the classpath AND the world LLM toggle on AND the addressed bot's LLM toggle on -- starts the
 * (worker-thread) model setup, once per JVM ({@code Frens.requestLegacyNlpAssets}).
 */
public final class LegacyNlpBootstrapPolicy {

    private LegacyNlpBootstrapPolicy() {
    }

    /** Whether the legacy NLP/LLM path may run for this bot at all. */
    public static boolean isLegacyNlpUsable(boolean ollama4jAvailable, boolean worldLlmEnabled, boolean botLlmEnabled) {
        return ollama4jAvailable && worldLlmEnabled && botLlmEnabled;
    }
}
