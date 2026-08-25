package net.wcfcarolina13.GameAI.souls.voice;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulVoiceSanitizerTest {

    @Test
    void stripsFormattingCodesAndMarkupAndCollapsesWhitespace() {
        assertEquals(Optional.of("Hello there, friend."),
                SoulVoiceSanitizer.sanitize("§7Hello  *there*,\n_friend_.§r", 400));
    }

    @Test
    void truncatesAtASentenceBoundaryPastMaxChars() {
        String text = "First sentence here. Second sentence is much longer and keeps going.";
        // maxChars lands inside the second sentence -> cut back to the end of the first.
        assertEquals(Optional.of("First sentence here."), SoulVoiceSanitizer.sanitize(text, 25));
    }

    @Test
    void hardTruncatesWhenNoSentenceBoundaryExists() {
        String text = "a".repeat(500);
        Optional<String> out = SoulVoiceSanitizer.sanitize(text, 100);
        assertTrue(out.isPresent());
        assertEquals(100, out.get().length());
    }

    @Test
    void emptyBlankAndNullYieldEmpty() {
        assertTrue(SoulVoiceSanitizer.sanitize(null, 400).isEmpty());
        assertTrue(SoulVoiceSanitizer.sanitize("", 400).isEmpty());
        assertTrue(SoulVoiceSanitizer.sanitize("§7§r * _ ", 400).isEmpty());
    }
}
