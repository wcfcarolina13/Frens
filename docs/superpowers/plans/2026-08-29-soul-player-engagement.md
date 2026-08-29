# Soul Player Engagement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Companions may start conversations with the player — a lone bot occasionally speaks TO them (solo remarks), and multi-bot banter scenes may end with a player-addressed line — both opening the existing 30 s reply window on delivery.

**Architecture:** Extends the shipped banter director (roster gate ≥2 → ≥1, deterministic fire-time `addressPlayer` decision) and the `GroupSceneTurn`/assembler pair (flag + three directive variants). The reply-window handoff extends the 1.1.178 `sceneDelivered` seam with a last-speaker index and a NEW director entry point `noteEngagementDelivered` — the existing `noteSceneDelivered` cannot be reused because its continuation tracker deliberately fails closed for deliveries with no matching pending fire.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric, JUnit 5. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-29-frens-soul-player-engagement-design.md`

## Global Constraints

- Baseline: 539 tests green on 1.1.180. Every task leaves the suite green and growing.
- No `Frens` references inside `GameAI/souls/` except `SoulRuntime`'s established lazy lambdas; logger `LoggerFactory.getLogger("frens.souls")`; message content never logged.
- The model never schedules: `addressPlayer` is decided by the director's injected `RandomGenerator`, probability exactly 1/3 for rosters ≥2, always true for roster 1.
- No new toggles, no new caps, no LoadGoverner change; `PLAYER` and `LOCAL` turns never set `addressPlayer`.

---

### Task 1: `addressPlayer` flag + assembler directives

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypes.java:75-99`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssembler.java:58-70`
- Test: `SoulGroupTypesTest.java`, `SoulGroupPromptAssemblerTest.java`

**Interfaces:**
- Produces: `GroupSceneTurn(SceneKind kind, UUID ownerId, String ownerDisplayName, List<SceneParticipant> roster, String playerMessage, Instant acceptedAt, UUID routingId, boolean addressPlayer)` — canonical 8-arg; both existing constructors (7-arg kinded, 6-arg PLAYER-compat) preserved, defaulting `addressPlayer=false`. Accessor `turn.addressPlayer()`.

- [ ] **Step 1: Failing tests.** Append to `SoulGroupTypesTest`:

```java
    @Test
    void addressPlayerDefaultsFalseOnBothCompatConstructors() {
        UUID owner = UUID.randomUUID();
        SoulGroupTypes.GroupSceneTurn kinded = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.BANTER, owner, "Bradley", List.of(), "seed",
                Instant.EPOCH, UUID.randomUUID());
        assertFalse(kinded.addressPlayer(), "7-arg compat shape must not address the player");
        SoulGroupTypes.GroupSceneTurn playerKind = new SoulGroupTypes.GroupSceneTurn(
                owner, "Bradley", List.of(), "hi", Instant.EPOCH, UUID.randomUUID());
        assertFalse(playerKind.addressPlayer());

        SoulGroupTypes.GroupSceneTurn engaged = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.BANTER, owner, "Bradley", List.of(), "seed",
                Instant.EPOCH, UUID.randomUUID(), true);
        assertTrue(engaged.addressPlayer());
    }
```

Append to `SoulGroupPromptAssemblerTest` (reuse the file's `turn(String)`/`profile(String,String)`/`grounding(UUID,String)` helpers; add a `banterTurn(int rosterSize, boolean addressPlayer)` helper that builds a BANTER turn with 1 or 2 participants via the 8-arg constructor):

```java
    @Test
    void groupBanterWithoutFlagKeepsTheShippedDirective() {
        SoulTypes.ProviderRequest req = assemble(banterTurn(2, false));
        String last = lastMessage(req);
        assertTrue(last.contains("chat briefly among themselves"));
        assertFalse(last.contains("to Bradley"), "unflagged banter never addresses the player");
    }

    @Test
    void groupBanterWithFlagAppendsThePlayerAddressedOption() {
        String last = lastMessage(assemble(banterTurn(2, true)));
        assertTrue(last.contains("chat briefly among themselves"), "appends, not replaces");
        assertTrue(last.contains("may end by saying one short thing to Bradley"));
    }

    @Test
    void soloBanterDirectiveSpeaksToThePlayerByName() {
        String last = lastMessage(assemble(banterTurn(1, true)));
        assertFalse(last.contains("among themselves"), "there is no 'themselves' for one bot");
        assertTrue(last.contains("Jake may say one short thing to Bradley"));
        assertTrue(last.contains("seed-text"), "the seed still steers the topic");
    }
