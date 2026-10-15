# One pathfinder: design for 1.1.242 (2026-10-15)

Bradley (2026-10-15): "a single pathfinding method that's a combination of the best of both". This doc is the contract for replacing `PathFinding/PathFinder.java` (bidirectional A*, "PF") and `PathFinding/BaritoneStylePathFinder.java` ("BS") with one finder, then removing the finder toggle. Starting point: `docs/research/PATHFINDING_RESEARCH_2026-10.md` (sections "What to take from each of our finders", "Merge sketch", "Benchmark harness idea") and `docs/testing/NAV_1.1.240_DESIGN.md`. Line numbers below are 1.1.241. MS = `GameAI/services/MovementService.java`.

Field facts driving the design: on 26 paired searches both finders agreed on reachability, BS p50 about 1 ms vs PF about 120 ms. Walks that had an executable plan reached the goal 61.5% (8/13), so the executor matters as much as the search. Bob's gully (sandbox #21: a sand ledge at 22,63,126 boxed in by a 2-deep pond and 2-high Spawn-protected sand) is only escapable by swimming.

Read-only design. No source files change with this doc.

## Rulings already made (Bradley pre-approved the recommended options)

| Question | Ruling | Where it lands |
|---|---|---|
| DOWNWARD (dig the floor) | Off for companions. A skill opts in through `SearchContext.allowDownward` | `MoveCosts.downward` returns `COST_INF` unless the flag is set. No executor in 1.1.242 |
| PLACE price | In ticks: `blockPlacementPenalty` 20 plus a scarcity term, so the planner may pick a 1-block dirt stair itself. Dirt only, left in the world | `MoveKind.ASCEND_PLACE` |
| Depth floor | Stays a hard limit (night safety) | `SearchBox.minY = min(start.y, goal.y) - 2` |
| Leg cancel | Baritone-style cost verification may cancel a leg. `walkSegment` stagnation stays as the fallback | `CostVerifier` in the executor |
| Protected zones | No penalty for walking through. BREAK and PLACE there are forbidden | `CellProps.PROTECTED` gates `breakTicks` and `placeable` only |
| Benchmark | A release gate for 1.1.242 | `UnifiedFinderBenchmarkTest` in the JUnit suite |
| Spawn protection | Strict | same `PROTECTED` bit, filled from `ProtectedZoneService.isProtected` with the bot owner |
| Wading | Kept (1-deep, dry head) | `TRAVERSE`, `DESCEND`, `ASCEND` landings may wade as today, `WADE_PENALTY` in ticks |
| Swimming | Decide here | Included, minimal, behind `SearchContext.allowSwim` (section 3) |

## 1. Why one finder, and what each one contributes

From the research table, kept:

- From BS: packed-long keys, the chunk cache idea (now behind the terrain adapter), best-so-far partial paths, diagonal corridor clearance, step-down modelling, the cheap floor hazard check, the primary timeout shape.
- From PF: the slab head-clearance rule from `tagBlocks` (PF:505-513), `hasSupportedFloor`, `simplifyPath` as the executor-facing smoother, the 6-block Manhattan segment split, the per-search campfire memo (PF `CampfireMemo`, chunk-section palette check).
- From neither: the bidirectional loop (no partial-path semantics, 10 s timeout on the gully), the raw Up move (already gone in 1.1.240), `PriorityQueue.remove` re-heaps, world reads inside the search.

Added: tick-unit costs, BREAK and PLACE and SWIM as costed moves with per-move `toBreak` and `toPlace` cells, a binary heap with decrease-key, a primitive terrain interface so the search runs under JUnit, a move simulator, a Dijkstra reference, and cost verification in the executor.

## 2. Interfaces, classes, files

