package net.wcfcarolina13.GameAI.souls;

import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for Phase 3d novelty rejection — no net.minecraft types, no I/O. */
class SoulNoveltyPolicyTest {

    /** Same reflection trick as SoulFoundationTest: a real ManualConfig without save()/load(). */
    private static ManualConfig newRealConfig() throws Exception {
        Constructor<ManualConfig> constructor = ManualConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static String norm(String text) {
        return SoulNoveltyPolicy.normalise(text);
    }

    @Test
    void toggleDefaultsOff() throws Exception {
        assertFalse(newRealConfig().isSoulNoveltyRejectionEnabled());
    }

    @Test
    void exactRepeatRejected() {
        String line = "We should head back before the sun goes down.";
        Optional<String> reason = SoulNoveltyPolicy.rejectReason(line, List.of(norm(line)));
        assertEquals(Optional.of(SoulNoveltyPolicy.REASON_EXACT), reason);
    }

    @Test
    void punctuationAndCaseDoNotMakeALineNovel() {
        Optional<String> reason = SoulNoveltyPolicy.rejectReason("Aye, right!", List.of(norm("aye right")));
        assertEquals(Optional.of(SoulNoveltyPolicy.REASON_EXACT), reason);
    }

    @Test
    void longParaphraseRejectedOnTrigramOverlap() {
        String remembered = "we should gather the copper ore and haul it back to the base camp tonight";
        String candidate = "We should gather the copper ore and haul it back to the base camp again.";
        assertTrue(SoulNoveltyPolicy.contentWords(candidate).size() >= SoulNoveltyPolicy.MIN_CONTENT_WORDS);
        assertEquals(Optional.of(SoulNoveltyPolicy.REASON_TRIGRAM),
                SoulNoveltyPolicy.rejectReason(candidate, List.of(norm(remembered))));
    }

    @Test
    void shortParaphraseKept() {
        // The laconic-persona case: "Aye." and "Aye, right." must both be sayable.
        assertTrue(SoulNoveltyPolicy.contentWords("Aye, right.").size() < SoulNoveltyPolicy.MIN_CONTENT_WORDS);
        assertEquals(Optional.empty(), SoulNoveltyPolicy.rejectReason("Aye, right.", List.of(norm("Aye."))));
    }

    @Test
    void shortExactRepeatRejected() {
        assertEquals(Optional.of(SoulNoveltyPolicy.REASON_EXACT),
                SoulNoveltyPolicy.rejectReason("Aye.", List.of(norm("Aye."))));
    }

    @Test
    void overlapBelowThresholdKept() {
        String remembered = "the copper ore near the ravine looks rich enough to spend a morning on";
        String candidate = "I saw a wolf pack circling the northern ridge just before dusk settled in";
        assertEquals(Optional.empty(), SoulNoveltyPolicy.rejectReason(candidate, List.of(norm(remembered))));
    }

    @Test
    void trigramOverlapUsesMinDenominator() {
        List<String> shortWords = SoulNoveltyPolicy.contentWords("copper ore ravine camp");
        List<String> longWords = SoulNoveltyPolicy.contentWords(
                "copper ore ravine camp tonight after we finish hauling the spruce logs home");
        // Every trigram of the shorter line appears in the longer one -> containment 1.0.
        assertEquals(1.0d, SoulNoveltyPolicy.trigramOverlap(
                SoulNoveltyPolicy.trigrams(shortWords), SoulNoveltyPolicy.trigrams(longWords)), 1e-9);
    }

    @Test
    void trigramsEmptyBelowThreeContentWords() {
        assertTrue(SoulNoveltyPolicy.trigrams(SoulNoveltyPolicy.contentWords("Aye, right.")).isEmpty());
    }

    @Test
    void ringEvictsOldestAtCapacityAndFirstLineBecomesNovelAgain() {
        SoulNoveltyPolicy.Ring ring = new SoulNoveltyPolicy.Ring();
        String first = norm("line number zero");
        ring.remember(first);
        for (int i = 1; i < SoulNoveltyPolicy.RING_SIZE; i++) {
            ring.remember(norm("line number " + i));
        }
        assertEquals(SoulNoveltyPolicy.RING_SIZE, ring.size());
        assertTrue(ring.snapshot().contains(first));

        ring.remember(norm("line number twelve"));
        assertEquals(SoulNoveltyPolicy.RING_SIZE, ring.size());
        assertFalse(ring.snapshot().contains(first));
        assertEquals(Optional.empty(), SoulNoveltyPolicy.rejectReason("line number zero", ring.snapshot()));
    }

    @Test
    void sameLineTwiceInOneSceneDropsTheSecond() {
        List<SoulNoveltyPolicy.Verdict> verdicts = SoulNoveltyPolicy.filter(
                List.of("Storm's coming.", "Storm's coming."),
                List.of(List.of(), List.of()));
        assertTrue(verdicts.get(0).kept());
        assertFalse(verdicts.get(1).kept());
        assertEquals(SoulNoveltyPolicy.REASON_EXACT, verdicts.get(1).reason());
    }

    @Test
    void allLinesDroppedYieldsNoKeptVerdicts() {
        List<String> history = List.of(norm("Storm's coming."), norm("Aye."));
        List<SoulNoveltyPolicy.Verdict> verdicts = SoulNoveltyPolicy.filter(
                List.of("Storm's coming.", "Aye."), List.of(history, history));
        assertEquals(2, verdicts.size());
        assertTrue(verdicts.stream().noneMatch(SoulNoveltyPolicy.Verdict::kept));
    }

    @Test
    void filterKeepsNovelLinesAndPreservesOrder() {
        List<SoulNoveltyPolicy.Verdict> verdicts = SoulNoveltyPolicy.filter(
                List.of("Storm's coming.", "I'll bank the fire.", "Storm's coming."),
                List.of(List.of(), List.of(), List.of()));
        assertEquals(List.of(0, 1, 2), verdicts.stream().map(SoulNoveltyPolicy.Verdict::index).toList());
        assertEquals(List.of(true, true, false),
                verdicts.stream().map(SoulNoveltyPolicy.Verdict::kept).toList());
    }

    @Test
    void shortRememberedFragmentInsideLongCandidateIsKept() {
        // "bank the fire lads" is 3 content words -> 1 trigram, below MIN_TRIGRAMS. Containment
        // would score it 1.0 against any long line quoting it, so the fuzzy path must stand down.
        String remembered = "bank the fire lads";
        String candidate = "I'll bank the fire lads before we head down the ravine tonight";
        assertTrue(SoulNoveltyPolicy.trigrams(SoulNoveltyPolicy.contentWords(remembered)).size()
                < SoulNoveltyPolicy.MIN_TRIGRAMS);
        assertTrue(SoulNoveltyPolicy.contentWords(candidate).size() >= SoulNoveltyPolicy.MIN_CONTENT_WORDS);
        assertEquals(Optional.empty(), SoulNoveltyPolicy.rejectReason(candidate, List.of(norm(remembered))));
    }

    @Test
    void sixContentWordRememberedLineContainedInLongCandidateStillDropped() {
        String remembered = "bank the fire before the wolves reach the camp";
        String candidate = "Lads bank the fire before the wolves reach the camp tonight and sleep light";
        assertTrue(SoulNoveltyPolicy.trigrams(SoulNoveltyPolicy.contentWords(remembered)).size()
                >= SoulNoveltyPolicy.MIN_TRIGRAMS);
        assertEquals(Optional.of(SoulNoveltyPolicy.REASON_TRIGRAM),
                SoulNoveltyPolicy.rejectReason(candidate, List.of(norm(remembered))));
    }

    @Test
    void sameSpeakerParaphraseInOneSceneDropsTheSecond() {
        List<String> lines = List.of(
                "We should haul the copper ore back to the base camp tonight",
                "We should haul the copper ore back to the base camp again");
        List<SoulNoveltyPolicy.Verdict> verdicts = SoulNoveltyPolicy.filter(
                lines, List.of(List.of(), List.of()), List.of("bob", "bob"));
        assertTrue(verdicts.get(0).kept());
        assertFalse(verdicts.get(1).kept());
        assertEquals(SoulNoveltyPolicy.REASON_TRIGRAM, verdicts.get(1).reason());
    }

    @Test
    void crossSpeakerParaphraseInOneSceneIsKept() {
        List<String> lines = List.of(
                "We should haul the copper ore back to the base camp tonight",
                "We should haul the copper ore back to the base camp again");
        List<SoulNoveltyPolicy.Verdict> verdicts = SoulNoveltyPolicy.filter(
                lines, List.of(List.of(), List.of()), List.of("bob", "silas"));
        assertTrue(verdicts.get(0).kept());
        assertTrue(verdicts.get(1).kept());
    }

    @Test
    void crossSpeakerExactEchoInOneSceneStillDropped() {
        List<SoulNoveltyPolicy.Verdict> verdicts = SoulNoveltyPolicy.filter(
                List.of("Storm's coming.", "Storm's coming."),
                List.of(List.of(), List.of()), List.of("bob", "silas"));
        assertTrue(verdicts.get(0).kept());
        assertFalse(verdicts.get(1).kept());
        assertEquals(SoulNoveltyPolicy.REASON_EXACT, verdicts.get(1).reason());
    }

    @Test
    void emptyHistoryNeverRejects() {
        assertEquals(Optional.empty(), SoulNoveltyPolicy.rejectReason("anything at all", List.of()));
    }
}
