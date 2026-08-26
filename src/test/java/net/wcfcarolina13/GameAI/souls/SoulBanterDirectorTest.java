package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the deterministic banter-eligibility rules: fixed veto ordering, cooldown bands,
 * the post-capture danger veto, and the chat-quiet signal.
 */
class SoulBanterDirectorTest {

    @Test
    void firstVetoReportsGatesInSpecOrder() {
        // All gates pass -> eligible (null veto).
        assertNull(SoulBanterDirector.firstVeto(true, true, true, true, true, true, true, 2, true));
        // Each gate failing alone names itself.
        assertEquals("disabled", SoulBanterDirector.firstVeto(false, true, true, true, true, true, true, 2, true));
        assertEquals("pipeline", SoulBanterDirector.firstVeto(true, false, true, true, true, true, true, 2, true));
        assertEquals("cooldown", SoulBanterDirector.firstVeto(true, true, false, true, true, true, true, 2, true));
        assertEquals("busy", SoulBanterDirector.firstVeto(true, true, true, false, true, true, true, 2, true));
        assertEquals("muted", SoulBanterDirector.firstVeto(true, true, true, true, false, true, true, 2, true));
        assertEquals("player-not-at-ease", SoulBanterDirector.firstVeto(true, true, true, true, true, false, true, 2, true));
        assertEquals("not-quiet", SoulBanterDirector.firstVeto(true, true, true, true, true, true, false, 2, true));
        assertEquals("roster", SoulBanterDirector.firstVeto(true, true, true, true, true, true, true, 1, true));
        assertEquals("bots-apart", SoulBanterDirector.firstVeto(true, true, true, true, true, true, true, 2, false));
        // Earlier gate wins when two fail.
        assertEquals("cooldown", SoulBanterDirector.firstVeto(true, true, false, false, true, true, true, 0, false));
    }

    @Test
    void cooldownBandsHoldOverManySamples() {
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            long initial = SoulBanterDirector.initialDelayMs(random);
            assertTrue(initial >= 4 * 60_000L && initial <= 8 * 60_000L, "initial=" + initial);
            long next = SoulBanterDirector.nextDelayMs(random);
            assertTrue(next >= 8 * 60_000L && next <= 15 * 60_000L, "next=" + next);
        }
    }

    @Test
    void groundingDangerVeto() {
        assertFalse(SoulBanterDirector.groundingDangerous(calm()));
        assertTrue(SoulBanterDirector.groundingDangerous(withHostiles()));
        assertTrue(SoulBanterDirector.groundingDangerous(inCombat()));
    }

    @Test
    void chatQuietSignalRoundTrip() {
        UUID player = UUID.randomUUID();
        assertEquals(0L, SoulPlayerActivity.lastChatAt(player));
        SoulPlayerActivity.noteChat(player, 12_345L);
        assertEquals(12_345L, SoulPlayerActivity.lastChatAt(player));
        SoulPlayerActivity.clear();
        assertEquals(0L, SoulPlayerActivity.lastChatAt(player));
    }

    private static SoulTypes.SituationSnapshot calm() {
        return SoulTypes.SituationSnapshot.empty();
    }

    private static SoulTypes.SituationSnapshot withHostiles() {
        return new SoulTypes.SituationSnapshot(-1,
                List.of(new SoulTypes.HostileSighting("zombie", "north", 10)),
                List.of(), "", List.of(), false, false, false, "", false,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static SoulTypes.SituationSnapshot inCombat() {
        return new SoulTypes.SituationSnapshot(-1, List.of(), List.of(), "", List.of(),
                false, false, false, "", false,
                true, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
