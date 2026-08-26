# Soul Banter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Companions occasionally banter among themselves — autonomous BANTER-kind scenes triggered by a deterministic director, seeded from witnessed events + situation, delivered through the shipped PARTY playback with ambient-category gating.

**Architecture:** New `SoulBanterDirector` (tick-driven eligibility + two-phase async trigger) and `SoulBanterSeed` (pure seed builder) over the existing group-scene machinery. `GroupSceneTurn` gains `SceneKind`; assembler/validator/playback/service become kind-aware. No DM-pipeline changes; scheduler untouched.

**Tech Stack:** Java 21, Fabric 0.18.4 / MC 1.21.11, JUnit (baseline 463 green), local Ollama.

**Spec:** `docs/superpowers/specs/2026-08-26-frens-soul-banter-design.md` (parents: 2026-08-23 soul communication §Autonomous banter; 2026-08-25 group chat).

## Global Constraints

- Deterministic Frens logic schedules banter; the model only writes lines inside one accepted scene.
- Default OFF: `ManualConfig.soulBanterEnabled = false`; souls master still gates everything.
- BANTER delivery respects ambient text/voice category masks (re-read per line); PLAYER scenes keep the soul exemption unchanged.
- Cadence: post-fire cooldown randomized in [8, 15] min; initial grace [4, 8] min; danger-veto retry ≈ 2 min; quiet window 90 s.
- Audience: player within 24 blocks of the bots; bots within 12 blocks of each other; ≥2 eligible bots (group-chat filter reused); cap 4 roster bots.
- Banter scenes cap at 4 lines (per-bot 2 and 300-char line caps unchanged).
- Budget: `activeGenerations() == 0` and no active scene for the player; no generation when both ambient surfaces are muted.
- Banter failures are SILENT to the player (log-only) — an autonomous scene must never nag.
- souls package never references `Frens`; live config reads are injected lambdas (established pattern).
- Threading: director evaluation + capture + submit on the server thread; event fetches async with a server-thread hop back and re-check.
- Build per task (`./gradlew test --tests ...` then `./gradlew build -x test`); full `./gradlew build` before the wiring commit; commit per task (`souls:` prefix).

---

### Task 1: SceneKind + banter line cap

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypes.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupResponseValidator.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupTypesTest.java`, `SoulGroupResponseValidatorTest.java`

**Interfaces (produced):**
- `SoulGroupTypes.SceneKind { PLAYER, BANTER }`.
- `SoulGroupTypes.BANTER_MAX_SCENE_LINES = 4`.
- `GroupSceneTurn` gains leading component `SceneKind kind` → canonical shape `(kind, ownerId, ownerDisplayName, roster, playerMessage, acceptedAt, routingId)`; compat constructor with the old 6-arg shape defaults `kind = PLAYER` (router/service/tests stay source-stable).
- `SoulGroupResponseValidator.parse(String raw, List<String> rosterDisplayNames, int maxSceneLines)`; existing 2-arg overload delegates with `MAX_SCENE_LINES`.

- [ ] **Step 1: Failing tests.** `SoulGroupTypesTest`: compat ctor yields `kind() == PLAYER`; canonical ctor with `BANTER` keeps it; `kind` rejects null. `SoulGroupResponseValidatorTest`: 6 valid lines parsed with `maxSceneLines = 4` yields 4; 2-arg overload still yields 6.

```java
@Test
void compatConstructorDefaultsToPlayerKind() {
    SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
            UUID.randomUUID(), "Bradley", List.of(), "hi", Instant.EPOCH, UUID.randomUUID());
    assertEquals(SoulGroupTypes.SceneKind.PLAYER, turn.kind());
}

@Test
void banterKindCarriesThrough() {
    SoulGroupTypes.GroupSceneTurn turn = new SoulGroupTypes.GroupSceneTurn(
            SoulGroupTypes.SceneKind.BANTER, UUID.randomUUID(), "Bradley", List.of(),
            "[seed]", Instant.EPOCH, UUID.randomUUID());
    assertEquals(SoulGroupTypes.SceneKind.BANTER, turn.kind());
}

