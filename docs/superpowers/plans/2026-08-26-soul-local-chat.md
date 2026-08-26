# Frens Soul Ambient / Local Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a soul-bound companion overhear unaddressed chat spoken near it and, rarely, chime in with one short line delivered to everyone in earshot.

**Architecture:** Two units. `SoulLocalMemory` is a bounded in-memory ring of recent unaddressed lines, keyed by player, stamped with the bots that witnessed each one; it rides into prompts on `GroundingSnapshot.overheard()`. `SoulLocalDirector` is a deterministic, default-OFF gate — hard rejects, then a pure salience score, then banter's veto chain — that submits a **one-bot `LOCAL` scene** through the already-shipped PARTY pipeline, reusing its earshot fan-out, per-line ambient-mask gating, voice, and persistence unchanged.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric 0.18.4, JUnit 5 (`org.junit.jupiter`). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-26-frens-soul-local-chat-design.md`

## Global Constraints

- Build/verify command: `./gradlew build -x test` for compilation, `./gradlew test` for the suite. **Baseline is 488 tests green** — every task must leave it green and growing.
- **Never-consume invariant (spec §3):** the local path observes chat and returns. It must never return a "consumed" signal, never `return` early out of the `Frens` chat callback, and never prevent `tryHandleNearbyQuestAsk`, `BotRespawnPromptService.handleChat`, `SkillResumeService.handleChat`, `FunctionCallerV2.tryHandleConfirmation`, or `handleLegacyInlineActionFromRaw` from running.
- **No `Frens` references inside `GameAI/souls/`.** Live config reads arrive as injected `BooleanSupplier`s; the bot list as a `Function<MinecraftServer, List<ServerPlayerEntity>>`; clock as `LongSupplier`; randomness as `RandomGenerator`. This is the established lazy-lambda pattern (see `SoulBanterDirector`'s constructor).
- **Logger is `LoggerFactory.getLogger("frens.souls")`**, never `Frens.LOGGER`.
- **Message content is never logged.** Log the numeric salience score and the veto reason only.
- **Threading:** all recorder writes, director evaluation, and `SoulSnapshotBuilder.capture` calls happen on the server thread. No `Thread.sleep` and no blocking I/O on it.
- Constants that field tests will retune (`SALIENCE_THRESHOLD`, the weights, `EARSHOT_BLOCKS`, cooldown bands, `REPLY_WINDOW_MS`) live as named constants in one place per class.
- Package-private (no modifier) visibility is the default for new souls classes, matching `SoulPlayerActivity` and `SoulBanterSeed`.

---

### Task 1: `SoulLocalMemory` — the overheard-line recorder

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalMemory.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalMemoryTest.java`

**Interfaces:**
- Consumes: nothing (leaf class).
- Produces:
  - `static void note(UUID playerId, String line, Set<UUID> witnessBotIds, long atEpochMs)`
  - `static List<String> witnessedBy(UUID botId, UUID playerId, long nowEpochMs)` — oldest first
  - `static void forget(UUID playerId)`
  - `static void clear()`
  - `static final int MAX_ENTRIES_PER_PLAYER = 8`, `static final long TTL_MS = 600_000L`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalMemoryTest.java`:

```java
package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the overhear recorder's bounds: ring size, TTL, and — the point of the class —
 * that a bot only ever reads lines it actually witnessed.
 */
class SoulLocalMemoryTest {

    private final UUID player = UUID.randomUUID();
    private final UUID jake = UUID.randomUUID();
    private final UUID sara = UUID.randomUUID();

    @BeforeEach
    void reset() {
        SoulLocalMemory.clear();
    }

    @Test
    void witnessedLinesComeBackOldestFirst() {
        SoulLocalMemory.note(player, "heading to the ravine", Set.of(jake), 1_000L);
        SoulLocalMemory.note(player, "bring a bucket", Set.of(jake), 2_000L);

        assertEquals(List.of("heading to the ravine", "bring a bucket"),
                SoulLocalMemory.witnessedBy(jake, player, 3_000L));
    }

    @Test
    void nonWitnessReadsNothing() {
        SoulLocalMemory.note(player, "heading to the ravine", Set.of(jake), 1_000L);

        assertTrue(SoulLocalMemory.witnessedBy(sara, player, 2_000L).isEmpty());
    }

    @Test
    void ringIsBoundedAndDropsOldest() {
        for (int i = 0; i < SoulLocalMemory.MAX_ENTRIES_PER_PLAYER + 3; i++) {
            SoulLocalMemory.note(player, "line " + i, Set.of(jake), 1_000L + i);
        }

        List<String> read = SoulLocalMemory.witnessedBy(jake, player, 2_000L);
        assertEquals(SoulLocalMemory.MAX_ENTRIES_PER_PLAYER, read.size());
        assertEquals("line 3", read.get(0));
        assertEquals("line 10", read.get(read.size() - 1));
    }

    @Test
    void entriesOlderThanTtlAreInvisible() {
        SoulLocalMemory.note(player, "stale", Set.of(jake), 1_000L);

        assertTrue(SoulLocalMemory.witnessedBy(
                jake, player, 1_000L + SoulLocalMemory.TTL_MS + 1).isEmpty());
    }

    @Test
    void blankAndEmptyWitnessSetsAreNotRecorded() {
        SoulLocalMemory.note(player, "   ", Set.of(jake), 1_000L);
        SoulLocalMemory.note(player, "nobody heard this", Set.of(), 1_000L);

        assertTrue(SoulLocalMemory.witnessedBy(jake, player, 2_000L).isEmpty());
    }

    @Test
    void forgetDropsOnePlayerAndClearDropsAll() {
        UUID other = UUID.randomUUID();
        SoulLocalMemory.note(player, "mine", Set.of(jake), 1_000L);
        SoulLocalMemory.note(other, "theirs", Set.of(jake), 1_000L);

        SoulLocalMemory.forget(player);
        assertTrue(SoulLocalMemory.witnessedBy(jake, player, 2_000L).isEmpty());
        assertEquals(List.of("theirs"), SoulLocalMemory.witnessedBy(jake, other, 2_000L));

        SoulLocalMemory.clear();
        assertTrue(SoulLocalMemory.witnessedBy(jake, other, 2_000L).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SoulLocalMemoryTest*'`
Expected: FAIL — compilation error, `SoulLocalMemory` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalMemory.java`:

```java
package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped recorder of unaddressed chat spoken near soul-bound bots (local-chat spec §4).
 * One bounded ring per player; each entry carries the set of bots that were in earshot when the
 * line was spoken, so a bot only ever reads back what it actually witnessed.
 *
 * <p>In-memory only — nothing here is ever written to disk. The single write site (the Frens
 * public-chat callback) is gated by {@code soulLocalChatEnabled}, so while the toggle is off this
 * class holds nothing and every consumer reads an empty list.
 *
 * <p>Shaped after {@link SoulPlayerActivity}: static facade, server-thread writes, concurrent map
 * for safe reads, {@link #clear()} for shutdown and tests.
 */
final class SoulLocalMemory {

    /** Most overheard lines retained per player; the oldest is dropped past this. */
    static final int MAX_ENTRIES_PER_PLAYER = 8;
    /** Lines older than this are no longer "recently overheard". */
    static final long TTL_MS = 600_000L; // 10 min

    private SoulLocalMemory() {
    }

    private record Overheard(String line, Set<UUID> witnesses, long atEpochMs) {
    }

    private static final Map<UUID, Deque<Overheard>> BY_PLAYER = new ConcurrentHashMap<>();

    /**
     * Records one overheard line. No-op for a blank line or an empty witness set — a line nobody
     * heard is not a memory.
     */
    static void note(UUID playerId, String line, Set<UUID> witnessBotIds, long atEpochMs) {
        if (playerId == null || line == null || line.isBlank()
                || witnessBotIds == null || witnessBotIds.isEmpty()) {
            return;
        }
        Deque<Overheard> ring = BY_PLAYER.computeIfAbsent(playerId, id -> new ArrayDeque<>());
        synchronized (ring) {
            ring.addLast(new Overheard(line, Set.copyOf(witnessBotIds), atEpochMs));
            while (ring.size() > MAX_ENTRIES_PER_PLAYER) {
                ring.removeFirst();
            }
        }
    }

    /** @return this player's lines that {@code botId} witnessed and that are inside the TTL,
     *  oldest first; never {@code null}. */
    static List<String> witnessedBy(UUID botId, UUID playerId, long nowEpochMs) {
        if (botId == null || playerId == null) {
            return List.of();
        }
        Deque<Overheard> ring = BY_PLAYER.get(playerId);
        if (ring == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        synchronized (ring) {
            for (Overheard entry : ring) {
                if (nowEpochMs - entry.atEpochMs() <= TTL_MS && entry.witnesses().contains(botId)) {
                    lines.add(entry.line());
                }
            }
        }
        return List.copyOf(lines);
    }

    /** Drops one player's ring — called when they disconnect. */
    static void forget(UUID playerId) {
        if (playerId != null) {
            BY_PLAYER.remove(playerId);
        }
    }

    static void clear() {
        BY_PLAYER.clear();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SoulLocalMemoryTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalMemory.java \
        src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalMemoryTest.java
git commit -m "souls: SoulLocalMemory — bounded per-player overheard-line ring"
```

---

### Task 2: `SoulLocalSalience` — the pure reaction gate

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalSalience.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalSalienceTest.java`

**Interfaces:**
- Consumes: nothing (pure leaf class).
- Produces:
  - `static boolean hardReject(String line, String previousLine)`
  - `static int score(String line, String botName, String activeTask, String recentEventSubject)`
  - `static final int THRESHOLD = 4`

**Why a separate class from the director:** the same split `SoulBanterSeed` uses — the deterministic rules stay pure and exhaustively unit-testable with no Minecraft types, while the director keeps only the parts that need a live server.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalSalienceTest.java`:

```java
package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the deterministic decision about which overheard lines are worth an LLM call
 * (local-chat spec §5.1). Every weight and the threshold are asserted here, so retuning from
 * field-test logs is a visible, reviewed change.
 */
class SoulLocalSalienceTest {

    @Test
    void hardRejectsCatchNoiseBeforeAnyScoring() {
        assertTrue(SoulLocalSalience.hardReject("", null));
        assertTrue(SoulLocalSalience.hardReject("   ", null));
        assertTrue(SoulLocalSalience.hardReject("ok", null), "too short");
        assertTrue(SoulLocalSalience.hardReject("a b c", null), "five chars, under the minimum");
        assertTrue(SoulLocalSalience.hardReject("lets go to the ravine", "lets go to the ravine"),
                "duplicate of previous line");
        assertFalse(SoulLocalSalience.hardReject("lets go to the ravine", "something else"));
    }

    @Test
    void indirectBotMentionScoresButLeadingAddressDoesNot() {
        // "Jake go mine" is an address the DM router already consumed — it must score 0 here.
        assertEquals(0, SoulLocalSalience.score("Jake go mine", "Jake", "", ""));
        // Same name, mid-sentence: a genuine overheard mention.
        assertEquals(3, SoulLocalSalience.score("that was Jake fault", "Jake", "", ""));
    }

    @Test
    void statedIntentScoresOnItsOwn() {
        assertEquals(2, SoulLocalSalience.score("we should rest", "Jake", "", ""));
        assertEquals(0, SoulLocalSalience.score("anyone got flint", "Jake", "", ""),
                "no signal, three words");
    }

    @Test
    void questionAndLengthAndTaskOverlapStack() {
        // question (+2) + six-or-more words (+1) + task overlap on "fishing" (+2) = 5.
        assertEquals(5, SoulLocalSalience.score(
                "do you ever get bored of fishing?", "Jake", "skill:fishing", ""));
    }

    @Test
    void recentEventSubjectOverlapCounts() {
        // overlap (+2) + six words (+1) = 3.
        assertEquals(3, SoulLocalSalience.score(
                "that creeper came out of nowhere", "Jake", "", "creeper"));
    }

    @Test
    void coordinateSpamIsPenalisedBelowThreshold() {
        assertEquals(0, SoulLocalSalience.score("128 64 -512 waypoint here", "Jake", "", ""));
    }

    @Test
    void thresholdBoundaryIsExact() {
        // mention (+3) only = 3, below threshold.
        assertEquals(3, SoulLocalSalience.score("that was Jake fault", "Jake", "", ""));
        // mention (+3) + seven words (+1) = 4, exactly at threshold.
        assertEquals(4, SoulLocalSalience.score(
                "i think that was Jake being careless", "Jake", "", ""));
        assertTrue(SoulLocalSalience.score("i think that was Jake being careless", "Jake", "", "")
                >= SoulLocalSalience.THRESHOLD);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SoulLocalSalienceTest*'`
Expected: FAIL — compilation error, `SoulLocalSalience` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalSalience.java`:

```java
package net.wcfcarolina13.GameAI.souls;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic "is this overheard line worth answering" rule (local-chat spec §5.1). Pure — no
 * Minecraft types, no state — and the whole reason the ambient surface is affordable: it runs
 * before any provider call, on every unaddressed line, and rejects nearly all of them.
 *
 * <p>The model never decides whether to speak; this class does. Weights and the threshold are
 * constants precisely so field-test logs can retune them without touching logic.
 */
final class SoulLocalSalience {

    /** Minimum score for a line to earn a reaction. */
    static final int THRESHOLD = 4;

    static final int WEIGHT_BOT_MENTION = 3;
    static final int WEIGHT_STATED_INTENT = 2;
    static final int WEIGHT_TOPIC_OVERLAP = 2;
    static final int WEIGHT_QUESTION = 2;
    static final int WEIGHT_SUBSTANTIAL = 1;
    static final int PENALTY_NUMERIC = -2;

    private static final int MIN_WORDS = 3;
    private static final int MIN_CHARS = 12;
    private static final int SUBSTANTIAL_WORDS = 6;

    private static final Set<String> INTENT_MARKERS = Set.of(
            "i'm going", "im going", "going to", "let's", "lets ", "we should",
            "i need to", "i'm gonna", "im gonna", "heading to", "planning to");

    private SoulLocalSalience() {
    }

    /**
     * Cheapest possible reject, needing no bot context: blank, too short, or a repeat of the
     * player's previous line. Runs before the veto chain and before any scoring.
     */
    static boolean hardReject(String line, String previousLine) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String trimmed = line.trim();
        if (trimmed.length() < MIN_CHARS) {
            return true;
        }
        if (trimmed.split("\\s+").length < MIN_WORDS) {
            return true;
        }
        return previousLine != null && trimmed.equalsIgnoreCase(previousLine.trim());
    }

    /**
     * Additive salience of {@code line} with respect to one candidate bot. Never negative.
     *
     * @param botName the candidate bot's display name
     * @param activeTask that bot's current task ("skill:fishing", or "" when idle)
     * @param recentEventSubject a one-word subject from its newest journal event ("creeper"), or ""
     */
    static int score(String line, String botName, String activeTask, String recentEventSubject) {
        if (line == null || line.isBlank()) {
            return 0;
        }
        String lower = line.trim().toLowerCase(Locale.ROOT);
        int score = 0;

        if (mentionsBotNotLeading(lower, botName)) {
            score += WEIGHT_BOT_MENTION;
        }
        for (String marker : INTENT_MARKERS) {
            if (lower.contains(marker)) {
                score += WEIGHT_STATED_INTENT;
                break;
            }
        }
        if (overlaps(lower, activeTask) || overlaps(lower, recentEventSubject)) {
            score += WEIGHT_TOPIC_OVERLAP;
        }
        if (lower.endsWith("?")) {
            score += WEIGHT_QUESTION;
        }
        String[] words = lower.split("\\s+");
        if (words.length >= SUBSTANTIAL_WORDS) {
            score += WEIGHT_SUBSTANTIAL;
        }
        if (mostlyNumeric(words)) {
            score += PENALTY_NUMERIC;
        }
        return Math.max(0, score);
    }

    /**
     * True when the bot's name appears somewhere other than the first word. A leading name is an
     * address ("Jake, come here") which the DM router already consumed, so it must score zero
     * here — otherwise every address would double as an overheard mention.
     */
    private static boolean mentionsBotNotLeading(String lowerLine, String botName) {
        if (botName == null || botName.isBlank()) {
            return false;
        }
        String needle = botName.trim().toLowerCase(Locale.ROOT);
        int at = lowerLine.indexOf(needle);
        return at > 0;
    }

    /** Overlap on the meaningful tail of a task id ("skill:fishing" -> "fishing"). */
    private static boolean overlaps(String lowerLine, String topic) {
        if (topic == null || topic.isBlank()) {
            return false;
        }
        String normalized = topic.toLowerCase(Locale.ROOT);
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < normalized.length()) {
            normalized = normalized.substring(colon + 1);
        }
        return normalized.length() >= 3 && lowerLine.contains(normalized);
    }

    private static boolean mostlyNumeric(String[] words) {
        if (words.length == 0) {
            return false;
        }
        int numeric = 0;
        for (String word : words) {
            if (word.matches("-?\\d+")) {
                numeric++;
            }
        }
        return numeric * 2 >= words.length;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SoulLocalSalienceTest*'`
Expected: PASS, 7 tests. Every expected value above was computed by hand against the weights in
Step 3 — if one disagrees, the implementation drifted from the spec, not the test.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalSalience.java \
        src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalSalienceTest.java
git commit -m "souls: SoulLocalSalience — deterministic overheard-line scoring"
```

---

### Task 3: `SceneKind.LOCAL` and the ambient generalization

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypes.java` (add `LOCAL`, `isAmbient()`, `LOCAL_MAX_SCENE_LINES`)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/GroupScenePlayback.java:169`, `:220`, `:323-336`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationService.java:95`, `:113-118`, `:174-176`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssembler.java:58-65`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypesTest.java`, `GroupScenePlaybackTest.java`, `SoulGroupPromptAssemblerTest.java`

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces:
  - `SoulGroupTypes.SceneKind.LOCAL`
  - `boolean SoulGroupTypes.SceneKind.isAmbient()` — true for `BANTER` and `LOCAL`
  - `SoulGroupTypes.LOCAL_MAX_SCENE_LINES = 1`
  - `GroupScenePlayback.lineSurfaces(boolean ambientKind, boolean textAllowed, boolean voiceAllowed, boolean audioPresent)` (parameter renamed from `banterKind`)
  - `GroupScenePlayback.ambientCombatAbort(boolean ambientKind, boolean ownerInCombat, boolean speakerInCombat)` (renamed from `banterCombatAbort`)

**Critical:** `GroupScenePlayback` and `SoulGroupConversationService` currently branch on `kind() == SceneKind.BANTER` in six places. Three are asking "is this ambient?" and must become `isAmbient()`. Three depend on content and stay explicit. Getting this wrong is the most likely bug in the whole plan — a `LOCAL` scene that keeps the soul-DM Text exemption would ignore the ambient mute masks.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypesTest.java`:

```java
    @Test
    void ambientKindsAreBanterAndLocalOnly() {
        assertTrue(SoulGroupTypes.SceneKind.BANTER.isAmbient());
        assertTrue(SoulGroupTypes.SceneKind.LOCAL.isAmbient());
        assertFalse(SoulGroupTypes.SceneKind.PLAYER.isAmbient(),
                "player scenes keep the soul-DM visibility exemption");
    }

    @Test
    void localScenesAreCappedAtOneLine() {
        assertEquals(1, SoulGroupTypes.LOCAL_MAX_SCENE_LINES);
    }

    @Test
    void localTurnUsesThePartyKeyOfItsOwner() {
        UUID owner = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.LOCAL, owner, "Bradley",
                List.of(new SoulGroupTypes.SceneParticipant(bot, "frens:jake", "Jake",
                        new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL,
                                botSnapshot(bot, "Jake"), Optional.empty(), Instant.EPOCH))),
                "heading to the ravine", Instant.EPOCH, UUID.randomUUID());

        assertEquals(SoulGroupTypes.partyKey(owner), turn.key());
        assertEquals(1, turn.roster().size(), "a scene of one is legal");
    }
```

Add whatever imports and a `botSnapshot(UUID, String)` helper the file needs; copy the 30-argument `BotSnapshot` construction from `SoulGroupPromptAssemblerTest.java:25-28`.

Append to `src/test/java/net/wcfcarolina13/GameAI/souls/GroupScenePlaybackTest.java`:

```java
    @Test
    void localLinesRespectAmbientMasksJustLikeBanter() {
        // Text open, voice closed -> text only.
        GroupScenePlayback.LineSurfaces textOnly =
                GroupScenePlayback.lineSurfaces(true, true, false, true);
        assertTrue(textOnly.text());
        assertFalse(textOnly.audio());
        assertFalse(textOnly.skip());

        // Both closed -> skipped entirely, never committed.
        assertTrue(GroupScenePlayback.lineSurfaces(true, false, false, true).skip());

        // A PLAYER scene ignores both masks (soul exemption).
        GroupScenePlayback.LineSurfaces player =
                GroupScenePlayback.lineSurfaces(false, false, false, true);
        assertTrue(player.text());
        assertTrue(player.audio());
        assertFalse(player.skip());
    }

    @Test
    void ambientCombatAbortAppliesToAmbientKindsOnly() {
        assertTrue(GroupScenePlayback.ambientCombatAbort(true, true, false));
        assertTrue(GroupScenePlayback.ambientCombatAbort(true, false, true));
        assertFalse(GroupScenePlayback.ambientCombatAbort(true, false, false));
        assertFalse(GroupScenePlayback.ambientCombatAbort(false, true, true),
                "player scenes are never aborted by combat");
    }
```

Append to `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssemblerTest.java`:

```java
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
```

Add a `localTurn(String)` helper mirroring the existing `turn(String)` (line 33) but passing
`SoulGroupTypes.SceneKind.LOCAL` and a **single**-participant roster. The `profile(String, String)`
helper at line 43 already exists — use it, do not add another.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*SoulGroupTypesTest*' --tests '*GroupScenePlaybackTest*' --tests '*SoulGroupPromptAssemblerTest*'`
Expected: FAIL — `LOCAL` and `isAmbient` do not exist; `lineSurfaces`/`ambientCombatAbort` names do not match.

- [ ] **Step 3: Add the enum constant, the predicate, and the cap**

In `SoulGroupTypes.java`, replace the `SceneKind` enum and its Javadoc:

```java
    /** Tighter scene cap for a one-bot overheard-chat reaction: exactly one line. */
    public static final int LOCAL_MAX_SCENE_LINES = 1;

    /**
     * How a scene came to exist. {@code PLAYER} scenes (broadcast/multi-name chat addresses)
     * keep the soul-DM visibility exemption. {@code BANTER} and {@code LOCAL} scenes are
     * system-initiated and ambient-like: their delivery respects the ambient text/voice category
     * masks, their failures are silent, and combat aborts their remaining lines.
     */
    public enum SceneKind {
        PLAYER, BANTER, LOCAL;

        /** True for the system-initiated kinds that obey the ambient masks. */
        public boolean isAmbient() {
            return this != PLAYER;
        }
    }
```

- [ ] **Step 4: Generalize the three "is this ambient" branches**

In `GroupScenePlayback.java`:

- Line ~169 (`advance`): `boolean banterKind = scene.turn().kind() == SoulGroupTypes.SceneKind.BANTER;` becomes
  `boolean ambientKind = scene.turn().kind().isAmbient();` — update the three uses below it (`state.synth` ternary, the combat-abort call, and nothing else in that method).
- Line ~220 (`deliver`): same rename, and pass `ambientKind` to `lineSurfaces`.
- Lines ~319-336: rename the two static helpers' first parameter to `ambientKind`, rename `banterCombatAbort` to `ambientCombatAbort`, and update both Javadocs to say "ambient kinds (banter and local)" instead of "banter".
- Rename the two supplier fields `banterTextAllowed`/`banterVoiceAllowed` to `ambientTextAllowed`/`ambientVoiceAllowed` (all four constructors plus the two use sites). The values passed in from `SoulRuntime` are already named `ambientTextOpen`/`ambientVoiceOpen`, so this only removes a naming mismatch.

In `SoulGroupConversationService.java`:

- Line ~113: rename `statusUnlessBanter` to `statusUnlessAmbient` and change its body to
  `if (!turn.kind().isAmbient()) { status.deliverStatus(turn.ownerId(), text); }`, updating its Javadoc to "Ambient scenes (banter, local) are system-initiated: their failures never surface to chat." Update both call sites (lines ~87 and ~106).

- [ ] **Step 5: Keep the three content-dependent branches explicit**

In `SoulGroupConversationService.java` line ~95, the HEARD tagging stays a `BANTER`-only check — a `LOCAL` record must fall into the `else` branch and persist as `ownerDisplayName + ": " + playerMessage`, because an overheard line genuinely was said and must replay as a player utterance (spec §6). Add the reason as a comment so a later reader does not "simplify" it to `isAmbient()`:

```java
        // BANTER only: a synthetic narrator seed must never replay as a player utterance. A LOCAL
        // turn carries a real thing the player said in earshot, so it takes the ordinary
        // speaker-tagged form and replays normally.
        String taggedMessage = turn.kind() == SoulGroupTypes.SceneKind.BANTER
                ? SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + turn.playerMessage()
                : turn.ownerDisplayName() + ": " + turn.playerMessage();
```

At line ~174, replace the two-way cap with a switch:

```java
        int maxSceneLines = switch (turn.kind()) {
            case BANTER -> SoulGroupTypes.BANTER_MAX_SCENE_LINES;
            case LOCAL -> SoulGroupTypes.LOCAL_MAX_SCENE_LINES;
            case PLAYER -> SoulGroupTypes.MAX_SCENE_LINES;
        };
```

In `SoulGroupPromptAssembler.assemble` (line ~58), replace the two-way `if` with a switch on kind:

```java
        messages.add(switch (turn.kind()) {
            // Narrator directive, never attributed to the player: banter has no player utterance.
            case BANTER -> new SoulTypes.Message(SoulTypes.Role.USER,
                    "[A quiet moment. The companions chat briefly among themselves. Recent happenings: "
                            + turn.playerMessage() + ". A few short lines only.]");
            // A real utterance the bot overheard: bracketed context, then the tagged line.
            case LOCAL -> new SoulTypes.Message(SoulTypes.Role.USER,
                    "[" + turn.ownerDisplayName() + " is talking nearby, not to you. You may chime"
                            + " in with one short line, or stay quiet if there is nothing worth"
                            + " saying.]\n" + turn.ownerDisplayName() + ": " + turn.playerMessage());
            case PLAYER -> new SoulTypes.Message(SoulTypes.Role.USER,
                    turn.ownerDisplayName() + ": " + turn.playerMessage());
        });
```

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS, 488 + 6 new tests. If any existing `GroupScenePlaybackTest` test fails on the renamed helpers, update the call sites — the behavior is unchanged for `PLAYER` and `BANTER`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/ src/test/java/net/wcfcarolina13/GameAI/souls/
git commit -m "souls: SceneKind.LOCAL + ambient generalization of the scene path"
```

---

### Task 4: The grounding seam — overheard lines reach prompts

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulTypes.java:471-486` (`GroundingSnapshot`)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulSnapshotBuilder.java:793`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssembler.java:70-95`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterSeed.java:34-50`
- Test: `SoulPromptAssemblerTest.java`, `SoulBanterSeedTest.java`, `SoulSituationTypesTest.java`

**Interfaces:**
- Consumes: `SoulLocalMemory.witnessedBy(UUID, UUID, long)` from Task 1.
- Produces:
  - `SoulTypes.GroundingSnapshot.overheard()` returning `List<String>` (never null, defensively copied)
  - Both pre-existing `GroundingSnapshot` constructors preserved, defaulting `overheard` to `List.of()`

**Why grounding:** "what I just heard you say nearby" is perception, and grounding is the one object already threaded to every prompt assembler. Carrying it here is what lets `SoulConversationService` stay untouched.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssemblerTest.java`:

```java
    @Test
    void recentlyOverheardBlockRendersOnlyWhenPresent() {
        SoulTypes.GroundingSnapshot without = groundingWithOverheard(List.of());
        SoulTypes.ProviderRequest bare = assembler.assemble(
                UUID.randomUUID(), "model", profile, without, List.of(), List.of(), List.of(),
                "hello", Duration.ofSeconds(30));
        assertTrue(bare.messages().stream().noneMatch(m -> m.content().contains("RECENTLY OVERHEARD")),
                "an empty list must add no block at all — DM prompts stay byte-identical");

        SoulTypes.GroundingSnapshot with = groundingWithOverheard(
                List.of("heading to the ravine", "bring a bucket"));
        SoulTypes.ProviderRequest grounded = assembler.assemble(
                UUID.randomUUID(), "model", profile, with, List.of(), List.of(), List.of(),
                "hello", Duration.ofSeconds(30));
        String block = grounded.messages().stream()
                .map(SoulTypes.Message::content)
                .filter(c -> c.contains("RECENTLY OVERHEARD"))
                .findFirst().orElse("");
        assertTrue(block.contains("heading to the ravine"));
        assertTrue(block.contains("bring a bucket"));
    }

    @Test
    void overheardBlockIsBounded() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("a fairly long overheard line number " + i + " about mining and caves");
        }
        SoulTypes.ProviderRequest request = assembler.assemble(
                UUID.randomUUID(), "model", profile, groundingWithOverheard(many), List.of(),
                List.of(), List.of(), "hello", Duration.ofSeconds(30));
        String block = request.messages().stream()
                .map(SoulTypes.Message::content)
                .filter(c -> c.contains("RECENTLY OVERHEARD"))
                .findFirst().orElse("");
        assertTrue(block.length() <= SoulPromptAssembler.MAX_OVERHEARD_CHARS + 40,
                "block was " + block.length() + " chars");
    }
```

`assembler` and `profile` are existing **fields** on that test class (lines 21 and 23) — reference
them directly, do not add methods. Add a `groundingWithOverheard(List<String>)` helper that builds a
`GroundingSnapshot` via the new 6-argument constructor, reusing the file's existing bot-snapshot
construction.

Append to `src/test/java/net/wcfcarolina13/GameAI/souls/SoulBanterSeedTest.java`:

```java
    @Test
    void seedPicksUpAnOverheardFragmentFromGrounding() {
        String seed = SoulBanterSeed.build(
                List.of(groundingWithOverheard(List.of("we should check the ravine"))),
                List.of(List.of()), "Bradley", "", new Random(1));

        assertTrue(seed.contains("ravine"), "seed was: " + seed);
    }

    @Test
    void seedWithoutOverheardLinesIsUnchanged() {
        String seed = SoulBanterSeed.build(
                List.of(groundingWithOverheard(List.of())),
                List.of(List.of()), "Bradley", "", new Random(1));

        assertFalse(seed.contains("overheard"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests '*SoulPromptAssemblerTest*' --tests '*SoulBanterSeedTest*'`
Expected: FAIL — no 6-argument `GroundingSnapshot` constructor, no `MAX_OVERHEARD_CHARS`.

- [ ] **Step 3: Extend `GroundingSnapshot`**

Replace `SoulTypes.java:471-486` with:

```java
    public record GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                     Optional<PlayerSnapshot> player, SituationSnapshot situation,
                                     Instant capturedAt, List<String> overheard) {
        public GroundingSnapshot {
            Objects.requireNonNull(reachability, "reachability");
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(capturedAt, "capturedAt");
            player = player == null ? Optional.empty() : player;
            situation = situation == null ? SituationSnapshot.empty() : situation;
            overheard = overheard == null ? List.of() : List.copyOf(overheard);
        }

        /** Pre-overhear shape (local-chat spec §4): no overheard lines. */
        public GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                  Optional<PlayerSnapshot> player, SituationSnapshot situation,
                                  Instant capturedAt) {
            this(reachability, bot, player, situation, capturedAt, List.of());
        }

        public GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                                  Optional<PlayerSnapshot> player, Instant capturedAt) {
            this(reachability, bot, player, SituationSnapshot.empty(), capturedAt, List.of());
        }
    }
```

Both existing constructors are preserved, so **no existing call site changes**.

- [ ] **Step 4: Populate it at capture**

In `SoulSnapshotBuilder.java`, change the final return (line ~793) to read the ring for this
bot/player pair. `capture` already has `bot` and the sender; use the player's UUID when present:

```java
        List<String> overheard = playerOpt
                .map(p -> SoulLocalMemory.witnessedBy(bot.getUuid(), p.playerId(),
                        System.currentTimeMillis()))
                .orElse(List.of());
        return new SoulTypes.GroundingSnapshot(reachability, bot, playerOpt, situation,
                capturedAt, overheard);
```

If `PlayerSnapshot` exposes the uuid under a different accessor, use whatever the record declares —
grep `record PlayerSnapshot` in `SoulTypes.java` and use that name. No toggle check is needed here:
the ring is empty whenever `soulLocalChatEnabled` is off, because Task 6 gates the write.

- [ ] **Step 5: Render the DM block**

In `SoulPromptAssembler.java`, add the constant beside the other bounds (near line 50):

```java
    /** Bound on the RECENTLY OVERHEARD block (local-chat spec §4 consumer 3). */
    static final int MAX_OVERHEARD_CHARS = 200;
```

and insert one message immediately before `messages.add(presentMoment(grounding));` in both
`assemble` overloads' shared body (line ~91):

```java
        overheardBlock(grounding).ifPresent(messages::add);
```

with the helper:

```java
    /**
     * Bounded RECENTLY OVERHEARD block: things this player said out loud near this bot that it
     * was not addressed by. Empty whenever ambient/local chat is off, so DM prompts are then
     * byte-identical to the pre-feature build.
     */
    private Optional<SoulTypes.Message> overheardBlock(SoulTypes.GroundingSnapshot grounding) {
        if (grounding.overheard().isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("RECENTLY OVERHEARD\n");
        sb.append("Things they said out loud nearby, not to you:\n");
        for (String line : grounding.overheard()) {
            String candidate = "- " + line + "\n";
            if (sb.length() + candidate.length() > MAX_OVERHEARD_CHARS) {
                break;
            }
            sb.append(candidate);
        }
        return Optional.of(new SoulTypes.Message(SoulTypes.Role.SYSTEM, sb.toString()));
    }
```

- [ ] **Step 6: Add the banter seed fragment**

In `SoulBanterSeed.build`, after the `playerActivity` block and before `String seed = String.join(...)`:

```java
        if (!rosterGroundings.isEmpty()) {
            List<String> overheard = rosterGroundings.get(0).overheard();
            if (!overheard.isEmpty()) {
                parts.add(playerName + " was saying: "
                        + truncatePhrase(overheard.get(overheard.size() - 1)));
            }
        }
```

Add a small private `truncatePhrase(String)` capping at `MAX_PHRASE_CHARS`, matching how
`phraseFor` already bounds its output.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`
Expected: PASS, previous total + 4.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/ src/test/java/net/wcfcarolina13/GameAI/souls/
git commit -m "souls: carry overheard lines on GroundingSnapshot; DM + banter-seed consumers"
```

---

### Task 5: `SoulLocalDirector` — veto chain, cooldowns, reply window

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirector.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirectorTest.java`

**Interfaces:**
- Consumes: `SoulLocalSalience.hardReject/score/THRESHOLD` (Task 2), `SoulGroupTypes.SceneKind.LOCAL` (Task 3), `SoulLocalMemory` (Task 1), and from the existing runtime: `SoulRuntime.pipelineAvailable()`, `isSceneBudgetFree(UUID)`, `hasActiveProfile(UUID)`, `cachedState(UUID)`, `submitGroupTurn(GroupSceneTurn)`; `SoulGroupRouter.eligibleRoster(List<Candidate>)`; `CompanionCommunicationPolicy.isPrivateSoulAuthorized/classifySoulReachability`; `SoulSnapshotBuilder.capture`.
- Produces:
  - `static String firstVeto(boolean enabled, boolean pipelineAvailable, boolean cooldownElapsed, boolean budgetFree, boolean surfaceOpen, boolean playerAtEase, int eligibleRosterSize, boolean salient)`
  - `static long nextDelayMs(RandomGenerator)`, `static long initialDelayMs(RandomGenerator)`
  - `static boolean groundingDangerous(SoulTypes.SituationSnapshot)`
  - `void noteUnaddressedChat(ServerPlayerEntity player, String line)`
  - `void noteAddressedChat(UUID playerId)` — closes any open reply window
  - `void notePlayerScene(UUID playerId)`, `void tick()`, `String statusFor(UUID playerId)`
  - `static final double EARSHOT_BLOCKS = 16.0`, `static final long REPLY_WINDOW_MS = 30_000L`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirectorTest.java`:

```java
package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the deterministic local-chat eligibility rules: veto ordering (roster before
 * salience, because scoring is per-bot), the cooldown band, and the danger veto.
 */
class SoulLocalDirectorTest {

    @Test
    void firstVetoReportsGatesInSpecOrder() {
        assertNull(SoulLocalDirector.firstVeto(true, true, true, true, true, true, 1, true));

        assertEquals("disabled", SoulLocalDirector.firstVeto(false, true, true, true, true, true, 1, true));
        assertEquals("pipeline", SoulLocalDirector.firstVeto(true, false, true, true, true, true, 1, true));
        assertEquals("cooldown", SoulLocalDirector.firstVeto(true, true, false, true, true, true, 1, true));
        assertEquals("busy", SoulLocalDirector.firstVeto(true, true, true, false, true, true, 1, true));
        assertEquals("muted", SoulLocalDirector.firstVeto(true, true, true, true, false, true, 1, true));
        assertEquals("player-not-at-ease", SoulLocalDirector.firstVeto(true, true, true, true, true, false, 1, true));
        assertEquals("roster", SoulLocalDirector.firstVeto(true, true, true, true, true, true, 0, true));
        assertEquals("salience", SoulLocalDirector.firstVeto(true, true, true, true, true, true, 1, false));

        // Earlier gate wins when several fail.
        assertEquals("cooldown", SoulLocalDirector.firstVeto(true, true, false, false, false, false, 0, false));
    }

    @Test
    void rosterIsCheckedBeforeSalience() {
        // Scoring is per-bot: with no roster there is nothing to score against, so "roster"
        // must be the reported reason even when the line is also unsalient.
        assertEquals("roster", SoulLocalDirector.firstVeto(true, true, true, true, true, true, 0, false));
    }

    @Test
    void cooldownBandsHoldOverManySamples() {
        Random random = new Random(11);
        for (int i = 0; i < 1000; i++) {
            long next = SoulLocalDirector.nextDelayMs(random);
            assertTrue(next >= 6 * 60_000L && next <= 12 * 60_000L, "next=" + next);
            long initial = SoulLocalDirector.initialDelayMs(random);
            assertTrue(initial >= 0L && initial <= 2 * 60_000L, "initial=" + initial);
        }
    }

    @Test
    void groundingDangerVetoMatchesBanter() {
        assertFalse(SoulLocalDirector.groundingDangerous(SoulTypes.SituationSnapshot.empty()));
        assertTrue(SoulLocalDirector.groundingDangerous(withHostiles()));
    }

    @Test
    void replyWindowConstantsAreTheSpecValues() {
        assertEquals(30_000L, SoulLocalDirector.REPLY_WINDOW_MS);
        assertEquals(16.0, SoulLocalDirector.EARSHOT_BLOCKS);
    }

    private static SoulTypes.SituationSnapshot withHostiles() {
        return new SoulTypes.SituationSnapshot(-1,
                List.of(new SoulTypes.HostileSighting("creeper", "north", 8)),
                List.of(), "", List.of(), false, false, false, "", false,
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*SoulLocalDirectorTest*'`
Expected: FAIL — `SoulLocalDirector` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirector.java`. Model the class
structure on `SoulBanterDirector` (same constructor shape, same `recordVerdict` throttling, same
`eligibleRosterBots` helper) with these differences:

- **Edge-triggered, not polled.** `noteUnaddressedChat(player, line)` is the entry point, called
  from the chat callback on the server thread. `tick()` only expires reply windows.
- **Hard rejects first**, before the veto chain, using the player's previous line (keep a
  `Map<UUID, String> lastLineByPlayer`, updated after every evaluation).
- **Roster before salience** in `firstVeto`.
- **Continuation bypass:** when a reply window is open for (player, bot), skip the `cooldown` and
  `salience` gates only.

The constructor signature is fixed by Task 6's call site — copy it exactly:

```java
    SoulLocalDirector(SoulRuntime runtime, MinecraftServer server,
                      BooleanSupplier localChatEnabled, BooleanSupplier ambientTextOpen,
                      BooleanSupplier ambientVoiceOpen,
                      Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider,
                      LongSupplier clock, RandomGenerator random)
```

```java
    static final double EARSHOT_BLOCKS = 16.0;
    static final long REPLY_WINDOW_MS = 30_000L;
    static final long RETRY_AFTER_VETO_MS = 120_000L;

    /** First failed gate's name in spec §5.2 order, or {@code null} when eligible. */
    static String firstVeto(boolean enabled, boolean pipelineAvailable, boolean cooldownElapsed,
                             boolean budgetFree, boolean surfaceOpen, boolean playerAtEase,
                             int eligibleRosterSize, boolean salient) {
        if (!enabled) {
            return "disabled";
        }
        if (!pipelineAvailable) {
            return "pipeline";
        }
        if (!cooldownElapsed) {
            return "cooldown";
        }
        if (!budgetFree) {
            return "busy";
        }
        if (!surfaceOpen) {
            return "muted";
        }
        if (!playerAtEase) {
            return "player-not-at-ease";
        }
        if (eligibleRosterSize < 1) {
            return "roster";
        }
        if (!salient) {
            return "salience";
        }
        return null;
    }

    static long initialDelayMs(RandomGenerator random) {
        return (long) (random.nextDouble() * 2 * 60_000L);
    }

    static long nextDelayMs(RandomGenerator random) {
        return 6 * 60_000L + (long) (random.nextDouble() * 6 * 60_000L);
    }

    /** Post-capture danger veto — identical rule to banter's. */
    static boolean groundingDangerous(SoulTypes.SituationSnapshot situation) {
        return !situation.hostiles().isEmpty() || situation.inCombat()
                || situation.breakingFree() || situation.surfaceRecoveryActive();
    }
```

The instance path, in order:

1. Return immediately if the player is a bot, or `hardReject(line, lastLine)` is true. Record the
   line as `lastLine` regardless.
2. Compute `windowOpen` for this player (an open, unexpired, unused window).
3. Build the eligible roster: bots in the same world within `EARSHOT_BLOCKS`, sorted by distance,
   filtered through `SoulGroupRouter.eligibleRoster` exactly as `SoulBanterDirector.eligibleRosterBots`
   does (copy that method, changing only the radius constant).
4. Pick the reacting bot: highest `SoulLocalSalience.score(line, botName, activeTask, eventSubject)`,
   ties broken by the distance order already established. Take `activeTask` from
   `SoulSnapshotBuilder.capture(...).bot().activeTask()` — capture once per candidate and reuse the
   snapshot for step 6, so grounding is captured exactly once per bot.
5. Call `firstVeto(...)` with `salient = bestScore >= SoulLocalSalience.THRESHOLD || windowOpen`
   and `cooldownElapsed = now >= nextAt || windowOpen`. On a non-null veto, `recordVerdict` and return.
6. Apply `groundingDangerous` to the chosen bot's snapshot; on danger, record `vetoed:danger`, push
   `nextEligibleAtMs` by `RETRY_AFTER_VETO_MS`, and return.
7. Build the turn and submit:

```java
        SoulGroupTypes.SceneParticipant participant = new SoulGroupTypes.SceneParticipant(
                bot.getUuid(), profileId, bot.getName().getString(), grounding);
        UUID routingId = UUID.randomUUID();
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.LOCAL, player.getUuid(), player.getName().getString(),
                List.of(participant), line, Instant.now(), routingId);
        nextEligibleAtMs.put(player.getUuid(), now + nextDelayMs(random));
        openReplyWindow(player.getUuid(), bot.getUuid(), now);
        recordVerdict(player.getUuid(), "fired");
        LOGGER.info("[souls] local player={} bot={} outcome=fired routingId={} score={}",
                player.getUuid(), bot.getUuid(), routingId, bestScore);
        runtime.submitGroupTurn(turn);
```

Note the log carries the score and never the line. `openReplyWindow` replaces any existing window
(spec §7: a new reaction closes the old window). If the fire was itself a continuation, mark the
window used instead of reopening — one continuation per window.

`statusFor(UUID)` returns, in the style of `SoulBanterDirector.statusFor`:
`"last verdict: <verdict>; last score: N; cooldown: <n>s remaining|elapsed; reply window: open|closed"`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests '*SoulLocalDirectorTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Verify compilation of the whole module**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirector.java \
        src/test/java/net/wcfcarolina13/GameAI/souls/SoulLocalDirectorTest.java
git commit -m "souls: SoulLocalDirector — salience gate, cooldowns, reply window"
```

---

### Task 6: Runtime and chat-callback wiring

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java` (field ~107, construction ~238, `tickScenes` ~493, `submitGroupTurn` ~428, new statics + `localStatus`)
- Modify: `src/main/java/net/wcfcarolina13/Frens.java:1274` (chat callback), plus the disconnect hook
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulRuntimeTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces:
  - `static void SoulRuntime.noteUnaddressedChat(ServerPlayerEntity player, String line)` — the witness set is computed inside the director, not passed in
  - `static void SoulRuntime.noteAddressedChat(ServerPlayerEntity player)`
  - `static void SoulRuntime.forgetPlayerLocalMemory(UUID playerId)`
  - `String SoulRuntime.localStatus(UUID playerId)`

- [ ] **Step 1: Wire the director into the runtime**

In `SoulRuntime.java`:

- Add `private volatile SoulLocalDirector localDirector;` beside `banterDirector` (line ~107).
- Construct it immediately after the `banterDirector` assignment (line ~238), reusing the
  `ambientTextOpen`/`ambientVoiceOpen` suppliers already in scope:

```java
            runtime.localDirector = new SoulLocalDirector(runtime, server,
                    () -> {
                        ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                        return cfg != null && cfg.isSoulLocalChatEnabled();
                    },
                    ambientTextOpen, ambientVoiceOpen,
                    srv -> net.wcfcarolina13.GameAI.BotEventHandler.getRegisteredBots(srv),
                    System::currentTimeMillis, new java.util.Random());
```

- In `tickScenes` (line ~493), after the banter director's `tick()`:

```java
        SoulLocalDirector local = runtime.localDirector;
        if (local != null) {
            local.tick();
        }
```

- In `submitGroupTurn` (line ~428), extend the re-arm so the two ambient surfaces take turns:

```java
        if (turn.kind() == SoulGroupTypes.SceneKind.PLAYER) {
            // A real conversation re-arms both ambient cooldowns — they yield to the player.
            SoulBanterDirector banter = banterDirector;
            if (banter != null) {
                banter.notePlayerScene(turn.ownerId());
            }
            SoulLocalDirector local = localDirector;
            if (local != null) {
                local.notePlayerScene(turn.ownerId());
            }
        } else if (turn.kind() == SoulGroupTypes.SceneKind.LOCAL) {
            SoulBanterDirector banter = banterDirector;
            if (banter != null) {
                banter.notePlayerScene(turn.ownerId());
            }
        } else if (turn.kind() == SoulGroupTypes.SceneKind.BANTER) {
            SoulLocalDirector local = localDirector;
            if (local != null) {
                local.notePlayerScene(turn.ownerId());
            }
        }
```

- Add the static facades beside `notePlayerChat` (line ~505), each fully swallowing failures:

```java
    /**
     * Records one unaddressed chat line into the overhear ring and offers it to the local
     * director. Observational only — this never consumes the chat line (local-chat spec §3).
     */
    public static void noteUnaddressedChat(net.minecraft.server.network.ServerPlayerEntity player,
                                            String line) {
        try {
            SoulRuntime runtime = INSTANCE.get();
            if (runtime == null || player == null || line == null || line.isBlank()) {
                return;
            }
            ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
            if (cfg == null || !cfg.isSoulLocalChatEnabled()) {
                return; // gated at the write: nothing recorded, nothing to read
            }
            SoulLocalDirector director = runtime.localDirector;
            if (director != null) {
                director.noteUnaddressedChat(player, line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** An explicit address closes any open reply window (local-chat spec §7). */
    public static void noteAddressedChat(net.minecraft.server.network.ServerPlayerEntity player) {
        try {
            SoulRuntime runtime = INSTANCE.get();
            if (runtime != null && player != null) {
                SoulLocalDirector director = runtime.localDirector;
                if (director != null) {
                    director.noteAddressedChat(player.getUuid());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Drops one player's overheard ring on disconnect. */
    public static void forgetPlayerLocalMemory(UUID playerId) {
        try {
            SoulLocalMemory.forget(playerId);
        } catch (Throwable ignored) {
        }
    }

    /** For {@code /bot soul local status}. */
    public String localStatus(UUID playerId) {
        SoulLocalDirector director = localDirector;
        return director == null ? "Local chat director not running." : director.statusFor(playerId);
    }
```

The recorder's `SoulLocalMemory.note(...)` call lives inside
`SoulLocalDirector.noteUnaddressedChat`, after it computes the earshot set — that way the witness
set is computed exactly once and shared between the record and the reaction decision.

- Add `SoulLocalMemory.clear();` next to the existing `SoulPlayerActivity.clear()` call in the
  shutdown path (grep `SoulPlayerActivity.clear` to find it).

- [ ] **Step 2: Hook the chat callback**

In `Frens.java`, the callback at line ~1274 already calls `SoulRuntime.notePlayerChat(sender)`
before `resolveChatTargets`. Leave that untouched. Then:

- Immediately inside `if (!target.bots().isEmpty()) {` (line ~1276), add as the **first** statement:

```java
                // Explicit address: close any open ambient reply window. Observational only.
                net.wcfcarolina13.GameAI.souls.SoulRuntime.noteAddressedChat(sender);
```

- Add an `else` branch on that same `if`, before the terminal `handleLegacyInlineActionFromRaw`
  call at line ~1368. The unaddressed branch currently falls through to that call, so the new hook
  must sit immediately before it and **must not return**:

```java
            // Ambient/local chat: bots standing nearby may overhear this. Strictly observational
            // — this never consumes the line, so the legacy inline-action parser below still runs
            // exactly as it did before the feature existed (local-chat spec §3).
            net.wcfcarolina13.GameAI.souls.SoulRuntime.noteUnaddressedChat(sender, raw);

            handleLegacyInlineActionFromRaw(raw, sender);
```

Both statics already swallow every `Throwable`, so no additional try/catch is needed here — but
confirm by reading the two methods that this is true before relying on it.

- [ ] **Step 3: Hook disconnect**

Find the existing player-disconnect handler (grep `ServerPlayConnectionEvents.DISCONNECT` in
`Frens.java`). Add:

```java
            net.wcfcarolina13.GameAI.souls.SoulRuntime.forgetPlayerLocalMemory(handler.getPlayer().getUuid());
```

If no disconnect handler exists in `Frens.java`, register one following the pattern of the
neighboring `ServerPlayConnectionEvents` registrations in that file.

- [ ] **Step 4: Add the wiring regression test**

Append to `src/test/java/net/wcfcarolina13/GameAI/souls/SoulRuntimeTest.java`:

```java
    @Test
    void localChatStaticsAreNullSafeWithNoRuntimeInstalled() {
        // The chat callback calls these on every line; with souls off there is no runtime and
        // they must be silent no-ops rather than throwing into the chat handler.
        assertDoesNotThrow(() -> SoulRuntime.noteUnaddressedChat(null, "anything"));
        assertDoesNotThrow(() -> SoulRuntime.noteAddressedChat(null));
        assertDoesNotThrow(() -> SoulRuntime.forgetPlayerLocalMemory(UUID.randomUUID()));
    }
```

- [ ] **Step 5: Build and run the suite**

Run: `./gradlew build -x test && ./gradlew test`
Expected: BUILD SUCCESSFUL; suite green.

- [ ] **Step 6: Manually re-read the chat callback and confirm the never-consume invariant**

Open `Frens.java` around lines 1270-1372 and confirm by eye that:
- no new `return` was introduced,
- `handleLegacyInlineActionFromRaw(raw, sender)` still runs on the unaddressed path,
- `tryHandleNearbyQuestAsk`, `BotRespawnPromptService.handleChat`, and `SkillResumeService.handleChat`
  still run before any new code.

This is a review step, not a test — the invariant is the feature's whole safety story.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java \
        src/main/java/net/wcfcarolina13/Frens.java \
        src/test/java/net/wcfcarolina13/GameAI/souls/SoulRuntimeTest.java
git commit -m "souls: wire local director into runtime + observational chat hook"
```

---

### Task 7: Config, command, and UI toggle

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java:119` (field), `:859` (accessors)
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java:105-108` (registration), `:535-567` (handlers)
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java:45` (toggle def), `:53` (index), `:223` (init load), `:516` (save)
- Test: `src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java`

**Interfaces:**
- Consumes: `SoulRuntime.localStatus(UUID)` from Task 6.
- Produces: `ManualConfig.isSoulLocalChatEnabled()` / `setSoulLocalChatEnabled(boolean)`.

- [ ] **Step 1: Add the config field**

In `ManualConfig.java`, after the `soulBanterEnabled` declaration (line ~119):

```java
    // Ambient/local chat: bots overhearing unaddressed chat spoken near them. Default-OFF for the
    // same reason banter is — companions spending LLM/TTS time unprompted is opt-in. This toggle
    // also gates the overhear recorder's write, so off means nothing is recorded at all.
    private boolean soulLocalChatEnabled = false;
```

and after the `soulBanterEnabled` accessors (line ~859):

```java
    public boolean isSoulLocalChatEnabled() { return soulLocalChatEnabled; }
    public void setSoulLocalChatEnabled(boolean v) { this.soulLocalChatEnabled = v; }
```

- [ ] **Step 2: Add the command**

In `BotSoulCommands.java`, extend the registration chain (line ~108) — note the previous
`.then(...)` loses its trailing `;`:

```java
                .then(CommandManager.literal("banter")
                        .then(CommandManager.literal("on").executes(ctx -> executeBanterToggle(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> executeBanterToggle(ctx, false)))
                        .then(CommandManager.literal("status").executes(BotSoulCommands::executeBanterStatus)))
                .then(CommandManager.literal("local")
                        .then(CommandManager.literal("on").executes(ctx -> executeLocalToggle(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> executeLocalToggle(ctx, false)))
                        .then(CommandManager.literal("status").executes(BotSoulCommands::executeLocalStatus)));
```

and add the two handlers beside the banter pair (after line ~567), mirroring them exactly:

```java
    /**
     * {@code /bot soul local on|off} — operator-only switch for ambient/local chat. No pipeline
     * reload needed: the director reads the config through a live supplier.
     */
    private static int executeLocalToggle(CommandContext<ServerCommandSource> context, boolean enabled) {
        ServerCommandSource source = context.getSource();
        if (!Frens.isOperator(source)) {
            source.sendError(Text.literal("Only an operator may change the local-chat switch."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        config.setSoulLocalChatEnabled(enabled);
        config.save();
        ChatUtils.sendSystemMessage(source, "Companion local chat set to " + (enabled ? "on" : "off")
                + (enabled ? ". They may chime in on what they overhear." : "."));
        return 1;
    }

    /** {@code /bot soul local status} — enablement plus the actor's live eligibility verdict. */
    private static int executeLocalStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity actor = source.getPlayer();
        if (actor == null) {
            source.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        ManualConfig config = Frens.CONFIG;
        boolean enabled = config != null && config.isSoulLocalChatEnabled();
        String verdict = SoulRuntime.current()
                .map(rt -> rt.localStatus(actor.getUuid()))
                .orElse("Soul runtime is not currently running.");
        ChatUtils.sendSystemMessage(source,
                "Local chat is " + (enabled ? "ON" : "OFF") + ". " + verdict);
        return 1;
    }
```

- [ ] **Step 3: Add the UI chip — all three wiring sites together**

In `BotControlScreen.java`:

1. Append to `GLOBAL_TOGGLES` (after line 45, adding a `,` to the Banter entry):

```java
            new GlobalToggleDef("Local", "Companions may occasionally react to chat you type near them that wasn't addressed to anyone (needs Soul Chat on). Ambient category masks in the Text/Voice Adv… menus apply. /bot soul local status explains why it is or isn't firing.")
```

2. Add the index beside `BANTER_TOGGLE_INDEX` (line 53):

```java
    private static final int LOCAL_TOGGLE_INDEX = 9;
```

3. Load it in `init()` (after line 223):

```java
            globalValues[LOCAL_TOGGLE_INDEX] = Frens.CONFIG.isSoulLocalChatEnabled();
```

4. Save it in `saveSettings()` (after line 516):

```java
        // Local chat needs no pipeline reload either: same live-supplier pattern as banter.
        config.setSoulLocalChatEnabled(globalValues[LOCAL_TOGGLE_INDEX]);
```

The comment at line 48 warns that these three sites move together — this step is that contract.

- [ ] **Step 4: Add the command test**

Append to `src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java` a test in whatever
style that file already uses for the banter switch. If it only covers pure helpers, add instead a
`ManualConfig` round-trip test asserting the default and the setter:

```java
    @Test
    void localChatDefaultsOffAndRoundTrips() {
        ManualConfig config = new ManualConfig();
        assertFalse(config.isSoulLocalChatEnabled(), "ambient speech must be opt-in");
        config.setSoulLocalChatEnabled(true);
        assertTrue(config.isSoulLocalChatEnabled());
    }
```

- [ ] **Step 5: Build and run the suite**

Run: `./gradlew build -x test && ./gradlew test`
Expected: BUILD SUCCESSFUL; suite green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java \
        src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java \
        src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java \
        src/test/java/net/wcfcarolina13/Commands/BotSoulCommandsTest.java
git commit -m "souls: soulLocalChatEnabled config, /bot soul local command, Local chip"
```

---

### Task 8: Docs, version bump, and handoff

**Files:**
- Modify: `changelog.md` (newest-first entry)
- Modify: `RALPH_TASK.md` (task line + new handoff section at the top of the handoffs)
- Modify: `gradle.properties` (`mod_version`)

- [ ] **Step 1: Bump the version**

In `gradle.properties`, change `mod_version=1.1.177-release+1.21.11` to
`mod_version=1.1.178-release+1.21.11`. Required before any deploy, per `CLAUDE.md`.

- [ ] **Step 2: Write the changelog entry**

Add a newest-first entry to `changelog.md` covering: the two units and why they are separate; the
never-consume invariant; the salience weights and threshold as the field-tuning surface; the
grounding seam and why it kept `SoulConversationService` untouched; the write-side gate; the
`LOCAL` scene cap of 1 and the ordinary (unmarked) HEARD record; the reply window; and the commit
hashes from Tasks 1-7.

Include the field-test checklist:
- Toggle off: no `[souls] local` lines at all, and DM replies unchanged.
- `/bot soul local status` while typing deliberately boring vs deliberately salient lines — watch
  the score and the veto reason.
- A fired reaction end to end with voice, confirmed audible to a second player standing nearby.
- Ambient text muted → voice only; ambient voice muted → text only; both muted → the `muted` veto
  fires and **no generation happens at all**.
- Answer the bot inside 30 s → one continuation; let it lapse → nothing.
- Address a bot explicitly mid-window → window closes.
- Combat during the window → `danger` veto.
- Cooldown spacing over a session; banter and local alternating rather than stacking.
- `/bot soul reset party` archiving local records alongside group-chat and banter ones.

- [ ] **Step 3: Update `RALPH_TASK.md`**

Change the front-matter `task:` line to say local chat (1.1.178) shipped and is pending field test.
Add a new handoff section above the 2026-08-26 banter one, naming the next soul-track queue item —
**consolidation** — and carrying forward the still-open field tests for group chat (1.1.176) and
banter (1.1.177).

- [ ] **Step 4: Final verification**

Run: `./gradlew build -x test && ./gradlew test`
Expected: BUILD SUCCESSFUL; suite green; report the exact final test count in the changelog entry.

- [ ] **Step 5: Commit**

```bash
git add changelog.md RALPH_TASK.md gradle.properties
git commit -m "souls: local chat docs + changelog + handoff; bump 1.1.178"
```

- [ ] **Step 6: Report the artifact path and STOP**

Report `build/libs/frens-1.1.178-release+1.21.11.jar` and **wait for Bradley to confirm the game is
closed** before any deploy. Per `CLAUDE.md`, run the pre-deploy check first:

```bash
pgrep -f "net.minecraft.client.main.Main" >/dev/null && { echo "ABORT: Minecraft is running — close the game first"; exit 1; }
```

Do not silently hot-swap the JAR.
