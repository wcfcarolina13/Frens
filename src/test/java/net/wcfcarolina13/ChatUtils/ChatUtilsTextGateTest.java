package net.wcfcarolina13.ChatUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat text gate that decides both the "Sending chat message" / "Scripted chat suppressed"
 * log choice and the real send. Pure booleans; no Minecraft types.
 */
class ChatUtilsTextGateTest {

    @Test
    void nonBotSenderAlwaysSeesText() {
        assertTrue(ChatUtils.shouldSendText(false, false, false));
        assertTrue(ChatUtils.shouldSendText(false, false, true));
        assertTrue(ChatUtils.shouldSendText(false, true, false));
        assertTrue(ChatUtils.shouldSendText(false, true, true));
    }

    @Test
    void botLineShowsWhenItsCategoryTextIsAllowed() {
        assertTrue(ChatUtils.shouldSendText(true, true, false));
        assertTrue(ChatUtils.shouldSendText(true, true, true));
    }

    @Test
    void voiceOnlyFallbackForcesTextForAnUnvoicedBotLine() {
        assertTrue(ChatUtils.shouldSendText(true, false, true));
    }

    @Test
    void botLineWithTextOffAndNoFallbackIsSuppressed() {
        assertFalse(ChatUtils.shouldSendText(true, false, false));
    }
}