@Test
void banterSceneCapBindsAtFourLines() {
    List<String> roster = List.of("A", "B", "C", "D");
    StringBuilder raw = new StringBuilder();
    for (String name : roster) { raw.append(name).append(": one\n").append(name).append(": two\n"); }
    var parse = validator.parse(raw.toString(), roster, SoulGroupTypes.BANTER_MAX_SCENE_LINES);
    assertTrue(parse.accepted());
    assertEquals(4, parse.lines().size());
}
```

- [ ] **Step 2:** Run — expect compile failures (no `kind`, no 3-arg parse).
- [ ] **Step 3: Implement.** Record change + `Objects.requireNonNull(kind)`; validator threads `maxSceneLines` through the loop bound (replace the `MAX_SCENE_LINES` reference in the loop with the parameter).
- [ ] **Step 4:** `./gradlew test --tests '*SoulGroup*'` → PASS; `./gradlew build -x test` clean.
- [ ] **Step 5:** Commit `souls: SceneKind on GroupSceneTurn + parameterized scene-line cap (banter=4)`.

### Task 2: Banter seed builder

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterSeed.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulBanterSeedTest.java`

**Interfaces (produced):**

```java
public final class SoulBanterSeed {
    static final int MAX_EVENTS = 3;
    static final int MAX_SEED_CHARS = 400;
    /** eventsPerBot: recent journal tails, one list per roster bot (newest last, store order).
     *  situationSource: the first roster member's grounding. playerActivity: "" when unknown.
     *  random: injected for testability. */
    public static String build(List<SoulTypes.GroundingSnapshot> rosterGroundings,
                                List<List<SoulTypes.SoulEvent>> eventsPerBot,
                                String playerName, String playerActivity,
                                java.util.random.RandomGenerator random)
}
```

Rules: flatten events, sort HIGH→NORMAL→LOW then newest-first within tier; drop `DIRECT_CONVERSATION`; dedupe by `EventType`; pick up to 3 (mild randomness: when >3 candidates, the 3rd pick comes from a random index among the remainder). Phrase mapping via a `switch` on `EventType` (e.g. `TASK_COMPLETED` → "finished a task", `MOB_KILLED` → "slew a mob", `DEATH` → "died recently", `DIMENSION_CHANGED` → "travelled between worlds", `SLEEP` → "got some sleep", default: enum name lowercased with spaces), appending up to 2 fact values when present (`facts.values()` joined, 60-char cap per phrase). Situation line from `rosterGroundings.get(0)`: `"it is <timePhase>, <weather>, in <biome>"` (skip empties). Player line: `"<playerName> is <playerActivity>"` only when activity non-empty. Join with "; ", truncate to MAX_SEED_CHARS.

- [ ] **Step 1: Failing tests** — salience-first pick (1 HIGH + 3 NORMAL → HIGH included); `DIRECT_CONVERSATION` excluded; dedupe by type; ≤3 events; deterministic given a fixed `new java.util.Random(42)`; empty inputs → situation-only seed; 400-char bound with oversized fact values; player-activity line present/absent.
- [ ] **Step 2:** Verify failure.
- [ ] **Step 3:** Implement (pure; no Minecraft imports).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `souls: banter seed builder (salience-first events + situation, bounded, deterministic)`.

### Task 3: Assembler banter mode + [banter] history skip

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssembler.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupPromptAssemblerTest.java`

**Interfaces:**
- Constant `public static final String BANTER_HEARD_PREFIX = "[banter] "` (single source of truth; the service reuses it in Task 4).
- `assemble(...)` unchanged signature; branches on `turn.kind()`:
  - PLAYER: final USER message `owner + ": " + playerMessage` (as today).
  - BANTER: final USER message `"[A quiet moment. The companions chat briefly among themselves. Recent happenings: " + turn.playerMessage() + ". A few short lines only.]"`.
- `boundedHistory` skips HEARD records whose content starts with `BANTER_HEARD_PREFIX` (SPOKEN records unaffected).

- [ ] **Step 1: Failing tests** — BANTER turn's last message is USER, bracketed, contains the seed, and contains no `"Bradley: "` attribution; PLAYER turn unchanged (existing tests must stay green); history containing a `[banter] dusk` HEARD record + a SPOKEN line replays only the SPOKEN line; a plain HEARD record still replays.
- [ ] **Step 2:** Verify failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `./gradlew test --tests '*SoulGroupPromptAssembler*'` → PASS.
- [ ] **Step 5:** Commit `souls: assembler banter mode (narrator directive, [banter] HEARD skipped in replay)`.

### Task 4: Group service banter turns (tagged HEARD, silent failures, 4-line cap)

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationService.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulGroupConversationServiceTest.java`

