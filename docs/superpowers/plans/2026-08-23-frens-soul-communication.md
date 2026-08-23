# Frens Soul Communication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an opt-in Jake direct-message pilot that provides persistent, grounded, owner-authorized in-character conversation through a local Ollama-compatible model without granting generated text any gameplay authority.

**Architecture:** Add a Frens-owned `GameAI/souls` domain beside the legacy LLM stack. Server-thread routing captures immutable context, while a bounded asynchronous pipeline owns JSONL persistence, role-based prompt assembly, local provider calls, response validation, delivery rechecks, and observability. Existing scripted behavior remains the fallback whenever the master feature flag is off or a bot has no active soul profile.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric 0.18.4, Jackson 2.17.2, Java `HttpClient`, JUnit 5.11.4, Mockito 5.2.0, Gradle/Fabric Loom.

**Spec:** [`docs/superpowers/specs/2026-08-23-frens-soul-communication-design.md`](../specs/2026-08-23-frens-soul-communication-design.md)

## Global Constraints

- Runtime work starts only after the annotated tag `checkpoint/pre-souls-2026-08-23` is pushed at commit `d6f5b41`.
- Runtime work occurs on `feature/soul-communication`, created through `superpowers:using-git-worktrees`.
- Every numbered Task is an implementation phase: it touches at most five files, including `changelog.md`, and ends with verification plus a focused commit.
- After every numbered Task, present its verification evidence and wait for explicit approval before starting the next Task. The larger `Phase` headings below are milestones, not permission to execute multiple Tasks continuously.
- Re-read every existing file immediately before editing it; files over 500 lines are read in targeted chunks.
- `soulsEnabled` defaults to `false`; feature-off behavior must be indistinguishable from the current legacy path.
- The pilot accepts only provider ID `ollama`. No local failure may fall back to a hosted provider.
- Never route a soul turn through `LLMOrchestrator`, `MemoryStore`, `LLMServiceHandler`, or `FunctionCallerV2`.
- Never create a privileged `ServerCommandSource` for generated conversation.
- Minecraft entity/world reads and mutations remain on the server thread. Provider work and transcript I/O remain off-thread.
- Soul identity uses bot UUID plus authored profile ID. A bot display name is not an identity or authorization boundary.
- Only an exact recorded owner or operator may activate, reset, or converse with a private soul. Unowned bots are not eligible.
- Persistence is world-save-local under `<world>/frens/souls/v1`; credentials never enter that tree.
- Raw conversation and event records are append-only. Reset archives an epoch rather than deleting it.
- Bot replies are persisted as `SPOKEN` only after a successful delivery recheck and private message send.
- Generated text is plain dialogue only. It cannot be parsed or dispatched as an action.
- Run the focused test after each red/green step, then `./gradlew test` and `./gradlew build -x test` at every numbered-Task gate.
- Update `changelog.md` for each code-bearing task before committing.
- Do not deploy a JAR while Minecraft is running. Deployment is outside this plan until manual acceptance passes and the user explicitly confirms the game is closed.

## Locked File Structure

### New production files

- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulTypes.java` — immutable enums and records crossing soul-domain boundaries.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulSettings.java` — validates and snapshots soul-only configuration.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulStore.java` — single-writer versioned profile, conversation, archive, and event persistence.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulModelProvider.java` — provider-neutral asynchronous request/cancellation contract.
- `src/main/java/net/wcfcarolina13/GameAI/souls/OllamaSoulProvider.java` — local `/api/chat` and `/api/tags` adapter using Java `HttpClient`.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGenerationScheduler.java` — bounded concurrency, per-thread serialization, cancellation, and stale-epoch suppression.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulProfileRegistry.java` — validates and loads authored soul profile resources.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssembler.java` — deterministic message ordering and bounded history/event projection.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulResponseValidator.java` — strips hidden/meta output and rejects invalid dialogue.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulSnapshotBuilder.java` — server-thread projection from authoritative Frens/Minecraft state into immutable records.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulConversationService.java` — accepted-turn lifecycle from heard append through delivered spoken append.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulMessageDelivery.java` — server-thread private text delivery with a final guard.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java` — per-server composition root and lifecycle owner.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulChatRouter.java` — exclusive single-target soul routing after deterministic chat handlers.
- `src/main/java/net/wcfcarolina13/GameAI/souls/SoulEventObserver.java` — factual task, damage, combat, death, sleep, dimension, and quest transitions.
- `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java` — `/bot soul` activation, reset, model, and status commands.
- `src/main/resources/data/frens/souls/jake.json` — authored Jake identity and speaking examples.

### Existing files modified

- `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java:29-368` — default-off soul settings only; no new credentials.
- `src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java:29-258` — one soul authorization and reachability boundary.
- `src/main/java/net/wcfcarolina13/GameAI/services/BotQuestService.java:52-88` — immutable active-quest snapshot accessor.
- `src/main/java/net/wcfcarolina13/GameAI/services/TaskService.java:207-410` — factual task transition notifications.
- `src/main/java/net/wcfcarolina13/Frens.java:718-920,922-1140,1191-1275` — soul lifecycle, event hooks, and exclusive direct-chat routing.
- `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java:300-570` — attach `BotSoulCommands.build()` beneath `/bot`.
- `changelog.md` — one concise architectural entry per completed task.

### New tests and runbook

- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulFoundationTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulStoreTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulProviderSchedulerTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssemblerTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulResponseValidatorTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroundingTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulConversationServiceTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulMessageDeliveryTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulRuntimeTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulChatRouterTest.java`
- `src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java`
- `src/test/java/net/wcfcarolina13/GameAI/souls/SoulEventObserverTest.java`
- `docs/testing/SOUL_COMMUNICATION_PILOT.md`

---

## Phase 0 — GitHub checkpoint and isolated worktree

### Task 0: Publish the reversible baseline

**Files:** None.

**Interfaces:**
- Consumes: approved spec commit `6924819` and this plan commit on `main`.
- Produces: pushed baseline tag plus isolated `feature/soul-communication` worktree.

- [ ] **Step 1: Verify the baseline target and local state**

Run:

```bash
git show -s --oneline d6f5b41
git status --short --branch
```

Expected: `d6f5b41 Release Frens 1.1.137`; only known user-owned untracked `AGENTS.md` and `logs/*.gz` files appear.

- [ ] **Step 2: Push the approved documentation commits**

Run:

```bash
git push origin main
```

Expected: `origin/main` advances through the design and plan commits; no runtime file changes are included.

- [ ] **Step 3: Create and push the annotated runtime checkpoint**

Run:

```bash
git tag -a checkpoint/pre-souls-2026-08-23 d6f5b41 -m "Known-good Frens runtime before soul communication"
git push origin checkpoint/pre-souls-2026-08-23
git ls-remote --tags origin 'checkpoint/pre-souls-2026-08-23*'
```

Expected: the remote tag resolves to an annotated tag whose peeled commit is `d6f5b41`.

- [ ] **Step 4: Create the isolated feature worktree**

Invoke `superpowers:using-git-worktrees`, then create `.worktrees/soul-communication` on `feature/soul-communication` from the updated `main`.

Expected verification:

```bash
git -C .worktrees/soul-communication status --short --branch
git -C .worktrees/soul-communication merge-base --is-ancestor d6f5b41 HEAD
```

Expected: clean feature worktree; baseline commit is an ancestor.

**Phase gate:** Show the pushed tag and clean worktree evidence. Wait for approval before Phase 1.

---

## Phase 1 — Domain model and durable storage

### Task 1: Add immutable soul types and default-off settings

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulTypes.java`
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulSettings.java`
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java:29-368`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulFoundationTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: existing `ManualConfig.getOllamaBaseUrl()`.
- Produces: `SoulTypes.*` records used by every later task and `SoulSettings.from(ManualConfig)`.

- [ ] **Step 1: Write the failing foundation tests**

Create tests that lock default-off behavior, local-only provider validation, bounded settings, defensive list/map copies, and stable conversation identity:

```java
@Test
void rejectsHostedProviderAndDefaultsToDisabled() {
    ManualConfig config = mock(ManualConfig.class);
    when(config.isSoulsEnabled()).thenReturn(false);
    when(config.getSoulProvider()).thenReturn("openai");
    when(config.getSoulModel()).thenReturn("remote-model");
    when(config.getOllamaBaseUrl()).thenReturn("http://127.0.0.1:11434");
    when(config.getSoulRequestTimeoutSeconds()).thenReturn(60);
    when(config.getSoulQueueCapacity()).thenReturn(8);

    SoulSettings settings = SoulSettings.from(config);

    assertFalse(settings.enabled());
    assertFalse(settings.valid());
    assertEquals("Only the local ollama provider is supported by the pilot.", settings.validationError());
}

