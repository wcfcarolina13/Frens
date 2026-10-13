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
}
