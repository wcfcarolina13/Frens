---
task: farm tree-clear + irrigation pipeline stabilization
test_command: "./gradlew build -x test"
---

## Session Notes 2026-04-10 — Walkable-partial stuck fix + rescue teleport

- **Fixed:** Bot permanently stuck on walkable partial blocks (carpets, pressure plates, slabs, stairs, snow layers, rails, tripwire, lily pad) when near doorways. Root cause was `BotRescueService.rescueFromBurial` / `isBotCurrentlyStuck` computing `feetBlocked = !getCollisionShape().isEmpty()` — every walkable partial has a non-empty thin shape, so a bot standing normally on one had its feet blockpos == the partial block and was classified `stuckInBlocks=true`. That kicked `attemptEscapeMovement` every ~1.2s, yanking the bot off its planned door-traversal path and producing doorway wedge loops visible in `latest.log` 17:22–17:39 (`feetState=White Carpet` → repeated `door-close wait: bot too close` + `door-corner: stagnant`). Fix: added `isThinWalkablePartialBlock` class-based whitelist (mirrors `FollowPathService`) plus a ≤0.125 max-Y fallback for floor candles/skulls/etc. Called in both feetBlocked sites.
- **New feature:** Rescue teleport keybind (`key.frens.rescue_teleport`, unbound by default). Player-pressed un-stick hotkey. Server finds closest follower within 5 blocks horizontal / ≤3 above / ≤1 below / line of sight / actually following the player, then teleports it to the player's exact block with zeroed velocity. Tight constraints so it can't yank a bot across the map or phase through walls — purely for wedge-geometry escapes when wolf-teleport can't fire.
- **Known latent issue flagged but NOT fixed:** `ReturnBaseStuckService.isPassable()` has a misleading comment claiming pressure plates/carpets have empty collision shapes. It doesn't. Same false-positive category can reject carpeted path cells during return-to-base escape. Fix if it surfaces.
- Deployed JAR to all three Prism instances (1.21.11, 1.21.10, 1.21.10 TEST) after confirming game was not running. Verified deployed class contains `isThinWalkablePartialBlock` via `javap`.

## Next Session: Farm Pipeline Validation & Follow-Ups

**Status:** Farm tree-clear + irrigation pipeline fixed end-to-end (2026-04-08). In-game validation confirmed all four user-reported failures are fixed. Ready for deeper farm playtesting + known follow-ups below.

### What was fixed this session (2026-04-08, commits 6458e9d..085d2a5)

User reported four distinct failures during `/bot skill farm ... manual=true` in a forested area. Each traced from Prism log evidence in `1.21.10/minecraft/logs/latest.log`.

- **WoodcutSkill bounded-mode envelope check** (`6458e9d`) — `isTreeWorkEnvelopeWithinBounds` required the whole leaf envelope expanded by `WOODCUT_LOG_SCAN_EXPANSION=4` to fit inside caller bounds. For a 13×10×13 farm AABB it rejected every tree (16/16). Relaxed to require only the trunk (`base` → `top`). Added per-tree `activeTreeWorkEnvelopeMin/Max` overlay so `mineBlockDetailed` can prune canopy that overhangs caller bounds for the currently-felling tree only. Also skipped the outer-finally full-region cleanup in bounded mode (saves ~30s per pass).
- **FarmSkill work buffer + brute-clear demotion** (`f5cee16`) — Split "is in the way" query buffer from woodcut's work buffer. Added `FARM_WOODCUT_WORK_BUFFER=6` / `FARM_WOODCUT_WORK_VERTICAL_RANGE=20` (used only by `runWoodcutInline`). `clearBlockingTreeBlocksLocally` no longer runs in parallel with woodcut — only as last-resort fallback when woodcut failed to reduce the blocker count. Eliminates floaters.
- **Farm phantom precipice** (`4a73129`) — `assessFarmSite` used `Heightmap.Type.WORLD_SURFACE` which counts logs/leaves, returning canopy tops (y=78–85) instead of walkable ground (y=65–68) in a forest. Median landed mid-canopy; `hasSimplePrecipice` sampled at y=72 and saw air below → phantom rejection. Replaced with `SafePositionService.getWalkableGroundY` in `assessFarmSite`'s column loop + `estimateFarmAreaMedianSurfaceY`. Removed two redundant hard-reject `hasSimplePrecipice` call sites.
- **WoodcutSkill UOE + unconditional bounded cleanup skip** (`14f1fa0`) — After the envelope check relaxation, `collectRemainingEnvelopeLogs` crashed with `UnsupportedOperationException`: `Stream.toList()` is unmodifiable, next line called `.sort()`. Latent bug that never fired before. Fix: `Collectors.toCollection(ArrayList::new)`. The UOE then burned 42 seconds in the outer finally's cleanup (user-visible stillness) because `felled == 0` on the exception path. Broadened the bounded-mode cleanup skip to unconditional.
- **Irrigation 2x2 infinite source** (`085d2a5`) — `placeWaterOnServerThread` used `world.getBlockState(waterPos).isOf(Blocks.WATER)` as a success check. After placing NW, flowing water spreads into SE within ticks. On the SE placement: `useOnBlock` returned `Pass` (bucket NOT consumed), but the world check saw pre-existing flowing water and falsely reported success. Result: only 1 real source, 3 flowing cells. Fix: use **bucket consumption** (`WATER_BUCKET` → `BUCKET`) as proof of placement. Removed the `still >= 1 && water >= 4` fallback in `isAcceptableIrrigation` (it was labeled "snow/cold-biome" but only ever fired in exactly this bug case).

