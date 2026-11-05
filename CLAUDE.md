# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Minecraft Fabric mod ("Frens") that adds intelligent NPC bot companions with autonomous survival skills, persistent inventory/stats, and personality-driven behaviors. Fork of [shasankp000/AI-Player](https://github.com/shasankp000/AI-player). Java 21, Minecraft 1.21.11, Fabric 0.18.4.

## Build Commands

```bash
JAVA_HOME=<your JDK 21> ./gradlew build   # JDK 21 required (JDK 25 breaks Gradle 8.14.2)
```

JUnit suite under `src/test/java` (3857 tests as of 2026-11-04, pure-logic policy/director/pathfinding tests; run with `./gradlew test`, or `./gradlew build` which includes it). CI runs `./gradlew build -x test` and then `./gradlew test` as a separate step (test reports are uploaded when it fails). Gameplay behaviour is verified manually in-game.

**Test policy (hard rules, learned 2026-09-04):** no `net.minecraft.*` types in test code — extract the decision as a static pure helper over ints/strings/records and test that (`*Policy.java` convention: `TravelWaitPolicy`, `BundleReachPolicy`, `ScaffoldSlotPolicy`, `FuelOffloadPolicy`). `ManualConfig` is built in tests via its private constructor with reflection (see `SoulFoundationTest.newRealConfig()`); never call `save()`/`load()`. Classes whose static init touches `Blocks`/`SoundEvents` (e.g. `BotFleeService`) throw `ExceptionInInitializerError` under JUnit — put the pure helper in a separate class (`PlayerUtils/TickFreshness` is the precedent). If `./gradlew test` reports UP-TO-DATE, use `./gradlew cleanTest test`; stale incremental classes after `build -x test` are fixed by `./gradlew clean build`.

