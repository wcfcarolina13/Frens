# Soul Conversation Ontology Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give companion bots continuity across scenes — a persisted per-bot `mind.json` holding stance toward the player, open (unanswered) questions, deterministic day memories, and the first-sighting registry — and feed all of it into the group prompt and the banter seed.

**Architecture:** New pure `SoulMindOps` (rules, caps, decay, consolidation) + `SoulStore` load/save of `mind.json` on the existing single writer thread. Hooks: the event observer detects Minecraft day rollover -> runtime consolidates; scene playback hands delivered lines to the runtime -> open-thread extraction; addressed chat marks threads answered. Prompt/seed: stance clause in `stateBlock`, an `OPEN THREADS` block, memory/thread anchors in `SoulBanterSeed`, and the seen-registry read from minds.

**Tech Stack:** Java 21, Jackson (store JSON), JUnit 5 (`./gradlew test --tests 'net.wcfcarolina13.GameAI.souls.*' -q`). Build: `./gradlew build -x test -q`.

**Spec:** `docs/superpowers/specs/2026-08-29-frens-soul-conversation-ontology-phase2.md`

## Global Constraints
- Package base `src/main/java/net/wcfcarolina13/GameAI/souls/`; tests under `src/test/java/net/wcfcarolina13/GameAI/souls/`.
- No LLM calls anywhere in this plan; every rule is deterministic and unit-tested.
- All store mutations run on the `SoulStore` writer thread via `submit(...)` with tmp + `atomicReplace` writes; readers use the cached copy (`cachedMind`) — never block the server thread on a store future.
- Stance ints are clamped 0..6; baselines trust 3, exasperation 0, curiosity 3. Caps: 3 open threads, 30 memories, 400 seen keys. Thread TTL 10 minutes real time. Seed anchor weights: memory 4, thread 5 (grounding 1-3 < memory 4 < change 5 < HIGH event 6).
- `SoulEvent.worldTick` is server ticks since start (resets per launch) — never use it for "since last time"; use `occurredAt` (Instant) and the Minecraft day number.
- Every task ends with `./gradlew build -x test -q` clean and the souls suite green, then a commit prefixed `souls:` ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Stage only files you touched. Do not touch `gradle.properties` or `changelog.md`.

---

### Task 2a: Mind types, pure ops, store persistence

**Files:**
- Modify: `SoulTypes.java` (add records after `KnowledgeMemory`, ~line 262)
- Create: `SoulMindOps.java`
- Modify: `SoulStore.java` (mind load/save, `eventsSince`, `trimEvents`, paths)
- Test: create `SoulMindOpsTest.java`; extend `SoulStoreTest.java`