All five commits pushed to `origin/main`. User in-game validation: **"Good, that fixed it"**.

### Known issues to watch next session

1. **The `ensureAtSurface` / `nudgeToward failed` loop at session start** (~74 seconds in the 12:23 log). Not fixed this session — secondary symptom. Triggered when bot starts with `leavesTrap=true` under canopy. Worth investigating if it recurs.
2. **WoodcutSkill still reports `soilFail=29` out of 31 logs** in dense forest — most logs fail `hazardous terrain on all sides` before even reaching the envelope check. The one tree that does get through is felled correctly now, but most of the forest is invisible to detection. May need to revisit the soil/hazard heuristic for bounded-mode farm clearing.
3. **Farm manual placement still has multiple `Heightmap.Type.WORLD_SURFACE` / `findSurfaceY` call sites** outside `assessFarmSite`. They weren't fixed in this session because they receive upstream-chosen positions. If they misbehave in forests, apply the same walkable-ground-Y treatment.

### Previously outstanding from last session (2026-04-03/04)

**Lodestone compass fast-travel system:**
- `LodestoneCompassService` — inventory scanning (including bundles), validation, selection, home designation
- `/bot compass list|home|travel` commands
- Lodestones in Bases menu, smoke signal navigation beacons
- Sunrise skill resume loop (sunset save → sleep → sunrise fast-travel back → resume skill)
- Protected lodestones from ALL mining paths (BotStuckService, ReturnBaseStuckService, MovementService)
- Fast-travel spawn offset away from solid blocks

**Woodcut scaffold descent:**
- `descendScaffoldColumn()` — Y-level-grouped descent with bridge-first-then-drop
- Hazard avoidance — `WoodcutHazardScanner` scans for ravines/water, filters scaffold directions
- Bridge retraction fix — bot walks toward perch before mining bridge blocks behind it
- Adjacent column pillar — bot can pillar from 1 block off trunk when entry is blocked
- No-walk elevated sweeps during descent (bot stays on column)

**Woodcut "Until sunset" GUI:**
- Actions menu defaults to "Until sunset" like fishing
- `SkillManager.isOpenEnded()` extended for woodcut

### Legacy follow-ups (not addressed this session)

**Lodestone compass fast-travel system:**
- `LodestoneCompassService` — inventory scanning (including bundles), validation, selection, home designation
- `/bot compass list|home|travel` commands
- Lodestones in Bases menu, smoke signal navigation beacons
- Sunrise skill resume loop (sunset save → sleep → sunrise fast-travel back → resume skill)
- Protected lodestones from ALL mining paths (BotStuckService, ReturnBaseStuckService, MovementService)
- Fast-travel spawn offset away from solid blocks

**Woodcut scaffold descent:**
- `descendScaffoldColumn()` — Y-level-grouped descent with bridge-first-then-drop
- Hazard avoidance — `WoodcutHazardScanner` scans for ravines/water, filters scaffold directions
- Bridge retraction fix — bot walks toward perch before mining bridge blocks behind it
- Adjacent column pillar — bot can pillar from 1 block off trunk when entry is blocked
- No-walk elevated sweeps during descent (bot stays on column)

