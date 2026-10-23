package net.wcfcarolina13.GameAI.services.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the text-only line set (lines whose silent placeholder sounds were removed). */
class TextOnlyLinePolicyTest {

    @Test
    void placeholderLinesAreTextOnly() {
        assertTrue(TextOnlyLinePolicy.isTextOnly("bot.line.stop_adios"));
        assertTrue(TextOnlyLinePolicy.isTextOnly("bot.line.snowball_probe_incoming"));
        assertTrue(TextOnlyLinePolicy.isTextOnly("bot.line.weird_portal_nether_in_end_2"));
        assertTrue(TextOnlyLinePolicy.isTextOnly("bot.line.post_explosion_less_boom"));
    }

    @Test
    void voicedLinesAreNotTextOnly() {
        assertFalse(TextOnlyLinePolicy.isTextOnly("bot.line.greeting_hey"));
        assertFalse(TextOnlyLinePolicy.isTextOnly("bot.line.portal_nether_overworld_1"));
        assertFalse(TextOnlyLinePolicy.isTextOnly("bot.greeting"));
    }

    @Test
    void pathMustMatchExactly() {
        assertFalse(TextOnlyLinePolicy.isTextOnly(null));
        assertFalse(TextOnlyLinePolicy.isTextOnly(""));
        assertFalse(TextOnlyLinePolicy.isTextOnly("frens:bot.line.stop_adios"));
        assertFalse(TextOnlyLinePolicy.isTextOnly("bot.line.STOP_ADIOS"));
        assertFalse(TextOnlyLinePolicy.isTextOnly(" bot.line.stop_adios"));
    }

    @Test
    void setHoldsTheThirtyNineRulingLines() {
        assertEquals(39, TextOnlyLinePolicy.TEXT_ONLY_PATHS.size());
        for (String path : TextOnlyLinePolicy.TEXT_ONLY_PATHS) {
            assertTrue(path.startsWith("bot.line."), path);
        }
    }
}
