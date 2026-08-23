# Frens Soul Situational Awareness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ground Jake's soul conversations in the hazards, hostiles, combat aftermath, survival struggles, and relationship history that Frens' deterministic services already compute, and give him short-term narrative memory of kills, self-rescues, hobbies, and hunt progress.

**Architecture:** A new immutable `SituationSnapshot` joins `GroundingSnapshot` as an additional component (existing 4-arg constructor preserved as a delegating convenience, so no existing call site or test changes). It is captured on the server thread in `SoulSnapshotBuilder` from verified public reads, rendered as a bounded SITUATION sub-block by `SoulPromptAssembler`, and four appended `EventType`s flow through the existing `SoulEventObserver` sink via one-line guarded hooks in the owning services. Zero new perception.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric 0.18.4, JUnit 5.11.4, Mockito (inline), Gradle/Fabric Loom.

**Spec:** [`docs/superpowers/specs/2026-08-23-frens-soul-situational-awareness-design.md`](../specs/2026-08-23-frens-soul-situational-awareness-design.md)

## Global Constraints

- Work happens on `feature/soul-situational-awareness`, created from `main` (which contains the merged soul pilot) via `superpowers:using-git-worktrees`.
- Every numbered Task touches at most five files including `changelog.md` and ends with verification plus a focused commit (subject given in the task; end commit messages with the `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` trailer).
- TDD per task: failing test first (RED evidence), then implement, then GREEN; `./gradlew test` and `./gradlew build -x test` exit 0 at every task gate.
- Feature-off behavior stays byte-for-byte unchanged: every new hook gates on `SoulRuntime.current()` + `isMasterEnabled()` + `hasActiveProfile` BEFORE any argument computation; no disk writes, no scans, no log lines when off.
- All Minecraft/world/entity reads happen on the server thread inside `SoulSnapshotBuilder.capture(...)` or existing server-thread callbacks; snapshot records contain strings, primitives, UUIDs, Instants, Optionals, and immutable collections only — never Minecraft objects.
- Records use defensive canonical constructors: `List.copyOf`, null Optionals → `Optional.empty()`, null strings → `""`.
- Never read the legacy `BotEventHandler.currentState` / `getCurrentState()` singleton or the static `BotEventHandler.bot` field — always the per-bot calls named below.
- Event facts are short category strings only — never free text, prompt text, coordinates finer than coarse, or entity references. `EventType` values are APPENDED to the enum, never reordered.
- Prompt bounds unchanged (20 turns / 12 events / 12,000 / 4,000 chars; `maxOutputTokens=220`; `num_ctx=8192`). The SITUATION block is capped at 800 characters, hostiles at 5.
- Soul classes log via `LoggerFactory.getLogger("frens.souls")`, never `Frens.LOGGER` (its static init throws off-game). Mockito/ByteBuddy on Java 21 is centrally handled in build.gradle — no per-class static-block workarounds.
- If any named external method/field is missing or has a different signature than stated, STOP the task and report the mismatch — do not improvise.

## Verified source inventory (controller-verified against merged main, 2026-08-23)

