# Woodcut Hazard Avoidance Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent bots from placing scaffold over ravines and deep water, work trees from the safe side, and recover gracefully when scaffold cleanup gets stuck.

**Architecture:** New `WoodcutHazardScanner` performs per-tree terrain assessment. `WoodcutSkill` uses the profile to filter scaffold directions, reject fully-enclosed trees, and recover via bridge-to-safety or abandon when cleanup fails. Hazard profile stored on `WoodcutReachSession`.

**Tech Stack:** Minecraft 1.21.11 Fabric, Java 21

**Spec:** `docs/superpowers/specs/2026-04-03-woodcut-hazard-avoidance-design.md`

---

## Chunk 1: Hazard Scanner + Tree Selection

### Task 1: Create WoodcutHazardScanner with terrain probing

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/skills/support/WoodcutHazardScanner.java`

- [ ] **Step 1: Create the scanner class with TerrainRating and TreeHazardProfile**

```java
package net.wcfcarolina13.GameAI.skills.support;

import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class WoodcutHazardScanner {

    private WoodcutHazardScanner() {}

    public enum TerrainRating { SAFE, SHALLOW_WATER, DEEP_WATER, RAVINE }

    public record TreeHazardProfile(
            Map<Direction, TerrainRating> ratings,
            boolean hasAnySafeApproach,
            boolean fullyEnclosed,
            List<Direction> safeSides
    ) {}

    private static final int PROBE_DEPTH = 6;
    private static final int RAVINE_DROP_THRESHOLD = 3;
    private static final int SHALLOW_WATER_MAX_DEPTH = 2;
    private static final int DEEP_WATER_FLOOR_PROBE = 4;

    /**
     * Scan terrain around a tree base in 4 cardinal directions.
     * Returns a hazard profile indicating which sides are safe for approach/scaffolding.
     */
    public static TreeHazardProfile scan(ServerWorld world, BlockPos treeBase) {
        Map<Direction, TerrainRating> ratings = new EnumMap<>(Direction.class);
        List<Direction> safeSides = new ArrayList<>();

        for (Direction dir : Direction.Type.HORIZONTAL) {
            TerrainRating worst = TerrainRating.SAFE;
            BlockPos current = treeBase;

            for (int step = 1; step <= PROBE_DEPTH; step++) {
                current = treeBase.offset(dir, step);
                TerrainRating rating = assessPosition(world, current, treeBase.getY());
                if (rating.ordinal() > worst.ordinal()) {
                    worst = rating;
                }
                if (worst == TerrainRating.RAVINE || worst == TerrainRating.DEEP_WATER) {
                    break; // worst possible, no need to probe further
                }
            }

            ratings.put(dir, worst);
            if (worst == TerrainRating.SAFE || worst == TerrainRating.SHALLOW_WATER) {
                safeSides.add(dir);
            }
        }

        boolean hasAnySafe = !safeSides.isEmpty();
        boolean fullyEnclosed = safeSides.isEmpty();
        return new TreeHazardProfile(ratings, hasAnySafe, fullyEnclosed, List.copyOf(safeSides));
    }

    /**
     * Assess a single position relative to the tree base Y level.
     */
    private static TerrainRating assessPosition(ServerWorld world, BlockPos pos, int baseY) {
        if (!world.isChunkLoaded(pos)) return TerrainRating.SAFE; // can't assess, assume safe

        // Walk down from base Y to find ground level at this position
        BlockPos groundCheck = new BlockPos(pos.getX(), baseY, pos.getZ());
        int dropDepth = 0;

        for (int d = 0; d <= RAVINE_DROP_THRESHOLD + DEEP_WATER_FLOOR_PROBE; d++) {
            BlockPos probe = groundCheck.down(d);
            var blockState = world.getBlockState(probe);
            var fluidState = world.getFluidState(probe);

            // Hit water — count depth
            if (fluidState.isIn(FluidTags.WATER)) {
                return classifyWaterDepth(world, probe);
            }

            // Hit solid ground
            if (!blockState.getCollisionShape(world, probe).isEmpty()) {
                // Ground found — is the drop dangerous?
                return dropDepth >= RAVINE_DROP_THRESHOLD ? TerrainRating.RAVINE : TerrainRating.SAFE;
            }

            dropDepth++;
        }

        // Probed all the way down without hitting solid or water — deep void
        return TerrainRating.RAVINE;
    }

    private static TerrainRating classifyWaterDepth(ServerWorld world, BlockPos waterSurface) {
        int depth = 0;
        for (int d = 0; d < DEEP_WATER_FLOOR_PROBE; d++) {
            BlockPos probe = waterSurface.down(d);
            if (world.getFluidState(probe).isIn(FluidTags.WATER)) {
                depth++;
            } else {
                break;
            }
        }
        // Check for solid floor below water
        BlockPos floor = waterSurface.down(depth);
        boolean hasFloor = !world.getBlockState(floor).getCollisionShape(world, floor).isEmpty();

        if (depth <= SHALLOW_WATER_MAX_DEPTH && hasFloor) {
            return TerrainRating.SHALLOW_WATER;
        }
        return TerrainRating.DEEP_WATER;
    }
}
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/support/WoodcutHazardScanner.java
git commit -m "feat: Add WoodcutHazardScanner for ravine/water terrain assessment"
```

---

### Task 2: Add TreeHazardProfile to WoodcutReachSession and tree selection

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java`