**Woodcut "Until sunset" GUI:**
- Actions menu defaults to "Until sunset" like fishing
- `SkillManager.isOpenEnded()` extended for woodcut

### Known issues to validate/fix next

1. **Woodcut success rate still ~10-15%.** Most trees end PATH_OR_REACH_FAILURE. The bot mines 3-5 ground logs per tree but often can't reach upper trunk. The adjacent-column fix helps but the bridge fallback still has LoS failures. Needs in-game observation to identify remaining blockers.

2. **Bot gets stuck for 11k+ ticks** between tree canopies during woodcut. ReturnBaseStuck fires but can't effectively escape. May need a "give up on this area" threshold.

3. **Follow mode stuck at 1-block Y differences.** Bot can see commander but `directBlocked=true` when 1 block above. Escalates to stagnant-80+ with no resolution.

4. **Duplicate "Returning to base" messages** on each sunset return.

5. **Suffocation during scaffold descent** — bot embeds in terrain after dropping through scaffold gaps.

### Backlog items added this session

- **P1:** Axe retrieval from nearby chests (wooden/stone/copper only, no enchanted)
- **P1:** Bundle-aware inventory scanning (systemic fix for all inv methods)
- **P1:** Idle during fast-travel cooldown (hobby/offload while waiting)

### Key files changed

| File | Changes |
|---|---|
| `LodestoneCompassService.java` | NEW — compass scanning, validation, bundle support |
| `NavigationArtifactService.java` | Tier/multiplier, spawn offset, smoke signal, skipArtifactGate |
| `BotAutoReturnSunsetService.java` | LODESTONE_COMPASS anchor, sunrise resume, lodestone shortcut |
| `SkillResumeService.java` | SunriseResumeRecord, getLastRawArgs |
| `BotHomeService.java` | homeCompassNameByBot, findBaseNearPosition |
| `WoodcutSkill.java` | descendScaffoldColumn, hazard filtering, adjacent column, no-walk sweeps |
| `WoodcutHazardScanner.java` | NEW — ravine/water terrain assessment |
| `BridgeScaffoldService.java` | Retraction walks toward perch first |
| `ProtectedStructureBlockHelper.java` | isNeverBreakBlock (lodestone, beacon, etc.) |
| `MovementService.java` | isNeverBreakBlock guards on obstruction mining |
| `BotStuckService.java` | isNeverBreakBlock guard on mine-escape |
| `ReturnBaseStuckService.java` | isNeverBreakBlock guard on tryMineBlock |
| `modCommandRegistry.java` | /bot compass commands, orphaned compass warnings |
| `SkillManager.java` | Woodcut open-ended when no count |
| `BotPlayerInventoryScreen.java` | Woodcut "Until sunset" GUI |
| `BaseNetworkManager.java` | Lodestone entries in bases menu |
| `BaseManagerScreen.java` | isLodestone, Go To/Set Home for lodestones |

# Task: (No active task)

No active Ralph criteria. Pick from the backlog below when starting a new iteration.

## Recent Session Notes (2026-03-31)

- Implemented the critical shallow-hole navigation fix: the main pathfinder now supports 8-direction local movement (including diagonal step-ups/step-downs with anti-corner-cutting), the movement follower preserves nearby diagonal/vertical lead-in hops instead of collapsing them into the same bad wall-hump, and `ReturnBaseStuckService` now promotes discovered natural step-ups into a temporary local escape target instead of just nudging once and immediately losing control back to the old blocked destination.
- In-game validation now confirms the shallow-hole work is materially better: recent Prism logs show multi-hop `movement local escape ... route=...` selections and successful terrain progress instead of the original repeated wall-jump failure loop. The remaining navigation issue shifted from "cannot escape" to "movement still feels too micro-step / exact-hop heavy."
- Follow-up pass landed on top of that validated route escape:
  - `MovementService` now keeps a short-lived commitment to a successful local route so ordinary path following stays fluid instead of re-triggering precision local escape on every next segment.
  - same-level raw lead-in segments are merged into longer runs when the bot is no longer in trap-like terrain.
  - woodcut trunk-entry carving no longer digs the support block under the stance by default, reducing the 1-block pits left around harvested trees.
  - per-tree maintenance now includes a narrow terrain-restore hook for tracked entry repairs.
  - sapling replanting returns to planting range after drop sweep/cleanup and idle woodcut no longer disables replanting.
