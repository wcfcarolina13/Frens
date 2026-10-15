package net.wcfcarolina13.GameAI.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatOffHintPolicyTest {

    private static boolean hint(boolean single, boolean prompt, boolean soulOn,
                                boolean authorized, boolean hinted) {
        return AiChatOffHintPolicy.shouldHint(single, prompt, soulOn, authorized, hinted);
    }

    @Test
    void hintsTheFirstUnansweredAddressWithAiChatOff() {
        assertTrue(hint(true, true, false, true, false));
    }

    @Test
    void onlyOncePerPlayerPerSession() {
        assertFalse(hint(true, true, false, true, true));
    }

    @Test
    void soulMasterOnIsADifferentStateAndGetsNoHint() {
        assertFalse(hint(true, true, true, true, false));
    }

    @Test
    void groupAddressEmptyPromptOrUnauthorizedSenderGetNoHint() {
        assertFalse(hint(false, true, false, true, false));
        assertFalse(hint(true, false, false, true, false));
        assertFalse(hint(true, true, false, false, false));
    }

    @Test
    void noticeHasNoEmDashEnDashOrSemicolonAndNamesTheGuide() {
        String n = AiChatOffHintPolicy.NOTICE;
        assertFalse(n.contains("—"));
        assertFalse(n.contains("–"));
        assertFalse(n.contains(";"));
        assertTrue(n.contains("Guide"));
        assertTrue(n.contains("AI setup"));
    }
}
