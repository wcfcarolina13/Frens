package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conversation ontology Phase 3a TEASE rule: a teasing scene is affectionate friction. */
class SoulTeasePeerRuleTest {
    private static final UUID ALFA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BRAVO = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CHARLIE = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final List<String> NAMES = List.of("Alfa", "Bravo");
    private static final List<UUID> IDS = List.of(ALFA, BRAVO);

    private static SoulGroupTypes.SceneLine line(int index, String text) {
        return new SoulGroupTypes.SceneLine(index, text);
    }

    private static final List<SoulGroupTypes.SceneLine> BANTER = List.of(
            line(0, "Still carrying that dull axe around?"),
            line(1, "It's seasoned, not dull."));

    private static SoulTypes.Stance stanceOf(SoulTypes.SoulMind mind, UUID peer) {
        return mind.peerStances().get(peer).stance();
    }

    @Test
    void teaseSceneAddsExasperationAndTrustForBothSpeakers() {
        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, NAMES, IDS,
                BANTER, 4, SoulSpeechAct.TEASE);
        assertEquals(new SoulTypes.Stance(4, 1, 3), stanceOf(alfa, BRAVO));
        assertEquals(4, alfa.peerStances().get(BRAVO).lastAskDay(), "spends the friction day guard");
        SoulTypes.SoulMind bravo = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 1, NAMES, IDS,
                BANTER, 4, SoulSpeechAct.TEASE);
        assertEquals(new SoulTypes.Stance(4, 1, 3), stanceOf(bravo, ALFA));
    }

    @Test
    void otherActsAndNoActLeaveFrictionAlone() {
        for (SoulSpeechAct act : new SoulSpeechAct[] {null, SoulSpeechAct.RECALL, SoulSpeechAct.WORRY}) {
            SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, NAMES, IDS,
                    BANTER, 4, act);
            assertEquals(new SoulTypes.Stance(4, 0, 3), stanceOf(alfa, BRAVO), "act=" + act);
        }
        SoulTypes.SoulMind legacy = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, NAMES, IDS,
                BANTER, 4);
        assertEquals(new SoulTypes.Stance(4, 0, 3), stanceOf(legacy, BRAVO), "old overload = no act");
    }

    @Test
    void oneLineTeaseIsNotAnExchange() {
        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, NAMES, IDS,
                List.of(line(0, "Still carrying that dull axe around?")), 4, SoulSpeechAct.TEASE);
        assertNull(alfa.peerStances().get(BRAVO), "nothing recorded toward a peer who never spoke");
    }

    @Test
    void twoLinesFromOneSpeakerDoNotTouchTheSilentPeer() {
        List<SoulGroupTypes.SceneLine> monologue = List.of(
                line(0, "Still carrying that dull axe around?"),
                line(0, "Thought so."));
        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, NAMES, IDS,
                monologue, 4, SoulSpeechAct.TEASE);
        assertNull(alfa.peerStances().get(BRAVO));
        SoulTypes.SoulMind bravo = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 1, NAMES, IDS,
                monologue, 4, SoulSpeechAct.TEASE);
        assertNull(bravo.peerStances().get(ALFA), "the silent bot was not part of the teasing");
    }

    @Test
    void silentThirdBotIsLeftOutOfTheTease() {
        List<String> names = List.of("Alfa", "Bravo", "Charlie");
        List<UUID> ids = List.of(ALFA, BRAVO, CHARLIE);
        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, names, ids,
                BANTER, 4, SoulSpeechAct.TEASE);
        assertEquals(new SoulTypes.Stance(4, 1, 3), stanceOf(alfa, BRAVO));
        assertNull(alfa.peerStances().get(CHARLIE));
        SoulTypes.SoulMind charlie = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 2, names, ids,
                BANTER, 4, SoulSpeechAct.TEASE);
        assertTrue(charlie.peerStances().isEmpty());
    }

    @Test
    void unansweredQuestionAndTeaseStepExasperationOnlyOnce() {
        // Alfa asks Bravo and Bravo's only line comes before the question: rule 2 fires; the
        // tease must not stack a second exasperation step in the same scene.
        List<SoulGroupTypes.SceneLine> scene = List.of(
                line(1, "Nice axe."),
                line(0, "Bravo, is that sarcasm?"));
        SoulTypes.SoulMind alfa = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, NAMES, IDS,
                scene, 4, SoulSpeechAct.TEASE);
        assertEquals(1, stanceOf(alfa, BRAVO).exasperation());
    }

    @Test
    void teaseFrictionIsOncePerDayAndSharesTheAskGuard() {
        SoulTypes.SoulMind first = SoulMindOps.notePeerScene(SoulTypes.SoulMind.empty(), 0, NAMES, IDS,
                BANTER, 4, SoulSpeechAct.TEASE);
        SoulTypes.SoulMind again = SoulMindOps.notePeerScene(first, 0, NAMES, IDS, BANTER, 4, SoulSpeechAct.TEASE);
        assertSame(first, again, "a second teasing scene the same day changes nothing");
        // Bravo asking Alfa later the same day finds the friction/curiosity guard already spent.
        SoulTypes.SoulMind asked = SoulMindOps.notePeerScene(first, 0, NAMES, IDS,
                List.of(line(1, "Alfa, want to trade?")), 4);
        assertSame(first, asked);
        SoulTypes.SoulMind nextDay = SoulMindOps.notePeerScene(first, 0, NAMES, IDS, BANTER, 5, SoulSpeechAct.TEASE);
        assertEquals(new SoulTypes.Stance(5, 2, 3), stanceOf(nextDay, BRAVO));
    }

    @Test
    void groupTurnCarriesTheActAndOlderConstructorsCarryNone() {
        SoulGroupTypes.GroupSceneTurn plain = new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER,
                ALFA, "owner", List.of(), "", Instant.EPOCH, UUID.randomUUID(), false);
        assertNull(plain.speechAct());
        SoulGroupTypes.GroupSceneTurn teased = new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER,
                ALFA, "owner", List.of(), "", Instant.EPOCH, UUID.randomUUID(), false, java.util.Set.of(), null,
                SoulSpeechAct.TEASE);
        assertEquals(SoulSpeechAct.TEASE, teased.speechAct());
    }

    @Test
    void banterDirectorAndRuntimeCarryTheActToTheRule() throws IOException {
        String director = flatten("SoulBanterDirector.java");
        assertTrue(director.contains("privateAnchorUsed ? playerId : null, seeded.act());"),
                "the banter scene is built with the act its seed picked");
        String runtime = flatten("SoulRuntime.java");
        assertTrue(runtime.contains(
                "SoulMindOps.notePeerScene(m, selfIndex, names, ids, delivered, day, turn.speechAct())"));
    }

    private static String flatten(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/souls", file))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "")
                .replaceAll("\\s+", " ");
    }
}
