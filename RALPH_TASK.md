---
task: Named-hostile-mob pacifism — bot ignores & flees from name-tagged hostiles
test_command: "./gradlew build -x test"
---

## Session Notes 2026-04-20 — Handoff for next session (deployed: 1.1.41)

**Current deployed version: 1.1.41** on all three Prism instances (1.21.11, 1.21.10, 1.21.10 TEST). See the top of [changelog.md](changelog.md) for the five 2026-04-20 entries that shipped in this session:

1. **1.1.38** — Creeper line swapped to A/B-tested take; kraken lines removed; 24 `map`-tagged OGGs staged from `april_2026_v1_1_36_fill`.
2. **1.1.39** — `SkillResumeService.clear()` now wipes `SUNRISE_RESUME_BY_BOT` so `/bot stop` truly clears pending fishing resume; `executeFollow` calls `clearAndNotify` + `BotAutoReturnSunsetService.clearSession` so `/bot follow` is a proper take-manual-control signal; Base Manager got an inline legend + new `bases_home_explained` guide topic.
3. **1.1.39** — Fast-travel gate reason now surfaces in the departure chat (`Jake has departed §7(fast-travel: lodestone compass, instant-class)§e …`); new `fast_travel_gates` guide topic under Basics.
4. **1.1.40** — Unified spells menu: Regroup / Summon / Home / Remote Guidance / Chorus Recall / Soul of Ender / Remote Inventory / Enchant / Anvil all now live in the Spells tab under Movement / Travel / Remote Access headers. `✦` Spells button redirects to the tab in-place instead of opening `CompanionSpellsScreen`.
5. **1.1.41** — Spell-tab section headers now use the same dark-row + accent-underline styling as Action-tab headers, with an amethyst-purple accent (`#6A4A8C` / text `#D4B5E6`) to differentiate tabs.

All five are validated in-game by the user. No regressions reported.

### Next session's task: Named-hostile-mob pacifism

**User intent:** When the player nametags a hostile mob (e.g. a zombie in a mob-collection farm, a skeleton display, a named raid captain set aside for decoration), the bot should treat it as passive — **do not attack**, and **if the named mob attacks the bot, flee instead of fighting back**. This protects player-curated mob setups from a companion that would otherwise clear them on sight.

### Design (pre-baked — don't re-derive)

The naive fix — `if (entity.hasCustomName()) return false;` in `EntityUtil.isHostile(Entity)` — is **too blunt**. It would make the bot ignore the named mob entirely, including damage response. We still want threat-detection to see the named mob so flee behavior can fire. The right split:

- **Keep `EntityUtil.isHostile()` as-is** — threat detection remains accurate.
- **Add a new filter at the attack-engagement boundary** — a method like `BotCombatPolicyService.shouldBotAttack(Entity, ServerPlayerEntity)` that returns `false` for named hostiles (unless the per-bot opt-in toggle is ON).
- **On damage from a named hostile, trigger flee** — `BotFleeService.fleeFromEntity(bot, attacker, radius, …)`, prefer putting a wall between bot and attacker.
- **Per-bot toggle** — `BotHomeService.setBotAttackNamedHostiles(bot, boolean)` (new persisted flag, default `false`), with a companion Admin UI row `Attack Named Hostiles` in the Admin tab so users who *want* the bot to still defend can opt in. Follow the same pattern as `autoReturnPreferLastBedAtSunset` etc.

### Exact hook points

Grep these and add the `shouldBotAttack` gate:

- **`BotAnimalDefenseService.markAttackerForDefense`** at [BotAnimalDefenseService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java) — the Step-1 scan at line ~181 calls `BotThreatService.findHostilesAround(bot, HOSTILE_SCAN_RADIUS)` then iterates. Gate the engagement at line ~192 (`canEngage` branch) via the new filter. The Step-2 watch-list reverse scan has the same pattern. **Step 1/2 should still CALL the scan** (we want to see named mobs for flee-trigger purposes), just not mark them for defense.
- **`BotCombatCalloutService`** — any place that sets an attack target or selects from the hostiles list. Same filter.
- **`BotRLActionService`** — the RL agent's combat action selection. Filter the candidate set before picking.
- **`BotEventHandler`** — search for `attack` or `setTarget` sites and gate.
- **`BotMutualAidService`** — ally-threat engagement; apply filter so bots don't help each other attack named mobs.

### Flee hook

When a named hostile damages the bot:

- Listen on damage intake (there's existing `onBotHurt` / `noteObstructDamage` / similar hooks — search for `DamageSource` + bot handling).
- If attacker is a named hostile AND `attackNamedHostiles` toggle is `false`, call `BotFleeService.fleeFromEntity(bot, attacker, 12.0, …)`.
- Check `BotFleeService` signature first — if no `fleeFromEntity` exists, add one. Prefer existing flee primitives (`BotFleeService` exists per grep).

### Test plan

Run all of these in-game before marking done:

1. Spawn a zombie, name-tag it with "Bob" (vanilla anvil-rename + right-click on zombie). Bot should NOT engage. Bot should still include "Bob" in warning overhead lines (`§c(out-of-range)` etc. — Step-1 scan sees the threat).
2. Let "Bob" hit the bot. Bot should flee — move away, try to interpose a wall.
3. Spawn a second un-named zombie near "Bob". Bot should attack the un-named one while still avoiding "Bob".
4. Toggle `Attack Named Hostiles` ON for the bot. Repeat #1-#2 — bot should now engage "Bob" normally (opt-in behavior preserved).
5. Named raid captain during a raid — verify bot doesn't break the Bad Omen mechanic. (If the named captain is aggressive and other raiders spawn, the bot should still engage the non-named raiders.)
6. Confirm no regression in `BotAnimalDefenseService.isDefendedEntity` — named mobs should not accidentally become defended because they have a custom name.

### Build verification

Standard: `./gradlew build -x test` (the `test_command` above). No automated tests exist for combat targeting; the test plan above is the verification contract.

### Backlog items pre-existing from prior sessions

See the P2 Commands / UX section below for:

- **Base Manager UX polish (2026-04-20 carry-forward)** — sort rows / section headers / hover tooltip / Set Home chat echo.
- **Delete legacy `CompanionSpellsScreen` (post-1.1.40 cutover)** — the unified tab is validated; safe to remove the dead screen and its references.
- **Actions-tab Regroup duplicate** — decide whether to keep `Regroup` / `Return Home` in both Actions and Spells or consolidate.

Those are all independent follow-ups; don't block the main task on them.

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

**For any vanilla-game knowledge** (mob behavior, entity classes, item names, block properties, drops, recipes, biome rules), use the **Minecraft Wiki MCP** (`MinecraftWiki_searchWiki`, `MinecraftWiki_getPageSummary`, `MinecraftWiki_getPageSection`) **before relying on training data**. 1.21.11 ships with content that postdates training (e.g. the rideable Nautilus mob, Mounts of Mayhem); training-data assertions about new mobs/items will be wrong. See CLAUDE.md "Game / API Knowledge" + "MCP integrations for this work" for the full guidance.

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
- [ ] **Base Manager UX polish (carry-forward from 2026-04-20)**: the menu mixes registered bases (yellow `[Base]`) with lodestone compasses (white rows) in one flat list, and `[Home]` means two different things depending on row color. Even the dev got confused. A minimal inline legend landed in 1.1.39 and a "Home & Bases Explained" guide topic was added, but the full fix is: (a) sort rows so registered bases come first, lodestones second; (b) insert section headers (`Registered Bases`, `Lodestone Compasses`); (c) on row hover, show a one-line tooltip describing what clicking `Set Home` will do for that row type; (d) when the user clicks `Set Home` on a row, echo the stored label back in chat (`Jake will treat 'home' as home.`) — so they can immediately verify it took.
- [ ] **Named-hostile-mob pacifism (from 2026-04-20 backlog)**: when targeting hostile mobs for combat, skip any mob with a custom name tag. If a named hostile does hit the bot, engage flee behavior (`BotFleeService.fleeFromEntity` with a modest radius, prefer putting a wall between bot and attacker). Keeps player mob-collection farms/displays safe. Add a toggle so players who want "bot still fights back" can opt in. Hook points: `BotCombatCalloutService`, `BotAnimalDefenseService`, target-selection in `BotEventHandler`.
- [ ] **Delete legacy `CompanionSpellsScreen` (post-1.1.40 cutover)**: the unified Spells tab in `BotPlayerInventoryScreen` now covers Regroup / Summon / Home / Remote Guidance / Chorus Recall / Soul of Ender / Remote Inventory / Enchant / Anvil. The `✦` button was redirected to switch tabs in-place. `CompanionSpellsScreen.java` and the `openSpellsMenu` method are no longer reachable through the UI, but were left in the codebase for one session so the unified tab can be verified. Once validated: delete `CompanionSpellsScreen.java`, remove `openSpellsMenu` from `BotPlayerInventoryScreen`, scrub `FrensClient.isEyeSpellOnCooldown()` / `armEyeSpellCooldown()` if they're only used by the legacy screen, and drop unused imports.
- [ ] **Actions-tab Regroup duplicate**: `Regroup` lives in both the Actions tab (`Orders & Travel`) and the Spells tab (Movement). Users may hit the Actions-tab version first; it runs `/bot companion come` which the server then rejects if artifacts are missing. Decide: keep both with a visible "gated" indicator on the Actions copy, or remove Regroup from Actions and leave it in Spells only. Same question for `Return Home` (Actions) vs `Home` (Spells) — distinct actions (`RETURN_HOME` vs `COMPANION_HOME`) so this may just need clearer labels.

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

### Hobbies (new ideas)

- [ ] **Walking dogs (2026-05-05)**: New hobby — when the bot is idle and a nearby unnamed tamed wolf is sitting, the bot walks the dog for a while. Composes alongside other hobbies; doesn't claim a TaskService slot.
  - **Pickup conditions:** unnamed (`!hasCustomName()`) tamed `WolfEntity` (`isTamed() && isSitting()`) within ~12 blocks. Wolf must be tamed by anyone (commander, the bot itself, another bot — doesn't matter), but excluded if it has a custom name (player-curated pet, hands off).
  - **Bot performs the interaction physically, like a player would:** walk into 2-block reach + line-of-sight, then activate the wolf's sit/stand toggle the same way a right-click does. Spec says "the way a player would" — so use the existing `BotActions` interact path (`useOnEntity` style), not direct `setSitting(false)` mutation. The activation primitive should mirror what `/bot come <wolf>`-equivalent code already does for entity interaction.
  - **Walk session:** wolf follows on its own (vanilla follow-owner-while-standing AI). Bot continues whatever else it was going to do (other hobbies, chest offload, idle wander). Random duration ~3–10 minutes per session.
  - **Dismissal:** at end of session OR when bot returns to a registered base / last-slept bed (use `BotHomeService.getHomeBaseLocation` + `getLastSleptBed`), random ~50% roll to order a sit. Same physical-interaction rule — walk to range + LoS + activate. If the wolf has wandered off and isn't reachable, just drop the session (wolf reverts to standing-and-following until vanilla AI sits it down or the player intervenes).
  - **External cancellation:** if anyone else (commander, another bot, another player) orders the wolf to sit while the bot's session is active, end the session smoothly — don't try to re-stand it. Detect via `WolfEntity.isSitting()` flipping true while we still hold the session, OR via `TameableEntity` data-tracker change events. Either way, this means the bot must not assume the wolf is standing just because it ordered stand earlier; check on each tick.
  - **Concurrency:** runs on its own light tick handler (BotIdleHobbiesService side-channel or new `BotDogWalkingHobbyService`), NOT through `TaskService.beginSkill()`. The bot doesn't drop its current task to walk the dog — the dog tags along. So this is implemented as a passive companion-tracker, not a skill.
  - **Hobby gate:** new entry in `HOBBY_BIT_ORDER` (append-only — see [project_per_hobby_toggles.md] memory). Default ON. Reachable from the Configure Hobbies menu.
  - **Existing scaffolding to reuse:**
    - `WolfEntity.isSitting()` / `setSitting()` for state checks (don't directly call setSitting — use the interaction path).
    - `BotActions.useOnEntity` (or whatever the bot's right-click-on-entity primitive is — grep first).
    - `MovementService.execute(DIRECT)` to walk to the wolf, with LoS check before the interact swing.
    - `BotHomeService.getHomeBaseLocation(bot)` + `getLastSleptBed(bot)` for the "back at home" check.
    - `BotPersistenceService` for per-bot session state (active wolf UUID, session-start tick).
  - **Open design questions for next session before implementing:**
    - Should the bot have a max # of dogs walked simultaneously? (Spec doesn't say. Default to 1 to keep behavior legible.)
    - What happens when the wolf teleports away (vanilla owner-teleport on distance > 12)? Does the session end, or does the bot try to relocate? Default: session ends, since the wolf is back near its actual owner.
    - Voiced line opportunity: "Who's a good dog?" / "Going for walkies." pool when starting a session.

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

**"That's a quality animal."** — scope down + slow down.

- Currently in [PetProximityReactionService.java:67-70](src/main/java/net/wcfcarolina13/GameAI/services/PetProximityReactionService.java#L67-L70). `ANIMAL_NEARBY_LINES` fires on **any tamed non-wolf animal** (`hasNearbyTamedNonWolfAnimal` matches all `TameableEntity` + all `AbstractHorseEntity`), 20% roll per 20-tick check, 90s cooldown. With a single horse + a cat + a parrot in range it effectively fires every 90s — that's why it feels constant.
- Fix: split the pool. Keep "I respect a well-behaved animal." on the broad tamed-animal trigger but rebuild "That's a quality animal." to fire only when an `AbstractHorseEntity`, `CamelEntity`, or `LlamaEntity`/`TraderLlamaEntity` is tamed-and-nearby. Also bump `ANIMAL_NEARBY_COOLDOWN_MS` from 90_000L to ~300_000L (5 min) for the quality pool specifically.

## LLM Integration (Future)

- [ ] Phase 1+: Core architecture, toggles, identity & memory, routing, performance, social awareness, integration & testing
