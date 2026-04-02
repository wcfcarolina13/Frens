# Woodcut Scaffold Rework Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current "pillar to max height" scaffold approach with level-by-level column mining that mines ALL reachable logs, uses correct tools for scaffold blocks, and never jumps off scaffolds.

**Architecture:** The branch-mining phase in `fellTree()` is replaced by a new `scaffoldAndMineReachableLogs()` method that visits XZ columns one at a time, scaffolding up one block per level, mining all reachable log blocks at each level, then safely tearing down before moving to the next column. The `selectScaffoldToolOrHands()` method is fixed to use block-material-aware tool selection.

**Tech Stack:** Java 21, Minecraft Fabric 1.21.11, existing WoodcutSkill infrastructure

**Spec:** `docs/superpowers/specs/2026-03-30-woodcut-scaffold-rework-design.md`

---

## File Map

| File | Changes |
|------|---------|
| `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` | Fix `selectScaffoldToolOrHands`; new `scaffoldAndMineReachableLogs`, `scanReachableLogs`, `pickNextScaffoldColumn`; rework `fellTree` branch phase |

---

### Task 1: Fix `selectScaffoldToolOrHands` — block-material-aware tool selection

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java:2556-2562`

The current method always picks shovel. This causes shovel-on-cobblestone when the bot scaffolds with cobblestone. Fix it to check the actual block material.

- [ ] **Step 1: Replace `selectScaffoldToolOrHands` with block-aware version**

Replace the method at line 2556-2562 with:

```java
private void selectScaffoldToolOrHands(ServerPlayerEntity bot) {
    selectScaffoldToolForBlock(bot, null);
}

