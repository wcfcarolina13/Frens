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
        assertEquals("roster", SoulBanterDirector.firstVeto(true, true, true, true, true, true, true, 0, true));
        assertEquals("bots-apart", SoulBanterDirector.firstVeto(true, true, true, true, true, true, true, 2, false));
        // Earlier gate wins when two fail.
        assertEquals("cooldown", SoulBanterDirector.firstVeto(true, true, false, false, true, true, true, 0, false));
    }

    @Test
    void cooldownBandsHoldOverManySamples() {
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            long initial = SoulBanterDirector.initialDelayMs(random);
            // 60–150 s: a companion that says nothing for the first five minutes of every
            // session reads as broken — both 2026-08 field sessions ended before the old
            // 4–8 min grace ever elapsed.
            assertTrue(initial >= 60_000L && initial <= 150_000L, "initial=" + initial);
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

    @Test
    void soloRosterIsEligibleAndAlwaysAddressesThePlayer() {
        assertNull(SoulBanterDirector.firstVeto(true, true, true, true, true, true, true, 1, true),
                "one eligible bot is a valid banter roster since the engagement spec");
        Random random = new Random(3);
        for (int i = 0; i < 100; i++) {
            assertTrue(SoulBanterDirector.decideAddressPlayer(1, random),
                    "a solo scene's whole point is speaking to the player");
        }
    }

    @Test
    void groupScenesAddressThePlayerAboutOneTimeInThree() {
        Random random = new Random(9);
        int hits = 0;
        for (int i = 0; i < 3000; i++) {
            if (SoulBanterDirector.decideAddressPlayer(2, random)) {
                hits++;
            }
        }
        assertTrue(hits > 800 && hits < 1200, "expected ~1000/3000, got " + hits);
    }
    @Test
    void scaledCooldownBandsFollowTheMultiplier() {
        Random random = new Random(3);
        for (int i = 0; i < 500; i++) {
            long slow = SoulBanterDirector.nextDelayMs(random, 4.0);
            assertTrue(slow >= 32 * 60_000L && slow <= 60 * 60_000L, "slow=" + slow);
            long fast = SoulBanterDirector.nextDelayMs(random, 0.25);
            assertTrue(fast >= 2 * 60_000L && fast <= 15 * 60_000L / 4, "fast=" + fast);
            long active = SoulBanterDirector.nextActiveDelayMs(random, 1.0);
            assertTrue(active >= 4 * 60_000L && active <= 8 * 60_000L, "active=" + active);
        }
        assertEquals(SoulBanterDirector.nextDelayMs(new Random(9)), SoulBanterDirector.nextDelayMs(new Random(9), 1.0));
        assertEquals(8.0, SoulBanterDirector.multiplier(0), 1e-9);
        assertEquals(0.125, SoulBanterDirector.multiplier(100), 1e-9);
    }

    @Test
    void activeVetoOrderAddsNobodyWorkingAndRelaxesAtEase() {
        assertNull(SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 2, 1, true));
        assertEquals("disabled", SoulBanterDirector.firstActiveVeto(false, true, true, true, true, true, true, 2, 1, true));
        assertEquals("pipeline", SoulBanterDirector.firstActiveVeto(true, false, true, true, true, true, true, 2, 1, true));
        assertEquals("cooldown", SoulBanterDirector.firstActiveVeto(true, true, false, true, true, true, true, 2, 1, true));
        assertEquals("busy", SoulBanterDirector.firstActiveVeto(true, true, true, false, true, true, true, 2, 1, true));
        assertEquals("muted", SoulBanterDirector.firstActiveVeto(true, true, true, true, false, true, true, 2, 1, true));
        assertEquals("player-not-ready", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, false, true, 2, 1, true));
        assertEquals("not-quiet", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, false, 2, 1, true));
        assertEquals("roster", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 0, 0, true));
        assertEquals("nobody-working", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 2, 0, true));
        assertEquals("bots-apart", SoulBanterDirector.firstActiveVeto(true, true, true, true, true, true, true, 2, 1, false));
        assertEquals(30_000L, SoulBanterDirector.ACTIVE_QUIET_WINDOW_MS);
    }
}
