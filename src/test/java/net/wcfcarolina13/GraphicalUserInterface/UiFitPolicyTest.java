package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiFitPolicyTest {

    /** 6 px per code point, like a rough Minecraft glyph. */
    private static final ToIntFunction<String> W6 = s -> s.codePointCount(0, s.length()) * 6;

    // ── wrap ──

    @Test
    void wrapKeepsShortTextOnOneLine() {
        assertEquals(List.of("hello world"), UiFitPolicy.wrap("hello world", 200, W6));
    }

    @Test
    void wrapBreaksAtWordBoundaries() {
        // 60 px = 10 chars per line
        assertEquals(List.of("aaa bbb", "ccc ddd"), UiFitPolicy.wrap("aaa bbb ccc ddd", 60, W6));
    }

    @Test
    void wrapLineExactlyAtWidthFits() {
        assertEquals(List.of("abcde fghi"), UiFitPolicy.wrap("abcde fghi", 60, W6));
    }

    @Test
    void wrapHardBreaksOverlongWord() {
        assertEquals(List.of("abcd", "efgh", "ij"), UiFitPolicy.wrap("abcdefghij", 24, W6));
    }

    @Test
    void wrapContinuesAfterHardBrokenWordTail() {
        assertEquals(List.of("abcde", "f gh"), UiFitPolicy.wrap("abcdef gh", 30, W6));
    }

    @Test
    void wrapCollapsesRepeatedSpacesAndTrims() {
        assertEquals(List.of("a b c"), UiFitPolicy.wrap("  a   b  c  ", 200, W6));
    }

    @Test
    void wrapHonoursNewlines() {
        assertEquals(List.of("one", "two"), UiFitPolicy.wrap("one\ntwo", 200, W6));
    }

    @Test
    void wrapNullAndEmpty() {
        assertEquals(List.of(), UiFitPolicy.wrap(null, 100, W6));
        assertEquals(List.of(""), UiFitPolicy.wrap("", 100, W6));
    }

    @Test
    void wrapNonPositiveWidthReturnsSingleEmptyLine() {
        assertEquals(List.of(""), UiFitPolicy.wrap("anything", 0, W6));
        assertEquals(List.of(""), UiFitPolicy.wrap("anything", -5, W6));
    }

    @Test
    void wrapNeverSplitsSurrogatePairs() {
        String horse = "🐴"; // one code point, two chars
        List<String> lines = UiFitPolicy.wrap(horse + horse + horse, 12, W6);
        assertEquals(List.of(horse + horse, horse), lines);
    }

    @Test
    void wrapEveryLineFitsForALongSentence() {
        String text = "Looking at Jake. Right-click to open menu • Press ] for Guide and some more words";
        for (int max : new int[] {30, 61, 100, 290}) {
            for (String line : UiFitPolicy.wrap(text, max, W6)) {
                assertTrue(W6.applyAsInt(line) <= max, "line '" + line + "' exceeds " + max);
            }
        }
    }

    @Test
    void wrapWithMaxLinesElidesTheLastKeptLine() {
        List<String> lines = UiFitPolicy.wrap("aaa bbb ccc ddd eee fff", 42, W6, 2);
        assertEquals(2, lines.size());
        assertEquals("aaa bbb", lines.get(0));
        assertEquals("ccc dd…", lines.get(1));
        assertTrue(W6.applyAsInt(lines.get(1)) <= 42);
    }

    @Test
    void wrapWithMaxLinesLeavesFittingTextAlone() {
        assertEquals(List.of("aaa bbb", "ccc"), UiFitPolicy.wrap("aaa bbb ccc", 42, W6, 3));
    }

    @Test
    void wrapWithMaxLinesBelowOneKeepsOneLine() {
        List<String> lines = UiFitPolicy.wrap("aaa bbb ccc", 42, W6, 0);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith(UiFitPolicy.ELLIPSIS));
    }

    // ── elide ──

    @Test
    void elideLeavesFittingTextAlone() {
        assertEquals("short", UiFitPolicy.elide("short", 30, W6));
    }

    @Test
    void elideCutsAndAppendsEllipsis() {
        String out = UiFitPolicy.elide("abcdefghij", 30, W6);
        assertEquals("abcd…", out);
        assertTrue(W6.applyAsInt(out) <= 30);
    }

    @Test
    void elideTrimsTrailingSpaceBeforeEllipsis() {
        assertEquals("ab…", UiFitPolicy.elide("ab cdefgh", 24, W6));
    }

    @Test
    void elideReturnsEmptyWhenEllipsisCannotFit() {
        assertEquals("", UiFitPolicy.elide("abcdef", 5, W6));
    }

    @Test
    void elideReturnsBareEllipsisWhenOnlyItFits() {
        assertEquals("…", UiFitPolicy.elide("abcdef", 6, W6));
    }

    @Test
    void elideNullIsEmpty() {
        assertEquals("", UiFitPolicy.elide(null, 100, W6));
    }

    // ── pickLabel ──

    @Test
    void pickLabelTakesFirstThatFits() {
        List<String> candidates = List.of("Right Bracket (])", "]");
        assertEquals("Right Bracket (])", UiFitPolicy.pickLabel(candidates, 200, W6));
        assertEquals("]", UiFitPolicy.pickLabel(candidates, 60, W6));
    }

    @Test
    void pickLabelElidesShortestWhenNothingFits() {
        assertEquals("Ta…", UiFitPolicy.pickLabel(List.of("Dialogue", "Talk"), 18, W6));
    }

    @Test
    void pickLabelEmptyList() {
        assertEquals("", UiFitPolicy.pickLabel(List.of(), 100, W6));
        assertEquals("", UiFitPolicy.pickLabel(null, 100, W6));
    }

    // ── fitTabWidths ──

    @Test
    void fitTabWidthsSharesSpareEvenlyAndSumsToAvailable() {
        int[] widths = UiFitPolicy.fitTabWidths(List.of(36, 41, 25), 130, 3);
        // natural 42 + 47 + 31 = 120, spare 10 -> 4,3,3
        assertArrayEquals(new int[] {46, 50, 34}, widths);
        assertEquals(130, widths[0] + widths[1] + widths[2]);
    }

    @Test
    void fitTabWidthsExactFit() {
        assertArrayEquals(new int[] {42, 47}, UiFitPolicy.fitTabWidths(List.of(36, 41), 89, 3));
    }

    @Test
    void fitTabWidthsReturnsNullWhenLabelsCannotFit() {
        // the 1.1.227 overlay case: three labels in about 111 px
        assertNull(UiFitPolicy.fitTabWidths(List.of(36, 41, 25), 110, 3));
    }

    @Test
    void fitTabWidthsEmptyAndNegativePadding() {
        assertArrayEquals(new int[0], UiFitPolicy.fitTabWidths(List.of(), 100, 3));
        assertArrayEquals(new int[] {10, 10}, UiFitPolicy.fitTabWidths(List.of(10, 10), 20, -4));
    }

    // ── splitColumns ──

    @Test
    void splitColumnsUsesFractionWhenRoomy() {
        int[] cols = UiFitPolicy.splitColumns(400, 0.5, 100, 100, 10);
        assertArrayEquals(new int[] {195, 195}, cols);
    }

    @Test
    void splitColumnsClampsToMinimums() {
        int[] cols = UiFitPolicy.splitColumns(400, 0.1, 160, 140, 10);
        assertArrayEquals(new int[] {160, 230}, cols);
        int[] right = UiFitPolicy.splitColumns(400, 0.95, 160, 140, 10);
        assertArrayEquals(new int[] {250, 140}, right);
    }

    @Test
    void splitColumnsRelaxesMinimumsProportionallyWhenShort() {
        // 1.1.227 overlay at 342 wide: 290 available, minimums 160 + 140 = 300
        int[] cols = UiFitPolicy.splitColumns(290, 0.55, 160, 140, 0);
        assertEquals(290, cols[0] + cols[1]);
        assertEquals(155, cols[0]);
        assertEquals(135, cols[1]);
    }

    @Test
    void splitColumnsAlwaysSumsToAvailableMinusGap() {
        for (int available = 0; available <= 500; available += 7) {
            for (double fraction : new double[] {0.0, 0.33, 0.5, 0.8, 1.0}) {
                int[] cols = UiFitPolicy.splitColumns(available, fraction, 120, 90, 6);
                assertEquals(Math.max(0, available - 6), cols[0] + cols[1]);
                assertTrue(cols[0] >= 0 && cols[1] >= 0);
            }
        }
    }

    @Test
    void splitColumnsHandlesBadInputs() {
        assertArrayEquals(new int[] {0, 0}, UiFitPolicy.splitColumns(4, 0.5, 10, 10, 10));
        int[] nan = UiFitPolicy.splitColumns(100, Double.NaN, 0, 0, 0);
        assertArrayEquals(new int[] {50, 50}, nan);
        int[] zeroMins = UiFitPolicy.splitColumns(100, 0.3, 0, 0, 0);
        assertArrayEquals(new int[] {30, 70}, zeroMins);
    }

    // ── fitScale ──

    @Test
    void fitScaleIsOneWhenContentFits() {
        assertEquals(1.0f, UiFitPolicy.fitScale(364, 230, 427, 240, 4));
    }

    @Test
    void fitScaleShrinksToTheTighterAxis() {
        // bot inventory at Bradley's 342x328: width bound
        float s = UiFitPolicy.fitScale(364, 230, 342, 328, 4);
        assertEquals((342f - 8f) / 364f, s, 1e-6);
        // at 320x240, still width bound
        float floor = UiFitPolicy.fitScale(364, 230, 320, 240, 4);
        assertEquals((320f - 8f) / 364f, floor, 1e-6);
        // height bound
        float tall = UiFitPolicy.fitScale(200, 400, 640, 240, 0);
        assertEquals(240f / 400f, tall, 1e-6);
    }

    @Test
    void fitScaleGuardsDegenerateInput() {
        assertEquals(1.0f, UiFitPolicy.fitScale(0, 100, 10, 10, 0));
        assertEquals(UiFitPolicy.MIN_SCALE, UiFitPolicy.fitScale(364, 230, 10, 10, 20));
    }

    // ── popups and legacy-preserving fits ──

    @Test
    void popupKeepsItsDesignedSizeWheneverItFits() {
        assertEquals(420, UiFitPolicy.popupExtent(420, 427, 16));
        assertEquals(240, UiFitPolicy.popupExtent(240, 240, 8));
        assertEquals(411, UiFitPolicy.popupExtent(430, 427, 16));
        assertEquals(304, UiFitPolicy.popupExtent(420, 320, 16));
        assertEquals(0, UiFitPolicy.popupExtent(420, 4, 16));
    }

    @Test
    void popupOriginCentresAndNeverGoesOffTheTop() {
        assertEquals(0, UiFitPolicy.popupOrigin(240, 240));
        assertEquals(4, UiFitPolicy.popupOrigin(240, 232));
        assertEquals(0, UiFitPolicy.popupOrigin(200, 250));
    }

    @Test
    void elideStartKeepsTheEndOfAPath() {
        assertEquals("/a/b", UiFitPolicy.elideStart("/a/b", 60, W6));
        String out = UiFitPolicy.elideStart("/home/user/frens/piper", 60, W6);
        assertEquals("…ens/piper", out);
        assertTrue(W6.applyAsInt(out) <= 60);
        assertEquals("", UiFitPolicy.elideStart("abc", 3, W6));
        assertEquals("", UiFitPolicy.elideStart(null, 60, W6));
        // Never starts on the low half of an emoji.
        String emoji = UiFitPolicy.elideStart("abc\uD83D\uDE00de", 24, W6);
        assertEquals("…\uD83D\uDE00de", emoji);
    }

    @Test
    void wrapIfNeededKeepsAFittingLineExactlyIncludingDoubleSpaces() {
        assertEquals(List.of("a  b"), UiFitPolicy.wrapIfNeeded("a  b", 60, W6));
        assertEquals(List.of("aaa bbb", "ccc ddd"), UiFitPolicy.wrapIfNeeded("aaa bbb ccc ddd", 60, W6));
    }

    @Test
    void keepOrWrapKeepsTheLegacySplitOnlyWhenEveryLineFits() {
        List<String> legacy = List.of("aaa bbb ccc", "ddd");
        assertEquals(legacy, UiFitPolicy.keepOrWrap(legacy, "aaa bbb ccc ddd", 66, W6));
        assertEquals(List.of("aaa bbb", "ccc ddd"), UiFitPolicy.keepOrWrap(legacy, "aaa bbb ccc ddd", 60, W6));
    }
}
