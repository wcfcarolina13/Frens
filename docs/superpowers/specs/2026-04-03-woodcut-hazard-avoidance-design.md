# Woodcut Hazard Avoidance & Scaffold Recovery

Prevents bots from placing scaffold over ravines and deep water, works trees from the safe side when partially exposed, and recovers gracefully when scaffold cleanup gets stuck over hazardous terrain.

## Problem

The bot places scaffold blocks during woodcut pillar-up without checking terrain below. When scaffolding extends over a ravine or water, the cleanup phase fails: all nearby ground positions are rejected as unsafe by `isSafeWoodcutWorkStand()`, `moveNearScaffoldForCleanup()` returns false, and orphaned scaffold blocks are left in the world. The bot can also fall into ravines/water during approach or descent.

## Decisions

- **Tree selection:** Trees surrounded on all sides by hazards are rejected. Trees with partial hazards are accepted but only worked from safe sides.
- **Water depth:** Shallow water (1-2 blocks) is walkable, not a hazard. Deep water (3+ blocks) is treated like a ravine.
- **Scaffold direction:** Never place scaffold toward a ravine or deep water. Only scaffold toward safe or shallow-water directions.
- **Cleanup recovery:** Try bridge-to-safety first, fall back to abandon-and-retreat if bridging fails.

## Architecture

### New: `WoodcutHazardScanner`

`GameAI/skills/support/WoodcutHazardScanner.java` (~120 lines)

Lightweight per-tree terrain assessment. Runs during tree selection and before scaffold placement.

#### TerrainRating enum

```java
public enum TerrainRating { SAFE, SHALLOW_WATER, DEEP_WATER, RAVINE }
```

#### TreeHazardProfile record

```java
public record TreeHazardProfile(
    Map<Direction, TerrainRating> ratings,  // 4 cardinal directions only (NORTH, SOUTH, EAST, WEST)
    boolean hasAnySafeApproach,
    boolean fullyEnclosed,
    List<Direction> safeSides               // cardinal directions rated SAFE or SHALLOW_WATER
) {}
```

The `ratings` map uses `net.minecraft.util.math.Direction` which only has axis-aligned values. We scan the 4 horizontal cardinals (`Direction.Type.HORIZONTAL`), NOT diagonals. This aligns with all consumers: `tryPlaceScaffold()` only considers cardinal offsets (line 3807), `BridgeScaffoldService.bridgeAndRetract()` only accepts cardinal `Direction`, and `moveNearScaffoldForCleanup()` has no directional awareness. Diagonal probing would produce data no consumer can act on.

#### scan method

```java
public static TreeHazardProfile scan(ServerWorld world, BlockPos treeBase)
```

Probes outward from `treeBase` in 4 cardinal directions, up to 6 blocks per direction (covers most bridge scenarios; `BridgeScaffoldService.MAX_BRIDGE_LENGTH` is 8 but 6 catches the vast majority of hazards). For each direction, at each step:

1. Check the ground level at that position (walk forward, find where the ground drops)
2. If ground drops 3+ blocks with no water below: `RAVINE`
3. If ground drops into water, count water depth:
   - 1-2 blocks with solid floor below within 4 blocks: `SHALLOW_WATER`
   - 3+ blocks or no solid floor within 4 blocks: `DEEP_WATER`
4. If ground is continuous (drop < 3): `SAFE`

The worst rating along each direction wins (if block 2 is safe but block 4 is a ravine, the direction is `RAVINE`).

`hasAnySafeApproach` = at least one cardinal direction is `SAFE` or `SHALLOW_WATER`.
`fullyEnclosed` = no cardinal direction is `SAFE` or `SHALLOW_WATER`.
`safeSides` = list of cardinal directions rated `SAFE` or `SHALLOW_WATER`.

### Modified: `WoodcutSkill`

#### Data flow: TreeHazardProfile storage

Store the `TreeHazardProfile` as a field on `WoodcutReachSession` (the mutable per-tree-harvest class at line 291). Set it after scanning in `scanDetectionSnapshot()`, and read it in `tryPlaceScaffold()` via the `reachSession` parameter which is already passed through the scaffold/pillar call chain.

#### Tree selection — hazard rejection

In `scanDetectionSnapshot()`, after existing soil/leaves/protection checks, run `WoodcutHazardScanner.scan()` on candidate trees. Reject trees where `fullyEnclosed == true` with a skip reason like `"hazardous terrain on all sides"`. Store the `TreeHazardProfile` on the `WoodcutReachSession` when the session is created for the accepted tree.

#### Scaffold placement — direction filtering

In `tryPlaceScaffold()`, when considering cardinal offset positions, read the `TreeHazardProfile` from the `reachSession` and skip directions where the hazard profile rates them `RAVINE` or `DEEP_WATER`. This prevents scaffold from being placed over hazardous terrain.

