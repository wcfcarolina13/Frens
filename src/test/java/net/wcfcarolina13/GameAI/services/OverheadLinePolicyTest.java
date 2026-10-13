package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverheadLinePolicyTest {

    @Test
    void nullAndBlankGiveNoLines() {
        assertTrue(OverheadLinePolicy.wrap(null).isEmpty());
        assertTrue(OverheadLinePolicy.wrap("").isEmpty());
        assertTrue(OverheadLinePolicy.wrap("   \t ").isEmpty());
    }

    @Test
    void shortLineStaysOneLine() {
        assertEquals(List.of("Chopping wood"), OverheadLinePolicy.wrap("Chopping wood"));
    }

    @Test
    void exactlyMaxCharsStaysOneLine() {
        String s = "abcdefghij abcdefghij ab"; // 24
        assertEquals(List.of(s), OverheadLinePolicy.wrap(s));
    }

    @Test
    void wrapsOnWordBoundaries() {
        assertEquals(List.of("RotiWokeman, there you", "are! I was starting to", "wonder."),
                OverheadLinePolicy.wrap("RotiWokeman, there you are! I was starting to wonder."));
    }

    @Test
    void collapsesWhitespaceAndNewlines() {
        assertEquals(List.of("a b c"), OverheadLinePolicy.wrap("  a \n b\t\tc  "));
    }

    @Test
    void overflowElidesLastLineWithinWidth() {
        List<String> out = OverheadLinePolicy.wrap(
                "RotiWokeman, there you are! I was starting to wonder if the creepers had got you first.");
        assertEquals(3, out.size());
        assertTrue(out.get(2).endsWith(OverheadLinePolicy.ELLIPSIS));
        for (String line : out) {
            assertTrue(line.length() <= 24, line);
        }
    }

    @Test
    void textThatFitsExactlyIsNotElided() {
        assertEquals(List.of("aaaa", "bb cc"), OverheadLinePolicy.wrap("aaaa bb cc", 5, 2));
    }

    @Test
    void elisionAppendsWhenRoomAndCutsAtSpaceWhenFull() {
        assertEquals(List.of("aaaa", "bb c…"), OverheadLinePolicy.wrap("aaaa bb c dd", 5, 2));
        assertEquals(List.of("aaaa", "bb…"), OverheadLinePolicy.wrap("aaaa bb cc dd", 5, 2));
        assertEquals(List.of("aaaa", "bbbb…"), OverheadLinePolicy.wrap("aaaa bbbbb cc", 5, 2));
    }

    @Test
    void overlongWordIsHardBroken() {
        assertEquals(List.of("abcdefghij", "klmnop xy"),
                OverheadLinePolicy.wrap("abcdefghijklmnop xy", 10, 3));
    }

    @Test
    void overlongWordAfterTextStartsOnItsOwnLine() {
        assertEquals(List.of("hi", "abcdefghij", "klm"),
                OverheadLinePolicy.wrap("hi abcdefghijklm", 10, 3));
    }

    @Test
    void neverExceedsMaxLinesOrWidth() {
        String longWord = "x".repeat(200);
        List<String> out = OverheadLinePolicy.wrap(longWord);
        assertEquals(3, out.size());
        assertEquals("x".repeat(23) + OverheadLinePolicy.ELLIPSIS, out.get(2));
        for (String line : out) {
            assertTrue(line.length() <= 24, line);
        }
    }

    @Test
    void degenerateLimitsAreClamped() {
        List<String> out = OverheadLinePolicy.wrap("hello there", 0, 0);
        assertEquals(1, out.size());
        assertTrue(out.get(0).length() <= 2);
        assertTrue(out.get(0).endsWith(OverheadLinePolicy.ELLIPSIS));
    }
}
