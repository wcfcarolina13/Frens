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
        assertEquals("No companion of yours is online. Spawn or recruit one first.", c.step(StepId.BOTS).detail());
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

    // ── Status JSON (the wire format) ───────────────────────────────────────

    @Test
    void fullStatusRoundTrips() {
        AiSetupStatus s = hostStatus(ollama(true, "llama3.2:3b", "qwen2.5:7b"), true, true, "llama3.2:3b",
                List.of(bot("Bob", BOB_ID, true, true, false, true)), true);
        assertEquals(s, AiSetupStatus.fromJson(s.toJson()));
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