private void selectScaffoldToolForBlock(ServerPlayerEntity bot, BlockState blockState) {
    if (blockState != null && blockState.isIn(BlockTags.PICKAXE_MINEABLE)) {
        if (hasToolKeyword(bot, "pickaxe")) {
            BotActions.selectBestTool(bot, "pickaxe", "axe");
            return;
        }
    } else if (blockState == null || blockState.isIn(BlockTags.SHOVEL_MINEABLE)) {
        if (hasToolKeyword(bot, "shovel")) {
            BotActions.selectBestTool(bot, "shovel", "axe");
            return;
        }
    }
    selectHandsOrHarmlessItem(bot);
}
```

Also add the `BlockTags.PICKAXE_MINEABLE` and `BlockTags.SHOVEL_MINEABLE` imports if not present (they should already be available via `net.minecraft.registry.tag.BlockTags`).

- [ ] **Step 2: Update `cleanupReachSession` to use block-aware tool selection**

In `cleanupReachSession` (line 2390-2431), before calling `mineBlock(bot, placed, false)` at line 2422, add block-aware tool selection:

Change line 2422 from:
```java
if (mineBlock(bot, placed, false) || world.getBlockState(placed).isAir()) {
```
to:
```java
selectScaffoldToolForBlock(bot, world.getBlockState(placed));
if (mineBlock(bot, placed, true) || world.getBlockState(placed).isAir()) {
```

Note: `preferAxe=false` → `preserveSelectedHotbarItem=true` (second arg to mineBlock, passed through to `mineBlockDetailed` then to `MiningTool.mineBlock`). Wait — `mineBlock(bot, pos, boolean)` is a private wrapper where the boolean is `preferAxe`. When `preferAxe=false` it calls `selectScaffoldToolOrHands(bot)` inside `mineBlockDetailed`. So we need to pre-select the right tool and then pass `preferAxe=false` which will call the no-arg `selectScaffoldToolOrHands(bot)` overload.

Actually, looking more carefully: `mineBlockDetailed` at line 2330-2333 checks `preferAxe`: if true calls `ensureAxeEquipped`, if false calls `selectScaffoldToolOrHands(bot)`. Since we fixed `selectScaffoldToolOrHands` to be a no-arg that falls through to hands (no block context), this won't work for scaffold cleanup.

Better approach: update `cleanupReachSession` to pre-select the tool AND pass `preferAxe=true` so `mineBlockDetailed` calls `ensureAxeEquipped` — no, that selects an axe.

Simplest fix: pre-select the correct tool before calling mineBlock, and use `mineBlock` with `preferAxe=false`. Since we keep the no-arg `selectScaffoldToolOrHands` as a fallback for dirt/shovel, and `mineBlockDetailed` will call it again at line 2333, the tool gets re-selected. This is redundant but harmless because the no-arg version now falls through to hands if nothing matches.

Actually, the cleanest approach: make `mineBlockDetailed` accept an optional `BlockState` for scaffold tool selection. But that changes too many signatures.

**Revised approach:** Just fix the no-arg `selectScaffoldToolOrHands` to check what the bot is standing on. Since scaffold blocks are always underfoot, read the block below the bot:

```java
private void selectScaffoldToolOrHands(ServerPlayerEntity bot) {
    if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
        selectHandsOrHarmlessItem(bot);
        return;
    }
    // Check what we're likely about to mine — look at the block we're facing
    // or the most recent scaffold material. Fall back to checking inventory.
    selectScaffoldToolForBlock(bot, null);
}
```

Actually this is overcomplicating it. The simplest correct fix:

Keep the existing call sites unchanged. Just fix `selectScaffoldToolOrHands` to also check for pickaxe when appropriate. The method is called in two contexts:
1. Before mining scaffold blocks (cleanup) — needs correct tool for the block
2. As fallback in `mineBlockDetailed` when `preferAxe=false` — same thing

Since we can't know the block from the method signature, and changing the signature touches many call sites, the pragmatic fix is: **always prefer pickaxe for scaffold mining, fall back to shovel, then hands**. Pickaxes mine dirt (slowly but correctly), and they mine cobblestone/stone correctly. Shovels mine cobblestone wrong.

```java
private void selectScaffoldToolOrHands(ServerPlayerEntity bot) {
    if (hasToolKeyword(bot, "pickaxe")) {
        BotActions.selectBestTool(bot, "pickaxe", "axe");
        return;
    }
    if (hasToolKeyword(bot, "shovel")) {
        BotActions.selectBestTool(bot, "shovel", "axe");
        return;
    }
    selectHandsOrHarmlessItem(bot);
}
```

This is safe because:
- Pickaxe mines cobblestone/deepslate at correct speed
- Pickaxe mines dirt/gravel slower than shovel but still correct (won't use wrong tool)
- If no pickaxe, shovel handles dirt/gravel; cobblestone mines slowly with shovel but won't crash
- Hands as last resort

- [ ] **Step 3: Build and verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "fix: Scaffold tool selection prefers pickaxe for stone-type scaffold blocks"
```

---

### Task 2: Add `scanReachableLogs` helper method

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` (add new method near line 1044, after `scoreOwnedLogCandidate`)

- [ ] **Step 1: Add the scanReachableLogs method**

This method scans a sphere around the bot for ALL log blocks within reach distance, regardless of tree ownership:

```java
/**
 * Scans for all log blocks within reach distance of the bot.
 * Does NOT check tree ownership — mines any log within reach.
 */
private List<BlockPos> scanReachableLogs(ServerPlayerEntity bot, ServerWorld world) {
    List<BlockPos> reachable = new ArrayList<>();
    if (bot == null || world == null) {
        return reachable;
    }
    int scanRadius = 5; // slightly larger than reach distance (4.5) to account for block centers
    BlockPos botBlock = bot.getBlockPos();
    for (BlockPos candidate : BlockPos.iterate(
            botBlock.add(-scanRadius, -scanRadius, -scanRadius),
            botBlock.add(scanRadius, scanRadius, scanRadius))) {
        if (!world.getBlockState(candidate).isIn(BlockTags.LOGS)) {
            continue;
        }
        if (!isWithinReach(bot, candidate)) {
            continue;
        }
        if (hasLineOfSight(bot, Vec3d.ofCenter(candidate))) {
            reachable.add(candidate.toImmutable());
        }
    }
    // Sort: lower Y first (mine pillars bottom-to-top), then by distance
    reachable.sort(Comparator
            .comparingInt((BlockPos p) -> p.getY())
            .thenComparingDouble(p -> bot.getBlockPos().getSquaredDistance(p)));
    return reachable;
}
```

- [ ] **Step 2: Build and verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Add scanReachableLogs helper for reach-based log scanning"
```

---

### Task 3: Add `scaffoldAndMineReachableLogs` — the new branch-mining method

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` (add new method after `scanReachableLogs`)

This is the core new method that replaces the old `while(true) { selectNextOwnedLogTarget }` loop.

- [ ] **Step 1: Add the scaffoldAndMineReachableLogs method**

```java
/**
 * Level-by-level scaffold mining of remaining logs after trunk is cut.
 * Visits XZ columns, scaffolds up one block at a time, mines ALL reachable
 * logs at each level, then safely tears down before moving to the next column.
 */
private int scaffoldAndMineReachableLogs(ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          TreeDetector.TreeTarget target,
                                          WoodcutReachSession reachSession,
                                          Map<String, Object> sharedState) {
    if (!(source.getWorld() instanceof ServerWorld world)) {
        return 0;
    }
    int totalMined = 0;
    Set<Long> visitedColumns = new HashSet<>();
    int maxColumnAttempts = 20; // safety cap to prevent infinite loops

    for (int attempt = 0; attempt < maxColumnAttempts; attempt++) {
        if (isAbortRequested(bot)) break;

        // Find remaining logs in the expanded envelope area.
        List<BlockPos> remainingLogs = scanEnvelopeLogs(world, target);
        if (remainingLogs.isEmpty()) break;

        // First, mine any logs reachable from ground level without scaffolding.
        List<BlockPos> groundReachable = scanReachableLogs(bot, world);
        for (BlockPos log : groundReachable) {
            if (isAbortRequested(bot)) break;
            LookController.faceBlock(bot, log);
            ensureAxeEquipped(bot);
            MineAttemptResult result = mineBlockDetailed(bot, log, true, target.base(), reachSession);
            if (result.success()) {
                totalMined++;
            }
        }

        // Re-scan after ground-level mining.
        remainingLogs = scanEnvelopeLogs(world, target);
        if (remainingLogs.isEmpty()) break;

        // Pick the best XZ column to scaffold from.
        BlockPos columnTarget = pickNextScaffoldColumn(bot, world, remainingLogs, visitedColumns);
        if (columnTarget == null) break;

        long columnKey = BlockPos.asLong(columnTarget.getX(), 0, columnTarget.getZ());
        visitedColumns.add(columnKey);

        // Move to the column base position.
        if (horizontalDistance(bot.getBlockPos(), columnTarget) > 1.5) {
            BlockPos standPos = findStandableNear(world, new BlockPos(columnTarget.getX(), bot.getBlockY(), columnTarget.getZ()), 1, 3);
            if (standPos == null) standPos = new BlockPos(columnTarget.getX(), bot.getBlockY(), columnTarget.getZ());
            MovementService.planLootApproach(bot, standPos, WOODCUT_MOVEMENT_OPTIONS)
                    .ifPresent(plan -> MovementService.execute(source, bot, plan, false, true, true, false));
        }

        // Determine max scaffold height needed for this column.
        int maxLogY = remainingLogs.stream()
                .filter(p -> Math.abs(p.getX() - columnTarget.getX()) <= 5
                          && Math.abs(p.getZ() - columnTarget.getZ()) <= 5)
                .mapToInt(BlockPos::getY)
                .max()
                .orElse(bot.getBlockY());
        int maxScaffoldHeight = Math.min(maxLogY - bot.getBlockY() + 2, 18); // cap at reasonable height

        if (maxScaffoldHeight <= 0) {
            // All remaining logs are at or below ground level — skip scaffolding.
            continue;
        }

        // Ensure we have enough scaffold material.
        if (!ensurePillarStock(bot, maxScaffoldHeight, source, bot.getBlockY() + maxScaffoldHeight, reachSession, sharedState)) {
            LOGGER.warn("Woodcut scaffold-mine: insufficient pillar stock for {} steps", maxScaffoldHeight);
            break;
        }

        // Level-by-level scaffold + mine.
        boolean wasSneaking = bot.isSneaking();
        bot.setSneaking(true);
        int scaffoldHeight = 0;

        for (int level = 0; level < maxScaffoldHeight; level++) {
            if (isAbortRequested(bot)) break;

            // Pillar up one block.
            boolean placed = pillarUpOneStep(bot, reachSession, sharedState);
            if (!placed) {
                LOGGER.warn("Woodcut scaffold-mine: failed to place scaffold at level {}", level + 1);
                break;
            }
            scaffoldHeight++;
            sleepQuiet(80L);

            // Mine all reachable logs at this level.
            int minedThisLevel = mineAllReachableLogsAtLevel(bot, world, source, target, reachSession, sharedState);
            totalMined += minedThisLevel;

            // Check if there are still logs above us that need more scaffolding.
            boolean logsAbove = scanEnvelopeLogs(world, target).stream()
                    .anyMatch(p -> p.getY() > bot.getBlockY() + 1
                               && Math.abs(p.getX() - columnTarget.getX()) <= 5
                               && Math.abs(p.getZ() - columnTarget.getZ()) <= 5);
            if (!logsAbove) break;
        }

        // Safe descent: tear down scaffold top-to-bottom while staying sneaked.
        cleanupReachSession(source, bot, target.base(), reachSession, sharedState);
        bot.setSneaking(wasSneaking);
    }

    return totalMined;
}
```

- [ ] **Step 2: Add `pillarUpOneStep` helper**

A thin wrapper around the existing `pillarUp` for single-step scaffolding:

```java
private boolean pillarUpOneStep(ServerPlayerEntity bot,
                                 WoodcutReachSession reachSession,
                                 Map<String, Object> sharedState) {
    ServerWorld world = (ServerWorld) bot.getEntityWorld();
    BlockPos candidate = bot.getBlockPos();
    if (!world.getBlockState(candidate).isAir()) {
        candidate = candidate.up();
    }
    BotActions.jump(bot);
    sleepQuiet(PILLAR_STEP_DELAY_MS);
    if (!world.getBlockState(candidate).isAir()) {
        candidate = candidate.up();
    }
    boolean placed = tryPlaceScaffold(bot, candidate, sharedState, reachSession);
    if (placed && reachSession != null) {
        reachSession.recordPlacement(candidate);
    }
    sleepQuiet(PILLAR_STEP_DELAY_MS);
    return placed;
}
```

- [ ] **Step 3: Add `mineAllReachableLogsAtLevel` helper**

Mines all logs within reach at the current scaffold height:

```java
private int mineAllReachableLogsAtLevel(ServerPlayerEntity bot,
                                         ServerWorld world,
                                         ServerCommandSource source,
                                         TreeDetector.TreeTarget target,
                                         WoodcutReachSession reachSession,
                                         Map<String, Object> sharedState) {
    int mined = 0;
    int maxPerLevel = 30; // safety cap
    for (int pass = 0; pass < maxPerLevel; pass++) {
        if (isAbortRequested(bot)) break;
        List<BlockPos> reachable = scanReachableLogs(bot, world);
        if (reachable.isEmpty()) break;
        BlockPos log = reachable.get(0);
        LookController.faceBlock(bot, log);
        ensureAxeEquipped(bot);
        MineAttemptResult result = mineBlockDetailed(bot, log, true, target.base(), reachSession);
        if (result.success()) {
            mined++;
        } else {
            // Skip this one if we can't mine it.
            break;
        }
    }
    return mined;
}
```

- [ ] **Step 4: Add `scanEnvelopeLogs` helper**

Scans the tree envelope (expanded) for any remaining log blocks:

```java
private List<BlockPos> scanEnvelopeLogs(ServerWorld world, TreeDetector.TreeTarget target) {
    List<BlockPos> logs = new ArrayList<>();
    if (world == null || target == null) {
        return logs;
    }
    // Expand envelope by reach distance to catch nearby orphaned branches.
    int expand = 5;
    BlockPos min = target.envelopeMin().add(-expand, -2, -expand);
    BlockPos max = target.envelopeMax().add(expand, 2, expand);
    for (BlockPos candidate : BlockPos.iterate(min, max)) {
        if (world.getBlockState(candidate).isIn(BlockTags.LOGS)) {
            logs.add(candidate.toImmutable());
        }
    }
    return logs;
}
```

- [ ] **Step 5: Add `pickNextScaffoldColumn` helper**

Picks the best XZ position to scaffold from, avoiding visited columns:

```java
private BlockPos pickNextScaffoldColumn(ServerPlayerEntity bot,
                                         ServerWorld world,
                                         List<BlockPos> remainingLogs,
                                         Set<Long> visitedColumns) {
    if (remainingLogs.isEmpty()) {
        return null;
    }
    // Pick the remaining log closest to the bot, preferring positions not yet visited.
    BlockPos botPos = bot.getBlockPos();
    return remainingLogs.stream()
            .filter(log -> {
                long colKey = BlockPos.asLong(log.getX(), 0, log.getZ());
                return !visitedColumns.contains(colKey);
            })
            .min(Comparator
                    .comparingInt((BlockPos p) -> p.getY()) // prefer lower logs first
                    .thenComparingDouble(p -> botPos.getSquaredDistance(p)))
            .orElse(null);
}
```

- [ ] **Step 6: Build and verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Add scaffoldAndMineReachableLogs for level-by-level column mining"
```

---

### Task 4: Wire up `scaffoldAndMineReachableLogs` in `fellTree`

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java:948-985`

Replace the old branch-mining loop with the new method.

- [ ] **Step 1: Replace the branch-mining phase in `fellTree`**

Replace lines 948-985 (the `while(true)` owned-log loop + leftover check):

**Old code (lines 948-985):**
```java
// Then drain any same-tree owned logs inside the selected tree envelope.
while (true) {
    if (isAbortRequested(bot)) {
        return new TreeHarvestResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "abort-requested");
    }
    BlockPos next = selectNextOwnedLogTarget(world, bot, target, failedOwnedLogs);
    if (next == null) {
        break;
    }
    MineAttemptResult cleanupResult = mineWithRetries(bot, source, next, reachSession, true, sharedState, target.base());
    if (cleanupResult.success()) {
        failedOwnedLogs.remove(next.asLong());
        forgetCleanupFloater(bot, sharedState, world, next);
        unreachable = 0;
    } else {
        LOGGER.warn("Owned log {} for base {} remained after harvest attempt", next.toShortString(), target.base().toShortString());
        failedOwnedLogs.add(next.asLong());
        pendingFloaters.add(next.toImmutable());
        recordCleanupFloater(bot, sharedState, world, next);
        unreachable++;
        if (unreachable >= 4) {
            LOGGER.warn("Stopping same-tree completion for base {} after {} unreachable owned logs", target.base().toShortString(), unreachable);
            break;
        }
    }
}
List<BlockPos> leftoverOwnedLogs = TreeDetector.collectOwnedTreeLogs(world, target);
if (!leftoverOwnedLogs.isEmpty()) {
    leftoverOwnedLogs.forEach(pos -> {
        pendingFloaters.add(pos.toImmutable());
        recordCleanupFloater(bot, sharedState, world, pos);
    });
    LOGGER.warn("Tree {} still has {} owned log(s) after harvest: {}",
            target.base().toShortString(),
            leftoverOwnedLogs.size(),
            leftoverOwnedLogs.stream().limit(4).map(BlockPos::toShortString).toList());
    return new TreeHarvestResult(false, WoodcutFailureReason.PATH_OR_REACH_FAILURE, "owned-logs-remained");
}
```

**New code:**
```java
// Level-by-level scaffold mining of remaining branches and nearby orphaned canopy.
int branchLogsMined = scaffoldAndMineReachableLogs(source, bot, target, reachSession, sharedState);
LOGGER.info("Woodcut branch phase: mined {} logs for base {}", branchLogsMined, target.base().toShortString());

