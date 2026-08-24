package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulPlayerActivityTest {

    @Test
    void describeJoinsStatesAndRecentAction() {
        String described = SoulPlayerActivity.describe(
                List.of("sneaking", "holding a torch aloft"), "broke Stone 4s ago");
        assertEquals("sneaking, holding a torch aloft; broke Stone 4s ago", described);
    }

    @Test
    void describeWithNoActivityIsEmpty() {
        assertEquals("", SoulPlayerActivity.describe(List.of(), ""));
    }

    @Test
    void describeStatesOnly() {
        assertEquals("swimming", SoulPlayerActivity.describe(List.of("swimming"), ""));
    }

    @Test
    void trackerReportsRecentActionsInsideWindowOnly() {
        UUID player = UUID.randomUUID();
        SoulPlayerActivity.noteBlockBreak(player, "Stone", 10_000L);

        assertEquals("broke Stone 4s ago",
                SoulPlayerActivity.recentAction(player, 14_000L).orElse(""));
        assertTrue(SoulPlayerActivity.recentAction(
                player, 10_000L + SoulPlayerActivity.ACTION_WINDOW_MS + 1).isEmpty());
    }

    @Test
    void trackerPrefersNewestAction() {
        UUID player = UUID.randomUUID();
        SoulPlayerActivity.noteBlockBreak(player, "Stone", 10_000L);
        SoulPlayerActivity.noteAttack(player, "Zombie", 12_000L);

        assertEquals("attacked a Zombie 2s ago",
                SoulPlayerActivity.recentAction(player, 14_000L).orElse(""));
    }
}
