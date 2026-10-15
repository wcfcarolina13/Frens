# Wall-off night shelter: rebuild design (audit 5a, answer 5)

Ruling (DEAD_CODE_AUDIT_2026-10.md §5a.5): rebuild wall-off as a feature. After the village-house check, before cliff-dig. Needs ≥4 blocks, walks with the real mover and confirms arrival, seals 1-2 open faces of a natural overhang, accepts only if `assessShelterAt` validates, else falls through. Records placed blocks for morning teardown. Fixes the `isSolidBlock` footing check and the off-thread reads. Leaves no hole (dig-down bunkers caused the 1.1.235-1.1.237 stuck-in-a-hole bugs: 1.1.236 "Stuck in a shelter hole", 1.1.237 "Capped pit no longer counts as the surface").

All references are method names in `BotFleeService` unless stated; line numbers in facts.md are from f6dc166d and have already shifted (a concurrent agent is deleting `emergencyPillarUp`, `findNearbyTree` and the two unused `runEmergencyTacticChain` params). The old `findNearbyOverhangShelter` / `emergencyWallOff` bodies are replaced, not reused: they read blocks off-thread, used `isSolidBlock` for footing, slept 2 s instead of confirming arrival, never validated, never recorded.

## 1. Chain position and gate

`runEmergencyTacticChain(bot, gen)` after `tryVillageHouseShelter` returns false and before `findNearbyCliffFace`:

```java
if (bot.getEntityWorld() instanceof ServerWorld wallWorld && tryWallOffShelter(bot, wallWorld, gen)) return;
```

`tryWallOffShelter` returns true only when `SHELTER_ACTIVE` was set. Every other outcome logs and returns false, so cliff-dig / dig-down run exactly as today. Context already guaranteed by the callers (`tryEmergencyTactic`: night or thunder, HP ≤ 50 %; `tryProactiveShelter`: night, sky visible, not peaceful, no base target, commander not within `undergroundProximityBlocks`, cooldown) and by the earlier `prioritizeDryLand` (bot is on land). No new config toggle: the step is inside the existing tactical-shelter / emergency-tactics gates.

Gate inside the step, in this order:
1. `isStaleShelter(bot, gen)` → false.
2. Blocks: `WallOffPolicy.countSealable(countsByItemId, SEAL_IDS) >= 4`. **Deviation from the brief's `hasPlaceableBlocks`**: that helper counts any `BlockItem` (diamond blocks, chests, lodestones), which the brief's own "never valuable blocks" clause forbids. The existing `SEAL_BLOCKS` allowlist (cobble, stone, dirt, deepslate, granite/diorite/andesite, sandstone, netherrack, tuff, calcite, basalt, gravel, sand, planks) is already what cliff-dig seals with and what `BotActions.tryPlaceBlockAt` restricts slot selection to. Counting is done on the server hop (inventory read) and handed to the policy as `Map<String,Integer>` item-id → count. Gravel/sand are kept in the list because they are only placed on top of a solid support cell (§2 rule 6).
3. Hotbar: if `HotbarLockService.isLocked(bot)` and no seal block sits in the hotbar, `ensureHotbarAccess` will return -1 at placement; detect it in the same hop (`hotbarHasSealBlock`) and fall through before walking.

## 2. Candidate search (server-thread snapshot, pure scan)

One bounded hop, `callOnServer(server, supplier, 500 ms, null)` (private copy of the `ChestStoreService.callOnServer` pattern; `BotActions.callOnServerThread` is private). Null (timeout) → log `wall-off: snapshot timeout` and fall through. The hop builds `WallOffPolicy.Snapshot`:

- Box: candidates at `dy ∈ [-2, +2]`, horizontal radius `R = 6`; reads cover `dx,dz ∈ [-7, 7]`, `dy ∈ [-3, +4]` (neighbours, supports, cover): 15×15×8 = 1800 cells, one `getBlockState` each plus `isSkyVisible` and `getFluidState`. Cell codes (byte): `CLEAR` (air, or replaceable with empty collision), `SOLID` (not air, not replaceable, non-empty collision shape), `PARTIAL` (not air, not replaceable, empty collision: torches, signs, rails), `FLUID`, `HAZARD` (lava, fire, soul fire, cactus, magma, campfire, soul campfire, still fluid, same set as `hasImmediateShelterHazard`), `PROTECTED` (inside any `ProtectedZoneService.listZones(world)` zone where `BotTerritoryAuthorizationService.authorizeBlockMutation` would refuse; computed once per zone AABB, not per cell). Plus `sky[]` per cell (`world.isSkyVisible`), hostile offsets (`HostileEntity` within the box, same class `isHostileExposed` uses), bot offset (origin = bot feet), `blocks` count, `hotbarHasSealBlock`.
- Footing rule (the fix): support = `SOLID` (collision non-empty), never `isSolidBlock`. This is `VoxelJunctionService.isStandable`'s support test plus the collision requirement; `isStandable` itself is used again by `assessShelterAt` at accept time.

