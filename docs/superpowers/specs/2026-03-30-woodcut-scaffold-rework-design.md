# Woodcut Scaffold Rework — Level-by-Level Mining

**Date:** 2026-03-30
**Status:** Approved

## Problem

The woodcut scaffold system has several issues:
1. Bot builds scaffold to full height, mines branches, but skips logs it can't reach from that single position
2. Bot sometimes uses wrong tools on scaffold blocks (shovel on cobblestone)
3. Bot occasionally jumps/walks off scaffold instead of safely descending
4. Orphaned canopy branches from neighboring de-trunked trees are left floating
5. Bot re-scaffolds at positions where it already worked, wasting time and materials

## Design

### Core concept: Column-based level-by-level mining

After cutting the trunk, the branch-mining phase switches from "pillar to max height then mine owned logs" to "visit XZ columns, scaffold up one level at a time, mine ALL reachable logs at each level."

### New method: `scaffoldAndMineReachableLogs`

Replaces the current `while(true) { selectNextOwnedLogTarget; mine }` loop in `fellTree()` (lines 948-973).

**Algorithm:**
1. Scan for remaining log blocks within the tree envelope (expanded by reach distance)
2. Pick the nearest unvisited log, determine which XZ column to scaffold from (directly below or adjacent)
3. Move to column position
4. Scaffold up one block (single `pillarUp(bot, 1, ...)`)
5. Stay sneaked. Scan sphere (reach distance) for ANY log block. Mine all found logs.
6. Repeat steps 4-5 until no more logs are reachable above current height OR max reasonable height reached
7. Tear down scaffold top-to-bottom (stay sneaked, safe descent)
8. Mark this XZ column as visited
9. Re-scan for remaining logs. If any exist that weren't reachable from visited columns, pick new column position, go to step 3.
10. Done when no reachable logs remain in envelope.

### Scanning for logs at each level

New helper: `scanReachableLogs(bot, world, envelope)`

- Scans all blocks within reach distance (4.5 blocks) of bot's eye position
- Returns all blocks that are logs (`BlockTags.LOGS`)
- NO ownership check — mines any log within reach
- Filters out blocks that fail line-of-sight check (can't be reached through solid terrain)
- Does NOT filter by the tree's `isOwnedTreeLog` — orphaned canopy fragments are fair game

### Scaffold tool fix

Current `selectScaffoldToolOrHands()` always prefers shovel. Change to:

```
if block is in BlockTags.PICKAXE_MINEABLE → select pickaxe
else if block is in BlockTags.SHOVEL_MINEABLE → select shovel
else → hands/harmless item
```

This ensures cobblestone/deepslate get pickaxe, dirt/gravel/sand get shovel.

### Safe descent enforcement

- Bot sets `sneaking = true` when scaffold has any placements
- Bot NEVER calls movement methods while on scaffold (no `walkTowardBlock`, no `MovementService.execute`)
- Scaffold teardown always uses `cleanupReachSession` which iterates top-down
- After full teardown, sneaking is restored to previous state
- If a log is spotted but unreachable while sneaking at current position: record it, finish current column, tear down, THEN relocate and scaffold at new position

### Column visit tracking

`Set<Long> visitedScaffoldColumns` tracks XZ positions (encoded as `BlockPos.asLong` with Y=0) where scaffold was already built and torn down. The bot never re-scaffolds at a visited column.

### Changes to `fellTree()`

The trunk-first phase (lines 934-946) stays unchanged. After trunk mining:

**Old flow:**
```
while (true) {
    next = selectNextOwnedLogTarget(world, bot, target, failedOwnedLogs)
    if (next == null) break
    mineWithRetries(bot, source, next, ...)  // builds scaffold internally via prepareReach
}
```

**New flow:**
```
scaffoldAndMineReachableLogs(bot, source, target, reachSession, sharedState, visitedColumns)
```

The new method handles all scaffold lifecycle internally.

### Changes to `selectScaffoldToolOrHands()`

```java
private void selectScaffoldToolOrHands(ServerPlayerEntity bot, BlockState blockState) {
    // Check what tool is effective for this specific block
    if (blockState.isIn(BlockTags.PICKAXE_MINEABLE)) {
        // Select best pickaxe from inventory
    } else if (blockState.isIn(BlockTags.SHOVEL_MINEABLE)) {
        // Select best shovel from inventory
    } else {
        // Hands or harmless item
    }
}
```

### What stays the same

- `pillarUp()` jump-place mechanics (called with steps=1 repeatedly)
- `WoodcutReachSession` block tracking
- `cleanupReachSession()` top-down teardown
- Tree detection, trunk-first mining, sapling replanting
- `PILLAR_BLOCKS` material list
- Per-tree drop sweeps and cleanup passes

## Files to modify

| File | Method/Section | Change |
|------|---------------|--------|
| `WoodcutSkill.java` | `fellTree()` branch phase | Replace owned-log loop with `scaffoldAndMineReachableLogs` |
| `WoodcutSkill.java` | New: `scaffoldAndMineReachableLogs()` | Column-based level-by-level mining |
| `WoodcutSkill.java` | New: `scanReachableLogs()` | Find all log blocks within reach sphere |
| `WoodcutSkill.java` | New: `pickScaffoldColumn()` | Choose best XZ position for next scaffold |
| `WoodcutSkill.java` | `selectScaffoldToolOrHands()` | Block-material-aware tool selection |
| `WoodcutSkill.java` | `cleanupReachSession()` | Enforce sneaking throughout teardown (already mostly does this) |

## Verification

1. `./gradlew build` — compilation
2. In-game: spawn bot in dense forest with overlapping canopies
3. Run `/bot skill woodcut <bot>` and observe:
   - Bot cuts trunk, then scaffolds up one block at a time
   - At each level, mines ALL reachable logs (not just owned)
   - Stays sneaked on scaffold, never jumps off
   - Uses pickaxe for cobblestone scaffold, shovel for dirt
   - Tears down scaffold safely top-to-bottom
   - Moves to new position for unreachable logs, scaffolds there
   - Never re-scaffolds at a position it already visited
   - Orphaned canopy branches from neighboring trees are cleaned up