- [ ] **Step 1: Add hazardProfile field to WoodcutReachSession**

In the `WoodcutReachSession` class (line ~291), add a field after the existing fields:

```java
    private WoodcutHazardScanner.TreeHazardProfile hazardProfile;
```

Add import at the top of WoodcutSkill.java:
```java
import net.wcfcarolina13.GameAI.skills.support.WoodcutHazardScanner;
```

- [ ] **Step 2: Add hazard scan in tree selection**

In `scanDetectionSnapshot()`, the hazard check must go AFTER `TreeDetector.detectTreeAtForWoodcut()` succeeds and returns a valid `TreeTarget` (around line 3224-3225), and AFTER the visited/failedBases checks (lines 3226-3237), but BEFORE the nearest-tree distance comparison (line ~3238). Insert between line 3237 (`continue;` for failedBases) and line 3238 (`double treeDistSq = ...`):

```java
                    // Hazard terrain check: reject trees fully enclosed by ravines/deep water
                    WoodcutHazardScanner.TreeHazardProfile hazard =
                            WoodcutHazardScanner.scan(world, tree.base());
                    if (hazard.fullyEnclosed()) {
                        failedBaseReasons.put(tree.base().toImmutable(), "hazardous terrain on all sides");
                        failedBases.add(tree.base().toImmutable());
                        continue;
                    }
```

