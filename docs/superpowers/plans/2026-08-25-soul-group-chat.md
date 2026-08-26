# Soul Group Chat (PARTY Channel) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "bots, …" or "Jake and Sara, …" triggers a one-turn group scene: the speaker's own soul-bound bots within 32 blocks answer via one orchestration LLM call, played back line-by-line with per-speaker positional voice to everyone in earshot.

**Architecture:** Additive parallel path beside the DM soul pipeline. The party channel reuses `SoulStore` (a second instance rooted at `<world>/frens/party/v1`), the existing 1-slot `SoulGenerationScheduler` (party jobs keyed by `ConversationKey(ownerId, ownerId, PARTY)` — no scheduler changes), the Ollama provider, and the voice engine. New classes: group types, group router, group prompt assembler, group response validator, group conversation service, and a tick-driven scene playback machine.

**Tech Stack:** Java 21, Fabric 0.18.4 / MC 1.21.11, JUnit (existing suite: 417 tests), Jackson, Ollama local provider.

**Spec:** `docs/superpowers/specs/2026-08-25-frens-soul-group-chat-design.md` (plus parent `2026-08-23-frens-soul-communication-design.md`).

## Global Constraints

- DIRECT DM pipeline behavior byte-identical: `SoulChatRouter`, `SoulConversationService`, `SoulMessageDelivery`, DM store paths untouched (only additive changes to shared files).
- `SoulRuntime.activeGenerations()` static signature frozen (LoadGoverner reflects it); adding terms inside is allowed.
- souls package never references `Frens` (static-init breaks plain JUnit); live config reads are injected from call sites.
- Never `Thread.sleep` / blocking I/O on the server thread; playback is tick-driven; synthesis on the voice worker; store I/O on store writer threads.
- Scene caps: MAX_SCENE_BOTS=4, MAX_LINES_PER_BOT=2, MAX_SCENE_LINES=6, MAX_LINE_CHARS=300.
- Speaker-tagged plain-line output format (`Name: text`), not JSON.
- Only delivered lines commit to the party transcript. Party history never merges into DM history.
- Text lines for scenes bypass the Text Chat master (player-initiated soul replies, Bradley's 2026-08-25 ruling).
- Kill switch: `ManualConfig.isSoulPartyEnabled()` default true; souls master off ⇒ everything falls to legacy loop as today.
- Build after each task: `./gradlew build -x test` for compile, full `./gradlew build` (runs tests) before commit.
- Commit after each task with a `souls:` prefixed message.

## Plan-level refinements vs. spec (fold into spec in Task 9)

1. **No `SoulJobKey` interface / no `PartyKey` record.** The party job/store key is `ConversationKey(botId=ownerId, playerId=ownerId, channel=PARTY)`. Scheduler semantics (per-key single-flight, `cancelForPlayer` via `playerId()`, `invalidate`) all work unchanged. The oddity (botId slot carries the owner UUID) is confined to a documented factory `SoulGroupTypes.partyKey(ownerId)`.
2. **No `SoulPartyStore` class.** A second `SoulStore` instance rooted at `<world>/frens/party/v1` (new static factory `SoulStore.openAt(Path exactRoot)`) provides epochs, crash reconciliation, corrupt-tail quarantine, bounded history for free. Party transcript path is therefore `<world>/frens/party/v1/<ownerId>/conversations/<ownerId>/active.jsonl` (+ `archive/`), cursor in `<world>/frens/party/v1/<ownerId>/soul.json` under key `PARTY:<ownerId>`.
3. **No `GroupSnapshotBuilder`.** Per-bot grounding = one `SoulSnapshotBuilder.capture(server, bot, sender, LOCAL)` per roster bot at accept time.
4. Party records are uniformly speaker-tagged: HEARD content is stored as `"<OwnerName>: <message>"`, SPOKEN content as `"<BotName>: <line>"`, so history replays into prompts verbatim.
5. Per-line voice grouping: each scene line gets its own derived `groupId` (own client audio source positioned at its speaker); ordering is enforced server-side by the playback pacer. No client changes.

---

### Task 1: ChatAddressing multi-name resolution

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/ChatAddressing.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (resolveChatTargets mapping only)
- Test: `src/test/java/net/wcfcarolina13/ChatAddressingTest.java`

**Interfaces:**
- Produces: `Resolution(List<Integer> matchedNameIndices, boolean broadcast, String prompt)` with derived compat accessor `int matchedNameIndex()` (first index or -1). Multi-name matches only as a LEADING run: `Name (("and"|"&"|",") Name)*` then prompt. Single-name behavior (incl. mid-sentence full-message rule) byte-identical.
- `Frens.resolveChatTargets` maps all indices to targets; `ChatTarget(bots, prompt, broadcast)` unchanged in shape — multi-name simply yields `bots.size() >= 2` with `broadcast == false`.

- [ ] **Step 1: Write failing tests** in `ChatAddressingTest` (existing file, add cases):

```java
@Test
void multiNameLeadingRunResolvesAllNames() {
    var r = ChatAddressing.resolve("Jake and Sara, what do you think", List.of("Jake", "Sara")).orElseThrow();
    assertEquals(List.of(0, 1), r.matchedNameIndices());
    assertFalse(r.broadcast());
    assertEquals("what do you think", r.prompt());
}

@Test
void multiNameCommaOnlyRun() {
    var r = ChatAddressing.resolve("Jake, Sara, come look at this", List.of("Jake", "Sara")).orElseThrow();
    assertEquals(List.of(0, 1), r.matchedNameIndices());
    assertEquals("come look at this", r.prompt());
}

@Test
void connectorWithoutSecondNameStopsExtension() {
    var r = ChatAddressing.resolve("Jake and I went mining", List.of("Jake", "Sara")).orElseThrow();
    assertEquals(List.of(0), r.matchedNameIndices());
    assertEquals("and I went mining", r.prompt()); // tail after "Jake" only
}

@Test
void singleNameCompatAccessorUnchanged() {
    var r = ChatAddressing.resolve("Jake come here", List.of("Jake")).orElseThrow();
    assertEquals(0, r.matchedNameIndex());
    assertEquals(List.of(0), r.matchedNameIndices());
}

@Test
void midSentenceNameNeverStartsMultiRun() {
    var r = ChatAddressing.resolve("can you help me, Jake and Sara", List.of("Jake", "Sara")).orElseThrow();
    assertEquals(List.of(0), r.matchedNameIndices());          // first match only, as today
    assertEquals("can you help me, Jake and Sara", r.prompt()); // full-message rule, as today
}

@Test
void broadcastKeywordStillWinsOverNames() {
    var r = ChatAddressing.resolve("bots Jake and Sara hello", List.of("Jake", "Sara")).orElseThrow();
    assertTrue(r.broadcast());
    assertTrue(r.matchedNameIndices().isEmpty());
}

@Test
void duplicateNameInRunKeptOnce() {
    var r = ChatAddressing.resolve("Jake and Jake, hi", List.of("Jake")).orElseThrow();
    assertEquals(List.of(0), r.matchedNameIndices());
    assertEquals("hi", r.prompt());
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests 'net.wcfcarolina13.ChatAddressingTest'` → FAIL (no `matchedNameIndices`).

- [ ] **Step 3: Implement.** `Resolution` becomes:

```java
public record Resolution(List<Integer> matchedNameIndices, boolean broadcast, String prompt) {
    public Resolution {
        matchedNameIndices = matchedNameIndices == null ? List.of() : List.copyOf(matchedNameIndices);
    }
    /** Back-compat: the first explicitly named bot's index, or -1 for broadcast/none. */
    public int matchedNameIndex() {
        return matchedNameIndices.isEmpty() ? -1 : matchedNameIndices.get(0);
    }
}
```

In `resolve`, after the existing scan finds a leading single-name match (`matchedNameIndex >= 0 && leading`), extend the run before computing the prompt:

```java
List<Integer> indices = new ArrayList<>();
if (matchedNameIndex >= 0) {
    indices.add(matchedNameIndex);
}
if (matchedNameIndex >= 0 && leading) {
    int cursor = consumed;
    while (cursor < tokens.length) {
        int probe = cursor;
        // Consume at most one connector run ("and"/"&"; bare commas normalize to empty).
        String norm = normalizeToken(tokens[probe]);
        boolean sawConnector = false;
        while (probe < tokens.length && (norm.equals("and") || norm.isEmpty())) {
            sawConnector = sawConnector || norm.equals("and");
            probe++;
            norm = probe < tokens.length ? normalizeToken(tokens[probe]) : "";
        }
        // Comma-joined names arrive with the comma attached ("Sara,") which normalizes clean,
        // so a run may continue with NO connector tokens at all — the name test decides.
        int nameIdx = -1;
        for (int n = 0; n < botNames.size(); n++) {
            if (normalizeToken(botNames.get(n)).equals(norm)) { nameIdx = n; break; }
        }
        if (probe >= tokens.length || nameIdx < 0) {
            break; // revert: nothing consumed past `cursor`
        }
        if (!indices.contains(nameIdx)) {
            indices.add(nameIdx);
        }
        cursor = probe + 1;
        consumed = cursor;
    }
}
```

Return `new Resolution(broadcast ? List.of() : List.copyOf(indices), broadcast, prompt)` (prompt logic unchanged, using the possibly-advanced `consumed`). Note: a plain-token like "come" fails the name test and reverts — `"Jake come here"` keeps prompt `"come here"`. Careful: only extend when a **connector or comma-attached name** follows; the code above naturally handles `"Jake Sara, hi"` (no connector, "sara" is a name → consumed — acceptable and useful).

In `Frens.resolveChatTargets` replace the single-index mapping:

```java
List<ServerPlayerEntity> targets;
if (resolved.broadcast()) {
    targets = new ArrayList<>(bots);
} else {
    targets = new ArrayList<>();
    for (int idx : resolved.matchedNameIndices()) {
        targets.add(bots.get(idx));
    }
}
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests 'net.wcfcarolina13.ChatAddressingTest'` → PASS; then `./gradlew build -x test` compiles.
- [ ] **Step 5: Commit** — `souls: ChatAddressing multi-name leading-run resolution (Jake and Sara, ...)`

---

### Task 2: Party store root + group types

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulStore.java` (additive factory)
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypes.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypesTest.java` (+ a case in `SoulStoreTest`)

**Interfaces:**
- Produces: `SoulStore.openAt(Path exactRoot)` and `SoulStore.openAt(Path exactRoot, ExecutorService executor)` — same class, root used verbatim instead of `worldRoot/frens/souls/v1`.
- Produces `SoulGroupTypes` (namespace class, same style as `SoulTypes`):

```java
public final class SoulGroupTypes {
    private SoulGroupTypes() {}

    public static final int MAX_SCENE_BOTS = 4;
    public static final int MAX_LINES_PER_BOT = 2;
    public static final int MAX_SCENE_LINES = 6;
    public static final int MAX_LINE_CHARS = 300;

    /**
     * The party channel's conversation/store/scheduler key. Deliberately reuses ConversationKey
     * with BOTH id slots carrying the owner's UUID: the party store is a separate SoulStore
     * instance rooted at frens/party/v1, so the "<botId>" path segment is the owner's directory
     * there and can never collide with any DM path; the scheduler's per-key single-flight and
     * cancelForPlayer(playerId) semantics apply unchanged.
     */
    public static SoulTypes.ConversationKey partyKey(UUID ownerId) {
        return new SoulTypes.ConversationKey(ownerId, ownerId, SoulTypes.Channel.PARTY);
    }

    /** One roster member, grounding captured at accept time (fresh roster per turn). */
    public record SceneParticipant(UUID botId, String profileId, String displayName,
                                    SoulTypes.GroundingSnapshot grounding) {
        public SceneParticipant {
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(grounding, "grounding");
            profileId = profileId == null ? "" : profileId;
            displayName = displayName == null ? "" : displayName;
        }
    }

    /** An accepted group turn: N bots, one triggering owner, one message. */
    public record GroupSceneTurn(UUID ownerId, String ownerDisplayName,
                                  List<SceneParticipant> roster, String playerMessage,
                                  Instant acceptedAt, UUID routingId) {
        public GroupSceneTurn {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(routingId, "routingId");
            ownerDisplayName = ownerDisplayName == null ? "" : ownerDisplayName;
            playerMessage = playerMessage == null ? "" : playerMessage;
            roster = roster == null ? List.of() : List.copyOf(roster);
        }
        public SoulTypes.ConversationKey key() { return partyKey(ownerId); }
    }

    /** One validated scene line: which roster member speaks, and the clean dialogue text. */
    public record SceneLine(int participantIndex, String text) {
        public SceneLine {
            text = text == null ? "" : text;
        }
    }
}
```

- [ ] **Step 1: Failing tests.** `SoulGroupTypesTest`: `partyKey` puts ownerId in both slots + PARTY channel; `GroupSceneTurn` defensive copies; `SoulStore.cursorKey(partyKey(owner))` equals `"PARTY:" + owner`. In `SoulStoreTest` add: `openAt(tempDir)` writes `soul.json` at `<tempDir>/<ownerId>/soul.json` (not under `frens/souls/v1`) after a `beginHeardTurn` with a party key, and `archiveAndReset` bumps the PARTY cursor epoch.
- [ ] **Step 2: Verify failure** — `./gradlew test --tests '*SoulGroupTypesTest' --tests '*SoulStoreTest'`.
- [ ] **Step 3: Implement.** `SoulStore`: extract current root computation into a new private constructor `SoulStore(Path root, ExecutorService executor, boolean exactRoot)`; existing public constructors delegate with `exactRoot=false`; add:

```java
/** Opens a store whose on-disk root is exactly {@code exactRoot} (used by the party channel). */
public static SoulStore openAt(Path exactRoot) {
    return new SoulStore(exactRoot, newWriterExecutor(), true);
}

public static SoulStore openAt(Path exactRoot, ExecutorService executor) {
    return new SoulStore(exactRoot, executor, true);
}
```

- [ ] **Step 4: Run tests** → PASS. `./gradlew build -x test` clean.
- [ ] **Step 5: Commit** — `souls: party store root (SoulStore.openAt) + SoulGroupTypes (partyKey, GroupSceneTurn, caps)`

---

### Task 3: Group response validator

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulResponseValidator.java` (extract shared `sanitizeBase`)
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupResponseValidator.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupResponseValidatorTest.java`

**Interfaces:**
- `SoulResponseValidator` gains package-static `static String sanitizeBase(String raw)`: newline-normalize, strip think/analysis blocks, truncate at unclosed reasoning tag, strip § codes + control chars, collapse blank lines, strip. DM `validate` refactored to call it — behavior identical (its fence rejection and length cap stay in `validate`).
- Produces:

```java
public final class SoulGroupResponseValidator {
    public record SceneParse(boolean accepted, List<SoulGroupTypes.SceneLine> lines,
                              SoulTypes.FailureCode failureCode, String reason) {}
    /** rosterDisplayNames index-aligned with the turn's roster. */
    public SceneParse parse(String raw, List<String> rosterDisplayNames)
}
```

Parsing rules: sanitizeBase → split on `\n` → for each non-blank line, match `^\s*([^:]{1,40}):\s*(.+)$`; speaker token normalized via `ChatAddressing.normalizeToken`-equivalent (local copy: strip non-alphanumerics, lowercase) and compared to each normalized roster name; unknown speaker or non-matching line → dropped. Per-line text truncated at MAX_LINE_CHARS (cut at last sentence-ending punctuation within the cap when one exists past char 80, else hard cut). Enforce MAX_LINES_PER_BOT (drop extras for that bot) then MAX_SCENE_LINES (drop tail). Zero surviving lines → `MALFORMED`.

- [ ] **Step 1: Failing tests** — cases: happy 3-line two-speaker parse preserves order; unknown speaker dropped; narration line (no colon) dropped; per-bot cap drops the 3rd Jake line; scene cap drops line 7; 301-char line truncated; `<think>` block stripped before parsing; all-invalid → MALFORMED with reason; roster name matching is case/punct-insensitive (`jake:` matches "Jake"); DM validator regression: existing `SoulResponseValidatorTest` still green after the `sanitizeBase` refactor.
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement** (validator is pure; no Minecraft imports).
- [ ] **Step 4: Run** `./gradlew test --tests '*SoulGroupResponseValidatorTest' --tests '*SoulResponseValidatorTest'` → PASS.
- [ ] **Step 5: Commit** — `souls: group response validator (speaker-tagged line parse, caps) + shared sanitizeBase`

---

### Task 4: Group prompt assembler

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssembler.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssemblerTest.java`

**Interfaces:**
- Produces:

```java
public final class SoulGroupPromptAssembler {
    static final int MAX_HISTORY_TURNS = 12;
    static final int MAX_HISTORY_CHARS = 4_000;
    static final int MAX_IDENTITY_CHARS_PER_BOT = 600;
    static final int MAX_STATE_CHARS_PER_BOT = 400;
    static final int MAX_SITUATION_CHARS = 800;
    static final int MAX_OUTPUT_TOKENS = 320;

    public SoulTypes.ProviderRequest assemble(UUID correlationId, String model,
            SoulGroupTypes.GroupSceneTurn turn,
            List<SoulTypes.SoulProfile> profiles,            // index-aligned with turn.roster()
            List<SoulTypes.ConversationRecord> partyHistory,
            Duration timeout)
}
```

Message order (fixed, mirrors DM discipline — static system contract, no interpolation of untrusted text into it):
1. SYSTEM scene contract (static): "You are narrating one short exchange among a player's companions… Output ONLY dialogue lines, each on its own line, exactly in the form `Name: what they say`. Speakers must be chosen from the CAST list. At most two lines per companion, at most six lines total. No narration, no stage directions, no lines for the player."
2. SYSTEM `CAST` block: per roster member — display name, then identity/values bounded to MAX_IDENTITY_CHARS_PER_BOT from the profile (`String.join("; ", profile.identity())` etc., truncated).
3. SYSTEM `CURRENT STATE` block: per roster member from its `GroundingSnapshot.bot()` — health/maxHealth, hunger, held item, mood, activeTask, behaviorMode — bounded to MAX_STATE_CHARS_PER_BOT; plus one shared SITUATION paragraph from `roster.get(0).grounding().situation()` (hostiles count, nearby animals, standingOn, time phase/weather from bot snapshot) bounded to MAX_SITUATION_CHARS.
4. Bounded party history: each `ConversationRecord` replayed verbatim (records are already speaker-tagged) — HEARD → `Role.USER`, SPOKEN → `Role.ASSISTANT`, FAILURE records skipped; newest-first budget then reversed (same algorithm as `SoulPromptAssembler.boundedHistory`).
5. Final USER message: `turn.ownerDisplayName() + ": " + turn.playerMessage()`.

- [ ] **Step 1: Failing tests** — message order (system contract first, user message last exactly once); cast contains both display names; history budget honored (13 records → 12; char cap trims oldest); FAILURE records skipped; ProviderRequest carries model/timeout/MAX_OUTPUT_TOKENS/correlationId; profile identity truncation at 600 chars.
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement** (pure; reuse `SoulTypes.Message`/`ProviderRequest`; copy `boundedHistory`'s newest-first-then-reverse loop shape from `SoulPromptAssembler` with the group budgets).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `souls: group prompt assembler (scene contract, cast/state blocks, party history budgets)`

---

### Task 5: Voice line synthesis + scene playback machine

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceService.java` (add `synthesizeLine`)
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/GroupScenePlayback.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/GroupScenePlaybackTest.java`, `src/test/java/net/wcfcarolina13/GameAI/souls/voice/SoulVoiceServiceTest` additions (if the voice test dir has a service test; otherwise new `SoulVoiceLineSynthesisTest`)

**Interfaces:**
- `SoulVoiceService` gains:

```java
public record SynthesizedLine(int sampleRate, List<byte[]> chunks, long durationMs) {}

/**
 * Synthesizes one scene line with {@code profileId}'s voice on the shared voice worker
 * (counted in activeSyntheses). Completes with empty on ANY gate/synthesis failure —
 * scene playback then falls back to beat pacing. Never throws.
 */
public CompletableFuture<Optional<SynthesizedLine>> synthesizeLine(String profileId, String text) {
    Optional<SoulVoiceGate.Mode> mode = SoulVoiceGate.decide(
            settings.enabled() && masterVoiceEnabled.getAsBoolean(),
            settings.valid(), engineAlive(), SoulTypes.Reachability.LOCAL);
    Optional<String> sanitized = SoulVoiceSanitizer.sanitize(text, settings.maxChars());
    if (mode.isEmpty() || sanitized.isEmpty()) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
    CompletableFuture<Optional<SynthesizedLine>> out = new CompletableFuture<>();
    try {
        worker.execute(() -> {
            activeSyntheses.incrementAndGet();
            try {
                byte[] wav = engine.synthesize(sanitized.get(), profileId)
                        .get(settings.synthTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
                Optional<SoulVoicePcm.PcmAudio> pcm = SoulVoicePcm.parseWav(wav);
                if (pcm.isEmpty()) { noteFailure(); out.complete(Optional.empty()); return; }
                long durationMs = pcm.get().data().length * 1000L / (pcm.get().sampleRate() * 2L);
                out.complete(Optional.of(new SynthesizedLine(pcm.get().sampleRate(),
                        SoulVoicePcm.chunk(pcm.get().data(), CHUNK_BYTES), durationMs)));
                backoff.onSuccess();
            } catch (Exception ex) {
                noteFailure();
                out.complete(Optional.empty());
            }
        });
    } catch (RejectedExecutionException rejected) {
        out.complete(Optional.empty());
    }
    // Queue-full rejection handler discards without running; complete defensively.
    worker-rejection note: the existing rejection handler logs and drops — guard by completing
    out with empty via a timeout-free check: if the task never ran, `out` would hang, so submit
    through a helper that completes empty when execute() itself rejected (as above) and rely on
    the LinkedBlockingQueue cap only dropping when 4 renders are already queued — a scene
    submits at most one line at a time, so this cannot occur mid-scene in practice.
}
```

(Implementation note: the existing `ThreadPoolExecutor` rejection handler silently drops the runnable; since it doesn't throw, `worker.execute` won't raise — instead pass the future into a wrapper runnable and ALSO install a scene-side guard: `GroupScenePlayback` treats a synthesis future that hasn't completed within `synthTimeoutMs + 2000` as failed (beat fallback). That guard is a tick-loop check, not a blocking wait.)

- Produces `GroupScenePlayback` (souls package; Minecraft server imports OK, no Frens/ChatUtils):

```java
public final class GroupScenePlayback {
    static final double EARSHOT_BLOCKS = 32.0;
    static final long LINE_GAP_MS = 350;

    public interface LineCommitter {   // implemented by SoulGroupConversationService
        void commitLine(SoulTypes.TurnToken token, int participantIndex, String taggedLine);
        void sceneFinished(SoulTypes.TurnToken token, int deliveredLines, int totalLines);
    }

    public record PlayableScene(SoulGroupTypes.GroupSceneTurn turn, SoulTypes.TurnToken token,
                                 List<SoulGroupTypes.SceneLine> lines) {}

    public GroupScenePlayback(MinecraftServer server, SoulVoiceService voice,
                              SoulVoiceService.VoiceDelivery voiceDelivery, LineCommitter committer)

    /** Called from the group service after validation; thread-safe. */
    public void enqueue(PlayableScene scene)
    public boolean hasActiveScene(UUID ownerId)
    public int activeSceneCount()
    public void cancelOwner(UUID ownerId)
    public void cancelAll()
    /** Ticked from END_SERVER_TICK via SoulRuntime.tickScenes. */
    public void tick()

    // Pure, unit-tested helpers:
    static long beatMsFor(int textLength)          // clamp(1500 + 4*len, 1500, 2500)
    static long lineDurationMs(Optional<SoulVoiceService.SynthesizedLine> line, int textLength)
        // present -> durationMs + LINE_GAP_MS ; empty -> beatMsFor(textLength)
    static UUID lineGroupId(UUID routingId, int lineIndex)
        // new UUID(msb ^ 0x517E... * (lineIndex+1), lsb) — same derivation style as
        // SoulVoiceService.segmentCorrelationId but a distinct multiplier so ids never collide
    static boolean lineDeliverable(boolean botOnline, boolean botAlive, boolean ownerOnline,
                                   boolean sceneCancelled)  // pure staleness combinator
}
```

Per-scene state machine (all mutation on the server thread inside `tick()`; scenes map is a `ConcurrentHashMap<UUID, SceneState>` keyed by ownerId):
- Phase SYNTH: on entering a line, call `voice.synthesizeLine(profile, lineText)` (async) and record `synthStartedAtMs`.
- `tick()`: for each active scene, if current line's synthesis future is done (or empty, or older than `synthTimeoutMs + 2000` → treat as empty): run staleness checks (`lineDeliverable`): scene cancelled / owner offline → abort scene (call `committer.sceneFinished`); speaker bot offline or dead → skip line, advance. Otherwise deliver:
  - Text fan-out: `Text.literal(displayName + ": " + text)` to every `ServerPlayerEntity` in `server.getPlayerManager().getPlayerList()` in the speaker's world within `EARSHOT_BLOCKS` (squared compare). Fake players receive harmlessly (no-op connection).
  - Voice fan-out (when synthesis produced audio): for each of those players, `voiceDelivery.send(playerId, lineGroupId(routingId, i), botId, POSITIONAL, sampleRate, chunks, lineGroupId(routingId, i), 0)` — correlationId == groupId per line, segmentIndex 0.
  - `committer.commitLine(token, participantIndex, displayName + ": " + text)`.
  - Set `nextLineAtMs = now + lineDurationMs(...)`; immediately start the NEXT line's synthesis (overlaps playback).
- A line only *delivers* when `now >= nextLineAtMs` of the previous line (pacing), even if synthesis finished early.
- On last line: `committer.sceneFinished(token, delivered, total)` and remove the scene.
- Uses `System.currentTimeMillis()` via an injectable `LongSupplier clock` (constructor overload) so pacing tests are deterministic.

- [ ] **Step 1: Failing tests** — pure helpers: `beatMsFor(0)=1500`, `beatMsFor(300)=2500`, `lineDurationMs` uses PCM duration + gap when present and beat when empty; `lineGroupId` distinct across indices and from `segmentCorrelationId`; `lineDeliverable` truth table. State machine with fake clock + fake committer + null-server-free seam: extract the per-tick decision into a pure/package method `SceneStep decideStep(SceneState s, long nowMs)` returning WAIT/DELIVER/SKIP/ABORT and test THAT (the Minecraft fan-out itself stays untested, like `SoulMessageDelivery`'s server path).
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew test --tests '*GroupScenePlayback*' --tests '*SoulVoice*'` → PASS; full compile.
- [ ] **Step 5: Commit** — `souls: scene playback machine (tick-paced lines, per-speaker voice fan-out) + SoulVoiceService.synthesizeLine`

---

### Task 6: Group conversation service

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationService.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationServiceTest.java`

**Interfaces:**
- Consumes: party `SoulStore`, `SoulGroupPromptAssembler`, `SoulGenerationScheduler`, `SoulModelProvider`, `SoulGroupResponseValidator`, `SoulSettings`, a `ScenePlayer` sink + `StatusSink`:

```java
public final class SoulGroupConversationService implements GroupScenePlayback.LineCommitter {
    public interface ScenePlayer {          // production: GroupScenePlayback
        void enqueue(GroupScenePlayback.PlayableScene scene);
        boolean hasActiveScene(UUID ownerId);
    }
    public interface StatusSink {           // production: SoulMessageDelivery.deliverStatus-alike
        void deliverStatus(UUID playerId, String text);
    }
    public enum Submission { SCENE_STARTED, FAILED }

    public SoulGroupConversationService(SoulStore partyStore, SoulGroupPromptAssembler prompts,
            SoulGenerationScheduler scheduler, SoulModelProvider provider,
            SoulGroupResponseValidator validator, SoulSettings settings,
            ScenePlayer player, StatusSink status)

    public CompletableFuture<Submission> submit(SoulGroupTypes.GroupSceneTurn turn)
    public void invalidate(SoulTypes.ConversationKey key, long newEpoch)  // passthrough
}
```

`submit` flow (mirrors `SoulConversationService.submit`, correlationId = `turn.routingId()`):
1. `player.hasActiveScene(turn.ownerId())` → status "Your companions are still talking. Give them a moment." + FAILED.
2. `partyStore.beginHeardTurn(turn.key(), routingId, turn.ownerDisplayName() + ": " + turn.playerMessage(), turn.acceptedAt())` → token (no-token → INTERNAL status, FAILED).
3. `partyStore.recentBefore(token, MAX_HISTORY_TURNS, MAX_HISTORY_CHARS)` → history; resolve profiles via `SoulProfileRegistry.require` per roster member (unknown profile → INTERNAL).
4. Assemble request; `scheduler.submit(turn.key(), token.epoch(), supplier)` — one call, one slot, `OVERLOADED` behaves as DM.
5. On provider success → `validator.parse(result.text(), rosterDisplayNames)`; rejected → `failTurn` (same CANCELLED/STALE_EPOCH skip-append rule as DM, same `statusFor` text with name "Your companions" — add a small local `statusFor(code)` copy specialized for the plural).
6. Accepted → `player.enqueue(new PlayableScene(turn, token, lines))`, complete SCENE_STARTED. Log `[souls] scene` line: routingId, owner, rosterSize, lineCount, queueDepth, providerMs, validationMs.
7. `LineCommitter.commitLine` → `partyStore.appendSpoken(token, taggedLine, sceneResult)` (store the scene's ProviderResult metadata; a `StaleEpochException` inside the append future is swallowed with a `[souls] scene` WARN — the line was already shown).
8. `sceneFinished` → log `[souls] scene outcome=finished delivered=X/Y`.

- [ ] **Step 1: Failing tests** (temp-dir party store via `SoulStore.openAt(tmp, directExecutor)`, fake provider/validator/player/status — mirror `SoulConversationServiceTest` style): happy path → HEARD persisted tagged, SCENE_STARTED, player got 3 lines; active-scene guard → status + FAILED, no HEARD; provider OVERLOADED → plural status, FAILURE record appended; MALFORMED parse → FAILURE + status; commitLine appends SPOKEN with tagged content and advancing sequence; STALE append swallowed.
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `souls: group conversation service (one orchestration call, per-line commit)`

---

### Task 7: Group router

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupRouter.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupRouterTest.java`

**Interfaces:**

```java
public final class SoulGroupRouter {
    public enum RouteOutcome { NOT_SOUL, CONSUMED, DOWNGRADE_TO_DM }

    /** Pure roster projection, exhaustively unit-tested. Candidate order preserved (nearest-first
     *  ordering is the caller's job); result capped at MAX_SCENE_BOTS. */
    public record Candidate(UUID botId, boolean profileActive, boolean authorized,
                             SoulTypes.Reachability reachability) {}
    public static List<UUID> eligibleRoster(List<Candidate> candidates) {
        return candidates.stream()
                .filter(c -> c.profileActive() && c.authorized()
                        && c.reachability() == SoulTypes.Reachability.LOCAL)
                .limit(SoulGroupTypes.MAX_SCENE_BOTS)
                .map(Candidate::botId).toList();
    }

    /** Pure coarse gate mirroring SoulChatRouter.decide. */
    public static RouteOutcome decide(boolean masterEnabled, boolean indexReady,
                                       boolean partyEnabled, int eligibleCount) {
        if (!masterEnabled || !partyEnabled) return RouteOutcome.NOT_SOUL;
        if (!indexReady) return RouteOutcome.CONSUMED;         // loading notice
        if (eligibleCount == 0) return RouteOutcome.CONSUMED;   // none-eligible notice
        if (eligibleCount == 1) return RouteOutcome.DOWNGRADE_TO_DM;
        return RouteOutcome.CONSUMED;                           // scene
    }

    /**
     * Live route for a broadcast or multi-name address. {@code candidateBots} = the resolved
     * target bots (all registered bots for broadcast), {@code partyEnabled} injected from the
     * Frens call site (souls package never reads Frens.CONFIG). Returns DOWNGRADE_TO_DM with
     * {@code downgraded[0]} set when exactly one bot is eligible — the caller then invokes
     * SoulChatRouter.tryRoute on it.
     */
    public static RouteOutcome tryRoute(List<ServerPlayerEntity> candidateBots,
            ServerPlayerEntity sender, String prompt, boolean partyEnabled,
            ServerPlayerEntity[] downgraded)
}
```

`tryRoute` order: runtime present else NOT_SOUL; sort candidates by distance to sender ascending; build `Candidate` list (profileActive = `runtime.hasActiveProfile`, authorized = `isPrivateSoulAuthorized(sender, bot)`, reachability = `classifySoulReachability`); coarse `decide`; on CONSUMED-loading → LOADING notice (reuse SoulChatRouter's wording, plural: "Your companions' conversation memory is still loading."); `pipelineAvailable` false → notice; eligible==0 → "None of your companions are close enough to chat."; ==1 → set `downgraded[0]`, return DOWNGRADE_TO_DM (no notice, no submission); ≥2 → capture `SoulSnapshotBuilder.capture(server, bot, sender, LOCAL)` per eligible bot (a capture that throws drops that bot; if <2 survive, fall back to downgrade/none paths), build `SceneParticipant`s (profileId from `runtime.cachedState(botId).profileId()`, displayName from entity), mint routingId, log `[souls] scene-routing` (routingId, owner, candidateCount, eligibleCount, outcome, per-stage ms), `runtime.submitGroupTurn(turn)`, CONSUMED.

- [ ] **Step 1: Failing tests** — `eligibleRoster`: filters non-profile/unauthorized/REMOTE/UNREACHABLE, caps at 4, preserves order; `decide` truth table incl. partyEnabled=false → NOT_SOUL even with eligible bots, masterOff → NOT_SOUL, loading → CONSUMED, 1 → DOWNGRADE.
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** — `souls: group router (roster projection, coarse gate, live scene accept)`

---

### Task 8: Wiring — runtime, config, chat seam, commands

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java`
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java`
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulRuntimeTest.java` additions

**Interfaces:**
- `ManualConfig`: field `private boolean soulPartyEnabled = true;` + `public boolean isSoulPartyEnabled()` / `public void setSoulPartyEnabled(boolean v)` in the "Soul communication accessors" block.
- `SoulRuntime` additions:
  - Field `private final SoulStore partyStore;` — production: `SoulStore.openAt(worldRoot.resolve("frens").resolve("party").resolve("v1"))` in `start`; test-seam constructor: `SoulStore.openAt(<same root as store>.. not needed)` → give the test-seam ctor a party store parameter defaulting via overload that reuses `store` root sibling; simplest: test-seam keeps `partyStore = store` (documented: tests exercising party paths construct production-style).
  - Field `private volatile GroupScenePlayback scenePlayback;` created in `start` after delivery wiring; `null` in test seam (facades null-check).
  - `Pipeline` record gains `SoulGroupConversationService groupService`; `buildPipeline` constructs it with the SAME scheduler/provider/settings, the party store, a `ScenePlayer` that forwards to `scenePlayback` (null-safe), and a `StatusSink` backed by the existing `delivery::deliverStatus`.
  - `public CompletableFuture<SoulGroupConversationService.Submission> submitGroupTurn(SoulGroupTypes.GroupSceneTurn turn)` — same stopped/enabled fail-closed shape as `submitTurn`.
  - `public boolean isPartyRoutable()` → `pipelineAvailable()`.
  - `public CompletableFuture<Long> resetParty(UUID ownerId)` — `partyStore.archiveAndReset(partyKey(ownerId))` then under `lifecycleLock` invalidate via current pipeline's groupService AND `scenePlayback.cancelOwner(ownerId)`.
  - `cancelPlayer(playerId)`: additionally `scenePlayback.cancelOwner(playerId)` (null-safe).
  - `activeGenerations()`: `+ (runtime.scenePlayback == null ? 0 : runtime.scenePlayback.activeSceneCount())`.
  - `public static void tickScenes(MinecraftServer server)` — static facade: `current()` → playback tick; no-op when absent. Cheap when souls off.
  - `shutdown()`: also `scenePlayback.cancelAll()` (before store close) and `partyStore.close()`.
- `Frens.java`:
  - Register `ServerTickEvents.END_SERVER_TICK.register(server -> net.wcfcarolina13.GameAI.souls.SoulRuntime.tickScenes(server));` next to the other END_SERVER_TICK registrations (~line 1167).
  - Chat seam (directly above the existing single-bot soul gate at ~1291): 

```java
// Group scenes: a broadcast keyword or a multi-name leading address routes to the soul
// PARTY channel when enabled; single-bot DMs continue through SoulChatRouter below.
if ((target.broadcast() || routedBots.size() >= 2) && !target.prompt().isEmpty()) {
    try {
        ServerPlayerEntity[] downgraded = new ServerPlayerEntity[1];
        boolean partyEnabled = CONFIG == null || CONFIG.isSoulPartyEnabled();
        var groupOutcome = net.wcfcarolina13.GameAI.souls.SoulGroupRouter.tryRoute(
                routedBots, sender, target.prompt(), partyEnabled, downgraded);
        if (groupOutcome == net.wcfcarolina13.GameAI.souls.SoulGroupRouter.RouteOutcome.CONSUMED) {
            return;
        }
        if (groupOutcome == net.wcfcarolina13.GameAI.souls.SoulGroupRouter.RouteOutcome.DOWNGRADE_TO_DM
                && downgraded[0] != null
                && net.wcfcarolina13.GameAI.souls.SoulChatRouter.tryRoute(downgraded[0], sender, target.prompt())
                        == net.wcfcarolina13.GameAI.souls.SoulChatRouter.RouteOutcome.CONSUMED) {
            return;
        }
    } catch (Throwable t) {
        LOGGER.warn("SoulGroupRouter threw; falling back to legacy routing: {}", t.toString());
    }
}
```

  (NOT_SOUL falls through to the legacy loop exactly as today — souls off, party toggle off, or no runtime.)
- `BotSoulCommands`: under `soul` add `reset party`:

```java
.then(CommandManager.literal("reset")
        .then(CommandManager.literal("party").executes(BotSoulCommands::executePartyReset))
        .then(/* existing bot argument branch unchanged */))
```

`executePartyReset`: player-only; runtime present; `runtime.resetParty(player.getUuid())` → feedback "Party conversation archived (epoch N)." / error on failure. Owner-scoped: resets only the actor's own party thread — no operator override needed in v1.

- [ ] **Step 1: Failing tests** — `SoulRuntimeTest` additions where feasible without a server: `submitGroupTurn` fails closed when stopped / not enabled (test-seam runtime); `activeGenerations` null-playback safe. (`tickScenes`/start-path wiring is exercised in-game, like the rest of `start`.)
- [ ] **Step 2: Verify failure.**
- [ ] **Step 3: Implement all four files.**
- [ ] **Step 4: Run FULL build** — `./gradlew build` (entire suite; DM regressions checked here) → green.
- [ ] **Step 5: Commit** — `souls: PARTY wiring — runtime party store/playback, group route seam, souls.party toggle, /bot soul reset party`

---

### Task 9: Docs, version bump, deploy

**Files:**
- Modify: `docs/superpowers/specs/2026-08-25-frens-soul-group-chat-design.md` (record refinements 1–5 from the header of this plan)
- Modify: `changelog.md` (newest-first entry: feature summary, file list, reasoning, commit hashes)
- Modify: `RALPH_TASK.md` (handoff header: group chat shipped; field-test checklist)
- Modify: `gradle.properties` (`mod_version` 1.1.175 → 1.1.176)

- [ ] **Step 1:** Amend spec §4.2/§6 with the ConversationKey-as-party-key and SoulStore.openAt refinements; note per-line voice groupId decision.
- [ ] **Step 2:** Changelog entry + RALPH_TASK.md handoff update (include manual field-test checklist: 2-bot scene via second `/bot soul enable <bot>` Jake-profile bind, mixed soul/non-soul broadcast, bystander earshot, voice-off beat pacing, walk-away mid-scene, `/bot soul reset party`, LoadGoverner floor during scene, party toggle off ⇒ legacy loop).
- [ ] **Step 3:** Bump `mod_version` to `1.1.176-release+1.21.11`; `./gradlew build` → verify `build/libs/frens-1.1.176-release+1.21.11.jar` exists and suite is green.
- [ ] **Step 4:** Commit — `souls: group chat docs + changelog; bump 1.1.176`.
- [ ] **Step 5: Deploy** — run the pre-deploy check (`pgrep -f "net.minecraft.client.main.Main"`); if Minecraft is running, STOP and report the artifact path instead. Otherwise the standard three-instance deploy loop from CLAUDE.md (rm old `frens-*.jar`, cp new). Verify with `unzip -l` that the deployed jar contains `GroupScenePlayback.class`.

## Self-review notes

- Spec coverage: §3 addressing/routing → Tasks 1, 7, 8; §4 types/scheduler/orchestration → Tasks 2, 4, 6 (scheduler deliberately unchanged — refinement 1); §5 delivery/playback → Task 5; §6 storage → Task 2 + 6; §7 config/failure/telemetry → Tasks 6–8; §8 invariants — DM files touched only additively (`SoulStore.openAt`, `SoulResponseValidator.sanitizeBase`, `SoulVoiceService.synthesizeLine`, `Resolution` record shape); §9 testing → per-task; §10 out-of-scope respected.
- `Resolution` record shape change is the one DM-adjacent API break — all call sites are `Frens.resolveChatTargets` and tests, both updated in Task 1.
- Type consistency: `SoulGroupTypes.SceneLine(participantIndex, text)` consumed by validator (produces), playback (consumes), committer (participantIndex → roster displayName for the tagged commit).
