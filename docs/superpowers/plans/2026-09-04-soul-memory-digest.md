# Soul Memory Digest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** At each Minecraft day rollover a soul-bound bot digests what each player said to it (DM + party scenes it took part in) into ≤5 one-line facts via the local model, stores them in `mind.json`, and recalls them in every prompt/seed where that player is present.

**Architecture:** Pure rules in a new `SoulMemoryDigestOps` (gather/validate/merge/decay/anchors); one new `SoulMemoryDigestService` that runs on the store thread, submits one generation per (bot, player) through the existing `SoulGenerationScheduler`, and writes back through `SoulStore.updateMind`. Wiring hangs off `SoulRuntime.onNewDay` (Phase 2 hook). Prompt/seed injection reuses the `mindLookup` / `SoulMindOps.anchors` seams. No server-thread work beyond the existing name capture.

**Tech Stack:** Java 21, Fabric 0.18.4 / MC 1.21.11, Jackson 2.17 (records, canonical ctor), JUnit 5. Build: `./gradlew build` (runs tests). Package: `net.wcfcarolina13.GameAI.souls` (all files below are relative to `src/main/java/net/wcfcarolina13/GameAI/souls/` unless stated; tests in `src/test/java/net/wcfcarolina13/GameAI/souls/`).

**Spec:** `docs/superpowers/specs/2026-09-04-frens-soul-memory-digest-design.md`

## Global Constraints

- Nothing new on the server thread except reading `store.cachedMind` and capturing player names (spec §9).
- Records: add new fields **trailing**, keep a compat constructor with the old arity delegating with empties (Jackson 2.17 picks the canonical ctor for records; missing JSON fields arrive null and are defaulted in the compact ctor).
- Constants (spec §3/§5/§6): MIN_PLAYER_LINES 4 · MAX_RECORDS 40 · MAX_MATERIAL_CHARS 2000 · MAX_FACTS 5 · MAX_FACT_CHARS 100 · RUNAWAY_LINES 8 · INITIAL_SALIENCE 10 · MAX_SALIENCE 10 · RECALL_BUMP 3 · DUP_BUMP 2 · MAX_PER_PLAYER 24 · MAX_ARCHIVED 100 · DUP_JACCARD 0.6 · MAX_ABOUT_LINES 5 · MAX_ABOUT_CHARS 300 · anchor weight = `SoulMindOps.MEMORY_ANCHOR_WEIGHT` (4) · recall cooldown = `SoulMindOps.RECALL_COOLDOWN_DAYS` (3).
- Cursor keys: DIRECT → `SoulStore.cursorKey(key)` (= `"DIRECT:<player>"`), party → `"PARTY:<owner>"` (same function on the party key, since `SoulGroupTypes.partyKey(owner)` has channel PARTY).
- Cursor semantics: `ConversationCursor(epoch, nextSequence)` = first sequence **not yet digested**.
- Routine logs carry counts/outcomes only; never transcript text or model output.
- `./gradlew build` green after every task; one commit per task; `changelog.md` entry + `mod_version` bump only in the last task.

---

### Task 1: Types — `PlayerMemory`, `SoulMind` fields, `ConversationRecord.participants`

**Files:**
- Modify: `SoulTypes.java` (records `ConversationRecord` ~L108, `DayMemory` ~L307, `SoulMind` ~L324)
- Modify: `SoulMindOps.java` (`rebuild` ~L381 and the three `with*` helpers ~L365–379)
- Test: `SoulFoundationTest.java` (append)

**Interfaces:**
- Produces:
  - `SoulTypes.PlayerMemory(UUID playerId, int day, String fact, int salience, int lastRecalledDay, List<UUID> sourceCorrelationIds)`
  - `SoulTypes.SoulMind(int schemaVersion, Stance playerStance, List<OpenThread> threads, List<DayMemory> memories, Set<String> seen, long lastConsolidatedAtMs, int lastDay, int lastTaskTrustDay, List<PlayerMemory> playerMemories, List<PlayerMemory> archivedPlayerMemories, Map<String, ConversationCursor> digestCursors)` + 8-arg compat ctor + `empty()`
  - `SoulTypes.ConversationRecord(... FailureCode failureCode, List<UUID> participants)` + 10-arg compat ctor
  - `SoulMindOps.withPlayerMemories(mind, list)`, `withArchivedPlayerMemories(mind, list)`, `withDigestCursors(mind, map)` (package-private static)

- [ ] **Step 1: Write the failing test** (append to `SoulFoundationTest`):

```java
@Test
void mindAndRecordCompatConstructorsDefaultNewFields() {
    SoulTypes.SoulMind legacy = new SoulTypes.SoulMind(1, SoulTypes.Stance.BASELINE, List.of(), List.of(),
            Set.of(), 0L, -1, -1);
    assertTrue(legacy.playerMemories().isEmpty());
    assertTrue(legacy.archivedPlayerMemories().isEmpty());
    assertTrue(legacy.digestCursors().isEmpty());
    assertEquals(legacy, SoulTypes.SoulMind.empty());

    SoulTypes.ConversationRecord rec = new SoulTypes.ConversationRecord(UUID.randomUUID(), 0L, 0L,
            SoulTypes.TurnKind.HEARD, "hi", Instant.EPOCH, "", "", null, null);
    assertTrue(rec.participants().isEmpty());

    SoulTypes.PlayerMemory pm = new SoulTypes.PlayerMemory(UUID.randomUUID(), 3, "  Roti hates the Nether ", 10, -1, null);
    assertEquals("Roti hates the Nether", pm.fact());
    assertTrue(pm.sourceCorrelationIds().isEmpty());
}
```

