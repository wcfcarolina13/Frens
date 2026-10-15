package net.wcfcarolina13.PathFinding;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void outcomeLineMatchesTheFixedFormat() {
        assertEquals("[PathCompareOutcome] bot=Jake to=40,66,-8 reached=yes detail=woodcut:_walked_to_destination",
                PathCompareFormat.outcomeLine("Jake", 40, 66, -8, true, "woodcut: walked to destination"));
        assertEquals("[PathCompareOutcome] bot=Two_Words to=1,2,3 reached=no detail=walk_blocked_near_1,2,3",
                PathCompareFormat.outcomeLine("Two Words", 1, 2, 3, false, "walk blocked  near 1,2,3"));
        assertEquals("[PathCompareOutcome] bot=? to=0,0,0 reached=n/a detail=-",
                PathCompareFormat.outcomeLine(null, 0, 0, 0, null, null));
    }

    @Test
    void outcomeDetailIsOneBoundedTokenWithoutEquals() {
        String d = PathCompareFormat.sanitizeDetail("  a=b\tc\nd  " + "x".repeat(200));
        assertFalse(d.contains("="));
        assertFalse(d.matches(".*\\s.*"));
        assertTrue(d.startsWith("a:b_c_d_"));
        assertEquals(PathCompareFormat.OUTCOME_DETAIL_MAX, d.length());
        assertEquals("-", PathCompareFormat.sanitizeDetail("   "));
    }

    @Test
    void outcomeLineParsesAsKeyValuesAndNotAsAPathCompareLine() {
        String line = PathCompareFormat.outcomeLine("Bob", -5, 70, 12, false, "label: no walkable path (bot=Bob)");
        assertFalse(Pattern.compile("\\[PathCompare\\] (.*)$").matcher(line).find());
        Matcher m = Pattern.compile("\\[PathCompareOutcome\\] bot=(\\S+) to=(-?\\d+),(-?\\d+),(-?\\d+)"
                + " reached=(yes|no|n/a) detail=(\\S+)$").matcher(line);
        assertTrue(m.find());
        assertEquals("Bob", m.group(1));
        assertEquals("-5", m.group(2));
        assertEquals("no", m.group(5));
        assertEquals("label:_no_walkable_path_(bot:Bob)", m.group(6));
    }

    @Test
    void threeFinderLineMatchesTheFixedFormat() {
        String line = PathCompareFormat.line("Jake", 12, 64, -30, 40, 66, -8,
                PathFinderChoice.UNIFIED,
                1840L, 0, PathCompareFormat.NONE,
                37L, 31, PathCompareFormat.EXEC,
                2L, 29, PathCompareFormat.NOEXEC,
                12L);
        assertEquals("[PathCompare] bot=Jake from=12,64,-30 to=40,66,-8 primary=unified"
                + " bidir=1840ms/0/none baritone=37ms/31/exec unified=2ms/29/noexec plan=p12", line);
        String noPlan = PathCompareFormat.line("Jake", 0, 0, 0, 1, 1, 1, PathFinderChoice.BARITONE,
                1L, 1, "exec", 1L, 1, "exec", 1L, 1, "exec", 0L);
        assertTrue(noPlan.endsWith(" unified=1ms/1/exec"));
    }

    @Test
    void threeFinderLineParsesWithTheAnalyzerRegexes() {
        Pattern kv = Pattern.compile("(\\w+)=(\\S+)");
        Pattern finder = Pattern.compile("^(-?\\d+)ms/(-?\\d+)/(\\w+)");
        String line = PathCompareFormat.line("Bob", -1, -2, -3, 4, 5, 6, PathFinderChoice.UNIFIED,
                5L, 3, "exec", 6L, 4, "noexec", 1L, 5, "exec", 7L);
        Matcher m = kv.matcher(line.substring("[PathCompare] ".length()));
        java.util.Set<String> finders = new java.util.HashSet<>();
        java.util.Map<String, String> plain = new java.util.HashMap<>();
        while (m.find()) {
            if (finder.matcher(m.group(2)).find()) {
                finders.add(m.group(1));
            } else {
                plain.put(m.group(1), m.group(2));
            }
        }
        assertEquals(java.util.Set.of("bidir", "baritone", "unified"), finders);
        assertEquals("p7", plain.get("plan"));
        assertEquals("unified", plain.get("primary"));
        assertEquals(5, plain.size()); // bot, from, to, primary, plan
    }

    @Test
    void unifiedReasonSuffixRidesOnTheFinderValue() {
        String line = PathCompareFormat.line("Eve", 29, 78, 142, 17, 73, 166, PathFinderChoice.UNIFIED,
                1L, 0, "none", 32L, 11, "exec", 0L, 0, "none",
                PathCompareFormat.unifiedReason("GOAL_NOT_STANDABLE"), 12L);
        assertTrue(line.endsWith(" unified=0ms/0/none:GOAL_NOT_STANDABLE plan=p12"), line);
        assertNull(PathCompareFormat.unifiedReason("NONE"));
        assertEquals("3ms/4/exec", PathCompareFormat.finderValue(3L, 4, "exec", null));
        assertEquals("3ms/4/exec", PathCompareFormat.finderValue(3L, 4, "exec", "two words"));
        // The analyzer's finder regex: status and reason are separate groups; the k=v split still holds.
        Matcher fv = Pattern.compile("^(-?\\d+)ms/(-?\\d+)/(\\w+)(?::(\\w+))?").matcher("0ms/0/none:GOAL_NOT_STANDABLE");
        assertTrue(fv.find());
        assertEquals("none", fv.group(3));
        assertEquals("GOAL_NOT_STANDABLE", fv.group(4));
    }

    @Test
    void unifiedLenAndStatus() {
        assertEquals(0, PathCompareFormat.unifiedLen(true, 0));
        assertEquals(1, PathCompareFormat.unifiedLen(false, 0));
        assertEquals(13, PathCompareFormat.unifiedLen(false, 12));
        assertEquals("none", PathCompareFormat.unifiedStatus(true, true));
        assertEquals("exec", PathCompareFormat.unifiedStatus(false, true));
        assertEquals("noexec", PathCompareFormat.unifiedStatus(false, false));
    }

    @Test
    void outcomeLineCarriesThePlanBeforeTheDetail() {
        assertEquals("[PathCompareOutcome] bot=Jake to=40,66,-8 reached=yes plan=p42 detail=walked",
                PathCompareFormat.outcomeLine("Jake", 40, 66, -8, true, "walked", 42L));
        assertEquals("[PathCompareOutcome] bot=Jake to=40,66,-8 reached=no detail=walked",
                PathCompareFormat.outcomeLine("Jake", 40, 66, -8, false, "walked", 0L));
        // tools/logs/frens_log_report.py outcome regex
        Matcher m = Pattern.compile("\\[PathCompareOutcome\\] bot=(\\S+) to=(-?\\d+,-?\\d+,-?\\d+)"
                + " reached=(yes|no|n/a)(?: plan=(\\S+))?(?: detail=(\\S*))?")
                .matcher(PathCompareFormat.outcomeLine("Bob", 1, 2, 3, null, "a b", 3L));
        assertTrue(m.find());
        assertEquals("p3", m.group(4));
        assertEquals("a_b", m.group(5));
    }
}
