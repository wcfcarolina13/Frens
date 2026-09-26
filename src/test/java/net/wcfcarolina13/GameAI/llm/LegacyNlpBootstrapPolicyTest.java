package net.wcfcarolina13.GameAI.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyNlpBootstrapPolicyTest {

    @Test
    void usableOnlyWithOllamaAndBothToggles() {
        assertTrue(LegacyNlpBootstrapPolicy.isLegacyNlpUsable(true, true, true));
    }

    @Test
    void standardBuildWithoutOllamaNeverUsesLegacyNlp() {
        assertFalse(LegacyNlpBootstrapPolicy.isLegacyNlpUsable(false, true, true));
    }

    @Test
    void worldToggleAloneIsNotDemand() {
        // Fresh defaults: world LLM on, per-bot LLM off -> nothing is fetched or run.
        assertFalse(LegacyNlpBootstrapPolicy.isLegacyNlpUsable(true, true, false));
    }

    @Test
    void worldToggleOffBlocksEvenAnEnabledBot() {
        assertFalse(LegacyNlpBootstrapPolicy.isLegacyNlpUsable(true, false, true));
    }
}
