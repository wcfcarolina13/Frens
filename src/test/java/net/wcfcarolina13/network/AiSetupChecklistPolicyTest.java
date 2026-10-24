package net.wcfcarolina13.network;

import net.wcfcarolina13.network.AiSetupChecklistPolicy.Checklist;
import net.wcfcarolina13.network.AiSetupChecklistPolicy.State;
import net.wcfcarolina13.network.AiSetupChecklistPolicy.Step;
import net.wcfcarolina13.network.AiSetupChecklistPolicy.StepId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSetupChecklistPolicyTest {

    private static final String BOB_ID = "00000000-0000-0000-0000-000000000001";
    private static final String AMY_ID = "00000000-0000-0000-0000-000000000002";

    private static AiSetupStatus.Bot bot(String name, String id, boolean enabled, boolean owned,
                                         boolean inReach, boolean replied) {
        return new AiSetupStatus.Bot(name, id, enabled ? "frens:" + name.toLowerCase() : "", enabled,
                owned, inReach, replied);
    }

    private static AiSetupStatus hostStatus(AiSetupStatus.Ollama ollama, boolean soulsOn, boolean ready,
                                            String model, List<AiSetupStatus.Bot> bots, boolean voiceOn) {
        return new AiSetupStatus(true, ollama,
                new AiSetupStatus.Runtime(true, soulsOn, ready, model, model.isBlank()
                        ? "Configure a local soul model first." : "", true),
                bots, new AiSetupStatus.Voice(voiceOn, true, "piper", true, voiceOn),
                new AiSetupStatus.Viewer(true, true));
    }

    private static AiSetupStatus.Ollama ollama(boolean reachable, String... tags) {
        return new AiSetupStatus.Ollama(reachable, reachable ? "0.12.0" : "", List.of(tags), 32);
    }

    @Test
    void noStatusYetIsAllUnknownWithNoButtons() {
        Checklist c = AiSetupChecklistPolicy.evaluate(null);
        assertEquals(StepId.values().length, c.steps().size());
        for (Step s : c.steps()) {
            assertEquals(State.UNKNOWN, s.state());
            assertFalse(s.buttonEnabled());
        }
        assertTrue(c.bots().isEmpty());
        assertEquals("", c.testBotName());
    }

    @Test
    void freshSingleplayerHostWithoutOllama() {
        Checklist c = AiSetupChecklistPolicy.evaluate(
                hostStatus(ollama(false), false, false, "", List.of(bot("Bob", BOB_ID, false, true, true, false)), false));
        assertEquals(State.TODO, c.step(StepId.OLLAMA).state());
        assertTrue(c.step(StepId.OLLAMA).buttonEnabled());
        assertEquals("Only needed for AI chat. Frens works fully without it.", c.step(StepId.OLLAMA).detail());
        assertEquals(State.TODO, c.step(StepId.MODEL).state());
        assertTrue(c.step(StepId.MODEL).buttonEnabled());
        assertEquals(State.TODO, c.step(StepId.SOULS_ON).state());
        assertTrue(c.step(StepId.SOULS_ON).buttonEnabled());
        assertEquals(State.TODO, c.step(StepId.BOTS).state());
        assertEquals(1, c.bots().size());
        assertTrue(c.bots().get(0).enableButton());
        assertEquals(State.TODO, c.step(StepId.TEST_CHAT).state());
        assertFalse(c.step(StepId.TEST_CHAT).buttonEnabled(), "no enabled bot to say hi to");
        assertEquals(State.OPTIONAL, c.step(StepId.VOICE).state());
        assertTrue(c.step(StepId.VOICE).buttonEnabled());
        assertFalse(c.onServerMachine(), "integrated host runs the server itself");
    }

    @Test
    void modelChosenButOllamaDownIsUnknown() {
        Checklist c = AiSetupChecklistPolicy.evaluate(
                hostStatus(ollama(false), true, false, "llama3.2:3b", List.of(), false));
        assertEquals(State.UNKNOWN, c.step(StepId.MODEL).state());
    }

    @Test
    void modelChosenButNotPulledIsTodo() {
        Checklist c = AiSetupChecklistPolicy.evaluate(
                hostStatus(ollama(true, "llama3.2:1b"), true, true, "llama3.2:3b", List.of(), false));
        assertEquals(State.DONE, c.step(StepId.OLLAMA).state());
        assertEquals(State.TODO, c.step(StepId.MODEL).state());
        assertEquals("llama3.2:3b is chosen but not downloaded.", c.step(StepId.MODEL).detail());
    }

    @Test
    void everythingDoneAfterABotReplied() {
        Checklist c = AiSetupChecklistPolicy.evaluate(hostStatus(ollama(true, "llama3.2:3b"), true, true,
                "llama3.2:3b", List.of(bot("Bob", BOB_ID, true, true, true, true)), true));
        for (StepId id : StepId.values()) {
            assertEquals(State.DONE, c.step(id).state(), id.name());
        }
        assertFalse(c.step(StepId.SOULS_ON).buttonEnabled(), "already on");
        assertFalse(c.bots().get(0).enableButton(), "already enabled");
        assertEquals("Bob", c.testBotName());
    }

    @Test
    void readyButNoReplyYetInvitesATest() {
        Checklist c = AiSetupChecklistPolicy.evaluate(hostStatus(ollama(true, "llama3.2:3b"), true, true,
                "llama3.2:3b", List.of(bot("Bob", BOB_ID, true, true, true, false)), false));
        Step test = c.step(StepId.TEST_CHAT);
        assertEquals(State.TODO, test.state());
        assertTrue(test.buttonEnabled());
        assertEquals("Try it: say hi to Bob in chat.", test.detail());
        assertEquals("Bob, can you hear me?", AiSetupChecklistPolicy.greeting(c.testBotName()));
    }

    @Test
    void enabledBotButRuntimeNotReadyStillOffersSayHi() {
        Checklist c = AiSetupChecklistPolicy.evaluate(hostStatus(ollama(true), false, false, "",
                List.of(bot("Bob", BOB_ID, true, true, true, false)), false));
        assertTrue(c.step(StepId.TEST_CHAT).buttonEnabled());
        assertEquals("Finish the steps above, then say hi to Bob.", c.step(StepId.TEST_CHAT).detail());
    }

    @Test
    void multiplayerNonOperatorSeesOwnBotsAndDisabledServerSteps() {
        AiSetupStatus full = new AiSetupStatus(true, ollama(true, "llama3.2:3b"),
                new AiSetupStatus.Runtime(true, false, false, "llama3.2:3b", "", true),
                List.of(bot("Bob", BOB_ID, false, true, true, false), bot("Amy", AMY_ID, true, false, true, false)),
                new AiSetupStatus.Voice(false, true, "pocket", false, false),
                new AiSetupStatus.Viewer(false, false));
        // The server sends the redacted view to a non-operator; round-trip it like the wire does.
        AiSetupStatus seen = AiSetupStatus.fromJson(full.redactedForPlayer().toJson());
        Checklist c = AiSetupChecklistPolicy.evaluate(seen);

        assertTrue(c.onServerMachine());
        assertEquals(State.UNKNOWN, c.step(StepId.OLLAMA).state());
        assertEquals(State.UNKNOWN, c.step(StepId.MODEL).state());
        for (StepId serverStep : List.of(StepId.OLLAMA, StepId.MODEL, StepId.SOULS_ON, StepId.VOICE)) {
            assertFalse(c.step(serverStep).buttonEnabled(), serverStep.name());
            assertTrue(c.step(serverStep).needsOperator(), serverStep.name());
        }
        assertEquals(State.TODO, c.step(StepId.SOULS_ON).state());
        // Only their own bot is listed, and they may enable it.
        assertEquals(1, c.bots().size());
        assertEquals("Bob", c.bots().get(0).name());
        assertTrue(c.bots().get(0).enableButton());
        assertEquals(State.OPTIONAL, c.step(StepId.VOICE).state());
    }

    @Test
    void operatorSeesOtherOwnersBotsButTestsTheirOwnFirst() {
        AiSetupStatus s = new AiSetupStatus(true, ollama(true, "llama3.2:3b"),
                new AiSetupStatus.Runtime(true, true, true, "llama3.2:3b", "", true),
                List.of(bot("Amy", AMY_ID, true, false, true, false), bot("Bob", BOB_ID, true, true, false, false)),
                new AiSetupStatus.Voice(false, true, "piper", true, false),
                new AiSetupStatus.Viewer(true, false));
        Checklist c = AiSetupChecklistPolicy.evaluate(s);
        assertEquals(2, c.bots().size());
        assertEquals("Bob", c.testBotName(), "own bot preferred over another owner's, even out of reach");
        assertTrue(c.onServerMachine(), "dedicated-server op is not the integrated host");
    }

    @Test
    void noOwnedBotsTellsThePlayerToSpawnOne() {
        Checklist c = AiSetupChecklistPolicy.evaluate(
                hostStatus(ollama(true, "llama3.2:3b"), true, true, "llama3.2:3b", List.of(), false));
        assertEquals(State.TODO, c.step(StepId.BOTS).state());
        assertEquals("Spawn or recruit a companion.", c.step(StepId.BOTS).detail());
        assertFalse(c.step(StepId.TEST_CHAT).buttonEnabled());
    }

    @Test
    void replyFromADisabledBotDoesNotCount() {
        Checklist c = AiSetupChecklistPolicy.evaluate(hostStatus(ollama(true, "llama3.2:3b"), true, true,
                "llama3.2:3b", List.of(bot("Bob", BOB_ID, false, true, true, true)), false));
        assertEquals(State.TODO, c.step(StepId.TEST_CHAT).state());
    }

    @Test
    void untaggedModelMatchesLatest() {
        assertTrue(AiSetupChecklistPolicy.isModelInstalled("llama3.2", List.of("llama3.2:latest")));
        assertTrue(AiSetupChecklistPolicy.isModelInstalled("llama3.2:3b", List.of("llama3.2:3b")));
        assertFalse(AiSetupChecklistPolicy.isModelInstalled("llama3.2:3b", List.of("llama3.2:latest")));
        assertFalse(AiSetupChecklistPolicy.isModelInstalled("", List.of("llama3.2:latest")));
        assertFalse(AiSetupChecklistPolicy.isModelInstalled("llama3.2", null));
    }

    @Test
    void ramGuidanceShowsHostRamWhenKnown() {
        assertEquals("Host has 16 GB RAM. 1B ≈4 GB RAM, 3B ≈6 GB, 8B ≈12 GB (plus Minecraft's own memory)",
                AiSetupChecklistPolicy.ramGuidance(16.0));
        assertEquals("1B ≈4 GB RAM, 3B ≈6 GB, 8B ≈12 GB (plus Minecraft's own memory)",
                AiSetupChecklistPolicy.ramGuidance(-1));
    }

    @Test
    void ramHintShortFitsTheNarrowestPanelWithTheSameNumbers() {
        assertEquals("Host: 32 GB RAM. 1B/3B/8B need about 4/6/12 GB.", AiSetupChecklistPolicy.ramGuidanceShort(32.0));
        assertEquals("RAM: 1B/3B/8B need about 4/6/12 GB.", AiSetupChecklistPolicy.ramGuidanceShort(-1));
        // Same facts as the full tooltip text: 1B 4 GB, 3B 6 GB, 8B 12 GB.
        String full = AiSetupChecklistPolicy.ramGuidance(-1);
        assertTrue(full.contains("1B ≈4 GB") && full.contains("3B ≈6 GB") && full.contains("8B ≈12 GB"), full);
        // Vanilla font advances: space 4, '.' and ':' 2, the rest at most 6. The line has ~276 px at
        // the 320-wide window and ~298 px at 342, so it must fit 276 with a three-digit RAM figure.
        assertTrue(fontWidth(AiSetupChecklistPolicy.ramGuidanceShort(128.0)) <= 276,
                AiSetupChecklistPolicy.ramGuidanceShort(128.0));
    }

    // ── Step 4 text at GUI scale 5 (342 px window: 326 px panel, 190 px text column) ──

    /** Text column beside the step buttons at scale 5: 326 - 2*8 - 104 - 16. */
    private static final int SCALE5_TEXT_W = 190;

    /** {@link #fontWidth} with colour codes removed (they draw nothing). */
    private static int shownWidth(String text) {
        return fontWidth(text.replaceAll("§.", ""));
    }

    @Test
    void botsDetailsFitTheScale5TextColumn() {
        Checklist none = AiSetupChecklistPolicy.evaluate(hostStatus(ollama(true, "llama3.2:3b"), true, true,
                "llama3.2:3b", List.of(), true));
        Checklist off = AiSetupChecklistPolicy.evaluate(hostStatus(ollama(true, "llama3.2:3b"), true, true,
                "llama3.2:3b", List.of(bot("Bob", BOB_ID, false, true, true, false)), true));
        Checklist on = AiSetupChecklistPolicy.evaluate(hostStatus(ollama(true, "llama3.2:3b"), true, true,
                "llama3.2:3b", List.of(bot("Bob", BOB_ID, true, true, true, true)), true));
        for (Checklist c : List.of(none, off, on)) {
            String detail = c.step(StepId.BOTS).detail();
            assertTrue(shownWidth(detail) <= SCALE5_TEXT_W, detail);
        }
        assertEquals("A companion has an AI persona.", on.step(StepId.BOTS).detail());
    }

    @Test
    void botsTitleKeepsTheRangeAtScale5() {
        List<String> candidates = AiSetupChecklistPolicy.botsTitleCandidates("4. Companion enabled", 1, 3, 7);
        assertEquals("4. Companion enabled  §7(1–3 of 7, scroll)", candidates.get(0));
        String picked = net.wcfcarolina13.GraphicalUserInterface.UiFitPolicy.pickLabel(
                candidates, SCALE5_TEXT_W, AiSetupChecklistPolicyTest::shownWidth);
        assertEquals("4. Companion enabled  §7(1–3 of 7)", picked);
        assertFalse(picked.endsWith("…"), picked);
    }

    @Test
    void botsTitleCandidatesShrinkToTheBareTitle() {
        List<String> candidates = AiSetupChecklistPolicy.botsTitleCandidates("4. Companion enabled", 10, 12, 14);
        for (int i = 1; i < candidates.size(); i++) {
            assertTrue(shownWidth(candidates.get(i)) < shownWidth(candidates.get(i - 1)), candidates.get(i));
        }
        assertEquals("4. Companion enabled", candidates.get(candidates.size() - 1));
        // Every form fits the narrowest panel too (320 px window: 304 px panel, 168 px column) or is the bare title.
        String picked = net.wcfcarolina13.GraphicalUserInterface.UiFitPolicy.pickLabel(
                candidates, 168, AiSetupChecklistPolicyTest::shownWidth);
        assertTrue(shownWidth(picked) <= 168, picked);
        assertFalse(picked.endsWith("…"), picked);
    }

    @Test
    void botsTitleWithoutScrollingIsTheBareTitle() {
        assertEquals(List.of("4. Companion enabled"),
                AiSetupChecklistPolicy.botsTitleCandidates("4. Companion enabled", 1, 3, 3));
        assertEquals(List.of("4. Companion enabled"),
                AiSetupChecklistPolicy.botsTitleCandidates("4. Companion enabled", 1, 0, 0));
    }

    private static int fontWidth(String text) {
        int w = 0;
        for (char c : text.toCharArray()) {
            w += c == ' ' ? 4 : (c == '.' || c == ':' ? 2 : 6);
        }
        return w;
    }

    // ── New-bot soul question (step 4's button slot) ────────────────────────

    private static AiSetupStatus withNewBotAnswer(AiSetupStatus.Ollama ollama, String model, Boolean answer,
                                                  boolean canEdit) {
        return new AiSetupStatus(true, ollama,
                new AiSetupStatus.Runtime(true, true, true, model, "", true, answer),
                List.of(bot("Bob", BOB_ID, false, true, true, false)),
                new AiSetupStatus.Voice(false, true, "piper", true, false),
                new AiSetupStatus.Viewer(canEdit, canEdit));
    }

    @Test
    void newBotQuestionWaitsForOllamaAndTheModel() {
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.HIDDEN, AiSetupChecklistPolicy.evaluate(
                withNewBotAnswer(ollama(false), "llama3.2:3b", null, true)).newBotSoul());
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.HIDDEN, AiSetupChecklistPolicy.evaluate(
                withNewBotAnswer(ollama(true), "llama3.2:3b", null, true)).newBotSoul(), "model not downloaded");
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.ASK, AiSetupChecklistPolicy.evaluate(
                withNewBotAnswer(ollama(true, "llama3.2:3b"), "llama3.2:3b", null, true)).newBotSoul());
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.HIDDEN, AiSetupChecklistPolicy.evaluate(null).newBotSoul());
    }

    @Test
    void newBotQuestionIsOnlyForOperatorOrHost() {
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.HIDDEN, AiSetupChecklistPolicy.evaluate(
                withNewBotAnswer(ollama(true, "llama3.2:3b"), "llama3.2:3b", null, false)).newBotSoul());
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.HIDDEN, AiSetupChecklistPolicy.evaluate(
                withNewBotAnswer(ollama(true, "llama3.2:3b"), "llama3.2:3b", true, false)).newBotSoul());
        // The redacted view a regular player gets never carries the answer.
        AiSetupStatus full = withNewBotAnswer(ollama(true, "llama3.2:3b"), "llama3.2:3b", true, false);
        String json = full.redactedForPlayer().toJson();
        assertFalse(json.contains("newBotSoul"), json);
        assertEquals(null, AiSetupStatus.fromJson(json).runtime().newBotSoulDefault());
    }

    @Test
    void answeredQuestionStaysReachableToChangeIt() {
        // Ollama stopped after answering: still shown so the answer can be changed.
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.SOUL, AiSetupChecklistPolicy.evaluate(
                withNewBotAnswer(ollama(false), "", true, true)).newBotSoul());
        assertEquals(AiSetupChecklistPolicy.NewBotSoul.NONE, AiSetupChecklistPolicy.evaluate(
                withNewBotAnswer(ollama(true, "llama3.2:3b"), "llama3.2:3b", false, true)).newBotSoul());
        assertTrue(AiSetupChecklistPolicy.newBotSoulNextAnswer(AiSetupChecklistPolicy.NewBotSoul.ASK));
        assertFalse(AiSetupChecklistPolicy.newBotSoulNextAnswer(AiSetupChecklistPolicy.NewBotSoul.SOUL));
        assertTrue(AiSetupChecklistPolicy.newBotSoulNextAnswer(AiSetupChecklistPolicy.NewBotSoul.NONE));
    }

    @Test
    void newBotLabelsFitTheButtonAndTheTooltipAsksTheQuestion() {
        for (AiSetupChecklistPolicy.NewBotSoul choice : List.of(AiSetupChecklistPolicy.NewBotSoul.ASK,
                AiSetupChecklistPolicy.NewBotSoul.SOUL, AiSetupChecklistPolicy.NewBotSoul.NONE)) {
            List<String> labels = AiSetupChecklistPolicy.newBotSoulLabels(choice);
            // Narrowest button: min(104, 304 / 3) = 101 px, 8 px inner margin.
            assertTrue(fontWidth(labels.get(1)) <= 93, labels.get(1));
            String tip = AiSetupChecklistPolicy.newBotSoulTooltip(choice);
            assertTrue(tip.startsWith(AiSetupChecklistPolicy.NEW_BOT_SOUL_QUESTION), tip);
            assertFalse(tip.contains("—") || tip.contains(";"), tip);
        }
        assertEquals("Give new companions a soul when Ollama is ready?", AiSetupChecklistPolicy.NEW_BOT_SOUL_QUESTION);
    }

    // ── Status JSON (the wire format) ───────────────────────────────────────

    @Test
    void fullStatusRoundTrips() {
        AiSetupStatus s = hostStatus(ollama(true, "llama3.2:3b", "qwen2.5:7b"), true, true, "llama3.2:3b",
                List.of(bot("Bob", BOB_ID, true, true, false, true)), true);
        assertEquals(s, AiSetupStatus.fromJson(s.toJson()));
    }

    @Test
    void newBotAnswerRoundTripsInTheFullView() {
        for (Boolean answer : new Boolean[] {null, true, false}) {
            AiSetupStatus s = withNewBotAnswer(ollama(true, "llama3.2:3b"), "llama3.2:3b", answer, true);
            AiSetupStatus back = AiSetupStatus.fromJson(s.toJson());
            assertEquals(s, back);
            assertEquals(answer, back.runtime().newBotSoulDefault());
        }
    }

    @Test
    void redactedStatusOmitsServerMachineDetails() {
        AiSetupStatus s = hostStatus(ollama(true, "llama3.2:3b"), true, true, "llama3.2:3b",
                List.of(bot("Bob", BOB_ID, true, true, true, false), bot("Amy", AMY_ID, true, false, true, false)), true);
        String json = s.redactedForPlayer().toJson();
        assertFalse(json.contains("ollama"), json);
        assertFalse(json.contains("llama3.2"), json);
        assertFalse(json.contains("hostRamGb"), json);
        assertFalse(json.contains("validationError"), json);
        assertFalse(json.contains("engine"), json);
        assertFalse(json.contains("Amy"), json);
        AiSetupStatus back = AiSetupStatus.fromJson(json);
        assertFalse(back.full());
        assertEquals(null, back.ollama());
        assertEquals(null, back.runtime().providerHealthy());
        assertTrue(back.runtime().soulsEnabled());
        assertEquals(1, back.bots().size());
        assertTrue(back.voice().enabled());
        assertFalse(back.voice().detailKnown());
    }

    @Test
    void malformedJsonIsNull() {
        assertEquals(null, AiSetupStatus.fromJson(null));
        assertEquals(null, AiSetupStatus.fromJson(""));
        assertEquals(null, AiSetupStatus.fromJson("not json"));
        assertEquals(null, AiSetupStatus.fromJson("[1,2]"));
    }

    @Test
    void oversizedListsAreCapped() {
        List<String> tags = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) tags.add("m" + i + ":1b");
        List<AiSetupStatus.Bot> bots = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) bots.add(bot("B" + i, "id" + i, false, true, true, false));
        AiSetupStatus s = hostStatus(new AiSetupStatus.Ollama(true, "1", tags, 8), true, true, "m0:1b", bots, false);
        String json = s.toJson();
        assertTrue(json.length() < 32767, "fits the payload's string limit");
        AiSetupStatus back = AiSetupStatus.fromJson(json);
        assertEquals(AiSetupStatus.MAX_TAGS, back.ollama().installedTags().size());
        assertEquals(AiSetupStatus.MAX_BOTS, back.bots().size());
    }
}
