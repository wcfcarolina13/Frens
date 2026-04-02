# Simplify fellTree: Replace Column Entry with Mine-Pillar-Bridge

## Context

The current `fellTree` uses a 680-line column-entry system (`enterTrunkColumn`, `pickNextScaffoldColumn`, `moveToColumnStand`, `resolveTrunkEntryStand`, etc.) to get the bot INSIDE a solid trunk before pillaring up. This consistently fails for cherry trees, wastes 56+ scaffold blocks per tree with 0 logs mined, and adds complexity that breaks on any non-standard tree shape.

The simpler approach: mine trunk logs from the outside (they're within reach from adjacent ground), then the column is clear — walk in, pillar up, bridge at each level. This works for ALL tree types including floaters (which are just 1-block "trees").

## Step 0: Checkpoint

Commit ALL uncommitted files (40+ from Codex and this session) as a single checkpoint commit before the refactor. This gives a clean revert point.

## The New `fellTree` Flow

```
1. approachBase(tree.base)              — navigate near trunk [KEEP AS-IS]
2. Mine all trunk logs within reach      — from adjacent ground, no entry needed
3. Walk into the cleared column          — trivial, it's now air
4. Pillar up (ascent loop)              — mine branches + bridge at each level [REUSE]
5. Descend (scaffold teardown)          — mine branches + bridge on way down [REUSE]
6. Check leftovers                      — if any remain, they're floaters for cleanup
```

**Floater case (no trunk):** Steps 2-3 are skipped (no trunk to mine). The bot is already near the floater from `approachBase`. It pillars up below the target and bridges to reach it. The `collectRemainingEnvelopeLogs` envelope for a 1-block floater returns just that block — the loop exits after mining it.

## What Changes

### Replace in `fellTree` (~lines 1294-1456)

Remove the entire column-selection loop (`pickNextScaffoldColumn`, `mineFromScaffoldColumn`, column visit tracking, `ColumnVisitRecord`). Replace with:

```java
// Phase 1: Mine trunk logs from outside (within reach from ground)
int trunkMined = 0;
for (int pass = 0; pass < 10; pass++) {
    List<BlockPos> trunkLogs = collectRemainingEnvelopeLogs(bot, world, target).stream()
        .filter(pos -> pos.getX() == trunkBase.getX() && pos.getZ() == trunkBase.getZ())
        .filter(pos -> isWithinReach(bot, pos))
        .sorted(Comparator.comparingInt(BlockPos::getY))
        .toList();
    if (trunkLogs.isEmpty()) break;
    BlockPos log = trunkLogs.get(0);
    clearPathToTarget(bot, log);
    ensureAxeEquipped(bot);
    if (mineBlock(bot, log, true)) { trunkMined++; totalMined++; }
    else break;
}

// Phase 2: Position at trunk base (walk into cleared column or stay adjacent)
BlockPos pillarBase = new BlockPos(trunkBase.getX(), bot.getBlockY(), trunkBase.getZ());
if (!bot.getBlockPos().equals(pillarBase)) {
    moveToStand(source, bot, world, pillarBase, trunkBase, reachSession);
}

// Phase 3: Pillar up + mine + bridge (reuse existing ascent logic)
int maxScanY = target.top().getY() + 2;
int maxSteps = Math.min(target.height() + 4, MAX_COLUMN_PILLAR_STEPS);
// [existing ascent loop: pillar step → mineReachableBranches → bridge sweep]

// Phase 4: Descend + mine + bridge (reuse existing descent logic)
// [existing descent loop: scaffold teardown → mine → elevated sweep → bridge]

// Phase 5: Check leftovers
List<BlockPos> leftover = collectRemainingEnvelopeLogs(bot, world, target);
```

### Keep Unchanged

- `approachBase()` — pure navigation
- `mineReachableBranches()` — branch mining at any height
- `BridgeScaffoldService.bridgeAndRetract()` — lateral reach
- `ScaffoldService.pillarUpWithPositions()` — vertical scaffold
- `collectRemainingEnvelopeLogs()` — envelope scanning (works for floaters)
- Ascent bridge sweep logic (from current lines ~1537-1570)
- Descent loop with elevated sweep + bridge sweep (from current lines ~1573-1639)
- `clearPathToTarget()`, `ensureAxeEquipped()`, `mineBlock()` — mining primitives

### Remove (Dead After Refactor)

- `enterTrunkColumn()` (~150 lines)
- `pickNextScaffoldColumn()` + `scoreScaffoldColumn()` (~170 lines)
- `resolveTrunkEntryStand()` + `scoreTrunkEntryStandPriority()` (~50 lines)
- `moveToColumnStand()` + `isControlledTrunkEntryStand()` (~80 lines)
- `carveEntryHeadway()` + `clearEntryShaftCells()` (~80 lines)
- `ColumnVisitRecord`, `ColumnVisitStatus`, `TrunkEntryResult`, `ColumnMineResult`, `ColumnEntryMoveResult`, `TrunkEntryStandChoice` records
- `isCarveableWoodcutStand()`, `isEntryCellPassableOrCarveable()`, `isSoftTerrainEntryBlocker()`
- `forceStepIntoExactStand()`, `findEntryStagingStand()`
- Various column-specific helpers

**Estimated removal: ~600-700 lines** of column-entry code, replaced by ~50 lines of mine-from-outside + pillar-up.

## Files Modified

- `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` — replace `fellTree` internals, remove dead column-entry methods

## Verification

1. `./gradlew build -x test`
2. In-game test (standard oaks): `/bot skill woodcut 4` in a regular forest
   - Observe: bot mines trunk from ground, pillars up, bridges outward, descends
3. In-game test (cherry): same in cherry blossom forest
   - Observe: short trunks mined easily, branches reached via bridge
4. In-game test (floaters): leave some floating logs, run woodcut
   - Observe: bot approaches, pillars up, mines the floater
5. If anything regresses: `git revert` the refactor commit, checkpoint still works