All new search code is pure Java over primitives and lives in `src/main/java/net/wcfcarolina13/PathFinding/unified/`. Only two classes in that package import `net.minecraft.*`: `WorldTerrainView` (world reads) and `PlanAdapter` (it builds today's `PathNode` and `Segment`, which carry `BlockPos`). Slice A's package test greps for the import and exempts exactly those two names. Tests construct `VoxelGrid`, never a `BlockState` (CLAUDE.md test policy).

### 2.1 Terrain

```java
package net.wcfcarolina13.PathFinding.unified;

/** Per-cell properties as bit flags, read once per cell per search. */
public final class CellProps {
    public static final int SOLID_FLOOR     = 1;        // a bot can stand on this block's top (opaque, or walkable partial top)
    public static final int PASSABLE_BODY   = 1 << 1;   // a body fits here dry (air, pathable partial, openable wooden door/trapdoor)
    public static final int WATER_WADE      = 1 << 2;   // water with no collision whose cell below has SOLID_FLOOR
    public static final int WATER_DEEP      = 1 << 3;   // water with no collision whose cell below is not a floor
    public static final int LAVA            = 1 << 4;
    public static final int HAZARD          = 1 << 5;   // BotHazardService.isDeadlyBlock, magma, lit campfire, campfire berth
    public static final int PROTECTED       = 1 << 6;   // ProtectedZoneService.isProtected for the bot owner (strict)
    public static final int NEVER_BREAK     = 1 << 7;   // ProtectedStructureBlockHelper.isNeverBreakBlock or hasBlockEntity
    public static final int GRAVITY         = 1 << 8;   // FallingBlock (sand, gravel, concrete powder)
    public static final int SLAB_TOP        = 1 << 9;   // bottom slab or stair top the bot steps onto without a jump
    public static final int PLACEABLE       = 1 << 10;  // air or replaceable, not PROTECTED, has a solid face below
    public static final int LOADED          = 1 << 11;  // chunk loaded. Everything else is 0 when unset
    public static final int LOCKED_DOOR     = 1 << 12;  // LockableBlockService wall: never passable, never breakable
}

public interface TerrainView {
    /** Bit set from CellProps. Must be deterministic for the life of one search. */
    int props(int x, int y, int z);

    /**
     * On-ground break ticks with the best hotbar tool snapshotted into the context
     * (ObstructionMinePolicy.groundBreakTicks), or COST_INF when the cell is air,
     * PROTECTED, NEVER_BREAK, LOCKED_DOOR, liquid, unloaded, or slower than maxBreakTicks.
     */
    int breakTicks(int x, int y, int z);

    int minY();
    int maxY();
}
```

`WorldTerrainView implements TerrainView` (production, `PathFinding/unified/WorldTerrainView.java`): wraps a `ServerWorld`, a 4-slot chunk cache like BS `ChunkCache` (never shared across threads, Baritone #5136), PF's `CampfireMemo` for the 2-block berth, a per-search `Long2IntOpenHashMap` props memo, a `Long2IntOpenHashMap` break-ticks memo, the bot owner UUID for `ProtectedZoneService.isProtected`, the hotbar tool speed table, and the territory predicate (`BotTerritoryAuthorizationService.authorizeBlockMutation`, called at most once per cell). It reuses the existing passability helpers exactly as PF `isPassable` and BS `isPassable` do today (`BotHazardService.isDeadlyBlock`, `WalkablePartialBlocks.isPathable`, `LockableBlockService.isLocked`, iron doors only when open, fence gates never passable, `WadeableWater.isWadeable`, `headFitsOverWater`). Unloaded chunks are never force-loaded.

`VoxelGrid implements TerrainView` (test, `src/test/java/net/wcfcarolina13/PathFinding/unified/VoxelGrid.java`): `byte[][][]` of cell classes parsed from ASCII layers, one string per y level, same glyphs as the MCP `blocks` command (`.` air, `~` water, `!` lava, `@` origin) plus `#` stone (break 150 by hand, INF with the hand-only tool table), `d` dirt (15), `s` sand (gravity, 15), `l` leaves (6), `m` magma, `c` lit campfire, `P` protected stone, `p` protected sand, `_` bottom slab, `L` locked door. Break ticks come from a `ToolTable` the scenario names (`HAND`, `WOOD_PICK`, `STONE_AXE`). `VoxelGrid.copy()`, `set(x,y,z,glyph)` for the simulator.

### 2.2 Context and costs

```java
public record SearchContext(
        TerrainView terrain,
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        boolean canSprint,          // food > 6, Baritone
        boolean allowBreak,         // isBotExecutingTask && !constructionProtection && !OBSTRUCTION_MINE_DISABLED
        boolean allowPlace,         // same gates, plus the owner's territory check at the cell
        boolean allowSwim,          // default true for walkTo, false for probes
        boolean allowDownward,      // default false (ruling)
        int scaffoldCount,          // plain DIRT in the hotbar (countTerrainStairBlocks)
        int maxFall,                // 3
        int maxBreakTicks,          // ObstructionMinePolicy.MAX_GROUND_BREAK_TICKS = 100
        int maxSwimRun,             // 10 cells
        long primaryMs, long failureMs, int nodeCap,
        SearchBox box,              // x,z: start..goal ± 24 (± 48 for goals over 60 away); y: min(start,goal)-2 .. max(start,goal)+16
        LongToDoubleFunction hazardPenaltyTicks,   // NavHazardCache.penaltyFor * STEP_TICKS, snapshotted reads
        LongToDoubleFunction anchorBonusTicks,     // PassageAnchorService.bonusFor * STEP_TICKS
        Set<Long> forbiddenFirstHops,              // leg loop: first hops FailedRoutePolicy remembers, treated as HAZARD (section 5.4)
        long planId)
```

`ActionCosts` (`PathFinding/unified/ActionCosts.java`): the Baritone table in ticks, verified against `ActionCosts.java` upstream on 2026-10-14: `WALK_ONE_BLOCK = 4.633`, `SPRINT_ONE_BLOCK = 3.564`, `WALK_ONE_IN_WATER = 9.091`, `WALK_OFF_BLOCK = 3.706`, `CENTER_AFTER_FALL = 0.927`, `COST_INF = 1_000_000`, `JUMP_PENALTY = 2`, `BLOCK_PLACEMENT_PENALTY = 20`, `BREAK_ADDITIONAL = 2`, `SWIM_ENTRY_PENALTY = 10` (ours). `JUMP_ONE_BLOCK` and `FALL_N[0..maxFall+1]` are computed at class init from the vanilla velocity curve `v(t) = (0.98^t - 1) * -3.92` exactly as Baritone does, and `ActionCostsTest` pins them to three decimals once the values are known, so a later edit cannot drift them silently.

Unit conversion for the old finder-side penalties: in PF and BS one unit was one flat walking step (1.0), so `NavHazardCache`'s cap of 12 meant twelve blocks of detour and `PathMovePolicy.WADE_PENALTY` 1.0 made a wade step cost twice a dry one. The conversion is therefore `STEP_TICKS = WALK_ONE_BLOCK = 4.633`, not 20: a wade step lands at 9.27 ticks (within 2% of Baritone's own `WALK_ONE_IN_WATER`), the hazard cap at 55.6 ticks (still twelve blocks), and the 1.1.240 field-tested wading behaviour is unchanged. `TerrainMovePolicy.TICKS_PER_COST_UNIT` (20) is that policy's own unit and is used only inside the ASCEND_PLACE scarcity term.

`MoveCosts` (`PathFinding/unified/MoveCosts.java`): the one pure cost function. The search, the simulator, the Dijkstra reference and the executor's `CostVerifier` all call it, so they cannot disagree.

```java
public final class MoveCosts {
    /** Returns the move for (from, kind, dir) or null when a precondition fails. ticks == COST_INF means forbidden. */
    public static PlannedMove evaluate(SearchContext ctx, TerrainView t, int x, int y, int z, MoveKind kind, int dx, int dz,
                                       int scaffoldLeft, int swimRun);
    public static double minHorizontal(SearchContext ctx);   // SPRINT or WALK
    public static double ascendBase(SearchContext ctx);      // max(JUMP_ONE_BLOCK, minHorizontal) + JUMP_PENALTY
}
```

### 2.3 Moves and plans

```java
public enum MoveKind { TRAVERSE, DIAGONAL, ASCEND, DESCEND, FALL, ASCEND_PLACE, SWIM, DOWNWARD }

/** One executable step. Cells are packed longs (BlockPos.asLong layout, so PlanAdapter needs no conversion table). */
public record PlannedMove(MoveKind kind,
                          int fromX, int fromY, int fromZ,
                          int toX, int toY, int toZ,
                          long[] toBreak,     // mined in this order before the step, empty for a plain walk
                          long[] toPlace,     // placed before the step, at most one cell in 1.1.242
                          double ticks,
                          int scaffoldLeftAfter,   // node state after this move, so CostVerifier re-evaluates move i without replaying the prefix
                          int swimRunAfter) {
    public boolean isAction() { return toBreak.length > 0 || toPlace.length > 0 || kind == SWIM || kind == FALL || kind == DOWNWARD; }
}

public enum PlanStatus { FULL, PARTIAL, NO_PATH }

public enum NoPathReason { BOXED_IN, GOAL_NOT_STANDABLE, BUDGET, BLOCKED_PROTECTED, BLOCKED_UNBREAKABLE, NO_SCAFFOLD, UNLOADED, NONE }

public record PathPlan(long planId, PlanStatus status, NoPathReason reason, double partialCoefficient,
                       List<PlannedMove> moves, double totalTicks, int nodesExpanded, long elapsedMs) {
    public static PathPlan noPath(long planId, NoPathReason reason, int nodes, long ms);
    public int endX(); public int endY(); public int endZ();
}
```

### 2.4 Search

- `BinaryHeapOpenSet` (`PathFinding/unified/BinaryHeapOpenSet.java`): array heap over `SearchNode`, `heapIndex` on the node for `decreaseKey`, initial capacity 1024. A randomized test (`BinaryHeapOpenSetTest`, 10k ops against a `PriorityQueue` oracle) guards the mineflayer #369 bug class.
- `SearchNode`: `x, y, z, swimRun, scaffoldLeft, packedKey, parent, move (PlannedMove), g, h, f, heapIndex`.
- `UnifiedPathFinder` (`PathFinding/unified/UnifiedPathFinder.java`): `static PathPlan search(SearchContext ctx)`. Section 5.
- `PlanAdapter` (`PathFinding/unified/PlanAdapter.java`): `PathPlan` to today's `PathNode` list and `Segment` queue, plus the executor's unit list. Section 6.
- `MoveSimulator` (`PathFinding/unified/MoveSimulator.java`, main source set, pure): applies a plan to a mutable `TerrainView` copy (`MutableTerrainView` interface with `removeBlock`, `placeBlock`) and reports the first move whose precondition fails. Production uses it for the PathCompare `exec` status against `WorldTerrainView` (read-only overlay: a `Long2IntOpenHashMap` of overrides on top of the world view, so nothing in the world changes).
- `DijkstraReference` (test only): same `MoveCosts`, same state space (position, swimRun), no heuristic, no timeout. Returns the optimal ticks.
- `CostVerifier` (`PathFinding/unified/CostVerifier.java`, pure): section 7.
- `NavLegPolicy` (`GameAI/services/NavLegPolicy.java`, pure): section 5.4.
- `PathPlanIds` (`PathFinding/unified/PathPlanIds.java`): an `AtomicLong` counter, `next()` returns `p<n>`, reset at SERVER_STOPPING. One token, no spaces.

## 3. Move set, tick costs, preconditions

Notation: O = origin standing cell (feet), y = O.y, F = O + dir (the cell in front at feet height), dir is one of 4 cardinals, diagonals only where stated. "Standing cell" = `SOLID_FLOOR` at y-1, body passable or `WATER_WADE`, head (y+1) `PASSABLE_BODY` and dry, and over a wading body the head has no collision at all (`headFitsOverWater`). `C_xz = minHorizontal(ctx)`. `C_asc = ascendBase(ctx)`. `bt(c)` = `breakTicks(c) + BREAK_ADDITIONAL`, INF when forbidden. Every move adds `hazardPenaltyTicks(to)` and subtracts `anchorBonusTicks(to)`, with the bonus clamped so it only eats the penalty part (hazard plus wade), never the base table cost (section 4). Any move whose landing cell is `HAZARD` or `LAVA` or has `HAZARD` as its floor is forbidden. A gravity block directly above the topmost cell of `toBreak` forbids the move (it refills the hole, Baritone's refusal). `LOCKED_DOOR` cells are walls for every move.

| Kind | to | Base ticks | Preconditions (all cells inside the box and LOADED) | toBreak (only when allowBreak, each cell bt < INF) |
|---|---|---|---|---|
| TRAVERSE | F | `C_xz` (+ `WADE_PENALTY` 20 ticks when F is wade) | F standing, or F body/head breakable into a standing cell | F body if not passable, F head if not passable (0 to 2 cells). Never the floor |
| DIAGONAL | O + (dx,0,dz) | `C_xz * sqrt(2)` | landing standing, AND both side corridors (O+dx, O+dz) body and head passable (mineflayer #393). No breaking, no wading, no step |
| ASCEND | F + up | `C_asc` | F solid at y (the step), landing (F.y+1) standing, O.y+2 passable. A `SLAB_TOP` step costs `C_xz` and sets jump=false | landing body (F.y+1) if not passable, landing head (F.y+2) if not passable, O.y+2 if not passable (0 to 3 cells). Rise-2 case: F solid at y and y+1, break F.y+1 so it becomes a 1-step: that is `TerrainMovePolicy` BREAK for rise 2 |
| DESCEND | F + down | `WALK_OFF + FALL_N[1] + CENTER` (+ wade penalty) | F body and head passable (the head lane), F.y-1 standing with floor at F.y-2 | none |
| FALL | F + down(n), n in 2..maxFall | `WALK_OFF + FALL_N[n] + CENTER` | F column passable from F.y down to the landing head, landing standing, landing not HAZARD, floor not HAZARD or magma. Landing in wade water allowed, deep water forbidden (that is SWIM's job) | none |
| ASCEND_PLACE | F + up(2) | `BLOCK_PLACEMENT_PENALTY + JUMP_ONE_BLOCK + C_asc + scarcity` where `scarcity = TerrainMovePolicy.PLACE_COST * TerrainMovePolicy.TICKS_PER_COST_UNIT / scaffoldLeft` | allowPlace, scaffoldLeft > 0, F solid at y and y+1 (rise exactly 2), landing F.y+2 standing, O.y+2 and O.y+3 and F.y+3 passable, O `PLACEABLE` and not `PROTECTED` and territory allows. toPlace = [O]. Decrements scaffoldLeft | none (the executor does not combine break and place in one move) |
| SWIM | O + dir, or F + down (entry), or F + up (exit) | `WALK_ONE_IN_WATER` per cell, `+ SWIM_ENTRY_PENALTY` on entry | allowSwim. A swim cell has `WATER_DEEP` body, dry passable head. Entry from a standing cell at dy 0 or -1. Lateral swim at dy 0 between swim cells (both corridors clear for diagonals is not needed, diagonal swim is not offered). Exit to a standing cell at dy 0 or +1 (vanilla jumps out of water one block). `swimRun` increments per swim cell and the move is forbidden at `maxSwimRun`. `swimRun` is part of the node key for water cells only | none |
| DOWNWARD | O + down | `bt(O.y-1) + FALL_N[1]` | allowDownward, floor breakable, cell under it solid (never dig over air, water, lava, hazard), O.y+1 stays passable | [O.y-1] |

Dropped from the research sketch: the "place the destination floor" variant of ASCEND_PLACE (needs a placement face and has no executor, while the shipped executor and Bradley's "1-block dirt stair" both put the block under the bot), a free-standing PILLAR move (dirt towers), parkour.

Heuristic invariant every kind must satisfy (checked mechanically in `MoveCostsInvariantTest` with the computed table values): `ticks >= C_xz * xzSteps + (C_asc - C_xz) * max(0, rise) + C_down_extra * max(0, drop)`. Worked: ASCEND_PLACE moves 1 xz and rises 2, so it needs `20 + JUMP_ONE_BLOCK + C_asc >= C_xz + 2 * (C_asc - C_xz)`, which reduces to `JUMP_ONE_BLOCK + 20 >= C_asc - C_xz` and holds for any `JUMP_ONE_BLOCK > 0`. SWIM exit moves 1 xz and rises 1, so it needs `WALK_ONE_IN_WATER >= C_asc`, which depends on the computed `JUMP_ONE_BLOCK`. If the test shows it fails, the SWIM exit cost gets a floor of `C_asc` (cheap and still honest, climbing out of water is at least a jump). SWIM entry at dy -1 needs `WALK_ONE_IN_WATER + SWIM_ENTRY_PENALTY >= C_xz + C_down_extra`, which holds because `C_down_extra <= WALK_OFF + FALL_N[1] + CENTER - C_xz` by definition and the left side exceeds that sum.

## 4. Heuristic

`h(n) = C_xz * cheb(dx, dz) * (1 + 1/1000) + up * (C_asc - C_xz) + down * C_down_extra`

- `cheb = max(|dx|, |dz|)` to the goal, `up = max(0, goal.y - n.y)`, `down = max(0, n.y - goal.y)`.
- `C_down_extra = max(0, min over n in 1..maxFall of (WALK_OFF + FALL_N[n] + CENTER - C_xz) / n)`. If `allowDownward`, also `min(..., bt_min + FALL_N[1])` with `bt_min = BREAK_ADDITIONAL` (the cheapest conceivable floor).
- Admissible by the argument in section 3: a path needs at least `cheb` xz-moving steps each costing at least `C_xz`, each unit of net rise needs an ASCEND-class move that costs at least `C_asc - C_xz` more than a horizontal step, each unit of drop at least `C_down_extra` more. On a pure staircase `cheb == up`, so `h == up * C_asc`, exact (this is the double-count the research flagged, resolved by charging only the extra). The 1/1000 tie-break nudge (Amit Patel) is a deliberate 0.1% overestimate, so the benchmark's optimality threshold is a 1.01 ratio to Dijkstra, not 1.0.
- `C_xz` is the exact cheapest horizontal move in the context, not Baritone's fixed 3.563: with `canSprint` false the sprint constant would be inadmissible only in the other direction (an underestimate), but the tighter value finds the same path with fewer nodes. The benchmark reports nodes for both settings once.
- Anchor bonus and admissibility: `PassageAnchorService.bonusFor` may only subtract from the penalty part of an edge (hazard penalty plus wade penalty). The base table cost is a floor. Otherwise an anchored doorway would make a cheaper-than-`C_xz` step and the bound breaks. The hazard penalty is additive ticks, which never breaks admissibility.
- BS's `2.5 * dy` is gone (unit-cost tuning).

## 5. Search loop, timeouts, partial legs, NO_PATH

### 5.1 Loop

Baritone `calculate0` shape:

1. Validate: start inside the box and LOADED. If the goal cell is not standable, snap it to the nearest standable cell within 2 (today `walkTo` does this with `findNearbyStandable`). None: `NO_PATH(GOAL_NOT_STANDABLE)`.
2. Open set = `BinaryHeapOpenSet`, nodes = `Long2ObjectOpenHashMap<SearchNode>` keyed by `pack(x - box.minX, y - box.minY, z - box.minZ, swimRun)` (x 25 bits, z 25 bits, y 9 bits, swimRun 4 bits, all relative to the box). Relative packing is deliberate: BS packs absolute y in 8 bits, and 1.21 worlds span 384 y values, so a box across y -60 and y 196 would collide there. The box is at most 2^25 wide and 512 tall by construction, and `SearchBox` asserts it. fastutil 8.5.18 is on the compile classpath (`./gradlew dependencies --configuration compileClasspath`), imported nowhere yet. Buffers (`heap`, node map, the two memos in `WorldTerrainView`) come from a `ThreadLocal<SearchBuffers>` and are cleared, not reallocated, per search (Baritone #5145 measured 77.8% less time on short searches).
3. Pop the lowest f. Goal test on position only (any swimRun). Expand: for each cardinal, try TRAVERSE, ASCEND, ASCEND_PLACE, DESCEND, FALL 2..maxFall, SWIM entry/lateral/exit. For each diagonal, DIAGONAL. DOWNWARD when allowed. Each candidate calls `MoveCosts.evaluate`, which returns null fast on the first failed precondition (cheapest reads first: box, LOADED, floor, body, head).
4. Edge cost `c = move.ticks + hazard - clampedBonus`. Assert `c > 0` (Baritone throws on cost <= 0, we log once and treat as `C_xz`). Skip when `c >= COST_INF`. Relax when `g + c < existing.g - MIN_IMPROVEMENT (0.01)`, decrease-key in the heap. New nodes record the `PlannedMove` that reached them.
5. Best-so-far: `COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10}`, score `h + g / coeff`, kept per coefficient. `failing` flips false once a node is more than `MIN_DIST_PATH = 5` blocks (Euclidean) from the start.
6. Every 64 pops check `System.currentTimeMillis()`. Stop on: goal popped (FULL), `nodesExpanded >= nodeCap`, `failureMs` passed, or `primaryMs` passed and `!failing`. Open set empty: NO_PATH or PARTIAL from best-so-far.
7. PARTIAL: lowest coefficient with a node past `MIN_DIST_PATH`. Tail cut of 10% only beyond 30 moves (Baritone's `pathCutoffMinimumLength`). A partial never ends on a SWIM cell (walk the cut back to the last standing cell) and never ends mid-action (cut before a move with `toBreak` or `toPlace`).

### 5.2 Budgets by thread

| Caller | Thread | primaryMs | failureMs | nodeCap |
|---|---|---|---|---|
| `MovementService.walkTo` | skill worker | 500 | 2000 | `min(8000, max(1000, 4 * dist^2))` |
| leg replans after the first | worker | 300 | 1200 | same |
| `PathFinder.canReach` probes (BotEventHandler:2610, :5624), sunset `refreshTravelSegment` (BotAutoReturnSunsetService:860) | worker or server | caller's 200 | none (primary is the cap) | 3000 |
| `/bot path` (modCommandRegistry:3251) | server thread | 500 | none | 3000 |
| PathCompare shadow | `frens-pathcompare` | 500 | 2000 | as walkTo |

On the server thread there is no failure timeout: the primary budget is the whole budget, like today's `calculatePath(start, goal, world, timeoutMs)`.

### 5.3 NO_PATH reasons

Refusal counters are kept per reason during expansion (`protectedRefusals`, `unbreakableRefusals`, `noScaffoldRefusals`, `unloadedRefusals`). When the open set empties or the budget ends without a 5-block partial:

- `BOXED_IN`: only the start node was expanded. Replaces `PathFinder.lastSearchBoxedIn()`.
- `UNLOADED`: `unloadedRefusals > 0` and the goal lies in an unloaded chunk.
- `BLOCKED_PROTECTED`: the most frequent refusal was `PROTECTED` and the goal is inside the box.
- `BLOCKED_UNBREAKABLE`: the most frequent refusal was a break cost over `maxBreakTicks` or `NEVER_BREAK`.
- `NO_SCAFFOLD`: ASCEND_PLACE was the only candidate refused at the frontier and `scaffoldCount == 0`.
- `BUDGET`: timeout or node cap with nothing past 5 blocks.

The reason reaches the `[PathFinder]` line (`reason=`) and `WalkResult.detail`, so a skill says it once in chat ("I can't get there, the way is protected") instead of retrying (mineflayer #222 is the failure to avoid). Woodcut's unreachable-target skip (1.1.240) reads the reason instead of the boxed-in flag.

### 5.4 Legs (NavLegPolicy, pure)

```java
public final class NavLegPolicy {
    public static final int MAX_LEGS = 8;
    public static final int MIN_PROGRESS_BLOCKS = 3;
    /** Whether to plan another leg after one ended at (endX,endZ,endY) having started at the previous leg's end. */
    public static boolean continueLegs(int legsDone, double progressBlocksTowardGoal, boolean firstHopRememberedFailed, boolean aborted);
}
```

`walkTo` runs: plan, execute, measure progress (Euclidean distance to goal before minus after), replan from the grounded cell while `continueLegs` says so. A leg whose first hop is in `FailedRoutePolicy` (origin, first hop, TTL 30 s) is dropped and the finder is asked again with that hop marked `HAZARD` through a per-search override set in `SearchContext` (one extra field, `Set<Long> forbiddenFirstHops`). Plan-ahead and `SplicedPath` (Baritone) are a follow-up, not 1.1.242: legs are sequential here.

## 6. Output contract

`PathFinder.calculatePath(...)` keeps its signature and return type during the transition and becomes a thin dispatch (section 9, slice B). New primary entry:

```java
public static PathPlan plan(BlockPos start, BlockPos goal, ServerWorld world, UUID botUuid, String botName, PlanRequest req);
```

`PlanAdapter`:

- `toPathNodes(PathPlan)`: one `PathNode` per move's `to` (plus the start), `jump = kind in {ASCEND, ASCEND_PLACE} && !slabStep`, `walkable = true`, `blockName` from the floor cell (today's callers log it). `simplifyPath` keeps working on it (it only drops duplicates and non-standing cells now).
- `toSegments(List<PathNode>, sprint)`: today's `convertPathToSegments` unchanged (6-block Manhattan split, jump and axis breaks).
- `toUnits(PathPlan, sprint)`: the executor's list. Consecutive non-action moves (TRAVERSE, DIAGONAL, ASCEND without toBreak, DESCEND) become one `WalkRun(Queue<Segment>)` built by the two calls above. Every action move becomes an `ActionUnit(PlannedMove)`. The list alternates runs and actions in plan order.

```java
public sealed interface ExecUnit permits WalkRun, ActionUnit {}
public record WalkRun(Queue<Segment> segments, int firstMoveIndex, int lastMoveIndex) implements ExecUnit {}
public record ActionUnit(PlannedMove move, int moveIndex) implements ExecUnit {}
```

So `walkSegment` keeps working untouched for walking, and only action units get new code.

## 7. Executor changes (MovementService, slice C)

`walkTo` after planning:

```
units = PlanAdapter.toUnits(plan, sprint)
for unit in units:
    verify = CostVerifier.check(plan, unit.firstMoveIndex, liveView, 5, 10)   // next 5 moves, max +10 ticks
    if verify.cancel: record reason, break to replan (leg loop)
    if bot already stands on a later move's `to` within this unit: skip forward (Baritone PathExecutor)
    WalkRun  -> for each segment: walkSegment(...) as today (its stuck ladder is the fallback)
    ActionUnit -> executeAction(move) with a 100-tick movementTimeout per move
```

`CostVerifier.check` is pure: it re-evaluates `MoveCosts.evaluate` for moves `i..i+4` against a fresh `WorldTerrainView` (new memos, same thread) and cancels when any move is now forbidden or its ticks grew by more than `maxCostIncrease = 10`. It runs before each unit, not every tick: a `WalkRun` is a few seconds long and `walkSegment` already stops on stagnation.

Per-kind execution (`switch` over `MoveKind` with no default branch, so a new kind without an executor fails to compile):

- `TRAVERSE` with `toBreak`: for each cell, directed mine through `tryMinePlannedStepTrim(bot, world, cell, label)` (reach check, tool select, server-thread break, wait for air, 6 s cap), then `nudgeTowardExactBlock(bot, to, false, label)`. A cell that is still solid afterwards records `FailedRoutePolicy(origin, cell)` and cancels the leg.
- `ASCEND` with `toBreak`: same mining for the listed cells (dest body, dest head, origin head+1), then `nudgeTowardUntilClose(bot, to, 2.25, 1400 ms, 0.18, label)` and the grounded-Y check `>= to.y`, exactly `tryTerrainMoveToward`'s BREAK branch (MS:3234-3257).
- `ASCEND_PLACE`: `ScaffoldService.pillarUp(bot, 1, false)` (DIRT first in `SCAFFOLD_BLOCKS`, `countTerrainStairBlocks` guarantees dirt), then the same step-up nudge onto `to`, exactly the PLACE branch (MS:3258-3269). A failed place records `FailedRoutePolicy(origin, origin.up())`.
- `FALL`: face `to`, walk forward without jumping (`inputStepToward` with `forceJump=false`, impulse 0.12) until `!isOnGround`, then wait for ground (`ScaffoldService.waitForOnGround` style, 60 ticks), then confirm the grounded cell is `to` or adjacent to it.
- `SWIM`: new, small. Each tick on the server thread: `LookController.faceBlock(bot, to)`, `applyMovementInput(bot, center(to), 0.12)`, `jump()` when the bot's eye is below the surface (`getAir() < getMaxAir()` or `isSubmergedInWater`) and on every exit move (dy +1). Today's `encourageSurfaceDrift` already lets vanilla handle water physics and jumps when air drops, so SWIM is that loop with a direction. Budget 100 ticks per cell, cancel on a wall (no block change in 30 ticks). Field acceptance is section 8's gully in the sandbox, which is why `allowSwim` stays a context flag.
- `DOWNWARD`: `throw new UnsupportedOperationException` in 1.1.242 (no companion skill opts in yet, the flag defaults false, the executor branch exists so the switch is total).

What stays: `walkSegment`'s stuck ladder and `executeStuckRecoveryHeuristics` (ruling: stagnation remains the fallback for what the plan did not foresee, such as an entity in the way). Both keep writing to `NavHazardCache.recordRejection` and `FailedRoutePolicy` so the next leg routes around the cell.

`recordAttempt` logs the outcome line with the plan ID (section 10).

## 8. Threading

- The search runs on the thread that calls `walkTo` (a skill worker). It reads the world through `WorldTerrainView` the way PF and BS already do off-thread (chunk reads, `LockableBlockService`, `ProtectedZoneService`, never a mutation). `/bot path` and a few `canReach` probes run on the server thread with the on-thread budgets of section 5.2 and never a failure timeout.
- `SearchContext` is a snapshot: tool speeds, scaffold count, sprint, the gates for break and place are read once before the search, never live during it (Stack Overflow 79788739 is the hazard).
- One `ThreadLocal<SearchBuffers>` per worker. `WorldTerrainView` and its chunk cache are per search and never escape the thread. The PathCompare shadow thread builds its own view.
- Mutations stay on the server thread through the existing helpers (`tryMinePlannedStepTrim`, `ScaffoldService.pillarUp`, `BotActions`). The executor never calls `world.setBlockState` itself.
- `CostVerifier` reads a fresh view on the worker before each unit. A `WalkRun` in flight is not re-verified every tick.
- `PathPlanIds` is an `AtomicLong`. `PathCompareService` and `NavHazardCache` are unchanged in their threading.

## 9. Field comparison and PathCompare

The old finders read the world directly, so they cannot be scored on `VoxelGrid`. Comparison is split:

- JUnit: unified vs `DijkstraReference` (optimality) and `MoveSimulator` (executability) on the scenario grids (section 10).
- Field: unified vs the old finders through PathCompare. `PathFinderChoice` gains `UNIFIED`. The active finder is a `PathFinderChoice` (default `UNIFIED`), per-bot overrides keep working during the field window. Shadow direction during the window: `other(UNIFIED) == BARITONE` (the better old finder, the one behind the 1.1.241 numbers), and `other(BARITONE) == other(BIDIR) == UNIFIED`, so the safe first field step is possible: bots walk on the proven finder while every plan also logs what unified would have done ("unified as shadow"). The old bidir-vs-baritone pair is already measured (26 pairs) and is not logged again.

  Field-window config mapping (the persisted key stays a boolean until slice D): `baritonePathfinderEnabled=true` (the 1.1.240 migrated default) maps to `UNIFIED`, `false` maps to `BIDIR`. `BARITONE` as a primary is reachable only in memory, through `/bot config pathfinder baritone <bot>` (per-bot override) or `/bot config pathfinder baritone` (global, not saved). The two copy sites `Frens.java:904` and `configNetworkManager.java:218` become one-line calls to `PathFinderChoice.fromLegacyFlag(boolean)`. The GUI Admin toggle keeps its "Baritone Pathfinder" label and in effect switches unified on and off during the window, which is wrong wording and is accepted until D removes it.

  The line gains a third finder field `unified=<ms>ms/<len>/<status>` and `plan=<id>`, both additive to the `(\w+)=(\S+)` parse. Status for the unified finder comes from `MoveSimulator` against the live `WorldTerrainView` overlay, not from `PathFinder.isPathExecutable`, which refuses every planned break or place (dy 2, wall cells) and would report `noexec` for exactly the plans that matter. The analyzer gets a "unified-only reachable" bucket (unified found a path, the old finder none) instead of counting that as disagreement, and joins outcomes on `plan=` when both lines carry it, falling back to the 15 s window otherwise.

## 10. Benchmark scenarios and pass thresholds

`src/test/resources/pathfinding/<name>.grid` (ASCII layers, bottom y first, a header line with start, goal, tool table, context flags). `UnifiedFinderBenchmarkTest` loads each, runs the finder 5 times (first run discarded for JIT), checks the thresholds, and prints one table row. `BenchmarkMain` (test source set, `main`) prints the table for a quick look during tuning.

| Scenario | Builds | Expect | Thresholds |
|---|---|---|---|
| `open_field` | 60 x 60 flat grass, goal 50 blocks away | FULL, TRAVERSE and DIAGONAL only | p50 under 5 ms, p95 under 20 ms, ratio to Dijkstra under 1.01, nodes under 3000 |
| `terraces` | five 1-block dirt steps up, then five down | FULL, ASCEND and DESCEND, no break | ratio under 1.01, pure-stair heuristic exactness: `h(start) == optimal` on the up half (proves section 4) |
| `pure_stair` | 8-step diagonal-free staircase, start to top | FULL | `h(start)` equals the Dijkstra cost to within 0.1% (the admissibility bench the research asked for) |
| `cliff_side_slope` | 4-high stone cliff in front, 1-step dirt slope 6 blocks to the side | FULL via the slope, no break (stone by hand is INF) | ratio under 1.01, every move passes the simulator |
| `gully_ledge` | rebuilt from `blocks` dumps around 22,63,126 (section 10.1): sand ledge, 2-deep pond east, 2-high protected sand west, terrace beyond the pond | `allowSwim` true: FULL with SWIM entry, lateral, exit. `allowSwim` false, hand tool, scaffold 0: NO_PATH `BLOCKED_PROTECTED`. `allowSwim` false, scaffold 2, an unprotected 2-rise added on one side: FULL with one ASCEND_PLACE | each variant as stated, simulator passes, the swim run is at most `maxSwimRun` |
| `sand_wall_break` | 1-wide unprotected sand wall 2 high across the route, hand tool | FULL with one ASCEND carrying `toBreak=[F.y+1]`, cost below the 12-block detour that also exists | the chosen plan's ticks are below the detour's ticks by construction (the test computes both) |
| `cave_pit` | surface start, a 3-deep pit on the straight line, floor 5 below start | route around the pit, never into it (depth floor) | no node below `min(start, goal) - 2`, FULL |
| `trees_2x2` | four 2x2 trunks with leaves, goal behind them | FULL walking around, or through leaves with `toBreak` when the detour is over 60 ticks longer | simulator passes, leaves are the only broken cells |
| `leaf_canopy` | a low canopy forcing head-height leaves on the direct line | FULL with TRAVERSE `toBreak` of leaves (6 ticks each) | ratio under 1.01 |
| `campfire` | a lit campfire on the direct line | route keeps the 2-block berth (PF semantics) | no move lands inside the berth |
| `lava` | a lava pool with a 1-block land bridge and a wade crossing | takes the bridge or the wade, never a FALL toward lava, never a cell adjacent-below lava | no landing with `LAVA` floor or body |
| `protected_zone` | a protected dirt wall 2 high, unprotected detour 20 blocks around | detour taken, no break or place inside the zone, walking through protected flat ground allowed | zero `toBreak`/`toPlace` cells with `PROTECTED` |
| `partial_budget` | open field with `primaryMs = 5`, `failureMs = 5`, goal 200 away | PARTIAL past 5 blocks, no tail cut (under 30 moves) or 10% cut (over) | status PARTIAL, end at least 5 blocks from start |
| `boxed_in` | start enclosed by stone, hand tool | NO_PATH `BOXED_IN` in under 1 ms | reason and time |
| `determinism` | every scenario twice | identical move lists | equality |
| `heap_fuzz` | `BinaryHeapOpenSetTest` | matches `PriorityQueue` on 10k random insert/decrease/pop | equality |

Release gate: every row green in `./gradlew test`, plus the sandbox gully escape in the field (section 7, SWIM).

### 10.1 Rebuilding the gully from the real sandbox

The hardened MCP fork's `blocks` command (`docs/testing/JAVA_MINECRAFT_AUTOMATION.md`) dumps a server-side grid around a bot or a position, one level per y, with `.` air, `~` water, `!` lava, `@` centre and block ids elsewhere, r and dy up to 4. That is a 9 x 9 x 9 box per call, so the 34 x 26 route to the tree at -12,68,152 is many dumps. The benchmark rebuilds only the ledge, the pond, the protected sand and the nearest terrace from dumps around 22,63,126 (four to six calls, saved verbatim under `src/test/resources/pathfinding/gully/`) and tests the escape from the ledge. The long route is covered by `open_field` and `terraces`. A small converter in the test source set turns dump text into `.grid` glyphs (block ids map to `#`, `d`, `s`, `l`, `~`), with protection marked by hand from the Spawn-base zone bounds.

## 11. Implementation plan: slices with disjoint file sets

Three parallel implementer agents (A, B, C) start together against the API frozen in section 2. One sequencing point: C's final `walkTo` wiring waits for B's `PlanAdapter` and `PathCompareFormat.outcomeLine` overload. D runs after the field win. Every agent stages only its own paths (`git add <paths>`, never `-A`), per CLAUDE.md parallel-agent rules.

### Slice A: pure core (size L, about 1500 lines main + 1200 test)

Files (all new):
- `src/main/java/net/wcfcarolina13/PathFinding/unified/CellProps.java`, `TerrainView.java`, `MutableTerrainView.java`, `SearchContext.java`, `SearchBox.java`, `ActionCosts.java`, `MoveKind.java`, `PlannedMove.java`, `PlanStatus.java`, `NoPathReason.java`, `PathPlan.java`, `MoveCosts.java`, `SearchNode.java`, `BinaryHeapOpenSet.java`, `SearchBuffers.java`, `UnifiedPathFinder.java`, `MoveSimulator.java`, `CostVerifier.java`, `PathPlanIds.java`
- `src/main/java/net/wcfcarolina13/GameAI/services/NavLegPolicy.java`
- `src/test/java/net/wcfcarolina13/PathFinding/unified/VoxelGrid.java`, `GridLoader.java`, `BlocksDumpConverter.java`, `DijkstraReference.java`, `BenchmarkMain.java`, `ActionCostsTest.java`, `MoveCostsInvariantTest.java`, `BinaryHeapOpenSetTest.java`, `UnifiedPathFinderTest.java`, `MoveSimulatorTest.java`, `CostVerifierTest.java`, `NavLegPolicyTest.java`, `UnifiedFinderBenchmarkTest.java`
- `src/test/resources/pathfinding/*.grid`, `src/test/resources/pathfinding/gully/*.txt`

Acceptance: section 10 table green, `MoveCostsInvariantTest` passes for every kind, no `net.minecraft` import outside `WorldTerrainView` (a test greps the package), `./gradlew test` adds the new tests to the count.

### Slice B: world adapter, dispatch, PathCompare (size M, about 700 lines)

Files:
- new `src/main/java/net/wcfcarolina13/PathFinding/unified/WorldTerrainView.java`, `PlanAdapter.java`, `ExecUnit.java` (with `WalkRun`, `ActionUnit`), `PlanRequest.java`
- edit `PathFinding/PathFinder.java` (new `plan(...)`, `calculatePath` dispatches by `PathFinderChoice`, `runFinder` gains the UNIFIED branch, `canReach` uses the unified finder with the caller's budget, `[PathFinder]` line gains `plan=` and `reason=`, `USE_BARITONE_STYLE` becomes `ACTIVE` of type `PathFinderChoice`), `PathFinderChoice.java` (UNIFIED, `other()` per section 9, `parse("unified")`, `fromLegacyFlag(boolean)`), `PathFinderOverrides.java` (resolve takes the active choice, not a boolean), `PathCompareService.java` (third finder field, simulator-based status for unified plans, plan ID), `PathCompareFormat.java` (`line(...)` overload with `unified=` and `plan=`, `outcomeLine(..., planId)` overload)
- edit `Frens.java:904` and `network/configNetworkManager.java:218` (one line each: `PathFinder.ACTIVE = PathFinderChoice.fromLegacyFlag(config.isBaritonePathfinderEnabled())`)
- edit `GameAI/services/BotAutoReturnSunsetService.java:860` (`PathFinder.plan` with a 200 ms request instead of `BaritoneStylePathFinder.calculatePath`), `Commands/modCommandRegistry.java` (`/bot config pathfinder unified|baritone|classic|default`, `/bot path` through the adapter)
- edit `tools/logs/frens_log_report.py` (third finder field, `plan=` join, unified-only bucket)
- tests: `PathCompareFormatTest.java`, `PathFinderOverridesTest.java` extended, new `PlanAdapterTest.java` (pure: moves to units), `tools/logs/test_frens_log_report.py` cases for the new fields

Acceptance: `./gradlew build` green with the unified finder as default, `/bot config pathcompare on` logs lines with three finder fields and a plan ID, the analyzer joins by plan ID, `canReach` and the sunset probe compile against `plan(...)`.

### Slice C: executor (size M, about 500 lines)

Files:
- edit `GameAI/services/MovementService.java` only: `walkTo` runs the unit loop and the leg loop (`NavLegPolicy`), `executeAction(PlannedMove)` with the per-kind switch, `swimToward(...)`, `fallToward(...)`, `CostVerifier` before each unit, `recordAttempt` logs the outcome line with the plan ID (calls the `outcomeLine` overload B adds), `WalkResult.detail` carries `NoPathReason`
- new `GameAI/services/PlannedMoveExecPolicy.java` (pure: per-kind timeouts in ticks, skip-forward rule, when a failed action records a failed route and when it cancels the leg) and `PlannedMoveExecPolicyTest.java`

Acceptance: a plan with an ASCEND `toBreak` mines exactly the listed cells and steps up (sandbox: the unprotected sand wall case), ASCEND_PLACE places one dirt and steps up, SWIM crosses the gully pond, every other kind still goes through `walkSegment`, a cost rise over 10 ticks within the next 5 moves cancels the leg (log line `walkTo leg cancelled: cost verification ...`). Unit tests cover the policy. Field: `[PathCompareOutcome]` reached rate for `primary=unified` at or above the 1.1.241 baseline 61.5% in a multi-bot daylight session, with a log-analyst agent before and after.

### Slice D: removal, after the field win (size M, about -1800 lines)

Files:
- delete `PathFinding/BaritoneStylePathFinder.java`, `PathFinderChoice.java`, `PathFinderOverrides.java`, `BaritoneStylePathFinderTest.java`, `PathFinderOverridesTest.java`
- edit `PathFinder.java` (delete `calculatePathBidirectional`, `Node`, `mergePaths`, `reconstructPath`, `getNeighbors`, `addIfSafe`, `CampfireMemo`, `tagBlocks`, `isPathExecutable`, `lastSearchBoxedIn`, `USE_BARITONE_STYLE`, the commented-out methods, `isWalkableBlock`, `isJumpableBlock`), `PathCompareService.java` and `PathCompareFormat.java` (keep PathCompare as a regression check: the shadow becomes a second unified run with a different budget, or the service is reduced to outcome logging, decide at D), `PathMovePolicy.java` (its edge rule moves into `MoveCosts`, delete if no caller remains), `WadeableWater.java` (stays, used by `WorldTerrainView`)
- edit `FilingSystem/ManualConfig.java` (replace `baritonePathfinderEnabled` and `baritoneDefaultMigrated` with nothing: load tolerates the old keys through the existing fallback path and drops them on the next save, logged once as `1.1.24x config migration: pathfinder keys removed`), `FilingSystem/SharedConfig.java` (allowlist entry and snapshot copy), `network/configNetworkManager.java:218`, `Frens.java:904`, `GraphicalUserInterface/BotPlayerInventoryScreen.java` (TopicEntry 742, cases 5161, 5616, 5929, 5982, methods 6575-6590), `Commands/modCommandRegistry.java` (the `pathfinder` subcommand 729-780), `CLAUDE.md` PathFinding section
- edit `MovementService.java` for the redundancies in section 12 that the audit confirms
- tests: `ManualConfig` reflection test that an old file with `baritonePathfinderEnabled=false` loads clean

Acceptance: `./gradlew clean build` green, `grep -rn "Baritone\|baritonePathfinder\|USE_BARITONE_STYLE" src` returns only the research doc references in comments if any, the GUI Admin tab no longer lists the toggle, a 1.1.241 settings file loads without a warning.

## 12. What becomes redundant once BREAK, PLACE and SWIM are in the search

Feeds the dead-code audit. "Goes" means slice D deletes it after the field win. "Stays" is by ruling or because it covers what a plan cannot foresee.

| Item | Where | Verdict |
|---|---|---|
| Boxed-in branch in `walkTo` (`boxedInTried`, `tryTerrainMoveToward` from a boxed-in start, MS:962-975) | MS | Goes: the finder plans the terrain move itself, `NoPathReason.BOXED_IN` replaces the flag |
| `preclearPlannedStepUps` (MS:3957) and the second `calculatePath` after it (MS:939-943) | MS | Goes: ASCEND carries its own `toBreak` |
| `tryTrimStepUpClearance` for planned step-ups (MS:3982), `tryMinePlannedStepTrim` as a standalone assist | MS | The trim goes, `tryMinePlannedStepTrim` stays as the executor's directed mine primitive |
| `TerrainMovePolicy` PLACE branch and `placeOk` checks (MS:3129-3139) | MS, policy | Goes: ASCEND_PLACE. The policy's WALK_AROUND and BREAK branches stay as the stuck fallback (ruling) until the audit shows the fallback never picks them, then the policy goes too |
| `tryTerrainMoveToward` as a whole | MS | Stays for 1.1.242 (fallback), candidate for deletion in D if the field log shows zero `terrain move:` lines with a unified primary |
| `PathFinder.isPathExecutable`, `PathCompareFormat.status` on it | PF, PathCompare | Goes: `MoveSimulator` gives the status |
| `PathFinder.lastSearchBoxedIn`, `noteSearchBoxedIn`, the `ThreadLocal` | PF, BS | Goes |
| Both `tagBlocks`, `simplifyPath`'s lift/drop history | PF, BS | `tagBlocks` goes, `simplifyPath` stays as the smoother |
| `CampfireMemo` (PF), `ChunkCache` (BS) | PF, BS | Absorbed by `WorldTerrainView`, the classes go |
| `shouldUseRawPathSegmentsForLocalEscape`, `convertRawPathToSegments` (MS:1047-1100) | MS | Goes: `PlanAdapter.toUnits` decides the split from move kinds, not from a heuristic over the first 4 nodes |
| `walkDirect` fallback after no path (MS:980) and the 144-block-distance direct shortcut (MS:915-923) | MS | The no-path fallback goes (a NO_PATH reason is final for that leg). The short direct walk stays (it never planned, cheap, and the 61.5% number does not cover it) |
| Local escape (`tryImmediateLocalEscape`, `findBestLocalEscapeCandidate`, `searchForLocalEscapeRoute`, `executeLocalEscapeCandidate`, directives and commitments) | MS | Stays as the mid-segment stuck fallback (ruling). The pre-walk call (`-prewalk`, MS:901) and the per-segment pre-call (`-segment-pre`, MS:993) go: the plan already starts from the grounded cell and every landing is a standing cell |
| `walkSegment` stuck ladder, `executeStuckRecoveryHeuristics`, `trySidestepAround`, `tryLocalUnstick`, door helpers | MS | Stays (ruling). Doors are passable cells in the search, so `tryOpenDoorToward` keeps its job at execution |
| `FailedRoutePolicy` | services | Stays: the leg loop's first-hop memory and the executor's failed-action memory |
| `NavHazardCache`, `PassageAnchorService` | navigation | Stays, now in ticks (section 4) |
| `PathMovePolicy` | PathFinding | Its rule is `MoveCosts` TRAVERSE/ASCEND/DESCEND preconditions. Goes in D once `isPathExecutable` is gone |
| `PathFinderChoice`, `PathFinderOverrides`, GUI toggle, `/bot config pathfinder`, `baritonePathfinderEnabled`, `baritoneDefaultMigrated`, `SharedConfig` entry, `configNetworkManager` copy | several | Goes in D (section 11) |
| `BotAutoReturnSunsetService` direct BS call (line 860) | sunset | Rewired in B, nothing left for D |

## 13. Open items not decided here

- Plan-ahead with splicing (Baritone `SplicedPath`) after the sequential leg loop proves itself.
- Whether PathCompare survives D as a two-budget self-compare or shrinks to outcome logging.
- DOWNWARD for mining skills (the move exists, no executor, no caller).
- The scarcity term's constant: `PLACE_COST * 20 / scaffoldLeft` gives 60 ticks with one dirt and 15 with four. The benchmark's `gully_ledge` scaffold-2 variant pins the first value that makes the stair beat a 40-block detour and nothing shorter.
