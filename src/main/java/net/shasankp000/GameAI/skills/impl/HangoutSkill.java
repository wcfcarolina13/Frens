package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.BlockInteractionService;
import net.shasankp000.GameAI.services.FollowPathService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * A simple ambient skill: stand around (optionally near a campfire) and idle for a while.
 *
 * <p>Intended for the "idle hobbies" system; it's open-ended and safe to interrupt.
 */
public final class HangoutSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-hangout");

    private static final Random RNG = new Random();
    private static final int SUNSET_TIME_OF_DAY = 12_000;
    private static final int CAMPFIRE_SEARCH_RADIUS = 24;
    // Never stand on/adjacent-to campfires during hangout.
    private static final int CAMPFIRE_MIN_STAND_RADIUS = 2;
    private static final int CAMPFIRE_MAX_STAND_RADIUS = 5;

    @Override
    public String name() {
        return "hangout";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");

        int durationSec = getInt(context.parameters(), "duration_sec", 75);
        durationSec = Math.max(15, Math.min(durationSec, 240));

        boolean stopAtSunset = getBoolean(context.parameters(), "until_sunset", true);

        // Safety: if we somehow start on a campfire (e.g., restored location), nudge off immediately.
        try {
            net.shasankp000.GameAI.services.BotCampfireAvoidanceService.tryEscapeCampfire(bot);
        } catch (Throwable ignored) {
        }

        // Try to find a nearby campfire (optional).
        // NOTE: we prefer a campfire we can actually "see" from a safe stand tile so the bot
        // doesn't pick a spot inside a wall/house while the fire is just outside.
        BlockPos campfire = findNearbyCampfire(bot, CAMPFIRE_SEARCH_RADIUS);
        List<BlockPos> standCandidates = List.of();
        BlockPos desiredStand = null;
        
        // Capture reachability snapshot for filtering candidates.
        FollowPathService.FollowSnapshot reachabilitySnapshot = null;
        if (bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos origin = bot.getBlockPos();
            reachabilitySnapshot = FollowPathService.capture(sw, origin, origin, true);
        }
        
        if (campfire != null) {
            standCandidates = findStandCandidatesNearCampfire(bot, campfire, 6);
            
            // Filter candidates by reachability to prevent oscillation on unreachable targets.
            if (reachabilitySnapshot != null && !standCandidates.isEmpty()) {
                BlockPos origin = bot.getBlockPos();
                final FollowPathService.FollowSnapshot snap = reachabilitySnapshot;
                List<BlockPos> reachable = new ArrayList<>();
                for (BlockPos cand : standCandidates) {
                    boolean alreadyThere = origin.getSquaredDistance(cand) <= 2.25D;
                    if (alreadyThere) {
                        reachable.add(cand);
                    } else if (snap.inBounds(cand) && FollowPathService.isReachable(snap, cand)) {
                        reachable.add(cand);
                    } else if (!snap.inBounds(cand)) {
                        // Out of snapshot bounds - can't verify, include anyway.
                        reachable.add(cand);
                    } else {
                        LOGGER.debug("Hangout: filtering out unreachable stand candidate {}", cand.toShortString());
                    }
                }
                standCandidates = reachable;
            }
            
            desiredStand = standCandidates.isEmpty() ? null : standCandidates.get(0);

            // Fallback: if LoS-based selection yields nothing (common when the bot is in a room and the
            // campfire is outside), still try to move to a reasonable stand tile near the campfire.
            if (desiredStand == null) {
                BlockPos fallbackStand = findStandNearCampfire(bot, campfire);
                if (fallbackStand != null) {
                    // Also check reachability of fallback.
                    boolean fallbackReachable = true;
                    if (reachabilitySnapshot != null && reachabilitySnapshot.inBounds(fallbackStand)) {
                        boolean alreadyThere = bot.getBlockPos().getSquaredDistance(fallbackStand) <= 2.25D;
                        fallbackReachable = alreadyThere || FollowPathService.isReachable(reachabilitySnapshot, fallbackStand);
                    }
                    if (fallbackReachable) {
                        desiredStand = fallbackStand;
                        standCandidates = List.of(fallbackStand);
                    }
                }
            }
        }

        if (campfire != null && desiredStand != null) {
            ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                    "I'll hang out by the fire for a bit.", true);
                // Try a few candidates so we don't get stuck choosing a "good" tile that is
                // technically standable but separated from the fire by a wall/door.
                boolean reached = false;
                for (int i = 0; i < Math.min(standCandidates.size(), 4); i++) {
                    BlockPos cand = standCandidates.get(i);
                    if (attemptMoveToStand(source, bot, cand, i == 0 ? "hangout-init" : "hangout-init-alt" + i)) {
                        desiredStand = cand;
                        reached = true;
                        break;
                    }
                }
                if (!reached) {
                    // Keep the original desiredStand for repath retries.
                }
        } else if (campfire != null) {
            ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                    "I'll hang out for a bit.", true);
            LOGGER.info("Hangout: campfire found at {} but no safe stand tile found", campfire.toShortString());
        } else {
            ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                    "Taking a short breather.", true);
        }

        long nextRepathMs = System.currentTimeMillis() + 3_500L;

        long endMs = System.currentTimeMillis() + durationSec * 1000L;
        while (System.currentTimeMillis() < endMs) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Hangout paused by another task.");
            }

            // If we promised to hang out by a specific spot, keep trying (best-effort) to reach it.
            // This helps after world reloads where the first move attempt may happen during chunk settling.
            if (desiredStand != null && System.currentTimeMillis() >= nextRepathMs) {
                double distSq = bot.getBlockPos().getSquaredDistance(desiredStand);
                // IMPORTANT: distance alone isn't sufficient near doors/walls.
                // If we're "close" but separated by a door, continue to commit to crossing.
                BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, desiredStand, 12.0D);

                // Re-attempt if we're not actually close enough to feel like "by the fire",
                // or if a blocking doorway still exists between us and the stand.
                if (distSq > 2.25D || blockingDoor != null) {
                    if (blockingDoor != null) {
                        MovementService.tryOpenDoorAt(bot, blockingDoor);
                        MovementService.tryTraverseOpenableToward(bot, blockingDoor, desiredStand, "hangout-door");
                    }
                    attemptMoveToStand(source, bot, desiredStand, "hangout-retry");
                }
                nextRepathMs = System.currentTimeMillis() + 3_500L + RNG.nextInt(2_000);
            }

            if (stopAtSunset && bot.getEntityWorld() instanceof ServerWorld world) {
                int tod = (int) (world.getTimeOfDay() % 24_000L);
                if (tod >= SUNSET_TIME_OF_DAY && tod < 23_000) {
                    ChatUtils.sendSystemMessage(source, "It's getting late; heading home.");
                    break;
                }
            }

            // Don't drift.
            BotActions.stop(bot);

            // Absolute rule: never linger on campfires.
            try {
                net.shasankp000.GameAI.services.BotCampfireAvoidanceService.tryEscapeCampfire(bot);
            } catch (Throwable ignored) {
            }

            // Very small flavor: occasionally look around.
            if (RNG.nextDouble() < 0.15D) {
                float yaw = bot.getYaw() + (float) ((RNG.nextDouble() - 0.5D) * 60.0D);
                bot.setYaw(yaw);
            }

            sleep(650L);
        }

        return SkillExecutionResult.success("Done hanging out.");
    }

    private static boolean attemptMoveToStand(ServerCommandSource source, ServerPlayerEntity bot, BlockPos stand, String label) {
        if (source == null || bot == null || stand == null) {
            return false;
        }
        try {
            // Hangout should be gentle and deterministic: just walk to the tile.
            // (Avoid loot-style snap/bridge heuristics for a simple "stand here" move.)
            MovementService.MovementPlan plan = new MovementService.MovementPlan(MovementService.Mode.DIRECT, stand, stand, null, null, null);
            MovementService.MovementResult res = MovementService.execute(
                    source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                    bot,
                    plan,
                    Boolean.FALSE,
                    true,
                    false,
                    false
            );

            // If we ended close but a door is still in the way, do a deterministic doorway commit and re-check.
            if (res != null) {
                BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, stand, 12.0D);
                double distSq = bot.getBlockPos().getSquaredDistance(stand);
                if (blockingDoor != null && distSq <= 16.0D) {
                    MovementService.tryOpenDoorAt(bot, blockingDoor);
                    MovementService.tryTraverseOpenableToward(bot, blockingDoor, stand, "hangout-stand-door");
                    MovementService.nudgeTowardUntilClose(bot, stand, 2.25D, 2200L, 0.18, "hangout-stand-door");
                }
            }
            LOGGER.info("Hangout move [{}]: target={} success={} arrivedAt={} detail='{}'",
                    label,
                    stand.toShortString(),
                    res != null && res.success(),
                    res != null && res.arrivedAt() != null ? res.arrivedAt().toShortString() : "null",
                    res != null ? res.detail() : "null");

            // Verify we are actually "by" the stand and not separated by a doorway/wall.
            double distSq = bot.getBlockPos().getSquaredDistance(stand);
            BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, stand, 12.0D);
            return distSq <= 2.25D && blockingDoor == null;
        } catch (Throwable t) {
            // Hangout is best-effort; if pathing fails we still idle in place.
            LOGGER.debug("Hangout move [{}] errored: {}", label, t.getMessage());
            return false;
        }
    }

    private static int getInt(Map<String, Object> params, String key, int fallback) {
        if (params == null || key == null) {
            return fallback;
        }
        Object raw = params.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean getBoolean(Map<String, Object> params, String key, boolean fallback) {
        if (params == null || key == null) {
            return fallback;
        }
        Object raw = params.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return fallback;
    }

    private static BlockPos findNearbyCampfire(ServerPlayerEntity bot, int radius) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        int r = Math.max(2, radius);

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean bestLit = false;

        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!(state.isOf(net.minecraft.block.Blocks.CAMPFIRE) || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE))) {
                continue;
            }
            boolean lit = state.contains(Properties.LIT) && Boolean.TRUE.equals(state.get(Properties.LIT));

            // Prefer campfires where we can find at least one "see-the-fire" stand candidate.
            List<BlockPos> stands = findStandCandidatesNearCampfire(bot, pos, 1);
            if (stands.isEmpty()) {
                continue;
            }

            double distSq = pos.getSquaredDistance(origin);
            double score = distSq + (lit ? 0.0D : 64.0D); // unlit fires are less "hangout-worthy"
            if (best == null
                    || score < bestScore - 0.001D
                    || (Math.abs(score - bestScore) <= 0.001D && lit && !bestLit)) {
                best = pos.toImmutable();
                bestScore = score;
                bestLit = lit;
            }
        }

        // Fallback: nearest campfire even if we couldn't find a visible stand.
        if (best == null) {
            for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                BlockState state = world.getBlockState(pos);
                if (state.isOf(net.minecraft.block.Blocks.CAMPFIRE) || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE)) {
                    double distSq = pos.getSquaredDistance(origin);
                    if (best == null || distSq < bestScore) {
                        best = pos.toImmutable();
                        bestScore = distSq;
                    }
                }
            }
        }

        return best;
    }

    private static BlockPos findStandNearCampfire(ServerPlayerEntity bot, BlockPos campfire) {
        if (bot == null || campfire == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }

        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;

        for (int r = CAMPFIRE_MIN_STAND_RADIUS; r <= CAMPFIRE_MAX_STAND_RADIUS; r++) {
            for (BlockPos pos : BlockPos.iterate(campfire.add(-r, -1, -r), campfire.add(r, 1, r))) {
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                if (Math.abs(pos.getY() - campfire.getY()) > 1) {
                    continue;
                }
                if (pos.getSquaredDistance(campfire) < (double) (CAMPFIRE_MIN_STAND_RADIUS * CAMPFIRE_MIN_STAND_RADIUS)) {
                    continue;
                }
                if (!isStandable(world, pos)) {
                    continue;
                }
                // Avoid being too close to any campfire tile (including the chosen one).
                if (isTooCloseToAnyCampfire(world, pos, 2)) {
                    continue;
                }
                double d = pos.getSquaredDistance(campfire);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.toImmutable();
                }
            }
            if (best != null) {
                return best;
            }
        }

        return null;
    }

    private static List<BlockPos> findStandCandidatesNearCampfire(ServerPlayerEntity bot, BlockPos campfire, int maxCandidates) {
        if (bot == null || campfire == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return List.of();
        }
        int max = Math.max(1, maxCandidates);

        ArrayList<BlockPos> candidates = new ArrayList<>(32);
        BlockPos botPos = bot.getBlockPos();

        for (int r = CAMPFIRE_MIN_STAND_RADIUS; r <= CAMPFIRE_MAX_STAND_RADIUS; r++) {
            for (BlockPos pos : BlockPos.iterate(campfire.add(-r, -1, -r), campfire.add(r, 1, r))) {
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                if (Math.abs(pos.getY() - campfire.getY()) > 1) {
                    continue;
                }
                if (pos.getSquaredDistance(campfire) < (double) (CAMPFIRE_MIN_STAND_RADIUS * CAMPFIRE_MIN_STAND_RADIUS)) {
                    continue;
                }
                if (!isStandable(world, pos)) {
                    continue;
                }
                if (isTooCloseToAnyCampfire(world, pos, 2)) {
                    continue;
                }
                // Prefer stands that can actually see the fire (avoid "inside the wall" stands).
                if (!hasLineOfSightToCampfire(bot, pos, campfire)) {
                    continue;
                }
                candidates.add(pos.toImmutable());
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // Prefer: close to campfire, but also not ridiculously far/awkward from bot.
        candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(campfire) + 0.25D * p.getSquaredDistance(botPos)));

        if (candidates.size() > max) {
            return candidates.subList(0, max);
        }
        return candidates;
    }

    private static boolean hasLineOfSightToCampfire(ServerPlayerEntity bot, BlockPos standFeet, BlockPos campfire) {
        if (bot == null || standFeet == null || campfire == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        // Rough "eye" from the stand tile, and a target point slightly above the campfire.
        Vec3d from = Vec3d.ofCenter(standFeet).add(0.0D, 1.25D, 0.0D);
        Vec3d to = Vec3d.ofCenter(campfire).add(0.0D, 0.45D, 0.0D);
        HitResult hit = world.raycast(new RaycastContext(
                from,
                to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot
        ));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            BlockPos hitPos = bhr.getBlockPos();
            // Campfires are 1 block tall; accept hits on the campfire block itself.
            return hitPos.equals(campfire);
        }
        return false;
    }

    private static boolean isStandable(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null) {
            return false;
        }
        BlockPos head = feet.up();
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) {
            return false;
        }
        var feetState = world.getBlockState(feet);
        var headState = world.getBlockState(head);
        if (!feetState.getCollisionShape(world, feet).isEmpty()) {
            return false;
        }
        if (!headState.getCollisionShape(world, head).isEmpty()) {
            return false;
        }
        BlockPos below = feet.down();
        var belowState = world.getBlockState(below);
        return !belowState.getCollisionShape(world, below).isEmpty();
    }

    private static boolean isTooCloseToAnyCampfire(ServerWorld world, BlockPos pos, int radius) {
        int r = Math.max(0, radius);
        for (BlockPos p : BlockPos.iterate(pos.add(-r, -1, -r), pos.add(r, 1, r))) {
            if (!world.isChunkLoaded(p)) {
                continue;
            }
            var state = world.getBlockState(p);
            if (state.isOf(net.minecraft.block.Blocks.CAMPFIRE) || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE)) {
                return true;
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
}