`WallOffPolicy.rank(Snapshot s, int maxPlacements = 4)` → `List<Candidate(dx, dy, dz, List<Cell> placements, int openFaces, Direction primaryFace, int distSq)>` sorted by placements asc, then distSq asc, capped at 3. A feet cell F is a candidate iff:
1. F-1 `SOLID`; F and F+1 `CLEAR`; F+2 `SOLID` (cover, matches `hasOverheadCover`).
2. `!sky` at F, F+1, F+2 (matches `assessShelterAt` OPEN_SKY).
3. No `FLUID`/`HAZARD` at F, F+1, F-1, or any of the 4 feet-level neighbours.
4. Faces, checked at feet AND head level (the original false-positive bug read feet only): a face is closed iff neighbour N and N+1 are both `SOLID`; open iff at least one of N, N+1 is not `SOLID`; sealable iff every non-`SOLID` one of N, N+1 is `CLEAR` and not `PROTECTED`. Open faces must be 1 or 2 (0 = already enclosed, that is `setShelterFromJoin`'s job; 3-4 = too exposed), all open faces sealable, all other faces closed.
5. Placements = the `CLEAR` cells among open faces' N, N+1; count ≤ `min(4, blocks)`.
6. Support for the feet placement: N-1 `SOLID` for every open face (otherwise `tryPlaceBlockAt` returns `no-solid-support` after the walk). The head cell is supported by the feet block placed just before it.
7. No hostile within 4 blocks of F (`isHostileExposed` would reject it anyway; skip the walk).
8. Whole neighbourhood inside the box (no `OUT` reads).

The bot's own cell may be a candidate (distSq 0, no walk). Nothing is scored for reachability; the real mover decides.

## 3. Walk and arrival

For each ranked candidate (max 3; a 12 s wall-clock budget is checked between candidates, the first always runs; one candidate costs up to ~6.5 s of `moveTowardLocalShelterAnchor` plus 2.5 s centring):
1. `moveTowardLocalShelterAnchor(bot, world, feet, "wall-off")` (existing: `MovementService.planLootApproach` + `MovementService.execute(source, bot, plan, Boolean.FALSE, fastReplan=true, allowPursuit=true, allowSnap=false)` + `nudgeTowardUntilClose(..., 2.5, 4000 ms, 0.20, label)`). Returns `MovementResult.success()` or the ≤2-block fallback.
2. Centring: `nudgeTowardUntilClose(bot, feet, 0.25, 2500 ms, 0.15, "wall-off-center")`, then `WallOffPolicy.arrival(botX, botY, botZ, feet, onGround)` → `ARRIVED` iff `getBlockPos().equals(feet)`, `|x-(fx+0.5)| ≤ 0.3`, `|z-(fz+0.5)| ≤ 0.3`, on ground. Any other verdict → next candidate. Seal cells are adjacent to the stand cell, so a bot straddling the boundary would get a block in its bounding box (the IN_WALL/suffocation class, changelog "persistent IN_WALL / clipping emergency regroup").
3. Abort the whole step (fall through) when `isStaleShelter`, `shouldAbortSurvival`, or `bot.getHealth()` dropped ≥ 4.0 since the step began (the bot is being hit on the way; cliff-dig/dig-down are faster). Log `wall-off: walk aborted reason=`.

## 4. Seal

Order: for each open face, feet cell then head cell (head needs the feet block as support); primary face (the one with 2 placements, else the first) last so the bot's view stays open longest. Each: `BotActions.tryPlaceBlockAt(bot, cell, Direction.UP, SEAL_BLOCKS, allowIntersecting=false)` (it hops to the server thread itself, authorises the claim, restricts the slot to `SEAL_BLOCKS`, refuses `bot-intersects-target`). After each `PlaceResult`: append to the ledger on `success()`; on failure retry once after 300 ms (cliff-dig precedent), then **tear down** every ledger cell (§6 routine, reverse order), log `wall-off: place failed at {} reason={} — tore down {}` and fall through. Note that `tryPlaceBlockAt` may itself fall back to `timeout-or-thread-error`; treated the same.

Verify hop (one `callOnServer`): every ledger cell `blocksMovement()`; any miss → teardown + fall through.

## 5. Accept

`ShelterType.WALL_OFF` added. `assessShelterAt(bot, world, feet, WALL_OFF, capPos = first placed feet cell, entryDir = primaryFace)` on the worker, as dig-down and cliff do today (it reads world state off-thread; that is the existing precedent for all four types, flagged in §9, not fixed here). `isShelterEntranceSecured` gets the exhaustive-switch case:

```java
case WALL_OFF -> { for (Direction d : HORIZONTAL) { if (!blocksMovement(stand.offset(d)) || !blocksMovement(stand.offset(d).up())) yield false; } yield true; }
```

Strictest rule of the four: all 8 side cells solid (the candidate definition guarantees it after sealing; a mob or player breaking one invalidates the shelter on the next `validateAndTickShelter`). On valid: `SHELTER_ACTIVE.put(uuid, new ShelterInfo(tick, WALL_OFF, capPos, primaryFace, standPos, tick, VALID, ledger))`, log, then `HealingService.stabilizeEat(bot, 5)` as the other tactics.

On invalid, split by reason (`WallOffPolicy.teardownOnReject(reason)`), because `assessShelterAt` runs `isHostileExposed` (hostile within 4 blocks) before the entrance rule and in the emergency path a chasing zombie is almost always adjacent at seal time:
- Structural (`NO_STANDABLE_INTERIOR`, `OPEN_SKY`, `NO_OVERHEAD_COVER`, `WATER_INTRUSION`, `HAZARD_EXPOSED`, `ENTRANCE_UNSEALED`) → teardown ledger, log `wall-off rejected reason=`, fall through.
- `HOSTILE_EXPOSED` → re-run only `isShelterEntranceSecured(world, stand, WALL_OFF, ...)` (one hop); if the 8 cells hold, register anyway and log `wall-off: hostile adjacent but sealed, registering`; if not, teardown + fall through. Tearing a finished wall down with the mob outside and then dig-down is exactly the hole the ruling forbids.
- **Ruling for Bradley (cost if wrong):** a sealed 1×1×2 cell registers with a hostile outside (zombies cannot reach, skeletons and creepers need line of sight). Cost: `validateAndTickShelter` re-assesses every tick and drops `SHELTER_ACTIVE` on `HOSTILE_EXPOSED` without launching break-free (pre-existing for all four types, §9), so a mob that lingers outside un-registers the bot while it still stands in a valid wall, and the chain may run again from inside it (own cell has 0 open faces, so wall-off skips; cliff-dig could mine its own wall). Alternative if refused: skip `isHostileExposed` for WALL_OFF inside `assessShelterAt` and `validateAndTickShelter` (the 8-cell rule is the exposure check).

## 6. Ledger and morning teardown

`ShelterInfo` gains `List<BlockPos> placedBlocks` (immutable, `List.of()` for other types) via a delegating 7-arg constructor so the 6 existing construction sites do not change. `breakFreeFromShelter` branch: `info.type() == WALL_OFF && info.capPos() != null` → `breakFreeWallOff(bot, world, info)`:
- For each ledger cell, feet cells first: hop-read the state; mine it with `MiningTool.mineBlock(bot, cell, false).join()` only if `WallOffPolicy.teardownAllowed(itemIdOf(state.getBlock()), SEAL_IDS)` (still a seal-family block: a player may have replaced the bot's dirt with cobble, that is still the wall; glass or a chest is not). Skipped cells are logged. Never mines anything outside the ledger.
- Walk out: DIRECT plan to `stand.offset(entryDir, 2)` if standable else `stand.offset(entryDir)` (cliff precedent `breakFreeCliff`), 600 ms settle, log `broke free from wall-off shelter via {}`.
- Then the common tail: `noteShelterExit`, `escapeToSurface`. Under an overhang at the real surface `isAtSurface` is true (1.1.237: "a tree overhang or porch at the real surface still counts") and nothing more happens. Residual risk: a deep cliff recess may read as underground and trigger surface recovery (pillar/mine); watched in the field, not widened here.
- `validateAndTickShelter`'s dawn/starvation exits and `clearShelterAndBreakFree` all pass the same `ShelterInfo`, so they tear down too.

Persistence: **deferred**, as in 1.1.236. Accepted cost, traced: reload mid-night → `SHELTER_ACTIVE` empty → `setShelterFromJoin` validates the cell as `JOIN_ENCLOSED` (`countOpenFaces ≤ 1`) with `capPos = pos.up(2)` → at dawn `breakFreeDugDown` mines the natural overhang ceiling, leaves the 4 wall blocks standing, `escapeToSurface` pillars out. One hole in a roof and an abandoned wall per reload-at-night. Deferred fix (either): persist the ledger per bot in `BotPersistenceService`, or a JOIN_ENCLOSED exit that tries a horizontal face before the cap.

## 7. Pure policy and tests

`services/WallOffPolicy.java` (no `net.minecraft.*`; `BotFleeService` cannot be loaded under JUnit, `UndergroundLingerPolicy` precedent):
- `record Snapshot(int radius, int dyMin, int dyMax, byte[] cells, boolean[] sky, int[][] hostileOffsets, int blocks, boolean hotbarHasSealBlock)` + `byte cell(dx,dy,dz)`, `boolean sky(dx,dy,dz)`, constants `CLEAR, SOLID, PARTIAL, FLUID, HAZARD, PROTECTED, OUT`.
- `record Cell(int dx, int dy, int dz)`; `enum Face { NORTH, SOUTH, WEST, EAST }` (mapped to `Direction` at the boundary); `record Candidate(Cell feet, List<Cell> placements, int openFaces, Face primaryFace, int distSq)`.
- `static int countSealable(Map<String,Integer> countsByItemId, Set<String> sealIds)`
- `static List<Candidate> rank(Snapshot s, int maxPlacements)`
- `static List<Cell> placementOrder(Candidate c)` (feet before head per face, primary face last)
- `enum Arrival { ARRIVED, OFF_CELL, OFF_CENTRE, AIRBORNE }`; `static Arrival arrival(double x, double y, double z, boolean onGround, int feetX, int feetY, int feetZ)` (absolute block coordinates; `Cell` stays relative to the snapshot origin)
- `static boolean abortWalk(float hpAtStart, float hpNow)` (drop ≥ 4.0)
- `static boolean teardownAllowed(String blockItemId, Set<String> sealIds)`
- `static boolean teardownOnReject(String reason)` (true for the structural reasons, false for `HOSTILE_EXPOSED`)

`WallOffPolicyTest` (services test dir), grid built by a small helper that fills a 15×15×8 array:
1. `countSealable`: dirt 3 + cobble 1 → 4 passes; diamond_block 64 + dirt 3 → 3 fails; empty map → 0.
2. `rank`: one open face (N clear at feet and head) → 1 candidate, 2 placements, primaryFace N.
3. Two open faces → 4 placements; three open faces → rejected.
4. Zero open faces (the bot's own leftover dirt box) → rejected.
5. Head-level gap only (N solid, N+1 clear) counts as an open face with 1 placement (old code missed it).
6. `PARTIAL` (torch) in a face cell → face not sealable → rejected.
7. Sky visible at F+1 → rejected; `PARTIAL` cover at F+2 → rejected (collision rule, the `isSolidBlock` fix).
8. Floor `PARTIAL` (carpet over air) → rejected; floor `SOLID` slab-like partial not modelled (slabs are `SOLID` by collision) → accepted.
9. Missing support under an open-face feet cell (N-1 `CLEAR`) → rejected.
10. `PROTECTED` face cell → rejected; hazard neighbour (lava) → rejected; hostile within 4 → rejected; hostile at 5 → accepted.
11. Blocks = 3 with a 4-placement candidate → only ≤3-placement candidates returned; `maxPlacements` respected.
12. Ordering: fewer placements before closer; tie → closer; cap 3.
13. Candidate whose neighbourhood leaves the box → rejected.
14. `placementOrder`: feet before head within a face; 2-placement face last.
15. `arrival`: exact centre on ground → ARRIVED; 0.4 off → OFF_CENTRE; wrong cell → OFF_CELL; airborne → AIRBORNE.
16. `abortWalk`: 20→16 true, 20→17 false. 17. `teardownAllowed`: cobblestone true, glass false, chest false. 18. `teardownOnReject`: `entrance_unsealed` true, `open_sky` true, `hostile_exposed` false, unknown string true (fail safe toward tearing down).

## 8. Log lines and sandbox recipe

All INFO, prefix `Bot {} wall-off:` so one grep (`wall-off`) covers the step:
- `wall-off: scan r=6 candidates={} blocks={} hotbarOk={}` / `wall-off: no candidate` / `wall-off: snapshot timeout`
- `wall-off: walking to {} placements={} faces={}` / `wall-off: arrival {} at {} target {}` / `wall-off: walk aborted reason={}`
- `wall-off: placed {} ({}/{})` / `wall-off: place failed at {} reason={} — tore down {}`
- `wall-off: sealed at {} placed={}` then `wall-off rejected reason={}` (assess) or `wall-off shelter active at {} blocks={}`
- Morning: `wall-off teardown: removed {}/{} skipped={}` / `broke free from wall-off shelter via {}`
- Existing lines that must follow on failure: `trying emergency cliff-dig` / `trying emergency dig-down`.

Sandbox (instance `mcp-sandbox`, hardened fork API `POST 127.0.0.1:9876/api/cmd`, JAVA_MINECRAFT_AUTOMATION.md): the current sandbox world has commands off, so create a throwaway world with Allow Commands ON (or reuse it via LAN with cheats) for the overhang build.
1. Spawn Jake (roster), note his position with `bot_state name=Jake` (x,y,z = feet). Player stands > `undergroundProximityBlocks` away or in spectator; proactive shelter is suppressed while the commander is near.
2. Build a natural-looking overhang next to him with `send_chat` (control mode): `/fill x-1 y+2 z-1 x+1 y+2 z+1 stone` (roof), `/fill x-1 y z-1 x-1 y+1 z+1 stone` (west wall), `/fill x-1 y z+1 x+1 y+1 z+1 stone` (south wall). Leaves north and east open at feet+head = 4 placements. For a 1-face variant also fill the east wall. Verify with `blocks x,y,z r=2 dy=2`.
3. `/give Jake cobblestone 8` (fake players take `/give`). Negative checks: 3 cobblestone only → `wall-off: scan` line with `blocks=3`, no walk, then cliff-dig/dig-down as today; `/give Jake diamond_block 8` only → `blocks=0`, same fall-through.
4. `set_time 13000` (dusk). Proactive path triggers from the IDLE tick (`proactively seeking shelter`). Expect the wall-off lines, then `blocks` shows the 4 cells filled and Jake inside; `bot_state` sky above head = false.
5. `set_time 1200`: expect `breaking free from tactical shelter (daylight)`, teardown lines, `blocks` shows the 4 cells air again and the roof intact (no hole). Repeat with one wall block replaced by glass before dawn → `skipped=1`, glass stays.
6. Emergency path (speculative, reaching `tryEmergencyTactic` depends on the flee logic deciding fleeing failed): `/effect give Jake instant_damage 1 1` (amplifier 1 = 12 HP, Jake at 8/20, under the 50 % gate; amplifier 0 leaves him at 14/20) with a zombie summoned 10 blocks away at night. Verify in the log which branch fired (`evaluating emergency tactics` vs a fight) before reading the wall-off lines; expect `hostile adjacent but sealed, registering` here.

## 9. Files, size, risks, deferrals

Files: `BotFleeService.java` (enum case, `ShelterInfo` field + delegating ctor, `isShelterEntranceSecured` case, `tryWallOffShelter` + snapshot builder + seal/teardown ≈ 220 lines, `breakFreeWallOff` ≈ 40, `callOnServer` helper ≈ 20, one line in the chain), new `WallOffPolicy.java` (≈ 200), new `WallOffPolicyTest.java` (≈ 250), changelog, field checklist phase, Guide text if the shelter list is user-visible (grep `dig-down` in resources). Integration points: 3 (chain line, `breakFreeFromShelter` branch, `isShelterEntranceSecured` case). Build gate: `./gradlew build` (tests), sequence after the dead-code agent lands since both touch `BotFleeService`.

History: `changelog.md` has no wall-off entry at all (grep `wall-off|walled off`), the DISABLED comment in `runEmergencyTacticChain` is the only record of why it was turned off (false positives from the bot's own placed blocks and partial shelters, success reported without protection). The 1.1.236/1.1.237 entries are the hole bugs the ruling cites.

Risks: (a) the mover may stop 1 short in tight recesses, the centring nudge and `ARRIVED` gate turn that into a fall-through, not a bad seal; (b) `assessShelterAt` off-thread reads remain for all types (pre-existing); (c) deep-recess `escapeToSurface` after teardown (§6); (d) reload cost (§6); (e) a 2-face candidate burns up to 4 blocks that cliff-dig would have gotten back by mining; acceptable, they are reclaimed at dawn; (f) `validateAndTickShelter` drops `SHELTER_ACTIVE` on any later `HOSTILE_EXPOSED` without launching break-free (pre-existing for all types, interacts with the §5 ruling).

Deferred: ledger persistence, torch inside the cell (not in the ruling, keeps placements ≤ 4, `collectNearbyTorch` already handles one), moving `assessShelterAt` to a hop, a horizontal-first JOIN_ENCLOSED exit, spawn-base-specific messaging.