Before `pillarUp()` begins, select the scaffold approach direction from `safeSides`, preferring the side closest to the bot's current position.

#### Scaffold over water — blocked

Scaffold blocks should never be placed over any water (shallow or deep). In `tryPlaceScaffold()`, before calling `ensureSupportBlock()` or `BotActions.placeBlockAt()`, check the support column: `world.getFluidState(targetPos.down()).isIn(FluidTags.WATER)`. If the immediate support position contains water, skip that placement. This catches cases where the hazard profile says SHALLOW_WATER (walkable but not buildable).

#### Cleanup recovery — bridge-to-safety + abandon

When `moveNearScaffoldForCleanup()` fails (returns false, scaffold unreachable):

**Bridge attempt:** Scan for nearest safe ground within 6 blocks horizontal of the bot's position. "Safe ground" = `isSafeWoodcutWorkStand()` returns true. If found:
1. Build a 1-wide temporary bridge from the bot's current position toward the safe ground using scaffold blocks (dirt/cobble). Block placement uses `server.execute()` / `callOnServer()` to schedule mutations on the server thread, matching the threading pattern used throughout WoodcutSkill.
2. Walk across the bridge to safe ground
3. From safe ground, attempt to break unreachable scaffold blocks within 4.5-block reach
4. Remove the bridge blocks after scaffold is cleaned

**Abandon fallback:** If no safe ground within 6 blocks, or bridge-building fails (no materials, blocked), or bot can't reach the scaffold from the bridge endpoint:
1. Mark the remaining scaffold blocks as lost
2. Find an escape position using `findEscapeStandNear()`
3. Pathfind to the escape position
4. Log a warning with the count of abandoned scaffold blocks
5. Continue to next tree

#### `findEscapeStandNear()` — new method

Like `findDryStandableNear()` but with relaxed criteria for emergency escape:
- Allows positions where `isDangerousDropCell()` is true, IF at least **2** cardinal neighbors are standable (ensures the bot has a viable exit vector, not just one potentially blocked neighbor)
- **Returns the nearest standable neighbor** of the dangerous-drop position, not the dangerous-drop position itself — the bot pathfinds to safety through the precarious area
- Allows positions adjacent to shallow water (the bot might need to step through 1-2 block water to escape)
- Does NOT allow positions where feet are in water — use `BotWaterEscapeService` for that case
- Wider search radius: 8 blocks (vs 1-3 for normal cleanup)

#### Water depth classification

At each probed position during hazard scanning:
- Count water blocks downward from the ground drop point
- 1-2 water blocks with solid ground below within 4 blocks: `SHALLOW_WATER`
- 3+ water blocks or no solid floor within 4 blocks: `DEEP_WATER`

Shallow water behavior:
- **Tree selection:** Not a hazard — trees adjacent to ponds are fine
- **Scaffold placement:** Skip — don't place scaffold over any water (check `FluidTags.WATER` at support position)
- **Movement:** Bot can walk through shallow water to approach trees (existing pathfinding handles this)
- **Cleanup escape:** Shallow water adjacency is allowed in `findEscapeStandNear()` — the bot can pass through 1-2 block water to reach scaffold blocks

Deep water behavior:
- **Tree selection:** Hazard — counted as an unsafe direction like ravine
- **Scaffold placement:** Blocked (via direction filter)
- **If bot falls in:** `BotWaterEscapeService.findNearestShoreStand()` handles this reactively (existing)

### Modified: `BridgeScaffoldService`

Hazard filtering happens at the **WoodcutSkill call site**, not inside `BridgeScaffoldService` itself. The skill already chooses which direction to bridge before calling `bridgeAndRetract(Direction dir, ...)`. The call site checks the `TreeHazardProfile` and skips `bridgeAndRetract` calls toward directions rated `RAVINE` or `DEEP_WATER`. No signature change needed on `BridgeScaffoldService`.

## Files Changed

| File | Change | ~Lines |
|---|---|---|
| **NEW:** `GameAI/skills/support/WoodcutHazardScanner.java` | Terrain assessment, TreeHazardProfile | ~120 |
| `GameAI/skills/impl/WoodcutSkill.java` | Tree rejection, hazard profile on WoodcutReachSession, scaffold direction filter, water check, cleanup bridge+abandon recovery, findEscapeStandNear | ~180 |

## Not in Scope

- Lava-specific handling (different biomes, separate concern)
- Ravine bridging for tree approach (bot avoids hazard side, doesn't bridge across)
- Underwater scaffold placement or underwater tree harvesting
- Modifying the pathfinder for ravine awareness (too broad)
- Changing `DangerZoneDetector` (serves combat/flee, not woodcut)
- Modifying `CliffDetector` (directional only, not suitable for 360-degree tree assessment)
- Diagonal terrain probing (no consumer can act on diagonal data)