- [ ] **Step 2: Run** `./gradlew test --tests 'net.wcfcarolina13.GameAI.souls.SoulFoundationTest'` → compile FAIL (no `PlayerMemory`).

- [ ] **Step 3: Implement.** In `SoulTypes`:

```java
/** One thing the bot remembers a player SAYING (spec 2026-09-04 §3). Claims, never world truth. */
public record PlayerMemory(UUID playerId, int day, String fact, int salience, int lastRecalledDay,
                           List<UUID> sourceCorrelationIds) {
    public PlayerMemory {
        Objects.requireNonNull(playerId, "playerId");
        fact = fact == null ? "" : fact.trim();
        sourceCorrelationIds = sourceCorrelationIds == null ? List.of() : List.copyOf(sourceCorrelationIds);
    }
}
```
`ConversationRecord`: add trailing `List<UUID> participants`; compact ctor `participants = participants == null ? List.of() : List.copyOf(participants);`; add compat ctor with the old 10 params delegating `this(…, null)`.
`SoulMind`: add the three trailing fields; compact ctor defaults `playerMemories`/`archivedPlayerMemories` to `List.of()`/`List.copyOf`, `digestCursors` to `Map.of()`/`Map.copyOf`; add 8-arg compat ctor delegating with `List.of(), List.of(), Map.of()`; `empty()` unchanged in meaning (still 8-arg call is fine).
`SoulMindOps.rebuild`: pass `mind.playerMemories(), mind.archivedPlayerMemories(), mind.digestCursors()` through; add:

```java
static SoulTypes.SoulMind withPlayerMemories(SoulTypes.SoulMind m, List<SoulTypes.PlayerMemory> pm) {
    return new SoulTypes.SoulMind(m.schemaVersion(), m.playerStance(), m.threads(), m.memories(), m.seen(),
            m.lastConsolidatedAtMs(), m.lastDay(), m.lastTaskTrustDay(), pm, m.archivedPlayerMemories(), m.digestCursors());
}
static SoulTypes.SoulMind withArchivedPlayerMemories(SoulTypes.SoulMind m, List<SoulTypes.PlayerMemory> a) { /* same shape */ }
static SoulTypes.SoulMind withDigestCursors(SoulTypes.SoulMind m, Map<String, SoulTypes.ConversationCursor> c) { /* same shape */ }
```

- [ ] **Step 4: Run** `./gradlew build` → green (all 7 existing `new SoulTypes.SoulMind(` call sites still compile via the compat ctor).
- [ ] **Step 5: Commit** `souls: PlayerMemory type; SoulMind gains playerMemories/archived/digestCursors; ConversationRecord.participants (compat ctors)`

---

### Task 2: `SoulMemoryDigestOps` — pure rules + tests

**Files:**
- Create: `SoulMemoryDigestOps.java`
- Test: `SoulMemoryDigestOpsTest.java`

**Interfaces (all `static`, package-private, no Minecraft imports):**
```java
record Material(List<SoulTypes.ConversationRecord> records, String text, int playerLines, SoulTypes.ConversationCursor next)
static Material gather(List<SoulTypes.ConversationRecord> sinceRecords, SoulTypes.ConversationCursor from,
                       UUID botId, String botName, String playerName, boolean party)
static List<String> validate(String raw, String playerName)
static List<SoulTypes.PlayerMemory> merge(List<SoulTypes.PlayerMemory> existing, UUID playerId,
                                          List<String> facts, int day, List<UUID> sources)
static SoulTypes.SoulMind decay(SoulTypes.SoulMind mind)
static String factKey(String fact)                       // "said:" + 8 hex of normalized fact hash
static SoulTypes.SoulMind noteRecalled(SoulTypes.SoulMind mind, String factKey, int day)
static List<SoulBanterSeed.Anchor> anchors(SoulTypes.SoulMind mind, UUID playerId, String playerName, int currentDay, RandomGenerator random)
static List<String> aboutLines(SoulTypes.SoulMind mind, UUID playerId)   // ≤5 lines "- fact", ≤300 chars total
static SoulTypes.SoulMind archiveFor(SoulTypes.SoulMind mind, UUID playerId)
static SoulTypes.ConversationCursor cursorFor(SoulTypes.SoulMind mind, String cursorKey) // or (0,0)
static SoulTypes.SoulMind withCursor(SoulTypes.SoulMind mind, String cursorKey, SoulTypes.ConversationCursor c)
```