```

with small private helpers `assemble(turn)` (calls `assembler.assemble(UUID.randomUUID(), "model", turn, List.of(profile("frens:jake","Jake"), ...one per participant...), List.of(), Duration.ofSeconds(30))`) and `lastMessage(req)` returning `req.messages().get(req.messages().size()-1).content()`; the banter seed passed is the literal `"seed-text"`.

- [ ] **Step 2:** `./gradlew test --tests '*SoulGroupTypesTest*' --tests '*SoulGroupPromptAssemblerTest*'` → FAIL (no 8-arg constructor / no accessor).

- [ ] **Step 3: Implement.** In `SoulGroupTypes.java` replace the record header and compat constructor block:

```java
    public record GroupSceneTurn(SceneKind kind, UUID ownerId, String ownerDisplayName,
                                  List<SceneParticipant> roster, String playerMessage,
                                  Instant acceptedAt, UUID routingId, boolean addressPlayer) {
        public GroupSceneTurn {
            // (existing null checks / defensive copies unchanged)
        }

        /** Pre-engagement shape (1.1.177–1.1.180): scene never addresses the player. */
        public GroupSceneTurn(SceneKind kind, UUID ownerId, String ownerDisplayName,
                               List<SceneParticipant> roster, String playerMessage,
                               Instant acceptedAt, UUID routingId) {
            this(kind, ownerId, ownerDisplayName, roster, playerMessage, acceptedAt,
                    routingId, false);
        }

        /** Compatibility shape from the group-chat pilot: a player-initiated scene. */
        public GroupSceneTurn(UUID ownerId, String ownerDisplayName,
                               List<SceneParticipant> roster, String playerMessage,
                               Instant acceptedAt, UUID routingId) {
            this(SceneKind.PLAYER, ownerId, ownerDisplayName, roster, playerMessage,
                    acceptedAt, routingId, false);
        }
        // key() unchanged
    }
```

In `SoulGroupPromptAssembler.java` replace only the BANTER arm of the switch:

```java
            // Narrator directive, never attributed to the player: banter has no player utterance.
            // Three variants (engagement spec §4): solo scenes always speak TO the owner; group
            // scenes may be granted a closing player-addressed line by the director's fire-time
            // coin — the model never decides WHETHER, only HOW.
            case BANTER -> new SoulTypes.Message(SoulTypes.Role.USER,
                    turn.roster().size() == 1
                            ? "[A quiet moment. " + turn.roster().get(0).displayName()
                                    + " may say one short thing to " + turn.ownerDisplayName()
                                    + " — a remark, an observation, or a question about recent"
                                    + " happenings: " + turn.playerMessage()
                                    + ". One or two short lines only.]"
                            : "[A quiet moment. The companions chat briefly among themselves."
                                    + " Recent happenings: " + turn.playerMessage()
                                    + ". A few short lines only."
                                    + (turn.addressPlayer()
                                            ? " One of you may end by saying one short thing to "
                                                    + turn.ownerDisplayName()
                                                    + " — a question or a remark addressed to them."
                                            : "")
                                    + "]");
```

- [ ] **Step 4:** Same test command → PASS. Then `./gradlew test` → full suite green (compat constructors mean zero call-site churn).
- [ ] **Step 5:** `git add -A src/ && git commit -m "souls: GroupSceneTurn.addressPlayer + three banter directive variants"`

---

### Task 2: Director — solo roster + fire-time engagement coin

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirector.java` (firstVeto `:~300`, `botsCloseTogether:262-270`, `fireScene:187-204`)
- Test: `SoulBanterDirectorTest.java`

**Interfaces:**
- Consumes: Task 1's 8-arg `GroupSceneTurn`.
- Produces: `static boolean decideAddressPlayer(int rosterSize, RandomGenerator random)`.

- [ ] **Step 1: Failing tests.** In `SoulBanterDirectorTest`, update `firstVetoReportsGatesInSpecOrder`: the roster veto asserts now use size **0** (`assertEquals("roster", ...firstVeto(..., 0, true))`), and a size-1 roster with `botsCloseTogether=true` is eligible (`assertNull`). Keep the `bots-apart` assert at size 2 + false. Append:

```java
    @Test
    void soloRosterIsEligibleAndAlwaysAddressesThePlayer() {
        assertNull(SoulBanterDirector.firstVeto(true, true, true, true, true, true, true, 1, true),
                "one eligible bot is a valid banter roster since the engagement spec");
        Random random = new Random(3);
        for (int i = 0; i < 100; i++) {
            assertTrue(SoulBanterDirector.decideAddressPlayer(1, random),
                    "a solo scene's whole point is speaking to the player");
        }
    }

    @Test
    void groupScenesAddressThePlayerAboutOneTimeInThree() {
        Random random = new Random(9);
        int hits = 0;
        for (int i = 0; i < 3000; i++) {
            if (SoulBanterDirector.decideAddressPlayer(2, random)) {
                hits++;
            }
        }
        assertTrue(hits > 800 && hits < 1200, "expected ~1000/3000, got " + hits);
    }
```