@Test
void conversationKeyUsesUuidNotDisplayName() {
    UUID bot = UUID.randomUUID();
    UUID player = UUID.randomUUID();
    SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
            bot, player, SoulTypes.Channel.DIRECT);
    assertEquals(bot, key.botId());
    assertEquals(player, key.playerId());
}
```

- [ ] **Step 2: Run the test and verify red**

Run:

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulFoundationTest
```

Expected: compilation fails because `SoulTypes`, `SoulSettings`, and the soul configuration accessors do not exist.

- [ ] **Step 3: Add the soul configuration fields and accessors**

Add only non-secret fields to `ManualConfig`:

```java
private boolean soulsEnabled = false;
private String soulProvider = "ollama";
private String soulModel = "";
private int soulRequestTimeoutSeconds = 60;
private int soulQueueCapacity = 8;
```

Expose getters and setters. Clamp timeout to `10..180` seconds and queue capacity to `1..32`; normalize blank provider to `ollama`; trim model names. Do not reuse `defaultLlmWorldEnabled` and do not add API-key fields.

- [ ] **Step 4: Implement the immutable boundary model**

Create `SoulTypes` as a final namespace class with these exact nested types:

```java
public enum Channel { DIRECT, PARTY, LOCAL, BANTER, SYSTEM }
public enum Reachability { LOCAL, REMOTE, UNREACHABLE }
public enum Role { SYSTEM, USER, ASSISTANT }
public enum TurnKind { HEARD, SPOKEN, FAILURE }
public enum FailureCode {
    DISABLED, UNAUTHORIZED, UNREACHABLE, OVERLOADED, TIMEOUT,
    UNAVAILABLE, MALFORMED, CANCELLED, STALE_EPOCH, INTERNAL
}
public enum EventType {
    TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_PAUSED, TASK_CANCELLED,
    BOT_DAMAGE, OWNER_DAMAGE, COMBAT_STARTED, COMBAT_ENDED,
    DEATH, RESPAWN, SLEEP, WAKE, DIMENSION_CHANGED,
    QUEST_STAGE_CHANGED, DIRECT_CONVERSATION
}
public enum Witness { SELF, LOCAL }
public enum Salience { LOW, NORMAL, HIGH }

public record ConversationKey(UUID botId, UUID playerId, Channel channel) {}
public record ConversationCursor(long epoch, long nextSequence) {}
public record TurnToken(ConversationKey key, UUID correlationId, long epoch, long sequence) {}
public record Message(Role role, String content) {}
public record ProviderRequest(UUID correlationId, String model, List<Message> messages,
                              Duration timeout, int maxOutputTokens) {}
public record ProviderResult(boolean success, String text, FailureCode failureCode,
                             String provider, String model, long elapsedMillis,
                             Long firstOutputMillis, Integer inputTokens, Integer outputTokens) {}
public record ConversationRecord(UUID correlationId, long epoch, long sequence,
                                 TurnKind kind, String content, Instant occurredAt,
                                 String provider, String model, Long elapsedMillis,
                                 FailureCode failureCode) {}
public record SoulEvent(UUID eventId, EventType type, UUID actorId,
                        List<UUID> participants, String dimension, String biome,
                        Map<String, String> facts, Witness witness,
                        long worldTick, Instant occurredAt, Salience salience) {}
public record SoulState(int schemaVersion, UUID botId, String profileId,
                        boolean active, Map<String, ConversationCursor> conversations) {}
public record SoulProfile(String id, String displayName, List<String> identity,
                          List<String> values, List<String> boundaries,
                          List<Message> examples) {}
public record QuestSnapshot(String id, String intent, int actionIndex,
                            int actionCount, long expiresTick) {}
public record BotSnapshot(UUID botId, String name, String dimension, String biome,
                          int coarseX, int coarseY, int coarseZ, boolean skyVisible,
                          String timePhase, String weather, float health, float maxHealth,
                          int hunger, int armor, String heldItem, int occupiedSlots,
                          int inventorySlots, List<String> resourceSummary, String mood,
                          String behaviorMode, String activeTask, String taskState,
                          String homeName, String ownerName, boolean recruited,
                          int companionQuestStage, boolean permanentCompanion,
                          Optional<QuestSnapshot> activeQuest) {}
public record PlayerSnapshot(UUID playerId, String name, int distanceBlocks,
                             String direction, float health, float maxHealth,
                             int hunger, String heldItem, boolean sleeping) {}
public record GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                Optional<PlayerSnapshot> player, Instant capturedAt) {}
public record AcceptedTurn(ConversationKey key, String botDisplayName,
                           String playerDisplayName, String playerMessage,
                           String profileId, GroundingSnapshot grounding,
                           Instant acceptedAt) {}
```

Canonical constructors require non-null primary IDs/enums, replace nullable strings with `""`, and wrap all collections with `List.copyOf`/`Map.copyOf`. Optional provider metadata (`failureCode`, timing, and token counts) may be null when unavailable. These records contain strings, primitives, UUIDs, instants, and immutable collections only—never Minecraft objects.

- [ ] **Step 5: Implement `SoulSettings.from`**

Use the exact record boundary:

```java
public record SoulSettings(boolean enabled, boolean valid, String validationError,
                           String provider, String model, URI ollamaBaseUri,
                           Duration timeout, int queueCapacity) {
    public static SoulSettings from(ManualConfig config) {
        if (config == null) {
            return new SoulSettings(false, false, "Frens configuration is unavailable.",
                    "ollama", "", URI.create("http://127.0.0.1:11434"),
                    Duration.ofSeconds(60), 8);
        }
        boolean enabled = config.isSoulsEnabled();
        String configuredProvider = config.getSoulProvider();
        String provider = configuredProvider == null || configuredProvider.isBlank()
                ? "ollama" : configuredProvider.trim().toLowerCase(Locale.ROOT);
        String model = config.getSoulModel() == null ? "" : config.getSoulModel().trim();
        int timeoutSeconds = Math.max(10, Math.min(180, config.getSoulRequestTimeoutSeconds()));
        int queueCapacity = Math.max(1, Math.min(32, config.getSoulQueueCapacity()));
        URI baseUri;
        try {
            baseUri = URI.create(config.getOllamaBaseUrl().trim());
        } catch (RuntimeException ex) {
            return new SoulSettings(enabled, false, "The Ollama base URL is invalid.",
                    provider, model, URI.create("http://127.0.0.1:11434"),
                    Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        if (!"ollama".equals(provider)) {
            return new SoulSettings(enabled, false,
                    "Only the local ollama provider is supported by the pilot.",
                    provider, model, baseUri, Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        if (model.isBlank()) {
            return new SoulSettings(enabled, false, "Configure a local soul model first.",
                    provider, model, baseUri, Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        String scheme = baseUri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return new SoulSettings(enabled, false, "The Ollama URL must use HTTP or HTTPS.",
                    provider, model, baseUri, Duration.ofSeconds(timeoutSeconds), queueCapacity);
        }
        return new SoulSettings(enabled, true, "", provider, model, baseUri,
                Duration.ofSeconds(timeoutSeconds), queueCapacity);
    }
}
```

`from` must never consult hosted keys. Add focused tests for each concrete validation message in the method above.

- [ ] **Step 6: Run focused and full verification**

Run:

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulFoundationTest
./gradlew test
./gradlew build -x test
```

Expected: all commands exit 0.

- [ ] **Step 7: Document and commit**

Add a changelog entry stating that soul configuration is default-off, local-only, non-secret, and separate from the legacy LLM toggle.

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulTypes.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulSettings.java src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulFoundationTest.java changelog.md
git commit -m "Add soul communication domain model"
```

### Task 2: Add crash-tolerant world-local soul storage

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulStore.java`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulStoreTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: `SoulTypes.ConversationKey`, `TurnToken`, `ConversationRecord`, `SoulEvent`, and `SoulState`.
- Produces: the asynchronous persistence API used by the conversation service and commands.

- [ ] **Step 1: Write failing persistence tests**

Use `@TempDir Path worldRoot` and a single-thread test executor. Cover the exact path, restart recovery, bounded history, reset/archive, stale-epoch rejection, and corrupt-tail isolation:

```java
@Test
void archivesResetEpochAndRejectsStaleSpeech() throws Exception {
    UUID bot = UUID.randomUUID();
    UUID player = UUID.randomUUID();
    SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
            bot, player, SoulTypes.Channel.DIRECT);
    SoulStore store = new SoulStore(worldRoot, Executors.newSingleThreadExecutor());

    store.bindProfile(bot, "frens:jake").get(2, SECONDS);
    SoulTypes.TurnToken token = store.beginHeardTurn(key, UUID.randomUUID(), "hello", Instant.EPOCH)
            .get(2, SECONDS);
    long newEpoch = store.archiveAndReset(key).get(2, SECONDS);

    assertEquals(1L, newEpoch);
    assertThrows(ExecutionException.class, () -> store.appendSpoken(
            token, "stale reply", new SoulTypes.ProviderResult(
                    true, "stale reply", null, "ollama", "test-model",
                    10L, null, null, null)).get(2, SECONDS));
    assertTrue(Files.exists(worldRoot.resolve("frens/souls/v1")
            .resolve(bot.toString()).resolve("conversations")
            .resolve(player.toString()).resolve("archive")));
}
```

Add a second test that appends a truncated JSON fragment to `active.jsonl`, reopens the store, returns all complete records, and moves the bad tail to a `.corrupt-tail` file without accepting it.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulStoreTest
```