**IMPORTANT:** Use `tree.base()` (the detected tree's base position), NOT `pos` (the arbitrary log position being iterated). Read the code at lines 3220-3242 to confirm the variable names for the detected tree and the exact insertion point.

- [ ] **Step 3: Set hazard profile on WoodcutReachSession when tree is selected**

Find where `WoodcutReachSession` is created for each tree (search for `new WoodcutReachSession` or where `reachSession` is initialized per tree). After creation, set the profile:

```java
reachSession.hazardProfile = WoodcutHazardScanner.scan(world, target.base());
```

If the hazard scan was already done during selection (Step 2), consider caching it to avoid scanning twice. One option: store the profile in the `failedBaseReasons` map won't work (wrong type), so just re-scan — it's a lightweight 4-direction probe, not expensive.

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Reject fully-enclosed trees, store hazard profile on reach session"
```

---

### Task 3: Filter scaffold placement by hazard direction

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java:3807-3821`

- [ ] **Step 1: Add direction filtering in tryPlaceScaffold cardinal loop**

In `tryPlaceScaffold()`, the cardinal direction loop at lines 3807-3821 currently iterates all `Direction.Type.HORIZONTAL`. Add a hazard filter inside the loop. Find:

```java
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos alt = placePos.offset(dir);
```

After `BlockPos alt = placePos.offset(dir);`, add the hazard and water checks:

```java
                // Skip directions with ravine or deep water hazard
                if (reachSession != null && reachSession.hazardProfile != null) {
                    WoodcutHazardScanner.TerrainRating rating = reachSession.hazardProfile.ratings().get(dir);
                    if (rating == WoodcutHazardScanner.TerrainRating.RAVINE
                            || rating == WoodcutHazardScanner.TerrainRating.DEEP_WATER) {
                        continue;
                    }
                }
                // Never place scaffold over water (even shallow)
                if (world.getFluidState(alt.down()).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                    continue;
                }
```

- [ ] **Step 2: Verify primary placement does NOT get a water check**

Do NOT add a water check on the primary `placePos` (the bot's pillar column). The primary placement is the column the bot is currently in — if the bot is already there, refusing placement would break normal pillaring when legitimately above shallow water. The water check applies only to the cardinal offset loop (Step 1 above).

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Filter scaffold placement by hazard direction and water"
```

---

### Task 4: Filter bridge sweep directions by hazard profile

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java`

- [ ] **Step 1: Add hazard filter at BridgeScaffoldService call sites**

Search for `bridgeAndRetract` in WoodcutSkill.java. There are 3 call sites (around lines 1378, 1448, 1514). At each call site, wrap the call in a hazard check. The pattern before each call should look like:

```java
// Before calling bridgeAndRetract, check if direction is hazardous
if (reachSession != null && reachSession.hazardProfile != null) {
    WoodcutHazardScanner.TerrainRating rating = reachSession.hazardProfile.ratings().get(bridgeDir);
    if (rating == WoodcutHazardScanner.TerrainRating.RAVINE
            || rating == WoodcutHazardScanner.TerrainRating.DEEP_WATER) {
        LOGGER.debug("Woodcut bridge: skipping {} direction (hazard={})", bridgeDir, rating);
        continue; // or skip this bridge attempt
    }
}
```

Read each call site's context carefully — some may be in a loop where `continue` works, others may need a different skip pattern (e.g., set a flag and skip).

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Skip bridge sweeps toward hazardous directions"
```

---

## Chunk 2: Scaffold Cleanup Recovery

### Task 5: Add findEscapeStandNear method

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java`

- [ ] **Step 1: Add findEscapeStandNear after findDryStandableNear**

After `findDryStandableNear` (line ~4777), add:

```java
    /**
     * Find an escape position with relaxed safety criteria. Unlike findDryStandableNear,
     * this allows positions near dangerous drops IF at least 2 cardinal neighbors are
     * standable, and allows positions adjacent to shallow water. Returns the nearest
     * standable neighbor of any dangerous-drop candidate (not the drop position itself).
     */
    private BlockPos findEscapeStandNear(ServerWorld world, BlockPos center, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -4, -radius), center.add(radius, 4, radius))) {
            BlockPos foot = pos.toImmutable();
            if (!world.isChunkLoaded(foot)) continue;
            if (!isUsableWoodcutStand(world, foot)) continue;

            // Normal safe stand — best option
            if (isSafeWoodcutWorkStand(world, foot)) {
                candidates.add(foot);
                continue;
            }

            // Relaxed: dangerous drop OK if 2+ cardinal neighbors are standable
            if (FollowMovementService.isDangerousDropCell(world, foot)) {
                int standableNeighbors = 0;
                BlockPos bestNeighbor = null;
                double bestNeighborDist = Double.MAX_VALUE;
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    BlockPos neighbor = foot.offset(dir);
                    if (isUsableWoodcutStand(world, neighbor)
                            && !FollowMovementService.isDangerousDropCell(world, neighbor)) {
                        standableNeighbors++;
                        double dist = neighbor.getSquaredDistance(center);
                        if (dist < bestNeighborDist) {
                            bestNeighborDist = dist;
                            bestNeighbor = neighbor;
                        }
                    }
                }
                if (standableNeighbors >= 2 && bestNeighbor != null) {
                    // Return the safe neighbor, not the dangerous position
                    candidates.add(bestNeighbor);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return candidates.get(0);
    }
```

Add required import if not already present:
```java
import net.wcfcarolina13.GameAI.services.FollowMovementService;
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Add findEscapeStandNear with relaxed safety criteria"
```

---

### Task 6: Add bridge-to-safety + abandon recovery in scaffold cleanup

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java:4060-4080`

- [ ] **Step 1: Add bridge-to-safety recovery method**

Add a new private method after `moveNearScaffoldForCleanup`:

```java
    /**
     * When scaffold is unreachable during cleanup, try to bridge to safe ground.
     * Returns true if the bot reached safe ground (scaffold may or may not be cleaned).
     */
    private boolean tryBridgeToSafetyForCleanup(ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  BlockPos placed,
                                                  Map<String, Object> sharedState,
                                                  WoodcutReachSession reachSession) {
        // Find nearest safe ground within 6 blocks
        BlockPos safeGround = findDryStandableNear(world, bot.getBlockPos(), 6, 4);
        if (safeGround == null) {
            // Try relaxed escape
            safeGround = findEscapeStandNear(world, bot.getBlockPos(), 8);
        }
        if (safeGround == null) {
            return false;
        }

        // Check if we have scaffold blocks to build bridge
        if (countPillarBlocks(bot) < 2) {
            return false;
        }

        // Determine bridge direction
        double dx = safeGround.getX() - bot.getBlockPos().getX();
        double dz = safeGround.getZ() - bot.getBlockPos().getZ();
        Direction bridgeDir;
        if (Math.abs(dx) >= Math.abs(dz)) {
            bridgeDir = dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            bridgeDir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        // Build bridge toward safe ground (max 6 blocks)
        int bridgeLength = Math.min(6, (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(safeGround)));
        List<BlockPos> bridgeBlocks = new ArrayList<>();
        BlockPos current = bot.getBlockPos();
        for (int i = 0; i < bridgeLength; i++) {
            if (shouldAbortSurvival(bot)) break;
            BlockPos bridgePos = current.offset(bridgeDir, i + 1).down();
            if (world.getBlockState(bridgePos).getCollisionShape(world, bridgePos).isEmpty()) {
                if (BotActions.placeBlockAt(bot, bridgePos, Direction.UP, PILLAR_BLOCKS)) {
                    bridgeBlocks.add(bridgePos);
                    recordScaffoldPlacement(sharedState, world, bridgePos);
                    if (reachSession != null) reachSession.recordPlacement(bridgePos);
                }
            }
            // Walk forward
            BlockPos nextPos = current.offset(bridgeDir, i + 1);
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT, nextPos, nextPos, null, null, bridgeDir);
            MovementService.execute(source, bot, plan, false, true, true, false);
            sleepQuiet(100L);

            if (isSafeWoodcutWorkStand(world, bot.getBlockPos())) {
                // Reached safe ground — try to clean scaffold from here
                if (isWithinReach(bot, placed)) {
                    LookController.faceBlock(bot, placed);
                    mineBlock(bot, placed, false);
                }
                // Clean up bridge blocks
                for (BlockPos bp : bridgeBlocks) {
                    if (isWithinReach(bot, bp)) {
                        mineBlock(bot, bp, false);
                        forgetScaffoldPlacement(sharedState, world, bp);
                        if (reachSession != null) reachSession.recordRemoval(bp);
                    }
                }
                return true;
            }
        }

        // Bridge didn't reach safe ground — clean bridge and return false
        for (int i = bridgeBlocks.size() - 1; i >= 0; i--) {
            BlockPos bp = bridgeBlocks.get(i);
            if (isWithinReach(bot, bp)) {
                mineBlock(bot, bp, false);
                forgetScaffoldPlacement(sharedState, world, bp);
                if (reachSession != null) reachSession.recordRemoval(bp);
            }
        }
        return false;
    }
```

- [ ] **Step 2: Wire bridge-to-safety into the cleanup loop**

In the scaffold cleanup loop (line ~4065-4068), where the current code sets `cleanupIncomplete = true` after `moveNearScaffoldForCleanup` fails, replace:

```java
                if (!isWithinReach(bot, placed) && !moveNearScaffoldForCleanup(source, bot, world, placed, base, reachSession)) {
                    LOGGER.warn("Woodcut scaffold cleanup: unreachable {} from {}",
                            placed.toShortString(), bot.getBlockPos().toShortString());
                    reachSession.cleanupIncomplete = true;
                    continue;
                }
```

With:

```java
                if (!isWithinReach(bot, placed) && !moveNearScaffoldForCleanup(source, bot, world, placed, base, reachSession)) {
                    // Normal cleanup failed — try bridge-to-safety recovery
                    if (tryBridgeToSafetyForCleanup(source, bot, world, placed, sharedState, reachSession)) {
                        LOGGER.info("Woodcut scaffold cleanup: bridged to safety, cleaned {} from new position",
                                placed.toShortString());
                        if (world.getBlockState(placed).isAir()) {
                            forgetScaffoldPlacement(sharedState, world, placed);
                            reachSession.recordRemoval(placed);
                        }
                        continue;
                    }
                    // Bridge failed — abandon scaffold and retreat
                    LOGGER.warn("Woodcut scaffold cleanup: abandoning unreachable {} from {}",
                            placed.toShortString(), bot.getBlockPos().toShortString());
                    reachSession.cleanupIncomplete = true;

                    // Try to escape to safe ground
                    BlockPos escape = findEscapeStandNear(world, bot.getBlockPos(), 8);
                    if (escape != null) {
                        MovementService.MovementPlan escapePlan = new MovementService.MovementPlan(
                                MovementService.Mode.DIRECT, escape, escape, null, null, bot.getHorizontalFacing());
                        MovementService.execute(source, bot, escapePlan, false, true, true, false);
                    }
                    continue;
                }
```

Note: The `sharedState` variable must be accessible in this scope. Check the enclosing method to confirm it's a parameter — the scaffold cleanup method likely has access to `sharedState` via the skill execution context. If not, thread it through.

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Bridge-to-safety + abandon recovery for stuck scaffold cleanup"
```

---

### Task 7: Final build + changelog

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Full build verification**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog**

Add under the 2026-04-03 section:

```markdown
## 2026-04-03

- **Feat: Woodcut hazard avoidance.** New `WoodcutHazardScanner` assesses terrain around trees in 4 cardinal directions, classifying each as SAFE, SHALLOW_WATER, DEEP_WATER, or RAVINE. Trees fully enclosed by hazards are rejected. Scaffold placement skips directions rated RAVINE or DEEP_WATER, and never places over water. Bridge sweeps also respect hazard directions.

- **Feat: Scaffold cleanup recovery.** When scaffold blocks are unreachable during cleanup (e.g., over a ravine), the bot now tries to bridge to nearby safe ground first. If bridging fails (no materials, no safe ground within 6 blocks), it abandons the scaffold and escapes to safety via relaxed `findEscapeStandNear` (allows precarious positions if 2+ cardinal neighbors are standable).
```

- [ ] **Step 3: Commit**

```bash
git add changelog.md
git commit -m "docs: Add woodcut hazard avoidance changelog entries"
```
