package net.wcfcarolina13.guide;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuideChatAnswerTest {

    private static final List<GuideCatalog.Topic> GUIDE = GuideCatalog.loadBundled();

    private static List<GuideQuestionMatcher.Match> matches(String prompt) {
        return GuideQuestionMatcher.answer(prompt, GUIDE, GuideQuestionMatcher.Visibility.PUBLIC, 2);
    }

    private static GuideCatalog.Topic topic(String id) {
        return GUIDE.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void answerNamesTheCardFillsTheBotAndOffersTheRunnerUp() {
        GuideChatAnswer.Answer a = GuideChatAnswer.build(matches("how do I fast travel?"), "Jake").orElseThrow();
        assertFalse(a.spoken().contains("§"), a.spoken());
        assertEquals(topic(a.topicId()).resolve("Jake", GuideChatAnswer.GUIDE_KEY_TEXT).answer(), a.spoken(),
                "the card's spoken answer is what the fren says");
        assertFalse(a.spoken().contains("{bot}"), a.spoken());
        assertFalse(a.alsoTopicId().isEmpty(), "fast travel has two close cards");
        assertNotEquals(a.topicId(), a.alsoTopicId());

        GuideChatAnswer.Answer stop = GuideChatAnswer.build(matches("how do I stop you"), "Jake").orElseThrow();
        assertEquals("/bot stop Jake", stop.commandHint());
    }

    @Test
    void cardWithoutAnAnswerFallsBackToTitleAndSummary() {
        GuideCatalog.Topic t = topic("basics_stop");
        GuideCatalog.Topic bare = new GuideCatalog.Topic(t.id(), t.category(), t.title(), t.summary(),
                t.details(), t.command(), t.shortcuts(), t.tags(), t.adminOnly(), t.questingOnly(), "");
        assertEquals("Stop: " + t.summary(), GuideChatAnswer.spokenText(bare));
        assertTrue(GuideChatAnswer.spokenText(t).startsWith("Look at me"), GuideChatAnswer.spokenText(t));
    }

    @Test
    void askerWithoutBotCommandsGetsNoBotHintAndANote() {
        GuideChatAnswer.Answer stop = GuideChatAnswer.build(matches("how do I stop you"), "Jake").orElseThrow();
        assertTrue(GuideChatAnswer.needsOperatorNote(stop, false));
        assertFalse(GuideChatAnswer.needsOperatorNote(stop, true));
        assertEquals("", GuideChatAnswer.forAsker(stop, false).commandHint());
        assertEquals("/bot stop Jake", GuideChatAnswer.forAsker(stop, true).commandHint());
        assertEquals(stop.spoken(), GuideChatAnswer.forAsker(stop, false).spoken());

        GuideChatAnswer.Answer noCommand = new GuideChatAnswer.Answer("Open the Spells tab.", "", "x", "X", "", "");
        assertFalse(GuideChatAnswer.needsOperatorNote(noCommand, false));
        assertTrue(GUIDE.stream().anyMatch(t -> t.id().equals(GuideChatAnswer.FRIEND_ACCESS_TOPIC_ID)),
                "the note links a card that exists");
    }

    @Test
    void noMatchNoAnswer() {
        assertTrue(GuideChatAnswer.build(List.of(), "Jake").isEmpty());
        assertTrue(GuideChatAnswer.build(null, "Jake").isEmpty());
    }

    @Test
    void commandHints() {
        assertEquals("/bot follow Jake", GuideChatAnswer.commandHint("bot follow Jake"));
        assertEquals("", GuideChatAnswer.commandHint("UI-only"));
        assertEquals("", GuideChatAnswer.commandHint("UI-only action (Spells tab)"));
        assertEquals("", GuideChatAnswer.commandHint(""));
        assertEquals("/give @p frens:wizard_tome (creative)",
                GuideChatAnswer.commandHint("/give @p frens:wizard_tome (creative)"));
        assertEquals("Normal chat: Bots, hi", GuideChatAnswer.commandHint("Normal chat: Bots, hi"));
    }

    @Test
    void openCommandKeepsOnlyAValidBotName() {
        assertEquals("/frens guide fast_travel_gates Jake", GuideChatAnswer.openCommand("fast_travel_gates", "Jake"));
        assertEquals("/frens guide fast_travel_gates", GuideChatAnswer.openCommand("fast_travel_gates", "bad name"));
        assertEquals("/frens guide fast_travel_gates", GuideChatAnswer.openCommand("fast_travel_gates", null));
    }

    @Test
    void promptLinesCarryTheCardWithinBudgetAsWholeLines() {
        List<String> lines = GuideChatAnswer.promptLines(matches("how do I set up compass work loops?"),
                "Jake", GuideChatAnswer.MAX_PROMPT_CHARS);
        assertEquals("Topic: Daily Work Loops (Lodestone Compass)", lines.get(0));
        assertTrue(lines.get(1).startsWith("Short answer (in your voice): Put a lodestone"), lines.get(1));
        assertTrue(lines.contains("Command: /bot compass list Jake"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("/bot skill woodcut Jake")), lines.toString());
        int chars = lines.stream().mapToInt(l -> l.length() + 1).sum();
        assertTrue(chars <= GuideChatAnswer.MAX_PROMPT_CHARS, "chars=" + chars);
        assertTrue(lines.stream().noneMatch(l -> l.contains("§") || l.contains("{bot}")), lines.toString());

        List<String> fast = GuideChatAnswer.promptLines(List.of(
                new GuideQuestionMatcher.Match(topic("fast_travel_gates"), 9)), "Jake", 2000);
        assertTrue(fast.stream().noneMatch(l -> l.contains("§")), fast.toString());
        assertTrue(fast.stream().noneMatch(l -> l.startsWith("Command:")), "UI-only gives no command line");

        List<String> tiny = GuideChatAnswer.promptLines(matches("how do I set up compass work loops?"), "Jake", 60);
        assertEquals(List.of("Topic: Daily Work Loops (Lodestone Compass)"), tiny);
        assertTrue(GuideChatAnswer.promptLines(List.of(), "Jake", 500).isEmpty());
    }
}
