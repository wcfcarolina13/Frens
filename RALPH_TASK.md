---
task: (no active task — pick from Backlog below)
test_command: "./gradlew build -x test"
---

## Session Notes 2026-05-06 — Named-hostile pacifism shipped (verified in 1.1.55)

The "Next session's task" handed off on 2026-04-20 was **fully implemented and shipped in commit `aeef62a` (1.1.55)** along with the per-hobby toggle menu + llama mount filter. All hook points, the toggle, the flee hook, and the Admin UI are wired:

| Spec item | Location |
|---|---|
| `BotCombatPolicyService.shouldBotAttack` | [services/BotCombatPolicyService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotCombatPolicyService.java) |
| Per-bot toggle storage + getter/setter/toggle | [services/BotHomeService.java:489](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java#L489) (`isAttackNamedMobs` / `setAttackNamedMobs` / `toggleAttackNamedMobs`) |
| `engageHostiles` filter (covers all 8 call sites) | [BotEventHandler.java:3657](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3657) |
| `BotAnimalDefenseService` Step 1 + Step 2 (scan still runs, only engagement gated) | [services/BotAnimalDefenseService.java:193, 234](src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java#L193) |
| `BotMutualAidService` ally-threat | [services/BotMutualAidService.java:562](src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java#L562) |
| `BotRLActionService` candidate set | [services/BotRLActionService.java:92](src/main/java/net/wcfcarolina13/GameAI/services/BotRLActionService.java#L92) |
| Damage-intake flee hook | [Frens.java:932-944](src/main/java/net/wcfcarolina13/Frens.java#L932-L944) |
| `BotFleeService.fleeFromEntity` | [services/BotFleeService.java:650](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L650) |
| `/bot attack_named_mobs on/off/toggle` chat command | [Commands/BotHomeCommands.java:159](src/main/java/net/wcfcarolina13/Commands/BotHomeCommands.java#L159) |
| Admin UI row "Attack Named Mobs" | [BotPlayerInventoryScreen.java:622](src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java#L622) |

`BotCombatCalloutService` was on the spec's hook list but inspection confirmed it's voice-line-only and has no attack-target selection — no filter needed. Behavior matches the spec intent: hostile scans still see name-tagged mobs (so flee can trigger and overhead warnings still fire), only the engagement boundary rejects them.

The 2026-04-20 backlog item line at the bottom of P2 Commands/UX was kept for now as a "test in-game and confirm" reminder; can be checked off after manual verification of the spec's 6-item test plan.

### Other backlog items still open from 2026-04-20

- **Base Manager UX polish** — sort rows / section headers / hover tooltip / Set Home chat echo.
- **Delete legacy `CompanionSpellsScreen` (post-1.1.40 cutover)** — the unified tab is validated; safe to remove the dead screen and its references.
- **Actions-tab Regroup duplicate** — decide whether to keep `Regroup` / `Return Home` in both Actions and Spells or consolidate.

These are all independent follow-ups for whichever session picks them up next.

## Session Notes 2026-05-06 — Backlog audit pass

Swept the Backlog section against the current codebase to catch items that had quietly shipped without being checked off. Results:

**Flipped to [x] with implementation pointers:**

- Creeper evasion (sprint away when unarmed) — `BotEventHandler.java:3794`
- Protected build zones (no-grief areas) — `ProtectedZoneService` (AABB zones, persisted per-world)
- Till soil, plant seeds, harvest, replant — `PlantSeedsSkill` + `HarvestCropSkill` + `FarmSkill` cover the loop, auto-replant validated 2026-04-08
- Tree chopping (safe climbing, late drop collection) — `WoodcutSkill` (registered hobby `woodcut`)
- Strip mining with safety offset — `StripMineSkill`

**Annotated with current-state notes (still [ ] but partial):**

- Bundle-aware inventory scanning — lodestone / navigation / honey already migrated; HungerService / MiningTool / ChestStoreService still raw-slot
- Boat support — `TravelMountHandler` covers mount/dismount/leashed-rejoin sync; free-form bot-driven boat navigation still open
- Farm irrigation leak patching — detection shipped (`irrigationLeakReason`), patch path open
- Animal husbandry — shears used in `WoolSkill` / `HoneyCollectSkill`; breed/pen still open
- Create infinite water source — `FarmSkill` *uses* an existing 2×2 basin but doesn't *create* one
- Fall-clutch / ride-sync verification items — code shipped (`BotFallSafetyService`, `RideSyncService`); just unverified in-game

**Genuinely still open (large set — left as-is):** drop-sweep cobblestone loop, idle during fast-travel cooldown, furnace offload fallback, craft chest from wood, hunger-aware task interruption, smoker preference, HealingService cooked-food preference, fuel-acquisition fallback, farm underground recovery, farm proactive chest workflow, cave/structure detection, water encounter handling, shelves+containers no-break list, water location memory, fight with teammates, craft common items / armor / walls / 2-person house, recipe awareness, hunt camp shelter, multi-bot UX, advanced combat, command queuing, voiced banter for follow-adventure, quick-action buttons, shift-click inventory UI, ShelterSkill refactor, construction parity, FortifyVillageSkill Phase 2, command pruning eval, Base Manager UX polish, legacy `CompanionSpellsScreen` deletion (file still exists + still referenced by `FrensClient.java:38, 1578`), Actions-tab Regroup duplicate, Elder Scrolls dialogue/journal, LLM Phase 1+.

No code changes in this audit pass — RALPH-only documentation cleanup.

---

## Session Notes 2026-04-16 — Door passage series (1.1.5 → 1.1.16)

**Current deployed version: 1.1.16** (all three Prism instances). This session ran a long iterative series on bot door passage; documenting where each fix landed and what's still open so the next session can pick up cleanly.

### What shipped and is known-good (do not revert)

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

### What was reverted (1.1.15, deployed as 1.1.16)

- 1.1.15 added an auto-step escape hatch in `canOccupyPosition`: if `hasMovementClearance(feet)` failed but feet cell was `WalkablePartialBlocks.isStandable` and cells above passed, allow the impulse anyway, trust vanilla `Entity.move()` auto-step. **Too permissive.** Bot pushed into stair back-top-halves and other partials where vanilla auto-step couldn't actually resolve the step-up, accumulated stuck time, triggered rescue-mining behavior. Reverted in 1.1.16.

### Still open — priority order for next session

1. **Bot gets stuck approaching the northern tower door** (residual from 1.1.16 testing). Specific pattern from latest log at `08:39:44 → 08:39:55`: bot at `(888, 64, 1397)`, no door plan active, `commander-route clear` firing every ~2s (removing waypoints), outer direct-pursuit tries to apply impulse but something rejects it. Bot sits for 11 seconds until a wolf-teleport or user-jump rescues it. `canOccupyPosition` is rejecting movement from `z=1397` to `z=1396` even though `z=1396` should just be a pressure plate (1.1.12 fix handles plates). Something in the approach path — likely the head cell at `(888, 65, 1396)` — is not passable. Need to re-enable the 1.1.11-style diagnostic to see exactly what block rejects. Don't guess again.

2. **Shelter-breakfree misfires on indoor spawn.** Triggered by `Bot Jake trapped on join — hasn't moved (0.0 blocks) and no sky. Launching break-free.` in log. When bot legitimately respawns inside an enclosed structure (tower, house, base), the detection flags it as trapped and auto-mines upward, destroying stairs and other structural blocks. Need a smarter indoor-detection heuristic — e.g., check if there's a door within N blocks before assuming the bot must mine out. Or make the break-free path prefer walking toward known doors/bases before mining.

3. **Bot standing at end of staircase not trying to walk around.** New symptom reported in this session — bot on opposite end of the spiral staircase from commander, just stares, doesn't even attempt pathfinding around the central column. Likely the pathfinder ([BaritoneStylePathFinder](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java) or classic [PathFinder](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java)) can't build a multi-level path through interior stairs around a column. Separate, larger issue from door passage — may need to expand the pathfinder's node-type classification for interior stair traversal. Out of scope for a quick door-fix session; worth its own investigation.

### Architectural concern to raise with user at start of next session

3+ fix attempts on door passage in this session triggered the systematic-debugging `3+ fixes failed → question architecture` rule. The current door-plan state machine (approach/step/stepping + four separate creation sites + stuck counter + wrong-side check + forced jump + tick-persistent retry) is VERY complex compared to vanilla villager door handling (~30 line `InteractWithDoorGoal`). Possible simplification: drop the door-plan entirely and have the pathfinder emit door tiles as regular waypoints, with an `InteractWithDoorGoal`-style observer that opens doors opportunistically when the bot's next waypoint is a closed door. Large change but may eliminate several bug classes at once. Discuss before attempting.

### Test procedure for next session

When testing door passage:
1. Spawn bot **OUTSIDE** the tower (sky visible) to avoid triggering shelter-breakfree. This isolates the door-passage issue.
2. Have bot follow; walk through each door in both directions; observe whether stall occurs and at which cell.
3. If stall happens and cause isn't obvious from log, **re-enable the 1.1.11-style diagnostic first** rather than guessing. The diagnostic caught the pressure plate root cause in one test run.

## Session Notes 2026-04-11 — Tamed-animal defense (Feature A)

- **New service:** `BotAnimalDefenseService` consolidates owned-animal defense with hostile-forward scan + small reverse watch list. See `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md` (rev 3) and `docs/superpowers/plans/2026-04-11-tamed-animal-defense.md` for full design + plan.
- **Three integration hooks** in existing code: `BotEventHandler.scoreThreat` (additive defense boost), top of `BotEventHandler.engageHostiles` (augmentHostilesWithDefenseTargets call), `Frens.java` END_SERVER_TICK + SERVER_STOPPING. Iron-golem accidental-hit + direct-aggro special rules added inline in `engageHostiles`.
- **PvE only.** Player attackers get an overhead warning (existing "Engaging threats against allies" voiced line) instead of engagement. Alliances feature is the planned PvP gate, not yet built.
- **JAR built but not deployed** — user will deploy when ready. Manual verification checklist (15 items) is in the spec under "Manual Verification Checklist".

## Session Notes 2026-04-10 — Walkable-partial stuck fix + rescue teleport

- **Fixed:** Bot permanently stuck on walkable partial blocks (carpets, pressure plates, slabs, stairs, snow layers, rails, tripwire, lily pad) when near doorways. Root cause was `BotRescueService.rescueFromBurial` / `isBotCurrentlyStuck` computing `feetBlocked = !getCollisionShape().isEmpty()` — every walkable partial has a non-empty thin shape, so a bot standing normally on one had its feet blockpos == the partial block and was classified `stuckInBlocks=true`. That kicked `attemptEscapeMovement` every ~1.2s, yanking the bot off its planned door-traversal path and producing doorway wedge loops visible in `latest.log` 17:22–17:39 (`feetState=White Carpet` → repeated `door-close wait: bot too close` + `door-corner: stagnant`). Fix: added `isThinWalkablePartialBlock` class-based whitelist (mirrors `FollowPathService`) plus a ≤0.125 max-Y fallback for floor candles/skulls/etc. Called in both feetBlocked sites.
- **New feature:** Rescue teleport keybind (`key.frens.rescue_teleport`, unbound by default). Player-pressed un-stick hotkey. Server finds closest follower within 5 blocks horizontal / ≤3 above / ≤1 below / line of sight / actually following the player, then teleports it to the player's exact block with zeroed velocity. Tight constraints so it can't yank a bot across the map or phase through walls — purely for wedge-geometry escapes when wolf-teleport can't fire.
- **Also fixed (same session):** `ReturnBaseStuckService.isPassable()` had the same false-positive bug — rejected carpeted/plated path cells as non-passable during return-to-base stuck escape, because its comment falsely claimed pressure plates/carpets have empty collision shapes. Promoted `BotRescueService.isThinWalkablePartialBlock` to package-private and had `isPassable()` delegate both the feet-cell and head-cell checks to it. Rewrote the misleading comment. Left `isPassableForMining` (falling-block predicate) and `isPassableForStanding` (step-up target detection) alone — different semantics.
- Deployed JAR to all three Prism instances (1.21.11, 1.21.10, 1.21.10 TEST) after each change, after confirming game was not running. Verified deployed classes via `javap` (both `isThinWalkablePartialBlock` method in BotRescueService and the cross-class invocation from ReturnBaseStuckService.isPassable).

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

**For any vanilla-game knowledge** (mob behavior, entity classes, item names, block properties, drops, recipes, biome rules), use the **Minecraft Wiki MCP** (`MinecraftWiki_searchWiki`, `MinecraftWiki_getPageSummary`, `MinecraftWiki_getPageSection`) **before relying on training data**. 1.21.11 ships with content that postdates training (e.g. the rideable Nautilus mob, Mounts of Mayhem); training-data assertions about new mobs/items will be wrong.

**For any modding-API question** (Fabric APIs, mixins, registries, networking, screen handlers, server lifecycle hooks, Yarn / Parchment mappings, obfuscated→deobfuscated class lookups), use the **MCModding MCP** (`get_class_details`, `lookup_obfuscated`, `get_method_signature`, etc.) **before guessing or falling back to docs.fabricmc.net or the loom-decompiled jars**. The Parchment-mappings DB covers 1.21.11 down to 1.16.5 with documented class / method / field signatures. See CLAUDE.md "Game / API Knowledge" + "MCP integrations for this work" for the full guidance on both MCPs.

---

# Backlog

Future work items, organized by priority. Not active Ralph criteria — these are candidates for future RALPH_TASK.md iterations.

## P1 — High

### User-reported 2026-05-09 (top criticality)

User-flagged batch from in-game observation against deployed 1.1.93 (latest.log: `~/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/logs/latest.log`). Listed in working ROI order; subsequent sessions should pick top-down.

- [x] **Stand-down hotkey + stop→drop-sweep cooldown (60s)** — ✅ shipped 1.1.95. New per-bot drop-sweep suppression layer in [DropSweepService](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) (`suppressFor`/`isSuppressedFor`). `/bot stop` now sets a 60s suppression. New service [BotStandDownService](src/main/java/net/wcfcarolina13/GameAI/services/BotStandDownService.java) snapshots follow target, stops following, suppresses drop-sweep for 60s, then re-issues follow on tick expiry ("Back in formation."). Companion overlay slot 1 repurposed from duplicate "Stop" to `🪖 Stand Down (60s)`; new `bot standdown` brigadier command. Tap `\` still does plain stop. Final implementation differs from the original spec: timers live in `DropSweepService` (drop-sweep) and `BotStandDownService` (follow snapshot), not `BotHomeService` — closer to the existing per-service ownership pattern.
- [ ] **Creeper self-protection / back-away**: Existing creeper evasion (sprint away when unarmed, [BotEventHandler.java:3794](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3794)) reportedly insufficient — bot still doesn't reliably back off when creepers are near. Audit fuse-distance / armed-vs-unarmed branches and tighten. Likely also needs a dedicated `BotCreeperSafetyService` or expansion of `BotHazardService`.
- [x] **Dangerous-pursuit gate** — ✅ shipped 1.1.97. New [DangerousPursuitGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) composes 4 rules: target >4 blocks below bot → reject; combined light ≤0 at target → reject; 2+ hostiles within 5 blocks of target → reject; non-aggroed mob → require ranged weapon. Wired into [DropSweepService.collectNearbyDrops](src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java) per-drop filter + [BotEventHandler.engageHostiles](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) hostile-filter loop. Self-defense (mob already targeting bot) always passes. Out of scope: route-based fall/light analysis (currently checks target only, not the path); charged-creeper weighting in cluster threshold.
- [ ] **BotTorchHoldService not visibly firing** — added in 1.1.72-1.1.91 batch but user reports torch never appears in bot's hand in dim follow situations. Service IS deployed (in 1.1.93+ JAR) and registered ([Frens.java:1131](src/main/java/net/wcfcarolina13/Frens.java#L1131)). Likely caused by: (a) overly-strict 8-block audible-hostile suppression — in caves/at night there's almost always *some* hostile in 8 blocks → torch hold rejected; (b) foreign-swap detection cycling against other systems that mutate selected slot every tick (combat loadout, AutoFaceEntity), so torch gets re-overridden between 5-tick eval intervals; (c) only logs at `LOGGER.debug` so we can't tell which gate is firing. Diagnostic-first: bump key transitions to `LOGGER.info`, ship a build, see what the gate actually rejects. Possible fix after diagnosis: drop the 8-block audible gate, keep only the 16-block visible-LOS gate. Don't touch until diagnostic confirms the root cause.
- [x] **Locked-gate enclosure respect (drop-sweep + pursuit)** — ✅ shipped 1.1.98. New ray-cast helper [DangerousPursuitGate.crossesLockedGate](src/main/java/net/wcfcarolina13/GameAI/services/DangerousPursuitGate.java) samples ~1 cell per block along the bot→target line and returns true if any sample is a tracked locked door / fence gate / trapdoor. Wired into both `isLocationSafeForPursuit` (drop-sweep) and `engageHostiles` combat-target filter. Caveat: over-rejects when a wall has BOTH a locked gate AND an unlocked gap (line crosses the locked cell while a legitimate path goes around). Under-rejection was the actual user complaint, so the trade-off is acceptable. Reuses existing LockableBlockService — no new wall semantics.
- [x] **Bed selection bugs (two related)** — ✅ shipped 1.1.100. (a) [SleepService.findNearbyBedFeet](src/main/java/net/wcfcarolina13/GameAI/services/SleepService.java) now filters beds with `BedBlock.OCCUPIED == true` and sorts the bot's previously-claimed bed (via `BotHomeService.getLastSleep`) first. Defensive late-occupancy guard added to `tryUseBed`. (b) When all nearby beds are filtered (only-occupied case), the existing placement branch fires with an explicit "Nearby bed is taken. Setting up my own." handoff message. Out of scope: bed reservation across mid-night user step-outs; multi-bot claim contention.
- [~] **Pathfinding cache learning isn't measurably improving** — diagnostic surfacing shipped 1.1.101. [NavHazardCache](src/main/java/net/wcfcarolina13/GameAI/services/navigation/NavHazardCache.java) is the actual learning system (per-cell rejection scoring; pathfinders consult via `penaltyFor`). Wiring is correct — recording fires per `applyMovementInput-reject` (903 today's session), pathfinders read the penalty in both [PathFinder.java:152,187](src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java#L152) and [BaritoneStylePathFinder.java:380](src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java#L380). Penalty hits now log at INFO (throttled 1/s, ≥1.0 penalty); periodic summary every 5 min lists top scoring cells. Architectural caveat: cache helps when alternative routes exist; tight bottleneck doorways (the user's actual stuck cases) have no alternates, so cache doesn't help there even when working perfectly. Out of scope: chat command for on-demand cache dump (`/bot debug nav-hazard`); promotion-event INFO log; tuning `STREAK_PROMOTION_THRESHOLD` after we have real data.
- [ ] **Doorway / pressure plate stuck (still recurring)**: User reports persistent stalls at doorways and pressure plates despite the 1.1.5 → 1.1.16 fix series. Re-read the 2026-04-16 session notes at the top of this file before touching this — the Architectural Concern at line 92 (drop the door-plan state machine entirely; emit door tiles as regular pathfinder waypoints with an `InteractWithDoorGoal`-style observer) is the recommended next move. Discuss with user before attempting; this is a multi-day rework.

### Pre-existing P1 items

- [ ] **Elder Scrolls-style dialogue menu**: Conversation topics, commands, quests
- [ ] **Elder Scrolls-style Journal**: Conversation topics, quests, important information with simple filter search
- [x] **Drop-sweep cobblestone loop**: ✅ shipped. Two-layer fix: (1) [DropSweeper.ensureSpaceForDropSweep:284-286](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L284-L286) only drops items when `chestStoreSucceeded` is true — no offload target → no drop. (2) per-bot TTL self-drop suppression in 1.1.70 (commit `247005e`) — [DropSweeper.java:216-217](src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java#L216-L217) calls `CraftingHelper.isRecentlySelfDropped` to reject pickup of items the bot itself dropped within the last 5 min, killing the inter-sweep reacquisition loop even if guard #1 partially fails.
- [ ] **Idle during fast-travel cooldown**: When a bot wants to fast-travel but has an active cooldown, it should do useful things while waiting (idle hobbies if enabled, chest offloading to nearby existing chests if disabled), then fast-travel when cooldown expires. Currently the bot just sits idle. For sunset→home specifically, don't build new chests — only use existing ones.
- [x] **Axe retrieval from nearby chests**: When the bot runs out of axes during woodcut, check nearby registered chests (via BotChestRegistryService) for wooden/stone/copper axes — nothing better than copper, nothing enchanted. Take one and continue. Currently the bot just stops or mines with bare hands/wrong tool.
- [ ] **Bundle-aware inventory scanning**: Partially shipped. Audited 2026-05-06: lodestone compass ([LodestoneCompassService](src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java)), navigation artifacts ([NavigationArtifactService:312](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L312)), [HoneyCollectSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java), and [BundleService](src/main/java/net/wcfcarolina13/GameAI/services/BundleService.java) + [ArtifactScanner](src/main/java/net/wcfcarolina13/GameAI/services/ArtifactScanner.java) all read `BUNDLE_CONTENTS`. Still raw-slot in food detection (HungerService, isFoodItem, cookAllFoodSync), tool selection (MiningTool, armorUtils, CombatInventoryManager), crafting material checks (CraftingHelper), chest offloading (ChestStoreService). Consider a shared `InventoryIterator` utility that yields both direct slots and bundle contents, so every caller gets bundle support automatically.
- [ ] **Escape-with-full-inventory**: Guard/patrol stuck escape (pillar via `ensureAtSurfaceForHobby`) fails when inventory has no room for scaffold blocks — `"pillar recovery placed no blocks"` repeated every ~12s. Bot stuck in 1-block hole with full cobblestone inventory. Consider: temporarily drop a non-essential stack, pillar out, pick it back up. Or: use cobblestone directly as scaffold material.

## P2 — Medium

### Inventory & Storage

- [ ] **Furnace offload fallback**: When no chest is available but furnaces are nearby, dump fuel-eligible items (leaves, sticks, planks) into the fuel slot and smeltable items into the input slot. Especially useful during patrol when bot accumulates items with no chest infrastructure.
- [x] **Craft chest from wood** — ✅ already done. [ToolProvisionService.ensureChest](src/main/java/net/wcfcarolina13/GameAI/services/ToolProvisionService.java) crafts an 8-plank chest when planks/logs are available. Wired into [ChestStoreService.java:588](src/main/java/net/wcfcarolina13/GameAI/services/ChestStoreService.java#L588) (offload path), HuntSkill (camp), FishingSkill.
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
- [ ] **Base Manager UX polish (carry-forward from 2026-04-20)**: the menu mixes registered bases (yellow `[Base]`) with lodestone compasses (white rows) in one flat list, and `[Home]` means two different things depending on row color. Even the dev got confused. A minimal inline legend landed in 1.1.39 and a "Home & Bases Explained" guide topic was added, but the full fix is: (a) sort rows so registered bases come first, lodestones second; (b) insert section headers (`Registered Bases`, `Lodestone Compasses`); (c) on row hover, show a one-line tooltip describing what clicking `Set Home` will do for that row type; (d) when the user clicks `Set Home` on a row, echo the stored label back in chat (`Jake will treat 'home' as home.`) — so they can immediately verify it took.
- [x] **Named-hostile-mob pacifism (from 2026-04-20 backlog)** — implemented in `aeef62a` (1.1.55). `BotCombatPolicyService.shouldBotAttack` gates engagement; named hostiles still appear in scans so flee fires on damage; per-bot `attackNamedMobs` opt-in toggle reachable via `/bot attack_named_mobs <on|off|toggle> [target]` and the Admin tab "Attack Named Mobs" row. **Manual in-game verification still pending** — see the 6-item test plan in the 2026-05-06 session notes at the top of this file.
- [x] **`CompanionSpellsScreen` cleanup — partial**: Audited 2026-05-06. The legacy screen is **not** fully decommissioned — it's still reached via the dedicated `KEY_OPEN_SPELLS` keybind path in [FrensClient.java:660 → 1578](src/main/java/net/wcfcarolina13/FrensClient.java#L660), the recruit-contact key fall-through after recruitment, and the temporary `-` go-to-spells override (when holding a spell trigger item). `isEyeSpellOnCooldown` / `armEyeSpellCooldown` are also still consumed inside `FrensClient` itself, not just the legacy screen. So the original "delete the file" plan would break the keybind UX. What WAS removable: the dead `openSpellsMenu` method in `BotPlayerInventoryScreen` — defined but never called. Removed in 1.1.68. Open follow-up: decide whether to migrate the `KEY_OPEN_SPELLS` keybind path to also use the unified Spells tab (would let us actually delete the legacy screen) or accept the dual UX as intentional.
- [ ] **Actions-tab Regroup duplicate**: `Regroup` lives in both the Actions tab (`Orders & Travel`) and the Spells tab (Movement). Users may hit the Actions-tab version first; it runs `/bot companion come` which the server then rejects if artifacts are missing. Decide: keep both with a visible "gated" indicator on the Actions copy, or remove Regroup from Actions and leave it in Spells only. Same question for `Return Home` (Actions) vs `Home` (Spells) — distinct actions (`RETURN_HOME` vs `COMPANION_HOME`) so this may just need clearer labels.

### Navigation & Movement

- [ ] **Lodestone fast-travel: pre-check mount placement before bot teleport** (added 2026-05-17). Current asymmetry in 1.1.119: `coTeleportSavedMount` returns a "safe to proceed" boolean and four of five callsites gate `bot.teleport` on it (cross-dim follow handoff, wolf-tp catch-up, `/bot come`, emergency rescue). The fifth — lodestone fast-travel at [NavigationArtifactService.completePostSpawnSetup:1380](src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java#L1380) — teleports the bot FIRST, then attempts the mount. On placement failure the bot arrives alone, the mount stays at source, and only a `LOGGER.warn` fires. Restructure: move the mount-placement feasibility check into the destination-selection phase (before `completePostSpawnSetup` teleports the bot) so the whole travel is refused with a player message, matching the other four callsites. Bigger refactor because the fast-travel pipeline is multi-stage (queue → spawn fake-player → post-spawn setup), so the validation has to thread through the pre-spawn path. Related: same restructure could fix the chorus-recall `teleportAnimalWithBot` callsite at [SpellNavigationNetworkManager.java:214](src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java#L214), which has the same bot-first/mount-second ordering.
- [ ] Swimming parity (surface and underwater, verify behavior matches survival movement)
- [ ] Boat support (enter, exit, navigate) — partial: [TravelMountHandler](src/main/java/net/wcfcarolina13/GameAI/services/TravelMountHandler.java) handles boat mount/dismount/leashed-rejoin sync (incl. ChestBoatEntity); free-form bot-driven boat *navigation* (steer, paddle to a point) is not implemented.
- [ ] Test fishing from a boat
- [ ] Portal following (Nether, End)
- [ ] Cross-realm teleport command
- [ ] Water-aware pickup (wade/bridge)
- [ ] Edge/hole pickup (hop down safely)
- [x] Add shelves and containers to no-break list — ✅ already done. [ProtectedStructureBlockHelper.isProtectedContainer](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedStructureBlockHelper.java) covers bookshelves (incl. chiseled), all chest variants, barrels, hoppers, dispensers, droppers, decorated pots, crafters, brewing stands, furnaces, blast furnaces, smokers, and all 17 shulker box variants. Wired through `isNeverBreakBlock` and consulted from BotStuckService + MovementService.

### Fishing

- [ ] Verify leaf-block clearing when navigating far from shoreline
- [ ] Verify fishing from higher vertical positions (cliffs/piers)
- [ ] In-game check: trigger `/bot fish` while bot is swimming/submerged and verify it relocates to dry shore before first cast
- [ ] **Fishing reach**: Extend "near water" search/acceptance radius
- [ ] **Water location memory**: Store/recall known water locations

### Combat & Safety

- [x] Creeper evasion (sprint away when unarmed) — implemented in [BotEventHandler.java:3794](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3794): default flee sprints away within 6 blocks, raises shield within 4.5, with extra `isIgnited()` boost in scoreThreat (line 3603).
- [x] Protected build zones (no-grief areas) — implemented as [ProtectedZoneService](src/main/java/net/wcfcarolina13/GameAI/services/ProtectedZoneService.java) with AABB zones, persisted per-world; consulted by FeedAnimalsSkill, MiningHazardDetector, and others.
- [ ] Fight with teammates
- [ ] In-game check: stand near passive endermen and confirm bot does not face/aggro them; then provoke one and confirm bot can still target it once hostile
- [ ] In-game check: drop bot from lethal height with/without a water bucket (Overworld), verify clutch attempts near impact and no attempts in ultrawarm dimensions — **code shipped** in [BotFallSafetyService](src/main/java/net/wcfcarolina13/GameAI/services/BotFallSafetyService.java) ("Attempts a last-second water-bucket clutch when a lethal fall is detected"); just unverified in-game.
- [ ] Ride sync verification: mount/dismount mirroring across entities — **code shipped** in [RideSyncService](src/main/java/net/wcfcarolina13/GameAI/services/RideSyncService.java); just unverified in-game.
- [ ] Ride sync leashed persistence: tethered after disconnect/rejoin — **code shipped** in [Frens.java:803-809](src/main/java/net/wcfcarolina13/Frens.java#L803-L809) (`trySecureMountBeforeDismount` + `secureLeashedMountOnDisconnect`); just unverified in-game.

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

- [x] **Hunger-aware task interruption** — ✅ shipped 1.1.102. New helper `HealingService.shouldPauseForStarvation(bot)` returns true iff starving + autoEat fails. Wired into [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java), [CollectDirtSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/CollectDirtSkill.java) (covers MiningSkill), [WoodcutSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) main loop. Skill bails with `flagManualResume`; user feeds bot then `/bot resume`. HuntSkill / FishingSkill / GrassSeedSkill keep their existing in-skill checks. FarmSkill / Bridge / Shelter / Fortify not yet — main-loop boundaries non-obvious; add when symptom hits.
- [x] Till soil, plant seeds, harvest, replant — implemented across [PlantSeedsSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/PlantSeedsSkill.java), [HarvestCropSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HarvestCropSkill.java), and [FarmSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java) (full pipeline: assess site → till → irrigate → plant → harvest → replant; auto-replanting validated 2026-04-08).
- [ ] Create infinite water source — [FarmSkill.java:332+](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java#L332) detects + uses an existing 2×2 still-water basin, but does NOT yet *create* one when none exists. The "create from scratch" path is still open.
- [ ] Animal husbandry (shear, collect meat, pen animals) — partial: shears used for [WoolSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java) (sheep) and [HoneyCollectSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java); auto-hunt collects meat. Breed/pen behavior not done.
- [ ] **Farm underground recovery**: Escape when underground with overhead dirt
- [ ] **Farm chest workflow**: Proactive chest placement/use during farming
- [ ] **Farm irrigation leak patching**: Detection partially shipped — [FarmSkill.java:741](src/main/java/net/wcfcarolina13/GameAI/skills/impl/FarmSkill.java#L741) (`irrigationLeakReason`) flags leaks; the *patch* path (replace flowing-water cells with source / plug missing edges) is still open.
- [ ] Hobby verification: flower picking, feed-animals, hobby hunt behavior
- [x] **HealingService cooked food preference**: ✅ Auto-eat now prefers cooked over raw via two-pass search in [HealingService.findCheapestSafeFood](src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java). Raw meats (`BEEF`, `PORKCHOP`, `MUTTON`, `CHICKEN`, `RABBIT`, `COD`, `SALMON`) are skipped on the first pass and admitted on a second pass only if no cooked food is available. Closes the raw-chicken food-poisoning hole and stops the bot from gnawing raw beef next to a stack of cooked beef. Same `cheapest-within-tier` ordering preserved otherwise. See changelog 2026-05-08.
- [x] **Smoker preference for food cooking** — ✅ shipped 1.1.102. New `FurnacePreference { FOOD, ORE, ANY }` enum threaded through `resolveFurnaceTarget`. FOOD prefers SMOKER (rejects BLAST_FURNACE), ORE prefers BLAST_FURNACE (rejects SMOKER), ANY accepts all. Two-pass selection at every step (commander look-at, shared registry, nearest scan, inventory placement). `cookAllFoodSync` always passes FOOD; `startBatchCookInternal` passes FOOD when `foodOnly`. Smoker crafting (logs + cobblestone non-grid recipe) still out of scope — generic furnace remains the crafting fallback.
- [ ] **Fuel acquisition fallback**: If no fuel in inventory, attempt mini leaf-litter collection before giving up on cooking

### Hunting — Multi-Day Self-Sufficiency (Future Phase)

- [ ] **Hunt camp shelter**: Bot builds a small hut with a bed and door at hunting grounds for multi-day hunts
- [ ] **Hunt self-sufficient resource gathering**: Bot gathers wood/dirt/cobblestone for camp building and chest crafting

### Hobbies (new ideas)

- [x] **Walking dogs** — implemented in 1.1.65 as [BotDogWalkingHobbyService](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java). v1 design choice: opportunistic-only (no detour to find a wolf — fires when bot is within 3 blocks of an eligible sitting wolf during idle time). Sessions 3–10 min, 50% sit-at-home roll, external cancellation via per-tick `wolf.isSitting()` re-read. See changelog 1.1.65 for full notes. Open follow-ups deferred:
  - ✅ Voiced "Going for walkies" / "Who's a good dog?" line on session start — shipped via [BotDogWalkingHobbyService.playSessionStartLine():176-188](src/main/java/net/wcfcarolina13/GameAI/services/BotDogWalkingHobbyService.java#L176-L188), 50/50 between `LINE_WALK_DOGS_GOOD_DOG` and `LINE_WALK_DOGS_WALKIES` ([BotDialogueSounds:908-909](src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java#L908-L909)).
  - Multi-dog sessions (currently 1 wolf per bot).
  - Bot-side detour-to-wolf if user wants the bot to actively seek out sitters (currently fully opportunistic per spec).

### Mining & Resource Gathering

- [x] Tree chopping (safe climbing, late drop collection) — implemented as [WoodcutSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java) (also a registered hobby `woodcut`). Mine-from-outside trunk, scaffold ascent, late drop sweep, hazard scanning, sapling replant — all shipped through 2026-04-03.
- [x] Strip mining with safety offset (sand, gravel, lava) — implemented as [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java); torch placement + falling-block guards in place.
- [ ] **Cubic-area clearing skill (or stripmine extension)**: User wants the bot to clear large rectangular volumes near a base — e.g. excavate a 10×4×10 area for a future build. [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java) is line/branch-shaped only and isn't a fit. Could be a new skill `excavate <w> <h> <d>` that takes a corner anchor + dimensions and runs a layered sweep from top-down (avoids drops landing in unmined cells). Reuse [StripMineSkill](src/main/java/net/wcfcarolina13/GameAI/skills/impl/StripMineSkill.java)'s safety primitives (torch placement, falling-block guards, hazard detection) where possible. User flagged 2026-05-09 as a follow-up to the protected-zone-override work.
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

### Dialogue / Voiced Lines (backlog 2026-05-04)

New mob-proximity and context-triggered ambient lines. All would extend the existing dialogue pipeline (`PetProximityReactionService` / `CompanionOverheadDialogueService` / `BotDialoguePlayer` / `BotDialogueSounds` + subtitle map in `BotDialoguePlayer.SUBTITLE_MAP` + `DialogueTextMapper.EXACT_MAP`). New OGGs need to be staged + wired through `BotDialogueSounds` constants.

**Cute-animal "can we keep it?" pool** — fires near untamed cute mobs. Candidates: foxes, ocelots, axolotls, pandas, bees, rabbits, parrots, sniffers (already have own line below), turtles. Long cooldown (~5–10 min/bot).

**Pandas** — variant-specific lines keyed off `PandaEntity.getMainGene()` (NORMAL / LAZY / WORRIED / PLAYFUL / WEAK / BROWN / AGGRESSIVE). At minimum:

- Worried → "That panda looks stressed."
- Lazy → reference to flopping over.
- Brown → rare-variant callout.
- Aggressive → wariness line.

**Foxes & ocelots** — "Don't let it near the chickens." Single shared pool, fires when a fox or ocelot is within ~12 blocks AND a chicken is within ~12 blocks of the bot.

**Cats** — literal "Meow." line near tamed cats. Probably a separate pool from the existing tamed-animal-nearby pool (this one isn't a quality assessment).

**Zombified piglins** — "What's up, porkchop?" near `ZombifiedPiglinEntity` (current name; was "zombie pigman" pre-1.16).

**Hoglins** — "If they give us gravel again I'm going on a bacon spree." near `HoglinEntity`. (Reference to piglins bartering gravel.)

**Piglin brutes** — "That one's bigger than the others!" near `PiglinBruteEntity`. (User originally said "hoglin brute" — there's no such mob; this is the piglin brute.)

**Sniffers** — "Dinosaur." or "That's the cutest thing I've ever seen." near `SnifferEntity`.

**Vexes** — "Goblins with wings! Duck and cover!" near `VexEntity`.

**Guardians** — fires near `GuardianEntity` (ocean monuments). Wiki notes the guardian's eye follows the player and the laser charges over 4 seconds, going purple → yellow. Two trigger states:

- Proximity (mere sighting, ~16 blocks): "It's staring right at me." / "I don't like the way it's looking at us."
- Laser-charging (when guardian has begun beam wind-up on the bot or commander): "Why is it glowing at me?!" / "That beam is gonna hurt — move!"

**Elder guardians** — fires near `ElderGuardianEntity`. Rarer/more-emphatic pool: "That one's the boss. We should leave." / "Mining Fatigue incoming, I just know it." (References the iconic Mining Fatigue debuff Elder Guardians inflict.)

**Squids** — fires near `SquidEntity` underwater. Benign tone:

- "Just a squid." (mundane)
- When ink cloud spawns nearby (squid was hit): "...Ew."

**Glow squids** — fires near `GlowSquidEntity` (deep dark water, Y < 30). Curious tone:

- "Pretty." / "It's glowing." (single-word delivery preferred, since it's an "aqua luminescent" rare passive)
- "Grab the ink — that color's rare." (only the glow variant drops glow ink sacs)

**Dolphins** — "Did you see that dolphin?" Fires when a `DolphinEntity` enters the bot's view cone within ~16 blocks.

**Nautilus, untamed** — "Never going near the ocean again." Fires near a wild `NautilusEntity` (or whatever the entity class is named in 1.21.11; per [minecraft.wiki/w/Nautilus](https://minecraft.wiki/w/Nautilus) they're neutral mobs that spawn 1–3 per group in all ocean biomes between Y 38–58, retaliate when attacked, and dash at pufferfish — so the fearful-traveler tone fits).

**Nautilus, tamed** — "You can actually ride one of these?" Fires near a tamed nautilus (pufferfish-tamed, saddle-equipped). Per the wiki, ridden nautiluses grant "Breath of the Nautilus" (oxygen-pause underwater) and have a dash ability — a second optional line could reference the dash: "It just *jumped* through the water!" Confirm the entity class and tamed-flag accessor at implementation time.

**Redstone machines (proximity)** — fires near complex redstone setups. Lines: "Tech-o-no-lo-hee-ah" / "We literally went to hell and back to build this."

- Lowest-impact detection proposal: on the existing 20-tick idle scan, count powered redstone components (repeaters, comparators, observers, pistons, dispensers) with `block.hasComparatorOutput()` or `state.get(Properties.POWERED) == true` in a 5×5×5 box around the bot. Threshold ≥ 4 components AND ≥ 2 distinct block types triggers the pool. 90s cooldown. Skips if bot is already in a pre-classified base structure.

**Mob-crusher detection (anti-cruelty line)** — "Totally humane." / "100% cruelty free." / "There's a special place in the Nether for whoever built this."

- Lowest-impact detection proposal: scan within ~8 blocks for any `BlockPos` containing ≥ 6 living entities of the same passive type (cows, sheep, pigs, chickens, villagers — explicitly **excludes** hostile mobs per user spec, so skeleton/zombie grinders don't fire). Use `world.getEntitiesByClass(LivingEntity.class, smallBox, …)` filtered to passives, grouped by type. Long cooldown (~10 min/bot) since the line is editorial, not a scan-frequent reaction.

**"That's a quality animal."** — ✅ scope down + slow down shipped.

- Pool split landed: [PetProximityReactionService.java:79-87](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L79-L87) defines `ANIMAL_WELL_BEHAVED_LINES` (broad tamed-non-wolf trigger, 90s cooldown) and `MOUNT_QUALITY_LINES` ("That's a quality animal", mount-only trigger via `hasNearbyMountAnimal` at [lines 213-232](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L213-L232) covering tamed `AbstractHorseEntity` / any `CamelEntity` / `LlamaEntity` & `TraderLlamaEntity` as `AbstractHorseEntity` subclasses).
- Cooldown bumped: [line 36](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L36) — `MOUNT_QUALITY_COOLDOWN_MS = 5L * 60_000L`.

## Separate Version Ports (Backlogged — Low Priority)

Shelved 2026-05-06. Not on roadmap unless a long-term contributor commits to maintaining a parallel branch.

- [ ] **1.21.1 backport** — A user requested 1.21.1 compatibility. Real port, not a config tweak: ~10 point releases of yarn/registry/component drift between 1.21.1 and 1.21.11. High-risk surfaces are item components (`DataComponentTypes` reworked in 1.21.2), networking payload codecs, fake-player/`ServerPlayerEntity` constructor signatures, screen handler XP-sync, and any post-1.21.1 vanilla content references (e.g. rideable Nautilus). Approach if revived: fork `backport/1.21.1` branch, freeze scope to core companion + skills (no fortify-village), accept it will lag main. Alternative is Stonecutter/preprocessor multi-version build — more upfront work, sustainable long-term. Don't start without a contributor signed up to maintain it.

## LLM Integration (Future)

- [ ] Phase 1+: Core architecture, toggles, identity & memory, routing, performance, social awareness, integration & testing
