# Frens Guardrails (Signs)

> Lessons learned from past failures. READ THESE BEFORE ACTING.

Moved here from the untracked `.ralph/guardrails.md` on 2026-10-17 when the Ralph loop was retired. Project rules that
are already stated elsewhere are listed in the index at the bottom with where they now live, so none is lost. Two Signs that later fixes
made wrong were corrected in place (line-of-sight, inventory snapshot).

---

## Project-Specific Signs

### Sign: MiningTool Checks Line-of-Sight, Skills Still Own Reach
- **Trigger**: Any code that calls MiningTool.mineBlock() or similar mining primitives
- **Instruction**: MiningTool raycasts from the bot's eye and rejects a target when the ray hits a different block first (`PlayerUtils/MiningTool.java`, `losWorld.raycast`). It does not plan a stance: choosing a position from which the target is reachable and visible is still the skill's job.
- **Added after**: Initialization. **Corrected 2026-10-17**: the original Sign said MiningTool had no line-of-sight check, which stopped being true when the raycast landed (memory patterns.md).

### Sign: Construction Protection Blocks Scaffold Stances
- **Trigger**: Adding protection/stance checks that reject positions overlapping planned build targets
- **Instruction**: Scaffold stances are TEMPORARY pillar positions — do NOT check `isProtectedStance` or `ConstructionProtectionService.isProtected` for scaffold stance selection. The bot MUST be able to stand adjacent to/inside the build footprint to scaffold up. Protection should only block MINING/DESTRUCTION, not movement or standing.
- **Added after**: 2026-03-07 — isProtectedStance in chooseScaffoldStance silently rejected ALL valid stances for 3 playtests, producing zero scaffold log entries

### Sign: isWithinReach Does Not Mean Placeable
- **Trigger**: Using distance-based reach checks to decide if recovery is needed
- **Instruction**: A block can be "within reach" (< 4.5 blocks) but UNPLACEABLE when the bot is below it — `no-line-of-sight-to-support` fails because the bot's body blocks the support face, or wall blocks obstruct LOS. For elevated targets (verticalDiff >= 2), ALWAYS scaffold to eye-level first rather than relying on reach distance. The FortifyVillageSkill pattern (pillar to target.Y - 1) is the proven approach.
- **Added after**: 2026-03-07 — `isWithinReach && verticalDiff <= 1` early return caused infinite retry loops for 3 playtests

### Sign: Pathfinder Routes Through Protected Blocks
- **Trigger**: Using moveTo/pathfinder to navigate near active construction
- **Instruction**: The pathfinder plans routes through planned structure blocks (they're air during construction). But ConstructionProtectionService blocks the bot from mining those blocks, so the bot gets stuck. Always provide a nudgeToward fallback for movement near active builds, and accept approximate positions (within 2-3 blocks) rather than requiring exact pathfinder arrival.
- **Added after**: 2026-03-07 — moveTo for scaffold stances failed because paths routed through protected blocks with no fallback
- **Amended 2026-09-25 (1.1.218)**: exception — when the bot is inside the footprint and the target is outside, do NOT straight-nudge across the wall line while a doorway route exists; walk out through the doorway (`InteriorEgressService` / `HovelDoorAccessService`). The nudge fallback returns once egress is switched off (2 failures per build) or no safe doorway exists.

### Sign: Study FortifyVillageSkill Before Adding Construction Features
- **Trigger**: Adding reach/scaffold/movement/recovery logic to the generic construction system
- **Instruction**: FortifyVillageSkill has battle-tested implementations for elevated block placement (`ensureCanReachBlockWithEffort`, also on `FortifySkillOps`), batch scaffold escalation (`attemptScaffoldEscalation`), and scaffold cleanup. Always read these first and port the proven patterns rather than designing from scratch. Key pattern: scaffold directly below the target column (same XZ), pillar to target.Y - 1, place at eye level.
- **Added after**: 2026-03-07 — user explicitly requested this after 3 iterations of incremental patches failed. Line ranges replaced by method names 2026-10-17 (the file shrank after the 1.1.209-1.1.210 extraction).

---

## Learned Signs

### Sign: Gate Routing Y — Use Bot's Actual Y
- **Trigger**: Any code computing gate routing waypoint positions
- **Instruction**: NEVER use `safeSurfaceY`, `gateCenterY`, or heightmap-derived Y for gate routing waypoints. The heightmap at the gate center hits the lintel (stone bricks at Y+3 above ground), returning a Y that's 2-3 blocks above walkable ground. This makes 3D distance checks fail. Always use `bot.getBlockPos().getY()`.
- **Added after**: Gate routing v4 and v5 — v4 used surface profile Y (still wrong), v5 fixed by using bot's actual Y
- **Amended 2026-09-25 (1.1.218)**: a blueprint's own stand level (e.g. the hovel's `center.getY()+1`) is also safe for doorway waypoints — the rule is against heightmap-derived Y, which hits lintels.