**Interfaces (produces):**
```java
// SoulTypes
public record Stance(int trust, int exasperation, int curiosity) {
    public static final Stance BASELINE = new Stance(3, 0, 3);
    public Stance { trust = clamp(trust); exasperation = clamp(exasperation); curiosity = clamp(curiosity); }
    private static int clamp(int v) { return Math.max(0, Math.min(6, v)); }
}
public record OpenThread(UUID askerBotId, String question, long askedAtMs, boolean expired) {
    public OpenThread { Objects.requireNonNull(askerBotId); question = question == null ? "" : question.trim(); }
}
public record DayMemory(int day, String topic, String phrase, String place, List<String> participants,
                        int salience, int lastRecalledDay) { /* nulls -> "" / List.of() */ }
public record SoulMind(int schemaVersion, Stance playerStance, List<OpenThread> threads,
                       List<DayMemory> memories, Set<String> seen, long lastConsolidatedAtMs,
                       int lastDay, int lastTaskTrustDay) {
    public SoulMind { /* null -> BASELINE / List.of() / Set.of(); copies */ }
    public static SoulMind empty() { return new SoulMind(1, Stance.BASELINE, List.of(), List.of(), Set.of(), 0L, -1, -1); }
}
```
```java
// SoulMindOps (package-private final class, private ctor)
static final int MAX_THREADS = 3, MAX_MEMORIES = 30, MAX_SEEN = 400, MAX_QUESTION_CHARS = 120,
        MAX_MEMORY_PHRASE_CHARS = 80, MEMORIES_PER_DAY = 3;
static final long THREAD_TTL_MS = 600_000L;
static SoulMind withSeen(SoulMind mind, Set<String> newKeys)               // adds; evicts oldest (insertion order) past MAX_SEEN
static SoulMind openThread(SoulMind mind, OpenThread thread)               // appends; drops oldest past MAX_THREADS; question truncated
static SoulMind markAnswered(SoulMind mind)                                // if any non-expired thread: trust+1, curiosity-1; removes ALL threads
static SoulMind expireThreads(SoulMind mind, long nowMs)                   // open threads older than TTL -> expired=true, exasperation+1 each
static SoulMind dropExpired(SoulMind mind)                                 // removes expired threads (after they were recalled once)
static SoulMind noteTaskGiven(SoulMind mind, int day)                      // trust+1 once per day (lastTaskTrustDay guard)
static SoulMind noteOwnerHurt(SoulMind mind)                               // curiosity+1
static SoulMind noteRecalled(SoulMind mind, String topic, int day)         // sets lastRecalledDay on the memory with that topic
static Optional<String> extractQuestion(String lastLineText, String ownerName, boolean addressPlayer)
        // trimmed text ends with '?' and (addressPlayer || SoulGroupResponseValidator.addressesOwner(text, normalized owner)) -> text (<= MAX_QUESTION_CHARS)
static SoulMind consolidate(SoulMind mind, List<SoulEvent> events, int day, String place,
                            Function<UUID, String> nameOf, long nowMs)
        // 1. skip SLEEP/WAKE and sleep tasks (SoulBanterSeed.topicOf(event).equals("sleep")); group by SoulBanterSeed.topicOf(event)
        // 2. score = sum(HIGH 6 / NORMAL 3 / LOW 1) + min(count, 3); drop topics already in memories with the same day
        // 3. top MEMORIES_PER_DAY -> DayMemory(day, topic, SoulBanterSeed.phraseFor(latest event of the group) truncated to 80, place, names of event.participants(), score, -1)
        // 4. decay: every existing memory salience-1 (evict at 0); stance one step toward BASELINE per field; expireThreads(nowMs) is NOT applied here (2b ticks it)
        // 5. cap memories at MAX_MEMORIES (drop lowest salience first); set lastConsolidatedAtMs = nowMs, lastDay = day
static String stanceClause(Stance s, String playerName)
        // trust <=1 -> "wary of <P>"; trust >=5 -> "would follow <P> anywhere"; exasperation >=4 -> "sulking at being ignored"; >=2 -> "fed up with being ignored"; curiosity >=5 -> "full of questions for <P>"; join with ", "; "" when nothing applies
static List<SoulBanterSeed.Anchor> anchors(SoulMind mind, String botName, int currentDay, RandomGenerator random)
        // expired threads -> Anchor("unanswered question", botName + " never got an answer about \"" + question + "\"", 5)
        // memories -> Anchor("memory:" + topic, "remember when " + phrase + (day < currentDay ? " on day " + day : ""), 4) — skip memories recalled within the last 3 days; at most 2 memory anchors (highest salience, then random)
```
`SoulBanterSeed.phraseFor(SoulEvent)` and `topicOf(SoulEvent)` are already package-private static (1.1.197). Make `SoulBanterSeed.Anchor` accessible (it is a package-private record — fine).
```java
// SoulStore additions
public CompletableFuture<SoulTypes.SoulMind> mind(UUID botId)                         // load (cached)
public Optional<SoulTypes.SoulMind> cachedMind(UUID botId)
public CompletableFuture<SoulTypes.SoulMind> updateMind(UUID botId, UnaryOperator<SoulTypes.SoulMind> update)  // load -> update -> save if changed -> returns new
public CompletableFuture<List<SoulTypes.SoulEvent>> eventsSince(UUID botId, Instant after)
public CompletableFuture<Integer> trimEvents(UUID botId, int keepLast)                // rewrites events.jsonl atomically; returns removed count
private Path mindFile(UUID botId)  // botDir/mind.json
```