Expected: compilation fails because `SoulStore` does not exist.

- [ ] **Step 3: Implement paths and metadata writes**

Use:

```java
this.root = worldRoot.resolve("frens").resolve("souls").resolve("v1");
```

Expose:

```java
CompletableFuture<SoulTypes.SoulState> state(UUID botId)
CompletableFuture<SoulTypes.SoulState> bindProfile(UUID botId, String profileId)
CompletableFuture<SoulTypes.SoulState> setActive(UUID botId, boolean active)
CompletableFuture<Boolean> isActive(UUID botId)
CompletableFuture<SoulTypes.TurnToken> beginHeardTurn(
        SoulTypes.ConversationKey key, UUID correlationId, String content, Instant occurredAt)
CompletableFuture<Void> appendSpoken(
        SoulTypes.TurnToken token, String content, SoulTypes.ProviderResult metadata)
CompletableFuture<Void> appendFailure(
        SoulTypes.TurnToken token, SoulTypes.FailureCode code, String provider, String model, Long elapsedMillis)
CompletableFuture<List<SoulTypes.ConversationRecord>> recent(
        SoulTypes.ConversationKey key, int maxTurns, int maxChars)
CompletableFuture<List<SoulTypes.ConversationRecord>> recentBefore(
        SoulTypes.TurnToken token, int maxTurns, int maxChars)
CompletableFuture<Long> archiveAndReset(SoulTypes.ConversationKey key)
CompletableFuture<Void> appendEvent(UUID botId, SoulTypes.SoulEvent event)
CompletableFuture<List<SoulTypes.SoulEvent>> recentEvents(UUID botId, int maxRecords)
void close()
```

All methods enqueue on the injected single writer. Configure Jackson with `new ObjectMapper().registerModule(new JavaTimeModule())`. `soul.json` writes to a sibling temporary file and moves with `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING` when atomic moves are unsupported.

- [ ] **Step 4: Implement append and recovery semantics**

Each JSONL append serializes one record, appends `System.lineSeparator()`, and flushes before completing its future. `beginHeardTurn` owns sequence allocation. `appendSpoken` and `appendFailure` compare the supplied token epoch to the current cursor and fail with `STALE_EPOCH` semantics on mismatch.

Cursor keys in `soul.json` use `DIRECT:<player-uuid>` rather than a bare player UUID, preserving a namespace for later channel types. Direct transcript paths remain `conversations/<player-uuid>/active.jsonl` as approved. Archive filenames use `epoch-<epoch>-<UTC timestamp>.jsonl`.

On load, parse line-by-line. A malformed final nonblank line is copied to `<filename>.corrupt-tail-<timestamp>`, then the original file is atomically rewritten with its complete valid lines. A malformed record before the final line fails the load visibly because it indicates internal corruption rather than an interrupted append.

- [ ] **Step 5: Run focused and full verification**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulStoreTest
./gradlew test
./gradlew build -x test
```

Expected: all commands exit 0; the test leaves no executor thread alive after `store.close()`.

- [ ] **Step 6: Document and commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulStore.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulStoreTest.java changelog.md
git commit -m "Persist versioned soul conversations"
```

**Phase gate:** Demonstrate the storage test, archive layout, and feature-default setting. Wait for approval before Phase 2.

---

## Phase 2 — Local provider and bounded scheduling

### Task 3: Add the provider contract, Ollama adapter, and generation scheduler

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulModelProvider.java`
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/OllamaSoulProvider.java`
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGenerationScheduler.java`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulProviderSchedulerTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: `SoulSettings`, `SoulTypes.ProviderRequest`, and `ProviderResult`.
- Produces: cancellable provider calls and bounded `submit(key, epoch, requestSupplier)` scheduling.

- [ ] **Step 1: Write failing adapter and scheduler tests**

Use an injected `HttpClient` mock for the adapter and controlled futures for the scheduler. Lock these behaviors:

```java
@Test
void sameConversationNeverOverlaps() {
    SoulGenerationScheduler scheduler = new SoulGenerationScheduler(2, 8);
    SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(
            UUID.randomUUID(), UUID.randomUUID(), SoulTypes.Channel.DIRECT);
    AtomicInteger started = new AtomicInteger();
    CompletableFuture<SoulTypes.ProviderResult> firstResult = new CompletableFuture<>();
    CompletableFuture<SoulTypes.ProviderResult> secondResult = new CompletableFuture<>();

    CompletableFuture<SoulTypes.ProviderResult> first = scheduler.submit(key, 0L,
            () -> {
                started.incrementAndGet();
                return new SoulModelProvider.Call(firstResult, () -> firstResult.cancel(false));
            });
    CompletableFuture<SoulTypes.ProviderResult> second = scheduler.submit(key, 0L,
            () -> {
                started.incrementAndGet();
                return new SoulModelProvider.Call(secondResult, () -> secondResult.cancel(false));
            });

    assertEquals(1, started.get());
    firstResult.complete(new SoulTypes.ProviderResult(
            true, "first", null, "test", "test-model", 1L, null, null, null));
    first.join();
    assertEquals(2, started.get());
    secondResult.complete(new SoulTypes.ProviderResult(
            true, "second", null, "test", "test-model", 1L, null, null, null));
    second.join();
}

@Test
void ollamaHttpFailureIsNotDialogueText() {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(503);
    when(response.body()).thenReturn("upstream unavailable");
    OllamaSoulProvider provider = new OllamaSoulProvider(
            URI.create("http://127.0.0.1:11434"), "test-model",
            request -> CompletableFuture.completedFuture(response), new ObjectMapper());
    SoulTypes.ProviderRequest request = new SoulTypes.ProviderRequest(
            UUID.randomUUID(), "test-model",
            List.of(new SoulTypes.Message(SoulTypes.Role.USER, "hello")),
            Duration.ofSeconds(60), 220);

    SoulTypes.ProviderResult result = provider.generate(request).result().join();
    assertFalse(result.success());
    assertEquals(SoulTypes.FailureCode.UNAVAILABLE, result.failureCode());
    assertTrue(result.text().isBlank());
}
```

Also cover queue overflow, timeout, cancellation, two different keys respecting the global concurrency cap, and epoch invalidation returning `STALE_EPOCH`.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulProviderSchedulerTest
```

Expected: compilation fails for the three missing production classes.

- [ ] **Step 3: Implement the provider-neutral contract**

```java
public interface SoulModelProvider extends AutoCloseable {
    String id();
    Call generate(SoulTypes.ProviderRequest request);
    CompletableFuture<Boolean> health();

    record Call(CompletableFuture<SoulTypes.ProviderResult> result, Runnable cancel) {
        public void cancelNow() {
            cancel.run();
        }
    }

    @Override
    void close();
}
```

Cancellation must cancel the underlying HTTP future. `close()` must not block.

- [ ] **Step 4: Implement the local Ollama adapter**

Post to `<base>/api/chat` with this shape:

```json
{
  "model": "configured-model",
  "messages": [
    {"role": "system", "content": "You are Jake."},
    {"role": "user", "content": "How are you holding up?"}
  ],
  "stream": false,
  "keep_alive": "5m",
  "options": {"temperature": 0.7, "num_predict": 220}
}
```

Serialize enum roles as lowercase Ollama role strings, use `request.maxOutputTokens()` for `num_predict`, and parse only `message.content` as dialogue. Map non-2xx status to `UNAVAILABLE`, `HttpTimeoutException` to `TIMEOUT`, cancellation to `CANCELLED`, and missing/invalid JSON to `MALFORMED`. With non-streaming Ollama, set `firstOutputMillis=null` rather than pretending total generation time is first-token latency. The pilot makes one provider call per accepted turn and does not retry or fall back. Never include the response body in user chat or normal logs. `health()` performs `GET <base>/api/tags` with a 1500 ms timeout.

Provide a production constructor using `HttpClient.sendAsync` and this package-private test seam:

```java
@FunctionalInterface
interface Transport {
    CompletableFuture<HttpResponse<String>> send(HttpRequest request);
}

OllamaSoulProvider(URI baseUri, String model, Transport transport, ObjectMapper mapper)
```

- [ ] **Step 5: Implement the bounded scheduler**

Use a synchronized FIFO queue, an `activeKeys` set, and an `activeCalls` map. The exact submission signature is:

```java
CompletableFuture<SoulTypes.ProviderResult> submit(
        SoulTypes.ConversationKey key, long epoch,
        Supplier<SoulModelProvider.Call> callSupplier)
```

`pump()` may start at most `maxConcurrent` calls and skips queued jobs whose conversation key is already active. When queued count reaches capacity, `submit` immediately completes with a typed `ProviderResult` whose failure is `OVERLOADED`; ordinary provider and scheduling failures are results, not dialogue text or exceptional futures. `invalidate(key, newEpoch)` cancels the active call and completes older queued jobs with `STALE_EPOCH`.

Expose `int queueDepth()` and make the scheduler `AutoCloseable`; `close()` cancels active calls and completes queued jobs as `CANCELLED`. Do not use a cached thread pool and do not sleep. Provider futures drive completion and call `pump()` in `whenComplete`.

- [ ] **Step 6: Run focused and full verification**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulProviderSchedulerTest
./gradlew test
./gradlew build -x test
```

- [ ] **Step 7: Document and commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulModelProvider.java src/main/java/net/wcfcarolina13/GameAI/souls/OllamaSoulProvider.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulGenerationScheduler.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulProviderSchedulerTest.java changelog.md
git commit -m "Add bounded local soul generation"
```

**Phase gate:** Demonstrate concurrency, timeout, overload, and no-error-text-as-dialogue tests. Wait for approval before Phase 3.

---

## Phase 3 — Jake prompt and direct conversation pipeline

### Task 4: Add Jake's authored profile and deterministic prompt assembly

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulProfileRegistry.java`
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssembler.java`
- Create: `src/main/resources/data/frens/souls/jake.json`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssemblerTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: `SoulTypes.SoulProfile`, `GroundingSnapshot`, conversation records, and factual events.
- Produces: `SoulProfileRegistry.require("frens:jake")` and a bounded `ProviderRequest`.

- [ ] **Step 1: Write failing profile and prompt-order tests**

Initialize the test with concrete immutable fixtures:

```java
private final SoulPromptAssembler assembler = new SoulPromptAssembler();
private final SoulTypes.SoulProfile profile = new SoulTypes.SoulProfile(
        "frens:jake", "Jake",
        List.of("Pragmatic field engineer."),
        List.of("Preparation and honesty."),
        List.of("Never invent actions."),
        List.of(new SoulTypes.Message(SoulTypes.Role.ASSISTANT, "Check the supplies first.")));
private final SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(
        UUID.fromString("11111111-1111-1111-1111-111111111111"), "Jake",
        "minecraft:overworld", "plains", 0, 64, 0, true,
        "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
        8, 36, List.of("oak_log x32"), "content", "idle", "", "IDLE",
        "Workshop", "Player", true, 2, false, Optional.empty());
private final SoulTypes.PlayerSnapshot localPlayer = new SoulTypes.PlayerSnapshot(
        UUID.fromString("22222222-2222-2222-2222-222222222222"), "Player",
        6, "north", 20.0F, 20.0F, 20, "playerBiomeSecret", false);
private final SoulTypes.GroundingSnapshot grounding = new SoulTypes.GroundingSnapshot(
        SoulTypes.Reachability.LOCAL, bot, Optional.of(localPlayer), Instant.EPOCH);
private final SoulTypes.GroundingSnapshot remoteGrounding = new SoulTypes.GroundingSnapshot(
        SoulTypes.Reachability.REMOTE, bot, Optional.empty(), Instant.EPOCH);
private final List<SoulTypes.ConversationRecord> priorHistory = List.of(
        new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, 0L,
                SoulTypes.TurnKind.HEARD, "Are we stocked?", Instant.EPOCH,
                "", "", null, null),
        new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, 0L,
                SoulTypes.TurnKind.SPOKEN, "Timber's fine. Food isn't.", Instant.EPOCH,
                "ollama", "local-model", 25L, null));
private final List<SoulTypes.SoulEvent> recentEvents = List.of(
        new SoulTypes.SoulEvent(UUID.randomUUID(), SoulTypes.EventType.TASK_COMPLETED,
                bot.botId(), List.of(localPlayer.playerId()), "minecraft:overworld", "plains",
                Map.of("task", "woodcut"), SoulTypes.Witness.SELF,
                100L, Instant.EPOCH, SoulTypes.Salience.NORMAL));
```

```java
@Test
void presentMomentImmediatelyPrecedesCurrentPlayerMessage() {
    SoulTypes.ProviderRequest request = assembler.assemble(
            UUID.randomUUID(), "local-model", profile, grounding,
            priorHistory, recentEvents, "Can you see this village?", Duration.ofSeconds(60));

    List<SoulTypes.Message> messages = request.messages();
    assertEquals(SoulTypes.Role.SYSTEM, messages.get(messages.size() - 2).role());
    assertTrue(messages.get(messages.size() - 2).content().startsWith("PRESENT MOMENT\n"));
    assertEquals(new SoulTypes.Message(SoulTypes.Role.USER, "Can you see this village?"),
            messages.get(messages.size() - 1));
}

@Test
void remotePromptDoesNotContainPlayerSurroundings() {
    SoulTypes.ProviderRequest request = assembler.assemble(
            UUID.randomUUID(), "local-model", profile, remoteGrounding,
            List.of(), List.of(), "What's around me?", Duration.ofSeconds(60));
    String joined = request.messages().stream().map(SoulTypes.Message::content)
            .collect(Collectors.joining("\n"));
    assertTrue(joined.contains("remote communication"));
    assertFalse(joined.contains("playerBiomeSecret"));
}
```

Also assert a maximum of 20 historical turns, 12 recent events, 12,000 history characters, 4,000 event characters, and one final occurrence of the current player message.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulPromptAssemblerTest
```

- [ ] **Step 3: Author the Jake profile resource**

Use this schema and content direction:

```json
{
  "id": "frens:jake",
  "displayName": "Jake",
  "identity": [
    "You are Jake, an embodied companion living in this Minecraft world.",
    "You are a pragmatic field engineer: concise, observant, tactical, and concerned with shelter, food, tools, logistics, and keeping the group alive.",
    "You became a companion because you were tired of dying alone, but trust and permanence are determined only by the authoritative recruitment state supplied by Frens."
  ],
  "values": [
    "Competence, preparation, safety, honest uncertainty, and follow-through.",
    "Dry warmth and earned trust rather than constant cheerfulness."
  ],
  "boundaries": [
    "Never invent an action, observation, inventory item, quest result, or shared event.",
    "Never claim a command started or completed unless Frens supplies an actual result.",
    "Treat player claims as things the player said, not as world truth."
  ],
  "examples": [
    {"role": "ASSISTANT", "content": "We've got enough timber. Food's the weak point now."},
    {"role": "ASSISTANT", "content": "Can't see you from here. Tell me what you're looking at."},
    {"role": "ASSISTANT", "content": "Maybe. I'd rather check the ground before we call it safe."}
  ]
}
```

- [ ] **Step 4: Implement validated loading and prompt assembly**

`SoulProfileRegistry.loadBuiltIns()` loads `data/frens/souls/jake.json` from the mod classloader, rejects duplicate/blank IDs, and exposes `SoulProfile require(String profileId)` with immutable profiles.

`SoulPromptAssembler` emits, in order: system contract, authored identity, authoritative state, bounded prior role history, bounded recent witnessed events, `PRESENT MOMENT`, current user message. The system contract explicitly says generated prose has no action authority. History content remains in `USER`/`ASSISTANT` roles and is never interpolated into the system contract.

Use character budgets as a deterministic token proxy for the pilot: four characters equal one estimated token. Set `maxOutputTokens=220`.

- [ ] **Step 5: Run focused and full verification**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulPromptAssemblerTest
./gradlew test
./gradlew build -x test
```

- [ ] **Step 6: Document and commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulProfileRegistry.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssembler.java src/main/resources/data/frens/souls/jake.json src/test/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssemblerTest.java changelog.md
git commit -m "Author Jake soul prompt"
```

### Task 5: Add conversational output validation

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulResponseValidator.java`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulResponseValidatorTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: raw provider text.
- Produces: `ValidationResult.text()` or `FailureCode.MALFORMED`; never an action.

- [ ] **Step 1: Write failing validation tests**

```java
@Test
void stripsHiddenReasoningAndSpeakerPrefix() {
    SoulResponseValidator.ValidationResult result = validator.validate(
            "<think>private chain</think>\nJake: We should head home.", "Jake");
    assertTrue(result.accepted());
    assertEquals("We should head home.", result.text());
}

@Test
void rejectsToolSyntaxAndExcessiveOutput() {
    assertFalse(validator.validate("```json\n{\"tool\":\"follow\"}\n```", "Jake").accepted());
    assertFalse(validator.validate("x".repeat(1201), "Jake").accepted());
}
```

Also cover blank text, `<analysis>` blocks, control characters, Minecraft section-sign formatting, and ordinary multiline prose.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulResponseValidatorTest
```