// Check for leftover owned logs — record as floaters but don't fail the tree.
List<BlockPos> leftoverOwnedLogs = TreeDetector.collectOwnedTreeLogs(world, target);
if (!leftoverOwnedLogs.isEmpty()) {
    leftoverOwnedLogs.forEach(pos -> {
        pendingFloaters.add(pos.toImmutable());
        recordCleanupFloater(bot, sharedState, world, pos);
    });
    LOGGER.info("Tree {} has {} leftover log(s) after branch phase: {}",
            target.base().toShortString(),
            leftoverOwnedLogs.size(),
            leftoverOwnedLogs.stream().limit(4).map(BlockPos::toShortString).toList());
}
```

Note: We no longer fail the tree harvest due to leftover logs — the new approach is best-effort and the cleanup phase / pending floaters handle the rest. The `failedOwnedLogs` and `unreachable` variables from the old loop can be removed from `fellTree` since they're no longer used.

- [ ] **Step 2: Clean up unused variables in `fellTree`**

Remove the `Set<Long> failedOwnedLogs` declaration at line 932 and the `unreachable` counter setup. These are no longer used by the new branch phase.

Remove:
```java
Set<Long> failedOwnedLogs = new HashSet<>();
```
and the `int unreachable = 0;` at line 930 (keep `boolean success = false;`).

- [ ] **Step 3: Build and verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Wire up level-by-level scaffold mining in fellTree branch phase"
```