- Implemented woodcut utility-placement recovery: when the bot needs to place a crafting table or chest while stuck in a cramped hole, it now tries to clear a minimal safe pocket from soft-natural blocks or bot scaffold, and can do a short local relocation before giving up.
- Tightened woodcut exact-stand occupancy: trunk-entry movement now requires real block occupancy, runs bounded recovery on false-positive `"already at destination"` / near-stand movement results, and performs short post-failure stance recovery so the bot does not resume from the same bad hole stance.
- Follow-up tuning pass landed:
  - faster escalation out of repeated shallow-hole / exact-stand stalls
  - more frequent and broader woodcut drop sweeping
  - reduced log spam for utility-placement rejection scans, drop-sweep tactical traces, and expected woodcut/crafting pursuit misses
- Verified this session with:
  - `./gradlew compileJava`
  - `./gradlew remapJar`
  - `./gradlew test --tests 'net.wcfcarolina13.PathFinding.BaritoneStylePathFinderTest' --tests 'net.wcfcarolina13.PathFinding.PathFinderSegmentTest' --tests 'net.wcfcarolina13.GameAI.services.MovementServiceLocalEscapeHeuristicsTest'`
- Jar deployment status:
  - the latest navigation build with diagonal/local shallow-hole escape fixes and the movement-smoothing / replant / terrain-preservation follow-up is copied to the Prism `1.21.11` and `1.21.10` instances
  - `1.21.10 TEST` still has the older jar and was intentionally not touched during the latest deploy
- Best next validation target:
  - in-game regression run focused on:
    - shallow pits with diagonal one-block exits, verifying fewer repeated micro-hop local-escape restarts
    - woodcut trunk-entry cases that previously carved `grass_block` / `dirt` support under the stand
    - standalone and idle woodcut runs, verifying `Planted sapling at ...` or explicit replant skip summaries after per-tree cleanup

## Ralph Instructions

1. Work on the next incomplete criterion (marked [ ])
2. Check off completed criteria (change [ ] to [x])
3. Run build after code changes
4. Commit your changes frequently
5. Update .ralph/progress.md with what you accomplished
6. When ALL criteria are [x], say: "RALPH COMPLETE"
7. If stuck 3+ times on same issue, say: "RALPH GUTTER"

---

# Backlog

Future work items, organized by priority. Not active Ralph criteria — these are candidates for future RALPH_TASK.md iterations.

## P1 — High

- [ ] **Elder Scrolls-style dialogue menu**: Conversation topics, commands, quests
- [ ] **Elder Scrolls-style Journal**: Conversation topics, quests, important information with simple filter search
- [ ] **Drop-sweep cobblestone loop**: During patrol, drop sweep detects full inventory → tries bundle (no leather) → tries chest (none) → drops 64x Cobblestone "to free space" → sweep picks it back up → repeats every ~7s indefinitely. Fix: skip "drop items to make room" when no reachable offload target exists. Log evidence: `"Store: no chest in inventory and couldn't craft one."` → `"Dropped 64x Cobblestone to free inventory space."` → sweep picks it up → cycle. See `ForkJoinPool.commonPool-worker-1` thread in logs 22:45:21–22:46:08 (2026-03-28).
- [ ] **Idle during fast-travel cooldown**: When a bot wants to fast-travel but has an active cooldown, it should do useful things while waiting (idle hobbies if enabled, chest offloading to nearby existing chests if disabled), then fast-travel when cooldown expires. Currently the bot just sits idle. For sunset→home specifically, don't build new chests — only use existing ones.
- [x] **Axe retrieval from nearby chests**: When the bot runs out of axes during woodcut, check nearby registered chests (via BotChestRegistryService) for wooden/stone/copper axes — nothing better than copper, nothing enchanted. Take one and continue. Currently the bot just stops or mines with bare hands/wrong tool.
- [ ] **Bundle-aware inventory scanning**: The bot ignores items inside bundles across the entire codebase. Only lodestone compass scanning was patched. Needs a systematic fix: food detection (HungerService, isFoodItem, cookAllFoodSync), tool selection (MiningTool, armorUtils, CombatInventoryManager), crafting material checks (CraftingHelper), chest offloading (ChestStoreService), and any other inventory iteration that calls `inv.getStack(i)` without checking for `BUNDLE_CONTENTS`. Consider a shared `InventoryIterator` utility that yields both direct slots and bundle contents, so every caller gets bundle support automatically.
- [ ] **Escape-with-full-inventory**: Guard/patrol stuck escape (pillar via `ensureAtSurfaceForHobby`) fails when inventory has no room for scaffold blocks — `"pillar recovery placed no blocks"` repeated every ~12s. Bot stuck in 1-block hole with full cobblestone inventory. Consider: temporarily drop a non-essential stack, pillar out, pick it back up. Or: use cobblestone directly as scaffold material.

