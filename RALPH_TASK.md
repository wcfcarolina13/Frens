---
task: Stabilize P0/P1 bot reliability issues
test_command: "./gradlew build -x test"
---

# Task: Stabilize P0/P1 Bot Reliability

Address the highest-priority pending issues from the project backlog.

## P0 — Critical

- [x] Verify launch after permission predicate fix: Ensure bots can start without the LeveledPermissionPredicate OWNERS crash on 1.21.11
- [ ] Persist bot stats on respawn (verification): Save/load path exists; verify health/XP/hunger restore timing is visible before spawn flow completes

## P1 — High

- [x] Bot config UI refactor: Single-bot view with alias dropdown, grouped/scrollable settings, save/cancel affecting only the selected bot
- [ ] Bot identity separation (verification): Alias canonicalization + consistency guards across restart/respawn with multi-alias scenarios
- [ ] Job resume prompts on death/leave (verification): Verify leave/rejoin prompt and resume behavior in-game
- [ ] Per-bot chat addressing & broadcasts (verification): Verify no duplicate replies or cross-talk in runtime scenarios

## Success Criteria

All P0/P1 checkboxes above marked `[x]` after in-game verification or code fix.

## Notes

- P0 items may require code changes if bugs are found during verification
- P1 verification items map to `docs/testing/IN_GAME_AUDIT_MASTER.md` cases (AUD-010..015, AUD-030..033, AUD-050..055, AUD-060..062)
- Build must pass (`./gradlew build -x test`) after any code changes
- Commit after completing each criterion

### In-Flight: FortifyVillageSkill Refactoring — Phase 1 Complete, Phase 2 Blocked

**Completed (commit `2ee2253` + `38a3526`):**
- Extracted `FortifyEntombmentHelper.java` (411 lines) — pure state tracking, 21 methods, no skill dependencies
- Extracted `FortifySkillTypes.java` (~300 lines) — 9 enums, 16 records, 5 inner classes
  - Enums: `FortifyNavMode`, `FortifyCleanupKind`, `CleanupState`, `NavBreakRejectReason`, `ReplaceFailureKind`, `TowerPillarOutcome`, `TowerStepOutcome`, `TowerReturnOutcome`, `TowerScaffoldSideOutcome`
  - Classes: `DeferredCleanupTask`, `FortifyCarveSession`, `FortifyNavRuntimeScope`, `TowerNavAttemptState`, `ScaffoldLedger`
- Skill is now 9246 lines (was 9901 — reduced by 655 lines across both commits)

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