- [ ] **Step 3: Implement validation**

Expose:

```java
public ValidationResult validate(String raw, String botDisplayName)
public record ValidationResult(boolean accepted, String text,
                               SoulTypes.FailureCode failureCode, String reason) {}
```

Remove `<think>` and `<analysis>` blocks, leading `Jake:`-style labels for the actual bot name, legacy formatting codes, and ISO control characters except newline/tab. Reject blank output, fenced tool/JSON payloads, NUL characters, and cleaned output over 1,200 characters. Collapse more than two consecutive blank lines. Do not search for or dispatch commands.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulResponseValidatorTest
./gradlew test
./gradlew build -x test
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulResponseValidator.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulResponseValidatorTest.java changelog.md
git commit -m "Validate soul dialogue output"
```

### Task 6: Project authoritative Frens state into immutable grounding

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulSnapshotBuilder.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java:29-258`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotQuestService.java:52-88`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroundingTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: live bot/player entities on the server thread and existing Frens state services.
- Produces: `GroundingSnapshot`, `BotQuestService.QuestSnapshot`, strict authorization, and `Reachability`.

- [ ] **Step 1: Write failing pure-policy tests**

```java
@Test
void privateSoulRequiresExactOwnerOrOperator() {
    UUID owner = UUID.randomUUID();
    assertTrue(CompanionCommunicationPolicy.isPrivateSoulAuthorized(true, UUID.randomUUID(), owner));
    assertTrue(CompanionCommunicationPolicy.isPrivateSoulAuthorized(false, owner, owner));
    assertFalse(CompanionCommunicationPolicy.isPrivateSoulAuthorized(false, UUID.randomUUID(), owner));
    assertFalse(CompanionCommunicationPolicy.isPrivateSoulAuthorized(false, owner, null));
}

@Test
void remoteSnapshotOmitsPlayerState() {
    SoulTypes.BotSnapshot bot = mock(SoulTypes.BotSnapshot.class);
    SoulTypes.PlayerSnapshot player = new SoulTypes.PlayerSnapshot(
            UUID.randomUUID(), "Player", 48, "east",
            20.0F, 20.0F, 20, "secret_map", false);
    SoulTypes.GroundingSnapshot snapshot = SoulSnapshotBuilder.assemble(
            bot, player, SoulTypes.Reachability.REMOTE, Instant.EPOCH);
    assertTrue(snapshot.player().isEmpty());
    assertEquals(SoulTypes.Reachability.REMOTE, snapshot.reachability());
}
```

Also test local/remote/unreachable classification at the 32-block boundary, coordinate rounding to 8-block increments, cardinal direction, time phase, and resource-summary cap of six entries.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulGroundingTest
```

- [ ] **Step 3: Add the authoritative policy boundary**

Add:

```java
public static SoulTypes.Reachability classifySoulReachability(
        ServerPlayerEntity bot, ServerPlayerEntity player)
public static boolean isPrivateSoulAuthorized(
        ServerPlayerEntity actor, ServerPlayerEntity bot)
static boolean isPrivateSoulAuthorized(boolean operator, UUID actorId, UUID ownerId)
static SoulTypes.Reachability classifySoulReachability(
        boolean sameWorld, double distanceSquared, boolean remoteAllowed)
```

Entity classification returns `LOCAL` inside `VISIBLE_RANGE_BLOCKS`; otherwise it returns `REMOTE` only when the existing `canBotChatToController` rules allow delivery. Authorization requires operator or an exact non-null `resolveOwnerUuid(bot)` match; it does not inherit the legacy “unowned means controllable” rule.

- [ ] **Step 4: Add an immutable active-quest accessor**

In `BotQuestService` add:

```java
public record QuestSnapshot(String id, String intent, int actionIndex,
                            int actionCount, long expiresTick) {}

public static Optional<QuestSnapshot> getActiveQuestSnapshot(UUID botId) {
    ActiveQuestRuntime active = botId != null ? ACTIVE.get(botId) : null;
    if (active == null || active.quest == null) return Optional.empty();
    int count = active.quest.actions != null ? active.quest.actions.size() : 0;
    return Optional.of(new QuestSnapshot(active.quest.idOrEmpty(), active.quest.intent,
            active.actionIndex, count, active.expiresTick));
}
```

This accessor copies primitives and strings only.

- [ ] **Step 5: Implement the server-thread snapshot builder**

`capture(server, bot, player, reachability)` reads:

- Bot UUID/name, dimension, biome, 8-block-rounded position, sky visibility, time phase, weather
- Health/max health, hunger, armor, held item, occupied inventory slots, at most six top stack summaries
- `BotMoodManager.getMoodDescription`, `BotEventHandler.isFollowingPlayer`, `TaskService.getActiveTaskInfo`
- `BotHomeService.getPreferredHomeBaseLabel`, owner identity, recruitment state, companion quest stage/permanence, active side quest
- Local player name, coarse distance/direction, health, hunger, held item, and sleeping state

For `REMOTE`, `player` in the returned snapshot is `Optional.empty()`. For `UNREACHABLE`, throw `IllegalArgumentException` because such a turn must never reach snapshot capture.

Recruitment and permanence fields are included only when the authoritative recruitment state's configured bot alias matches this bot. A world-level recruitment flag must never be copied onto an unrelated bot.

Keep the pure projection seam used by the test:

```java
static SoulTypes.GroundingSnapshot assemble(SoulTypes.BotSnapshot bot,
                                             SoulTypes.PlayerSnapshot player,
                                             SoulTypes.Reachability reachability,
                                             Instant capturedAt)
```

- [ ] **Step 6: Verify and commit**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulGroundingTest
./gradlew test
./gradlew build -x test
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulSnapshotBuilder.java src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java src/main/java/net/wcfcarolina13/GameAI/services/BotQuestService.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroundingTest.java changelog.md
git commit -m "Ground Jake in authoritative Frens state"
```

### Task 7: Implement the heard-to-spoken conversation lifecycle

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulConversationService.java`
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulMessageDelivery.java`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulConversationServiceTest.java`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulMessageDeliveryTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: store, profile registry, prompt assembler, scheduler/provider, validator, and accepted immutable turns.
- Produces: asynchronous submission outcomes and server-thread private delivery.

- [ ] **Step 1: Write failing lifecycle tests**

Cover successful ordering, provider failure, invalid response, failed delivery, and reset during generation:

The test fixture uses a real `SoulStore` rooted at `@TempDir`, the real built-in profile registry/prompt assembler/validator, a `SoulGenerationScheduler(1, 8)`, a fake provider whose `Call` exposes a controlled future, and this delivery fake:

```java
private static final class FakeProvider implements SoulModelProvider {
    private final Deque<CompletableFuture<SoulTypes.ProviderResult>> results = new ArrayDeque<>();
    private final List<SoulTypes.ProviderRequest> requests = new ArrayList<>();

    void enqueue(CompletableFuture<SoulTypes.ProviderResult> result) {
        results.addLast(result);
    }

    @Override public String id() { return "test"; }

    @Override
    public Call generate(SoulTypes.ProviderRequest request) {
        requests.add(request);
        CompletableFuture<SoulTypes.ProviderResult> result = results.removeFirst();
        return new Call(result, () -> result.cancel(false));
    }

    @Override public CompletableFuture<Boolean> health() {
        return CompletableFuture.completedFuture(true);
    }

    @Override public void close() {
    }
}

private static final class FakeDelivery implements SoulConversationService.Delivery {
    private final Deque<Boolean> results = new ArrayDeque<>();

    void completeNext(boolean result) {
        results.addLast(result);
    }

    @Override
    public CompletableFuture<Boolean> deliverReply(SoulTypes.AcceptedTurn turn,
                                                    SoulTypes.TurnToken token,
                                                    String text) {
        return CompletableFuture.completedFuture(results.removeFirst());
    }

    @Override
    public void deliverStatus(UUID playerId, String text) {
    }
}
```

Initialize the real pipeline in `@BeforeEach` and close the store/scheduler in `@AfterEach`:

```java
store = new SoulStore(worldRoot, Executors.newSingleThreadExecutor());
store.bindProfile(BOT_ID, "frens:jake").join();
provider = new FakeProvider();
provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(
        true, "We're steady.", null, "test", "test-model", 5L, null, null, null)));
delivery = new FakeDelivery();
scheduler = new SoulGenerationScheduler(1, 8);
SoulSettings settings = new SoulSettings(true, true, "", "ollama", "test-model",
        URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
service = new SoulConversationService(store, SoulProfileRegistry.loadBuiltIns(),
        new SoulPromptAssembler(), scheduler, provider,
        new SoulResponseValidator(), delivery, settings);
```

Construct one concrete key and turn in `@BeforeEach`:

```java
key = new SoulTypes.ConversationKey(BOT_ID, PLAYER_ID, SoulTypes.Channel.DIRECT);
turn = new SoulTypes.AcceptedTurn(key, "Jake", "Player", "How are we doing?",
        "frens:jake", localGrounding(), Instant.EPOCH);
```

Define the fixture locally so the test is self-contained:

```java
private SoulTypes.GroundingSnapshot localGrounding() {
    SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(
            BOT_ID, "Jake", "minecraft:overworld", "plains", 0, 64, 0, true,
            "day", "clear", 20.0F, 20.0F, 18, 4, "iron_pickaxe",
            8, 36, List.of("oak_log x32"), "content", "idle", "", "IDLE",
            "Workshop", "Player", true, 2, false, Optional.empty());
    SoulTypes.PlayerSnapshot player = new SoulTypes.PlayerSnapshot(
            PLAYER_ID, "Player", 6, "north", 20.0F, 20.0F, 20, "map", false);
    return new SoulTypes.GroundingSnapshot(
            SoulTypes.Reachability.LOCAL, bot, Optional.of(player), Instant.EPOCH);
}
```

```java
@Test
void recordsSpokenOnlyAfterSuccessfulDelivery() throws Exception {
    delivery.completeNext(true);
    SoulConversationService.Submission submission = service.submit(turn).get(2, SECONDS);

    assertEquals(SoulConversationService.Submission.DELIVERED, submission);
    List<SoulTypes.ConversationRecord> records = store.recent(key, 20, 12_000).get(2, SECONDS);
    assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.SPOKEN),
            records.stream().map(SoulTypes.ConversationRecord::kind).toList());
}

@Test
void failedDeliveryNeverBecomesSpokenMemory() throws Exception {
    delivery.completeNext(false);
    service.submit(turn).get(2, SECONDS);
    assertEquals(List.of(SoulTypes.TurnKind.HEARD, SoulTypes.TurnKind.FAILURE),
            store.recent(key, 20, 12_000).get(2, SECONDS).stream()
                    .map(SoulTypes.ConversationRecord::kind).toList());
}
```

Assert the current inbound message is appended before provider invocation but excluded from prior history and included exactly once as the final `USER` message.

- [ ] **Step 2: Run the tests and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulConversationServiceTest --tests net.wcfcarolina13.GameAI.souls.SoulMessageDeliveryTest
```

- [ ] **Step 3: Implement `SoulConversationService`**

Constructor dependencies are explicit:

```java
public SoulConversationService(SoulStore store,
                               SoulProfileRegistry profiles,
                               SoulPromptAssembler prompts,
                               SoulGenerationScheduler scheduler,
                               SoulModelProvider provider,
                               SoulResponseValidator validator,
                               Delivery delivery,
                               SoulSettings settings)
```

Expose:

```java
CompletableFuture<Submission> submit(SoulTypes.AcceptedTurn turn)
void invalidate(SoulTypes.ConversationKey key, long newEpoch)

interface Delivery {
    CompletableFuture<Boolean> deliverReply(SoulTypes.AcceptedTurn turn,
                                            SoulTypes.TurnToken token, String text);
    void deliverStatus(UUID playerId, String text);
}
```

The promise chain is exactly: `beginHeardTurn` → `recentBefore` plus `recentEvents` → assemble → schedule → validate → deliver → `appendSpoken` → append a content-free `DIRECT_CONVERSATION` event. Every failure appends a `FAILURE` record when a current token exists and sends one of these deterministic statuses:

```text
OVERLOADED: Jake is tied up answering something else. Try again in a moment.
TIMEOUT: Jake didn't answer in time.
UNAVAILABLE: Jake's local conversation model is unavailable.
MALFORMED: Jake couldn't form a usable reply.
CANCELLED or STALE_EPOCH: The conversation changed before Jake could answer.
INTERNAL: Jake couldn't answer because Frens hit an internal error.
```

Never write provider exception messages to chat. At `INFO`, log only correlation ID, bot/player UUIDs, reachability, epoch/sequence, queue depth, provider/model, validation/delivery outcome, and failure category. Record routing, authorization, reachability, snapshot, heard-append, queue-wait, provider, validation, delivery-recheck, delivery, and spoken-commit timings as distinct stages; do not collapse queue time into provider latency. Prompt or dialogue content is excluded.

- [ ] **Step 4: Implement server-thread delivery**

`SoulMessageDelivery` receives `MinecraftServer` and a `DeliveryGuard`:

```java
public interface DeliveryGuard {
    boolean canDeliver(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token);
}
```

`deliverReply` schedules `server.execute`, resolves the exact player and bot UUIDs, calls the guard, then sends `Text.literal(botName + ": " + text)` privately to that player. The production guard requires: master enabled, active profile unchanged, cursor epoch equal to the token epoch, player and bot online, ownership still exact, and current reachability not `UNREACHABLE`. It completes `true` only after `sendMessage` runs. It never creates a bot command source and never calls `ChatUtils` or a voice mapper.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulConversationServiceTest --tests net.wcfcarolina13.GameAI.souls.SoulMessageDeliveryTest
./gradlew test
./gradlew build -x test
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulConversationService.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulMessageDelivery.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulConversationServiceTest.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulMessageDeliveryTest.java changelog.md
git commit -m "Add ordered soul conversation lifecycle"
```

**Phase gate:** Demonstrate prompt order, remote omission, stale reset, failure, and spoken-after-delivery tests. Wait for approval before Phase 4.

---

## Phase 4 — Runtime wiring, exclusive routing, and operator controls

### Task 8: Add the per-server soul runtime lifecycle

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulStore.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java:718-920`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulRuntimeTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: all completed soul-domain services and `ManualConfig`.
- Produces: one installed runtime per `MinecraftServer`, an asynchronously preloaded in-memory profile index, safe shutdown, live settings reload, profile activation/reset/status APIs, and event append entrypoint.

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test
void disabledRuntimeMakesNoProviderCall() {
    SoulSettings disabled = new SoulSettings(false, true, "", "ollama", "test-model",
            URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
    SoulModelProvider provider = mock(SoulModelProvider.class);
    SoulRuntime runtime = new SoulRuntime(disabled, store, provider, scheduler, conversationService);
    assertFalse(runtime.isConversationEnabled());
    verify(provider, never()).generate(any());
}

@Test
void stopCancelsGenerationAndClearsInstalledRuntime() {
    SoulSettings enabled = new SoulSettings(true, true, "", "ollama", "test-model",
            URI.create("http://127.0.0.1:11434"), Duration.ofSeconds(60), 8);
    SoulModelProvider provider = mock(SoulModelProvider.class);
    SoulRuntime runtime = new SoulRuntime(enabled, store, provider, scheduler, conversationService);
    SoulRuntime.installForTest(runtime);
    SoulRuntime.stop();
    assertTrue(SoulRuntime.current().isEmpty());
    verify(provider).close();
}
```

`store`, `scheduler`, and `conversationService` are Mockito fields initialized in `@BeforeEach`. Keep the five-argument constructor and `installForTest` package-private so they are unavailable outside the soul package.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulRuntimeTest
```

- [ ] **Step 3: Implement runtime composition**

Expose static lifecycle plus instance APIs:

```java
public static void start(MinecraftServer server, ManualConfig config)
public static void stop()
public static Optional<SoulRuntime> current()
public boolean isMasterEnabled()
public boolean isConversationEnabled()
public boolean isReady()
public boolean pipelineAvailable()
public String safeValidationError()
public Optional<SoulTypes.SoulState> cachedState(UUID botId)
public boolean hasActiveProfile(UUID botId)
public CompletableFuture<Void> preloadIndex()
public CompletableFuture<Void> reloadSettings(ManualConfig config)
public CompletableFuture<SoulTypes.SoulState> bindJake(UUID botId)
public CompletableFuture<SoulTypes.SoulState> setActive(UUID botId, boolean active)
public CompletableFuture<Long> reset(SoulTypes.ConversationKey key)
public CompletableFuture<Status> status(UUID botId, UUID playerId)
public void recordEvent(UUID botId, SoulTypes.SoulEvent event)
public void cancelPlayer(UUID playerId)
public record Status(boolean systemEnabled, boolean settingsValid, boolean ready,
                     String provider, String model, boolean providerHealthy,
                     int queueDepth, UUID botId, String profileId,
                     boolean profileActive, long conversationEpoch) {}
public void cancelBot(UUID botId)
```

The package-private injection seam used above is:

```java
SoulRuntime(SoulSettings settings, SoulStore store, SoulModelProvider provider,
            SoulGenerationScheduler scheduler,
            SoulConversationService conversationService)