## P2 — Medium

### Inventory & Storage

- [ ] **Furnace offload fallback**: When no chest is available but furnaces are nearby, dump fuel-eligible items (leaves, sticks, planks) into the fuel slot and smeltable items into the input slot. Especially useful during patrol when bot accumulates items with no chest infrastructure.
- [ ] **Craft chest from wood**: When inventory is full and bot has logs/planks but no chest, craft one (8 planks) and place it. Currently: try bundle → try chest → give up → drop items. Missing step: "craft chest if materials available."
- [ ] Shift-click, double-click, drag support in inventory UI
- [ ] Quick-action buttons (Sort, Equip Best, Take All, Give All)
- [ ] Bundle packing verification: drop_sweep crafts/uses bundles when inventory is truly full
- [ ] Chest management overhaul: locking/access policy, categorization rules, organization modes

### Follow / Come

- [ ] **Guard/patrol verification**: In-game tests for radius handling, stuck escape in various terrain, interaction with combat and drop sweeps (partially done 2026-03-28: UI radius controls, stuck escape, HUD mode display)
- [ ] **Come tool crafting (verification)**: Verify torches/shovels/pickaxes are provisioned in-world when recipes/materials permit
- [ ] **Follow stability (verification)**: Core planner/backoff/waypoint recovery runtime verification across dimensions/terrain
- [ ] **Deterministic follow/come assertions (verification)**: Run `FOLLOW_COME_ASSERT_RUNBOOK.md` and record pass/fail outcomes
- [ ] **Follow vertical recovery (verification)**: Bot should attempt a nearby projected anchor reroute first, then prompt regroup if still blocked
  - [ ] In-game check: have commander drop into a shaft with a nearby reachable staircase and verify follow reroutes to descend
  - [ ] In-game check: while following, place a 1x1 deep shaft in the movement lane; verify bot sidesteps/stops

### Shelter (Redo Needed)

- [ ] **ShelterSkill refactor**: Split `ShelterSkill.java` into smaller hovel/burrow builder classes
- [ ] **ScaffoldService extraction**: Centralize pillaring/scaffolding + ladder placement into a reusable service
- [ ] **LeafClearService extraction**: Centralize leaf-block clearing so other skills can reuse it
- [ ] **Shelter resource acquisition flow**: Auto-collect/craft materials by default; allow `ask|wait|manual` to pause
- [ ] **Shelter options parameter**: Investigate what `options` currently controls for hovel/burrow
- [ ] **Shelter chest workflow**: Withdraw/deposit resources and place chests inside planned interior
- [ ] **Burrow "descend-stripmine-descend"**: Restore intended method

### Construction (Blocked — formerly active task)

- [ ] **Construction parity baseline**: Establish measurable parity for generic schematic builds, shelter/hovel/burrow, fortify wall/patch/moat, and other block-placement paths
- [ ] **Shared construction reach/scaffold**: Standardize feet-based reach, LOS-aware recovery, scaffold stance rules in the generic service layer
- [ ] **Generic schematic bottlenecks**: Remove remaining bottlenecks in `BuildSchematicSkill` and `ConstructionRecoveryService`
- [ ] **Shelter onto shared semantics**: Move shelter/hovel/burrow onto shared reach/scaffold without regressing geometry-specific behavior
- [ ] **FortifyVillageSkill Phase 2 refactoring**: Design `FortifySharedContext` callback interface (~15 methods), then extract cleanup/tower sections. Phase 1 complete (extracted EntombmentHelper, SkillTypes, CleanupHelper, LayoutHelper, EscapeHelper — reduced by ~740 lines)

### Commands / UX

- [ ] **Command pruning review**: Evaluate whether `look_player` and `direction reset` are still needed
- [ ] In-game check: verify guide/search usability and that actions launched from adjusted counts run with the expected arguments