---

### Task 5: Final build, deploy, update changelog

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` (any compile fixes)
- Modify: `changelog.md`

- [ ] **Step 1: Full build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Deploy JARs**

```bash
cp build/libs/frens-1.1.0-release+1.21.11.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/mods/"
cp build/libs/frens-1.1.0-release+1.21.11.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/mods/"
cp build/libs/frens-1.1.0-release+1.21.11.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10 TEST/minecraft/mods/"
```

- [ ] **Step 3: Update changelog**

Add entry to `changelog.md` summarizing:
- Fixed scaffold tool selection (pickaxe for stone-type, shovel for dirt)
- Replaced branch mining with level-by-level column scaffold approach
- Bot mines ALL reachable logs (not just owned) during branch phase
- Bot stays sneaked on scaffold, never jumps off
- Safe top-down scaffold teardown
- Column visit tracking prevents re-scaffolding at same position
- Height-aware tree ownership from prior commit helps with overlapping canopies

- [ ] **Step 4: Commit**

```bash
git add changelog.md
git commit -m "docs: Update changelog with woodcut scaffold rework"
```

---

## Verification

1. `./gradlew build -x test` — compilation
2. In-game: spawn bot in dense forest with overlapping canopies
3. `/bot skill woodcut <bot>` — observe:
   - Bot cuts trunk first (unchanged)
   - Bot scaffolds up ONE block at a time, mining all reachable logs at each level
   - Bot stays sneaked while on scaffold
   - Bot uses pickaxe on cobblestone/stone scaffolds, shovel on dirt
   - Bot tears down scaffold top-to-bottom, never jumps off
   - Bot moves to new XZ position for unreachable logs, scaffolds there
   - Bot never re-scaffolds at a visited column
   - Orphaned canopy branches (from trees that lost their trunk) are mined
