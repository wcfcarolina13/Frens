# Frens soul situational awareness

Date: 2026-08-23
Status: Approved design — implementation plan pending
Builds on: [`2026-08-23-frens-soul-communication-design.md`](2026-08-23-frens-soul-communication-design.md) (the pilot, implemented on `feature/soul-communication`)

## Purpose

The soul pilot grounded Jake in a static status card: position, health, inventory pressure, task
name. This feature grounds him in his **situation** — the hazards, hostiles, terrain, combat
aftermath, survival struggles, and relationship history that Frens' deterministic services already
compute every tick — and gives him **short-term narrative memory** of witnessed happenings (kills,
self-rescues, hobbies, hunt progress) through the existing event journal.

Design constraint, per Bradley: ground exclusively in the deterministic awareness already baked
into Frens. This feature builds **zero new perception**; it reads state that existing, battle-
tested services maintain, and adds notification hooks in the same style the pilot added to
`TaskService`.

Evidence base: the deterministic-awareness survey of 2026-08-23 (all `GameAI/services/`,
`DangerZoneDetector/`, skills, `BotEventHandler`), which identified the sources below as fresh,
cheap, and publicly accessible, and flagged the legacy `BotEventHandler.currentState` singleton as
multi-bot-unsafe (never read it; always use the per-bot calls).

## Scope

### Goals

- A new immutable `SituationSnapshot` captured per accepted turn, rendered as a bounded SITUATION
  block in the prompt's authoritative-state section.
- Four new factual event types journaled through the existing observer/sink/storage pipeline:
  `MOB_KILLED`, `SELF_RESCUE`, `HOBBY_SESSION`, `HUNT_PROGRESS`.
- Full behavior-mode fidelity (IDLE / FOLLOW / GUARD / PATROL / STAY / RETURNING_BASE /
  TRAVELING) replacing the pilot's following/idle binary.

### Non-goals

- No player-activity watching (`CommanderActivityService`) — deferred as its own follow-up.
- No crafting-history, RL/Q-state, arrow-tracking, or pathfinding-failure grounding.
- No new perception, scanning, or world systems; no changes to storage format, prompt bounds,
  reachability, authorization, delivery, or provider behavior.
- No unprompted speech — events surface only when a conversation happens (banter remains a
  separate future project).

## Design

### 1. SituationSnapshot — a fourth grounding component

`SoulTypes.GroundingSnapshot` gains a `SituationSnapshot situation` component (canonical
constructor defaults null to an empty situation). `BotSnapshot`'s 28-component record is **not**
modified — every existing signature, fixture, and test stands.

`SoulTypes.SituationSnapshot` is an immutable record (strings, primitives, Optionals, immutable
lists only — never Minecraft objects) carrying:

| Group | Fields | Source (all existing, public) |
|---|---|---|
| Hazards & surroundings | dangerDistance; up to 5 `HostileSighting(name, direction, distanceBlocks)`; enclosure summary (enclosed / headroom / escape route) | `BotEventHandler.createInitialState(bot)` — per-bot, on-demand; wraps `DangerZoneDetector.detectDangerZone`, the 10-block entity scan, and `BotStuckService.analyzeEnvironment` |
| Behavior | behaviorMode (full mode name) | `BotEventHandler.getCurrentMode(bot)` |
| Combat aftermath | inCombat, postCombatLinger, recentKillCount | `BotCombatCalloutService.isInCombat / isInPostCombatLingerWindow / getRecentKillPositions(...).size()` |
| Survival context | inShelter, surfaceRecoveryActive, breakingFree, nightTravelActive | `BotFleeService` + `BotAutoReturnSunsetService` flag reads |
| Relationship | companionDays (from recruitedAtEpochMs), deathCount | `SurvivalRecruitmentService.getState(server)` fields the pilot left unread |
| Logistics | `Optional<MountSummary(type, health, maxHealth, saddled)>`; knownBaseCount; `Optional<String> lastSleepLabel` (the base label nearest the last sleep position, else empty — never raw coordinates); `Optional<HuntSummary(target, kills, goal)>`; `Optional<String> lastHobby` | `MountPersistenceService.getRecordedState`, `BotHomeService.listBases`/`getLastSleep`, `HuntSessionService.getSession`, `BotIdleHobbiesService.getLastHobbyName` |

