package net.wcfcarolina13.GameAI.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyChatStatusPolicyTest {
    @Test
    void disabledWorldDoesNotAnnounce() {
        assertFalse(LegacyChatStatusPolicy.shouldAnnounceProcessing(false, true, true, true, true));
    }

    @Test
    void disabledBotDoesNotAnnounce() {
        assertFalse(LegacyChatStatusPolicy.shouldAnnounceProcessing(true, false, true, true, true));
    }

    @Test
    void missingLibraryDoesNotAnnounce() {
        assertFalse(LegacyChatStatusPolicy.shouldAnnounceProcessing(true, true, false, true, true));
    }

    @Test
    void missingClientDoesNotAnnounce() {
        assertFalse(LegacyChatStatusPolicy.shouldAnnounceProcessing(true, true, true, false, true));
    }

    @Test
    void failedParsingDoesNotAnnounce() {
        assertFalse(LegacyChatStatusPolicy.shouldAnnounceProcessing(true, true, true, true, false));
    }

    @Test
    void allGatesPassedAnnounces() {
        assertTrue(LegacyChatStatusPolicy.shouldAnnounceProcessing(true, true, true, true, true));
    }
}
