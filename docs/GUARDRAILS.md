# Ralph Guardrails (Signs)

> Lessons learned from past failures. READ THESE BEFORE ACTING.

## Core Signs

### Sign: Read Before Writing
- **Trigger**: Before modifying any file
- **Instruction**: Always read the existing file first to understand context
- **Added after**: Core principle

### Sign: Test After Changes
- **Trigger**: After any code change
- **Instruction**: Run `./gradlew build -x test` to verify compilation
- **Added after**: Core principle

### Sign: Commit Checkpoints
- **Trigger**: Before risky changes
- **Instruction**: Commit current working state first
- **Added after**: Core principle

### Sign: One Task Focus
- **Trigger**: When context grows large
- **Instruction**: Focus on single criterion, complete it, commit, move on
- **Added after**: Core principle

### Sign: Update Progress
- **Trigger**: After completing any criterion
- **Instruction**: Update .ralph/progress.md AND check off the criterion in RALPH_TASK.md
- **Added after**: Core principle

---

## Project-Specific Signs

### Sign: Threading Boundary
- **Trigger**: Any code that touches world state, inventory, or entity state
- **Instruction**: World/inventory/entity mutations MUST run on the server thread. Use `server.execute(...)` or `callOnServer(...)` from worker threads. Never `Thread.sleep()` on the server thread.
- **Added after**: Initialization — most common bug source in this project

### Sign: Large File Awareness
- **Trigger**: Reading BotEventHandler.java (~199KB), FarmSkill (~151KB), CollectDirtSkill (~115KB), BotActions (~70KB)
- **Instruction**: Use offset/limit when reading. Use Grep to find specific sections. Never try to read the whole file at once.
- **Added after**: Initialization — these files exceed Read tool limits

### Sign: MiningTool Has No Line-of-Sight
- **Trigger**: Any code that calls MiningTool.mineBlock() or similar mining primitives
- **Instruction**: MiningTool checks distance only (squaredDistanceTo <= 4.5^2), NOT line-of-sight. Bot can mine through walls. Must add reach/visibility checks at the skill level.
- **Added after**: Initialization — confirmed bug in existing code

### Sign: Movement Method Selection
- **Trigger**: Writing code that needs to move the bot
- **Instruction**: Use `MovementService.execute(DIRECT)` for reliable movement (~85% success). Avoid `walkTowardBlock()` for long/complex moves (no pathfinding). A* pathfinding for 100+ blocks takes 20-30+ seconds — bot appears frozen.
- **Added after**: Initialization — proven pattern from testing

### Sign: AutoFaceEntity Task Flag
- **Trigger**: Running skills that need stable bot facing
- **Instruction**: Set `AutoFaceEntity.setBotExecutingTask(true)` before skill execution and `false` after, to suppress idle head swivel. SkillManager already does this around `skill.execute()`.
- **Added after**: Initialization — prevents visual glitches during skill execution

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
- **Instruction**: FortifyVillageSkill has battle-tested implementations for elevated block placement (L8813-8926 ensureCanReachBlockWithEffort), batch scaffold escalation (L3366-3630 attemptScaffoldEscalation), and scaffold cleanup. Always read these first and port the proven patterns rather than designing from scratch. Key pattern: scaffold directly below the target column (same XZ), pillar to target.Y - 1, place at eye level.
- **Added after**: 2026-03-07 — user explicitly requested this after 3 iterations of incremental patches failed

### Sign: Furnace Mutations via server.execute() + CompletableFuture
- **Trigger**: Any code that reads/writes furnace block entity slots from a worker thread
- **Instruction**: Furnace slots (input[0], fuel[1], output[2]) are world state — must be read and written on the server thread. Use `server.execute(() -> { ... future.complete(result); })` with `future.get(timeout)` from the worker thread. See `SmeltingService.cookAllFoodSync()` for the canonical pattern (poll loop with 2s intervals, 3min/batch and 10min total timeouts).
- **Added after**: 2026-03-27 — cookAllFoodSync implementation for post-hunt multi-batch cooking

### Sign: Auto-Stop Checks Should Include Inventory State
- **Trigger**: Any auto-stop or "is the bot fed enough" check that only reads the food bar
- **Instruction**: The food bar can be temporarily inflated by a gifted item. For self-sufficiency decisions, also check `HuntSkill.countFoodItems(bot)` to ensure the bot has backup food ITEMS (not just a temporarily full food bar). The auto-hunt trigger at food 11-14 also checks inventory food count.
- **Added after**: 2026-03-27 — bot stopped hunting at food=8 from a gifted steak with zero backup food items

---

## Learned Signs

### Sign: Verify JAR Path Before Copying
- **Trigger**: Copying built JAR to mods folder
- **Instruction**: JAR name includes version and Minecraft target (e.g. `frens-1.0.6-release+1.21.11.jar`). Use `Glob build/libs/*.jar` to find the actual filename rather than guessing `frens-1.0.0.jar`.
- **Added after**: Copy failed with "No such file or directory" due to wrong JAR name

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

### Sign: Don’t Let Stale Snapshots Override Vanilla Restore
- **Trigger**: Join-time restore path that combines PlayerManager load with custom inventory snapshots
- **Instruction**: If vanilla player `.dat` restore succeeded, compare file mtimes before applying custom snapshot. Skip custom restore when snapshot is older than vanilla data (with small grace). Never blindly load custom snapshot after manager restore.
- **Added after**: Inventory persistence failsafe pass (stale custom snapshot overwrite risk)

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

### Sign: Heightmap Lies About Ground Under Trees
- **Trigger**: Any code that determines "surface level" or "ground Y" or checks if the bot is underground
- **Instruction**: NEVER use raw `world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)` for ground detection. The heightmap counts log blocks (only excludes `instanceof LeavesBlock`). A tree trunk at Y=70 over ground at Y=64 returns Y=71. Use `SafePositionService.getWalkableGroundY(world, x, z)` which scans downward from the heightmap ceiling, skipping logs/leaves, to find actual solid ground. Median sampling of heightmap values is NOT a sufficient workaround — fix the root cause.
- **Added after**: 2026-03-25 — scaffold scanner found 130 underground stone false positives, bot rejected valid surface positions under canopy, hole recovery overreacted to trunk-biased Y values. Confirmed from Minecraft source (Heightmap.java predicate).

### Sign: Scaffold Detection Must Require Exposed Faces
- **Trigger**: Adding blocks to PILLAR_BLOCKS or modifying `findSuspiciousScaffold` heuristics
- **Instruction**: Hard block scaffold detection (cobblestone, stone, etc.) must require `floating || isolated` — at least one exposed air/leaves face. Without this, underground natural stone/gravel formations register as scaffold candidates (130+ false positives in a 36×42×36 scan). Dirt-like scaffold must only count column height UPWARD (not downward into terrain). GRASS_BLOCK must NOT be in PILLAR_BLOCKS or DIRT_LIKE_SCAFFOLD — it destroys natural terrain.
- **Added after**: 2026-03-25 — bot shaved 57 natural terrain blocks, dug holes in grassland thinking it was removing scaffold

<!-- Add new signs below as you learn from failures -->


### Sign: Running-Game Check Must Match Prism
- **Trigger**: Before copying a JAR into any Prism `mods/` directory
- **Instruction**: Use `pgrep -f "PrismLauncher/instances/.*/natives"`. `pgrep -f "net.minecraft.client.main.Main"` returns nothing for a Prism/Fabric launch (main class is fed via stdin), so it approved deploys while the game was open.
- **Added after**: 2026-08-29 — caught while watching the live log during a field test