- [ ] **Step 1: Tests** — `SoulMindOpsTest` (helper `event(type, salience, facts, participants)` like `SoulBanterSeedTest`'s, with `occurredAt = Instant.ofEpochMilli(tick)`):
```java
    @Test void stanceClampsAndBaseline() {
        assertEquals(new SoulTypes.Stance(6, 0, 0), new SoulTypes.Stance(9, -2, 0));
        assertEquals("", SoulMindOps.stanceClause(SoulTypes.Stance.BASELINE, "Roti"));
        assertEquals("wary of Roti", SoulMindOps.stanceClause(new SoulTypes.Stance(1, 0, 3), "Roti"));
        assertEquals("would follow Roti anywhere, fed up with being ignored", SoulMindOps.stanceClause(new SoulTypes.Stance(5, 2, 3), "Roti"));
    }
    @Test void threadsOpenAnswerExpireAndCap() {
        SoulTypes.SoulMind m = SoulTypes.SoulMind.empty();
        UUID bob = UUID.randomUUID();
        for (int i = 0; i < 4; i++) m = SoulMindOps.openThread(m, new SoulTypes.OpenThread(bob, "q" + i + "?", 1_000L * i, false));
        assertEquals(3, m.threads().size());
        assertEquals("q1?", m.threads().get(0).question(), "oldest evicted");
        SoulTypes.SoulMind answered = SoulMindOps.markAnswered(m);
        assertTrue(answered.threads().isEmpty());
        assertEquals(4, answered.playerStance().trust());
        assertEquals(2, answered.playerStance().curiosity());
        SoulTypes.SoulMind expired = SoulMindOps.expireThreads(m, 3_000L + SoulMindOps.THREAD_TTL_MS + 1);
        assertTrue(expired.threads().stream().allMatch(SoulTypes.OpenThread::expired));
        assertEquals(3, expired.playerStance().exasperation());
        assertTrue(SoulMindOps.dropExpired(expired).threads().isEmpty());
        assertTrue(SoulMindOps.markAnswered(expired).threads().isEmpty());
        assertEquals(3, SoulMindOps.markAnswered(expired).playerStance().trust(), "expired threads earn no trust");
    }
    @Test void questionExtraction() {
        assertEquals(Optional.of("Did you find the iron, Roti?"), SoulMindOps.extractQuestion("Did you find the iron, Roti?", "RotiWokeman", false));
        assertEquals(Optional.of("Where next?"), SoulMindOps.extractQuestion("Where next?", "RotiWokeman", true));
        assertTrue(SoulMindOps.extractQuestion("Where next?", "RotiWokeman", false).isEmpty(), "not addressed");
        assertTrue(SoulMindOps.extractQuestion("Fine by me.", "RotiWokeman", true).isEmpty(), "not a question");
        assertEquals(SoulMindOps.MAX_QUESTION_CHARS, SoulMindOps.extractQuestion("a".repeat(200) + "?", "R", true).orElseThrow().length());
    }
    @Test void consolidationKeepsTopTopicsDecaysAndCaps() {
        UUID roti = UUID.randomUUID();
        List<SoulTypes.SoulEvent> events = List.of(
                event(SoulTypes.EventType.BOT_DAMAGE, SoulTypes.Salience.NORMAL, Map.of("amount", "5", "source", "skeleton"), List.of(roti)),
                event(SoulTypes.EventType.BOT_DAMAGE, SoulTypes.Salience.NORMAL, Map.of("amount", "3", "source", "skeleton"), List.of()),
                event(SoulTypes.EventType.MOB_KILLED, SoulTypes.Salience.NORMAL, Map.of("mob", "Zombie"), List.of()),
                event(SoulTypes.EventType.TASK_COMPLETED, SoulTypes.Salience.LOW, Map.of("task", "skill:woodcut", "category", "skill"), List.of()),
                event(SoulTypes.EventType.SLEEP, SoulTypes.Salience.LOW, Map.of(), List.of()),
                event(SoulTypes.EventType.TASK_STARTED, SoulTypes.Salience.LOW, Map.of("task", "skill:sleep", "category", "skill"), List.of()),
                event(SoulTypes.EventType.SELF_RESCUE, SoulTypes.Salience.HIGH, Map.of(), List.of()));
        SoulTypes.SoulMind before = new SoulTypes.SoulMind(1, new SoulTypes.Stance(5, 3, 1),
                List.of(), List.of(new SoulTypes.DayMemory(2, "the work", "finished fishing", "river", List.of(), 1, -1)),
                Set.of(), 0L, 2, -1);
        SoulTypes.SoulMind after = SoulMindOps.consolidate(before, events, 3, "forest", id -> id.equals(roti) ? "Roti" : "?", 999L);
        List<String> topics = after.memories().stream().map(SoulTypes.DayMemory::topic).toList();
        assertEquals(List.of("getting stuck", "getting hurt", "fighting"), topics, "top 3 by score; sleep excluded; old memory decayed to 0 and evicted");
        SoulTypes.DayMemory hurt = after.memories().get(1);
        assertEquals("took a beating from a skeleton", hurt.phrase());
        assertEquals(List.of("Roti"), hurt.participants());
        assertEquals("forest", hurt.place());
        assertEquals(new SoulTypes.Stance(4, 2, 2), after.playerStance(), "one step toward baseline");
        assertEquals(3, after.lastDay());
        assertEquals(999L, after.lastConsolidatedAtMs());
    }
    @Test void anchorsFromMemoriesAndExpiredThreads() {
        UUID bob = UUID.randomUUID();
        SoulTypes.SoulMind m = new SoulTypes.SoulMind(1, SoulTypes.Stance.BASELINE,
                List.of(new SoulTypes.OpenThread(bob, "Did you find iron?", 0L, true), new SoulTypes.OpenThread(bob, "Open one?", 0L, false)),
                List.of(new SoulTypes.DayMemory(1, "fighting", "slew a zombie", "forest", List.of(), 4, -1),
                        new SoulTypes.DayMemory(3, "getting hurt", "took a beating", "cave", List.of(), 6, 3)),
                Set.of(), 0L, 3, -1);
        List<SoulBanterSeed.Anchor> anchors = SoulMindOps.anchors(m, "Bob", 4, new Random(1));
        assertEquals(2, anchors.size());
        assertEquals("unanswered question", anchors.get(0).topic());
        assertEquals("Bob never got an answer about \"Did you find iron?\"", anchors.get(0).phrase());
        assertEquals(5, anchors.get(0).weight());
        assertEquals("memory:fighting", anchors.get(1).topic());
        assertEquals("remember when slew a zombie on day 1", anchors.get(1).phrase());
        assertEquals(4, anchors.get(1).weight());
        // "getting hurt" was recalled on day 3 -> skipped until day 6
    }
    @Test void seenRegistryCaps() {
        SoulTypes.SoulMind m = SoulTypes.SoulMind.empty();
        for (int i = 0; i < SoulMindOps.MAX_SEEN + 5; i++) m = SoulMindOps.withSeen(m, Set.of("k" + i));
        assertEquals(SoulMindOps.MAX_SEEN, m.seen().size());
        assertFalse(m.seen().contains("k0"));
    }
```
  `SoulStoreTest` additions:
```java
    @Test void mindRoundTripAndUpdate() throws Exception {
        UUID bot = UUID.randomUUID();
        assertEquals(SoulTypes.SoulMind.empty(), store.mind(bot).get(2, SECONDS));
        SoulTypes.SoulMind updated = store.updateMind(bot, m -> SoulMindOps.noteOwnerHurt(m)).get(2, SECONDS);
        assertEquals(4, updated.playerStance().curiosity());
        assertTrue(Files.isRegularFile(botDir(bot).resolve("mind.json")));
        assertEquals(updated, store.cachedMind(bot).orElseThrow());
        SoulStore reopened = new SoulStore(worldRoot, executor);
        assertEquals(updated, reopened.mind(bot).get(2, SECONDS));
    }
    @Test void eventsSinceAndTrim() throws Exception {
        UUID bot = UUID.randomUUID();
        for (int i = 0; i < 5; i++) store.appendEvent(bot, eventAt(Instant.ofEpochMilli(1000L * i))).get(2, SECONDS);
        assertEquals(2, store.eventsSince(bot, Instant.ofEpochMilli(2500L)).get(2, SECONDS).size());
        assertEquals(3, store.trimEvents(bot, 2).get(2, SECONDS));
        assertEquals(2, store.recentEvents(bot, 10).get(2, SECONDS).size());
    }
```
  (`eventAt(Instant)` builds a `SoulEvent` with that `occurredAt`; `Set` must be serialised as a JSON array — use `LinkedHashSet` internally and Jackson handles `Set` fine.)
- [ ] **Step 2: Run** the two test classes -> FAIL.
- [ ] **Step 3: Implement** records, `SoulMindOps`, store methods. `SoulMind`'s compact constructor: `seen = seen == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(seen))` (keeps insertion order for eviction). Store: `cachedMinds` map (`ConcurrentHashMap`), `loadMind` (missing file -> `SoulMind.empty()`), `saveMind` (tmp + `atomicReplace`, then cache); `updateMind` = `submit(() -> { SoulMind cur = loadMind(botId); SoulMind next = update.apply(cur); if (!next.equals(cur)) saveMind(botId, next); return next; })`; `eventsSince` = `loadTranscript(eventsFile, SoulEvent.class)` filtered `occurredAt.isAfter(after)`; `trimEvents` = load all, keep last N, write to `events.jsonl.tmp-<uuid>` line by line, `atomicReplace`, return removed. Add `import java.util.function.UnaryOperator`.
- [ ] **Step 4: Run** souls tests -> PASS; build clean.
- [ ] **Step 5: Commit** `souls: mind.json — stance, open threads, day memories, seen-registry (types, pure ops, store)`.

### Task 2c: Prompt + seed consume the mind (pure, no wiring)

**Files:**
- Modify: `SoulGroupPromptAssembler.java` (ctor, `stateBlock`, new `threadsBlock`)
- Modify: `SoulBanterSeed.java` (`buildSeed` overload, `isEventTopic`)
- Modify: `SoulSceneDiff.java` (no signature change; only Javadoc noting the registry is now the roster union)
- Test: extend `SoulGroupPromptAssemblerTest.java`, `SoulBanterSeedTest.java`

**Interfaces:**
- Consumes: `SoulTypes.SoulMind`, `SoulMindOps.stanceClause`, `SoulMindOps.anchors`.
- Produces: `public SoulGroupPromptAssembler()` (unchanged, mind lookup = always empty) and `public SoulGroupPromptAssembler(Function<UUID, Optional<SoulTypes.SoulMind>> mindLookup)`; `static final int MAX_THREADS_BLOCK_CHARS = 200`; `SoulBanterSeed.buildSeed(rosterGroundings, eventsPerBot, playerName, playerActivity, random, recentTopics, changeAnchors, recentActs, List<SoulBanterSeed.Anchor> mindAnchors)` (old 8-arg overload delegates with `List.of()`); `SoulBanterSeed.isEventTopic` returns true for `"unanswered question"` and any topic starting with `"memory:"`.

- [ ] **Step 1: Tests** — `SoulGroupPromptAssemblerTest`: find an existing test that assembles a two-bot banter turn and inspects `request.messages()`. Add:
```java
    @Test void stanceClauseAndOpenThreadsReachThePrompt() {
        UUID jake = JAKE_ID; // reuse the fixture ids the file already uses
        SoulTypes.SoulMind mind = new SoulTypes.SoulMind(1, new SoulTypes.Stance(1, 2, 3),
                List.of(new SoulTypes.OpenThread(jake, "Did you find the iron?", 0L, false)),
                List.of(), Set.of(), 0L, 1, -1);
        SoulGroupPromptAssembler withMind = new SoulGroupPromptAssembler(id -> id.equals(jake) ? Optional.of(mind) : Optional.empty());
        SoulTypes.ProviderRequest req = withMind.assemble(UUID.randomUUID(), "m", twoBotBanterTurn(), profiles(), List.of(), Duration.ofSeconds(5));
        String state = req.messages().get(2).content();
        assertTrue(state.contains("Jake:") && state.contains("wary of Bradley, fed up with being ignored"), state);
        String threads = req.messages().get(3).content();
        assertTrue(threads.startsWith("OPEN THREADS"), threads);
        assertTrue(threads.contains("Jake still wants to know: \"Did you find the iron?\""), threads);
    }
    @Test void noMindMeansNoThreadsBlock() {
        SoulTypes.ProviderRequest req = new SoulGroupPromptAssembler().assemble(UUID.randomUUID(), "m", twoBotBanterTurn(), profiles(), List.of(), Duration.ofSeconds(5));
        assertFalse(req.messages().stream().anyMatch(m -> m.content().startsWith("OPEN THREADS")));
    }
```
  (adapt fixture names to the file; the owner display name in the fixture must be used in the expected clause.) `SoulBanterSeedTest`:
```java
    @Test void mindAnchorsJoinThePoolAndMemoryTopicsCountAsEvents() {
        assertTrue(SoulBanterSeed.isEventTopic("memory:fighting"));
        assertTrue(SoulBanterSeed.isEventTopic("unanswered question"));
        SoulBanterSeed.Seed seed = SoulBanterSeed.buildSeed(List.of(grounding()), List.of(List.of()), "Roti", "",
                new Random(3), Set.of(), List.of(), List.of(),
                List.of(new SoulBanterSeed.Anchor("unanswered question", "Bob never got an answer about \"iron?\"", 5)));
        assertTrue(seed.text().contains("never got an answer"), seed.text());
    }
```
- [ ] **Step 2: Run** -> FAIL.
- [ ] **Step 3: Implement** — assembler: field `mindLookup`; `stateBlock` appends `", " + clause` when `mindLookup.apply(participant.botId()).map(m -> SoulMindOps.stanceClause(m.playerStance(), turn.ownerDisplayName())).orElse("")` is non-empty; `threadsBlock(turn)` builds `"OPEN THREADS\n" + "<Name> still wants to know: \"<q>\"\n"` for each participant's non-expired threads, truncated to `MAX_THREADS_BLOCK_CHARS`, returns `Optional<Message>` (SYSTEM) — inserted in `assemble` right after `stateBlock` when present. Seed: new overload adds `mindAnchors` to `anchors` before events; `isEventTopic` updated. Note: memory anchors are weight 4 so a HIGH event or a change still wins the primary pick.
- [ ] **Step 4: Run** souls tests -> PASS; build clean.
- [ ] **Step 5: Commit** `souls: stance clause + OPEN THREADS block in the group prompt; mind anchors in the banter seed`.

### Task 2b: Hooks and wiring (observer day rollover, thread lifecycle, director)

**Files:**
- Modify: `SoulEventObserver.java` (`Observation` gains `int day`; `EventSink` gains `default void onNewDay(UUID botId, int day, String biome) {}`; production sink -> `runtime.onNewDay`)
- Modify: `SoulRuntime.java` (mind passthroughs, `onNewDay`, `noteThreadAnswered`, thread extraction in `sceneDelivered`, assembler ctor with mind lookup, `noteAddressedChat` -> answered)
- Modify: `GroupScenePlayback.java` (`LineCommitter.sceneDelivered` gains `List<SoulGroupTypes.SceneLine> delivered`)
- Modify: `SoulBanterDirector.java` (`AudienceMemory.seen` removed; seen = union of roster minds; new keys written back via `updateMind`; mind anchors passed to `buildSeed`; `noteRecalled` when the seed's topic starts with `memory:`; audiences idle > 1 h evicted)
- Modify: `SoulLocalDirector.java` — none required (reply windows unchanged)
- Test: extend `SoulEventObserverTest.java`, `SoulBanterDirectorTest.java` (if the director has a testable seam for minds; otherwise cover through `SoulMindOps` and keep wiring minimal)

**Interfaces:**
- Consumes everything from 2a/2c.
- Produces: `SoulRuntime.cachedMind(UUID)`, `SoulRuntime.onNewDay(UUID botId, int day, String biome)`, `SoulRuntime.noteThreadAnswered(UUID botId)`, `SoulRuntime.mindsFor(List<UUID>)` (cached, missing -> empty).

- [ ] **Step 1: Observer test** — in `SoulEventObserverTest` (it drives `observe(Observation)` with a recording sink): add a `day` field to the `Observation` record (after `worldTick`), update every constructor call in tests, and add:
```java
    @Test void dayRolloverNotifiesTheSinkOncePerDay() {
        observe(day = 4); observe(day = 4); observe(day = 5); observe(day = 5);
        assertEquals(List.of(5), sink.newDays);  // first observation seeds; same-day repeats are silent
    }
```
  (write it with the file's own helpers; the sink records `onNewDay(botId, day, biome)` calls).
- [ ] **Step 2: Run** -> FAIL. **Step 3: Implement** observer: `lastDay` map; in `observe`, after `firstSeen` return: `Integer prevDay = lastDay.put(botId, day)`; if `prevDay != null && prevDay != day` -> `sink.onNewDay(botId, day, biome)`. `buildObservation` computes `day = (int) (bot.getEntityWorld().getTimeOfDay() / 24000L)`. Production sink: `runtime.onNewDay(botId, day, biome)`.
  Runtime:
  - `onNewDay(botId, day, biome)` (server thread): build `Map<UUID,String> names` from `server.getPlayerManager().getPlayerList()` (uuid -> name); `store.eventsSince(botId, lastConsolidatedInstant)` where the instant comes from `cachedMind(botId).lastConsolidatedAtMs` (0 -> `Instant.EPOCH`), then `store.updateMind(botId, m -> SoulMindOps.consolidate(m, events, day, biome, id -> names.getOrDefault(id, "someone"), System.currentTimeMillis()))`, then `store.trimEvents(botId, 200)`; log `[souls] mind consolidated bot={} day={} memories={}`; all `.exceptionally` -> warn.
  - `noteThreadAnswered(botId)`: `store.updateMind(botId, SoulMindOps::markAnswered)` (fire-and-forget). Call it from `submitTurn` (DM PLAYER turn for `turn.botId()` — find the accepted turn's bot id field) and from `submitGroupTurn` when `turn.kind() == PLAYER` for every roster bot; also from the existing static `noteAddressedChat(player)` for every registered bot of that player (`BotRegistry.ids()` filtered by `hasActiveProfile`).
  - Thread expiry tick: in the runtime's existing per-tick/periodic path (grep `tick(` in `SoulRuntime` — the local director's `tick()` is driven from somewhere; add alongside it) every 600 ticks: for each bot with a cached mind having non-expired threads older than TTL -> `store.updateMind(botId, m -> SoulMindOps.expireThreads(m, now))`.
  - Task/hurt stance rules: in `recordEvent(botId, event)` before appending: `TASK_STARTED` with fact `category` not equal to `"hobby"` -> `store.updateMind(botId, m -> SoulMindOps.noteTaskGiven(m, currentDay(botId)))` where `currentDay` reads the bot's world time on the server thread (the observer's hooks run there; if unavailable use `cachedMind.lastDay()`); `OWNER_DAMAGE` -> `noteOwnerHurt`.
  - `LineCommitter.sceneDelivered(turn, deliveredLines, lastSpeakerIndex, List<SceneLine> delivered)`: keep the existing local-director logic, then if `turn.kind().isNarratorSeeded() && lastSpeakerIndex >= 0 && !delivered.isEmpty()`: `SoulMindOps.extractQuestion(delivered.get(delivered.size()-1).text(), turn.ownerDisplayName(), turn.addressPlayer())` -> `store.updateMind(askerBotId, m -> SoulMindOps.openThread(m, new OpenThread(askerBotId, q, now, false)))`. `GroupScenePlayback.finish` passes the delivered list it already tracks (add a `List<SceneLine> deliveredLines` to its per-scene state if only a count exists).
  - `groupPromptAssembler = new SoulGroupPromptAssembler(this::cachedMind)`.
  Director: remove `AudienceMemory.seen`; in `fireScene` build `Set<String> seen = new LinkedHashSet<>()` from the union of `runtime.cachedMind(botId).seen()` over `rosterIds`; after `SoulSceneDiff.diff(..., seen, ...)` compute `newKeys = seen minus union` and if non-empty `runtime.updateMinds(rosterIds, m -> SoulMindOps.withSeen(m, newKeys))` (add that runtime helper); build `mindAnchors` = concat of `SoulMindOps.anchors(mind, botName, currentDay, random)` per roster bot (day from `mind.lastDay()`, or the first bot's world day if you have it) and pass to the 9-arg `buildSeed`; after a fire, if `seeded.topic().startsWith("memory:")` -> `updateMind(botId-of-that-memory..., noteRecalled)` — simplest: apply `noteRecalled(m, topic.substring(7), day)` to every roster bot (only the holder changes); if topic is `"unanswered question"` -> `dropExpired` on every roster bot. Evict `memories` entries whose `lastGrounding` is older than 1 h (store a `long lastFiredMs` in `AudienceMemory`).
- [ ] **Step 4: Run** souls tests -> PASS (update any test constructing `Observation` or implementing `LineCommitter`); build clean.
- [ ] **Step 5: Commit** `souls: day-rollover consolidation, open-thread lifecycle, seen-registry from minds, director wiring (ontology Phase 2)`.