Rules to implement (from spec §5–§7):
- `gather`: drop FAILURE; drop HEARD whose content starts with `SoulGroupPromptAssembler.BANTER_HEARD_PREFIX`; when `party` group by `correlationId` and keep a scene only if its HEARD record's `participants` contains `botId` **or** any SPOKEN in the scene has content starting with `botName + ": "`; keep records in file order; take the newest `MAX_RECORDS`; render lines: party records are already tagged → use content as is; DIRECT: HEARD → `playerName + ": " + content`, SPOKEN → `botName + ": " + content`; then trim from the front until ≤ `MAX_MATERIAL_CHARS`; `playerLines` = number of kept HEARD records; `next` = `new ConversationCursor(epochOfKept, maxSequence + 1)` or `from` if nothing kept.
- `validate`: `SoulResponseValidator.sanitizeBase(raw)`, split on `\n`, trim, drop blanks; if > `RUNAWAY_LINES` → `List.of()`; if the only line equalsIgnoreCase `- none` → `List.of()`; keep line if startsWith `"- "`, body = substring(2).trim(), body length 1..`MAX_FACT_CHARS`, no `§`, no char < 0x20, and (`body.toLowerCase().contains(playerName.toLowerCase())` or word-boundary `\bthey\b`); cap `MAX_FACTS`.
- `merge`: tokens = lowercase, strip `[^a-z0-9 ]`, split whitespace, drop stop-words {a,an,the,and,or,to,of,in,on,is,are,was,were,be,it,that,this,they,their,them} and the player name; Jaccard ≥ `DUP_JACCARD` against an existing memory of the same `playerId` → replace it with salience `min(MAX_SALIENCE, s + DUP_BUMP)` and sources appended (deduped); otherwise append new `PlayerMemory(playerId, day, fact, INITIAL_SALIENCE, -1, sources)`; finally, for that player, if count > `MAX_PER_PLAYER` drop lowest salience then oldest `day` first. Other players' memories untouched.
- `decay`: every memory salience −1; drop ≤ 0.
- `noteRecalled`: memory with `factKey(fact).equals(factKey)` and `lastRecalledDay != day` → salience `min(MAX, s + RECALL_BUMP)`, `lastRecalledDay = day`.
- `anchors`: eligible = that player's memories with `lastRecalledDay < 0 || currentDay - lastRecalledDay >= SoulMindOps.RECALL_COOLDOWN_DAYS`; if none → empty; sort salience desc; pick first; one anchor: `new SoulBanterSeed.Anchor(SoulMindOps.MEMORY_TOPIC_PREFIX + factKey(fact), playerName + " once said: " + fact, SoulMindOps.MEMORY_ANCHOR_WEIGHT)`.
- `aboutLines`: that player's memories sorted salience desc then day desc, take `MAX_ABOUT_LINES`, lines `"- " + fact`, stop adding when total chars would exceed `MAX_ABOUT_CHARS`.
- `archiveFor`: move that player's memories to the front of `archivedPlayerMemories` (cap `MAX_ARCHIVED`, keep newest), remove them from `playerMemories`, remove `digestCursors` entries whose key ends with `":" + playerId`.

- [ ] **Step 1: Write the failing tests** (`SoulMemoryDigestOpsTest`, one `@Test` per bullet):

