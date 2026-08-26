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

    @Test
    void forgetEvictsOnlyThatPlayersActivityAndChatRecency() {
        UUID leaver = UUID.randomUUID();
        UUID stayer = UUID.randomUUID();
        SoulPlayerActivity.noteBlockBreak(leaver, "Stone", 10_000L);
        SoulPlayerActivity.noteChat(leaver, 10_000L);
        SoulPlayerActivity.noteBlockBreak(stayer, "Dirt", 10_000L);
        SoulPlayerActivity.noteChat(stayer, 10_000L);

        SoulPlayerActivity.forget(leaver);

        assertTrue(SoulPlayerActivity.recentAction(leaver, 11_000L).isEmpty(),
                "a disconnected player's last action must not survive them");
        assertEquals(0L, SoulPlayerActivity.lastChatAt(leaver),
                "a disconnected player's chat recency must not survive them");
        assertEquals("broke Dirt 1s ago",
                SoulPlayerActivity.recentAction(stayer, 11_000L).orElse(""));
        assertEquals(10_000L, SoulPlayerActivity.lastChatAt(stayer));
    }

    @Test
    void forgetIsNullSafe() {
        SoulPlayerActivity.forget(null);
    }

    @Test
    void clearDropsEveryPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SoulPlayerActivity.noteAttack(a, "Zombie", 10_000L);
        SoulPlayerActivity.noteChat(b, 10_000L);

        SoulPlayerActivity.clear();

        assertTrue(SoulPlayerActivity.recentAction(a, 11_000L).isEmpty());
        assertEquals(0L, SoulPlayerActivity.lastChatAt(b));
    }
}
