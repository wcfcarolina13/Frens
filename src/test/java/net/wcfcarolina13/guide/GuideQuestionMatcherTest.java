package net.wcfcarolina13.guide;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuideQuestionMatcherTest {

    private static final List<GuideCatalog.Topic> GUIDE = GuideCatalog.loadBundled();

    private static String best(String prompt) {
        List<GuideQuestionMatcher.Match> m = GuideQuestionMatcher.answer(prompt, GUIDE,
                GuideQuestionMatcher.Visibility.PUBLIC, 2);
        return m.isEmpty() ? "" : m.get(0).topic().id();
    }

    private static List<String> ids(String prompt) {
        return GuideQuestionMatcher.answer(prompt, GUIDE, GuideQuestionMatcher.Visibility.PUBLIC, 2)
                .stream().map(m -> m.topic().id()).toList();
    }

    @Test
    void fastTravelQuestionFindsTheFastTravelCards() {
        List<String> found = ids("how do I fast travel?");
        assertEquals(2, found.size(), found.toString());
        assertTrue(found.containsAll(List.of("fast_travel_gates", "settings_nav_modes")), found.toString());
        assertTrue(ids("how does fast-travel work").contains("fast_travel_gates"));
    }

    @Test
    void compassWorkLoopQuestionFindsWorkLoops() {
        assertEquals("gather_work_loops", best("how do I set up compass work loops?"));
        assertEquals("gather_work_loops", best("explain the daily work loop"));
    }

    @Test
    void everydayHowToQuestionsFindTheirCards() {
        assertEquals("basics_stop", best("how do I stop you"));
        assertEquals("move_follow", best("how do I make you follow me?"));
        assertEquals("gather_fish", best("how does fishing work"));
        assertEquals("construction_fortify", best("how do I fortify a village?"));
        assertEquals("zones_protected", best("how do I protect an area from bots breaking blocks"));
        assertEquals("basics_ai_setup", best("how do I set up AI chat with ollama?"));
        assertEquals("retrieve_drops", best("where do I get my items back after I died?"));
    }

    @Test
    void stemmingMeetsDifferentWordForms() {
        assertEquals("mine_strip", best("how does stripmining work"));
        assertEquals(GuideQuestionMatcher.stem("mining"), GuideQuestionMatcher.stem("mine"));
        assertEquals(GuideQuestionMatcher.stem("chopping"), GuideQuestionMatcher.stem("chop"));
        assertEquals(GuideQuestionMatcher.stem("travelling"), GuideQuestionMatcher.stem("travel"));
        assertEquals(GuideQuestionMatcher.stem("loops"), GuideQuestionMatcher.stem("loop"));
        assertEquals("gather_woodcut", best("how do I get wood"), "wood maps onto woodcut");
        assertEquals("settings_quiet_bots", best("how can I make the bot quieter"));
    }

    @Test
    void ordinaryTalkGetsNoGuideAnswer() {
        assertEquals("", best("nice house"));
        assertEquals("", best("fishing is fun"), "not a question");
        assertEquals("", best("how are you?"));
        assertEquals("", best("can you come here?"));
        assertEquals("", best("want to go fishing?"), "a bare ? needs more than one title word");
        assertEquals("", best("what do you think about the weather?"));
        assertEquals("", best("how do I build a wall"), "build is too generic to pick Build Farm");
        assertEquals("", best(""));
        assertEquals("", best(null));
    }

    @Test
    void questionShapes() {
        assertTrue(GuideQuestionMatcher.isHowToQuestion("how do I fish"));
        assertTrue(GuideQuestionMatcher.isHowToQuestion("Hey, what is a lodestone compass for"));
        assertTrue(GuideQuestionMatcher.isHowToQuestion("can you explain fast travel"));
        assertTrue(GuideQuestionMatcher.isHowToQuestion("I don’t know how to fish"));
        assertTrue(GuideQuestionMatcher.isHowToQuestion("fast travel?"));
        assertFalse(GuideQuestionMatcher.isHowToQuestion("follow me"));
        assertFalse(GuideQuestionMatcher.isHowToQuestion("showing off my house"));
        assertFalse(GuideQuestionMatcher.hasQuestionOpener("can you come here?"));
    }

    @Test
    void adminAndQuestCardsFollowVisibility() {
        String prompt = "how do I reset the world mode?";
        assertNotEquals("admin_worldmode_reset", best(prompt));
        List<GuideQuestionMatcher.Match> admin = GuideQuestionMatcher.answer(prompt, GUIDE,
                new GuideQuestionMatcher.Visibility(true, true), 1);
        assertEquals("admin_worldmode_reset", admin.get(0).topic().id());
        List<GuideQuestionMatcher.Match> adminNoQuesting = GuideQuestionMatcher.answer(prompt, GUIDE,
                new GuideQuestionMatcher.Visibility(true, false), 3);
        assertTrue(adminNoQuesting.stream().noneMatch(m -> m.topic().questingOnly()));
    }

    @Test
    void tiesKeepGuideOrderAndMaxIsRespected() {
        List<GuideQuestionMatcher.Match> one = GuideQuestionMatcher.answer("how do I fast travel?", GUIDE,
                GuideQuestionMatcher.Visibility.PUBLIC, 1);
        assertEquals(1, one.size());
        assertTrue(GuideQuestionMatcher.answer("how do I fast travel?", GUIDE,
                GuideQuestionMatcher.Visibility.PUBLIC, 0).isEmpty());
    }

    @Test
    void runnerUpNeedsHalfTheBestScore() {
        GuideCatalog.Topic t = GUIDE.get(0);
        assertTrue(GuideQuestionMatcher.runnerUpWorthMentioning(
                new GuideQuestionMatcher.Match(t, 10), new GuideQuestionMatcher.Match(t, 5)));
        assertFalse(GuideQuestionMatcher.runnerUpWorthMentioning(
                new GuideQuestionMatcher.Match(t, 10), new GuideQuestionMatcher.Match(t, 4)));
        assertFalse(GuideQuestionMatcher.runnerUpWorthMentioning(new GuideQuestionMatcher.Match(t, 10), null));
    }
}