```java
package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.random.RandomGenerator;
import static org.junit.jupiter.api.Assertions.*;

class SoulMemoryDigestOpsTest {
    private static final UUID BOT = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();
    private static long seq = 0;

    private static SoulTypes.ConversationRecord rec(SoulTypes.TurnKind kind, String content, UUID corr, List<UUID> parts) {
        return new SoulTypes.ConversationRecord(corr, 1L, seq++, kind, content, Instant.EPOCH, "", "", null, null, parts);
    }

    @Test void gatherDirectRendersTagsAndDropsFailuresAndNarrator() {
        UUID c = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> r = List.of(
                rec(SoulTypes.TurnKind.HEARD, "I hate the Nether", c, null),
                rec(SoulTypes.TurnKind.SPOKEN, "Noted.", c, null),
                rec(SoulTypes.TurnKind.FAILURE, "", c, null),
                rec(SoulTypes.TurnKind.HEARD, SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + "seed", c, null));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", false);
        assertEquals("Roti: I hate the Nether\nJake: Noted.", m.text());
        assertEquals(1, m.playerLines());
        assertEquals(new SoulTypes.ConversationCursor(1L, r.get(3).sequence() + 1), m.next());
    }

    @Test void gatherPartyKeepsOnlyScenesTheBotWasIn() {
        UUID in = UUID.randomUUID(), out = UUID.randomUUID(), legacy = UUID.randomUUID();
        List<SoulTypes.ConversationRecord> r = List.of(
                rec(SoulTypes.TurnKind.HEARD, "Roti: hello all", in, List.of(BOT)),
                rec(SoulTypes.TurnKind.SPOKEN, "Bob: hey", in, null),
                rec(SoulTypes.TurnKind.HEARD, "Roti: not you", out, List.of(UUID.randomUUID())),
                rec(SoulTypes.TurnKind.HEARD, "Roti: old scene", legacy, null),
                rec(SoulTypes.TurnKind.SPOKEN, "Jake: I was here", legacy, null));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", true);
        assertFalse(m.text().contains("not you"));
        assertTrue(m.text().contains("hello all"));
        assertTrue(m.text().contains("old scene"));
        assertEquals(2, m.playerLines());
    }

    @Test void gatherCapsRecordsAndCharsNewestFirstAndKeepsCursorWhenEmpty() {
        List<SoulTypes.ConversationRecord> r = new ArrayList<>();
        for (int i = 0; i < 60; i++) r.add(rec(SoulTypes.TurnKind.HEARD, "line " + i + " " + "x".repeat(80), UUID.randomUUID(), null));
        SoulMemoryDigestOps.Material m = SoulMemoryDigestOps.gather(r, new SoulTypes.ConversationCursor(1L, 0L), BOT, "Jake", "Roti", false);
        assertTrue(m.text().length() <= SoulMemoryDigestOps.MAX_MATERIAL_CHARS);
        assertTrue(m.text().contains("line 59"));
        assertFalse(m.text().contains("line 0 "));
        SoulTypes.ConversationCursor from = new SoulTypes.ConversationCursor(1L, 7L);
        assertEquals(from, SoulMemoryDigestOps.gather(List.of(), from, BOT, "Jake", "Roti", false).next());
    }

    @Test void validateAcceptsWellFormedLinesOnly() {
        String raw = "- Roti hates the Nether\n- they want to build a farm\n" +
                "not a fact\n- §cbad\n- " + "x".repeat(120) + "\n- nothing about anyone\n- Roti likes cats\n- Roti named the base Home\n- Roti fears creepers";
        List<String> facts = SoulMemoryDigestOps.validate(raw, "Roti");
        assertEquals(List.of("Roti hates the Nether", "they want to build a farm", "Roti likes cats", "Roti named the base Home", "Roti fears creepers"), facts);
        assertTrue(SoulMemoryDigestOps.validate("- none", "Roti").isEmpty());
        assertTrue(SoulMemoryDigestOps.validate("", "Roti").isEmpty());
        StringBuilder runaway = new StringBuilder();
        for (int i = 0; i < 9; i++) runaway.append("- Roti fact ").append(i).append('\n');
        assertTrue(SoulMemoryDigestOps.validate(runaway.toString(), "Roti").isEmpty());
    }

    @Test void mergeDedupesBumpsAndCaps() {
        UUID other = UUID.randomUUID();
        List<SoulTypes.PlayerMemory> existing = new ArrayList<>(List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti hates the Nether", 5, -1, List.of()),
                new SoulTypes.PlayerMemory(other, 1, "Sam likes cats", 5, -1, List.of())));
        UUID src = UUID.randomUUID();
        List<SoulTypes.PlayerMemory> merged = SoulMemoryDigestOps.merge(existing, PLAYER,
                List.of("Roti really hates the Nether", "Roti wants a farm"), 4, List.of(src));
        SoulTypes.PlayerMemory bumped = merged.stream().filter(m -> m.fact().equals("Roti hates the Nether")).findFirst().orElseThrow();
        assertEquals(7, bumped.salience());
        assertEquals(List.of(src), bumped.sourceCorrelationIds());
        assertTrue(merged.stream().anyMatch(m -> m.fact().equals("Roti wants a farm") && m.salience() == 10 && m.day() == 4));
        assertTrue(merged.stream().anyMatch(m -> m.playerId().equals(other)));

        List<SoulTypes.PlayerMemory> many = new ArrayList<>();
        for (int i = 0; i < SoulMemoryDigestOps.MAX_PER_PLAYER; i++)
            many.add(new SoulTypes.PlayerMemory(PLAYER, i, "Roti fact number " + i + " alpha" + i, i + 1, -1, List.of()));
        List<SoulTypes.PlayerMemory> capped = SoulMemoryDigestOps.merge(many, PLAYER, List.of("Roti brand new zeta"), 30, List.of());
        assertEquals(SoulMemoryDigestOps.MAX_PER_PLAYER, capped.size());
        assertFalse(capped.stream().anyMatch(m -> m.fact().endsWith("alpha0")));
    }

    @Test void decayRecallAnchorsAboutAndArchive() {
        SoulTypes.SoulMind mind = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
                new SoulTypes.PlayerMemory(PLAYER, 1, "Roti hates the Nether", 1, -1, List.of()),
                new SoulTypes.PlayerMemory(PLAYER, 2, "Roti wants a farm", 9, -1, List.of())));
        SoulTypes.SoulMind decayed = SoulMemoryDigestOps.decay(mind);
        assertEquals(1, decayed.playerMemories().size());
        assertEquals(8, decayed.playerMemories().get(0).salience());

        String key = SoulMemoryDigestOps.factKey("Roti wants a farm");
        assertTrue(key.startsWith("said:"));
        SoulTypes.SoulMind recalled = SoulMemoryDigestOps.noteRecalled(decayed, key, 5);
        assertEquals(10, recalled.playerMemories().get(0).salience());
        assertEquals(5, recalled.playerMemories().get(0).lastRecalledDay());

        RandomGenerator rnd = new Random(1);
        assertTrue(SoulMemoryDigestOps.anchors(recalled, PLAYER, "Roti", 6, rnd).isEmpty()); // cooldown
        List<SoulBanterSeed.Anchor> a = SoulMemoryDigestOps.anchors(recalled, PLAYER, "Roti", 9, rnd);
        assertEquals(1, a.size());
        assertEquals(SoulMindOps.MEMORY_TOPIC_PREFIX + key, a.get(0).topic());
        assertEquals("Roti once said: Roti wants a farm", a.get(0).phrase());
        assertEquals(SoulMindOps.MEMORY_ANCHOR_WEIGHT, a.get(0).weight());

        assertEquals(List.of("- Roti wants a farm"), SoulMemoryDigestOps.aboutLines(recalled, PLAYER));
        assertTrue(SoulMemoryDigestOps.aboutLines(recalled, UUID.randomUUID()).isEmpty());

        SoulTypes.SoulMind withCursor = SoulMemoryDigestOps.withCursor(recalled, "DIRECT:" + PLAYER, new SoulTypes.ConversationCursor(1L, 9L));
        assertEquals(new SoulTypes.ConversationCursor(1L, 9L), SoulMemoryDigestOps.cursorFor(withCursor, "DIRECT:" + PLAYER));
        assertEquals(new SoulTypes.ConversationCursor(0L, 0L), SoulMemoryDigestOps.cursorFor(withCursor, "PARTY:" + PLAYER));
        SoulTypes.SoulMind archived = SoulMemoryDigestOps.archiveFor(withCursor, PLAYER);
        assertTrue(archived.playerMemories().isEmpty());
        assertEquals(1, archived.archivedPlayerMemories().size());
        assertTrue(archived.digestCursors().isEmpty());
    }
}
```

- [ ] **Step 2: Run** the test class → compile FAIL.
- [ ] **Step 3: Implement `SoulMemoryDigestOps`** per the rules above (final class, private ctor, constants `static final`). `factKey`: `"said:" + String.format("%08x", normalize(fact).hashCode())` where `normalize` = lowercase, `[^a-z0-9 ]` stripped, whitespace collapsed.
- [ ] **Step 4: Run** the test class → PASS; `./gradlew build` → green.
- [ ] **Step 5: Commit** `souls: SoulMemoryDigestOps — gather/validate/merge/decay/recall/anchors/about/archive (pure, tested)`

---

### Task 3: `SoulStore` readers and the participants overload