**Interfaces:** `submit(GroupSceneTurn)` becomes kind-aware:
- HEARD content: PLAYER → `owner + ": " + msg` (unchanged); BANTER → `SoulGroupPromptAssembler.BANTER_HEARD_PREFIX + turn.playerMessage()`.
- Validator call: `validator.parse(text, rosterNames, turn.kind() == SceneKind.BANTER ? BANTER_MAX_SCENE_LINES : MAX_SCENE_LINES)`.
- Failures/busy-guard for BANTER: no `status.deliverStatus` call — log-only (`[souls] scene ... outcome=failed:<code> kind=BANTER`); PLAYER behavior byte-identical. Add `kind={}` to the scene log lines.

- [ ] **Step 1: Failing tests** — banter happy path: HEARD content equals `"[banter] dusk chatter seed"`; provider TIMEOUT on a banter turn → FAILURE record appended but `status.messages` stays empty; busy-guard on banter → FAILED with no status; player-turn failure still sends the plural status (existing tests must stay green); banter response with 5 valid lines → 4 handed to the player sink.
- [ ] **Step 2:** Verify failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run service tests → PASS.
- [ ] **Step 5:** Commit `souls: banter turns in group service (tagged HEARD, silent failures, tighter cap)`.

### Task 5: Playback kind gating + combat abort

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/GroupScenePlayback.java`
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/GroupScenePlaybackTest.java`

**Interfaces:**
- Constructors gain two `BooleanSupplier`s: `banterTextAllowed`, `banterVoiceAllowed` (existing 4/5-arg constructors delegate with `() -> true` so current call sites/tests compile; SoulRuntime passes real gates in Task 7).
- New pure helpers:

```java
/** Which surfaces a line may use. skip == neither surface open (BANTER only). */
record LineSurfaces(boolean text, boolean audio, boolean skip) {}
static LineSurfaces lineSurfaces(boolean banterKind, boolean textAllowed,
                                 boolean voiceAllowed, boolean audioPresent) {
    if (!banterKind) {
        return new LineSurfaces(true, audioPresent, false);
    }
    boolean text = textAllowed;
    boolean audio = voiceAllowed && audioPresent;
    return new LineSurfaces(text, audio, !text && !audio);
}
/** BANTER-only: any combat involving audience or current speaker cancels the scene. */
static boolean banterCombatAbort(boolean banterKind, boolean ownerInCombat, boolean speakerInCombat) {
    return banterKind && (ownerInCombat || speakerInCombat);
}
```

- Behavior wiring inside `advance`/`deliver`:
  - Synthesis start: for BANTER with `!banterVoiceAllowed.getAsBoolean()`, set `state.synth = CompletableFuture.completedFuture(Optional.empty())` instead of calling `synthesizeLine` (no wasted render).
  - Before `decideStep`: if `banterCombatAbort(kind==BANTER, ownerInCombat(owner), inCombat(speakerBot))` → `state.cancelled = true` (finish logs `cancelled`); combat probe = `entity != null && (entity.hurtTime > 0 || entity.getAttacker() != null)` (verify `hurtTime` usage against existing code — `grep -rn "hurtTime" src/main | head` shows the established access pattern; adapt if the codebase uses a different accessor).
  - In `deliver`: compute `LineSurfaces s = lineSurfaces(kind==BANTER, banterTextAllowed.getAsBoolean(), banterVoiceAllowed.getAsBoolean(), audio.isPresent())`; if `s.skip()` → log `outcome=skipped-muted` and `advanceToNextLine(state, now, 0L)` WITHOUT committing; else send text only when `s.text()`, audio only when `s.audio()`, commit as delivered, pacing from `s.audio() ? audio duration : beat`.

