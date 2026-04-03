# Scaffold Descent Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the broken scaffold descent in fellTree so the bot can reliably descend through its own scaffold column after mining tree canopy, enabling trees to actually be felled.

**Architecture:** Replace Phase 4's flat scaffold iteration (which never moves the bot) with `descendScaffoldColumn()` — a Y-level-grouped descent that processes each height from top to bottom: mine bridge blocks at current Y, run elevated sweeps, then mine the column block under feet to drop. The existing elevated sweep and bridge sweep logic is preserved within the new method.

**Tech Stack:** Minecraft 1.21.11 Fabric, Java 21

**Spec:** `docs/superpowers/specs/2026-04-03-scaffold-descent-design.md`

---

## Task 1: Add descendScaffoldColumn method

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java`

- [ ] **Step 1: Add the new method after currentColumnPlacements (line ~1677)**

```java
    /**
     * Descend through the scaffold column by Y-level, mining bridge blocks first at each
     * level then dropping through the column block. Runs elevated sweep + bridge sweep
     * at each height. Returns the total number of logs mined during descent sweeps.
     */
    private int descendScaffoldColumn(
            ServerCommandSource source,
            ServerPlayerEntity bot,
            ServerWorld world,
            TreeDetector.TreeTarget target,
            Map<String, Object> sharedState,
            WoodcutReachSession reachSession) {

        if (reachSession == null || !reachSession.hasPlacements()) return 0;

        int totalMined = 0;

        // Group scaffold blocks by Y-level (descending)
        TreeMap<Integer, List<BlockPos>> byLevel = new TreeMap<>(Collections.reverseOrder());
        for (BlockPos placed : reachSession.placementsDescending()) {
            if (placed == null || !reachSession.placedKeys.contains(placed.asLong())) continue;
            if (world.getBlockState(placed).isAir()) {
                // Already removed (by bridge retraction etc.) — clean up tracking
                forgetScaffoldPlacement(sharedState, world, placed);
                reachSession.recordRemoval(placed);
                continue;
            }
            byLevel.computeIfAbsent(placed.getY(), k -> new ArrayList<>()).add(placed);
        }

        if (byLevel.isEmpty()) return 0;

        for (var entry : byLevel.entrySet()) {
            if (TaskService.isServerStopping() || isAbortRequested(bot)) break;

            int level = entry.getKey();
            List<BlockPos> blocksAtLevel = entry.getValue();

            // Skip levels above the bot — should have been mined already
            if (level > bot.getBlockY()) {
                LOGGER.debug("Scaffold descent: skipping level Y={} (above bot at Y={})", level, bot.getBlockY());
                continue;
            }

            // If bot is below this level (fell past it or landed on ground), stop descending
            if (bot.getBlockY() < level - 1) {
                LOGGER.debug("Scaffold descent: bot at Y={} already below level Y={}, stopping", bot.getBlockY(), level);
                break;
            }

            bot.setSneaking(true);

            // Identify column block (under feet) vs bridge blocks (offset)
            BlockPos columnBlock = null;
            List<BlockPos> bridgeBlocks = new ArrayList<>();
            BlockPos botFeet = bot.getBlockPos();
            for (BlockPos bp : blocksAtLevel) {
                if (bp.getX() == botFeet.getX() && bp.getZ() == botFeet.getZ()
                        && bp.getY() == botFeet.getY() - 1) {
                    columnBlock = bp;
                } else {
                    bridgeBlocks.add(bp);
                }
            }

            // 1. Mine bridge blocks first (reachable from current position)
            for (BlockPos bridge : bridgeBlocks) {
                if (isAbortRequested(bot)) break;
                if (world.getBlockState(bridge).isAir()) {
                    forgetScaffoldPlacement(sharedState, world, bridge);
                    reachSession.recordRemoval(bridge);
                    continue;
                }
                if (isWithinReach(bot, bridge)) {
                    LookController.faceBlock(bot, bridge);
                    if (mineBlock(bot, bridge, false)) {
                        forgetScaffoldPlacement(sharedState, world, bridge);
                        reachSession.recordRemoval(bridge);
                    }
                }
            }

            // 2. Elevated sweep: mine reachable logs at this height
            totalMined += mineReachableBranches(bot, world, reachSession, target);
            for (int sweepPass = 0; sweepPass < 5; sweepPass++) {
                if (isAbortRequested(bot)) break;
                BlockPos botPos = bot.getBlockPos();
                BlockPos found = null;
                for (BlockPos check : BlockPos.iterate(botPos.add(-4, -2, -4), botPos.add(4, 4, 4))) {
                    if (!world.getBlockState(check).isIn(BlockTags.LOGS)) continue;
                    if (!isWithinReach(bot, check)) continue;
                    TreeDetector.WoodcutProtectionDecision prot =
                            getWoodcutMutationDecision(world, check, target.base());
                    if (prot.blocked()) continue;
                    found = check.toImmutable();
                    break;
                }
                if (found == null) break;
                clearPathToTarget(bot, found);
                ensureAxeEquipped(bot);
                if (mineBlock(bot, found, true)) {
                    totalMined++;
                }
            }

            // 3. Bridge sweep: try bridging in each cardinal direction for logs
            if (!isAbortRequested(bot)) {
                for (Direction bridgeDir : Direction.Type.HORIZONTAL) {
                    if (isAbortRequested(bot)) break;
                    boolean hasTargetsInDir = false;
                    for (int d = 2; d <= 6 && !hasTargetsInDir; d++) {
                        BlockPos probe = bot.getBlockPos().offset(bridgeDir, d);
                        for (int dy = -2; dy <= 4; dy++) {
                            if (world.getBlockState(probe.up(dy)).isIn(BlockTags.LOGS)) {
                                hasTargetsInDir = true;
                                break;
                            }
                        }
                    }
                    if (!hasTargetsInDir) continue;
                    if (reachSession.hazardProfile != null) {
                        WoodcutHazardScanner.TerrainRating rating = reachSession.hazardProfile.ratings().get(bridgeDir);
                        if (rating == WoodcutHazardScanner.TerrainRating.RAVINE
                                || rating == WoodcutHazardScanner.TerrainRating.DEEP_WATER) {
                            continue;
                        }
                    }
                    BridgeScaffoldService.BridgeResult bridgeResult =
                            BridgeScaffoldService.bridgeAndRetract(
                                    bot, bridgeDir, 6, false,
                                    state -> state.isIn(BlockTags.LOGS),
                                    target.base(),
                                    PILLAR_BLOCKS);
                    if (bridgeResult.targetsMined() > 0) {
                        totalMined += bridgeResult.targetsMined();
                        LOGGER.info("Scaffold descent bridge sweep: dir={} mined={}", bridgeDir.asString(), bridgeResult.targetsMined());
                    }
                }
            }

            // 4. Mine column block (drop trigger)
            if (columnBlock != null && !isAbortRequested(bot)) {
                if (!world.getBlockState(columnBlock).isAir()) {
                    LookController.faceBlock(bot, columnBlock);
                    if (mineBlock(bot, columnBlock, false)) {
                        forgetScaffoldPlacement(sharedState, world, columnBlock);
                        reachSession.recordRemoval(columnBlock);
                    }
                } else {
                    forgetScaffoldPlacement(sharedState, world, columnBlock);
                    reachSession.recordRemoval(columnBlock);
                }
                // Wait for gravity to settle
                sleepQuiet(250L);
            }

            // 5. Ground check: if bot landed on solid non-scaffold ground, stop descending
            BlockPos support = bot.getBlockPos().down();
            if (!reachSession.placedKeys.contains(support.asLong())
                    && !world.getBlockState(support).getCollisionShape(world, support).isEmpty()) {
                LOGGER.debug("Scaffold descent: landed on solid ground at Y={}", bot.getBlockY());
                break;
            }
        }

        return totalMined;
    }
