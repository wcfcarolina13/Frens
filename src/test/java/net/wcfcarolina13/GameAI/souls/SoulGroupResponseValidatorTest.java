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
    void perBotCapDropsTheThirdTurnForThatBot() {
        var parse = validator.parse(
                "Jake: one\nSara: hi\nJake: two\nSara: bye\nJake: three", ROSTER);
        assertTrue(parse.accepted());
        assertEquals(4, parse.lines().size());
        assertEquals("bye", parse.lines().get(3).text());
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
        for (String word : List.of("one", "two")) {
            for (String name : roster) {
                raw.append(name).append(": ").append(word).append('\n');
            }
        }
        var parse = validator.parse(raw.toString(), roster); // 8 turns survive per-bot caps
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

    // === Solo-roster leniency (2026-08-29): llama3.2:3b answered a roster-of-one banter
    // directive with untagged prose, and the strict "Name: line" grammar rejected every line
    // (outcome=failed:MALFORMED in the field log). With exactly one possible speaker there is
    // no ambiguity to protect against, so untagged lines attribute to that speaker.

    @Test
    void soloRosterAcceptsUntaggedLines() {
        SoulGroupResponseValidator.SceneParse parse = new SoulGroupResponseValidator()
                .parse("Quiet night, huh? Watch the tree line anyway.", List.of("Jake"), 4);
        assertTrue(parse.accepted(), "one speaker means untagged prose is unambiguous");
        assertEquals(1, parse.lines().size());
        assertEquals(0, parse.lines().get(0).participantIndex());
        assertEquals("Quiet night, huh? Watch the tree line anyway.", parse.lines().get(0).text());
    }

    @Test
    void soloRosterStripsAWrongNameTagInsteadOfRejecting() {
        // The shared profile means the model may tag lines with the PROFILE's name ("Jake")
        // while the roster bot is Bob — attribute to the only speaker, tag stripped.
        SoulGroupResponseValidator.SceneParse parse = new SoulGroupResponseValidator()
                .parse("Jake: That cave smells wrong.", List.of("Bob"), 4);
        assertTrue(parse.accepted());
        assertEquals(1, parse.lines().size());
        assertEquals(0, parse.lines().get(0).participantIndex());
        assertEquals("That cave smells wrong.", parse.lines().get(0).text());
    }

    @Test
    void soloRosterStillCapsLines() {
        SoulGroupResponseValidator.SceneParse parse = new SoulGroupResponseValidator()
                .parse("One.\nTwo.\nThree.\nFour.", List.of("Jake"), 4);
        assertTrue(parse.accepted());
        assertEquals(2, parse.lines().size(), "MAX_LINES_PER_BOT still binds a solo speaker");
    }

    @Test
    void multiRosterGrammarIsUnchangedByTheLeniency() {
        SoulGroupResponseValidator.SceneParse parse = new SoulGroupResponseValidator()
                .parse("Just some prose with no speaker tag.", List.of("Jake", "Sara"), 6);
        assertFalse(parse.accepted(), "with 2+ speakers an untagged line is still ambiguous");
    }

    @Test
    void soloRosterDropsModelScaffoldingInsteadOfSpeakingIt() {
        // "Here is the scene:" — a tag-shaped prefix with an EMPTY body used to fall through as
        // untagged prose and be spoken verbatim. A line ending in a bare colon is scaffolding.
        assertFalse(new SoulGroupResponseValidator()
                .parse("Here is the scene:", List.of("Jake"), 4).accepted());
        // Meta tags are narration markers, not wrong NAMES — dropped, never repaired.
        SoulGroupResponseValidator.SceneParse note = new SoulGroupResponseValidator()
                .parse("Note: this is narration\nJake: actual dialogue", List.of("Jake"), 4);
        assertTrue(note.accepted());
        assertEquals(1, note.lines().size());
        assertEquals("actual dialogue", note.lines().get(0).text());
        // Punctuation-only lines are noise.
        assertFalse(new SoulGroupResponseValidator()
                .parse(":", List.of("Jake"), 4).accepted());
    }

    @Test
    void soloRosterDropsLinesWrittenForTheOwnerInsteadOfSpeakingThem() {
        // Field log 2026-08-29 13:08: llama3.2:3b answered a solo banter directive with a
        // two-speaker exchange — the bot's question, then the PLAYER's answer. The wrong-tag
        // repair handed the player's line to the bot, so Jake visibly talked to himself.
        // A line tagged with the owner's name (or an abbreviation of it) is a line written for
        // the player, which the scene contract forbids — dropped, never repaired.
        SoulGroupResponseValidator.SceneParse parse = new SoulGroupResponseValidator()
                .parse("Jake: Morning, Roti. Any signs of movement?\nRoti: Not yet. Just the usual birdsong.",
                        List.of("Jake"), 4, "RotiWokeman");
        assertTrue(parse.accepted());
        assertEquals(1, parse.lines().size(), "the owner's answer must not be spoken by the bot");
        assertEquals("Morning, Roti. Any signs of movement?", parse.lines().get(0).text());

        SoulGroupResponseValidator.SceneParse exact = new SoulGroupResponseValidator()
                .parse("RotiWokeman: I'm fine.\nJake: Good.", List.of("Jake"), 4, "RotiWokeman");
        assertEquals(1, exact.lines().size());
        assertEquals("Good.", exact.lines().get(0).text());

        // "Player:" / "Owner:" are scaffolding tags for the same thing.
        SoulGroupResponseValidator.SceneParse player = new SoulGroupResponseValidator()
                .parse("Player: hello?\nJake: Hi.", List.of("Jake"), 4, "RotiWokeman");
        assertEquals(1, player.lines().size());
        assertEquals("Hi.", player.lines().get(0).text());

        // Multi-roster grammar: an owner-tagged line was already dropped (unknown speaker).
        SoulGroupResponseValidator.SceneParse multi = new SoulGroupResponseValidator()
                .parse("Roti: hey\nJake: yo\nSara: sup", List.of("Jake", "Sara"), 6, "RotiWokeman");
        assertEquals(2, multi.lines().size());
    }

    @Test
    void soloMetaTagListCoversTheCommonScaffoldWords() {
        for (String meta : List.of("Note", "Scene", "Narrator", "System", "Output", "Response")) {
            SoulGroupResponseValidator.SceneParse parse = new SoulGroupResponseValidator()
                    .parse(meta + ": should not be spoken", List.of("Bob"), 4);
            assertFalse(parse.accepted(), meta + " must be treated as scaffolding, not a name");
        }
    }
    @Test
    void aPlayerAddressedLineEndsAnAmbientSceneSoNobodyAnswersForThem() {
        // Field 2026-08-29 18:52: Bob greeted Roti, then Jake answered as if he were Roti.
        String raw = "Bob: Morning, Roti. How's the sleep skill going?\nJake: Still a bit rough.\nBob: Hungry.";
        SoulGroupResponseValidator.SceneParse ambient = new SoulGroupResponseValidator()
                .parse(raw, List.of("Jake", "Bob"), 4, "RotiWokeman", true);
        assertEquals(1, ambient.lines().size(), "the scene must stop at the line addressed to Roti");
        assertEquals(1, ambient.lines().get(0).participantIndex());
        // Replies to the player (PLAYER scenes) are all addressed to them — no truncation there.
        SoulGroupResponseValidator.SceneParse reply = new SoulGroupResponseValidator()
                .parse(raw, List.of("Jake", "Bob"), 6, "RotiWokeman", false);
        assertEquals(3, reply.lines().size());
        // Abbreviations need 4+ letters: "Rot" is not the player, "Roti" is.
        assertTrue(SoulGroupResponseValidator.addressesOwner("Nice one, Roti!", "rotiwokeman"));
        assertFalse(SoulGroupResponseValidator.addressesOwner("That rot won't wash out.", "rotiwokeman"));
    }

    @Test
    void consecutiveLinesFromOneSpeakerMergeIntoASingleTurn() {
        // 1.1.196 field bug: the 3B model answered "Bob, Bob, Jake, Jake" — the same voice twice
        // with a playback pause between, and a two-line scene spoken entirely by one bot.
        var parse = validator.parse("Jake: What's with the sleep?\nJake: I switched to bare hands.\n"
                + "Sara: Not with that sword.\nSara: Don't give me a hard time.", ROSTER);
        assertTrue(parse.accepted());
        assertEquals(2, parse.lines().size());
        assertEquals(0, parse.lines().get(0).participantIndex());
        assertEquals("What's with the sleep? I switched to bare hands.", parse.lines().get(0).text());
        assertEquals(1, parse.lines().get(1).participantIndex());
        assertEquals("Not with that sword. Don't give me a hard time.", parse.lines().get(1).text());
    }

    @Test
    void mergedRunCountsOnceAgainstThePerBotCap() {
        var parse = validator.parse("Jake: One.\nJake: Two.\nSara: Three.\nJake: Four.\nSara: Five.\nJake: Six.", ROSTER);
        assertTrue(parse.accepted());
        // Jake: "One. Two." (1 turn), Sara (1), Jake "Four." (2nd turn), Sara (2nd), Jake "Six." over the cap.
        assertEquals(4, parse.lines().size());
        assertEquals("Four.", parse.lines().get(2).text());
    }
}
