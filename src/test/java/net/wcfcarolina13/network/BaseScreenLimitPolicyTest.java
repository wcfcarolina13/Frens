package net.wcfcarolina13.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseScreenLimitPolicyTest {

    // ── Label cap ───────────────────────────────────────────────────────────

    @Test
    void labelCapMatchesTheZoneWandRule() {
        assertEquals(32, BaseScreenLimitPolicy.MAX_LABEL_CHARS);
    }

    @Test
    void shortAndNullLabelsPassThroughUnchanged() {
        assertNull(BaseScreenLimitPolicy.capLabel(null));
        String short32 = "a".repeat(32);
        assertSame(short32, BaseScreenLimitPolicy.capLabel(short32));
        assertEquals("", BaseScreenLimitPolicy.capLabel(""));
        assertEquals("Home Base", BaseScreenLimitPolicy.capLabel("Home Base"));
    }

    @Test
    void longLabelsAreCutToThirtyTwoChars() {
        String huge = "x".repeat(32767);
        assertEquals("x".repeat(32), BaseScreenLimitPolicy.capLabel(huge));
        assertEquals("a".repeat(32), BaseScreenLimitPolicy.capLabel("a".repeat(33)));
    }

    // ── List fitting ────────────────────────────────────────────────────────

    @Test
    void emptyListAlwaysFits() {
        assertEquals(0, BaseScreenLimitPolicy.entriesThatFit(List.of(), 2));
        assertEquals(0, BaseScreenLimitPolicy.entriesThatFit(null, 0));
    }

    @Test
    void countsBracketsAndCommasLikeAJsonArray() {
        // "[aaaa,bbbb,cccc]" is 16 chars: 2 brackets + 12 payload + 2 commas.
        List<Integer> three = List.of(4, 4, 4);
        assertEquals(3, BaseScreenLimitPolicy.entriesThatFit(three, 16));
        assertEquals(2, BaseScreenLimitPolicy.entriesThatFit(three, 15));
        assertEquals(2, BaseScreenLimitPolicy.entriesThatFit(three, 11));
        assertEquals(1, BaseScreenLimitPolicy.entriesThatFit(three, 10));
        assertEquals(1, BaseScreenLimitPolicy.entriesThatFit(three, 6));
        assertEquals(0, BaseScreenLimitPolicy.entriesThatFit(three, 5));
    }

    @Test
    void dropsFromTheEndNotTheMiddle() {
        // A huge second entry blocks itself and everything after it, keeping the first.
        assertEquals(1, BaseScreenLimitPolicy.entriesThatFit(List.of(10, 40000, 10),
                BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS));
    }

    @Test
    void aSingleOversizedEntryYieldsAnEmptyArray() {
        assertEquals(0, BaseScreenLimitPolicy.entriesThatFit(List.of(BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS),
                BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS));
        assertEquals(1, BaseScreenLimitPolicy.entriesThatFit(List.of(BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS - 2),
                BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS));
    }

    @Test
    void nullAndNegativeLengthsCountAsEmptyEntries() {
        java.util.List<Integer> odd = java.util.Arrays.asList(null, -5, 3);
        // "[,,ccc]" = 2 + 0 + 1 + 0 + 1 + 3 = 7
        assertEquals(3, BaseScreenLimitPolicy.entriesThatFit(odd, 7));
        assertEquals(2, BaseScreenLimitPolicy.entriesThatFit(odd, 6));
    }

    @Test
    void realisticListStaysUnderTheCodecCeiling() {
        // 200 entries of a typical 120-char DTO: 2 + 200*120 + 199 = 24201 chars, all kept.
        List<Integer> typical = java.util.Collections.nCopies(200, 120);
        assertEquals(200, BaseScreenLimitPolicy.entriesThatFit(typical, BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS));
        // 300 of them (36301 chars) do not fit: 2 + n*121 - 1 <= 32767 gives n = 270.
        List<Integer> many = java.util.Collections.nCopies(300, 120);
        assertEquals(270, BaseScreenLimitPolicy.entriesThatFit(many, BaseScreenLimitPolicy.MAX_LIST_JSON_CHARS));
    }

    // ── Write throttle ──────────────────────────────────────────────────────

    @Test
    void writeThrottleAllowsTheBurstThenOnePerRefill() {
        BotActionPolicy.Throttle t = BaseScreenLimitPolicy.newWriteThrottle();
        long now = 10_000L;
        for (int i = 0; i < BaseScreenLimitPolicy.WRITE_THROTTLE_CAPACITY; i++) {
            assertTrue(t.tryAcquire(now), "burst click " + i);
        }
        assertFalse(t.tryAcquire(now));
        long refill = BaseScreenLimitPolicy.WRITE_THROTTLE_REFILL_MS;
        assertTrue(t.tryAcquire(now + refill));
        assertFalse(t.tryAcquire(now + refill));
        // Just short of the next refill: still dry.
        assertFalse(t.tryAcquire(now + 2 * refill - 1));
        assertTrue(t.tryAcquire(now + 3 * refill));
    }

    @Test
    void writeThrottleNeverRefillsPastItsOwnCapacity() {
        BotActionPolicy.Throttle t = BaseScreenLimitPolicy.newWriteThrottle();
        assertTrue(t.tryAcquire(0L));
        // A long idle refills to the capacity of 3, not to the bot-action bucket's 20.
        long later = 1_000_000L;
        for (int i = 0; i < BaseScreenLimitPolicy.WRITE_THROTTLE_CAPACITY; i++) {
            assertTrue(t.tryAcquire(later), "refilled click " + i);
        }
        assertFalse(t.tryAcquire(later));
    }

    @Test
    void writeThrottleIsStricterThanTheBotActionBucket() {
        assertTrue(BaseScreenLimitPolicy.WRITE_THROTTLE_CAPACITY < BotActionPolicy.THROTTLE_CAPACITY);
        assertTrue(BaseScreenLimitPolicy.WRITE_THROTTLE_REFILL_MS > BotActionPolicy.THROTTLE_REFILL_MS);
    }

    @Test
    void defaultThrottleKeepsTheBotActionConstants() {
        BotActionPolicy.Throttle t = new BotActionPolicy.Throttle();
        for (int i = 0; i < BotActionPolicy.THROTTLE_CAPACITY; i++) {
            assertTrue(t.tryAcquire(0L));
        }
        assertFalse(t.tryAcquire(0L));
        assertTrue(t.tryAcquire(BotActionPolicy.THROTTLE_REFILL_MS));
    }

    @Test
    void degenerateThrottleArgumentsAreClampedToOneTokenAndOneMillisecond() {
        BotActionPolicy.Throttle t = new BotActionPolicy.Throttle(0, 0L);
        assertTrue(t.tryAcquire(0L));
        assertFalse(t.tryAcquire(0L));
        assertTrue(t.tryAcquire(1L));
    }
}