### Sign: Verify Fix Actually Fixed the Problem
- **Trigger**: After deploying a "fix" and reviewing logs
- **Instruction**: Check that the new log messages appear AND the downstream behavior improved (blocks placed, success rate up). A fix that passes compilation but doesn't improve in-game metrics is not a fix. Track metrics across iterations to avoid going in circles.
- **Added after**: Multiple gate routing iterations where the fix compiled but the real problem was elsewhere

### Sign: ActionResult Obfuscation
- **Trigger**: Seeing `class_9857[]` or similar obfuscated names in log output
- **Instruction**: Minecraft intermediary mappings obfuscate runtime class names. `class_9857` is likely `ActionResult.PASS`. When logging ActionResult, also log the context (item, target, support) so the rejection can be debugged without needing to decode the obfuscated class name.
- **Added after**: `place-rejected=class_9857[]` provided no actionable information

### Sign: Hull Boundary ≠ Interior for Navigation Checks
- **Trigger**: Using `pointInConvexHull()` to decide if a target needs gate routing
- **Instruction**: `pointInConvexHull` uses `cross >= 0` which treats boundary points as "inside". Tower vertices are hull vertices (on boundary). Use `pointStrictlyInsideHull` (cross > 0, strict) when checking if a target is inside the hull for gate routing decisions. Boundary targets must be treated as "outside" so the bot routes through the gate.
- **Added after**: Gate routing never fired for tower approaches, causing 50+ second stuck loops at wall

### Sign: Server Is Authority for Skin Policy
- **Trigger**: Any feature that changes bot skins (preset/custom URL)
- **Instruction**: Treat client UI gating as advisory only. Enforce authorization and policy in the server network handler and at spawn-time normalization. When custom skins are disabled, non-admin custom selections must deterministically downgrade to safe preset (`steve`).
- **Added after**: Skin policy toggle implementation — prevents stale/offline/custom bypass paths

### Sign: Tuple Codec Boolean Fields Need Explicit Unboxing
- **Trigger**: Extending payload tuple codecs with primitive booleans
- **Instruction**: Prefer explicit constructor lambdas that unbox booleans (with null-safe defaults) over direct constructor references when static analysis flags boxing/nullability mismatch.
- **Added after**: RecruitmentStatePayload codec warning after adding skin policy booleans

### Sign: Reserve Layout Space for Dynamic Policy Text
- **Trigger**: UI screens that show conditional policy/status notices
- **Instruction**: Reserve a fixed notice region in layout before placing controls. Do not rely on overlay text near titles; use wrapped lines with bounded height so feature growth doesn’t cause clipping/crowding.
- **Added after**: Skin chooser policy notice overlap/crowding fix

### Sign: The Frens Snapshot Is the Only Truth for Bot Inventories
- **Trigger**: Join-time restore path that combines PlayerManager load with the custom inventory snapshot
- **Instruction**: Fake-player items live only in `frens/inventories/*.nbt`. The vanilla `.dat` is always newer and item-less, so never gate the snapshot on file size or mtime. A restore-pending flag guards against saving an empty inventory over it (`BotInventoryStorageService.shouldRestoreOnJoin`).
- **Added after**: Inventory persistence failsafe pass. **Reversed 1.1.187** (changelog: "snapshot is the only truth"): the old Sign said to skip the snapshot when older than the vanilla `.dat`, which caused the Bob axe and lead wipe. See memory feedback_bot_snapshot_vs_vanilla_dat.md.