- [ ] **Step 1: Failing tests** — `lineSurfaces` truth table (player kind always text+audio-if-present; banter text-only, voice-only, both, neither→skip); `banterCombatAbort` table; existing pacing/step tests stay green (constructors delegate).
- [ ] **Step 2:** Verify failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `./gradlew test --tests '*GroupScenePlayback*'` → PASS; `./gradlew build -x test` clean.
- [ ] **Step 5:** Commit `souls: playback kind gating (ambient masks per line, no-render when voice muted) + banter combat abort`.

### Task 6: Banter director

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirector.java`
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulPlayerActivity.java` (chat note)
- Test: `src/test/java/net/wcfcarolina13/GameAI/souls/SoulBanterDirectorTest.java`

**Interfaces (produced):**

```java
public final class SoulBanterDirector {
    static final long EVAL_INTERVAL_TICKS = 100;          // 5s
    static final long QUIET_WINDOW_MS = 90_000L;
    static final long RETRY_AFTER_VETO_MS = 120_000L;     // danger/capture vetoes
    static final double AUDIENCE_RADIUS_BLOCKS = 24.0;
    static final double BOT_PROXIMITY_BLOCKS = 12.0;

    public SoulBanterDirector(SoulRuntime runtime, MinecraftServer server,
            BooleanSupplier banterEnabled, BooleanSupplier ambientTextOpen,
            BooleanSupplier ambientVoiceOpen,
            Function<MinecraftServer, List<ServerPlayerEntity>> botsProvider,
            LongSupplier clock, RandomGenerator random)

    /** Called from SoulRuntime.tickScenes every server tick; self-throttles to EVAL_INTERVAL_TICKS. */
    public void tick()
    /** For /bot soul banter status: last verdict + ms-to-next-eligible for this player. */
    public String statusFor(UUID playerId)
    public void notePlayerScene(UUID playerId)   // player-initiated scene re-arms the cooldown

    // Pure (unit-tested):
    static long initialDelayMs(RandomGenerator r)  // [4,8] min
    static long nextDelayMs(RandomGenerator r)     // [8,15] min
    /** First failed gate name, or null when eligible. Order fixed = spec §3. */
    static String firstVeto(boolean enabled, boolean pipelineAvailable, boolean cooldownElapsed,
            boolean budgetFree, boolean surfaceOpen, boolean playerAtEase, boolean quiet,
            int eligibleRosterSize, boolean botsCloseTogether)
    /** Post-capture danger veto over one grounding. */
    static boolean groundingDangerous(SoulTypes.SituationSnapshot s)
        // = !s.hostiles().isEmpty() || s.inCombat() || s.breakingFree() || s.surfaceRecoveryActive()
}
```

`SoulPlayerActivity` gains `static void noteChat(UUID playerId, long atEpochMs)` + `static long lastChatAt(UUID playerId)` (0 when unknown) backed by a second map; `clear()` clears both.

**Trigger flow (two-phase, per player, at most one pending attempt per player):**
- Phase A (server tick): compute `firstVeto` from cheap facts — enablement supplier, `runtime.pipelineAvailable()`, cooldown map, `SoulRuntime.activeGenerations() == 0 && !playback.hasActiveScene` (exposed as `runtime.isSceneBudgetFree(playerId)` — small runtime addition in Task 7), `ambientTextOpen || ambientVoiceOpen`, player at ease (`isAlive() && !isSleeping() && hurtTime == 0 && getAttacker() == null`), quiet (`now - SoulPlayerActivity.lastChatAt >= QUIET_WINDOW_MS`), roster from `SoulGroupRouter.eligibleRoster` over `botsProvider` candidates (nearest-first within AUDIENCE_RADIUS of the player) with the pairwise BOT_PROXIMITY check on the chosen ≥2. Record verdict for `statusFor`. On pass: mark pending, fetch `runtime.recentEventsForBanter(botId)` per roster bot (Task 7 facade over the DM store, window 12), combine.
- Phase B (`server.execute` on fetch completion): clear pending; re-check the cheap gates; capture per-bot grounding via `SoulSnapshotBuilder.capture(server, bot, player, LOCAL)` (a throw drops the bot); any `groundingDangerous` or fewer than 2 survivors → verdict `vetoed:danger`, retry at now + RETRY_AFTER_VETO_MS; else build seed (`SoulBanterSeed.build(...)`, playerActivity from `SoulPlayerActivity.recentAction`), mint routingId, submit `new GroupSceneTurn(BANTER, playerId, playerName, roster, seed, Instant.now(), routingId)` via `runtime.submitGroupTurn`, arm cooldown `now + nextDelayMs(random)`, log `[souls] banter player={} outcome=fired routingId={} roster={}`.
- Veto logging throttled: log only when the reason differs from the player's previous verdict.