- `BotEventHandler.createInitialState(ServerPlayerEntity)` → `State` — BotEventHandler.java:7610
- `BotEventHandler.getCurrentMode(ServerPlayerEntity)` → `Mode` — BotEventHandler.java:2275 (enum at :274)
- `State` getters — State.java:96-113: `getDistanceToDangerZone()`, `getDistanceToHostileEntity()`, `isEnclosed()`, `hasHeadroom()`, `hasEscapeRoute()`, `getNearbyEntities()` → `List<EntityDetails>`
- `EntityDetails` — Entity/EntityDetails.java:42-47: `getName()`, `getX/Y/Z()`, `isHostile()`, `getDirectionToBot()`
- `BotCombatCalloutService` — :564 `isInPostCombatLingerWindow(ServerPlayerEntity)`, :577 `noteKillPosition(UUID, Vec3d)`, :588 `getRecentKillPositions(UUID)`, :1056 `isInCombat(UUID)`
- `BotFleeService` — :143 `isInShelter(UUID)`, :331 `isSurfaceRecoveryActive(UUID)`, :1905 `isBreakingFree(UUID)`
- `BotAutoReturnSunsetService.isNightTravelSessionActive(UUID)` — :139
- `ManualConfig.SurvivalRecruitmentState` — :1592 `getRecruitedAtEpochMs()`, :1857 `getCompanionDeathCount()` (reached via `SurvivalRecruitmentService.getState(server)`, alias-gated exactly like the pilot's recruitment reads in SoulSnapshotBuilder)
- `MountPersistenceService.getRecordedState(ServerPlayerEntity)` → `MountState` record — :230 / :471
- `BotHomeService.listBases(MinecraftServer, ServerWorld)` → `List<BaseEntry>` — :1343; `getLastSleep(ServerPlayerEntity)` → `Optional<BlockPos>` — :277
- `HuntSessionService.getSession(UUID)` → `HuntSession` record (has `killsCompleted`, `killsTarget`, `targetIds`) — :157 / :40
- `BotIdleHobbiesService.getLastHobbyName(UUID)` — :114; internal `LAST_HOBBY`/`LAST_HOBBY_END_MS` maps at :83-84 (their writer is the HOBBY_SESSION hook site)

---

### Task 1: Situation types

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulTypes.java`
- Create: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulSituationTypesTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: existing `SoulTypes.GroundingSnapshot(Reachability, BotSnapshot, Optional<PlayerSnapshot>, Instant)`.
- Produces: `SituationSnapshot` (+ nested `HostileSighting`, `MountSummary`, `HuntSummary`), `SituationSnapshot.empty()`, and a 5-component `GroundingSnapshot` canonical constructor `(reachability, bot, player, situation, capturedAt)` with the old 4-arg form preserved as a delegating convenience constructor. Every later task builds on these exact shapes.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void groundingSnapshotFourArgConstructorDefaultsToEmptySituation() {
    SoulTypes.GroundingSnapshot g = new SoulTypes.GroundingSnapshot(
            SoulTypes.Reachability.LOCAL, botFixture(), Optional.empty(), Instant.EPOCH);
    assertEquals(SoulTypes.SituationSnapshot.empty(), g.situation());
}

@Test
void situationSnapshotDefensivelyCopiesAndNormalizes() {
    List<SoulTypes.HostileSighting> hostiles = new ArrayList<>();
    hostiles.add(new SoulTypes.HostileSighting("zombie", "north", 6));
    SoulTypes.SituationSnapshot s = new SoulTypes.SituationSnapshot(
            8, hostiles, true, false, true, "GUARD",
            true, false, 2, false, false, false, false,
            14, 1, Optional.empty(), 3, null, null, Optional.of("fishing"));
    hostiles.clear();
    assertEquals(1, s.hostiles().size());
    assertThrows(UnsupportedOperationException.class,
            () -> s.hostiles().add(new SoulTypes.HostileSighting("x", "s", 1)));
    assertEquals(Optional.empty(), s.lastSleepLabel());
    assertEquals(Optional.empty(), s.hunt());
}
```

(`botFixture()` is a local helper constructing the existing 28-arg `BotSnapshot` exactly as `SoulPromptAssemblerTest` does — copy that fixture.)

- [ ] **Step 2: RED** — `./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulSituationTypesTest` (expect compile failure: types missing).

- [ ] **Step 3: Implement the records in `SoulTypes`**

```java
public record HostileSighting(String name, String direction, int distanceBlocks) {
    public HostileSighting {
        name = name == null ? "" : name;
        direction = direction == null ? "" : direction;
    }
}
public record MountSummary(String type, float health, float maxHealth, boolean saddled) {
    public MountSummary { type = type == null ? "" : type; }
}
public record HuntSummary(String target, int kills, int goal) {
    public HuntSummary { target = target == null ? "" : target; }
}
public record SituationSnapshot(
        int dangerDistance,                 // blocks to nearest lava/cliff hazard; -1 = none detected
        List<HostileSighting> hostiles,     // nearest-first, at most 5
        boolean enclosed, boolean hasHeadroom, boolean hasEscapeRoute,
        String behaviorMode,                // Mode.name(), e.g. "GUARD"
        boolean inCombat, boolean postCombatLinger, int recentKillCount,
        boolean inShelter, boolean surfaceRecoveryActive, boolean breakingFree,
        boolean nightTravelActive,
        int companionDays,                  // -1 = unknown
        int deathCount,                     // -1 = unknown
        Optional<MountSummary> mount,
        int knownBaseCount,
        Optional<String> lastSleepLabel,
        Optional<HuntSummary> hunt,
        Optional<String> lastHobby) {
    public SituationSnapshot {
        hostiles = hostiles == null ? List.of() : List.copyOf(hostiles);
        behaviorMode = behaviorMode == null ? "" : behaviorMode;
        mount = mount == null ? Optional.empty() : mount;
        lastSleepLabel = lastSleepLabel == null ? Optional.empty() : lastSleepLabel;
        hunt = hunt == null ? Optional.empty() : hunt;
        lastHobby = lastHobby == null ? Optional.empty() : lastHobby;
    }
    public static SituationSnapshot empty() {
        return new SituationSnapshot(-1, List.of(), false, false, false, "",
                false, false, 0, false, false, false, false,
                -1, -1, Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
```

Change `GroundingSnapshot` to `(Reachability reachability, BotSnapshot bot, Optional<PlayerSnapshot> player, SituationSnapshot situation, Instant capturedAt)`; keep its existing null-handling; add the convenience constructor:

```java
public GroundingSnapshot(Reachability reachability, BotSnapshot bot,
                         Optional<PlayerSnapshot> player, Instant capturedAt) {
    this(reachability, bot, player, SituationSnapshot.empty(), capturedAt);
}
```

In the canonical constructor, `situation == null` → `SituationSnapshot.empty()`.

- [ ] **Step 4: GREEN** — focused test, then full `./gradlew test` (existing 4-arg call sites in tests/production must compile untouched), then `./gradlew build -x test`.

- [ ] **Step 5: Changelog + commit** — subject `Add soul situation types`. Files: the three listed.

### Task 2: Server-thread situation capture

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulSnapshotBuilder.java`
- Modify: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroundingTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: Task 1's records; the verified source inventory above.
- Produces: `capture(...)` now populates `GroundingSnapshot.situation`; package-private pure seam `static SoulTypes.SituationSnapshot buildSituation(SituationInputs inputs)` where `SituationInputs` is a package-private record of plain values (no Minecraft types) declared in `SoulSnapshotBuilder`.

- [ ] **Step 1: Write failing pure-seam tests** in `SoulGroundingTest`: hostiles are sorted nearest-first and capped at 5 (feed 7, assert 5 and ordering); non-hostile entities are excluded; `dangerDistance` passthrough with -1 default; `companionDays` computed from a recruitedAtEpochMs fixture (assert whole-day floor); every absent Optional input yields the `empty()`-equivalent field. Construct inputs via the `SituationInputs` record directly.

- [ ] **Step 2: RED** — `./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulGroundingTest`.

- [ ] **Step 3: Implement.** `SituationInputs` carries: `double dangerDistance` (from `state.getDistanceToDangerZone()`, ≤0 → -1), `List<RawEntity>`(name, hostile, dx, dy, dz, direction) from `state.getNearbyEntities()`, the three enclosure booleans, mode name, the combat/survival booleans and kill count, `long recruitedAtEpochMs` (0 = unknown) + `int deathCount` (-1 = unknown) + `long nowEpochMs`, mount/base/sleep/hunt/hobby values. `buildSituation` is pure: filters hostile, computes `distanceBlocks = (int) Math.round(sqrt(dx²+dy²+dz²))`, sorts ascending, caps 5, floors `companionDays = (now - recruitedAt) / 86_400_000`. The live gathering happens in a new private `captureSituation(server, bot)` called from `capture(...)`, reading exactly the inventory methods (alias-gate the recruitment fields the same way the existing `captureBot` gates recruitment; wrap each source group in the same defensive try/catch style the file already uses; absent source → default). `capture(...)` passes the situation into the 5-arg `GroundingSnapshot`; the existing package-private `assemble(...)` test seam gains a 5-arg overload, 4-arg kept delegating with `empty()`.

- [ ] **Step 4: GREEN** — focused, full suite, build.

- [ ] **Step 5: Changelog + commit** — subject `Capture Jake's live situation`.

### Task 3: SITUATION prompt rendering

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssembler.java`
- Modify: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulPromptAssemblerTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: `GroundingSnapshot.situation()`.
- Produces: a `SITUATION` sub-block appended inside the existing authoritative-state system message; nothing else in message order/bounds changes.

- [ ] **Step 1: Write failing tests**

```java
@Test
void situationBlockRendersInsideAuthoritativeStateInPriorityOrder() {
    SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(
            6, List.of(new SoulTypes.HostileSighting("zombie", "northeast", 7)),
            false, true, true, "GUARD", false, true, 2,
            false, false, false, false, 14, 1,
            Optional.of(new SoulTypes.MountSummary("horse", 11.0F, 22.0F, true)),
            3, Optional.of("Workshop"), Optional.empty(), Optional.of("fishing"));
    SoulTypes.ProviderRequest request = assembler.assemble(
            UUID.randomUUID(), "local-model", profile,
            new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot,
                    Optional.of(localPlayer), situation, Instant.EPOCH),
            List.of(), List.of(), "How are we doing?", Duration.ofSeconds(60));
    String state = authoritativeStateMessage(request); // helper: find the system msg containing "SITUATION"
    assertTrue(state.contains("SITUATION"));
    assertTrue(state.indexOf("zombie") < state.indexOf("GUARD"));      // hazards before mode
    assertTrue(state.indexOf("GUARD") < state.indexOf("companion"));   // mode before relationship
    assertTrue(state.contains("northeast"));
}

