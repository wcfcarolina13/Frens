package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the deterministic decision about which overheard lines are worth an LLM call
 * (local-chat spec §5.1). Every weight and the threshold are asserted here, so retuning from
 * field-test logs is a visible, reviewed change.
 */
class SoulLocalSalienceTest {

    @Test
    void hardRejectsCatchNoiseBeforeAnyScoring() {
        assertTrue(SoulLocalSalience.hardReject("", null));
        assertTrue(SoulLocalSalience.hardReject("   ", null));
        assertTrue(SoulLocalSalience.hardReject("ok", null), "too short");
        assertTrue(SoulLocalSalience.hardReject("a b c", null), "five chars, under the minimum");
        assertTrue(SoulLocalSalience.hardReject("lets go to the ravine", "lets go to the ravine"),
                "duplicate of previous line");
        assertFalse(SoulLocalSalience.hardReject("lets go to the ravine", "something else"));
    }

    @Test
    void indirectBotMentionScoresButLeadingAddressDoesNot() {
        // "Jake go mine" is an address the DM router already consumed — it must score 0 here.
        assertEquals(0, SoulLocalSalience.score("Jake go mine", "Jake", "", ""));
        // Same name, mid-sentence: a genuine overheard mention.
        assertEquals(3, SoulLocalSalience.score("that was Jake fault", "Jake", "", ""));
    }

    @Test
    void statedIntentScoresOnItsOwn() {
        assertEquals(2, SoulLocalSalience.score("we should rest", "Jake", "", ""));
        assertEquals(0, SoulLocalSalience.score("anyone got flint", "Jake", "", ""),
                "no signal, three words");
    }

    @Test
    void questionAndLengthAndTaskOverlapStack() {
        // question (+2) + six-or-more words (+1) + task overlap on "fishing" (+2) = 5.
        assertEquals(5, SoulLocalSalience.score(
                "do you ever get bored of fishing?", "Jake", "skill:fishing", ""));
    }

    @Test
    void recentEventSubjectOverlapCounts() {
        // overlap (+2) + six words (+1) = 3.
        assertEquals(3, SoulLocalSalience.score(
                "that creeper came out of nowhere", "Jake", "", "creeper"));
    }

    @Test
    void coordinateSpamIsPenalisedBelowThreshold() {
        assertEquals(0, SoulLocalSalience.score("128 64 -512 waypoint here", "Jake", "", ""));
    }

    @Test
    void thresholdBoundaryIsExact() {
        // mention (+3) only = 3, below threshold.
        assertEquals(3, SoulLocalSalience.score("that was Jake fault", "Jake", "", ""));
        // mention (+3) + seven words (+1) = 4, exactly at threshold.
        assertEquals(4, SoulLocalSalience.score(
                "i think that was Jake being careless", "Jake", "", ""));
        assertTrue(SoulLocalSalience.score("i think that was Jake being careless", "Jake", "", "")
                >= SoulLocalSalience.THRESHOLD);
    }
}
