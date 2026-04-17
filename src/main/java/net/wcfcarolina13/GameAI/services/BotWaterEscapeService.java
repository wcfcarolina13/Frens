package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared shoreline escape helpers for tasks that require dry footing.
 */
public final class BotWaterEscapeService {

    private BotWaterEscapeService() {
    }

    public static boolean isInWater(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos feet = bot.getBlockPos();
        return bot.isTouchingWater()
                || bot.isSwimming()
                || bot.isSubmergedInWater()
                || world.getFluidState(feet).isIn(FluidTags.WATER)
                || world.getFluidState(feet.up()).isIn(FluidTags.WATER);
    }

    /**
     * Swim to a shore stand without the cliff-check that normal land nav uses.
     *
     * <p>Generic navigation ({@link net.wcfcarolina13.GameAI.services.MovementService}
     * and skill-level helpers like FishingSkill#safeNudge) refuse to step onto a
     * block whose below-neighbour has an empty collision shape, because that's
     * how they detect cliff edges.  A swimming bot sits on water — empty
     * collision shape — so every adjacent block looks like a cliff and the bot
     * gives up on reaching shore.
     *
     * <p>This helper drives swim-forward input + auto-jump directly, polling on
     * the skill worker thread.  Iterates multiple shore candidates so a single
     * unreachable stand doesn't abort the whole attempt.  Returns true when
     * {@link #isInWater} goes false.
     *
     * <p>Must be called from a worker thread (blocks via {@code Thread.sleep}).
     * Returns false if the bot is aborted, the timeout elapses, or no
     * candidate produces a water-exit.
     */
    public static boolean swimToShore(ServerPlayerEntity bot, int searchRadius, long totalTimeoutMs) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) return false;
        if (!isInWater(bot)) return true;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;

        List<BlockPos> candidates = collectShoreStandCandidates(bot, world, Math.max(3, searchRadius), 5);
        if (candidates.isEmpty()) return false;

        long overallDeadline = System.currentTimeMillis() + Math.max(1000L, totalTimeoutMs);
        long perCandidateMs = Math.max(1500L, totalTimeoutMs / Math.max(1, candidates.size()));

        for (BlockPos target : candidates) {
            if (System.currentTimeMillis() >= overallDeadline) break;
            if (bot.isRemoved() || !bot.isAlive()) return false;
            if (!isInWater(bot)) return true;

            if (swimTowardOnce(bot, target, perCandidateMs, overallDeadline)) {
                return true;
            }
        }
        return !isInWater(bot);
    }

    private static boolean swimTowardOnce(ServerPlayerEntity bot, BlockPos target,
                                          long perCandidateMs, long overallDeadline) {
        long deadline = Math.min(System.currentTimeMillis() + perCandidateMs, overallDeadline);
        int stagnantTicks = 0;
        double lastDistSq = Double.MAX_VALUE;

        while (System.currentTimeMillis() < deadline) {
            if (bot.isRemoved() || !bot.isAlive()) return false;
            if (!isInWater(bot)) return true;

            Vec3d targetCenter = Vec3d.ofCenter(target);
            LookController.faceBlock(bot, target);
            BotActions.sprint(bot, false); // no sprint — swim is slow, sprint-swim can overshoot
            // Full forward impulse.  Unlike safeNudge we intentionally skip the
            // "is block-below empty?" cliff check — we're already in water, so
            // the concept doesn't apply.
            BotActions.applyMovementInput(bot, targetCenter, 1.0D);
            // Auto-jump handles climbing onto the shore block once we brush it.
            BotActions.autoJumpIfNeeded(bot);

            try {
                Thread.sleep(100L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }

            double distSq = bot.getBlockPos().getSquaredDistance(target);
            if (distSq <= 2.25D) {
                // Arrived at the stand — one extra jump-assist in case we're
                // on the water column directly below the shore block.
                BotActions.autoJumpIfNeeded(bot);
                try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
                if (!isInWater(bot)) return true;
            }

            // Stuck-detection: if we haven't made meaningful progress for ~2s, bail to the next candidate.
            if (distSq >= lastDistSq - 0.01D) {
                stagnantTicks++;
                if (stagnantTicks >= 20) {
                    return false;
                }
            } else {
                stagnantTicks = 0;
            }
            lastDistSq = distSq;
        }
        return !isInWater(bot);
    }

    /**
     * Returns up to {@code maxCandidates} shore-adjacent dry stands, ordered by the same
     * shoreline-priority score used in {@link #findNearestShoreStand}.
     */
    private static List<BlockPos> collectShoreStandCandidates(ServerPlayerEntity bot, ServerWorld world,
                                                              int radius, int maxCandidates) {
        BlockPos origin = bot.getBlockPos();
        record Scored(BlockPos pos, double score) {}
        List<Scored> scored = new ArrayList<>();

        for (BlockPos candidate : BlockPos.iterate(origin.add(-radius, -3, -radius), origin.add(radius, 4, radius))) {
            if (!world.isChunkLoaded(candidate)) continue;
            if (!isStandable(world, candidate)) continue;

            int dx = candidate.getX() - origin.getX();
            int dz = candidate.getZ() - origin.getZ();
            int dy = candidate.getY() - origin.getY();
            double horizontalDistSq = dx * dx + dz * dz;
            double verticalPenalty = Math.abs(dy) * 1.4D;
            double shorelinePenalty = isAdjacentToWater(world, candidate) ? 0.0D : 7.0D;
            scored.add(new Scored(candidate.toImmutable(), horizontalDistSq + verticalPenalty + shorelinePenalty));
        }
        scored.sort(Comparator.comparingDouble(Scored::score));

        List<BlockPos> out = new ArrayList<>(Math.min(maxCandidates, scored.size()));
        for (int i = 0; i < Math.min(maxCandidates, scored.size()); i++) {
            out.add(scored.get(i).pos());
        }
        return out;
    }

    /**
     * Find nearby dry land while preferring shoreline-adjacent stands.
     */
    public static BlockPos findNearestShoreStand(ServerPlayerEntity bot, int radius) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return null;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        int r = Math.max(3, radius);

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (BlockPos candidate : BlockPos.iterate(origin.add(-r, -3, -r), origin.add(r, 4, r))) {
            if (!world.isChunkLoaded(candidate)) {
                continue;
            }
            if (!isStandable(world, candidate)) {
                continue;
            }

            int dx = candidate.getX() - origin.getX();
            int dz = candidate.getZ() - origin.getZ();
            int dy = candidate.getY() - origin.getY();
            double horizontalDistSq = dx * dx + dz * dz;
            double verticalPenalty = Math.abs(dy) * 1.4D;
            boolean shoreline = isAdjacentToWater(world, candidate);
            double shorelinePenalty = shoreline ? 0.0D : 7.0D;
            double score = horizontalDistSq + verticalPenalty + shorelinePenalty;

            if (score < bestScore) {
                bestScore = score;
                best = candidate.toImmutable();
            }
        }
        return best;
    }

    private static boolean isStandable(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        if (world.getFluidState(feet).isIn(FluidTags.WATER) || world.getFluidState(feet.up()).isIn(FluidTags.WATER)) {
            return false;
        }
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(feet.up());
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, feet.up()).isEmpty()) {
            return false;
        }
        BlockPos floor = feet.down();
        BlockState floorState = world.getBlockState(floor);
        return !floorState.getCollisionShape(world, floor).isEmpty();
    }

    private static boolean isAdjacentToWater(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(dir);
            if (!world.isChunkLoaded(side)) {
                continue;
            }
            if (world.getFluidState(side).isIn(FluidTags.WATER) || world.getFluidState(side.down()).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }
}