@Test
void situationBlockIsCappedAt800CharsDroppingLowestPriorityFirst() {
    // build a situation whose full render exceeds 800 chars (long hobby/base labels),
    // assert rendered block <= 800 chars and still contains the hostiles line
    // while the lastHobby line is gone.
}

@Test
void emptySituationRendersNoSituationBlock() {
    // 4-arg GroundingSnapshot fixture -> assert authoritative state does NOT contain "SITUATION"
}
```

Also assert an existing REMOTE test still passes with a populated situation: Jake's situation lines present, `playerBiomeSecret` still absent.

- [ ] **Step 2: RED.** — `./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulPromptAssemblerTest`

- [ ] **Step 3: Implement.** Render lines in this fixed priority order, skipping default-valued fields: (1) hazard distance + hostiles ("Hostiles nearby: zombie 7 blocks northeast") + enclosure, (2) combat ("in combat" / "just finished a fight; N recent kills"), (3) survival flags, (4) behavior mode, (5) relationship ("companion for N days; died N times"), (6) logistics (mount, bases, last sleep label, hunt progress, last hobby). Assemble greedily in priority order into the 800-char budget; a line that does not fit is dropped along with everything lower-priority. `empty()` situation → no block. The block is appended to the existing authoritative-state builder output — same system message, never the system contract.

- [ ] **Step 4: GREEN** — focused, full suite, build.

- [ ] **Step 5: Changelog + commit** — subject `Render Jake's situation in prompts`.

