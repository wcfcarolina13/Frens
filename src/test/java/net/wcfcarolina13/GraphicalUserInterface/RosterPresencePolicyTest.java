package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterPresencePolicyTest {

    @Test
    void dropsPresentBotsCaseInsensitivelyAndKeepsOrder() {
        List<String> known = List.of("Bob", "jake", "Silas");
        assertEquals(List.of("jake", "Silas"), RosterPresencePolicy.restorable(known, Set.of("bob", "rotiwokeman")));
        assertEquals(List.of("Bob", "Silas"), RosterPresencePolicy.restorable(known, Set.of("jake")));
    }

    @Test
    void allPresentGivesEmptyListSoOnlyCreateRemains() {
        assertTrue(RosterPresencePolicy.restorable(List.of("Bob", "Jake"), Set.of("bob", "jake")).isEmpty());
    }

    @Test
    void nullsAndBlanksAreTolerated() {
        assertTrue(RosterPresencePolicy.restorable(null, Set.of("bob")).isEmpty());
        assertEquals(List.of("Bob"), RosterPresencePolicy.restorable(Arrays.asList("Bob", null, " "), null));
        assertEquals(List.of("Bob"), RosterPresencePolicy.restorable(List.of("Bob"), Set.of()));
    }

    @Test
    void presentNamesAreMatchedAfterTrim() {
        assertEquals(List.of(), RosterPresencePolicy.restorable(List.of(" Jake "), Set.of(RosterPresencePolicy.normalize("JAKE "))));
    }

    @Test
    void anyPresentDetectsAKnownBot() {
        assertTrue(RosterPresencePolicy.anyPresent(List.of("Bob", "Jake"), Set.of("jake")));
        assertFalse(RosterPresencePolicy.anyPresent(List.of("Bob", "Jake"), Set.of("rotiwokeman")));
        assertFalse(RosterPresencePolicy.anyPresent(List.of("Bob"), Set.of()));
        assertFalse(RosterPresencePolicy.anyPresent(null, Set.of("bob")));
        assertFalse(RosterPresencePolicy.anyPresent(List.of("Bob"), null));
    }
}