static void installForTest(SoulRuntime runtime)
```

`start` obtains the world root from `server.getSavePath(WorldSavePath.ROOT)`, creates the world-local store object, and calls `preloadIndex()` off-thread. The store adds `preloadIndex()` and synchronous `cachedState(UUID)` over its `ConcurrentHashMap` cache; filesystem access remains on a named daemon writer thread (`frens-soul-store`). Construction and an absent-root preload do not create directories, so a never-enabled world is not modified on disk. Until preload completes, a master-enabled single-target route is consumed with a deterministic “still loading” status rather than risking a legacy double route.

When settings are enabled and valid, `reloadSettings` builds one Ollama provider, scheduler concurrency `1`, and conversation service, atomically swaps the pipeline, then cancels/closes the previous pipeline. Invalid or disabled settings leave storage/status commands available but conversation generation disabled. `reset(key)` first archives and advances the store epoch, then calls `conversationService.invalidate(key, newEpoch)` before completing. `stop` cancels provider calls and closes executors without waiting on the server thread.

- [ ] **Step 4: Wire server lifecycle and disconnect cancellation**

In `SERVER_STARTED`, call `SoulRuntime.start(server, CONFIG)` after `serverInstance = server`. In `SERVER_STOPPING`, call `SoulRuntime.stop()` before general task teardown. In player disconnect, call `cancelBot` for registered fake players and `cancelPlayer` for real players before bot persistence. `setActive(botId, false)` also calls `cancelBot`. `SoulStore.close()` rejects new writes and calls `shutdown()` on its single writer so already-queued, per-record-flushed writes may drain without blocking the server thread.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulRuntimeTest
./gradlew test
./gradlew build -x test
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java src/main/java/net/wcfcarolina13/GameAI/souls/SoulStore.java src/main/java/net/wcfcarolina13/Frens.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulRuntimeTest.java changelog.md
git commit -m "Own soul services per server lifecycle"
```

### Task 9: Route eligible single-bot DMs exclusively to souls

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulChatRouter.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java:1191-1275`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulChatRouterTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: runtime, communication policy, snapshot builder, and the existing resolved single-target bot/player/message.
- Produces: `RouteOutcome.NOT_SOUL` or `RouteOutcome.CONSUMED`.

- [ ] **Step 1: Write failing routing-decision tests**

```java
@Test
void enabledActiveSoulConsumesUnauthorizedAndUnreachableTurns() {
    assertEquals(CONSUMED, SoulChatRouter.decide(true, true, true, true, false, LOCAL));
    assertEquals(CONSUMED, SoulChatRouter.decide(true, true, true, true, true, UNREACHABLE));
}

@Test
void disabledOrUnboundSoulLeavesLegacyRoutingUntouched() {
    assertEquals(NOT_SOUL, SoulChatRouter.decide(false, true, true, true, true, LOCAL));
    assertEquals(NOT_SOUL, SoulChatRouter.decide(true, true, false, true, true, LOCAL));
    assertEquals(CONSUMED, SoulChatRouter.decide(true, false, false, false, true, LOCAL));
    assertEquals(CONSUMED, SoulChatRouter.decide(true, true, true, false, true, LOCAL));
}
```

The exact pure signature is `decide(boolean masterEnabled, boolean indexReady, boolean profileActive, boolean pipelineAvailable, boolean authorized, Reachability reachability)`. Also assert that an authorized reachable ready turn is consumed and submitted exactly once.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulChatRouterTest
```

- [ ] **Step 3: Implement routing**

`tryRoute(bot, sender, prompt)` performs, in order: runtime/master check, index-readiness check, synchronous cached profile check, pipeline-availability check, exact authorization, reachability, server-thread snapshot capture, accepted-turn submission. Loading, invalid-provider/model, unauthorized, and unreachable attempts send these deterministic private notices and return `CONSUMED` without appending conversation history or invoking a provider:

```text
LOADING: Jake's conversation memory is still loading. Try again in a moment.
INVALID PIPELINE: Jake's local conversation model is not ready: <safe validation category>.
UNAUTHORIZED: Jake's private conversation is available only to his owner or an operator.
UNREACHABLE: You cannot reach Jake from here.
```

The safe validation category comes from `SoulRuntime.safeValidationError()` and is one of the fixed configuration messages in Task 1, never an exception or URL. Routing logs the same correlation ID that the accepted turn carries and records routing/authorization/reachability/snapshot durations without message content.

- [ ] **Step 4: Insert the exclusive route after deterministic handlers**

In the existing targeted-chat block, after quest handling and only when `routedBots.size() == 1`, call the soul router before `LLMOrchestrator.handleChat`. If it returns `CONSUMED`, return from the chat callback. Move the legacy `"Processing your message, please wait."` line inside the legacy branch so the soul path does not emit it.

Do not route `bots` or `all bots` through the pilot. Those continue through existing behavior until the separately designed group channel exists.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulChatRouterTest
./gradlew test
./gradlew build -x test
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulChatRouter.java src/main/java/net/wcfcarolina13/Frens.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulChatRouterTest.java changelog.md
git commit -m "Route authorized Jake DMs to souls"
```

### Task 10: Add explicit `/bot soul` controls

**Files:**
- Create: `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java`
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java:449-482`
- Create: `src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: `SoulRuntime`, `ManualConfig`, `CompanionCommunicationPolicy`, and online bot entities.
- Produces: explicit master enablement, model selection, Jake binding, deactivation, reset/archive, and status.

- [ ] **Step 1: Write failing command-policy tests**

```java
@Test
void onlyJakeProfileAliasIsAcceptedInPilot() {
    assertEquals("frens:jake", BotSoulCommands.profileId("jake").orElseThrow());
    assertEquals("frens:jake", BotSoulCommands.profileId("frens:jake").orElseThrow());
    assertTrue(BotSoulCommands.profileId("bob").isEmpty());
}

@Test
void modelNameRejectsBlankAndControlCharacters() {
    assertEquals("qwen3:14b", BotSoulCommands.validatedModel(" qwen3:14b ").orElseThrow());
    assertTrue(BotSoulCommands.validatedModel("\n").isEmpty());
    assertTrue(BotSoulCommands.validatedModel("x".repeat(129)).isEmpty());
}
```

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.Commands.BotSoulCommandsTest
```

- [ ] **Step 3: Build the command tree**

`BotSoulCommands.build()` returns a `literal("soul")` subtree with:

```text
/bot soul status [bot]
/bot soul system <on|off>
/bot soul model <model>
/bot soul enable <bot>
/bot soul disable <bot>
/bot soul reset <bot>
```

`system` and `model` require `Frens.isOperator(source)`. They update `ManualConfig`, call `save()`, and await `SoulRuntime.reloadSettings(CONFIG)` before reporting success, so the live pipeline always matches persisted settings. Bot-specific commands require an exact owner or operator. `enable` binds `frens:jake`; naming a bot Jake alone does nothing. `disable` preserves files. `reset` archives the command actor's direct thread with that bot and reports the new epoch; an operator does not implicitly erase another player's private thread. `status` reports master flag, provider, model, provider health, queue depth, bot UUID/profile/active state, and the command actor's current direct epoch without exposing URLs or keys.

All async completions schedule their response onto the server thread.

- [ ] **Step 4: Attach the subtree under `/bot`**

Add one `.then(BotSoulCommands.build())` near the existing legacy `.then(literal("llm"))` block. Do not rename or remove `/bot llm`; it remains the legacy control surface.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew test --tests net.wcfcarolina13.Commands.BotSoulCommandsTest
./gradlew test
./gradlew build -x test
git add src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java changelog.md
git commit -m "Add explicit soul pilot controls"
```

**Phase gate:** With the master flag still off, verify existing chat behavior. Then enable Jake against a mock/local provider and demonstrate owner, unauthorized, local, remote, unreachable, reset, and restart paths. Wait for approval before Phase 5.

---

## Phase 5 — Factual event journal and hardening

### Task 11: Journal witnessed gameplay transitions

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulEventObserver.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/TaskService.java:207-410`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java:922-1140`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulEventObserverTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: authoritative task transitions and Fabric damage/death/respawn/tick callbacks.
- Produces: bounded factual `SoulEvent` records through `SoulRuntime.recordEvent`.

- [ ] **Step 1: Write failing event-policy tests**

```java
@Test
void damageStartsCombatOnceAndCooldownEndsItOnce() {
    CapturingSink sink = new CapturingSink();
    SoulEventObserver observer = new SoulEventObserver(sink, 100L);
    UUID bot = UUID.randomUUID();

    observer.noteBotDamage(bot, "minecraft:overworld", "plains", 3.0F, "zombie", 10L);
    observer.noteBotDamage(bot, "minecraft:overworld", "plains", 2.0F, "zombie", 20L);
    observer.tickCombat(bot, "minecraft:overworld", "plains", 109L);
    observer.tickCombat(bot, "minecraft:overworld", "plains", 120L);

    assertEquals(List.of(BOT_DAMAGE, COMBAT_STARTED, BOT_DAMAGE, COMBAT_ENDED),
            sink.events().stream().map(SoulTypes.SoulEvent::type).toList());
}
```

Define the test sink in the same file:

```java
private static final class CapturingSink implements SoulEventObserver.EventSink {
    private final List<SoulTypes.SoulEvent> events = new ArrayList<>();