### Task 4: New event types and observer entry points

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulTypes.java` (append enum values only)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulEventObserver.java`
- Modify: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulEventObserverTest.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: existing `EventSink`, gating helpers, and `emit(...)` plumbing in `SoulEventObserver`.
- Produces: `EventType.MOB_KILLED, SELF_RESCUE, HOBBY_SESSION, HUNT_PROGRESS` (appended, in this order, at the END of the enum); public static hooks `onMobKilled(ServerPlayerEntity bot, String mobType)`, `onSelfRescue(ServerPlayerEntity bot, String kind)`, `onHobbySession(UUID botId, String hobbyName)`, `onHuntProgress(UUID botId, String target, int kills, int goal)`; package-private data-only instance methods `noteMobKilled`, `noteSelfRescue`, `noteHobbySession`, `noteHuntProgress` mirroring the file's existing pattern.

- [ ] **Step 1: Write failing tests** using the existing `CapturingSink` pattern: each `note*` emits exactly one event of the right type with the right salience (`MOB_KILLED`/`HUNT_PROGRESS` → NORMAL, `SELF_RESCUE` → HIGH, `HOBBY_SESSION` → LOW) and string-only facts (`mob`, `kind`, `hobby`, `target`/`kills`/`goal`); a sink whose `accepts` returns false emits nothing; facts never contain nulls.

- [ ] **Step 2: RED.** — `./gradlew test --tests net.wcfcarolina13.GameAI.souls.SoulEventObserverTest`