**Parallel agents in this one worktree** (the 2026-09-04 release loop ran 3–5 at once): give each agent a disjoint file set and tell it to stage only its own paths (`git add <paths>`, never `-A`); sequence any two that share a file. Concurrent gradle runs race on `build/` (`remapJar NoSuchFileException`, `cleanTest` XML write failures, transient `compileTestJava` errors from another agent's in-flight file) — rerun once when the tree is quiet; the controller does one `./gradlew clean build` before release.

JAR output: `build/libs/frens-*.jar`

## Deploying

Never overwrite a JAR in a running game's `mods/` folder: the game crashes later with `ZipException`. Bump `mod_version` in `gradle.properties` for each deployed build. Cloud sessions don't deploy. On the Mac, the deploy steps are in `CLAUDE.local.md`.

## Repository Conventions

- **`changelog.md`** — History and reasoning log. Add a concise entry after code changes.
- **`RALPH_TASK.md`** — Current session handoff (top) + project backlog (below, including "Recovered deferrals"). Single source of truth for all pending work. Name kept from the retired Ralph loop.
- **`docs/GUARDRAILS.md`** — Lessons learned ("Signs") from past failures. Read before acting.
- **`docs/handoffs/ARCHIVE_2026.md`** — Superseded handoffs, old session notes and the Ralph loop log. Grep it, don't read it whole.

### Agent Workflow

1. Read this file. Then read only the **Session Handoff** section at the top of `RALPH_TASK.md`
   (`sed -n '1,120p' RALPH_TASK.md`) — the file is ~73 KB and the backlog below the handoff is a
   grep target, not a read. `grep -n '^\- \[ \]' RALPH_TASK.md` lists open criteria.
2. Check git status/last commits to avoid redoing completed work.
3. Work on exactly one clearly bounded task at a time; finish or reach a clean stop before switching.
4. After code changes: run `./gradlew build -x test` (full `./gradlew build` before any release), update `changelog.md`, commit.
   **Invoke the `frens-release-loop` skill** for any "ship X as 1.1.NNN" / "next batch" request — it carries the dispatch briefs and the release checklist. Summary of the loop that shipped 1.1.203–1.1.208: read-only scoping agent → implementer agents (disjoint files) → one batch review → one fix wave → scoped re-review → bump `mod_version` → prepend changelog (after the 3-line header; the file is ~1 MB, never read it whole) → RALPH_TASK handoff + checkboxes → `./gradlew clean build` → commit → push → pre-deploy check → deploy → add a field-checklist phase to `docs/testing/FIELD_SESSION_1.1.202.md` → update auto-memory. Scoping comes FIRST: two backlog items this way were wrong as written ("delete legacy CompanionSpellsScreen" was the live Spells keybind target; "escape-with-full-inventory" was not an inventory problem).
5. Don't deploy until the game is closed: report the artifact path and wait for confirmation.
6. Wrap-up: summarize what changed and where; note completion vs follow-up needed.

## Game / API Knowledge — Verify Against Official Docs

Training data lags behind real Minecraft and Fabric updates. Mob behavior, entity classes, item names, block properties, and Fabric API surface area all shift between versions, and Frens targets a specific Java/Fabric pair (Java 21, Minecraft 1.21.11, Fabric 0.18.4 — see top of this file). **Do not invent or recall these details from memory** for any non-trivial change.

When the work touches vanilla mob/block/item behavior, item drops, biome rules, world generation, redstone mechanics, recipes, or any version-gated game feature, **use the Minecraft Wiki MCP first** where those tools are available. Concrete example: 1.21.11 added a rideable Nautilus mob; the wiki MCP returned its full spawn/taming/riding spec, none of which is in training data. Quote the wiki rather than asserting from memory. `WebFetch` against [https://minecraft.wiki](https://minecraft.wiki) is the fallback if the MCP is unavailable.

When the work touches Fabric APIs, mixins, registries, networking, screen handlers, server lifecycle hooks, or any modding surface, **use the MCModding MCP first** where those tools are available. Its Parchment-mappings DB covers Minecraft 1.21.11 down to 1.16.5 with class/method/field names + documented signatures, so reach for it before falling back to:

- [https://docs.fabricmc.net](https://docs.fabricmc.net) — Fabric developer guides (versioned, current).
- Fabric Javadoc — accessible via `./gradlew genSources` then through the IDE, or online via the Fabric docs site.
- The decompiled Yarn-mapped Minecraft source pulled into `~/.gradle/caches/fabric-loom/` after a build — search there for vanilla class behavior rather than guessing method signatures.

If you propose a method, field, or entity class that isn't in the codebase, **grep the codebase first** (`grep -rn "ClassName" src/`), and if missing, verify it exists via the MCModding MCP or the deobfuscated jars (`find ~/.gradle/caches/fabric-loom -name "ClassName.class"`) before writing code that imports it.

## Architecture

All paths are relative to `src/main/java/net/wcfcarolina13/`.

### Entry Points
- `Frens.java` — Server-side mod entry
- `FrensClient.java` — Client-side entry
- `FrensDataGenerator.java` — Data generation
- `CommandUtils.java`, `EntityUtil.java` — Shared helpers

### Core Behavior Loop
- **`GameAI/BotEventHandler.java`** (~199KB) — Central orchestrator for all bot behavior; dispatches to skills/services on server ticks
- **`GameAI/BotActions.java`** (~70KB) — Low-level action primitives (movement, interaction, combat)

### Fake Player Model
- `Entity/createFakePlayer.java` extends `ServerPlayerEntity` with an embedded `FakeClientConnection` (EmbeddedChannel-backed). Each bot is a full fake player with persistent inventory, stats, and position.

### Skill System
- **Interface:** `GameAI/skills/Skill.java` — `name()` + `execute(SkillContext)`
- **Registry:** `GameAI/skills/SkillManager.java` — Registers and dispatches 22+ skills
- **Implementations:** `GameAI/skills/impl/` — One class per skill (WoodcutSkill, MiningSkill, FishingSkill, ShelterSkill, FarmSkill, etc.)
- **Support:** `GameAI/skills/support/` — Shared helpers (MiningHazardDetector, TorchPlacer)
- Skills run on worker threads and coordinate via server-thread callbacks through `TaskService`

**Large skill files — size/coupling notes:**
- `FortifyVillageSkill.java` (~8611 lines) — Core fortification building, patching, navigation, tower, moat, gate, and cleanup logic. Implements `FortifySkillOps.FortifyNavOps` callback interface to allow extracted helpers to call back into primitives. Deeply coupled internally; most methods call 5–15 other private skill methods.
- `FortifySkillOps.java` (113 lines, same package) — Tiered callback interfaces: `FortifyBlockOps` (Tier 1: block queries, digging, sleeping, abort/overhead, scope access — 12 methods) and `FortifyNavOps extends FortifyBlockOps` (Tier 2: navigation, walk, scaffold, escape, reach-with-effort — 13 additional methods). Enables extracted helpers to call back into skill primitives without direct dependency.
- `FortifySkillTypes.java` (~400 lines, same package, commit `38a3526`) — All package-private types extracted from the skill: 9 enums (`FortifyNavMode`, `FortifyCleanupKind`, `CleanupState`, `NavBreakRejectReason`, `ReplaceFailureKind`, `TowerPillarOutcome`, `TowerStepOutcome`, `TowerReturnOutcome`, `TowerScaffoldSideOutcome`), 15 records, 5 state/session classes (`DeferredCleanupTask`, `FortifyCarveSession`, `FortifyNavRuntimeScope`, `TowerNavAttemptState`, `ScaffoldLedger`). Multiple non-public top-level types in one .java file — valid Java. All are package-private and accessible unchanged from FortifyVillageSkill.
- `FortifyEntombmentHelper.java` (411 lines, same package, commit `2ee2253`) — Extracted pure state-tracking helper. Handles: entombment recovery state, surface-escape retry state, carve-column cooldowns. Context update hooks: `updateScope()`, `updateLayout()`, `updateMovementEpoch()`. Static utility: `fortifyContextPrefix()`. Trivial delegates for `isTrapLikeCell`, `canStandAt`, hull-adjacency checks.
- `FortifyCleanupHelper.java` (143 lines, same package) — Owns the deferred cleanup queue (`List<DeferredCleanupTask> queue`), throttle timestamp, 3 constants, and 9 pure queue-management methods (`queue`, `queueCarveRepairs`, `noteSkip`, `noteImmediateRetry`, `noteResolved`, `checkAndUpdateThrottle`, `isForcedContext`, `allowActiveRecovery`, static `incrementReason`/`formatReasonSummary`). `processDeferredFortifyCleanupQueue` stays in skill (calls `digBlock`, `sleepQuiet`, `tryReplaceMinedBlock`) and delegates 32 call sites to `cleanupHelper.*`.
- `FortifyLayoutHelper.java` (~280 lines, same package) — Layout query helper. Owns material fallback lists (5 static lists), `ENABLE_MOAT_STAGE` constant, and 10 extracted methods: `buildCandidateList`, `isMoatRelatedType` (static), `isActiveFortifyBlock`, `isPlannedBlockSatisfied`, `computeEdgePlannedCounts`, `countPresentBlocks`, `chooseEdgeStartIndex`, `orderedRemainingEdges`, `pickNearestRemainingEdge`, `patchEdgeDistanceSq` (static). Takes shared `ignoredCavityPositions` Set reference in constructor. Skill retains thin delegates for high-call-count methods to minimize call-site churn.
- `FortifyEscapeHelper.java` (491 lines, same package) — Escape/precipice-defense helper. Owns footing-patch cooldown state and 5 constants. 13 extracted methods: `isFortifyPrecipiceDefenseContext`, `airDropDepth`, `hasDangerousFortifyPrecipiceAhead`, `tryPatchFortifyFootingNearWorksite`, `isFortifyEscapeContext`, `countEscapeShaftBlockers`, `clearEscapeShaftHeadroom`, `tryPillarEscapeFirst`, `escapeIfInHole` (2 overloads), `ensureOnSurface` (2 overloads), static `dominantHorizontalDirection`. Takes `FortifyBlockOps` for block mutations, `FortifyEntombmentHelper`, `Supplier<Set<BlockPos>>` for protected positions. `tryPillarEscapeFirst` also accepts `FortifyNavOps` for `walkTowardBlock`.

### Service Layer (80+ services in `GameAI/services/`)
Each service handles a single concern. Key ones:
- `TaskService` — Concurrent skill execution slots with thread tickets
- `MovementService` / `FollowMovementService` — Navigation and following
- `BotPersistenceService` — Save/load inventory, position, stats
- `ChestStoreService` — Inventory offload to chests
- `HealingService` / `HungerService` — Survival mechanics
- `BotAutoReturnSunsetService` — Return to base at dusk
- `BotIdleHobbiesService` — Passive behaviors when idle
- `SkillResumeService` — Resume tasks after death/pause
- `BotRegistry` — Global bot tracking

### Construction Services (`GameAI/services/construction/`)
- `ConstructionBlueprintService` — Blueprint definitions
- `ConstructionExecutionService` / `ConstructionRecoveryService` — Build execution and error recovery
- `VillageFortificationLayoutService` — Fortification layout (convex hull)
- `FortificationPersistenceService` / `FortificationVisualizerService` — Save/load and rendering
- `ScaffoldService` — Pillaring/scaffolding primitives

### PathFinding (`PathFinding/`)
- `PathFinder.java` — Classic A* pathfinder
- `BaritoneStylePathFinder.java` — Baritone-style pathfinder (opt-in fallback since 1.1.261 when unified became the default: `/bot config pathfinder unified|baritone|classic`)
- `ChartPathToBlock.java`, `GoTo.java`, `PathTracer.java`, `Segment.java` — Navigation helpers

### Commands
- `Commands/modCommandRegistry.java` — All `/bot` command registration
- `Commands/configCommand.java` — Config command handling
- Commands: spawn, stop, follow, skill, store, config, etc.

### Network & UI
- `network/` — Client-server payloads (config sync, recruitment dialogue, inventory view)
- `GraphicalUserInterface/` and `ui/` — Screens (inventory, recruitment, spells, config)

### Souls (optional local LLM)
- `GameAI/souls/` — Soul Chat, banter, memory, voices. Talks to Ollama over HTTP (`ManualConfig.ollamaBaseUrl`). The pre-souls legacy LLM stack was deleted in 1.1.251.
- `GameAI/llm/` — only `AiChatOffHintPolicy` and `LegacyChatAccessPolicy` remain (the "AI chat is off" notice).
- `ChatUtils/` — scripted dialogue, voice lines, ambient chatter. `FunctionCaller/SharedStateUtils` — skill shared-state helpers.
- Core bot functionality works without any LLM.

### Other Subsystems
- `GameAI/Knowledge/` — GameKnowledgeGraph (craftable items, tags, drop tables, used by souls)
- `DangerZoneDetector/` — Lava, cliff, water hazard detection
- `FilingSystem/` — Config handling (ManualConfig, SharedConfig)

## Threading Rules

This is the most common source of bugs. Memorize these boundaries:

**Server thread only:**
- World mutations (place/break blocks, open doors)
- Inventory mutations (move stacks, craft)
- Entity state mutations (teleport, velocity, attributes, swing/interact)

**Worker threads (preferred for):**
- Pathfinding, target scanning, heuristic scoring
- Waiting/backoff/retry loops
- Logging

**Rules:**
- Never `Thread.sleep(...)` on the server thread
- Never run long "while loop until X" on the server thread unless bounded to a tiny time window
- Keep server-thread sections small: compute off-thread, execute atomically on-thread
- Use `server.execute(...)` or `callOnServer(...)` to schedule work from worker threads

## Code Editing Guidelines

- Prefer surgical patches over wide rewrites; preserve working behavior
- Check usages before modifying functions; keep diffs reviewable
- Work on one clearly bounded task at a time
- Always run `./gradlew build` after code changes to verify compilation
- Large files are common (BotEventHandler 199KB, FarmSkill 151KB, CollectDirtSkill 115KB) — use offset/limit when reading

## Task Discipline

The Ralph autonomous loop (scripts, `.ralph/` state) was retired on 2026-10-17; work runs through the `frens-release-loop` skill. Rules kept from it:
- A backlog criterion that turns out to be wrong as written is a stop: say what it assumes, what is actually true, and wait. Do not implement it or silently reinterpret it.
- Stuck 3+ times on the same issue: stop and say so (global "RALPH GUTTER" signal) rather than trying a fourth variation.
- Commit early and often: after each completed item, after any significant code change, and before any risky refactor (checkpoint).

## Key Dependencies

| Dependency | Purpose |
|---|---|
| Fabric API 0.136.0 | Mod framework (events, networking, entity hooks) |
| Jackson 2.17.2 | JSON serialization (souls) |
| Carpet Mod | Recommended for fake-player mechanics |