- [ ] **Step 1: Failing tests** — `firstVeto` full truth table incl. ordering (disable two gates, assert the earlier one's name); delay bounds over 1000 samples with `new Random(7)` (min/max inside bands); `groundingDangerous` cases; `SoulPlayerActivity.noteChat/lastChatAt` roundtrip + clear.
- [ ] **Step 2:** Verify failure.
- [ ] **Step 3:** Implement (live tick path compiles against runtime facades added as stubs here if needed — see Task 7 notes; keep `runtime` calls behind small package-private methods so this task compiles: add to `SoulRuntime` in THIS task the two trivial facades `boolean isSceneBudgetFree(UUID ownerId)` and `CompletableFuture<List<SoulTypes.SoulEvent>> recentEventsForBanter(UUID botId)` delegating to existing fields).
- [ ] **Step 4:** `./gradlew test --tests '*SoulBanter*' --tests '*SoulPlayerActivity*'` → PASS; compile clean.
- [ ] **Step 5:** Commit `souls: banter director (two-phase deterministic trigger, veto telemetry) + chat quiet signal`.

### Task 7: Wiring — runtime, config, command, UI, chat hook

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/souls/SoulRuntime.java`
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java`
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotSoulCommands.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java`
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java`
- Test: `SoulRuntimeTest` additions where server-free

**Wiring:**
- `ManualConfig`: `private boolean soulBanterEnabled = false;` in the soul block + `isSoulBanterEnabled()/setSoulBanterEnabled(boolean)` accessors beside `isSoulPartyEnabled`.
- `SoulRuntime.start`: after `scenePlayback` is created, construct the director:

```java
runtime.banterDirector = new SoulBanterDirector(runtime, server,
        () -> { ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                return cfg != null && cfg.isSoulBanterEnabled(); },
        () -> net.wcfcarolina13.ChatUtils.TextLineVisibilityService.isTextAllowed(
                net.wcfcarolina13.ChatUtils.VoiceLineCategory.AMBIENT_CHATTER),
        () -> { ManualConfig cfg = net.wcfcarolina13.Frens.CONFIG;
                return cfg == null || (cfg.isVoicedDialogueEnabled()
                        && !cfg.isVoiceCategoryMuted(
                               net.wcfcarolina13.ChatUtils.VoiceLineCategory.AMBIENT_CHATTER.id())); },
        srv -> net.wcfcarolina13.GameAI.BotEventHandler.getRegisteredBots(srv),
        System::currentTimeMillis, new java.util.Random());
```

  (Verify the voice-mask accessor name with `grep -n "isVoiceCategoryMuted\|isMuted" ManualConfig.java VoiceLineMuteService.java` and use the real one; the mute check must mirror `VoiceLineMuteService.isMuted(AMBIENT_CHATTER, null)` semantics.)
  The same two ambient suppliers are passed to the `GroupScenePlayback` constructor (Task 5 params).
- `tickScenes(server)`: also `runtime.banterDirector.tick()` (null-safe).
- `submitGroupTurn`: on a PLAYER-kind turn, call `banterDirector.notePlayerScene(turn.ownerId())` (null-safe) so player scenes re-arm the banter cooldown.
- `shutdown`: nothing new (director holds no threads).
- New facades (if not already added in Task 6): `isSceneBudgetFree(UUID)` = `activeGenerations() == 0 && (scenePlayback == null || !scenePlayback.hasActiveScene(ownerId))`; `recentEventsForBanter(UUID botId)` = `store.recentEvents(botId, 12)`.
- `public String banterStatus(UUID playerId)` → director's `statusFor`, `"Banter director not running."` when null.
- `BotSoulCommands`: under `soul` add

```java
.then(CommandManager.literal("banter")
        .then(CommandManager.literal("on").executes(ctx -> executeBanterToggle(ctx, true)))
        .then(CommandManager.literal("off").executes(ctx -> executeBanterToggle(ctx, false)))
        .then(CommandManager.literal("status").executes(BotSoulCommands::executeBanterStatus)))
```

  `executeBanterToggle`: operator-only (mirror `executeSystem`'s permission check), sets `CONFIG.setSoulBanterEnabled(v)` + saves config the way the soul system toggle does, feedback line. `executeBanterStatus`: player-only, prints `isSoulBanterEnabled` + `runtime.banterStatus(actor.getUuid())`.
- `Frens` chat callback: at the top of the public-chat handling (same block that calls `tryHandleNearbyQuestAsk`), add `net.wcfcarolina13.GameAI.souls.SoulRuntime.notePlayerChat(sender);` — a new static facade on `SoulRuntime` calling `SoulPlayerActivity.noteChat(player.getUuid(), System.currentTimeMillis())` in a try/catch (mirror `notePlayerBlockBreak`).
- `BotControlScreen`: add a "Banter" global chip after Soul Voice — update all three wiring points together (GLOBAL_TOGGLES list, init loads, saveSettings writes; autosave on flip like the other chips; tooltip: "Companions occasionally chat among themselves. Ambient category masks apply."). Read the current toggle block first (`grep -n "Soul Voice\|GLOBAL_TOGGLES" BotControlScreen.java`) and follow it exactly.

- [ ] **Step 1: Failing test** — `SoulRuntimeTest`: `banterStatus` on a test-seam runtime returns the not-running string; `notePlayerChat` facade is safe with a null-config environment (no throw).
- [ ] **Step 2:** Verify failure.
- [ ] **Step 3:** Implement all five files.
- [ ] **Step 4:** FULL `./gradlew build` (whole suite green — the wiring regression gate).
- [ ] **Step 5:** Commit `souls: banter wiring — director in runtime, soulBanterEnabled + Banter chip, /bot soul banter, chat quiet hook`.

### Task 8: Docs, version bump, deploy

**Files:** `changelog.md`, `RALPH_TASK.md`, spec (as-implemented notes if any drift), `gradle.properties`.

- [ ] **Step 1:** Changelog entry (newest-first, reasoning + commits + field-test checklist: enable via chip/command, wait out the 4–8 min grace near 2 soul bots, watch `[souls] banter` verdicts via status command, ambient-mute matrix (text-only / voice-only / both-muted = no generation), combat interrupt, cooldown spacing, player scene re-arms cooldown, reset party mid-banter). Update RALPH_TASK.md handoff (banter shipped; next soul item = ambient/local chat, spec-gated; group chat + banter field tests both pending).
- [ ] **Step 2:** Bump `mod_version` → `1.1.177-release+1.21.11`; `./gradlew build`; verify `build/libs/frens-1.1.177-release+1.21.11.jar` exists and contains `SoulBanterDirector.class`.
- [ ] **Step 3:** Commit `souls: banter docs + changelog; bump 1.1.177`.
- [ ] **Step 4: Deploy** — run `pgrep -f "net.minecraft.client.main.Main"` first; if running STOP and report the artifact path. Else the standard three-instance deploy loop (rm `frens-*.jar`, cp), then verify the deployed jar with `unzip -l | grep SoulBanterDirector`.
- [ ] **Step 5:** Update auto-memory (soul pilot memory + MEMORY.md hook: banter shipped, next = ambient/local chat).

## Self-review notes

- Spec coverage: §3 director/eligibility → Task 6 (+ budget facades), §4 turn/seed/prompt → Tasks 1–4, §5 gating/abort → Task 5, §6 storage → no work (by design), §7 config/command/UI/telemetry → Tasks 6–7, §9 tests → per-task. No gaps.
- Kind-awareness threading check: `SceneKind` produced in Task 1, consumed by assembler (T3), service (T4), playback (T5), director (T6) — names consistent (`turn.kind()`, `SceneKind.BANTER`).
- Two verify-before-assume flags embedded where the plan touches APIs not re-read this session (`hurtTime`, voice-mask accessor, BotControlScreen toggle block) — resolve with the named greps before editing.
