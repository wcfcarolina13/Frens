package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.RaycastContext;
import net.minecraft.registry.tag.FluidTags;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.ChestStoreService;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.FollowPathService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.MovementService.Mode;
import net.shasankp000.GameAI.services.MovementService.MovementPlan;
import net.shasankp000.GameAI.services.MovementService.MovementResult;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FishingSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fishing");
    private static final int MAX_ATTEMPTS_PER_FISH = 6;
    private static final double APPROACH_REACH_SQ = 1.44D;
    private static final int WATER_SEARCH_RADIUS = 12;
    private static final int MIN_CAST_DISTANCE_SQ = 16;   // >= 4 blocks away
    private static final int MAX_CAST_DISTANCE_SQ = 100; // <= 10 blocks away
    private static final int IDEAL_CAST_DISTANCE_SQ = 36; // ~6 blocks away
    private static final double BOBBER_SEARCH_RADIUS = 24.0D;
    private static final long CAST_WAIT_MS = 6_000L;
    private static final long SWEEP_INTERVAL_MS = 3 * 60 * 1000L; // 3 minutes

    // Reactive sweep: if loot is piling up (usually because we're standing a touch too far from shore),
    // pause and do a quick local pickup run.
    private static final int DROP_SWEEP_TRIGGER_COUNT = 3; // "more than 2"
    private static final double DROP_SWEEP_TRIGGER_RADIUS = 6.5D;
    private static final long DROP_SWEEP_TRIGGER_COOLDOWN_MS = 12_000L;
    
    private static final Set<Item> FISH_ITEMS = Set.of(
            Items.COD,
            Items.SALMON,
            Items.TROPICAL_FISH,
            Items.PUFFERFISH
    );
    private static final record FishingSpot(BlockPos water, BlockPos stand, BlockPos castTarget, List<BlockPos> standOptions) {}
    private static final int CHEST_SCAN_RADIUS = 16;
    private static final record StandCandidate(BlockPos pos, boolean adjacent) {}
    private static final record StandOption(BlockPos stand, BlockPos castTarget, double score) {}

    // Failed target blacklist: prevent oscillation by remembering unreachable targets.
    private static final long BLACKLIST_DURATION_MS = 45_000L; // Forget after 45 seconds
    private static final int MAX_BLACKLIST_SIZE = 50;
    private static final Map<UUID, Map<BlockPos, Long>> FAILED_TARGET_BLACKLIST = new ConcurrentHashMap<>();

    private static final Field CAUGHT_FISH_FIELD = findCaughtFishField();

    @Override
    public String name() {
        return "fish";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("Bot not available.");
        }

        if (!ensureFishingRod(source, bot)) {
            return SkillExecutionResult.failure("Need a fishing rod (3 sticks + 2 strings) before fishing.");
        }

        if (isInventoryFull(bot)) {
            LOGGER.info("Inventory full at start of fishing. Attempting to store items before selecting a spot.");
            Optional<BlockPos> storedChest = handleFullInventory(bot, source, bot.getBlockPos());
            if (storedChest.isEmpty()) {
                return SkillExecutionResult.failure("Inventory full and couldn't store items.");
            }
            if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
                return SkillExecutionResult.failure("Lost fishing rod during storage routine.");
            }
        }

        long spotStart = System.nanoTime();
        FishingSpot spot = findFishingSpot(bot, WATER_SEARCH_RADIUS);
        long spotElapsedMs = (System.nanoTime() - spotStart) / 1_000_000L;
        if (spot == null) {
            if (hasNearbyWater(bot, WATER_SEARCH_RADIUS)) {
                return SkillExecutionResult.failure("No safe shoreline block to stand on.");
            }
            return SkillExecutionResult.failure("I need to be standing near open water to fish.");
        }

        BlockPos stand = spot.stand();
        // Use smarter navigation that can clear leaves
        boolean approached = navigateToSpot(source, bot, stand);
        if (!approached) {
            for (BlockPos alt : spot.standOptions()) {
                if (alt.equals(stand)) {
                    continue;
                }
                if (navigateToSpot(source, bot, alt)) {
                    stand = alt;
                    approached = true;
                    LOGGER.info("Switched to alternative fishing stand at {}", stand.toShortString());
                    break;
                }
            }
        }
        if (!approached) {
            return SkillExecutionResult.failure("Can't reach the fishing spot (blocked?).");
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockPos castTarget = chooseCastTarget(world, bot, stand, spot.water(), spot.castTarget());
        LOGGER.info("Fishing spot chosen stand={} water={} cast={} options={}",
                stand.toShortString(),
                spot.water().toShortString(),
                castTarget != null ? castTarget.toShortString() : "none",
                spot.standOptions().size());
        if (spotElapsedMs > 250L) {
            LOGGER.info("Fishing spot search took {}ms", spotElapsedMs);
        }
        
        // Initial positioning adjustment
        adjustPositionToWaterEdge(bot, spot.water());

        if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
            return SkillExecutionResult.failure("Unable to equip the fishing rod.");
        }

        int targetFish = getIntParameter(context.parameters(), "count", -1);
        boolean explicitSunset = isUntilSunset(context.parameters());
        boolean checkSunset = explicitSunset || (targetFish == -1);
        
        if (targetFish == -1) {
            targetFish = Integer.MAX_VALUE;
        }

        int maxAttempts = targetFish == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(targetFish * MAX_ATTEMPTS_PER_FISH, MAX_ATTEMPTS_PER_FISH);
        int caught = 0;
        int attempts = 0;
        int baseline = countFish(bot);
        long lastSweepTime = System.currentTimeMillis();
        long lastReactiveSweepTime = 0L;

        String modeDesc = (targetFish == Integer.MAX_VALUE ? "until sunset" : targetFish + " catches") + (checkSunset && targetFish != Integer.MAX_VALUE ? " (or sunset)" : "");
        LOGGER.info("Starting fishing session for {} (mode: {})", bot.getName().getString(), modeDesc);

        while (caught < targetFish && attempts < maxAttempts) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Fishing paused by another task.");
            }

            // Anchor: if we drift from our chosen stand (e.g., after loot shimmy or nudges), return.
            // This prevents "follow-the-commander"-looking creep during long sessions.
            if (bot.getBlockPos().getSquaredDistance(stand) > 2.25D) {
                if (!navigateToSpot(source, bot, stand)) {
                    return SkillExecutionResult.failure("Can't maintain position at the fishing spot.");
                }
                adjustPositionToWaterEdge(bot, spot.water());
            }

            // Periodic Sweep
            if (System.currentTimeMillis() - lastSweepTime > SWEEP_INTERVAL_MS) {
                performSweep(source, bot, stand);
                lastSweepTime = System.currentTimeMillis();
                // Re-equip after sweep
                if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
                    return SkillExecutionResult.failure("Lost fishing rod during sweep.");
                }
                adjustPositionToWaterEdge(bot, spot.water());
            }

            // Reactive Sweep: if multiple drops are accumulating nearby, stop casting and go pick them up now.
            // This is intentionally more aggressive than the periodic sweep.
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastReactiveSweepTime > DROP_SWEEP_TRIGGER_COOLDOWN_MS) {
                int nearbyDrops = countNearbyDropsForFishing(world, stand, spot.water(), DROP_SWEEP_TRIGGER_RADIUS);
                if (nearbyDrops >= DROP_SWEEP_TRIGGER_COUNT) {
                    LOGGER.info("Detected {} nearby drops during fishing; performing quick sweep.", nearbyDrops);
                    retractBobberIfPresent(bot);
                    performReactiveSweep(source, bot, stand);
                    lastReactiveSweepTime = System.currentTimeMillis();
                    if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
                        return SkillExecutionResult.failure("Lost fishing rod during reactive sweep.");
                    }
                    adjustPositionToWaterEdge(bot, spot.water());
                    continue;
                }
            }

            if (checkSunset) {
                long timeOfDay = world.getTimeOfDay() % 24000;
                if (timeOfDay >= 13000 && timeOfDay < 23000) {
                    ChatUtils.sendSystemMessage(source, "Sun has set. Stopping fishing.");
                    break;
                }
            }

            if (isInventoryFull(bot)) {
                LOGGER.info("Inventory full. Attempting to store items.");
                Optional<BlockPos> storedChest = handleFullInventory(bot, source, stand);
                if (storedChest.isEmpty()) {
                    return SkillExecutionResult.failure("Inventory full and couldn't store items.");
                }
                if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
                    return SkillExecutionResult.failure("Lost fishing rod during storage routine.");
                }
                // Re-evaluate best fishing spot after storage
                FishingSpot newSpot = findFishingSpot(bot, WATER_SEARCH_RADIUS);
                if (newSpot != null) {
                    spot = newSpot;
                    stand = newSpot.stand();
                    LOGGER.info("Re-selected fishing spot after storage: stand={} water={}", stand.toShortString(), spot.water().toShortString());
                    if (!navigateToSpot(source, bot, stand)) {
                        return SkillExecutionResult.failure("Can't return to the fishing spot after storing items.");
                    }
                    adjustPositionToWaterEdge(bot, spot.water());
                }
                continue;
            }

            // Ensure we are on solid ground before casting; if not, try returning to our chosen stand first.
            if (!isStandable(world, bot.getBlockPos()) || world.getFluidState(bot.getBlockPos()).isIn(FluidTags.WATER)) {
                LOGGER.info("Bot is in water or not on solid ground; returning to fishing stand before casting.");
                if (!navigateToSpot(source, bot, stand)) {
                    FishingSpot recoverySpot = findFishingSpot(bot, WATER_SEARCH_RADIUS);
                    if (recoverySpot != null) {
                        spot = recoverySpot;
                        stand = recoverySpot.stand();
                        if (navigateToSpot(source, bot, stand)) {
                            adjustPositionToWaterEdge(bot, spot.water());
                        }
                    }
                } else {
                    adjustPositionToWaterEdge(bot, spot.water());
                }
            }

            castTarget = chooseCastTarget(world, bot, stand, spot.water(), castTarget);
            if (castTarget == null) {
                LOGGER.warn("No valid cast target found near {}; re-selecting fishing spot.", spot.water().toShortString());
                FishingSpot recoverySpot = findFishingSpot(bot, WATER_SEARCH_RADIUS);
                if (recoverySpot == null) {
                    return SkillExecutionResult.failure("Can't find a valid fishing cast target.");
                }
                spot = recoverySpot;
                stand = recoverySpot.stand();
                if (!navigateToSpot(source, bot, stand)) {
                    return SkillExecutionResult.failure("Can't reach a valid fishing spot.");
                }
                castTarget = chooseCastTarget(world, bot, stand, spot.water(), spot.castTarget());
            }

            aimTowardWater(bot, castTarget);
            BotActions.useSelectedItem(bot); // Cast
            
            // Wait for bobber to settle and check validity
            sleep(1200L);
            FishingBobberEntity bobber = findActiveBobber(bot);
            if (bobber != null) {
                boolean ok = false;
                long settleDeadline = System.currentTimeMillis() + 1000L;
                while (System.currentTimeMillis() < settleDeadline && !SkillManager.shouldAbortSkill(bot)) {
                    BlockPos bobberPos = bobber.getBlockPos();
                    if (world.getFluidState(bobberPos).isIn(FluidTags.WATER) || bobber.isTouchingWater()) {
                        ok = true;
                        break;
                    }
                    sleep(150L);
                }
                if (!ok) {
                    var bobberPos = bobber.getBlockPos();
                    var bobberState = world.getBlockState(bobberPos);
                    var bobberFluid = world.getFluidState(bobberPos);
                    LOGGER.warn("Bad throw detected (bobber={} block={} fluid={}): retracting and adjusting.",
                            bobberPos.toShortString(),
                            bobberState.getBlock().getName().getString(),
                            bobberFluid.isIn(FluidTags.WATER) ? "water" : "not-water");
                    BotActions.useSelectedItem(bot); // Retract
                    attempts++;
                    adjustPositionToWaterEdge(bot, spot.water());
                    continue;
                }
            }
            
            boolean caughtFish = waitForBite(bot);
            
            if (!caughtFish) {
                 BotActions.useSelectedItem(bot); // Retract
                 attempts++;
                 continue;
            }

            castTarget = chooseCastTarget(world, bot, stand, spot.water(), castTarget);
            aimTowardWater(bot, castTarget != null ? castTarget : spot.water());
            BotActions.useSelectedItem(bot); // Reel in
            waitForBobberRemoval(bot);
            
            sleep(600L); // Wait for item arrival

            // If drops landed just out of pickup range, shimmy toward them to collect.
            shimmyTowardNearbyDrops(source, bot, stand, spot.water());

            // If loot is still building up after a catch, proactively sweep it before casting again.
            long afterCatchMs = System.currentTimeMillis();
            if (afterCatchMs - lastReactiveSweepTime > DROP_SWEEP_TRIGGER_COOLDOWN_MS) {
                int nearbyDrops = countNearbyDropsForFishing(world, stand, spot.water(), DROP_SWEEP_TRIGGER_RADIUS);
                if (nearbyDrops >= DROP_SWEEP_TRIGGER_COUNT) {
                    LOGGER.info("Loot pile-up after catch ({} drops); performing quick sweep.", nearbyDrops);
                    performReactiveSweep(source, bot, stand);
                    lastReactiveSweepTime = System.currentTimeMillis();
                    if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
                        return SkillExecutionResult.failure("Lost fishing rod during reactive sweep.");
                    }
                    adjustPositionToWaterEdge(bot, spot.water());
                }
            }

            // Always return to the stand after a shimmy to avoid slowly wandering.
            if (bot.getBlockPos().getSquaredDistance(stand) > 2.25D) {
                navigateToSpot(source, bot, stand);
                BotActions.stop(bot);
                adjustPositionToWaterEdge(bot, spot.water());
            }
            
            int now = countFish(bot);
            int delta = now - baseline;
            if (delta > 0) {
                caught += delta;
                baseline = now;
            } else {
                caught += 1;
                baseline = now;
            }
            attempts++;
            
            BotActions.stop(bot); // Ensure we don't drift
        }

        // Final Sweep
        performSweep(source, bot, stand);

        if (caught == 0 && attempts > 0) {
            return SkillExecutionResult.failure("No bites after " + attempts + " casts.");
        }
        
        ChatUtils.sendSystemMessage(source, "Fishing session finished. Caught " + caught + " items.");
        return SkillExecutionResult.success("Fishing succeeded (" + caught + " items).");
    }

    private boolean navigateToSpot(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        // Use pathfinding first, disable pursuit fallback to avoid walking off cliffs.
        // IMPORTANT: MovementService uses a fairly generous arrival radius; for fishing we need to
        // truly reach the chosen stand (doors/walls can otherwise produce false "arrived" results).
        MovementPlan plan = new MovementPlan(Mode.DIRECT, target, target, null, null, null);
        MovementResult result = MovementService.execute(source, bot, plan, Boolean.FALSE, false, false, false);
        if (result.success()) {
            // IMPORTANT: distance alone is not enough here.
            // If the target is on the other side of a closed door, the bot can be "close" but unreachable.
            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= APPROACH_REACH_SQ) {
                BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, target, 12.0D);
                if (blockingDoor == null) {
                    return true;
                }

                // Try the minimal "open + step" behavior, then re-check.
                MovementService.tryOpenDoorAt(bot, blockingDoor);
                // Commit to crossing THIS doorway toward our fishing target.
                MovementService.tryTraverseOpenableToward(bot, blockingDoor, target, "fish-stand-door-near");
                MovementService.nudgeTowardUntilClose(bot, target, APPROACH_REACH_SQ, 2200L, 0.18, "fish-stand-door-near");
                if (bot.getBlockPos().getSquaredDistance(target) <= APPROACH_REACH_SQ
                        && BlockInteractionService.findBlockingDoor(bot, target, 12.0D) == null) {
                    return true;
                }
                // Fall through to door-escape assist below.
            } else {
                // Common failure case: destination is "close" but separated by a closed door.
                BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, target, 64.0D);
                if (blockingDoor != null) {
                    MovementService.tryOpenDoorAt(bot, blockingDoor);
                    MovementService.tryTraverseOpenableToward(bot, blockingDoor, target, "fish-stand-door");
                    MovementService.nudgeTowardUntilClose(bot, target, APPROACH_REACH_SQ, 2000L, 0.16, "fish-stand-door");
                    if (bot.getBlockPos().getSquaredDistance(target) <= APPROACH_REACH_SQ
                            && BlockInteractionService.findBlockingDoor(bot, target, 12.0D) == null) {
                        return true;
                    }
                }
            }
        }

        String failureDetail = result.detail();

        // If failed, try clearing leaves towards target
        Direction dir = Direction.getFacing(target.getX() - bot.getX(), 0, target.getZ() - bot.getZ());
        if (MovementService.clearLeafObstruction(bot, dir)) {
            // Retry if we cleared something
            result = MovementService.execute(source, bot, plan, Boolean.FALSE, false, false, false);
            if (result.success() && bot.getBlockPos().getSquaredDistance(target) <= APPROACH_REACH_SQ) {
                return true;
            }
            failureDetail = result.detail();
        }

        // If the target is blocked by a nearby door/gate on the direct line, prioritize a doorway traverse
        // before broader "door escape" heuristics (prevents getting pulled back inside).
        BlockPos directBlockingDoor = BlockInteractionService.findBlockingDoor(bot, target, 64.0D);
        if (directBlockingDoor != null) {
            MovementService.tryOpenDoorAt(bot, directBlockingDoor);
            MovementService.tryTraverseOpenableToward(bot, directBlockingDoor, target, "fish-doorway");
            result = MovementService.execute(source, bot, plan, Boolean.FALSE, true, false, false);
            if (result.success() && bot.getBlockPos().getSquaredDistance(target) <= APPROACH_REACH_SQ) {
                return true;
            }
            failureDetail = result.detail();
        }

        // Door escape assist: if we appear to be in an enclosure, try a short "approach -> open -> step through".
        // This is intentionally local and only used as a recovery step when normal navigation fails.
        try {
            // If a door is directly blocking the line, we already handled it above.
            MovementService.DoorSubgoalPlan doorPlan = directBlockingDoor == null
                    ? MovementService.findDoorEscapePlan(bot, target, null)
                    : null;
            if (doorPlan != null) {
                boolean approached = MovementService.nudgeTowardUntilClose(bot, doorPlan.approachPos(), 2.25D, 2200L, 0.18, "fish-door-approach");
                if (approached) {
                    MovementService.tryOpenDoorAt(bot, doorPlan.doorBase());
                    // Be slightly more forgiving here: we just need to cross the doorway enough
                    // for the follow-up pathfinding to replan successfully.
                    MovementService.nudgeTowardUntilClose(bot, doorPlan.stepThroughPos(), 4.0D, 2600L, 0.22, "fish-door-step");
                    // After stepping through, retry the main move.
                    result = MovementService.execute(source, bot, plan, Boolean.FALSE, true, false, false);
                    if (result.success() && bot.getBlockPos().getSquaredDistance(target) <= APPROACH_REACH_SQ) {
                        return true;
                    }
                    failureDetail = result.detail();
                }
            }
        } catch (Throwable t) {
            // Never fail the skill due to a door helper throwing.
        }

        // Fallback: Safe nudge (only if very close)
        double distSq = bot.getBlockPos().getSquaredDistance(target);
        if (distSq <= 16.0) {
            boolean nudged = safeNudge(bot, target);
            if (nudged) {
                return true;
            }
            failureDetail = "safe nudge failed near " + target.toShortString();
        }
        
        // Blacklist this target to prevent oscillation.
        blacklistTarget(bot.getUuid(), target);
        
        LOGGER.warn("Navigation to fishing spot {} failed: {}", target.toShortString(), failureDetail);
        return false;
    }

    private boolean safeNudge(ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) return false;
        // Only try to nudge if we are reasonably close
        if (bot.getBlockPos().getSquaredDistance(target) > 25.0) return false;

        long deadline = System.currentTimeMillis() + 2500L;
        
        while (System.currentTimeMillis() < deadline) {
            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= APPROACH_REACH_SQ) {
                BotActions.stop(bot);
                return true;
            }
            
            LookController.faceBlock(bot, target);
            BotActions.sprint(bot, false); // No sprint for safety
            
            // Safety check: is ground ahead?
            Vec3d currentPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            Vec3d toward = Vec3d.ofCenter(target).subtract(currentPos).multiply(1.0, 0.0, 1.0);
            double lenSq = toward.lengthSquared();
            if (lenSq < 1.0E-6D) {
                return true;
            }
            toward = toward.multiply(1.0D / Math.sqrt(lenSq));
            Vec3d nextStep = currentPos.add(toward.multiply(0.6));
            BlockPos nextBlock = BlockPos.ofFloored(nextStep);
            if (bot.getEntityWorld().getBlockState(nextBlock.down()).getCollisionShape(bot.getEntityWorld(), nextBlock.down()).isEmpty()) {
                // Edge detected!
                BotActions.stop(bot);
                return false;
            }
            
            BotActions.applyMovementInput(bot, Vec3d.ofCenter(target), 0.2);
            // Small jump if needed
            if (target.getY() > bot.getY() + 0.6) {
                BotActions.autoJumpIfNeeded(bot);
            }
            
            sleep(100L);
        }
        return false;
    }

    private void performSweep(ServerCommandSource source, ServerPlayerEntity bot, BlockPos returnPos) {
        LOGGER.info("Scanning for loose items...");
        // Short duration sweep to catch floating items
        DropSweeper.sweep(source, 10.0, 5.0, 15, 8000L);
        // Return to spot
        navigateToSpot(source, bot, returnPos);
    }

    private void adjustPositionToWaterEdge(ServerPlayerEntity bot, BlockPos water) {
        if (bot == null || water == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        // Face the water
        LookController.faceBlock(bot, water);

        // Safety: only adjust when grounded.
        if (!bot.isOnGround()) {
            return;
        }

        Vec3d waterCenter = Vec3d.ofCenter(water);
        Vec3d botXZ = new Vec3d(bot.getX(), 0.0D, bot.getZ());
        Vec3d waterXZ = new Vec3d(waterCenter.x, 0.0D, waterCenter.z);
        double horizDistSq = botXZ.squaredDistanceTo(waterXZ);

        // Already close enough (roughly "adjacent to" the water block).
        if (horizDistSq <= 1.35D * 1.35D) {
            return;
        }

        BotActions.sneak(bot, true);
        try {
            // Take a few conservative micro-steps toward the shore.
            for (int i = 0; i < 8; i++) {
                if (!bot.isOnGround()) {
                    break;
                }

                Vec3d currentPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
                Vec3d toward = waterCenter.subtract(currentPos).multiply(1.0, 0.0, 1.0);
                double lenSq = toward.lengthSquared();
                if (lenSq < 1.0E-6D) {
                    break;
                }
                toward = toward.multiply(1.0D / Math.sqrt(lenSq));

                // Preview the next foothold.
                Vec3d nextStep = currentPos.add(toward.multiply(0.55));
                BlockPos nextBlock = BlockPos.ofFloored(nextStep);

                // Don't step into water (we want shoreline fishing, not wading).
                if (world.getFluidState(nextBlock).isIn(FluidTags.WATER)) {
                    break;
                }

                // Don't step off a ledge.
                if (world.getBlockState(nextBlock.down()).getCollisionShape(world, nextBlock.down()).isEmpty()) {
                    break;
                }

                // If the destination is not a plausible stand position, don't risk it.
                if (!isStandable(world, nextBlock)) {
                    break;
                }

                BotActions.applyMovementInput(bot, waterCenter, 0.12);
                sleep(140L);
                BotActions.stop(bot);

                // Re-check closeness.
                botXZ = new Vec3d(bot.getX(), 0.0D, bot.getZ());
                horizDistSq = botXZ.squaredDistanceTo(waterXZ);
                if (horizDistSq <= 1.35D * 1.35D) {
                    break;
                }
            }
        } finally {
            BotActions.sneak(bot, false);
        }
    }

    private void shimmyTowardNearbyDrops(ServerCommandSource source,
                                        ServerPlayerEntity bot,
                                        BlockPos stand,
                                        BlockPos waterAnchor) {
        if (source == null || bot == null || stand == null || waterAnchor == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        // Only do small, local adjustments; don't wander off from the spot.
        double maxScan = 5.5D;
        List<ItemEntity> items = world.getEntitiesByClass(
                ItemEntity.class,
                bot.getBoundingBox().expand(maxScan, 2.0D, maxScan),
                e -> e != null && e.isAlive() && e.getStack() != null && !e.getStack().isEmpty()
        );
        if (items.isEmpty()) {
            return;
        }

        // Filter to drops that are plausibly from this fishing session: nearby the stand and not "behind" it.
        // This prevents the bot from slowly creeping toward the commander (who is often behind the bot).
        Vec3d standCenter = Vec3d.ofCenter(stand);
        Vec3d waterCenter = Vec3d.ofCenter(waterAnchor);
        Vec3d forward = waterCenter.subtract(standCenter).multiply(1.0, 0.0, 1.0);
        double forwardLenSq = forward.lengthSquared();
        if (forwardLenSq > 1.0E-6) {
            forward = forward.multiply(1.0 / Math.sqrt(forwardLenSq));
        }
        final Vec3d fwd = forward;
        items.removeIf(e -> {
            if (e == null) {
                return true;
            }
            if (e.getBlockPos().getSquaredDistance(stand) > 4.0D * 4.0D) {
                return true;
            }
            if (fwd.lengthSquared() < 1.0E-6) {
                return false;
            }
            double dx = e.getX() - standCenter.x;
            double dz = e.getZ() - standCenter.z;
            double dot = dx * fwd.x + dz * fwd.z;
            // Allow slight sideways jitter, but reject clearly "behind" the stand.
            return dot < -0.15D;
        });
        if (items.isEmpty()) {
            return;
        }

        items.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)));
        ItemEntity closest = items.getFirst();
        if (closest == null || !closest.isAlive()) {
            return;
        }

        double distSq = closest.squaredDistanceTo(bot);
        // If already basically in pickup range, don't move.
        if (distSq <= 2.0D * 2.0D) {
            return;
        }

        BlockPos best = findStandableNear(world, closest.getBlockPos(), 2);
        if (best == null) {
            // Fallback: edge nudge again.
            adjustPositionToWaterEdge(bot, waterAnchor);
            return;
        }

        // Don't chase drops too far away from the fishing stand.
        if (best.getSquaredDistance(stand) > 5.0D * 5.0D) {
            return;
        }

        navigateToSpot(source, bot, best);
        BotActions.stop(bot);
        adjustPositionToWaterEdge(bot, waterAnchor);
    }

    private static BlockPos findStandableNear(ServerWorld world, BlockPos around, int radius) {
        if (world == null || around == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        int r = Math.max(0, radius);
        for (BlockPos pos : BlockPos.iterate(around.add(-r, -1, -r), around.add(r, 1, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!isStandable(world, pos)) {
                continue;
            }
            double d = pos.getSquaredDistance(around);
            if (d < bestDist) {
                bestDist = d;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private static Optional<BlockPos> handleFullInventory(ServerPlayerEntity bot, ServerCommandSource source, BlockPos safeStand) {
        ArrowKeep arrowKeep = computeArrowKeep(bot);
        for (BlockPos chest : findNearbyChests(bot, CHEST_SCAN_RADIUS)) {
            LOGGER.info("Attempting to deposit into nearby chest at {}", chest.toShortString());
            int deposited = ChestStoreService.depositMatchingWalkOnly(source, bot, chest, stack -> shouldStoreItem(stack, arrowKeep));
            if (deposited > 0) {
                LOGGER.info("Stored {} items in chest {}", deposited, chest.toShortString());
                return Optional.of(chest);
            }
            LOGGER.info("Deposit attempt to chest {} yielded {} items", chest.toShortString(), deposited);
        }

        BlockPos chestPos = findNearbyChestWithSpace(bot);
        if (chestPos == null) {
            LOGGER.info("No nearby empty chest detected around {}; crafting/placing chest...", bot.getBlockPos().toShortString());
            if (!hasItem(bot, Items.CHEST)) {
                CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "chest", 1, null);
            }
            if (hasItem(bot, Items.CHEST)) {
                chestPos = placeChestNearby(bot, safeStand);
            }
        }
        if (chestPos != null) {
            LOGGER.info("Depositing items to chest at {}", chestPos.toShortString());
            int deposited = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, stack -> shouldStoreItem(stack, arrowKeep));
            if (deposited > 0) {
                LOGGER.info("Stored {} items in chest {}", deposited, chestPos.toShortString());
                return Optional.of(chestPos);
            }
            LOGGER.warn("Chest {} reachable but depositMatching returned {} items.", chestPos.toShortString(), deposited);
        } else {
            LOGGER.warn("Unable to locate or place a chest near {} during storage.", safeStand.toShortString());
        }
        return Optional.empty();
    }

    private static BlockPos findNearbyChestWithSpace(ServerPlayerEntity bot) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-5, -2, -5), origin.add(5, 2, 5))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!(world.getBlockState(pos).isOf(Blocks.CHEST) || world.getBlockState(pos).isOf(Blocks.TRAPPED_CHEST))) {
                continue;
            }
            if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest && chestHasSpace(chest)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static boolean chestHasSpace(ChestBlockEntity chest) {
        if (chest == null) {
            return false;
        }
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.getCount() < stack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> findNearbyChests(ServerPlayerEntity bot, int radius) {
        if (bot == null || radius <= 0) {
            return List.of();
        }
        World world = bot.getEntityWorld();
        if (!(world instanceof ServerWorld serverWorld)) {
            return List.of();
        }
        BlockPos origin = bot.getBlockPos();
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!serverWorld.isChunkLoaded(pos)) {
                continue;
            }
            var state = serverWorld.getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                chests.add(pos.toImmutable());
            }
        }
        chests.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return chests;
    }

    private static BlockPos placeChestNearby(ServerPlayerEntity bot, BlockPos near) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        // Prefer positions that do not overwrite the stand and are slightly away from water
        Direction[] prefs = Direction.values();
        for (Direction dir : prefs) {
            if (!dir.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos candidate = near.offset(dir);
            if (!world.getBlockState(candidate).isAir()) {
                continue;
            }
            if (!world.getBlockState(candidate.down()).isSolidBlock(world, candidate.down())) {
                continue;
            }
            BotActions.placeBlockAt(bot, candidate, List.of(Items.CHEST));
            if (world.getBlockState(candidate).isOf(Blocks.CHEST)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean ensureFishingRod(ServerCommandSource source, ServerPlayerEntity bot) {
        if (hasItem(bot, Items.FISHING_ROD)) {
            return true;
        }
        int crafted = CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "fishing_rod", 1, null);
        return crafted > 0 && hasItem(bot, Items.FISHING_ROD);
    }

    private static FishingSpot findFishingSpot(ServerPlayerEntity bot, int radius) {
        if (bot == null) {
            return null;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        
        // Pre-capture a reachability snapshot centered on the bot for A* validation.
        FollowPathService.FollowSnapshot reachabilitySnapshot = FollowPathService.capture(world, origin, origin, true);
        
        // Clean up expired blacklist entries.
        UUID botUuid = bot.getUuid();
        cleanupBlacklist(botUuid);
        Map<BlockPos, Long> blacklist = FAILED_TARGET_BLACKLIST.getOrDefault(botUuid, Map.of());
        
        FishingSpot best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos water : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(water)) {
                continue;
            }
            if (!world.getFluidState(water).isIn(FluidTags.WATER)) {
                continue;
            }
            if (!isOpenWaterSurface(world, water)) {
                continue;
            }

            List<StandOption> options = findStandOptions(world, water, origin);
            if (options.isEmpty()) {
                continue;
            }

            // Filter options: skip blacklisted stands and verify reachability.
            List<StandOption> reachableOptions = new ArrayList<>();
            for (StandOption opt : options) {
                BlockPos stand = opt.stand();
                // Skip blacklisted positions.
                if (blacklist.containsKey(stand)) {
                    LOGGER.debug("Skipping blacklisted fishing stand {}", stand.toShortString());
                    continue;
                }
                // Check reachability via A* if we have a snapshot and stand is in bounds.
                if (reachabilitySnapshot != null && reachabilitySnapshot.inBounds(stand)) {
                    boolean alreadyThere = origin.getSquaredDistance(stand) <= 2.25D;
                    if (!alreadyThere && !FollowPathService.isReachable(reachabilitySnapshot, stand)) {
                        LOGGER.debug("Fishing spot {} unreachable via A* from {}", stand.toShortString(), origin.toShortString());
                        continue;
                    }
                }
                reachableOptions.add(opt);
            }
            if (reachableOptions.isEmpty()) {
                continue;
            }

            StandOption primary = reachableOptions.get(0);
            if (primary.score() < bestScore) {
                bestScore = primary.score();
                best = new FishingSpot(
                        water.toImmutable(),
                        primary.stand(),
                        primary.castTarget(),
                        reachableOptions.stream().map(StandOption::stand).toList()
                );
            }
        }
        
        if (best != null) {
            LOGGER.info("Selected fishing spot: stand={} water={} (score={:.2f})", 
                    best.stand().toShortString(), best.water().toShortString(), bestScore);
        } else {
            LOGGER.info("No reachable fishing spot found within radius {} from {}", radius, origin.toShortString());
        }
        return best;
    }

    private static List<StandOption> findStandOptions(ServerWorld world, BlockPos water, BlockPos botPos) {
        List<BlockPos> stands = findStandCandidates(world, water, botPos);
        if (stands.isEmpty()) {
            return List.of();
        }

        List<StandOption> options = new ArrayList<>();
        for (BlockPos stand : stands) {
            BlockPos castTarget = chooseCastTargetHeuristic(world, stand, water);
            if (castTarget == null) {
                continue;
            }
            double castDistSq = stand.getSquaredDistance(castTarget);
            double castDistancePenalty = Math.abs(castDistSq - IDEAL_CAST_DISTANCE_SQ) / (double) IDEAL_CAST_DISTANCE_SQ;
            int openness = countOpenWaterSurface(world, castTarget, 1);
            int depth = estimateWaterDepth(world, castTarget, 6);

            // Prefer open/deep water and a reasonable cast distance.
            double quality = castDistancePenalty - openness * 0.35 - depth * 0.55;

            // Strong preference for shoreline adjacency: loot pickup suffers when we stand a few blocks back.
            // This is deliberately large enough to override small water-quality differences.
            double shorePenalty = isAdjacentToWater(stand, water) ? 0.0D : 0.85D;

            // Weak travel penalty: don't let bot/commander position dominate the choice.
            double travelPenalty = botPos.getSquaredDistance(stand) * 0.01;

            options.add(new StandOption(stand.toImmutable(), castTarget.toImmutable(), quality + shorePenalty + travelPenalty));
        }

        options.sort(Comparator.comparingDouble(StandOption::score));
        return options;
    }

    private static BlockPos chooseCastTarget(ServerWorld world, ServerPlayerEntity bot, BlockPos stand, BlockPos waterAnchor, BlockPos preferred) {
        if (world == null || bot == null || stand == null || waterAnchor == null) {
            return null;
        }

        if (preferred != null && isOpenWaterSurface(world, preferred) && isCastPathClear(world, bot, preferred) && isReasonableCastDistance(stand, preferred)) {
            return preferred;
        }

        return chooseCastTargetAlongLine(world, bot, stand, waterAnchor);
    }

    private static BlockPos chooseCastTargetHeuristic(ServerWorld world, BlockPos stand, BlockPos waterAnchor) {
        return chooseCastTargetAlongLine(world, null, stand, waterAnchor);
    }

    private static BlockPos chooseCastTargetAlongLine(ServerWorld world, ServerPlayerEntity bot, BlockPos stand, BlockPos waterAnchor) {
        if (world == null || stand == null || waterAnchor == null) {
            return null;
        }

        Vec3d standCenter = Vec3d.ofCenter(stand);
        Vec3d waterCenter = Vec3d.ofCenter(waterAnchor);
        Vec3d dir = waterCenter.subtract(standCenter).multiply(1.0, 0.0, 1.0);
        double lenSq = dir.lengthSquared();
        if (lenSq < 1.0E-6) {
            return isOpenWaterSurface(world, waterAnchor) ? waterAnchor.toImmutable() : null;
        }
        dir = dir.normalize();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        // Step into the water away from the shoreline, with small lateral wiggle room.
        for (int step = 2; step <= 9; step++) {
            Vec3d base = waterCenter.add(dir.multiply(step));
            BlockPos basePos = BlockPos.ofFloored(base.x, waterAnchor.getY(), base.z);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    BlockPos probe = basePos.add(ox, 0, oz);
                    BlockPos surface = findOpenWaterSurfaceAt(world, probe.getX(), probe.getZ(), waterAnchor.getY(), 2);
                    if (surface == null) {
                        continue;
                    }
                    if (!isReasonableCastDistance(stand, surface)) {
                        continue;
                    }
                    if (bot != null && !isCastPathClear(world, bot, surface)) {
                        continue;
                    }

                    double distSq = stand.getSquaredDistance(surface);
                    double distPenalty = Math.abs(distSq - IDEAL_CAST_DISTANCE_SQ) / (double) IDEAL_CAST_DISTANCE_SQ;
                    int openness = countOpenWaterSurface(world, surface, 1);
                    int depth = estimateWaterDepth(world, surface, 6);
                    double score = distPenalty - openness * 0.35 - depth * 0.55;

                    if (score < bestScore) {
                        bestScore = score;
                        best = surface.toImmutable();
                    }
                }
            }
        }

        if (best != null) {
            return best;
        }

        BlockPos fallback = findOpenWaterSurfaceNear(world, waterAnchor, 2, 2);
        if (fallback == null) {
            return null;
        }
        if (bot != null && !isCastPathClear(world, bot, fallback)) {
            return null;
        }
        return fallback.toImmutable();
    }

    private static BlockPos findOpenWaterSurfaceAt(ServerWorld world, int x, int z, int baseY, int verticalRadius) {
        if (world == null) {
            return null;
        }
        for (int dy = 0; dy <= verticalRadius; dy++) {
            BlockPos up = new BlockPos(x, baseY + dy, z);
            if (world.isChunkLoaded(up) && isOpenWaterSurface(world, up)) {
                return up.toImmutable();
            }
            if (dy == 0) {
                continue;
            }
            BlockPos down = new BlockPos(x, baseY - dy, z);
            if (world.isChunkLoaded(down) && isOpenWaterSurface(world, down)) {
                return down.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos findOpenWaterSurfaceNear(ServerWorld world, BlockPos around, int horizontalRadius, int verticalRadius) {
        if (world == null || around == null || horizontalRadius < 0 || verticalRadius < 0) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int baseY = around.getY();
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                BlockPos surface = findOpenWaterSurfaceAt(world, around.getX() + dx, around.getZ() + dz, baseY, verticalRadius);
                if (surface == null) {
                    continue;
                }
                double distSq = around.getSquaredDistance(surface);
                if (distSq < bestDist) {
                    bestDist = distSq;
                    best = surface.toImmutable();
                }
            }
        }
        return best;
    }

    private static boolean isReasonableCastDistance(BlockPos stand, BlockPos target) {
        double distSq = stand.getSquaredDistance(target);
        return distSq >= MIN_CAST_DISTANCE_SQ && distSq <= MAX_CAST_DISTANCE_SQ;
    }

    private static boolean isOpenWaterSurface(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        if (!world.getFluidState(pos).isIn(FluidTags.WATER)) {
            return false;
        }
        return isSpaceClear(world, pos.up());
    }

    private static int countOpenWaterSurface(ServerWorld world, BlockPos center, int radius) {
        int count = 0;
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, 0, -radius), center.add(radius, 0, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (isOpenWaterSurface(world, pos)) {
                count++;
            }
        }
        return count;
    }

    private static int estimateWaterDepth(ServerWorld world, BlockPos surface, int maxDepth) {
        int depth = 0;
        for (int i = 0; i < maxDepth; i++) {
            BlockPos pos = surface.down(i);
            if (!world.isChunkLoaded(pos)) {
                break;
            }
            if (!world.getFluidState(pos).isIn(FluidTags.WATER)) {
                break;
            }
            depth++;
        }
        return depth;
    }

    private static boolean isCastPathClear(ServerWorld world, ServerPlayerEntity bot, BlockPos targetWater) {
        Vec3d from = bot.getCameraPosVec(1.0F);
        Vec3d to = Vec3d.ofCenter(targetWater).add(0.0, 0.15, 0.0);
        var hit = world.raycast(new RaycastContext(
                from,
                to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean hasNearbyWater(ServerPlayerEntity bot, int radius) {
        if (bot == null) {
            return false;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getFluidState(pos).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> findStandCandidates(ServerWorld world, BlockPos water, BlockPos botPos) {
        List<StandCandidate> candidates = new ArrayList<>();
        if (world == null || water == null || botPos == null) {
            return List.of();
        }
        
        BlockPos min = water.add(-3, -1, -3);
        BlockPos max = water.add(3, 4, 3);
        
        for (BlockPos candidate : BlockPos.iterate(min, max)) {
            if (candidate.equals(water)) {
                continue;
            }
            boolean adjacent = isAdjacentToWater(candidate, water);
            if (!adjacent && !canCastFrom(candidate, water)) {
                continue;
            }
            if (!isStandable(world, candidate)) {
                continue;
            }
            candidates.add(new StandCandidate(candidate.toImmutable(), adjacent));
        }
        if (candidates.isEmpty()) {
            BlockPos above = water.up();
            if (isStandable(world, above)) {
                candidates.add(new StandCandidate(above.toImmutable(), true));
            }
        }
        candidates.sort(Comparator
                .comparing((StandCandidate c) -> !c.adjacent())
                .thenComparingDouble(c -> botPos.getSquaredDistance(c.pos())));
        return candidates.stream().map(StandCandidate::pos).toList();
    }
    
    private static boolean canCastFrom(BlockPos stand, BlockPos water) {
        // Simple heuristic: if stand is higher than water and within casting distance
        int dy = stand.getY() - water.getY();
        double distSq = stand.getSquaredDistance(water);
        return dy >= 0 && dy <= 5 && distSq <= MAX_CAST_DISTANCE_SQ;
    }

    private static boolean isAdjacentToWater(BlockPos candidate, BlockPos water) {
        if (candidate == null || water == null) {
            return false;
        }
        int dx = Math.abs(candidate.getX() - water.getX());
        int dz = Math.abs(candidate.getZ() - water.getZ());
        int dy = Math.abs(candidate.getY() - water.getY());

        // Fishing loot pickup is noticeably worse from diagonal adjacency (distance ~1.41 blocks).
        // Prefer cardinal adjacency (N/E/S/W), or the block directly above water (e.g. docks).
        boolean directAbove = dx == 0 && dz == 0 && dy == 1;
        boolean cardinalAdjacent = (dx + dz) == 1 && dy <= 1;
        return directAbove || cardinalAdjacent;
    }

    private static int countNearbyDropsForFishing(ServerWorld world,
                                                 BlockPos stand,
                                                 BlockPos waterAnchor,
                                                 double radius) {
        if (world == null || stand == null) {
            return 0;
        }
        double r = Math.max(0.0D, radius);
        Vec3d standCenter = Vec3d.ofCenter(stand);

        // Bias toward loot that is "in front" of the stand (toward water) to avoid chasing the commander behind.
        Vec3d waterCenter = waterAnchor != null ? Vec3d.ofCenter(waterAnchor) : standCenter;
        Vec3d forward = waterCenter.subtract(standCenter).multiply(1.0, 0.0, 1.0);
        double forwardLenSq = forward.lengthSquared();
        if (forwardLenSq > 1.0E-6D) {
            forward = forward.multiply(1.0 / Math.sqrt(forwardLenSq));
        }
        final Vec3d fwd = forward;

        double radiusSq = r * r;
        var box = new net.minecraft.util.math.Box(
                standCenter.x - r, standCenter.y - 2.0D, standCenter.z - r,
                standCenter.x + r, standCenter.y + 4.0D, standCenter.z + r
        );
        List<ItemEntity> items = world.getEntitiesByClass(
                ItemEntity.class,
                box,
                e -> e != null && e.isAlive() && e.getStack() != null && !e.getStack().isEmpty()
        );
        if (items.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ItemEntity e : items) {
            if (e == null || !e.isAlive()) {
                continue;
            }
            double dx = e.getX() - standCenter.x;
            double dz = e.getZ() - standCenter.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            if (fwd.lengthSquared() > 1.0E-6D) {
                double dot = dx * fwd.x + dz * fwd.z;
                if (dot < -0.35D) {
                    continue;
                }
            }
            count++;
        }
        return count;
    }

    private static void retractBobberIfPresent(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        FishingBobberEntity bobber = findActiveBobber(bot);
        if (bobber == null) {
            return;
        }
        try {
            BotActions.useSelectedItem(bot);
            sleep(250L);
        } catch (Throwable ignored) {
        }
    }

    private void performReactiveSweep(ServerCommandSource source, ServerPlayerEntity bot, BlockPos returnPos) {
        if (source == null || bot == null || returnPos == null) {
            return;
        }
        LOGGER.info("Reactive sweep: collecting nearby fishing drops...");
        // Smaller than the periodic sweep: quick and local.
        DropSweeper.sweep(source, 7.5, 4.5, 8, 5000L);
        navigateToSpot(source, bot, returnPos);
        BotActions.stop(bot);
    }

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);
        if (world.getFluidState(pos).isIn(FluidTags.WATER) || world.getFluidState(pos.up()).isIn(FluidTags.WATER)) {
            return false;
        }
        if (!isSpaceClear(world, pos) || !isSpaceClear(world, pos.up())) {
            return false;
        }
        return !belowState.isAir() && !belowState.getCollisionShape(world, below).isEmpty();
    }

    private static boolean isSpaceClear(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static void aimTowardWater(ServerPlayerEntity bot, BlockPos water) {
        if (bot == null || water == null) {
            return;
        }
        LookController.faceBlock(bot, water);
    }

    private static boolean waitForBite(ServerPlayerEntity bot) {
        long deadline = System.currentTimeMillis() + CAST_WAIT_MS * 4;
        while (!SkillManager.shouldAbortSkill(bot) && System.currentTimeMillis() < deadline) {
            FishingBobberEntity bobber = findActiveBobber(bot);
            boolean bite = hasFishBite(bobber);
            if (bite) {
                LOGGER.info("Fishing bite detected near {} for {}", bobber != null ? bobber.getBlockPos().toShortString() : "unknown", 
bot.getName().getString());
                return true;
            }
            sleep(250L);
        }
        return false;
    }

    private static boolean hasFishBite(FishingBobberEntity bobber) {
        if (bobber == null) {
            return false;
        }
        if (CAUGHT_FISH_FIELD != null) {
            try {
                return CAUGHT_FISH_FIELD.getBoolean(bobber);
            } catch (IllegalAccessException e) {
                LOGGER.debug("Caught-fish field access failed", e);
            }
        }
        return bobber.getHookedEntity() != null;
    }

    private static Field findCaughtFishField() {
        String[] candidates = {"caughtFish", "field_23232"};
        for (String candidate : candidates) {
            Field field = tryField(candidate);
            if (field != null) {
                LOGGER.info("Fishing bite detection bound to field {}", field.getName());
                return field;
            }
        }
        LOGGER.warn("Unable to access a fishing bite flag field, tried {}", Arrays.toString(candidates));
        return null;
    }

    private static Field tryField(String name) {
        try {
            Field field = FishingBobberEntity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            LOGGER.debug("Fishing bobber field {} not found", name, e);
        } catch (Throwable throwable) {
            LOGGER.warn("Unexpected error while binding fishing bobber field {}", name, throwable);
        }
        return null;
    }

    private static void waitForBobberRemoval(ServerPlayerEntity bot) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (findActiveBobber(bot) == null) {
                return;
            }
            sleep(150L);
        }
    }

    private static FishingBobberEntity findActiveBobber(ServerPlayerEntity bot) {
        if (bot == null || bot.getEntityWorld() == null) {
            return null;
        }
        World world = bot.getEntityWorld();
        if (!(world instanceof ServerWorld serverWorld)) {
            return null;
        }
        var bbox = bot.getBoundingBox().expand(BOBBER_SEARCH_RADIUS, 6.0D, BOBBER_SEARCH_RADIUS);
        for (FishingBobberEntity bobber : serverWorld.getEntitiesByClass(FishingBobberEntity.class, bbox, entity -> {
            var owner = entity.getPlayerOwner();
            return owner != null && Objects.equals(owner.getUuid(), bot.getUuid());
        })) {
            if (!bobber.isRemoved()) {
                return bobber;
            }
        }
        return null;
    }

    private static int countFish(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int total = 0;
        for (Item fish : FISH_ITEMS) {
            total += countItem(bot, fish);
        }
        return total;
    }

    private record ArrowKeep(boolean keepSpectral, int bestNormalArrowCount) {}

    private static ArrowKeep computeArrowKeep(ServerPlayerEntity bot) {
        if (bot == null) {
            return new ArrowKeep(false, 0);
        }
        boolean hasSpectral = false;
        int bestNormalCount = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (item == Items.SPECTRAL_ARROW) {
                hasSpectral = true;
            } else if (item == Items.ARROW) {
                bestNormalCount = Math.max(bestNormalCount, stack.getCount());
            }
        }
        return new ArrowKeep(hasSpectral, bestNormalCount);
    }

    private static boolean isArrowStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item == Items.ARROW || item == Items.SPECTRAL_ARROW) {
            return true;
        }
        // Avoid compile-time dependencies on other arrow item types; treat anything with "arrow" key as an arrow.
        String key = item.getTranslationKey();
        return key != null && key.toLowerCase(java.util.Locale.ROOT).contains("arrow");
    }

    private static boolean shouldStoreItem(ItemStack stack, ArrowKeep arrowKeep) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (ChestStoreService.isOffloadProtected(stack)) {
            return false;
        }
        Item item = stack.getItem();
        if (item == Items.FISHING_ROD) {
            return false;
        }
        if (item == Items.ROTTEN_FLESH) {
            return true;
        }
        if (stack.getComponents().get(net.minecraft.component.DataComponentTypes.CUSTOM_NAME) != null) {
            return false;
        }
        if (stack.getComponents().get(net.minecraft.component.DataComponentTypes.FOOD) != null) {
            return false;
        }
        if (stack.isDamageable()) {
            return false;
        }
        if (isArrowStack(stack)) {
            // Keep best arrows: always keep spectral arrows; otherwise keep the largest normal arrow stack.
            if (item == Items.SPECTRAL_ARROW) {
                return !arrowKeep.keepSpectral();
            }
            if (item == Items.ARROW) {
                return stack.getCount() < arrowKeep.bestNormalArrowCount();
            }
            // Unknown arrow type: default to keeping it (likely "best" tipped arrows).
            return false;
        }

        // Default: store almost everything to free space (safer than stopping).
        return true;
    }

    private static boolean isInventoryFull(ServerPlayerEntity bot) {
        return bot != null && bot.getInventory().getEmptySlot() == -1;
    }

    private static int countItem(ServerPlayerEntity bot, Item item) {
        if (bot == null || item == null) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            Item stackItem = bot.getInventory().getStack(i).getItem();
            if (stackItem == item) {
                sum += bot.getInventory().getStack(i).getCount();
            }
        }
        return sum;
    }

    private static boolean hasItem(ServerPlayerEntity bot, Item item) {
        return countItem(bot, item) > 0;
    }

    private static int getIntParameter(Map<String, Object> params, String key, int defaultValue) {
        if (params == null || key == null) {
            return defaultValue;
        }
        Object raw = params.get(key);
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (raw instanceof String str) {
            try {
                return Math.max(0, Integer.parseInt(str));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
    
    private static boolean isUntilSunset(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        if (params.containsKey("until_sunset")) {
             Object val = params.get("until_sunset");
             if (val instanceof Boolean b) return b;
             if (val instanceof String s) return Boolean.parseBoolean(s);
        }
        if (params.containsKey("options") && params.get("options") instanceof List<?> list) {
            for (Object obj : list) {
                if (obj instanceof String s) {
                    if (s.equalsIgnoreCase("until_sunset") || s.equalsIgnoreCase("sunset")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------- Failed Target Blacklist Helpers --------

    /**
     * Marks a position as unreachable for this bot, preventing oscillation.
     */
    private static void blacklistTarget(UUID botUuid, BlockPos pos) {
        if (botUuid == null || pos == null) {
            return;
        }
        Map<BlockPos, Long> perBot = FAILED_TARGET_BLACKLIST.computeIfAbsent(botUuid, __ -> new ConcurrentHashMap<>());
        perBot.put(pos.toImmutable(), System.currentTimeMillis() + BLACKLIST_DURATION_MS);
        LOGGER.info("Blacklisted fishing target {} for {} ms", pos.toShortString(), BLACKLIST_DURATION_MS);
        
        // Evict oldest entries if too large.
        if (perBot.size() > MAX_BLACKLIST_SIZE) {
            long oldest = Long.MAX_VALUE;
            BlockPos oldestKey = null;
            for (var entry : perBot.entrySet()) {
                if (entry.getValue() < oldest) {
                    oldest = entry.getValue();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                perBot.remove(oldestKey);
            }
        }
    }

    /**
     * Removes expired blacklist entries.
     */
    private static void cleanupBlacklist(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        Map<BlockPos, Long> perBot = FAILED_TARGET_BLACKLIST.get(botUuid);
        if (perBot == null || perBot.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        perBot.entrySet().removeIf(e -> now >= e.getValue());
        if (perBot.isEmpty()) {
            FAILED_TARGET_BLACKLIST.remove(botUuid);
        }
    }

    /**
     * Checks if a position is currently blacklisted.
     */
    private static boolean isBlacklisted(UUID botUuid, BlockPos pos) {
        if (botUuid == null || pos == null) {
            return false;
        }
        Map<BlockPos, Long> perBot = FAILED_TARGET_BLACKLIST.get(botUuid);
        if (perBot == null) {
            return false;
        }
        Long until = perBot.get(pos.toImmutable());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            perBot.remove(pos.toImmutable());
            return false;
        }
        return true;
    }
}
