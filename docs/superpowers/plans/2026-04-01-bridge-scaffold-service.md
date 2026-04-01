# BridgeScaffoldService Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a horizontal scaffold bridge service that extends outward from a perch, mines targets at each step, then retracts — enabling WoodcutSkill to reach sprawling branch logs laterally.

**Architecture:** Single static service class (`BridgeScaffoldService`) in `services/construction/` alongside `ScaffoldService`. Uses existing `BotActions.tryPlaceBlockAt` for placement, `MiningTool.mineBlock` for clearing/mining, `SneakLockService` for fall safety. Caller provides direction, max length, target predicate, and bridge material. No new dependencies.

**Tech Stack:** Java 21, Fabric 1.21.11, Minecraft server-thread scheduling via `callOnServerThread`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java` | Create | Horizontal bridge extend/retract/mine service |
| `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` | Modify | Call bridge service during pillar descent |
| `changelog.md` | Modify | Document new feature |

---

## Chunk 1: BridgeScaffoldService Core

### Task 1: Create BridgeScaffoldService with records, constants, and entry point

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java`

- [ ] **Step 1: Create the service file with class shell, records, constants, and public API signature**

```java
package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.SneakLockService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.PlayerUtils.LookController;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class BridgeScaffoldService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bridge-scaffold");

    private static final int MAX_BRIDGE_LENGTH = 8;
    private static final long SAFE_STEP_DELAY_MS = 200L;
    private static final long NINJA_STEP_DELAY_MS = 100L;
    private static final double NINJA_SNEAK_THRESHOLD = 0.3D;
    private static final int TARGET_SWEEP_RADIUS = 4;
    private static final int TARGET_SWEEP_PASSES = 10;
    private static final long MINING_TIMEOUT_MS = 3_000L;
    private static final long RETRACT_BREAK_DELAY_MS = 100L;
    private static final double REACH_DISTANCE_SQ = 20.25D; // 4.5 blocks

    public record BridgeResult(
        List<BlockPos> placedBlocks,
        int adoptedBlocks,
        int targetsMined,
        boolean reachedMaxLength,
        boolean retracted,
        String failureReason
    ) {}

    private BridgeScaffoldService() {}

    public static BridgeResult bridgeAndRetract(
            ServerPlayerEntity bot,
            Direction direction,
            int maxLength,
            boolean ninja,
            Predicate<BlockState> isTarget,
            BlockPos associatedBase,
            List<Item> preferredBlocks) {
        // Implementation in next tasks
        return new BridgeResult(List.of(), 0, 0, false, false, "not-implemented");
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java
git commit -m "feat: BridgeScaffoldService shell with records and constants"
```

---