### Navigation & Movement

- [ ] Swimming parity (surface and underwater, verify behavior matches survival movement)
- [ ] Boat support (enter, exit, navigate)
- [ ] Test fishing from a boat
- [ ] Portal following (Nether, End)
- [ ] Cross-realm teleport command
- [ ] Water-aware pickup (wade/bridge)
- [ ] Edge/hole pickup (hop down safely)
- [ ] Add shelves and containers to no-break list

### Fishing

- [ ] Verify leaf-block clearing when navigating far from shoreline
- [ ] Verify fishing from higher vertical positions (cliffs/piers)
- [ ] In-game check: trigger `/bot fish` while bot is swimming/submerged and verify it relocates to dry shore before first cast
- [ ] **Fishing reach**: Extend "near water" search/acceptance radius
- [ ] **Water location memory**: Store/recall known water locations

### Combat & Safety

- [ ] Creeper evasion (sprint away when unarmed)
- [ ] Protected build zones (no-grief areas)
- [ ] Fight with teammates
- [ ] In-game check: stand near passive endermen and confirm bot does not face/aggro them; then provoke one and confirm bot can still target it once hostile
- [ ] In-game check: drop bot from lethal height with/without a water bucket (Overworld), verify clutch attempts near impact and no attempts in ultrawarm dimensions
- [ ] Ride sync verification: mount/dismount mirroring across entities
- [ ] Ride sync leashed persistence: tethered after disconnect/rejoin

### Crafting & Building

- [ ] Craft common items (armor, torches, etc.)
- [ ] Crafting helper: detect required inputs in bot inventory and report missing items
- [ ] Crafting table craft: craft when inputs exist; announce success or missing items in chat
- [ ] Placement: place crafted table/furnace/chest near commander safely
- [ ] Build walls (specified materials, dimensions)
- [ ] Simple 2-person house
- [ ] Block placement primitives
- [ ] Recipe awareness: refuse and explain if commander lacks recipe

### Farming & Survival

- [ ] **Hunger-aware task interruption**: Bot should stop working when starving instead of working until death. HungerService should trigger food acquisition: (1) search chests/barrels, (2) cook raw food (DONE: cookAllFoodSync), (3) hunt/fish (DONE: auto-hunt). Resume after eating.
- [ ] Till soil, plant seeds, harvest, replant
- [ ] Create infinite water source
- [ ] Animal husbandry (shear, collect meat, pen animals)
- [ ] **Farm underground recovery**: Escape when underground with overhead dirt
- [ ] **Farm chest workflow**: Proactive chest placement/use during farming
- [ ] **Farm irrigation leak patching**: Detect and patch leakage
- [ ] Hobby verification: flower picking, feed-animals, hobby hunt behavior
- [ ] **HealingService cooked food preference**: Auto-eat should prefer cooked over raw food
- [ ] **Smoker preference for food cooking**: resolveFurnaceTarget should prefer smokers for food-only cooking (2x faster)
- [ ] **Fuel acquisition fallback**: If no fuel in inventory, attempt mini leaf-litter collection before giving up on cooking

### Hunting — Multi-Day Self-Sufficiency (Future Phase)

- [ ] **Hunt camp shelter**: Bot builds a small hut with a bed and door at hunting grounds for multi-day hunts
- [ ] **Hunt self-sufficient resource gathering**: Bot gathers wood/dirt/cobblestone for camp building and chest crafting

### Mining & Resource Gathering

- [ ] Tree chopping (safe climbing, late drop collection)
- [ ] Strip mining with safety offset (sand, gravel, lava)
- [ ] Cave/structure detection and reporting
- [ ] Water encounter handling

## P3 — Low

### Multi-Bot Features

- [ ] Per-bot chat behaviors/personas (beyond routing)
- [ ] Broadcast command UX polish (feedback per bot)
- [ ] Shared job coordination (queue fan-out, conflict handling)
- [ ] Resume prompts respect group commands

### Advanced Combat

- [ ] PVP sparring mode
- [ ] Army formations (line, grid)
- [ ] Archer positioning
- [ ] Horse flank maneuvers

### Quality of Life

- [ ] Command queuing (multi-step instructions)
- [ ] Voiced banter variants for follow-adventure lines

## LLM Integration (Future)

- [ ] Phase 1+: Core architecture, toggles, identity & memory, routing, performance, social awareness, integration & testing