```

Add required import if not present:
```java
import java.util.TreeMap;
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Add descendScaffoldColumn with Y-level-grouped descent"
```

---

## Task 2: Replace Phase 4 loop with descendScaffoldColumn call

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java:1410-1485`

- [ ] **Step 1: Replace Phase 4 body**

Find the Phase 4 block at lines 1410-1485:

```java
                        // Phase 4: Descent loop (scaffold teardown + elevated sweep + bridge)
                        List<BlockPos> currentPlacements = currentColumnPlacements(reachSession, column);
                        for (BlockPos scaffold : currentPlacements) {
                            if (TaskService.isServerStopping() || isAbortRequested(bot)) {
                                break;
                            }
                            bot.setSneaking(true);
                            totalMined += mineReachableBranches(bot, world, reachSession, target);
                            if (!world.getBlockState(scaffold).isAir() && mineAdaptiveBlock(bot, scaffold, target.base(), reachSession)) {
                                forgetScaffoldPlacement(sharedState, world, scaffold);
                                reachSession.recordRemoval(scaffold);
                                sleepQuiet(200L);
                                bot.setSneaking(true);
                                totalMined += mineReachableBranches(bot, world, reachSession, target);
                                // Elevated sweep: mine any reachable log from this height
                                for (int sweepPass = 0; sweepPass < 5; sweepPass++) {
                                    // ... (entire elevated sweep + bridge sweep code)
                                }
                                // Bridge sweep: try bridging in each cardinal direction
                                if (!isAbortRequested(bot)) {
                                    for (Direction bridgeDir : Direction.Type.HORIZONTAL) {
                                        // ... (entire bridge sweep code)
                                    }
                                }
                            }
                        }
```

Replace the ENTIRE Phase 4 block (from `// Phase 4:` comment through the closing `}` of the for loop, including all nested elevated sweep and bridge sweep code) with:

```java
                        // Phase 4: Y-level-aware scaffold descent with elevated sweeps
                        totalMined += descendScaffoldColumn(source, bot, world, target, sharedState, reachSession);
```

**IMPORTANT:** Read the exact boundaries carefully. Phase 4 starts at line 1410 (`// Phase 4:` comment) and ends at line 1485 (closing `}` of the for loop). Phase 5 starts at line 1487 (`// Phase 5: Final sweep`). Replace everything between 1410-1485 with the single line above. Preserve Phase 5 and everything after.

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Replace Phase 4 flat loop with Y-level scaffold descent"
```

---

## Task 3: Final build + changelog

- [ ] **Step 1: Full build verification**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog**

Add under the 2026-04-03 section in `changelog.md`:

```markdown
- **Fix: Scaffold descent (zero-tree-felled root cause).** Phase 4 of `fellTree` iterated scaffold blocks top→bottom but never moved the bot, leaving it stranded at canopy height. Replaced with `descendScaffoldColumn()` which groups scaffold by Y-level and processes top→bottom: mine bridge blocks at each level, run elevated sweep for logs, then mine the column block under the bot's feet to trigger a gravity drop. The bot now reliably descends through its own scaffold column while harvesting logs at each height.
```

- [ ] **Step 3: Commit**

```bash
git add changelog.md
git commit -m "docs: Add scaffold descent changelog entry"
```
