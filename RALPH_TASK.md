---
task: fellTree simplification refactor
test_command: "./gradlew build -x test"
---

## Next Session: fellTree Simplification Refactor

**Plan:** `docs/superpowers/plans/2026-04-01-felltree-simplification.md`  
**Checkpoint:** commit `6a770c5` (safe revert point)  
**Status:** Plan approved, ready to execute

Replace the 680-line column-entry system in `fellTree` with a simpler mine-from-outside → pillar → bridge approach. Works for all tree types including cherry blossoms and floaters. ~600 lines removed, ~50 lines added.

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