- [ ] **Step 2:** `./gradlew test --tests '*SoulBanterDirectorTest*'` → FAIL.
- [ ] **Step 3: Implement.** In `SoulBanterDirector`:
  - `firstVeto`: `if (eligibleRosterSize < 2)` → `if (eligibleRosterSize < 1)`; update the Javadoc line for `roster` to "no eligible bot".
  - `botsCloseTogether`: `if (roster.size() < 2) { return false; }` → `if (roster.size() <= 1) { return roster.size() == 1; }` with comment `// A lone bot is trivially "together" — solo remarks (engagement spec §3) need no proximity pair.`
  - Add beside the other pure rules:

```java
    /** Fire-time engagement coin (engagement spec §3): solo scenes always speak to the player;
     *  group scenes get a closing player-addressed line about one time in three. Deterministic
     *  Frens logic — the model never decides whether the player is addressed. */
    static boolean decideAddressPlayer(int rosterSize, RandomGenerator random) {
        return rosterSize == 1 || random.nextInt(3) == 0;
    }
```

  - `fireScene`: `if (roster.size() < 2)` → `if (roster.size() < 1)` (reason string unchanged), and the turn construction becomes:

```java
        boolean addressPlayer = decideAddressPlayer(roster.size(), random);
        SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
                SoulGroupTypes.SceneKind.BANTER, playerId, player.getName().getString(),
                roster, seed, Instant.now(), routingId, addressPlayer);
```

  and the fired log line gains ` addressPlayer={}` with the flag.
- [ ] **Step 4:** `./gradlew test` → green.
- [ ] **Step 5:** `git commit -m "souls: banter roster-of-one + deterministic addressPlayer coin"`

---

### Task 3: Reply-window handoff + UI/command text

**Files:**
- Modify: `GroupScenePlayback.java` (`LineCommitter:63`, `SceneState:77-87`, `deliver` at `state.delivered++:265`, `finish:289`)
- Modify: `SoulRuntime.java:240-254` (committer override)
- Modify: `SoulLocalDirector.java` (new method + pure rule beside `noteSceneDelivered:326`)
- Modify: `BotSoulCommands.java` (banter toggle/status: local-off note), `BotControlScreen.java:45` (Banter chip text)
- Test: `SoulLocalDirectorTest.java`

**Interfaces:**
- Consumes: `turn.addressPlayer()` (Task 1).
- Produces: `LineCommitter.sceneDelivered(GroupSceneTurn turn, int deliveredLines, int lastSpeakerIndex)` (default no-op; −1 when nothing delivered); `SoulLocalDirector.noteEngagementDelivered(UUID playerId, UUID botId)`; `static boolean SoulLocalDirector.shouldOpenEngagementWindow(SoulGroupTypes.SceneKind kind, boolean addressPlayer, int deliveredLines, int lastSpeakerIndex)`.

- [ ] **Step 1: Failing tests.** Append to `SoulLocalDirectorTest`:

```java
    @Test
    void engagementWindowRuleOpensOnlyForDeliveredAddressPlayerBanter() {
        assertTrue(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, true, 1, 0));
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, false, 3, 1), "plain banter never opens");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, true, 0, -1), "zero deliveries never opens");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.BANTER, true, 2, -1), "no identified speaker, no window");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.LOCAL, true, 1, 0),
                "LOCAL scenes keep their own tracker path");
        assertFalse(SoulLocalDirector.shouldOpenEngagementWindow(
                SoulGroupTypes.SceneKind.PLAYER, true, 1, 0));
    }
```

- [ ] **Step 2:** `./gradlew test --tests '*SoulLocalDirectorTest*'` → FAIL.
- [ ] **Step 3: Implement.**
  - `GroupScenePlayback.LineCommitter`: change the default method to `default void sceneDelivered(SoulGroupTypes.GroupSceneTurn turn, int deliveredLines, int lastSpeakerIndex) { }`, Javadoc: fires exactly once per scene, unconditionally; `lastSpeakerIndex` is the roster index of the last DELIVERED line's speaker, −1 when nothing was delivered. (Safe signature change: one caller in `finish()`, one meaningful override in `SoulRuntime`; `SoulGroupConversationService` merely inherits the no-op.)
  - `SceneState`: add `int lastDeliveredParticipant = -1;`. In `deliver()`, beside `state.delivered++;`: `state.lastDeliveredParticipant = line.participantIndex();`
  - `finish()`: `committer.sceneDelivered(state.scene.turn(), state.delivered, state.lastDeliveredParticipant);`
  - `SoulLocalDirector`, beside `noteSceneDelivered`:

