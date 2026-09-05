package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SneakLockService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.services.LeafClearService;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Builds temporary horizontal scaffold bridges outward from a perch position,
 * mines reachable targets at each step, then retracts back to the perch.
 * Complement to {@link ScaffoldService} which handles vertical pillaring.
 */
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

    /**
     * Extend a bridge from the bot's current position in the given cardinal
     * direction, mining reachable targets at each step, then retract back
     * to the starting position.
     */
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

        int available = countAvailableScaffold(bot, preferredBlocks);
        int effectiveMax = Math.min(Math.min(maxLength, MAX_BRIDGE_LENGTH), available);
        if (effectiveMax <= 0) {
            return new BridgeResult(List.of(), 0, 0, false, false, "no-scaffold-material");
        }

        BlockPos perchPos = bot.getBlockPos().toImmutable();
        int bridgeY = perchPos.getY();
        List<BlockPos> placedBlocks = new ArrayList<>();

        LOGGER.info("bridge start: perch={} direction={} maxLength={} effective={} ninja={} available={}",
                perchPos.toShortString(), direction.asString(), maxLength, effectiveMax, ninja, available);

        SneakLockService.acquire(bot.getUuid());
        bot.setSneaking(true);
        try {
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

    // ── Extend phase ──────────────────────────────────────────────────────

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

            // Proactive clearing — mine obstacles and targets ahead
            targetsMined += clearAhead(bot, world, nextFoot, isTarget, associatedBase);

            // Check if existing solid floor can be adopted
            BlockState floorState = world.getBlockState(nextFloor);
            boolean hasFloor = !floorState.getCollisionShape(world, nextFloor).isEmpty();
            boolean floorIsTarget = hasFloor && isTarget != null && isTarget.test(floorState);

            // Mine target floors before adopting
            if (floorIsTarget) {
                LookController.faceBlock(bot, nextFloor);
                try {
                    String result = MiningTool.mineBlock(bot, nextFloor).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (result != null && result.toLowerCase().contains("complete")) {
                        targetsMined++;
                    }
                } catch (Exception ignored) {}
                hasFloor = !world.getBlockState(nextFloor).getCollisionShape(world, nextFloor).isEmpty();
            }

            boolean nextFootPassable = world.getBlockState(nextFoot).getCollisionShape(world, nextFoot).isEmpty()
                    && world.getBlockState(nextFoot.up()).getCollisionShape(world, nextFoot.up()).isEmpty();

            if (hasFloor && nextFootPassable) {
                adoptedBlocks++;
                LOGGER.info("bridge extend step {}: adopting existing floor at {}", step + 1, nextFloor.toShortString());
            } else if (!hasFloor) {
                LookController.faceBlock(bot, nextFloor);
                BotActions.PlaceResult place = BotActions.tryPlaceBlockAt(
                        bot, nextFloor, direction, preferredBlocks, false);
                if (!place.success()) {
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
                LOGGER.info("bridge extend step {}: blocked at {}", step + 1, nextFoot.toShortString());
                break;
            }

            boolean stepped = stepOntoBlock(bot, nextFoot, direction, ninja);
            if (!stepped) {
                LOGGER.info("bridge extend step {}: failed to step onto {}", step + 1, nextFoot.toShortString());
                break;
            }

            // Fall check
            if (bot.getBlockPos().getY() < bridgeY) {
                LOGGER.warn("bridge: bot fell from Y={} to Y={}", bridgeY, bot.getBlockPos().getY());
                return new int[]{targetsMined, adoptedBlocks, 0, 1};
            }

            targetsMined += mineReachableTargets(bot, world, isTarget, associatedBase);

            if (step == effectiveMaxLength - 1) {
                reachedMax = true;
            }
        }
        return new int[]{targetsMined, adoptedBlocks, reachedMax ? 1 : 0, 0};
    }

    private static boolean stepOntoBlock(ServerPlayerEntity bot, BlockPos target,
                                          Direction direction, boolean ninja) {
        Vec3d dest = Vec3d.ofCenter(target);

        if (!ninja) {
            bot.setSneaking(true);
            for (int tick = 0; tick < 20; tick++) {
                BotActions.applyMovementInput(bot, dest, 0.08);
                sleepQuiet(50L);
                if (bot.getBlockPos().equals(target)) {
                    BotActions.stop(bot);
                    return true;
                }
            }
        } else {
            for (int tick = 0; tick < 30; tick++) {
                double progress = computeSubBlockProgress(bot, direction);
                boolean nearEdge = progress < NINJA_SNEAK_THRESHOLD || progress > (1.0 - NINJA_SNEAK_THRESHOLD);
                bot.setSneaking(nearEdge);
                BotActions.applyMovementInput(bot, dest, nearEdge ? 0.06 : 0.10);
                sleepQuiet(40L);
                if (bot.getBlockPos().equals(target)) {
                    bot.setSneaking(true);
                    BotActions.stop(bot);
                    return true;
                }
            }
            bot.setSneaking(true);
        }
        return bot.getBlockPos().equals(target)
                || bot.getBlockPos().getSquaredDistance(target) <= 2.25;
    }

    // ── Retract phase ─────────────────────────────────────────────────────

    private static boolean retractBridge(ServerPlayerEntity bot, ServerWorld world,
                                          Direction direction, List<BlockPos> placedBlocks,
                                          BlockPos perchPos) {
        Direction returnDir = direction.getOpposite();
        bot.setSneaking(true);

        // Retract from far end back toward perch. The bot walks backward one
        // block at a time toward the perch, mining the scaffold block it just
        // stepped OFF (behind it). This preserves the floor ahead of the bot.
        //
        // placedBlocks is ordered nearest-to-perch (index 0) → farthest (last).
        // We iterate farthest→nearest so the bot retreats toward the perch.
        for (int i = placedBlocks.size() - 1; i >= 0; i--) {
            if (TaskService.isServerStopping()) break;

            BlockPos scaffold = placedBlocks.get(i);

            // If we're standing on/near this scaffold, step toward perch first
            // so we don't mine the block under our own feet
            if (bot.getBlockPos().getSquaredDistance(scaffold.up()) <= 2.0
                    || bot.getBlockPos().equals(scaffold.up())) {
                BlockPos stepTarget = bot.getBlockPos().offset(returnDir);
                Vec3d dest = Vec3d.ofCenter(stepTarget);
                for (int tick = 0; tick < 20; tick++) {
                    BotActions.applyMovementInput(bot, dest, 0.08);
                    sleepQuiet(50L);
                    if (bot.getBlockPos().equals(stepTarget)
                            || bot.getBlockPos().getSquaredDistance(stepTarget) <= 1.5) {
                        break;
                    }
                }
                BotActions.stop(bot);
            }

            // Now mine the scaffold block (should be behind/below us)
            if (!BlockInteractionService.canInteract(bot, scaffold)) {
                LOGGER.info("bridge retract: can't reach scaffold at {} from {}, skipping",
                        scaffold.toShortString(), bot.getBlockPos().toShortString());
                continue;
            }

            if (!world.getBlockState(scaffold).isAir()) {
                LookController.faceBlock(bot, scaffold);
                try {
                    MiningTool.mineBlock(bot, scaffold, true, false)
                            .get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {}
            }
            sleepQuiet(RETRACT_BREAK_DELAY_MS);
        }

        boolean atPerch = bot.getBlockPos().equals(perchPos)
                || bot.getBlockPos().getSquaredDistance(perchPos) <= 4.0;
        if (!atPerch) {
            LOGGER.info("bridge retract: not at perch, nudging from {} toward {}",
                    bot.getBlockPos().toShortString(), perchPos.toShortString());
            MovementService.nudgeTowardUntilClose(
                    bot, perchPos, 2.25, 2_000L, 0.15, "bridge-retract-perch");
        }
        return bot.getBlockPos().equals(perchPos)
                || bot.getBlockPos().getSquaredDistance(perchPos) <= 4.0;
    }

    // ── Obstacle clearing & target mining ─────────────────────────────────

    private static int clearAhead(ServerPlayerEntity bot, ServerWorld world,
                                   BlockPos nextFoot, Predicate<BlockState> isTarget,
                                   BlockPos associatedBase) {
        int targetsMined = 0;
        for (BlockPos pos : new BlockPos[]{nextFoot, nextFoot.up(), nextFoot.up(2)}) {
            if (isAbortRequested(bot)) break;
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (isTarget != null && isTarget.test(state)) {
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

    private static int mineReachableTargets(ServerPlayerEntity bot, ServerWorld world,
                                             Predicate<BlockState> isTarget,
                                             BlockPos associatedBase) {
        if (isTarget == null) return 0;
        int mined = 0;
        java.util.Set<Long> protectedSkips = new java.util.HashSet<>();
        for (int pass = 0; pass < TARGET_SWEEP_PASSES; pass++) {
            if (isAbortRequested(bot)) break;
            BlockPos botPos = bot.getBlockPos();
            BlockPos found = null;
            for (BlockPos check : BlockPos.iterate(
                    botPos.add(-TARGET_SWEEP_RADIUS, -2, -TARGET_SWEEP_RADIUS),
                    botPos.add(TARGET_SWEEP_RADIUS, TARGET_SWEEP_RADIUS, TARGET_SWEEP_RADIUS))) {
                if (protectedSkips.contains(check.asLong())) continue;
                BlockState state = world.getBlockState(check);
                if (!isTarget.test(state)) continue;
                if (!isWithinReach(bot, check)) continue;
                if (associatedBase != null) {
                    var prot = net.wcfcarolina13.GameAI.skills.support.TreeDetector
                            .getWoodcutProtectionDecision(world, check, 4);
                    if (prot.blocked()) {
                        protectedSkips.add(check.asLong());
                        continue;
                    }
                }
                found = check.toImmutable();
                break;
            }
            if (found == null) break;
            clearLeafObstructions(bot, world, found);
            LookController.faceBlock(bot, found);
            try {
                String result = MiningTool.mineBlock(bot, found).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (result != null && result.toLowerCase().contains("complete")) {
                    mined++;
                } else if (!world.getBlockState(found).isAir()) {
                    protectedSkips.add(found.asLong());
                }
            } catch (Exception ignored) {}
        }
        return mined;
    }

    private static void clearLeafObstructions(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        LeafClearService.clearLineOfSight(
                bot,
                target,
                new LeafClearService.Options(3, 3, false, null, true,
                        LeafClearService.CandidateMode.RAYCAST_HIT_PLUS_NEIGHBOURS),
                (b, pos) -> {
                    LookController.faceBlock(b, pos);
                    try {
                        MiningTool.mineBlock(b, pos, true, false).get(MINING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {}
                });
    }

    // ── Utility methods ───────────────────────────────────────────────────

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

    private static boolean hasLineOfSight(ServerPlayerEntity bot, Vec3d target) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        BlockHitResult hit = world.raycast(new RaycastContext(
                bot.getEyePos(), target,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, bot));
        return hit == null || hit.getType() != HitResult.Type.BLOCK
                || hit.getBlockPos().getSquaredDistance(target) < 1.5;
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

    private static double computeSubBlockProgress(ServerPlayerEntity bot, Direction direction) {
        double raw = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).getComponentAlongAxis(direction.getAxis()) % 1.0;
        double progress = ((raw % 1.0) + 1.0) % 1.0;
        if (direction.getDirection() == Direction.AxisDirection.NEGATIVE) {
            progress = 1.0 - progress;
        }
        return progress;
    }

    private static boolean isAbortRequested(ServerPlayerEntity bot) {
        return TaskService.isServerStopping()
                || (bot != null && TaskService.isAbortRequested(bot.getUuid()));
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
