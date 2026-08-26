package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the scene-line parse: speaker-tagged plain lines against the roster, hard caps,
 * unknown speakers and narration dropped, zero survivors = MALFORMED.
 */
class SoulGroupResponseValidatorTest {

    private final SoulGroupResponseValidator validator = new SoulGroupResponseValidator();
    private static final List<String> ROSTER = List.of("Jake", "Sara");

    @Test
    void happyParsePreservesSpeakerOrderAndIndices() {
        var parse = validator.parse("Jake: I say we mine tonight.\nSara: Too dangerous.\nJake: Fine, fishing then.", ROSTER);
        assertTrue(parse.accepted());
        assertEquals(3, parse.lines().size());
        assertEquals(0, parse.lines().get(0).participantIndex());
        assertEquals("I say we mine tonight.", parse.lines().get(0).text());
        assertEquals(1, parse.lines().get(1).participantIndex());
        assertEquals(0, parse.lines().get(2).participantIndex());
    }

    @Test
    void unknownSpeakerAndNarrationLinesAreDropped() {
        var parse = validator.parse(
                "The two look at each other.\nVillager: hello!\nJake: Who said that?", ROSTER);
        assertTrue(parse.accepted());
        assertEquals(1, parse.lines().size());
        assertEquals(0, parse.lines().get(0).participantIndex());
    }

    @Test
    void speakerMatchIsCaseAndPunctuationInsensitive() {
        var parse = validator.parse("jake: hi\n**Sara**: hello", ROSTER);
        assertTrue(parse.accepted());
        assertEquals(2, parse.lines().size());
        assertEquals(1, parse.lines().get(1).participantIndex());
    }

    @Test
    void perBotCapDropsTheThirdLineForThatBot() {
        var parse = validator.parse(
                "Jake: one\nJake: two\nSara: hi\nJake: three", ROSTER);
        assertTrue(parse.accepted());
        assertEquals(3, parse.lines().size());
        assertEquals("hi", parse.lines().get(2).text());
    }

    @Test
    void sceneCapDropsTheTail() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            raw.append(i % 2 == 0 ? "Jake" : "Sara").append(": line ").append(i).append('\n');
        }
        // 8 candidate lines, per-bot cap trims to 2+2=4 first, so the scene cap never binds here;
        // use 4 speakers' worth by allowing 2 each — with a 2-name roster max survivors = 4.
        var parse = validator.parse(raw.toString(), ROSTER);
        assertTrue(parse.accepted());
        assertEquals(4, parse.lines().size());
    }

    @Test
    void banterSceneCapBindsAtFourLines() {
        List<String> roster = List.of("A", "B", "C", "D");
        StringBuilder raw = new StringBuilder();
        for (String name : roster) {
            raw.append(name).append(": one\n").append(name).append(": two\n");
        }
        var parse = validator.parse(raw.toString(), roster, SoulGroupTypes.BANTER_MAX_SCENE_LINES);
        assertTrue(parse.accepted());
        assertEquals(4, parse.lines().size());
    }

    @Test
    void sceneCapBindsWithALargerRoster() {
        List<String> roster = List.of("A", "B", "C", "D");
        StringBuilder raw = new StringBuilder();
        for (String name : roster) {
            raw.append(name).append(": one\n").append(name).append(": two\n");
        }
        var parse = validator.parse(raw.toString(), roster); // 8 survive per-bot caps
        assertTrue(parse.accepted());
        assertEquals(SoulGroupTypes.MAX_SCENE_LINES, parse.lines().size());
    }

    @Test
    void overlongLineIsTruncatedAtASentenceBoundaryWhenOneExists() {
        String sentence = "This is a sentence that runs on for a while to build length. ";
        String raw = "Jake: " + sentence.repeat(8); // ~496 chars
        var parse = validator.parse(raw, ROSTER);
        assertTrue(parse.accepted());
        String text = parse.lines().get(0).text();
        assertTrue(text.length() <= SoulGroupTypes.MAX_LINE_CHARS);
        assertTrue(text.endsWith("."), "expected sentence-boundary truncation, got: ..." + text.substring(text.length() - 12));
    }

    @Test
    void thinkBlocksAreStrippedBeforeParsing() {
        var parse = validator.parse("<think>Jake: fake plan</think>Jake: real line", ROSTER);
        assertTrue(parse.accepted());
        assertEquals(1, parse.lines().size());
        assertEquals("real line", parse.lines().get(0).text());
    }

    @Test
    void zeroSurvivingLinesIsMalformed() {
        var parse = validator.parse("nothing tagged here\nVillager: nope", ROSTER);
        assertFalse(parse.accepted());
        assertEquals(SoulTypes.FailureCode.MALFORMED, parse.failureCode());
        assertFalse(parse.reason().isEmpty());
    }

    @Test
    void blankAndNullInputAreMalformed() {
        assertFalse(validator.parse("", ROSTER).accepted());
        assertFalse(validator.parse(null, ROSTER).accepted());
    }
}