```java
    /**
     * Engagement handoff rule (engagement spec §5), pure: a BANTER scene the director marked
     * {@code addressPlayer} opens a reply window for its last delivered speaker. LOCAL scenes
     * are excluded — they flow through {@link #noteSceneDelivered}'s continuation tracker,
     * which deliberately fails closed for deliveries with no matching pending fire (and a
     * banter fire never registers one, which is why reusing that path can never work).
     */
    static boolean shouldOpenEngagementWindow(SoulGroupTypes.SceneKind kind,
                                               boolean addressPlayer, int deliveredLines,
                                               int lastSpeakerIndex) {
        return kind == SoulGroupTypes.SceneKind.BANTER && addressPlayer
                && deliveredLines > 0 && lastSpeakerIndex >= 0;
    }

    /** A banter scene spoke to the player — open a fresh window so their answer routes back. */
    void noteEngagementDelivered(UUID playerId, UUID botId) {
        if (playerId != null && botId != null) {
            openReplyWindow(playerId, botId, clock.getAsLong());
        }
    }
```

  - `SoulRuntime` committer override (replace whole method; keep the existing FIX-2 comment on the LOCAL branch):

```java
                        @Override
                        public void sceneDelivered(SoulGroupTypes.GroupSceneTurn turn,
                                                    int deliveredLines, int lastSpeakerIndex) {
                            SoulLocalDirector local = runtime.localDirector;
                            if (local == null || turn.roster().isEmpty()) {
                                return;
                            }
                            if (turn.kind() == SoulGroupTypes.SceneKind.LOCAL) {
                                // (existing comment) Always notify for LOCAL, even at zero
                                // deliveries — consumes the pending flag, never opens a window.
                                local.noteSceneDelivered(turn.ownerId(),
                                        turn.roster().get(0).botId(), deliveredLines);
                            } else if (SoulLocalDirector.shouldOpenEngagementWindow(turn.kind(),
                                    turn.addressPlayer(), deliveredLines, lastSpeakerIndex)) {
                                local.noteEngagementDelivered(turn.ownerId(),
                                        turn.roster().get(lastSpeakerIndex).botId());
                            }
                        }
```

  - `BotSoulCommands`: in `executeBanterToggle` (enable branch) and `executeBanterStatus`, append after `ambientSurfaceWarning()`: a note when `config != null && !config.isSoulLocalChatEnabled()`: `" Note: Local chat is OFF, so you can't answer companion remarks — /bot soul local on enables replies."` via a small `private static String replyRoutingNote()` helper.
  - `BotControlScreen.java:45` Banter chip text: replace `"(needs Soul Chat on and 2+ soul-bound bots nearby)"` with `"(needs Soul Chat on; with one soul-bound bot nearby it may speak to you, with 2+ they chat among themselves and may pull you in)"` — rest of the sentence unchanged.
- [ ] **Step 4:** `./gradlew test` → full suite green.
- [ ] **Step 5:** `git commit -m "souls: reply-window handoff for player-addressed banter + chip/command text"`

---

### Task 4: Docs, bump, build, deploy-gate

- [ ] **Step 1:** Bump `gradle.properties` → `mod_version=1.1.181-release+1.21.11`.
- [ ] **Step 2:** Changelog entry (newest-first): the two entry points, the E1–E3 decisions, why `noteEngagementDelivered` exists (tracker fails closed), the banter-ON/local-OFF one-way limitation and its command note, commit hashes, final test count, and the field-test checklist: solo bot + calm + quiet 90 s → a remark TO you within 4–8 min (first) / 8–15 min bands; answer inside 30 s → exactly one continuation; ignore it → window lapses silently; two-bot banter runs ~2/3 plain, ~1/3 ending addressed to you; ambient mutes still silence everything (`vetoed:muted`); `/bot soul banter status` shows the local-off note only when local chat is off.
- [ ] **Step 3:** Update `RALPH_TASK.md` handoff (engagement shipped pending field test; consolidation still next).
- [ ] **Step 4:** `./gradlew build -x test && ./gradlew test` — record exact count.
- [ ] **Step 5:** Pre-deploy check (`pgrep -f "net.minecraft.client.main.Main"`); if closed, deploy to all three Prism instances with the standard `rm -f`+`cp` pattern and verify via `javap` that the deployed jar contains `noteEngagementDelivered`. If running, STOP and report the artifact path.
- [ ] **Step 6:** Commit docs; merge branch to main; update auto-memory.
