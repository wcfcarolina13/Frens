# Multi-step navigation: design scope for 1.1.240 (2026-10-14)

Bradley, watching the sandbox (screenshots 23:02): "the bot's in a situation where multi-step navigation would be quite beneficial. Instead of slamming against a cliff, it should try walking around toward more traversable land, using scaffolding to reach its target", and "breaking blocks, scaffolding, and simply walking around are all options a human player would use to reach its destination with little effort here."

Case: Bob, no tools, woodcut from a pond-side gully at 22,63,126 (2 sand blocks and a stone cliff in front, a terraced 1-block-step dirt slope to one side), target tree -12,68,152 (nearer trees are Spawn-base protected). He never moved in 7 minutes. Sandbox log 23:01:37-23:08.

Scoped read-only by a Fable agent on 1.1.239 code. Line numbers are 1.1.239. MS = `GameAI/services/MovementService.java`, PF = `PathFinding/PathFinder.java`, BS = `PathFinding/BaritoneStylePathFinder.java`, WS = `GameAI/skills/impl/WoodcutSkill.java`.

## Code path

WS:2805-2812 DIRECT plan → `MovementService.execute` WS:704 → moveDirect MS:663 → moveTo MS:754 → walkTo MS:855 → `PathFinder.calculatePath` MS:927 → `USE_BARITONE_STYLE` false (PF:27) → `calculatePathBidirectional` PF:113. `PATHFIND_TIMEOUT_MS = 10_000` (PF:82), checked every 512 iterations (PF:142), so "1536 iterations" means 3 checks. That's about 7.7 ms per iteration.

**Baritone is OFF by default** (`ManualConfig.java:92 baritonePathfinderEnabled = false`, copied at Frens.java:903, GUI toggle BotPlayerInventoryScreen.java:6576-6581). The auto-memory note saying it's on was wrong. Baritone is used live only for 200 ms reachability probes (PF:108, BotAutoReturnSunsetService.java:856).

After an empty path: local escape "-nop-path", then `walkDirect` (≤4.5 s), then a 3 s `pursuitUntilClose` toward a target 42 blocks away.

## Root causes

1. **Campfire scan dominates A* (#13), verified.** PF:678 calls `isNearExposedCampfire(..,2)` for every candidate (up to 14 per node), and that reads 75 cells each (BotCampfireAvoidanceService.java:221-235). That comes to about 2,000 block reads per iteration. BS:20-28 already documents the fix (a floor-only check, BS:404-416, "~300x reduction"). `PriorityQueue.remove` is O(n) at PF:167/201 (minor).
2. **No candidate passability.** PF:638-643 adds flat/up/down candidates, and the filter PF:676-690 checks only campfire, magma and floor. The raw Up move is never checked. `tagBlocks` PF:498-520 silently lifts (`pos.up()`) or drops nodes it can't stand on. Result: `Total raw nodes: 6` → `pathLen=5` with a dy=2 hop onto the sand. That is the "A* found a path in 78 ms but walk blocked near 21,66,127" staging failure. Baritone's Up move (BS:205) is an equally un-executable pillar.
3. **Local escape repeats one impossible route.** `searchForLocalEscapeRoute` MS:1411 starts from `player.getBlockPos()` (MS:1319). A first hop at y65 means the bot was read at y64, which suggests mid-jump (hypothesis; log `bot=22, 64, 126`). `finalDist=2.62` was identical across 10 attempts. `executeLocalEscapeCandidate` MS:1191 returns true when the block pos merely changes (jump bob). That resets walkDirect's stagnation (MS:2678-2681), so the step-up and mine assists (MS:2706-2716) never run. There is no failed-route memory: `LOCAL_ESCAPE_COMMITMENTS` lasts 2.2 s and is bypassed when stagnant or in precision mode, and woodcut wipes `LAST_WALK_ATTEMPT` after every failure (WS:2819).
4. **Woodcut per target from an unmoved cell:** approach-direct + approach-plan + 4 stands + post-abandon reposition = 7 × ~15 s ≈ 100 s.
5. **0 scaffold:** gather radius 3 (ScaffoldMaterialPolicy.java:24, feet/head rows); all 4 dirt cells were Spawn-base protected.
6. **No break/place moves in either finder** (BS:190-230 has walk moves only). Executors exist: `preclearPlannedStepUps` MS:3368 (dy=1 only), `tryMineObstructionToward` MS:3560 (sand by hand allowed by `ObstructionMinePolicy`), `ScaffoldService.pillarUp`, `BridgeScaffoldService`, woodcut `prepareReach` / `tryBoundedTerrainCorrection`.

## Design layers

- **(a) A* speed:** replace the per-candidate campfire scan with Baritone's floor check or a per-search campfire set over the search box. Expected ≥50× fewer block reads. Field: `grep "PathFinder\] mode=bidir elapsed="` under 500 ms for 42 blocks, `grep -c "timed out after"` = 0. Size S, low risk.
- **(b) Partial-path legs:** a best-so-far path on timeout (BS has it, BS:184), walked as a leg, then replan. Continue while each leg makes ≥3 blocks of progress, up to 8 legs (`NavLegPolicy`). Size M, medium risk.
- **(c) Failed-route memory + grounded origin:** `FailedRouteMemory` (bot, origin cell, first hop), TTL 30 s. Evaluate escapes only on the ground. `executeLocalEscapeCandidate` succeeds only when it reaches the target. Size S, low risk.
- **(d) Neighbour passability:** body and head passable for every candidate. Drop the raw Up unless a PLACE move is modelled. `tagBlocks` stops lifting and dropping. Field: raw nodes == pathLen, no `walk blocked near` after `pathLen>0`. Size S.
- **(e) Costed break / place / walk (Bradley's ask):** pure `TerrainMovePolicy.choose(detourCost, breakTicks, territoryAllowed, scaffoldCount, rise)` → WALK_AROUND / BREAK (≤100 ticks) / PLACE (one block turns a 2-rise into a stair), wired into `executeStuckRecoveryHeuristics` MS:2801 in place of the fixed order. In-planner BREAK/PLACE moves (PathNode move kind, costs) are size L. Also widen scaffold gathering to radius 6, unprotected cells first.
- **(f) Woodcut skip:** after a "no walkable path" from a cell the bot hasn't left, skip that target's remaining stands and the post-abandon reposition. Size S.

## Recommendation

1.1.240 = (d) + (a) + (c) + (f) + the stuck-cell `TerrainMovePolicy` slice of (e). Defer (b) legs and in-planner BREAK/PLACE to 1.1.241.

Riskiest assumption, test first with no code: toggle Baritone in the GUI in the sandbox and rerun woodcut from the gully. If `Using best-so-far` never fires or legs stall at the cliff, walking alone can't find the terrace and (e) comes before (b). Also log `isOnGround()` and `getY()` at MS:1319 to confirm the mid-air origin.

## Decisions for Bradley

1. Make Baritone the default finder? (Production default is off.)
2. Let bots break or gather natural blocks next to the Spawn-base protection (the cause of 0 scaffold), or keep protection strict?
3. Accept bots leaving 1-block dirt stairs (PLACE) without tearing them down?
