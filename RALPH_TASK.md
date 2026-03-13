---
task: Construction parity across builders
test_command: "./gradlew build -x test"
---

# Task: Construction Reliability + Runtime Parity

Bring all existing construction abilities and their associated services to the same reliability and runtime baseline, using the best existing patterns from `FortifyVillageSkill`, shelter/hovel flows, and the generic construction services.

## Active Criteria

- [ ] Establish a measurable parity baseline for generic schematic builds, shelter/hovel/burrow, fortify wall/patch/moat, and other construction-adjacent block-placement paths
- [ ] Standardize shared construction reach/scaffold behavior in the generic service layer (feet-based reach, LOS-aware recovery, scaffold stance rules, approximate-arrival handling, and shared tuning constants)
- [ ] Remove remaining generic schematic bottlenecks in `BuildSchematicSkill` and `ConstructionRecoveryService` (including tall-structure scaffold gating and scaffold stance/headroom failures)
- [ ] Move shelter / hovel / burrow onto the same shared reach/scaffold semantics without regressing their geometry-specific behavior
- [ ] Preserve fortify-specific hull/gate/moat/tower behavior while extracting only the generic mechanics worth sharing
- [ ] Verify parity in-game with representative build scenarios and record metrics/results

## Success Criteria

All active criteria above marked `[x]`, with build verification plus in-game evidence that construction paths converge on the same failure taxonomy and no longer diverge badly on scaffold usage, no-LOS retry loops, or wall-hugging stalls.

## Notes

- Treat `FortifyVillageSkill` as the behavioral reference for elevated reach/scaffold recovery
- Standardize generic mechanics in shared construction services; do not flatten fortify-specific policy into generic code
- Build must pass (`./gradlew build -x test`) after each implementation slice
- After code changes, deploy the built JAR to PrismLauncher for immediate playtesting

### In-Flight: FortifyVillageSkill Refactoring — Phase 1 Complete, Phase 2 Blocked

**Completed (commits `2ee2253`, `38a3526`, and current session):**
- Extracted `FortifyEntombmentHelper.java` (411 lines) — pure state tracking, 21 methods, no skill dependencies
- Extracted `FortifySkillTypes.java` (~300 lines) — 9 enums, 16 records, 5 inner classes
  - Enums: `FortifyNavMode`, `FortifyCleanupKind`, `CleanupState`, `NavBreakRejectReason`, `ReplaceFailureKind`, `TowerPillarOutcome`, `TowerStepOutcome`, `TowerReturnOutcome`, `TowerScaffoldSideOutcome`
  - Classes: `DeferredCleanupTask`, `FortifyCarveSession`, `FortifyNavRuntimeScope`, `TowerNavAttemptState`, `ScaffoldLedger`
- Extracted `FortifyCleanupHelper.java` (143 lines) — deferred cleanup queue, throttle state, 9 pure management methods
  - Methods: `queue`, `queueCarveRepairs`, `noteSkip`, `noteImmediateRetry`, `noteResolved`, `checkAndUpdateThrottle`, `isForcedContext`, `allowActiveRecovery`, static utilities
- Skill is now **9161 lines** (was 9901 — reduced by **740 lines** across 3 extraction sessions)

**Why Phase 2 is blocked:**
- Cleanup, tower, gate, and nav sections are deeply coupled — most methods call 5–15 other private skill methods (`walkToTarget`, `digBlock`, `sleepQuiet`, carve sessions, protected positions, nav scopes)
- Entombment was uniquely extractable as pure state-tracking with no outgoing calls to other skill methods
- Remaining sections would need a `FortifySharedContext` shared-state object or a `FortifySkillContext` callback interface (~15 methods) before they can be split

**Next step when resuming refactoring:**
- Design `FortifySharedContext` (mutable shared state struct) or `FortifySkillContext` callback interface
- Decide which approach, then extract one section (suggest cleanup or tower first as most self-contained after nav refs are abstracted)

---

## Ralph Instructions

1. Work on the next incomplete criterion (marked [ ])
2. Check off completed criteria (change [ ] to [x])
3. Run build after code changes
4. Commit your changes frequently
5. Update .ralph/progress.md with what you accomplished
6. When ALL criteria are [x], say: "RALPH COMPLETE"
7. If stuck 3+ times on same issue, say: "RALPH GUTTER"

---

# Recently Completed: Navigation + Combat + Companion Overhaul (2026-03-13)

All criteria met and verified in-game:

- [x] **Ranged kill drop recovery** — Bots record mob death positions via AFTER_DEATH hook (all bot kills). Post-combat sweep walks to kill sites with items (cap 24, 12-block radius, waypoint-step navigation for terrain).
- [x] **Reachability probe** — `PathFinder.canReach()` uses Baritone 150-200ms timeout. Integrated into come-early-exit fallback and `triggerComeRecoverySkill` pre-check to skip mining when surface path exists.
- [x] **Simplified regroup** — `/bot regroup` uses pure pathfinding (no mining/pillar). Snapshots player position, stops with contextual wait message if player moves >128 blocks.
- [x] **Conservative surface detection** — `isOnSurface()` now requires ≥2 of 4 cardinal neighbors to also be at surface Y. 1×1 shaft openings no longer trigger false surface detection.
- [x] **Kill position accumulation fix** — Kill positions and combat center are no longer wiped every tick while hostiles exist. `cancelPendingSweep()` only removes the timer; full clear only on sweep completion or bot death.
- [x] **Companion spell bidirectional tools** — `canUseCompanionCome/Summon/Home` check both commander AND bot inventories for Eye of Ender, Ender Pearl, Wizard's Tome. Immersive themed messages and sounds per tool type.
- [x] **Questing-mode base navigation** — Non-HOME bases >256 blocks require compass/map. HOME always reachable. Recovery skills enabled during base nav. `BotHomeService.resolvePreferredHomeBase` made public.
- [x] **Opportunistic idle drop-sweep** — After 15s idle (FOLLOW/STAY/IDLE mode, no hostiles), bot sweeps ground items within 20 blocks. Cancels if player moves >1 block (block-distance, not sub-pixel). Committed target tracking prevents flip-flopping with follow mode.
- [x] **Pillar-up escape** — Verified working in-game.

---

# Backlog

Future work items, organized by priority. Not active Ralph criteria — these are candidates for future RALPH_TASK.md iterations.

## P1 — High
- [ ] **Elder Scrolls-style dialogue menu**: Conversation topics, commands, quests
- [ ] **Elder Scrolls-style Journal**: Conversation topics, quests, important information with simple filter search

## P2 — Medium

### Follow / Come
- [ ] **Guard verification**: Run in-game tests for `/bot guard` (basic start/stop, radius handling, interaction with other tasks)
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

### Commands / UX
- [ ] **Command pruning review**: Evaluate whether `look_player` and `direction reset` are still needed
- [ ] In-game check: verify guide/search usability and that actions launched from adjusted counts run with the expected arguments

### Inventory & Items
- [ ] Shift-click, double-click, drag support
- [ ] Quick-action buttons (Sort, Equip Best, Take All, Give All)
- [ ] Bundle packing verification: drop_sweep crafts/uses bundles when inventory is truly full
- [ ] Chest management overhaul: locking/access policy, categorization rules, organization modes

### Navigation & Movement
- [ ] Swimming parity (surface and underwater)
- [ ] Verify swimming behavior matches survival movement
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
- [ ] Place and use crafting table, furnace, chest
- [ ] Craft common items (armor, torches, etc.)
- [ ] Crafting helper: detect required inputs in bot inventory and report missing items
- [ ] Crafting table craft: craft when inputs exist; announce success or missing items in chat
- [ ] Placement: place crafted table near commander safely
- [ ] Build walls (specified materials, dimensions)
- [ ] Simple 2-person house
- [ ] Block placement primitives
- [ ] Recipe awareness: refuse and explain if commander lacks recipe

### Farming & Survival
- [ ] **Hunger-aware task interruption**: Bot should stop working (e.g. auto-patching, fortifying) when starving instead of working until death. HungerService should trigger a food acquisition flow: (1) search nearby chests/barrels for food, (2) find raw food and cook it in a furnace/smoker/campfire, or (3) hunt or fish to obtain food to cook. Resume the interrupted task after eating.
- [ ] Till soil, plant seeds, harvest, replant
- [ ] Furnace usage with various fuels
- [ ] Create infinite water source
- [ ] Animal husbandry (shear, collect meat, pen animals)
- [ ] **Farm underground recovery**: Escape when underground with overhead dirt
- [ ] **Farm chest workflow**: Proactive chest placement/use during farming
- [ ] **Farm irrigation leak patching**: Detect and patch leakage
- [ ] Hobby verification: flower picking, feed-animals, hobby hunt behavior

### Hunting — Multi-Day Self-Sufficiency (Future Phase)

- [ ] **Hunt camp shelter**: Bot builds a small hut with a bed and door at hunting grounds for multi-day hunts. Falls back to placeholder blocks (e.g. fence gate) if no door materials available. Keeps builds small and tidy.
- [ ] **Hunt self-sufficient resource gathering**: Bot gathers wood, dirt, or cobblestone (whichever is most abundant at the hunting grounds) for camp building and chest crafting. Keeps quarrying clean and contained rather than sprawling.

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
