# Y-Level-Aware Scaffold Descent

Replaces the broken Phase 4 scaffold descent in `fellTree` with a Y-level-grouped descent that moves the bot downward through its own scaffold column, cleaning up as it goes.

## Problem

Phase 4 of `fellTree` (lines ~1410-1485) iterates scaffold blocks top→bottom to mine them, but **never moves the bot**. The bot stays at canopy height (Y=70-77), out of reach of scaffold blocks below it (Y=64-68). Every tree ends with PATH_OR_REACH_FAILURE. Zero trees successfully felled across 3 woodcut sessions.

The scaffold list in `WoodcutReachSession.placedBlocks` is perfectly ordered (bottom→top) and reversed for descent via `placementsDescending()`. The data is there — the bot just never uses it to navigate downward.

## Design

### Y-Level Grouping

Group `reachSession.placedBlocks` by Y-level. At each level, blocks split into two categories:
- **Column blocks** — blocks directly in the trunk column (same X/Z as the pillar base). These are the "floor" the bot stands on. Mining one causes a gravity drop.
- **Bridge blocks** — blocks offset horizontally (placed by bridge sweeps or cardinal offsets in `tryPlaceScaffold`). These are reachable from the column position at the same Y.

### Descent Algorithm

Process Y-levels from highest to lowest:

1. **Mine bridge blocks first.** At the current Y-level, mine all scaffold blocks that are NOT directly under the bot's feet. These are horizontal extensions reachable within 4.5 blocks. Walk to each if needed (short lateral moves on the same Y-level platform).

2. **Elevated sweep.** After clearing bridge blocks, run the existing elevated trunk/branch sweep at this height. This preserves the current behavior of mining logs during descent.

3. **Mine the column block (drop trigger).** Mine the scaffold block directly under the bot's feet. Gravity pulls the bot down to the next level. Wait 200ms for the bot to settle.

4. **Verify position.** After the drop, confirm the bot is at the expected next Y-level. If the bot fell further than expected (gap in scaffold column), handle gracefully — the bot may have landed on solid ground, which is fine.

5. **Walk to next column position.** If the scaffold column shifts horizontally between Y-levels (rare — happens when `tryPlaceScaffold` used a cardinal offset), walk to the new column X/Z before continuing descent.

6. **Repeat** until no more scaffold levels remain, or the bot reaches solid ground.

### Key Rules

- **Never jump up.** If a scaffold block is above the bot's current Y, skip it. It should have been mined in a previous level's iteration. If missed, the cleanup phase handles it later.
- **Bridge first, column last.** At each Y-level, mine horizontal extensions first (reachable while standing), mine the support block last (causes drop).
- **Abort check per level.** Check `isAbortRequested(bot)` at each Y-level to respect `/bot stop` and sunset interruption. If aborted mid-descent, the cleanup phase handles remaining scaffold.
- **Gravity wait.** After mining the block under feet, `sleepQuiet(200ms)` to let the bot fall and settle.
- **Ground detection.** If after mining a column block the bot lands on non-scaffold solid ground (the descent is complete), stop iterating even if lower scaffold blocks remain in the list (they're unreachable from above anyway — cleanup handles them).

### New Method: `descendScaffoldColumn`

```java
private int descendScaffoldColumn(
    ServerCommandSource source,
    ServerPlayerEntity bot,
    ServerWorld world,
    TreeDetector.TreeTarget target,
    Map<String, Object> sharedState,
    WoodcutReachSession reachSession)
```

Takes the reach session's placed blocks, groups by Y-level, and processes top→bottom with bridge-first-then-drop logic. Returns the number of scaffold blocks successfully removed during descent.

Called from `fellTree` at the start of Phase 4, replacing the current flat iteration loop.

### Integration with Existing Code

**Phase 4 (lines ~1410-1485):** Replace the `currentColumnPlacements` loop body with a call to `descendScaffoldColumn()`. The elevated sweep and bridge sweep calls within the current loop are moved into the new method (they happen at each Y-level).

**Cleanup phase (lines 4084-4152):** Stays as-is. It becomes a safety net for blocks the descent missed (edge cases, abort mid-descent, scaffold placed by bridge sweeps at heights the descent didn't visit). With a working descent, most scaffold should already be removed before cleanup runs.

**`prepareCleanupSurfacePosition()` (line ~4095):** Still runs before cleanup. With a working descent, the bot should already be on ground level by this point, so the recovery logic rarely fires.

### Edge Cases

- **Scaffold column has gaps** (blocks were already mined or missing): Bot falls through the gap to the next scaffold block or to ground. The per-level verification detects this and adjusts.
- **Bot knocked off scaffold** (mob attack, water push): If the bot is no longer on a scaffold block, skip the remaining descent levels and let cleanup handle from wherever the bot landed.
- **Single-block scaffold** (short tree, only 1-2 blocks of scaffold): Works normally — just 1-2 iterations.
- **No scaffold placed** (tree was reachable from ground): `descendScaffoldColumn` returns 0 immediately. No-op.
- **Abort during descent**: Bot stops mid-descent. Remaining scaffold blocks are left for the cleanup phase, which handles orphaned blocks via bridge-to-safety or abandon.

## Files Changed

| File | Change | ~Lines |
|---|---|---|
| `GameAI/skills/impl/WoodcutSkill.java` | New `descendScaffoldColumn` method, replace Phase 4 loop | ~80 |

## Not in Scope

- Changing the ascent/pillar-up logic (it works fine)
- Changing the cleanup phase (it stays as safety net)
- Modifying `BridgeScaffoldService` (bridge sweeps called from within descent, no API change)
- Scaffold placement optimization (handled by the hazard avoidance feature)
