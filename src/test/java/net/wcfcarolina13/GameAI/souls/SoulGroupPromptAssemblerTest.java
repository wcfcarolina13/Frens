package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the group-scene prompt: fixed message order (scene contract first, owner message
 * last exactly once), cast/state blocks for every roster member, verbatim speaker-tagged party
 * history under the group budgets, and request metadata.
 */
class SoulGroupPromptAssemblerTest {

    private final SoulGroupPromptAssembler assembler = new SoulGroupPromptAssembler();

    private static SoulTypes.GroundingSnapshot grounding(UUID botId, String name) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(botId, name, "overworld", "plains",
                0, 64, 0, true, "dusk", "clear", 17f, 20f, 14, 5, "iron sword", 10, 36,
                List.of(), "cheerful", "FOLLOW", "skill:woodcut", "running", "home", "Bradley",
                true, 0, true, Optional.empty());
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), Instant.EPOCH);
    }

    private static SoulGroupTypes.GroupSceneTurn turn(String message) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        return new SoulGroupTypes.GroupSceneTurn(owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara"))),
                message, Instant.EPOCH, UUID.randomUUID());
    }

    private static SoulGroupTypes.GroupSceneTurn localTurn(String message) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.LOCAL, owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake"))),
                message, Instant.EPOCH, UUID.randomUUID());
    }

    private static SoulTypes.SoulProfile profile(String id, String displayName) {
        return new SoulTypes.SoulProfile(id, displayName,
                List.of("A loyal companion who loves mining.", "Wry sense of humor."),
                List.of("honesty"), List.of("never spoils quests"), List.of());
    }

    private static SoulTypes.ConversationRecord record(long seq, SoulTypes.TurnKind kind, String content) {
        return new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, seq, kind, content,
                Instant.EPOCH, "", "", null, null);
    }

    @Test
    void messageOrderContractFirstOwnerMessageLastExactlyOnce() {
        SoulGroupTypes.GroupSceneTurn turn = turn("what should we do tonight");
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "llama3.1:8b",
                turn, List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(60));

        List<SoulTypes.Message> messages = request.messages();
        assertEquals(SoulTypes.Role.SYSTEM, messages.get(0).role());
        assertTrue(messages.get(0).content().startsWith("SCENE CONTRACT"));
        SoulTypes.Message last = messages.get(messages.size() - 1);
        assertEquals(SoulTypes.Role.USER, last.role());
        assertEquals("Bradley: what should we do tonight", last.content());
        long ownerMessages = messages.stream()
                .filter(m -> m.content().equals("Bradley: what should we do tonight")).count();
        assertEquals(1, ownerMessages);
    }

    @Test
    void castAndStateBlocksNameEveryRosterMember() {
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("hi"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(60));
        String allSystem = request.messages().stream()
                .filter(m -> m.role() == SoulTypes.Role.SYSTEM)
                .map(SoulTypes.Message::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allSystem.contains("CAST"));
        assertTrue(allSystem.contains("Jake"));
        assertTrue(allSystem.contains("Sara"));
        assertTrue(allSystem.contains("CURRENT STATE"));
        assertTrue(allSystem.contains("iron sword"));
    }

    @Test
    void partyHistoryReplaysVerbatimWithRolesAndSkipsFailures() {
        List<SoulTypes.ConversationRecord> history = List.of(
                record(0, SoulTypes.TurnKind.HEARD, "Bradley: evening plans?"),
                record(1, SoulTypes.TurnKind.SPOKEN, "Jake: mining, obviously."),
                record(2, SoulTypes.TurnKind.FAILURE, ""),
                record(3, SoulTypes.TurnKind.SPOKEN, "Sara: fishing is safer."));
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        List<SoulTypes.Message> messages = request.messages();
        int n = messages.size();
        // ... history..., final USER message
        assertEquals("Sara: fishing is safer.", messages.get(n - 2).content());
        assertEquals(SoulTypes.Role.ASSISTANT, messages.get(n - 2).role());
        assertEquals("Bradley: evening plans?", messages.get(n - 4).content());
        assertEquals(SoulTypes.Role.USER, messages.get(n - 4).role());
        assertTrue(messages.stream().noneMatch(m -> m.content().isEmpty() && m.role() == SoulTypes.Role.ASSISTANT));
    }

    @Test
    void historyTurnBudgetKeepsOnlyTheNewestTwelve() {
        List<SoulTypes.ConversationRecord> history = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            history.add(record(i, SoulTypes.TurnKind.SPOKEN, "Jake: line " + i));
        }
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        long historyMessages = request.messages().stream()
                .filter(m -> m.content().startsWith("Jake: line ")).count();
        assertEquals(SoulGroupPromptAssembler.MAX_HISTORY_TURNS, historyMessages);
        assertTrue(request.messages().stream().anyMatch(m -> m.content().equals("Jake: line 14")));
        assertTrue(request.messages().stream().noneMatch(m -> m.content().equals("Jake: line 2")));
    }

    @Test
    void historyCharBudgetTrimsOldestFirst() {
        String big = "x".repeat(3_900);
        List<SoulTypes.ConversationRecord> history = List.of(
                record(0, SoulTypes.TurnKind.SPOKEN, "Jake: OLDEST " + big),
                record(1, SoulTypes.TurnKind.SPOKEN, "Jake: newer " + big.substring(0, 100)));
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        assertTrue(request.messages().stream().noneMatch(m -> m.content().startsWith("Jake: OLDEST")));
        assertTrue(request.messages().stream().anyMatch(m -> m.content().startsWith("Jake: newer")));
    }

    @Test
    void identityBlocksAreTruncatedPerBot() {
        List<String> identity = List.of("y".repeat(2_000));
        SoulTypes.SoulProfile huge = new SoulTypes.SoulProfile("frens:jake", "Jake",
                identity, List.of(), List.of(), List.of());
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(huge, huge), List.of(), Duration.ofSeconds(60));
        String cast = request.messages().stream()
                .filter(m -> m.content().startsWith("CAST"))
                .findFirst().orElseThrow().content();
        // Two members, each identity bounded — the whole cast block stays under 2 * (cap + names/labels).
        assertTrue(cast.length() < 2 * (SoulGroupPromptAssembler.MAX_IDENTITY_CHARS_PER_BOT + 200),
                "cast block length " + cast.length());
    }

    private static SoulGroupTypes.GroupSceneTurn banterTurn(String seed) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER, owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara"))),
                seed, Instant.EPOCH, UUID.randomUUID());
    }

    @Test
    void banterFinalMessageIsANarratorDirectiveNotAPlayerLine() {
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                banterTurn("it is dusk, rain; Jake slew a mob"),
                List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(60));
        SoulTypes.Message last = request.messages().get(request.messages().size() - 1);
        assertEquals(SoulTypes.Role.USER, last.role());
        assertTrue(last.content().startsWith("[") && last.content().endsWith("]"), last.content());
        assertTrue(last.content().contains("it is dusk, rain; Jake slew a mob"), last.content());
        assertTrue(request.messages().stream().noneMatch(m -> m.content().startsWith("Bradley: ")));
    }

    @Test
    void banterHeardRecordsAreSkippedInHistoryReplayButSpokenReplay() {
        List<SoulTypes.ConversationRecord> history = List.of(
                record(0, SoulTypes.TurnKind.HEARD, SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + "old seed"),
                record(1, SoulTypes.TurnKind.SPOKEN, "Jake: banter line."),
                record(2, SoulTypes.TurnKind.HEARD, "Bradley: real question"));
        SoulTypes.ProviderRequest request = assembler.assemble(UUID.randomUUID(), "m",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                history, Duration.ofSeconds(60));
        assertTrue(request.messages().stream().noneMatch(m -> m.content().contains("old seed")));
        assertTrue(request.messages().stream().anyMatch(m -> m.content().equals("Jake: banter line.")));
        assertTrue(request.messages().stream().anyMatch(m -> m.content().equals("Bradley: real question")));
    }

    @Test
    void requestCarriesModelTimeoutTokensAndCorrelation() {
        UUID correlationId = UUID.randomUUID();
        SoulTypes.ProviderRequest request = assembler.assemble(correlationId, "llama3.2:3b",
                turn("ok"), List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Jake")),
                List.of(), Duration.ofSeconds(45));
        assertEquals(correlationId, request.correlationId());
        assertEquals("llama3.2:3b", request.model());
        assertEquals(Duration.ofSeconds(45), request.timeout());
        assertEquals(SoulGroupPromptAssembler.MAX_OUTPUT_TOKENS, request.maxOutputTokens());
    }

    @Test
    void localSceneDirectiveMarksTheLineAsNotAddressedToTheBot() {
        SoulGroupTypes.GroupSceneTurn turn = localTurn("heading to the ravine");
        SoulTypes.ProviderRequest request = new SoulGroupPromptAssembler().assemble(
                UUID.randomUUID(), "model", turn, List.of(profile("frens:jake", "Jake")), List.of(),
                Duration.ofSeconds(30));

        SoulTypes.Message last = request.messages().get(request.messages().size() - 1);
        assertEquals(SoulTypes.Role.USER, last.role());
        assertTrue(last.content().contains("not to you"),
                "the model must know it is chiming in, not answering");
        assertTrue(last.content().contains("Bradley: heading to the ravine"),
                "the real utterance is speaker-tagged, unlike a banter seed");
    }

    // === Engagement spec §4: three banter directive variants ===

    private static SoulGroupTypes.GroupSceneTurn banterTurn(int rosterSize, boolean addressPlayer) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        List<SoulGroupTypes.SceneParticipant> roster = new ArrayList<>();
        roster.add(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")));
        if (rosterSize > 1) {
            UUID sara = UUID.randomUUID();
            roster.add(new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara")));
        }
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER, owner, "Bradley",
                roster, "seed-text", Instant.EPOCH, UUID.randomUUID(), addressPlayer);
    }

    private SoulTypes.ProviderRequest assembleBanter(SoulGroupTypes.GroupSceneTurn turn) {
        List<SoulTypes.SoulProfile> profiles = new ArrayList<>();
        for (SoulGroupTypes.SceneParticipant participant : turn.roster()) {
            profiles.add(profile("frens:jake", participant.displayName()));
        }
        return assembler.assemble(UUID.randomUUID(), "m", turn, profiles, List.of(),
                Duration.ofSeconds(30));
    }

    private static String lastMessage(SoulTypes.ProviderRequest request) {
        return request.messages().get(request.messages().size() - 1).content();
    }

    @Test
    void groupBanterWithoutFlagKeepsTheShippedDirective() {
        String last = lastMessage(assembleBanter(banterTurn(2, false)));
        assertTrue(last.contains("chat briefly among themselves"));
        assertFalse(last.contains("to Bradley"), "unflagged banter never addresses the player");
    }

    @Test
    void groupBanterWithFlagAppendsThePlayerAddressedOption() {
        String last = lastMessage(assembleBanter(banterTurn(2, true)));
        assertTrue(last.contains("chat briefly among themselves"), "appends, not replaces");
        assertTrue(last.contains("may end by saying one short thing to Bradley"));
    }

    @Test
    void soloBanterDirectiveSpeaksToThePlayerByName() {
        String last = lastMessage(assembleBanter(banterTurn(1, true)));
        assertFalse(last.contains("among themselves"), "there is no 'themselves' for one bot");
        assertTrue(last.contains("Jake may say one short thing to Bradley"));
        assertTrue(last.contains("seed-text"), "the seed still steers the topic");
    }
    private SoulGroupTypes.GroupSceneTurn workTurn(int rosterSize, boolean addressPlayer) {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        List<SoulGroupTypes.SceneParticipant> roster = new ArrayList<>();
        roster.add(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")));
        if (rosterSize > 1) {
            UUID sara = UUID.randomUUID();
            roster.add(new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara")));
        }
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.WORK, owner, "Bradley",
                roster, "seed-text", Instant.EPOCH, UUID.randomUUID(), addressPlayer);
    }

    @Test
    void humanizeTaskStripsThePrefixAndMapsKnownSkills() {
        assertEquals("woodcutting", SoulGroupPromptAssembler.humanizeTask("skill:woodcut"));
        assertEquals("mining", SoulGroupPromptAssembler.humanizeTask("skill:mining"));
        assertEquals("collect dirt", SoulGroupPromptAssembler.humanizeTask("skill:collect_dirt"));
        assertEquals("", SoulGroupPromptAssembler.humanizeTask(""));
        assertEquals("", SoulGroupPromptAssembler.humanizeTask(null));
    }

    @Test
    void groupWorkDirectiveNamesWhatEachBotIsDoing() {
        String last = lastMessage(assembleBanter(workTurn(2, false)));
        assertTrue(last.contains("Jake is woodcutting"), last);
        assertTrue(last.contains("Sara is woodcutting"), last);
        assertTrue(last.contains("without stopping"));
        assertTrue(last.contains("seed-text"));
        assertFalse(last.contains("to Bradley"), "unflagged work scenes never address the player");
    }

    @Test
    void groupWorkDirectiveWithFlagAppendsThePlayerAddressedOption() {
        String last = lastMessage(assembleBanter(workTurn(2, true)));
        assertTrue(last.contains("may end by saying one short thing to Bradley"));
    }

    @Test
    void soloWorkDirectiveSpeaksToThePlayerAndForbidsAnAnswer() {
        String last = lastMessage(assembleBanter(workTurn(1, true)));
        assertTrue(last.contains("Jake is woodcutting and may say one short thing to Bradley"), last);
        assertTrue(last.contains("Bradley does not answer in this scene"));
        assertFalse(last.contains("among themselves"));
    }
    @Test
    void contractForbidsAnsweringForThePlayerAndTrustsStateOverInstinct() {
        String contract = assembleBanter(banterTurn(2, false)).messages().get(0).content();
        assertTrue(contract.contains("never speak on their behalf"), contract);
        assertTrue(contract.contains("must be the LAST line"), contract);
        assertTrue(contract.contains("do not fret about food, shelter"), contract);
    }

    @Test
    void stateBlockSaysWhenTheGroupIsHomeFedAndProvisioned() {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(jake, "Jake", "overworld", "plains",
                0, 64, 0, true, "dusk", "clear", 17f, 20f, 18, 5, "iron sword", 10, 36,
                List.of("food for 12 meals", "40x oak logs"), "cheerful", "IDLE", "", "", "home", "Bradley",
                true, 0, true, Optional.empty());
        SoulTypes.SituationSnapshot base = SoulTypes.SituationSnapshot.empty();
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(base.dangerDistance(), base.hostiles(),
                base.nearbyAnimals(), "oak planks", base.nearbyBlocks(), List.of("campfire (cook food)", "3x chest (storage)"),
                base.facilitySightings(), base.armorStands(), base.blockLight(), base.skyLight(), base.enclosed(),
                base.hasHeadroom(), base.hasEscapeRoute(), base.behaviorMode(), base.following(), base.inCombat(),
                base.postCombatLinger(), base.recentKillCount(), true, base.surfaceRecoveryActive(),
                base.breakingFree(), base.nightTravelActive(), base.companionDays(), base.deathCount(), base.mount(),
                1, base.lastSleepLabel(), Optional.of("Riverside"), base.hunt(), base.lastHobby());
        SoulTypes.GroundingSnapshot grounding = new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                Optional.empty(), situation, Instant.EPOCH, List.of());
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER, owner,
                "Bradley", List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding)),
                "seed-text", Instant.EPOCH, UUID.randomUUID(), false);
        String all = String.join("\n", assembleBanter(turn).messages().stream().map(SoulTypes.Message::content).toList());
        assertTrue(all.contains("hunger 18/20 (well fed)"), all);
        assertTrue(all.contains("food for 12 meals"), all);
        assertTrue(all.contains("AT HOME BASE (Riverside)"), all);
        assertTrue(all.contains("Close by: campfire (cook food), 3x chest (storage)"), all);
        assertEquals(" (hungry)", SoulGroupPromptAssembler.hungerWord(5));
        assertEquals(" (peckish)", SoulGroupPromptAssembler.hungerWord(10));
    }

    // === Conversation ontology Phase 2: the mind reaches the prompt ===

    private static SoulGroupTypes.GroupSceneTurn twoBotBanterTurn(UUID jake) {
        UUID owner = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.BANTER, owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara"))),
                "seed-text", Instant.EPOCH, UUID.randomUUID(), false);
    }

    private static List<SoulTypes.SoulProfile> twoProfiles() {
        return List.of(profile("frens:jake", "Jake"), profile("frens:jake", "Sara"));
    }

    @Test
    void stanceClauseAndOpenThreadsReachThePrompt() {
        UUID jake = UUID.randomUUID();
        SoulTypes.SoulMind mind = new SoulTypes.SoulMind(1, new SoulTypes.Stance(1, 2, 3),
                List.of(new SoulTypes.OpenThread(jake, "Did you find the iron?", 0L, false)),
                List.of(), Set.of(), 0L, 1, -1);
        SoulGroupPromptAssembler withMind = new SoulGroupPromptAssembler(
                id -> id.equals(jake) ? Optional.of(mind) : Optional.empty());
        SoulTypes.ProviderRequest req = withMind.assemble(UUID.randomUUID(), "m", twoBotBanterTurn(jake),
                twoProfiles(), List.of(), Duration.ofSeconds(5));
        String state = req.messages().get(2).content();
        assertTrue(state.contains("Jake:") && state.contains("wary of Bradley, fed up with being ignored"), state);
        String saraLine = state.lines().filter(l -> l.startsWith("Sara:")).findFirst().orElse("");
        assertFalse(saraLine.contains("wary"), "only Jake has a mind: " + saraLine);
        String threads = req.messages().get(3).content();
        assertTrue(threads.startsWith("OPEN THREADS"), threads);
        assertTrue(threads.contains("Jake still wants to know: \"Did you find the iron?\""), threads);
        assertEquals(SoulTypes.Role.SYSTEM, req.messages().get(3).role());
    }

    @Test
    void expiredThreadsAndBaselineStanceLeaveThePromptUntouched() {
        UUID jake = UUID.randomUUID();
        SoulTypes.SoulMind mind = new SoulTypes.SoulMind(1, SoulTypes.Stance.BASELINE,
                List.of(new SoulTypes.OpenThread(jake, "Did you find the iron?", 0L, true)),
                List.of(), Set.of(), 0L, 1, -1);
        SoulGroupPromptAssembler withMind = new SoulGroupPromptAssembler(id -> Optional.of(mind));
        SoulTypes.ProviderRequest req = withMind.assemble(UUID.randomUUID(), "m", twoBotBanterTurn(jake),
                twoProfiles(), List.of(), Duration.ofSeconds(5));
        assertFalse(req.messages().stream().anyMatch(m -> m.content().startsWith("OPEN THREADS")));
        assertFalse(req.messages().get(2).content().contains("wary"), req.messages().get(2).content());
    }

    @Test
    void openThreadsBlockIsBounded() {
        UUID jake = UUID.randomUUID();
        String longQuestion = "Did you find the iron " + "and the coal ".repeat(40) + "yet?";
        SoulTypes.SoulMind mind = new SoulTypes.SoulMind(1, SoulTypes.Stance.BASELINE,
                List.of(new SoulTypes.OpenThread(jake, longQuestion, 0L, false),
                        new SoulTypes.OpenThread(jake, longQuestion, 1L, false)),
                List.of(), Set.of(), 0L, 1, -1);
        SoulGroupPromptAssembler withMind = new SoulGroupPromptAssembler(id -> Optional.of(mind));
        SoulTypes.ProviderRequest req = withMind.assemble(UUID.randomUUID(), "m", twoBotBanterTurn(jake),
                twoProfiles(), List.of(), Duration.ofSeconds(5));
        String threads = req.messages().get(3).content();
        assertTrue(threads.startsWith("OPEN THREADS"), threads);
        assertTrue(threads.length() <= SoulGroupPromptAssembler.MAX_THREADS_BLOCK_CHARS, "" + threads.length());
    }

    @Test
    void noMindMeansNoThreadsBlock() {
        SoulTypes.ProviderRequest req = new SoulGroupPromptAssembler().assemble(UUID.randomUUID(), "m",
                twoBotBanterTurn(UUID.randomUUID()), twoProfiles(), List.of(), Duration.ofSeconds(5));
        assertFalse(req.messages().stream().anyMatch(m -> m.content().startsWith("OPEN THREADS")));
    }

    // === ABOUT <owner> block (memory digest: what the owner has said, as each bot remembers) ===

    private static SoulGroupTypes.GroupSceneTurn playerTurnWithOwner(UUID owner, UUID jake, UUID sara) {
        return new SoulGroupTypes.GroupSceneTurn(SoulGroupTypes.SceneKind.PLAYER, owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(jake, "frens:jake", "Jake", grounding(jake, "Jake")),
                        new SoulGroupTypes.SceneParticipant(sara, "frens:jake", "Sara", grounding(sara, "Sara"))),
                "hi", Instant.EPOCH, UUID.randomUUID(), false);
    }

    private static SoulTypes.SoulMind mindRemembering(UUID owner, String... facts) {
        List<SoulTypes.PlayerMemory> memories = new ArrayList<>();
        for (String fact : facts) {
            memories.add(new SoulTypes.PlayerMemory(owner, 3, fact, 8, -1, List.of()));
        }
        return new SoulTypes.SoulMind(1, SoulTypes.Stance.BASELINE, List.of(), List.of(), Set.of(),
                0L, 1, -1, memories, List.of(), java.util.Map.of());
    }

    @Test
    void aboutOwnerBlockNamesEachBotThatRemembersSomething() {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        SoulTypes.SoulMind jakeMind = mindRemembering(owner, "Bradley hates the Nether");
        SoulGroupPromptAssembler withMind = new SoulGroupPromptAssembler(
                id -> id.equals(jake) ? Optional.of(jakeMind) : Optional.empty());
        SoulTypes.ProviderRequest req = withMind.assemble(UUID.randomUUID(), "m",
                playerTurnWithOwner(owner, jake, sara), twoProfiles(), List.of(),
                Duration.ofSeconds(5));
        String about = req.messages().stream()
                .map(SoulTypes.Message::content)
                .filter(c -> c.startsWith("ABOUT Bradley"))
                .findFirst().orElse("");
        assertTrue(about.startsWith("ABOUT Bradley (things Bradley said, as remembered)\n"), about);
        assertTrue(about.contains("Jake remembers:"), about);
        assertTrue(about.contains("- Bradley hates the Nether"), about);
        assertFalse(about.contains("Sara remembers:"), about);
        int idx = 0;
        for (int i = 0; i < req.messages().size(); i++) {
            if (req.messages().get(i).content().startsWith("ABOUT Bradley")) {
                idx = i;
            }
        }
        assertEquals(SoulTypes.Role.SYSTEM, req.messages().get(idx).role());
        // Right after CURRENT STATE (no OPEN THREADS in this fixture).
        assertTrue(req.messages().get(idx - 1).content().startsWith("CURRENT STATE"),
                req.messages().get(idx - 1).content());
    }

    @Test
    void noPlayerMemoriesMeansNoAboutBlock() {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        SoulTypes.ProviderRequest none = new SoulGroupPromptAssembler().assemble(UUID.randomUUID(), "m",
                playerTurnWithOwner(owner, jake, sara), twoProfiles(), List.of(), Duration.ofSeconds(5));
        assertFalse(none.messages().stream().anyMatch(m -> m.content().startsWith("ABOUT ")));

        SoulGroupPromptAssembler emptyMinds = new SoulGroupPromptAssembler(
                id -> Optional.of(mindRemembering(owner)));
        SoulTypes.ProviderRequest req = emptyMinds.assemble(UUID.randomUUID(), "m",
                playerTurnWithOwner(owner, jake, sara), twoProfiles(), List.of(), Duration.ofSeconds(5));
        assertFalse(req.messages().stream().anyMatch(m -> m.content().startsWith("ABOUT ")));
    }

    @Test
    void aboutBlockIgnoresMemoriesAboutOtherPlayers() {
        UUID owner = UUID.randomUUID();
        UUID jake = UUID.randomUUID();
        UUID sara = UUID.randomUUID();
        SoulTypes.SoulMind other = mindRemembering(UUID.randomUUID(), "Someone else likes fishing");
        SoulGroupPromptAssembler withMind = new SoulGroupPromptAssembler(id -> Optional.of(other));
        SoulTypes.ProviderRequest req = withMind.assemble(UUID.randomUUID(), "m",
                playerTurnWithOwner(owner, jake, sara), twoProfiles(), List.of(), Duration.ofSeconds(5));
        assertFalse(req.messages().stream().anyMatch(m -> m.content().startsWith("ABOUT ")));
    }
}
