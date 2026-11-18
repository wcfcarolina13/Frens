package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterVisibilityPolicyTest {

    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    private static final Map<String, String> OWNERS = Map.of(
            "Ava", ME,
            "Bob", OTHER,
            "Cy", ME.toUpperCase());

    @Test
    void nonAdminSeesOnlyOwnCompanionsInOrder() {
        List<String> seen = RosterVisibilityPolicy.visible(List.of("Ava", "Bob", "Cy", "Dee"), OWNERS::get, ME, false);
        assertEquals(List.of("Ava", "Cy"), seen);
    }

    @Test
    void adminSeesEveryCompanionIncludingUnowned() {
        List<String> seen = RosterVisibilityPolicy.visible(List.of("Ava", "Bob", "Cy", "Dee"), OWNERS::get, ME, true);
        assertEquals(List.of("Ava", "Bob", "Cy", "Dee"), seen);
    }

    @Test
    void unownedOrBlankViewerIsNeverOwned() {
        assertFalse(RosterVisibilityPolicy.ownedBy(null, ME));
        assertFalse(RosterVisibilityPolicy.ownedBy(" ", ME));
        assertFalse(RosterVisibilityPolicy.ownedBy(ME, null));
        assertFalse(RosterVisibilityPolicy.ownedBy(ME, ""));
        assertTrue(RosterVisibilityPolicy.ownedBy(" " + ME + " ", ME));
    }

    @Test
    void nullsAndBlankAliasesAreSkipped() {
        assertEquals(List.of(), RosterVisibilityPolicy.visible(null, OWNERS::get, ME, true));
        assertEquals(List.of("Ava"), RosterVisibilityPolicy.visible(Arrays.asList("Ava", null, " "), null, ME, true));
        assertEquals(List.of(), RosterVisibilityPolicy.visible(List.of("Ava"), null, ME, false));
    }

    @Test
    void otherWorldsOnlyForAdmins() {
        assertEquals(List.of("Eve"), RosterVisibilityPolicy.otherWorldsFor(List.of("Eve"), true));
        assertEquals(List.of(), RosterVisibilityPolicy.otherWorldsFor(List.of("Eve"), false));
        assertEquals(List.of(), RosterVisibilityPolicy.otherWorldsFor(null, true));
    }

    @Test
    void payloadCapDropsOverlongNamesAndStopsAtMax() {
        List<String> many = new ArrayList<>();
        many.add("x".repeat(RosterVisibilityPolicy.MAX_ALIAS_LEN + 1));
        for (int i = 0; i < RosterVisibilityPolicy.MAX_LISTED + 10; i++) {
            many.add("bot" + i);
        }
        List<String> capped = RosterVisibilityPolicy.capForPayload(many);
        assertEquals(RosterVisibilityPolicy.MAX_LISTED, capped.size());
        assertEquals("bot0", capped.get(0));
        assertEquals(List.of(), RosterVisibilityPolicy.capForPayload(null));
    }

    @Test
    void requestsAreThrottledPerWindow() {
        assertTrue(RosterVisibilityPolicy.requestAllowed(0L, 1_000L));
        assertFalse(RosterVisibilityPolicy.requestAllowed(1_000L, 1_000L + RosterVisibilityPolicy.MIN_REQUEST_INTERVAL_MS - 1));
        assertTrue(RosterVisibilityPolicy.requestAllowed(1_000L, 1_000L + RosterVisibilityPolicy.MIN_REQUEST_INTERVAL_MS));
        // A clock that moved backwards never locks a player out.
        assertTrue(RosterVisibilityPolicy.requestAllowed(5_000L, 1_000L));
    }
}