**Files:**
- Modify: `SoulStore.java` (`beginHeardTurn` ~L173; path helpers ~L795; add readers near `eventsSince` ~L619)
- Test: `SoulStoreTest.java` (append)

**Interfaces (Produces):**
```java
public CompletableFuture<SoulTypes.TurnToken> beginHeardTurn(SoulTypes.ConversationKey key, UUID correlationId,
        String content, Instant occurredAt, List<UUID> participants)   // existing 4-arg delegates with List.of()
public CompletableFuture<List<UUID>> botDirectories()                  // root subdirs whose name parses as UUID
public CompletableFuture<List<UUID>> conversationPlayers(UUID botId)   // conversations/<uuid>/active.jsonl present
public CompletableFuture<List<SoulTypes.ConversationRecord>> recordsSince(SoulTypes.ConversationKey key,
        SoulTypes.ConversationCursor cursor)
```
`recordsSince`: `all = loadTranscript(activeFile(key.botId(), key.playerId()), ConversationRecord.class, cursorKey(key))`; `currentEpoch = max epoch in all` (empty → `List.of()`); keep `epoch == currentEpoch`; if `cursor.epoch() == currentEpoch` also require `sequence >= cursor.nextSequence()`; file order preserved.

- [ ] **Step 1: Write the failing tests** (append to `SoulStoreTest`; `store`, `worldRoot`, `executor` fixtures already exist; `UUID bot`, `UUID player` are created per test as in the neighbours):

