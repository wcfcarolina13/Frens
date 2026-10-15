package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCompareFormatTest {

    @Test
    void lineMatchesTheFixedFormatExactly() {
        String line = PathCompareFormat.line("Jake", 12, 64, -30, 40, 66, -8,
                PathFinderChoice.BARITONE,
                1840L, 0, PathCompareFormat.NONE,
                37L, 31, PathCompareFormat.EXEC);
        assertEquals("[PathCompare] bot=Jake from=12,64,-30 to=40,66,-8 primary=baritone"
                + " bidir=1840ms/0/none baritone=37ms/31/exec", line);
    }

    @Test
    void primaryLabelFollowsTheChoice() {
        String line = PathCompareFormat.line("Bob", 0, 70, 0, 5, 70, 5,
                PathFinderChoice.BIDIR, 12L, 9, PathCompareFormat.NOEXEC, 8L, 7, PathCompareFormat.EXEC);
        assertTrue(line.contains(" primary=bidir "));
        assertTrue(line.endsWith(" bidir=12ms/9/noexec baritone=8ms/7/exec"));
    }

    @Test
    void lineParsesWithTheAnalyzerRegexes() {
        // tools/logs/frens_log_report.py KV_RE and FINDER_VAL_RE.
        Pattern kv = Pattern.compile("(\\w+)=(\\S+)");
        Pattern finder = Pattern.compile("^(-?\\d+)ms/(-?\\d+)/(\\w+)");
        String line = PathCompareFormat.line("Two Words", -1, -2, -3, 4, 5, 6,
                PathFinderChoice.BARITONE, 5L, 3, "exec", 6L, 4, "noexec");
        Matcher m = kv.matcher(line.substring("[PathCompare] ".length()));
        int finders = 0;
        int plain = 0;
        while (m.find()) {
            if (finder.matcher(m.group(2)).find()) {
                finders++;
                assertTrue(m.group(1).equals("bidir") || m.group(1).equals("baritone"));
            } else {
                plain++;
            }
        }
        assertEquals(2, finders);
        assertEquals(4, plain); // bot, from, to, primary
        assertTrue(line.contains("bot=Two_Words "));
    }

    @Test
    void statusMapsEmptyToNone() {
        assertEquals("none", PathCompareFormat.status(0, true));
        assertEquals("none", PathCompareFormat.status(0, false));
        assertEquals("exec", PathCompareFormat.status(5, true));
        assertEquals("noexec", PathCompareFormat.status(5, false));
    }

    @Test
    void adjacentStepIsOneBlockPerHorizontalAxis() {
        assertTrue(PathCompareFormat.isAdjacentStep(1, 0));
        assertTrue(PathCompareFormat.isAdjacentStep(-1, 1));
        assertTrue(PathCompareFormat.isAdjacentStep(0, 0));
        assertFalse(PathCompareFormat.isAdjacentStep(2, 0));
        assertFalse(PathCompareFormat.isAdjacentStep(0, -2));
    }
}
