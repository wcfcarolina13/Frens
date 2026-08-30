package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulSpeechActTest {

    @Test
    void recallAndWorryNeedTheirMaterial() {
        for (int i = 0; i < 200; i++) {
            SoulSpeechAct act = SoulSpeechAct.pick(false, false, List.of(), new Random(i));
            assertTrue(act != SoulSpeechAct.RECALL && act != SoulSpeechAct.WORRY, act.name());
        }
    }

    @Test
    void recentActsAreSkippedWhileAnythingElseIsEligible() {
        List<SoulSpeechAct> recent = List.of(SoulSpeechAct.OBSERVE, SoulSpeechAct.ASK, SoulSpeechAct.JOKE, SoulSpeechAct.TEASE);
        for (int i = 0; i < 200; i++) {
            SoulSpeechAct act = SoulSpeechAct.pick(true, true, recent, new Random(i));
            assertFalse(recent.contains(act), act.name());
        }
        // Nothing fresh left → falls back to the eligible pool rather than failing.
        SoulSpeechAct fallback = SoulSpeechAct.pick(false, false,
                List.of(SoulSpeechAct.OBSERVE, SoulSpeechAct.ASK, SoulSpeechAct.TEASE, SoulSpeechAct.PLAN, SoulSpeechAct.JOKE),
                new Random(1));
        assertTrue(fallback.eligible(false, false));
    }

    @Test
    void directivesAddressThePlayerOnlyWhenSolo() {
        assertEquals("ask Roti something about", SoulSpeechAct.ASK.directive(true, "Roti"));
        assertEquals("one of you asks the other something real about", SoulSpeechAct.ASK.directive(false, "Roti"));
    }
}