### Task 2: Implement helper methods

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java`

- [ ] **Step 1: Add helper methods — isSoftObstacle, sleepQuiet, isWithinReach, countAvailableScaffold, computeSubBlockProgress**

```java
    private static boolean isSoftObstacle(BlockState state) {
        return state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW)
                || state.isReplaceable() || state.isOf(Blocks.VINE)
                || state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS);
    }

    private static boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        return botPos.squaredDistanceTo(center) <= REACH_DISTANCE_SQ;
    }

    private static int countAvailableScaffold(ServerPlayerEntity bot, List<Item> preferred) {
        int count = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            var stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && preferred.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Compute sub-block progress along the bridge direction, normalized to [0, 1).
     * 0.0 = entering the block, 1.0 = at the far edge.
     */
    private static double computeSubBlockProgress(ServerPlayerEntity bot, Direction direction) {
        double raw = bot.getPos().getComponentAlongAxis(direction.getAxis()) % 1.0;
        double progress = ((raw % 1.0) + 1.0) % 1.0;
        if (direction.getDirection() == Direction.AxisDirection.NEGATIVE) {
            progress = 1.0 - progress;
        }
        return progress;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isAbortRequested(ServerPlayerEntity bot) {
        return TaskService.isServerStopping()
                || (bot != null && TaskService.isAbortRequested(bot.getUuid()));
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java
git commit -m "feat: BridgeScaffoldService helper methods"
```

---

### Task 3: Implement obstacle clearing and target mining

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java`

- [ ] **Step 1: Add clearAhead and mineReachableTargets methods**

```java
    /**
     * Proactively clear soft blocks at the next bridge position (foot, head, jump clearance).
     * If a block matches isTarget, mine it as a target instead.
     * Returns number of target blocks mined.
     */
    private static int clearAhead(ServerPlayerEntity bot, ServerWorld world,
                                   BlockPos nextFoot, Predicate<BlockState> isTarget,
                                   BlockPos associatedBase) {
        int targetsMined = 0;
        for (BlockPos pos : new BlockPos[]{nextFoot, nextFoot.up(), nextFoot.up(2)}) {
            if (isAbortRequested(bot)) break;
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (isTarget != null && isTarget.test(state)) {
                // Check protection before mining target
                if (associatedBase != null) {
                    var prot = net.wcfcarolina13.GameAI.skills.support.TreeDetector
                            .getWoodcutProtectionDecision(world, pos, 4);
                    if (prot.blocked()) continue;
                }
                LookController.faceBlock(bot, pos);
                try {
                    String result = MiningTool.mineBlock(bot, pos).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (result != null && result.toLowerCase().contains("complete")) {
                        targetsMined++;
                    }
                } catch (Exception ignored) {}
                continue;
            }
            if (isSoftObstacle(state)) {
                LookController.faceBlock(bot, pos);
                try {
                    MiningTool.mineBlock(bot, pos).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }
        }
        return targetsMined;
    }

    /**
     * Sweep a cube around the bot for target blocks, mine all reachable ones.
     * Clears obstructing leaves via raycast before each mine attempt.
     */
    private static int mineReachableTargets(ServerPlayerEntity bot, ServerWorld world,
                                             Predicate<BlockState> isTarget,
                                             BlockPos associatedBase) {
        if (isTarget == null) return 0;
        int mined = 0;
        for (int pass = 0; pass < TARGET_SWEEP_PASSES; pass++) {
            if (isAbortRequested(bot)) break;
            BlockPos botPos = bot.getBlockPos();
            BlockPos found = null;
            for (BlockPos check : BlockPos.iterate(
                    botPos.add(-TARGET_SWEEP_RADIUS, -2, -TARGET_SWEEP_RADIUS),
                    botPos.add(TARGET_SWEEP_RADIUS, TARGET_SWEEP_RADIUS, TARGET_SWEEP_RADIUS))) {
                BlockState state = world.getBlockState(check);
                if (!isTarget.test(state)) continue;
                if (!isWithinReach(bot, check)) continue;
                if (associatedBase != null) {
                    var prot = net.wcfcarolina13.GameAI.skills.support.TreeDetector
                            .getWoodcutProtectionDecision(world, check, 4);
                    if (prot.blocked()) continue;
                }
                found = check.toImmutable();
                break;
            }
            if (found == null) break;
            // Clear obstructing leaves along raycast
            clearLeafObstructions(bot, world, found);
            LookController.faceBlock(bot, found);
            try {
                String result = MiningTool.mineBlock(bot, found).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (result != null && result.toLowerCase().contains("complete")) {
                    mined++;
                }
            } catch (Exception ignored) {}
        }
        return mined;
    }

    /** Break up to 3 leaf blocks along the line of sight to the target. */
    private static void clearLeafObstructions(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        for (int i = 0; i < 3; i++) {
            if (hasLineOfSight(bot, Vec3d.ofCenter(target))) return;
            net.minecraft.util.hit.BlockHitResult hit = world.raycast(
                    new net.minecraft.world.RaycastContext(
                            bot.getEyePos(), Vec3d.ofCenter(target),
                            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                            net.minecraft.world.RaycastContext.FluidHandling.NONE, bot));
            if (hit == null || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) return;
            BlockPos hitPos = hit.getBlockPos();
            if (hitPos.equals(target)) return;
            BlockState hitState = world.getBlockState(hitPos);
            if (hitState.isIn(BlockTags.LEAVES) || hitState.isOf(Blocks.SNOW) || hitState.isReplaceable()) {
                LookController.faceBlock(bot, hitPos);
                try {
                    MiningTool.mineBlock(bot, hitPos, true, false).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            } else {
                return; // solid non-leaf block — can't clear
            }
        }
    }

    private static boolean hasLineOfSight(ServerPlayerEntity bot, Vec3d target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        net.minecraft.util.hit.BlockHitResult hit = world.raycast(
                new net.minecraft.world.RaycastContext(
                        bot.getEyePos(), target,
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE, bot));
        return hit == null || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK
                || hit.getBlockPos().getSquaredDistance(target) < 1.5;
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java
git commit -m "feat: BridgeScaffoldService clearing and target mining methods"
```

---

### Task 4: Implement the extend phase

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java`

- [ ] **Step 1: Add the extendBridge method**

```java
    /**
     * Extend a bridge outward one block at a time.
     * Returns: [targetsMined, adoptedBlocks, reachedMaxLength, fellOff]
     */
    private static int[] extendBridge(ServerPlayerEntity bot, ServerWorld world,
                                       Direction direction, int effectiveMaxLength,
                                       boolean ninja, Predicate<BlockState> isTarget,
                                       BlockPos associatedBase, List<Item> preferredBlocks,
                                       List<BlockPos> placedBlocks, int bridgeY) {
        int targetsMined = 0;
        int adoptedBlocks = 0;
        boolean reachedMax = false;

        for (int step = 0; step < effectiveMaxLength; step++) {
            if (isAbortRequested(bot)) break;

            BlockPos currentPos = bot.getBlockPos();
            BlockPos nextFoot = currentPos.offset(direction);
            BlockPos nextFloor = nextFoot.down();

            // 1. Proactive clearing — mine obstacles and targets ahead
            targetsMined += clearAhead(bot, world, nextFoot, isTarget, associatedBase);

            // 2. Check if existing solid floor can be adopted
            BlockState floorState = world.getBlockState(nextFloor);
            boolean hasFloor = !floorState.getCollisionShape(world, nextFloor).isEmpty();
            boolean floorIsTarget = hasFloor && isTarget != null && isTarget.test(floorState);

            // Mine target floors before adopting
            if (floorIsTarget) {
                LookController.faceBlock(bot, nextFloor);
                try {
                    MiningTool.mineBlock(bot, nextFloor).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    targetsMined++;
                } catch (Exception ignored) {}
                hasFloor = !world.getBlockState(nextFloor).getCollisionShape(world, nextFloor).isEmpty();
            }

            boolean nextFootPassable = world.getBlockState(nextFoot).getCollisionShape(world, nextFoot).isEmpty()
                    && world.getBlockState(nextFoot.up()).getCollisionShape(world, nextFoot.up()).isEmpty();

            if (hasFloor && nextFootPassable) {
                // Adopt existing floor
                adoptedBlocks++;
                LOGGER.info("bridge extend step {}: adopting existing floor at {}", step + 1, nextFloor.toShortString());
            } else if (!hasFloor) {
                // 3. Place scaffold block
                LookController.faceBlock(bot, nextFloor);
                BotActions.PlaceResult place = BotActions.tryPlaceBlockAt(
                        bot, nextFloor, direction, preferredBlocks, false);
                if (!place.success()) {
                    // Try without face hint
                    place = BotActions.tryPlaceBlockAt(bot, nextFloor, Direction.UP, preferredBlocks, false);
                }
                if (!place.success()) {
                    LOGGER.info("bridge extend step {}: placement failed at {} reason={}",
                            step + 1, nextFloor.toShortString(), place.reason());
                    break;
                }
                placedBlocks.add(nextFloor.toImmutable());
                LOGGER.info("bridge extend step {}: placed scaffold at {}", step + 1, nextFloor.toShortString());
            } else {
                // Floor exists but foot/head not passable — can't proceed
                LOGGER.info("bridge extend step {}: blocked at {}", step + 1, nextFoot.toShortString());
                break;
            }

            // 4. Step onto the new block
            boolean stepped = stepOntoBlock(bot, nextFoot, direction, ninja);
            if (!stepped) {
                LOGGER.info("bridge extend step {}: failed to step onto {}", step + 1, nextFoot.toShortString());
                break;
            }

            // 5. Fall check
            if (bot.getBlockPos().getY() < bridgeY) {
                LOGGER.warn("bridge: bot fell from Y={} to Y={}", bridgeY, bot.getBlockPos().getY());
                return new int[]{targetsMined, adoptedBlocks, 0, 1}; // fellOff = 1
            }

            // 6. Mine reachable targets from this position
            targetsMined += mineReachableTargets(bot, world, isTarget, associatedBase);

            if (step == effectiveMaxLength - 1) {
                reachedMax = true;
            }
        }
        return new int[]{targetsMined, adoptedBlocks, reachedMax ? 1 : 0, 0};
    }

    /** Move the bot one block in the bridge direction. */
    private static boolean stepOntoBlock(ServerPlayerEntity bot, BlockPos target,
                                          Direction direction, boolean ninja) {
        Vec3d dest = Vec3d.ofCenter(target);
        long stepDelay = ninja ? NINJA_STEP_DELAY_MS : SAFE_STEP_DELAY_MS;

        if (!ninja) {
            // Safe mode: full sneak the whole way
            bot.setSneaking(true);
            for (int tick = 0; tick < 20; tick++) {
                BotActions.applyMovementInput(bot, dest, 0.08);
                sleepQuiet(50L);
                if (bot.getBlockPos().equals(target)) {
                    BotActions.stop(bot); // sneak lock prevents unsneak
                    return true;
                }
            }
        } else {
            // Ninja mode: sneak only near edges
            for (int tick = 0; tick < 30; tick++) {
                double progress = computeSubBlockProgress(bot, direction);
                boolean nearEdge = progress < NINJA_SNEAK_THRESHOLD || progress > (1.0 - NINJA_SNEAK_THRESHOLD);
                bot.setSneaking(nearEdge);
                BotActions.applyMovementInput(bot, dest, nearEdge ? 0.06 : 0.10);
                sleepQuiet(40L);
                if (bot.getBlockPos().equals(target)) {
                    bot.setSneaking(true); // re-sneak at destination
                    BotActions.stop(bot);
                    return true;
                }
            }
            bot.setSneaking(true); // safety re-sneak
        }
        // Fallback: check if close enough
        return bot.getBlockPos().equals(target)
                || bot.getBlockPos().getSquaredDistance(target) <= 2.25;
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java
git commit -m "feat: BridgeScaffoldService extend phase with step and placement logic"
```

---

### Task 5: Implement retract phase and wire up bridgeAndRetract

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java`

- [ ] **Step 1: Add retractBridge method**

```java
    /**
     * Walk back toward perch, breaking placed scaffold blocks behind.
     * Only breaks blocks in placedBlocks list (not adopted existing blocks).
     */
    private static boolean retractBridge(ServerPlayerEntity bot, ServerWorld world,
                                          Direction direction, List<BlockPos> placedBlocks,
                                          BlockPos perchPos) {
        Direction returnDir = direction.getOpposite();
        bot.setSneaking(true);

        // Walk back, breaking placed blocks behind us
        // placedBlocks is in extend order (perch-outward); iterate reverse for retract
        for (int i = placedBlocks.size() - 1; i >= 0; i--) {
            if (TaskService.isServerStopping()) break; // hard abort only for retract

            BlockPos scaffold = placedBlocks.get(i);
            // Step toward perch (one block in return direction)
            BlockPos stepTarget = bot.getBlockPos().offset(returnDir);
            Vec3d dest = Vec3d.ofCenter(stepTarget);
            for (int tick = 0; tick < 20; tick++) {
                BotActions.applyMovementInput(bot, dest, 0.08);
                sleepQuiet(50L);
                if (bot.getBlockPos().equals(stepTarget) || bot.getBlockPos().getSquaredDistance(stepTarget) <= 1.5) {
                    break;
                }
            }
            BotActions.stop(bot); // sneak lock keeps us sneaking

            // Verify we can reach the scaffold behind us before breaking
            if (!BlockInteractionService.canInteract(bot, scaffold)) {
                LOGGER.info("bridge retract: can't reach scaffold at {} from {}, skipping",
                        scaffold.toShortString(), bot.getBlockPos().toShortString());
                continue;
            }

            // Break the scaffold
            if (!world.getBlockState(scaffold).isAir()) {
                LookController.faceBlock(bot, scaffold);
                try {
                    MiningTool.mineBlock(bot, scaffold, true, false)
                            .get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }
            sleepQuiet(RETRACT_BREAK_DELAY_MS);
        }

        // Verify we returned to perch
        boolean atPerch = bot.getBlockPos().equals(perchPos)
                || bot.getBlockPos().getSquaredDistance(perchPos) <= 4.0;
        if (!atPerch) {
            LOGGER.info("bridge retract: not at perch, nudging from {} toward {}",
                    bot.getBlockPos().toShortString(), perchPos.toShortString());
            net.wcfcarolina13.GameAI.services.MovementService.nudgeTowardUntilClose(
                    bot, perchPos, 2.25, 2_000L, 0.15, "bridge-retract-perch");
        }
        return bot.getBlockPos().equals(perchPos)
                || bot.getBlockPos().getSquaredDistance(perchPos) <= 4.0;
    }
```

- [ ] **Step 2: Replace the stub bridgeAndRetract with the full implementation**

```java
    public static BridgeResult bridgeAndRetract(
            ServerPlayerEntity bot,
            Direction direction,
            int maxLength,
            boolean ninja,
            Predicate<BlockState> isTarget,
            BlockPos associatedBase,
            List<Item> preferredBlocks) {
        if (bot == null || direction == null || !direction.getAxis().isHorizontal()) {
            return new BridgeResult(List.of(), 0, 0, false, false, "invalid-args");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new BridgeResult(List.of(), 0, 0, false, false, "no-world");
        }
        if (preferredBlocks == null || preferredBlocks.isEmpty()) {
            preferredBlocks = ScaffoldService.SCAFFOLD_BLOCKS;
        }

        // Pre-flight: clamp to available materials and hard cap
        int available = countAvailableScaffold(bot, preferredBlocks);
        int effectiveMax = Math.min(Math.min(maxLength, MAX_BRIDGE_LENGTH), available);
        if (effectiveMax <= 0) {
            return new BridgeResult(List.of(), 0, 0, false, false, "no-scaffold-material");
        }

        BlockPos perchPos = bot.getBlockPos().toImmutable();
        int bridgeY = perchPos.getY();
        List<BlockPos> placedBlocks = new ArrayList<>();

        LOGGER.info("bridge start: perch={} direction={} maxLength={} effective={} ninja={} available={}",
                perchPos.toShortString(), direction.getName(), maxLength, effectiveMax, ninja, available);

        // Acquire sneak lock — prevents BotActions.stop() from unsneaking mid-bridge
        SneakLockService.acquire(bot.getUuid());
        bot.setSneaking(true);
        try {
            // Phase 1: Extend
            int[] extendResult = extendBridge(bot, world, direction, effectiveMax,
                    ninja, isTarget, associatedBase, preferredBlocks, placedBlocks, bridgeY);
            int targetsMined = extendResult[0];
            int adoptedBlocks = extendResult[1];
            boolean reachedMax = extendResult[2] == 1;
            boolean fellOff = extendResult[3] == 1;

            if (fellOff) {
                LOGGER.warn("bridge: bot fell off, skipping retraction. {} placed blocks left.",
                        placedBlocks.size());
                return new BridgeResult(placedBlocks, adoptedBlocks, targetsMined,
                        reachedMax, false, "fell-off-bridge");
            }

            // Phase 2: Retract
            boolean retracted = retractBridge(bot, world, direction, placedBlocks, perchPos);

            LOGGER.info("bridge complete: placed={} adopted={} mined={} retracted={}",
                    placedBlocks.size(), adoptedBlocks, targetsMined, retracted);

            return new BridgeResult(placedBlocks, adoptedBlocks, targetsMined,
                    reachedMax, retracted, null);
        } finally {
            SneakLockService.release(bot.getUuid());
            bot.setSneaking(false);
        }
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java
git commit -m "feat: BridgeScaffoldService retract phase and full bridgeAndRetract wiring"
```

---

## Chunk 2: WoodcutSkill Integration + Deploy

### Task 6: Add bridge calls to WoodcutSkill descent phase

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java` (descent loop in `mineFromScaffoldColumn`, ~line 1476)

- [ ] **Step 1: Add bridge sweep after each scaffold descent step**

In the descent loop, after the existing elevated sweep (the `sweepPass` loop added earlier), add a directional bridge probe:

```java
// Bridge sweep: try bridging in each cardinal direction to reach distant targets
if (!isAbortRequested(bot)) {
    for (Direction bridgeDir : Direction.Type.HORIZONTAL) {
        if (isAbortRequested(bot)) break;
        // Quick check: any target logs in this direction within bridge range?
        boolean hasTargetsInDir = false;
        for (int d = 2; d <= 6; d++) {
            BlockPos probe = bot.getBlockPos().offset(bridgeDir, d);
            for (int dy = -2; dy <= 4; dy++) {
                if (world.getBlockState(probe.up(dy)).isIn(BlockTags.LOGS)) {
                    hasTargetsInDir = true;
                    break;
                }
            }
            if (hasTargetsInDir) break;
        }
        if (!hasTargetsInDir) continue;

        BridgeScaffoldService.BridgeResult bridgeResult =
                BridgeScaffoldService.bridgeAndRetract(
                        bot, bridgeDir, 6, false,
                        state -> state.isIn(BlockTags.LOGS),
                        target.base(),
                        PILLAR_BLOCKS);
        if (bridgeResult.targetsMined() > 0) {
            mined += bridgeResult.targetsMined();
            LOGGER.info("Woodcut bridge sweep: dir={} mined={} placed={} adopted={}",
                    bridgeDir.getName(),
                    bridgeResult.targetsMined(),
                    bridgeResult.placedBlocks().size(),
                    bridgeResult.adoptedBlocks());
        }
    }
}
```

This goes inside the descent `for (BlockPos scaffold : currentPlacements)` loop, after the elevated sweep block and before the closing `}` of the `if (mineAdaptiveBlock...)` block.

- [ ] **Step 2: Add import for BridgeScaffoldService at the top of WoodcutSkill.java**

Add alongside the existing ScaffoldService import:

```java
import net.wcfcarolina13.GameAI.services.construction.BridgeScaffoldService;
```

- [ ] **Step 3: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoodcutSkill.java
git commit -m "feat: Integrate BridgeScaffoldService into WoodcutSkill descent phase"
```

---

### Task 7: Changelog, build, deploy

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Add changelog entry**

Add at top of `## 2026-04-01` section:

```markdown
- **Feat: Horizontal bridge scaffold service.** New `BridgeScaffoldService` builds temporary 1-block-wide horizontal bridges outward from a perch, mines targets at each step, then retracts. Supports safe mode (full sneak) and ninja bridging (sneak-toggle at block edges for ~2x speed). Integrated into WoodcutSkill descent phase — at each pillar level, the bot probes all 4 cardinal directions for reachable logs and bridges out up to 6 blocks to harvest them. Handles: sneak-lock safety, fall detection, proactive leaf clearing, target-at-floor mining, pre-flight material check, and clean retraction with scaffold teardown.
```

- [ ] **Step 2: Build final JAR**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Deploy to all instances**

```bash
cp build/libs/frens-1.1.0-release+1.21.11.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/mods/"
cp build/libs/frens-1.1.0-release+1.21.11.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/mods/"
cp build/libs/frens-1.1.0-release+1.21.11.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10 TEST/minecraft/mods/"
```

- [ ] **Step 4: Commit changelog**

```bash
git add changelog.md
git commit -m "docs: Add changelog entry for BridgeScaffoldService"
```
