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
}