```java
@Test
void heardTurnPersistsParticipantsAndLegacyRecordsReadAsEmpty() throws Exception {
    UUID bot = UUID.randomUUID(), player = UUID.randomUUID(), other = UUID.randomUUID();
    SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(bot, player, SoulTypes.Channel.PARTY);
    store.beginHeardTurn(key, UUID.randomUUID(), "Roti: hi", Instant.EPOCH, List.of(bot, other)).get();
    store.beginHeardTurn(key, UUID.randomUUID(), "Roti: again", Instant.EPOCH).get();
    List<SoulTypes.ConversationRecord> recs = store.recordsSince(key, new SoulTypes.ConversationCursor(0L, 0L)).get();
    assertEquals(2, recs.size());
    assertEquals(List.of(bot, other), recs.get(0).participants());
    assertTrue(recs.get(1).participants().isEmpty());
}

@Test
void recordsSinceHonoursCursorAndEpochChange() throws Exception {
    UUID bot = UUID.randomUUID(), player = UUID.randomUUID();
    SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(bot, player, SoulTypes.Channel.DIRECT);
    for (int i = 0; i < 3; i++) store.beginHeardTurn(key, UUID.randomUUID(), "m" + i, Instant.EPOCH).get();
    assertEquals(1, store.recordsSince(key, new SoulTypes.ConversationCursor(0L, 2L)).get().size());
    long newEpoch = store.archiveAndReset(key).get();
    store.beginHeardTurn(key, UUID.randomUUID(), "fresh", Instant.EPOCH).get();
    List<SoulTypes.ConversationRecord> after = store.recordsSince(key, new SoulTypes.ConversationCursor(0L, 2L)).get();
    assertEquals(1, after.size());
    assertEquals(newEpoch, after.get(0).epoch());
    assertEquals("fresh", after.get(0).content());
}

@Test
void listsBotDirectoriesAndConversationPlayers() throws Exception {
    UUID bot = UUID.randomUUID(), p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
    store.beginHeardTurn(new SoulTypes.ConversationKey(bot, p1, SoulTypes.Channel.DIRECT), UUID.randomUUID(), "a", Instant.EPOCH).get();
    store.beginHeardTurn(new SoulTypes.ConversationKey(bot, p2, SoulTypes.Channel.DIRECT), UUID.randomUUID(), "b", Instant.EPOCH).get();
    assertEquals(Set.of(p1, p2), new HashSet<>(store.conversationPlayers(bot).get()));
    assertTrue(store.botDirectories().get().contains(bot));
    assertTrue(store.conversationPlayers(UUID.randomUUID()).get().isEmpty());
}

@Test
void mindJsonWithoutDigestFieldsLoadsWithEmpties() throws Exception {
    UUID bot = UUID.randomUUID();
    Path dir = worldRoot.resolve("frens/souls/v1").resolve(bot.toString());
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("mind.json"), "{\"schemaVersion\":1,\"playerStance\":{\"trust\":3,\"exasperation\":0,\"curiosity\":3},"
            + "\"threads\":[],\"memories\":[],\"seen\":[],\"lastConsolidatedAtMs\":0,\"lastDay\":-1,\"lastTaskTrustDay\":-1}");
    SoulTypes.SoulMind mind = store.mind(bot).get();
    assertTrue(mind.playerMemories().isEmpty());
    assertTrue(mind.digestCursors().isEmpty());
    SoulTypes.SoulMind saved = store.updateMind(bot, m -> SoulMemoryDigestOps.withCursor(m, "DIRECT:x", new SoulTypes.ConversationCursor(2L, 5L))).get();
    assertEquals(new SoulTypes.ConversationCursor(2L, 5L), saved.digestCursors().get("DIRECT:x"));
}
```
(Check the test's existing `worldRoot` is the raw world root passed to `new SoulStore(worldRoot, executor)` — the public ctor appends `frens/souls/v1`; adjust the `mind.json` path if the fixture differs.)

- [ ] **Step 2: Run** `SoulStoreTest` → compile FAIL.
- [ ] **Step 3: Implement.** `beginHeardTurn(…, participants)`: identical body, constructing the record with the 11-arg ctor; old signature delegates. `botDirectories`: `submit(() -> Files.isDirectory(root) ? list(root) dirs → try UUID.fromString(name) catch skip)`. `conversationPlayers(botId)`: list `conversationsDir(botId)` subdirs where `resolve("active.jsonl")` exists. `recordsSince` as specified.
- [ ] **Step 4: Run** `./gradlew build` → green.
- [ ] **Step 5: Commit** `souls: SoulStore — participants on HEARD records, botDirectories/conversationPlayers/recordsSince readers`

---

### Task 4: `SoulMemoryDigestService`

**Files:**
- Create: `SoulMemoryDigestService.java`
- Test: `SoulMemoryDigestServiceTest.java`

**Interfaces:**
- Consumes: Task 2 ops, Task 3 readers, `SoulGenerationScheduler.submit(key, epoch, supplier)`, `SoulModelProvider.generate`, `SoulTypes.ProviderRequest(correlationId, model, messages, timeout, maxOutputTokens)`.
- Produces:
```java
public final class SoulMemoryDigestService {
    public SoulMemoryDigestService(SoulStore store, SoulStore partyStore, SoulGenerationScheduler scheduler,
            SoulModelProvider provider, String model, Duration timeout, BooleanSupplier enabled)
    /** Runs off the server thread. Completes when every (bot, player) digest has finished (success or not). */
    public CompletableFuture<Void> digest(UUID botId, String botName, int day, Map<UUID, String> playerNames)
    static SoulTypes.ProviderRequest buildRequest(UUID correlationId, String model, String botName, String playerName, String material, Duration timeout)
}
```

Behaviour of `digest`:
1. If `!enabled.getAsBoolean()` → completed future, log nothing.
2. `store.mind(botId)` → for DIRECT: `store.conversationPlayers(botId)` → for each player with a name in `playerNames` (unknown → `"someone"` is NOT used; skip players not online — their name is unknown, and the prompt needs it): key `(botId, player, DIRECT)`, cursorKey `SoulStore.cursorKey(key)`, `store.recordsSince(key, cursorFor(mind, cursorKey))` → `gather(records, cursor, botId, botName, playerName, false)`.
3. For party: `partyStore.botDirectories()` → for each owner with a known name: key `SoulGroupTypes.partyKey(owner)`, cursorKey `SoulStore.cursorKey(key)`, `partyStore.recordsSince(...)` → `gather(..., party=true)`.
4. Per material: if `playerLines < MIN_PLAYER_LINES` → skip, cursor unchanged, outcome `too-few`. Else `scheduler.submit(new ConversationKey(botId, playerId, Channel.SYSTEM), 0L, () -> provider.generate(buildRequest(...)))` → on completion: `facts = result.success() ? validate(result.text(), playerName) : List.of()`; `store.updateMind(botId, m -> withCursor(withPlayerMemories(m, merge(m.playerMemories(), playerId, facts, day, sources)), cursorKey, material.next()))`; sources = distinct `correlationId`s of the gathered HEARD records. Log `LOGGER.info("[souls] memory digest bot={} player={} channel={} outcome={} kept={} lines={}", …)` with outcome ∈ {`ok`, `none`, `provider-failed:<code>`, `too-few`, `disabled`}.
5. Chain sequentially per bot (compose one after another) so two digests never race on the same `mind.json`.

`buildRequest`: two messages — SYSTEM with the clerk contract verbatim from spec §6 (substituting names), USER = material; `maxOutputTokens` 200.

- [ ] **Step 1: Write the failing test** (copy the `FakeProvider` from `SoulConversationServiceTest` into this test class; use `@TempDir` + `new SoulStore(worldRoot, executor)` and a second store `SoulStore.exactRoot(...)`-style for party if the exact-root factory exists, else reuse the same store for both as the runtime test seam does):

```java
@Test
void digestWritesMemoriesAndAdvancesCursor() throws Exception {
    UUID bot = UUID.randomUUID(), player = UUID.randomUUID();
    SoulTypes.ConversationKey key = new SoulTypes.ConversationKey(bot, player, SoulTypes.Channel.DIRECT);
    for (String line : List.of("I hate the Nether", "I want to build a farm", "call the base Home", "creepers scare me"))
        store.beginHeardTurn(key, UUID.randomUUID(), line, Instant.EPOCH).get();
    provider.enqueue(CompletableFuture.completedFuture(new SoulTypes.ProviderResult(true,
            "- Roti hates the Nether\n- Roti wants to build a farm\n- none of this\n", null, "test", "m", 5L, null, null, null)));
    service.digest(bot, "Jake", 3, Map.of(player, "Roti")).get();
    SoulTypes.SoulMind mind = store.mind(bot).get();
    assertEquals(2, mind.playerMemories().size());
    assertEquals(new SoulTypes.ConversationCursor(0L, 4L), mind.digestCursors().get("DIRECT:" + player));
    assertEquals(1, provider.requests().size());
    assertTrue(provider.requests().get(0).messages().get(1).content().contains("Roti: I hate the Nether"));
}

@Test
void providerFailureAdvancesCursorWithoutMemories() throws Exception { /* enqueue failed ProviderResult(false, "", FailureCode.TIMEOUT, ...) after 4 HEARD lines; assert 0 memories and cursor (0,4) */ }

@Test
void tooFewLinesDoesNotCallProviderOrMoveCursor() throws Exception { /* 3 HEARD lines; assert provider.requests() empty and no cursor entry */ }

@Test
void disabledIsNoOp() throws Exception { /* enabled = () -> false; 4 lines; assert no request, no cursor */ }
```
(Write the three sketched tests out in full — same fixture as the first; each ends with concrete `assertEquals`/`assertTrue` calls as shown.)

- [ ] **Step 2: Run** → compile FAIL.
- [ ] **Step 3: Implement** the service as specified. Use `CompletableFuture` composition only; no blocking `get()` inside the service.
- [ ] **Step 4: Run** `./gradlew build` → green.
- [ ] **Step 5: Commit** `souls: SoulMemoryDigestService — per (bot, player) digest through the scheduler; cursor advances on every outcome`

---

### Task 5: Runtime wiring — pipeline, `onNewDay`, reset, party roster, decay, recall dispatch

**Files:**
- Modify: `SoulRuntime.java` (`Pipeline` record ~L83; test-seam ctor ~L142; `buildPipeline` ~L889; `onNewDay` ~L770; `reset` ~L1111)
- Modify: `SoulGroupConversationService.java` (~L99 `beginHeardTurn`)
- Modify: `SoulMindOps.java` (`consolidate` decay block ~L235; `noteRecalled` ~L145)
- Test: `SoulMindOpsTest.java` (append), `SoulRuntimeTest.java` (only if the test-seam ctor signature changes — it should not)

- [ ] **Step 1: Failing test** (append to `SoulMindOpsTest`):
```java
@Test
void consolidateDecaysPlayerMemoriesAndNoteRecalledDispatchesSaidKeys() {
    UUID p = UUID.randomUUID();
    SoulTypes.SoulMind m = SoulMindOps.withPlayerMemories(SoulTypes.SoulMind.empty(), List.of(
            new SoulTypes.PlayerMemory(p, 1, "Roti wants a farm", 2, -1, List.of())));
    SoulTypes.SoulMind c = SoulMindOps.consolidate(m, List.of(), 5, "plains", id -> "Roti", 1_000L);
    assertEquals(1, c.playerMemories().get(0).salience());
    String key = SoulMemoryDigestOps.factKey("Roti wants a farm");
    SoulTypes.SoulMind r = SoulMindOps.noteRecalled(c, key, 5);
    assertEquals(4, r.playerMemories().get(0).salience());
    assertEquals(5, r.playerMemories().get(0).lastRecalledDay());
}
```
- [ ] **Step 2: Run** → FAIL (salience unchanged).
- [ ] **Step 3: Implement.**
  - `SoulMindOps.consolidate`: after building the decayed `memories` list and before returning, apply `SoulMemoryDigestOps.decay` to the result (i.e. wrap the final `rebuild(...)`/`with…` result: `return SoulMemoryDigestOps.decay(result);`).
  - `SoulMindOps.noteRecalled`: at the top, `if (topic.startsWith("said:")) return SoulMemoryDigestOps.noteRecalled(mind, topic, day);`.
  - `Pipeline` record: add trailing `SoulMemoryDigestService digest` (nullable). Test-seam ctor passes `null`. `buildPipeline`: after the provider/scheduler are created, `new SoulMemoryDigestService(store, partyStore, scheduler, provider, settings.model(), settings.timeout(), () -> { ManualConfig cfg = Frens.CONFIG; return cfg == null || cfg.isSoulMemoryDigestEnabled(); })` — **Task 7 adds the config getter; until then use `() -> true` and replace in Task 7**.
  - `onNewDay`: capture `String botName` from `srv.getPlayerManager().getPlayer(botId)` (fallback `"the bot"`); extend the chain: `.thenCompose(mind -> { SoulMemoryDigestService d = pipelineRef.get().digest(); return d == null ? CompletableFuture.completedFuture(mind) : d.digest(botId, botName, day, names).thenApply(v -> mind); })` placed after `trimEvents`, before the `thenAccept` log.
  - `reset(key)`: in the `whenComplete` success branch, additionally `store.updateMind(key.botId(), m -> SoulMemoryDigestOps.archiveFor(m, key.playerId()))` (fire-and-forget with an `exceptionally` warn).
  - `SoulGroupConversationService` ~L99: `partyStore.beginHeardTurn(turn.key(), correlationId, taggedMessage, turn.acceptedAt(), turn.roster().stream().map(SoulGroupTypes.SceneParticipant::botId).toList())`.
- [ ] **Step 4: Run** `./gradlew build` → green.
- [ ] **Step 5: Commit** `souls: wire memory digest into onNewDay; reset archives player memories; party HEARD records carry the roster; decay + recall dispatch`

---

### Task 6: Injection — DM prompt, group prompt, banter anchors

**Files:**
- Modify: `SoulPromptAssembler.java` (`assemble` overloads ~L60–90)
- Modify: `SoulConversationService.java` (`dispatchProvider` ~L199–232)
- Modify: `SoulGroupPromptAssembler.java` (`assemble` ~L62; add `aboutBlock` next to `threadsBlock` ~L268)
- Modify: `SoulBanterDirector.java` (~L285–289 `mindAnchors` loop)
- Test: `SoulPromptAssemblerTest.java`, `SoulGroupPromptAssemblerTest.java` (append)

**Interfaces:**
- `SoulPromptAssembler.assemble(correlationId, model, profile, grounding, priorHistory, recentEvents, relevantKnowledge, aboutPlayer /*List<String>*/, currentMessage, timeout)`; the existing 9-arg overload delegates with `List.of()`.
- `SoulGroupPromptAssembler`: private `Optional<SoulTypes.Message> aboutBlock(GroupSceneTurn turn)`; message text `"ABOUT " + owner + " (things " + owner + " said, as remembered)\n"` then per roster bot with lines: `"<Bot> remembers:\n- fact\n…"`; skip bots with none; empty → `Optional.empty()`; inserted right after `threadsBlock`.

- [ ] **Step 1: Failing tests.** In `SoulPromptAssemblerTest` (mirror an existing assemble test's fixture for profile/grounding):
```java
@Test
void aboutPlayerBlockAppearsAfterStateWhenPresent() {
    SoulTypes.ProviderRequest req = assembler.assemble(UUID.randomUUID(), "m", profile, grounding, List.of(), List.of(),
            List.of(), List.of("- Roti hates the Nether"), "hi", Duration.ofSeconds(5));
    int state = indexOfContaining(req.messages(), "CURRENT STATE"); // or whatever the existing authoritative-state header is
    int about = indexOfContaining(req.messages(), "ABOUT");
    assertTrue(about > state);
    assertTrue(req.messages().get(about).content().contains("Roti hates the Nether"));
    SoulTypes.ProviderRequest none = assembler.assemble(UUID.randomUUID(), "m", profile, grounding, List.of(), List.of(), List.of(), List.of(), "hi", Duration.ofSeconds(5));
    assertEquals(-1, indexOfContaining(none.messages(), "ABOUT"));
}
```
In `SoulGroupPromptAssemblerTest`, build the assembler with a `mindLookup` returning a mind that has one `PlayerMemory` for the turn's `ownerId`, assemble a PLAYER turn, and assert a message containing `"ABOUT " + ownerName` and `"remembers:"` and the fact; assert absence when the lookup returns `Optional.empty()`. (Read the existing test's turn/roster fixture helper and reuse it.)
- [ ] **Step 2: Run** both classes → compile FAIL.
- [ ] **Step 3: Implement.**
  - DM assembler: new overload; after `authoritativeState(grounding)` add `if (!aboutPlayer.isEmpty()) messages.add(new SoulTypes.Message(SoulTypes.Role.SYSTEM, "ABOUT " + playerName + " (things they said, as remembered — not facts about the world)\n" + String.join("\n", aboutPlayer)));` where `playerName` = `grounding.player().map(SoulTypes.PlayerSnapshot::name).orElse("the player")` (check the snapshot's name accessor; fall back to the turn's display name if the snapshot lacks one).
  - `SoulConversationService.dispatchProvider`: `List<String> about = store.cachedMind(turn.key().botId()).map(m -> SoulMemoryDigestOps.aboutLines(m, turn.key().playerId())).orElse(List.of());` and pass it to the new overload.
  - Group assembler: `aboutBlock` as specified, using `SoulMemoryDigestOps.aboutLines(mind, turn.ownerId())` per participant.
  - Banter director: inside the `mindAnchors` loop add `mindAnchors.addAll(SoulMemoryDigestOps.anchors(minds.get(i), player.getUuid(), player.getName().getString(), currentDay, random));`.
- [ ] **Step 4: Run** `./gradlew build` → green.
- [ ] **Step 5: Commit** `souls: ABOUT <player> block in DM and group prompts; player-memory banter anchors`

---

### Task 7: Config toggle, commands, changelog, version

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java` (next to `soulBanterEnabled` ~L123 / getters ~L936)
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java` (tree ~L72–130; handlers near `executeBanterToggle`)
- Modify: `SoulRuntime.java` (`buildPipeline` enabled supplier → `cfg.isSoulMemoryDigestEnabled()`)
- Modify: `changelog.md`, `gradle.properties`, `RALPH_TASK.md`

- [ ] **Step 1: Config.** `private boolean soulMemoryDigestEnabled = true;` + `isSoulMemoryDigestEnabled()` / `setSoulMemoryDigestEnabled(boolean)`; replace the `() -> true` placeholder in `buildPipeline`.
- [ ] **Step 2: Commands.** Add to the `soul` tree:
```java
.then(CommandManager.literal("memory")
        .then(CommandManager.argument("bot", EntityArgumentType.player())
                .executes(context -> executeMemory(context, EntityArgumentType.getPlayer(context, "bot")))))
.then(CommandManager.literal("digest")
        .then(CommandManager.literal("on").executes(ctx -> executeDigestToggle(ctx, true)))
        .then(CommandManager.literal("off").executes(ctx -> executeDigestToggle(ctx, false)))
        .then(CommandManager.literal("status").executes(BotSoulCommands::executeDigestStatus)))
```
(Use the same argument type/getter the existing `reset <bot>` branch at ~L98–102 uses.) `executeMemory`: same guards as `executeReset` (player actor, registered bot, `isPrivateSoulAuthorized`, runtime present); then `runtime.cachedMind(bot.getUuid())` (add a public pass-through to `store.cachedMind` on `SoulRuntime` if none exists) → memories for `actor.getUuid()` sorted day desc → send one line per memory `"day " + day + " · " + salience + " · " + fact` via `ChatUtils.sendSystemMessage`, or `"<Bot> doesn't remember anything about you yet."`. `executeDigestToggle`: operator-gated like `executeBanterToggle`, sets+saves config, message `"Memory digest set to on/off."`. `executeDigestStatus`: prints `"Memory digest is ON/OFF. Runs at each Minecraft day rollover for soul-bound bots."`.
- [ ] **Step 3: Build** `./gradlew build` → green.
- [ ] **Step 4: Changelog + version.** `gradle.properties` → `mod_version=1.1.201-release+1.21.11`. Prepend to `changelog.md` (after the header, before the 1.1.200 entry) an entry titled `## Soul memory digest — bots remember what you said; 1.1.201 (2026-09-04)` covering: the spec path, the plan path, what each task added (one bullet each), the constants, the commands, the toggle, test count before/after, and the field checklist from spec §10. In `RALPH_TASK.md` Lane 3: check off CONSOLIDATION with `✅ 2026-09-04 (1.1.201) — phase 1 shipped as the memory digest; field-test pending`, and add the field checklist under Lane 1.
- [ ] **Step 5: Commit** `release: 1.1.201 — soul memory digest (consolidation phase 1); commands, toggle, changelog`