### Sign: World Keys Must Include Save-Root Identity
- **Trigger**: Persisting per-world bot state in global config files
- **Instruction**: Never key world state by `levelName` alone; include a stable server/save-root fingerprint to avoid collisions across different servers/world roots sharing common names like `world`.
- **Added after**: Bot world-state collision hardening (`BotWorldStateService` key migration)

### Sign: Overflowing Menus Need Both Wheel and Thumb Drag
- **Trigger**: Any UI panel with more actions than guaranteed vertical space
- **Instruction**: Always provide a visible scrollbar thumb that can be dragged (not wheel-only), and clip widget rendering/input to the panel content area so controls never bleed offscreen.
- **Added after**: Base Manager offscreen overflow fix

### Sign: Persist User-Adjusted Panel Layouts
- **Trigger**: Resizable/movable in-game tool windows
- **Instruction**: Save panel geometry preferences and include a one-click reset to defaults. Clamp saved values to viewport bounds on open to survive resolution changes.
- **Added after**: Base Manager resizable panel implementation

### Sign: Scaffold Detection Must Require Exposed Faces
- **Trigger**: Adding blocks to PILLAR_BLOCKS or modifying `findSuspiciousScaffold` heuristics
- **Instruction**: Hard block scaffold detection (cobblestone, stone, etc.) must require `floating || isolated` — at least one exposed air/leaves face. Without this, underground natural stone/gravel formations register as scaffold candidates (130+ false positives in a 36×42×36 scan). Dirt-like scaffold must only count column height UPWARD (not downward into terrain). GRASS_BLOCK must NOT be in PILLAR_BLOCKS or DIRT_LIKE_SCAFFOLD — it destroys natural terrain.
- **Added after**: 2026-03-25 — bot shaved 57 natural terrain blocks, dug holes in grassland thinking it was removing scaffold

<!-- Add new signs below as you learn from failures -->



---

## Door passage: known-good fixes and test procedure (from RALPH_TASK.md session notes, 1.1.5-1.1.16)

Known-good, do not revert:

- **1.1.5** `BotActions.hasMovementClearance` — replaced `blocksMovement()` feet/head check with stateful `isPassableForMovement`. Open doors/gates/trapdoors with `Properties.OPEN == true` are now passable. Same bug-pattern fix pattern as commit 29a5de8 (ReturnBaseStuckService, 2026-04-08).
- **1.1.6** `FollowMovementService.hasTwoHighClearance` — same fix applied to the follow-movement passability helper used by narrow-passage align, chokepoint, dropoff guard, local-obstacle nudge.
- **1.1.7** Door-plan refactor in [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java): (a) deleted the `door-recovery` retreat (was retreating bot 2-3 blocks away from door when stuck → primary oscillation source); (b) gated `door-corner` plan rebuild with wrong-side check; (c) `isDirectRouteBlocked` now skips hits on open openables. This change intentionally breaks the old shelter-escape "trapped in corner, needs door as exit" behavior — documented as acceptable trade-off at the time, re-address later.
- **1.1.8** Extracted `isDoorPlanWrongSide(approach, step, goal)` helper and applied it at **all four** door-plan creation sites (`door-corner`, `door-adjacent`, `door-escape`, `door-ray`). Previously only `door-corner` had the check.
- **1.1.9** In `tickFollowDoorPlan`: when `plan.stepping() && doorOpen && onGround`, force `BotActions.jump(bot)` each tick instead of deferring to `autoJumpIfNeeded` (which explicitly skips door cells).
- **1.1.10** Extracted `isBoxClearIgnoringWalkablePartials` helper in `BotActions`. When `world.isSpaceEmpty` rejects due to a walkable partial's thin collision overlapping the bot's bounding box at the Y boundary (strict-less-than `Box#intersects`), second pass skips cells whose state passes `isPassableForMovement`. Covered plates, carpets, rails, thin partials, open door strips.
- **1.1.11** **Diagnostic-only build** — added `door-step-diag` (tickFollowDoorPlan) and `applyMovementInput-reject` (BotActions) log channels. Evidence caught that pressure plates failed `hasClearance=false`.
- **1.1.12** **THE FIX** — `isPassableForMovement` now consults [WalkablePartialBlocks.isPathable](src/main/java/net/wcfcarolina13/GameAI/services/WalkablePartialBlocks.java) as a third gate. In 1.21.11 `state.blocksMovement()` **returns `true` for pressure plates** (contrary to my assumption), so the previous two gates rejected. `WalkablePartialBlocks.isPathable` correctly handles plates via `AbstractPressurePlateBlock`, plus carpets, rails, tripwire, lily pad, collision max Y ≤ 0.125 fallback. Signature changed to `(state, world, pos)`. Applied symmetrically in `FollowMovementService`.
- **1.1.13** Tick-persistent door-open retry (was only firing in `!plan.stepping()` phase) + stepping-flip only commits when `doorOpen` is confirmed from world state (not trusting `tryOpenDoorAt`'s return).
- **1.1.14** Stuck-near-doorway auto-jump — generalized 1.1.9. When door plan stuck counter ≥ 8 ticks and bot on ground, force jump regardless of stepping phase. Generalizes the user's "jump a couple times to unstick" reflex.
- **1.1.16** = 1.1.14 (1.1.15 reverted, see below).


### Door test procedure

When testing door passage:
1. Spawn bot **OUTSIDE** the tower (sky visible) to avoid triggering shelter-breakfree. This isolates the door-passage issue.
2. Have bot follow; walk through each door in both directions; observe whether stall occurs and at which cell.
3. If stall happens and cause isn't obvious from log, **re-enable the 1.1.11-style diagnostic first** rather than guessing. The diagnostic caught the pressure plate root cause in one test run.

## Rulings recorded only in old handoffs

- Never `git add -A` in this repo (untracked `logs/`, `voices/ab-test/`, `.superpowers/`). The vault git-guard hook blocks any `git add/commit` text while the shell cwd is inside `~/pontus/vault`, so `cd` out in a separate call. (RALPH_TASK.md 2026-09 handoff)
- Nothing goes to Modrinth until the live playthroughs settle (Bradley, 2026-10-14).

---

## Index: Signs that now live elsewhere

- **Read Before Writing** → ~/.claude/CLAUDE.md §6 Edit Safety ("re-read before EVERY edit")
- **Test After Changes** → CLAUDE.md Agent Workflow step 4 (`./gradlew build -x test`)
- **Commit Checkpoints** → CLAUDE.md Git Protocol ("before any risky refactor (checkpoint)"); ~/.claude/CLAUDE.md §3
- **One Task Focus** → CLAUDE.md Agent Workflow step 3
- **Update Progress** → retired with the Ralph loop: progress is changelog.md, checkboxes are a release step (frens-release-loop release.md)
- **Threading Boundary** → CLAUDE.md "Threading Rules"
- **Large File Awareness** → CLAUDE.md Code Editing Guidelines ("use offset/limit")
- **Movement Method Selection** → auto-memory MEMORY.md "Movement" short-form facts (A* timing note was pre-unified-finder, dropped)
- **AutoFaceEntity Task Flag** → auto-memory MEMORY.md "Bot behavior" short-form facts
- **Furnace Mutations via server.execute() + CompletableFuture** → auto-memory project_food_self_sufficiency.md (canonical pattern `SmeltingService.cookAllFoodSync()`)
- **Auto-Stop Checks Should Include Inventory State** → auto-memory project_food_self_sufficiency.md
- **Verify JAR Path Before Copying** → CLAUDE.md deploy pattern (JAR name built from `mod_version`); memory feedback_verify_deployed_jar.md
- **Heightmap Lies About Ground Under Trees** → auto-memory feedback_surface_detection.md
- **Running-Game Check Must Match Prism** → CLAUDE.md "Required pre-deploy check"; memory feedback_prism_running_check.md