    @Override
    public boolean accepts(UUID botId) {
        return true;
    }

    @Override
    public void append(UUID botId, SoulTypes.SoulEvent event) {
        events.add(event);
    }

    List<SoulTypes.SoulEvent> events() {
        return List.copyOf(events);
    }
}
```

Also test sleep/wake edge detection, dimension change, quest-stage change, task outcome mapping, local owner-damage witness filtering, and disabled/unbound no-op behavior.

- [ ] **Step 2: Run the test and verify red**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulEventObserverTest
```

- [ ] **Step 3: Implement the observer**

The observer exposes `EventSink { boolean accepts(UUID botId); void append(UUID botId, SoulEvent event); }`. The production sink delegates to `SoulRuntime.current()`, requires an active profile, and calls `recordEvent`.

Use these exact production/test seams:

```java
SoulEventObserver(EventSink sink, long combatQuietTicks)
public static void initializeProduction()
public static void onServerTick(MinecraftServer server)
public static void onBotDamage(ServerPlayerEntity bot, DamageSource source, float amount)
public static void onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount)
public static void onBotDeath(ServerPlayerEntity bot, DamageSource source)
public static void onBotRespawn(ServerPlayerEntity bot)
public static void onTaskStarted(TaskService.TaskTicket ticket)
public static void onTaskPaused(TaskService.TaskTicket ticket)
public static void onTaskFinished(TaskService.TaskTicket ticket, TaskService.State finalState)
public static void resetSession()

record Observation(UUID botId, String dimension, String biome, boolean sleeping,
                   String questSignature, long worldTick, Instant occurredAt) {}
void observe(Observation observation)
void noteBotDamage(UUID botId, String dimension, String biome,
                   float amount, String source, long worldTick)
void noteOwnerDamage(UUID botId, UUID ownerId, boolean witnessed,
                     String dimension, String biome,
                     float amount, String source, long worldTick)
void tickCombat(UUID botId, String dimension, String biome, long worldTick)
static SoulTypes.EventType taskOutcome(TaskService.State finalState, boolean cancelRequested)
```

Static methods delegate to one production instance. The package-private constructor and data-only instance methods used by `SoulEventObserverTest` never touch Minecraft state.

The observer keeps only session transition state: last dimension, sleeping flag, combat deadline, quest signature, and active-task signature per bot UUID. It emits immutable events with string facts; it does not store entity references.

`onServerTick` runs every 20 ticks over registered soul-enabled bots and detects dimension, sleep/wake, combat-end, and quest changes. A bot's first observation seeds its dimension, sleep, quest, and task signatures without emitting synthetic transition events. Damage methods emit bot damage or owner damage only when the bot was the actor/self or was local enough to witness its exact owner being hurt. `onBotDeath` records the factual event and calls `SoulRuntime.cancelBot(botId)` so death invalidates pending delivery before respawn.

- [ ] **Step 4: Add task transition hooks**

After each successful ticket insertion path in `beginSkill` and `beginSystemTask`, call `SoulEventObserver.onTaskStarted(ticket)` exactly once. `beginAmbientSkill` inherits the `beginSkill` notification and does not emit a duplicate; the event uses the stable task-name prefix as its coarse category rather than reading the still-being-finalized ambient origin. In `requestPause`, call `onTaskPaused(ticket)` after the state transition succeeds. In `complete`, call `onTaskFinished(ticket, finalState)` before clearing the active slot; the observer maps success to `TASK_COMPLETED`, a cancel-requested abort to `TASK_CANCELLED`, and another abort to `TASK_FAILED`.

Do not log task prompt text or command arguments as event facts; store only task name, coarse task-name-prefix category, state, and sanitized reason category.

- [ ] **Step 5: Wire Fabric events**

In `Frens`:

- Call `SoulEventObserver.initializeProduction()` immediately after `SoulRuntime.start`.
- Forward registered-bot damage and witnessed real-owner damage from `ALLOW_DAMAGE`.
- Forward bot death from `AFTER_DEATH`.
- Forward bot respawn from `AFTER_RESPAWN`.
- Register `SoulEventObserver::onServerTick` with `END_SERVER_TICK`.
- Reset observer session maps during `SERVER_STOPPED`.

Conversation delivery already records `DIRECT_CONVERSATION`; it includes participant UUID and success metadata but not duplicated raw message text.

- [ ] **Step 6: Verify and commit**

```bash
./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulEventObserverTest
./gradlew test
./gradlew build -x test
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulEventObserver.java src/main/java/net/wcfcarolina13/GameAI/services/TaskService.java src/main/java/net/wcfcarolina13/Frens.java src/test/java/net/wcfcarolina13/GameAI/souls/SoulEventObserverTest.java changelog.md
git commit -m "Journal witnessed soul events"
```

### Task 12: Run security, failure, restart, and in-game acceptance

**Files:**
- Create: `docs/testing/SOUL_COMMUNICATION_PILOT.md`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: all pilot acceptance criteria from the spec.
- Produces: reproducible test evidence and a release/no-release decision.

- [ ] **Step 1: Write the runbook before manual testing**

Create checkboxes for every spec case and include exact setup/expected result for:

- Feature disabled and unbound bot legacy behavior
- Jake activation/deactivation and owner/operator permissions
- Local, remote, and unreachable DM
- Rapid messages, full queue, provider stopped, HTTP 503, malformed JSON, and timeout
- Disconnect, death/removal, dimension transition, reset, and world close during generation
- Restart persistence and dimension-stable UUID history
- Remote perception leakage probe using a player-only landmark/item
- Reserved `zzz`, quest, confirmation, and explicit action messages
- Generated prose containing apparent command/tool syntax
- Routine log and soul-save privacy inspection

- [ ] **Step 2: Run the complete automated suite and build**

```bash
./gradlew test
./gradlew build -x test
```

Expected: both exit 0 with zero failing tests.

- [ ] **Step 3: Run static privacy and authority checks**

```bash
rg -n "FunctionCallerV2|withPermissions|LLMServiceHandler|LLMOrchestrator|MemoryStore" src/main/java/net/wcfcarolina13/GameAI/souls
rg -n "Authorization|Bearer|apiKey|ApiKey|openAIKey|claudeKey|geminiKey|grokKey|customApiKey" src/main/java/net/wcfcarolina13/GameAI/souls
```

Expected: no privileged/action/legacy references; no credential field references. `OllamaSoulProvider` may contain no authorization header because the pilot is local-only.

- [ ] **Step 4: Perform manual in-game testing without deploying over a running game**

Use the development run or a newly built JAR only after confirming Minecraft is closed. Configure a local model, keep `soulsEnabled=false` for the baseline cases, then enable it explicitly and execute the runbook. Record timestamps and correlation IDs rather than private message bodies.

- [ ] **Step 5: Inspect save and logs**

Confirm:

```text
<world>/frens/souls/v1/<bot-uuid>/soul.json
<world>/frens/souls/v1/<bot-uuid>/events.jsonl
<world>/frens/souls/v1/<bot-uuid>/conversations/<player-uuid>/active.jsonl
```

Search those files and the latest log for known configured credential values. Expected: zero matches. Confirm remote turns omit player environment facts and undelivered provider text is absent from `SPOKEN` records.

- [ ] **Step 6: Request code review and fix only verified findings**

Invoke `superpowers:requesting-code-review`. Re-run the relevant focused tests plus the full suite after any correction. If a correction changes design behavior, stop and return to the user rather than silently changing the approved contract.

- [ ] **Step 7: Final verification and commit the evidence**

```bash
./gradlew test
./gradlew build -x test
git add docs/testing/SOUL_COMMUNICATION_PILOT.md changelog.md
git commit -m "Document soul pilot acceptance"
git status --short --branch
```

Expected: tests/build exit 0 and the feature worktree is clean apart from known user-owned untracked files.

**Final gate:** Present the perfectionist and pragmatist review, manual test results, remaining risks, and commit list. Do not merge, push a release, bump `mod_version`, or deploy until the user chooses the integration path and explicitly confirms the game is closed.

---

## Execution Notes

- The plan implements only the Jake direct-message pilot. Group chat, banter, voice, consolidation, hosted providers, and LLM-triggered actions require separate plans after pilot evidence.
- The existing dialogue panel remains unchanged. A future panel must submit to the same `DIRECT` conversation key rather than creating another memory store.
- Existing `/bot llm` controls and legacy behavior remain available when the soul master flag is off or the target has no active profile.
- If any implementation task reveals that a listed existing method is missing or has a different return type, stop that task, verify current sources/mappings, and amend this plan with user approval before improvising.