Capture rules (inherited from the pilot, unchanged): all reads happen inside
`SoulSnapshotBuilder.capture()` on the server thread; every value is copied into immutable data;
absent/unavailable sources degrade to empty defaults, never to exceptions. The
`createInitialState` entity scan runs once per accepted turn (human-paced), bounded to its
existing 10-block radius.

**Reachability:** the situation describes *Jake's* surroundings, so it is included for both LOCAL
and REMOTE turns. The pilot's REMOTE rule is unchanged: player-side facts stay omitted, and the
prompt continues to state that Jake cannot see the player's environment.

### 2. Prompt rendering

`SoulPromptAssembler` renders the situation as a `SITUATION` sub-block inside the existing
authoritative-state system message (never inside the system contract; never sourced from player
text). Deterministic field order; hostiles capped at 5; whole block capped at 800 characters with
lowest-priority lines dropped first (priority order: hazards/hostiles → combat → survival flags →
behavior mode → relationship → logistics). Existing history/event bounds (20 turns / 12 events /
12,000 / 4,000 chars) and `maxOutputTokens=220` are unchanged; the pinned `num_ctx=8192` has
ample headroom for the addition.

### 3. Event enrichment

Four `SoulTypes.EventType` values are **appended** to the enum: `MOB_KILLED` (salience NORMAL),
`SELF_RESCUE` (HIGH), `HOBBY_SESSION` (LOW), `HUNT_PROGRESS` (NORMAL). Each is emitted by a
one-line notification hook in the owning service — the same pattern Task 11 established for
`TaskService`:

- `MOB_KILLED` — from `BotCombatCalloutService`'s kill-recording path; facts: mob type, coarse
  location. Debounced per existing combat-window state (no per-swing spam).
- `SELF_RESCUE` — on successful completion of `BotFleeService` surface recovery or break-free;
  facts: kind (surface-recovery | break-free).
- `HOBBY_SESSION` — on `BotIdleHobbiesService` session end; facts: hobby name.
- `HUNT_PROGRESS` — on `HuntSessionService` kill increment; facts: target, kills, goal.

All hooks route through `SoulEventObserver`'s production sink and inherit its gates: runtime
present, **master switch on**, active profile bound. Feature-off behavior remains byte-for-byte
unchanged — the hooks are cheap guarded calls, and the guard runs before any argument
computation (the lesson from the pilot's final review). Facts are short category strings only;
never free text, prompt text, or entity references.

### 4. Small accessors

Where a source lacks a clean read (e.g. a kill-completion or rescue-completion signal that is
currently internal), the owning service gains a minimal package-appropriate accessor or a
notification call site — never exposure of mutable internals. The implementation plan enumerates
each; any listed method found missing or mismatched stops the task for verification, per the
pilot's convention.

## Testing

- Record + rendering: fixture-driven unit tests for `SituationSnapshot` defaults/immutability and
  the SITUATION block (field order, 5-hostile cap, 800-char cap with priority-order drops, REMOTE
  turns keep Jake's situation while still omitting player-side facts).
- Hooks: policy tests on `SoulEventObserver`'s data-only seams for each new event type, including
  master-off/unbound no-op and debounce behavior.
- Capture: the pure `assemble(...)` seam is unit-tested; live `capture()` additions are verified
  in-game (established harness limitation: Minecraft types are unmockable).
- Gates: focused tests red→green per task, full `./gradlew test` and `./gradlew build -x test`
  green at every task boundary; runbook gains a short situational-awareness section (Jake names a
  visible hostile; remote DM keeps his situation but not the player's; a kill surfaces in a later
  conversation; feature-off disk/log check still clean).

## Compatibility and rollback

- Feature-gated by the existing master switch; no new config fields.
- `EventType` additions are append-only and forward-compatible. A **downgrade** to a pre-feature
  build that reads an `events.jsonl` containing new types would fail that record's parse visibly
  (the pilot's corrupt-tail/report machinery) rather than corrupt anything; the versioned
  `frens/souls/v1` tree and reset/archive remain the recovery path. Accepted for a pilot-stage
  feature.
- Rollback is the master switch, as before; no save migration in either direction.

## Implementation gate

This document is the design contract, not authorization to write runtime code. Implementation
follows an approved file-by-file plan (superpowers:writing-plans), executed with the same
task/review/gate discipline as the pilot, on a branch cut from `main` **after**
`feature/soul-communication` is merged.