- [ ] **Step 3: Implement.** Append the four enum values. Static hooks follow the file's established gate-first shape — the FIRST statement checks `SoulRuntime.current()` + `isMasterEnabled()` + (for entity overloads) computes nothing before the gate, exactly like the damage hooks after the pilot's final-review fix; then delegate to the instance methods, which build the immutable `SoulEvent` via the existing helper with `Witness.SELF`, coarse dimension/biome where available (entity overloads read them post-gate on the calling server thread; UUID-only overloads omit them as `""`).

- [ ] **Step 4: GREEN** — focused, full suite, build.

- [ ] **Step 5: Changelog + commit** — subject `Journal kills rescues hobbies and hunts`.

### Task 5: Service notification hooks

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotCombatCalloutService.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotIdleHobbiesService.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/HuntSessionService.java`
- Modify: `changelog.md`

**Interfaces:**
- Consumes: Task 4's static hooks (exact signatures above).
- Produces: one guarded call per transition; no other behavior change in any service.

- [ ] **Step 1: Locate each hook site by reading the current code (verify before editing; STOP on mismatch):**
  - `BotCombatCalloutService.noteKillPosition(UUID, Vec3d)` (:577): grep its callers; place `SoulEventObserver.onMobKilled(bot, mobType)` at the caller where the killed entity is in scope (mob type from the entity's type/name); if no caller has the entity in scope, hook inside `noteKillPosition` itself with `mobType = "hostile"` and note it in the report.
  - `BotFleeService`: the success/completion points of surface recovery and break-free (the code paths that clear `isSurfaceRecoveryActive` / `isBreakingFree` state after a successful escape — read the surrounding methods; hook only the success transitions, kind `"surface-recovery"` / `"break-free"`).
  - `BotIdleHobbiesService`: the writer of `LAST_HOBBY` / `LAST_HOBBY_END_MS` (:83-84) — hook where a finished session records its name.
  - `HuntSessionService`: the kill-increment path (where `killsCompleted` advances) — hook with target (first of `targetIds` or the display name the service already logs), new `killsCompleted`, `killsTarget`.
- [ ] **Step 2: Add the calls.** Each is one line plus at most one line to obtain an in-scope value; never wrap in try/catch (the hooks are gate-first and non-throwing by Task 4's contract); never reorder existing logic.
- [ ] **Step 3: Verify.** `./gradlew test` (Task 4's tests cover the policy; these hooks are compile-verified — the services have no unit harness, consistent with the repo), `./gradlew build -x test`. Re-run the pilot's static checks over the souls package (`rg -n "FunctionCallerV2|withPermissions|LLMServiceHandler|LLMOrchestrator|MemoryStore" src/main/java/net/wcfcarolina13/GameAI/souls` — expect only the known Javadoc line; credential grep expect zero).
- [ ] **Step 4: Changelog + commit** — subject `Hook awareness events into services`.

### Task 6: Runbook, verification, and gate

**Files:**
- Modify: `docs/testing/SOUL_COMMUNICATION_PILOT.md`
- Modify: `changelog.md`

- [ ] **Step 1:** Add a "Situational awareness (manual)" section: Jake names a visible hostile and its direction when asked "what's around?"; a REMOTE DM still carries Jake's own situation but no player surroundings; a kill made before a conversation surfaces when asked "what have you been up to?"; behavior mode is described truthfully under `/bot stay` vs follow; feature-off baseline unchanged (re-run the existing disabled-baseline case including its disk assertion).
- [ ] **Step 2:** Full `./gradlew test` + `./gradlew build -x test`; record counts in the report.
- [ ] **Step 3: Changelog + commit** — subject `Document situational awareness acceptance`.

**Final gate:** present the branch summary and commit list; do not merge, bump `mod_version`, or deploy until manual acceptance passes and the user confirms the game is closed.

---

## Execution Notes

- Tasks 1→2→3 are sequential (types → capture → rendering). Task 4 depends on Task 1 only; Task 5 depends on Task 4; Task 6 last. Execute sequentially — no parallel implementers.
- The observer hooks in Task 5 fire from whatever thread the owning service runs on (some are worker threads); Task 4's hooks must therefore stay data-only after the gate, matching the observer's documented mixed-thread contract.
- If `getRecentKillPositions` proves to be position-only with no mob types anywhere in scope (Task 5 fallback), `recentKillCount` in Task 2 still comes from its size — the two uses are independent.
