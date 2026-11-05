package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulThreadClosePolicyTest {

    private static final UUID BOB = UUID.randomUUID();

    private static SoulTypes.OpenThread open(String q) {
        return new SoulTypes.OpenThread(BOB, q, 0L, false);
    }

    private static SoulTypes.OpenThread expired(String q) {
        return new SoulTypes.OpenThread(BOB, q, 0L, true);
    }

    @Test
    void wordForWordAndCaseOrPunctuationChangesMatch() {
        assertTrue(SoulThreadClosePolicy.matches("Did you find the iron?", "Did you find the iron?"));
        assertTrue(SoulThreadClosePolicy.matches("Did you find the iron?", "did you find the IRON"));
        assertTrue(SoulThreadClosePolicy.matches("Did you find the iron?", "\"Did you find the iron?\""));
        assertTrue(SoulThreadClosePolicy.matches("Did you find the iron?", "Did you find the iron? (asked by Bob)"),
                "a quote with a short addition still names the question");
    }

    @Test
    void paraphraseAndVagueClaimsDoNotMatch() {
        assertFalse(SoulThreadClosePolicy.matches("Did you find the iron?", "Have you found any iron?"));
        assertFalse(SoulThreadClosePolicy.matches("Did you find the iron?", "iron"));
        assertFalse(SoulThreadClosePolicy.matches("Did you find the iron?", "the question"));
        assertFalse(SoulThreadClosePolicy.matches("Where?", "Where?  That is what Bob asked about the cave"),
                "a short question inside a long claim is not enough");
        assertFalse(SoulThreadClosePolicy.matches("", "anything"));
        assertFalse(SoulThreadClosePolicy.matches("Did you find the iron?", "  ?! "));
    }

    @Test
    void shortQuestionsNeedAnExactMatch() {
        assertTrue(SoulThreadClosePolicy.matches("Why?", "why"));
        assertFalse(SoulThreadClosePolicy.matches("Why?", "why not"));
    }

    @Test
    void eachClaimClosesOneOpenThreadAndLeavesTheRest() {
        List<SoulTypes.OpenThread> threads = List.of(open("Did you find the iron?"),
                open("Can we build a house?"), open("Did you find the iron?"));
        List<SoulTypes.OpenThread> left = SoulThreadClosePolicy.remaining(threads, List.of("did you find the iron"));
        assertEquals(List.of(threads.get(1), threads.get(2)), left);
    }

    @Test
    void expiredThreadsAreLeftForTheSeed() {
        List<SoulTypes.OpenThread> threads = List.of(expired("Did you find the iron?"));
        assertSame(threads, SoulThreadClosePolicy.remaining(threads, List.of("Did you find the iron?")));
    }

    @Test
    void noMatchReturnsTheSameList() {
        List<SoulTypes.OpenThread> threads = List.of(open("Did you find the iron?"));
        assertSame(threads, SoulThreadClosePolicy.remaining(threads, List.of("Can we build a house?")));
        assertSame(threads, SoulThreadClosePolicy.remaining(threads, List.of()));
    }

    @Test
    void closeThreadsKeepsThePlayerStance() {
        SoulTypes.Stance stance = new SoulTypes.Stance(5, 3, 1);
        SoulTypes.SoulMind mind = new SoulTypes.SoulMind(1, stance,
                List.of(open("Did you find the iron?"), open("Can we build a house?")),
                List.of(), Set.of(), 0L, 2, -1);
        SoulTypes.SoulMind after = SoulMindOps.closeThreads(mind, List.of("Did you find the iron?"));
        assertEquals(List.of(open("Can we build a house?")), after.threads());
        assertEquals(stance, after.playerStance(), "no trust or curiosity move from a model close");
        assertSame(mind, SoulMindOps.closeThreads(mind, List.of("Something else entirely?")));
    }
}
