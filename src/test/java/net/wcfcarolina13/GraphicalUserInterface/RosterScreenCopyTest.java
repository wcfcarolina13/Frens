package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterScreenCopyTest {

    @Test
    void anyRestorableBotWinsOverAllPresentFlag() {
        assertEquals(RosterScreenCopy.State.HAS_RESTORABLE, RosterScreenCopy.state(2, false));
        assertEquals(RosterScreenCopy.State.HAS_RESTORABLE, RosterScreenCopy.state(1, true));
    }

    @Test
    void emptyListSplitsIntoNoneSavedAndAllPresent() {
        assertEquals(RosterScreenCopy.State.NONE_SAVED, RosterScreenCopy.state(0, false));
        assertEquals(RosterScreenCopy.State.ALL_PRESENT, RosterScreenCopy.state(0, true));
    }

    @Test
    void allPresentDoesNotClaimNothingIsSaved() {
        RosterScreenCopy.State s = RosterScreenCopy.State.ALL_PRESENT;
        assertEquals("All your companions are already here", RosterScreenCopy.title(s));
        assertFalse(RosterScreenCopy.title(s).toLowerCase().contains("no saved"));
        assertFalse(RosterScreenCopy.subtitle(s).toLowerCase().contains("first"));
        assertTrue(RosterScreenCopy.subtitle(s).contains("Create"));
        assertNotEquals(RosterScreenCopy.emptyListLine(RosterScreenCopy.State.NONE_SAVED), RosterScreenCopy.emptyListLine(s));
    }

    @Test
    void noEmDashesOrSemicolonsInAnyCopy() {
        for (RosterScreenCopy.State s : RosterScreenCopy.State.values()) {
            for (String text : new String[]{RosterScreenCopy.title(s), RosterScreenCopy.subtitle(s), RosterScreenCopy.emptyListLine(s)}) {
                assertFalse(text.contains("—"), text);
                assertFalse(text.contains(";"), text);
            }
        }
    }

    @Test
    void noCompanionsHereButSomeElsewhereSaysSoPlainly() {
        assertEquals(RosterScreenCopy.State.NONE_HERE, RosterScreenCopy.state(0, false, 2));
        assertEquals(RosterScreenCopy.State.NONE_SAVED, RosterScreenCopy.state(0, false, 0));
        assertEquals(RosterScreenCopy.State.ALL_PRESENT, RosterScreenCopy.state(0, true, 2));
        assertEquals(RosterScreenCopy.State.HAS_RESTORABLE, RosterScreenCopy.state(1, false, 2));
        assertEquals("No companions in this world yet.", RosterScreenCopy.title(RosterScreenCopy.State.NONE_HERE));
        assertTrue(RosterScreenCopy.subtitle(RosterScreenCopy.State.NONE_HERE).contains("Other worlds"));
    }

    @Test
    void emptyListLineNeverRepeatsTheTitle() {
        for (RosterScreenCopy.State s : RosterScreenCopy.State.values()) {
            String line = RosterScreenCopy.emptyListLine(s);
            if (line.isEmpty()) {
                continue;
            }
            java.util.Set<String> titlePairs = wordPairs(RosterScreenCopy.title(s));
            for (String pair : wordPairs(line)) {
                assertFalse(titlePairs.contains(pair), s + " placeholder repeats title words \"" + pair + "\": " + line);
            }
        }
    }

    private static java.util.Set<String> wordPairs(String text) {
        String[] words = text.toLowerCase().replaceAll("[^a-z ]", " ").trim().split("\\s+");
        java.util.Set<String> pairs = new java.util.HashSet<>();
        for (int i = 0; i + 1 < words.length; i++) {
            pairs.add(words[i] + " " + words[i + 1]);
        }
        return pairs;
    }

    @Test
    void otherWorldsCopyNamesTheCountAndWhatImportDoes() {
        assertEquals("Other worlds (2)", RosterScreenCopy.otherWorldsToggle(2, false));
        assertEquals("Back to this world", RosterScreenCopy.otherWorldsToggle(2, true));
        String sub = RosterScreenCopy.otherWorldsSubtitle();
        assertTrue(sub.contains("fresh"));
        assertTrue(sub.contains("inventory stays in its old world"));
        for (String text : new String[]{sub, RosterScreenCopy.otherWorldsTitle(),
                RosterScreenCopy.otherWorldsToggle(3, false), RosterScreenCopy.otherWorldsToggle(3, true)}) {
            assertFalse(text.contains("\u2014"), text);
            assertFalse(text.contains(";"), text);
        }
    }

    @Test
    void createHintPointsAtOtherWorldsPlainly() {
        String hint = RosterScreenCopy.createNameInOtherWorldHint();
        assertTrue(hint.contains("another world"), hint);
        assertTrue(hint.contains("Other worlds"), hint);
        assertFalse(hint.contains("\u2014"), hint);
        assertFalse(hint.contains(";"), hint);
    }

    @Test
    void defaultRecruitAloneIsAFirstMeetingNotARestore() {
        assertEquals(RosterScreenCopy.State.FIRST_COMPANION, RosterScreenCopy.state(1, false, 0, 0));
        assertEquals(RosterScreenCopy.State.FIRST_COMPANION, RosterScreenCopy.state(1, false, 2, 0));
        assertEquals(RosterScreenCopy.State.HAS_RESTORABLE, RosterScreenCopy.state(2, false, 0, 1));
        // The old overloads keep treating every listed name as saved.
        assertEquals(RosterScreenCopy.State.HAS_RESTORABLE, RosterScreenCopy.state(1, false, 0));
        // Empty lists are unchanged by the saved count.
        assertEquals(RosterScreenCopy.State.NONE_SAVED, RosterScreenCopy.state(0, false, 0, 0));
        assertEquals(RosterScreenCopy.State.ALL_PRESENT, RosterScreenCopy.state(0, true, 0, 0));
    }

    @Test
    void firstCompanionCopyNeverCallsTheBotSavedOrRestorable() {
        RosterScreenCopy.State s = RosterScreenCopy.State.FIRST_COMPANION;
        assertEquals("Meet Your First Companion", RosterScreenCopy.title(s));
        assertEquals("Tick Jake, then click Spawn Selected, or name your own below.",
                RosterScreenCopy.subtitle(s, "Jake"));
        assertEquals("Tick a name, then click Spawn Selected, or name your own below.",
                RosterScreenCopy.subtitle(s, null));
        for (String text : new String[]{RosterScreenCopy.title(s), RosterScreenCopy.subtitle(s, "Jake")}) {
            String lower = text.toLowerCase();
            assertFalse(lower.contains("saved"), text);
            assertFalse(lower.contains("restor"), text);
            assertFalse(text.contains("—"), text);
            assertFalse(text.contains(";"), text);
        }
        assertEquals("", RosterScreenCopy.emptyListLine(s));
    }

    @Test
    void firstCompanionCopyFitsTheNarrowRoster() {
        // Panel 304 wide at the 320 floor, text inside 8 px padding; subtitle may wrap to 2 lines.
        java.util.function.ToIntFunction<String> w = t -> t.length() * 6;
        RosterScreenCopy.State s = RosterScreenCopy.State.FIRST_COMPANION;
        assertTrue(w.applyAsInt(RosterScreenCopy.title(s)) <= 288);
        java.util.List<String> lines = UiFitPolicy.wrap(RosterScreenCopy.subtitle(s, "Jake"), 288, w, 2);
        assertFalse(lines.get(lines.size() - 1).endsWith(UiFitPolicy.ELLIPSIS), lines.toString());
    }

    @Test
    void savedCountMatchesNamesCaseInsensitively() {
        assertEquals(0, RosterScreenCopy.savedCount(java.util.List.of("Jake"), java.util.Set.of()));
        assertEquals(1, RosterScreenCopy.savedCount(java.util.List.of("Jake", "Bob"), java.util.Set.of("bob")));
        assertEquals(2, RosterScreenCopy.savedCount(java.util.List.of(" JAKE ", "Bob"), java.util.Set.of("jake", "bob")));
        assertEquals(0, RosterScreenCopy.savedCount(null, java.util.Set.of("jake")));
        assertEquals(0, RosterScreenCopy.savedCount(java.util.List.of("Jake"), null));
    }
}
