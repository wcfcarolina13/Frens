package net.shasankp000.GameAI.services.construction;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handles roof access, walking, and escape for construction tasks.
 * Extracted from HovelPerimeterBuilder for reuse across schematic builders.
 *
 * <p>Key behaviors:</p>
 * <ul>
 *   <li>Pillar up to reach roof level from safe positions</li>
 *   <li>Walk perimeter and serpentine patterns on roof</li>
 *   <li>Escape from roof back to ground safely</li>
 *   <li>Track and cleanup roof access pillars</li>
 * </ul>
 */
public final class RoofAccessService {

    private static final Logger LOGGER = LoggerFactory.getLogger("roof-access-service");
    private static final double REACH_DISTANCE_SQ = 20.25D;

    /**
     * Tracks temporary pillars built for roof access.
     */
    public record RoofAccessPillar(
            BlockPos base,
            int height,
            List<BlockPos> scaffoldBlocks
    ) {}

    private RoofAccessService() {}

    /**
     * Per-bot tracking of roof access pillars.
     */
    private static final Map<UUID, List<RoofAccessPillar>> roofPillars = new HashMap<>();

    /**
     * Get roof pillar tracking for a bot.
     */
    public static List<RoofAccessPillar> getRoofPillars(ServerPlayerEntity bot) {
        return roofPillars.computeIfAbsent(bot.getUuid(), k -> new ArrayList<>());
    }

    /**
     * Clear roof pillar tracking.
     */
    public static void clearRoofPillars(ServerPlayerEntity bot) {
        roofPillars.remove(bot.getUuid());
    }

    /**
     * Build a temporary pillar to reach the roof.
     * 
     * @param world The world
     * @param source Command source
     * @param bot The bot
     * @param roofY The Y level of the roof
     * @param preferredBase Preferred position for the pillar base
     * @return The top standable position, or null if failed
     */
    public static BlockPos buildRoofAccessPillar(ServerWorld world,
                                                  ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  int roofY,
                                                  BlockPos preferredBase) {
        if (bot == null || world == null) {
            return null;
        }

        // Calculate how high we need to pillar
        int currentY = bot.getBlockPos().getY();
        int targetStandY = roofY + 1; // Stand on top of roof
        int stepsNeeded = Math.max(0, targetStandY - currentY);

        if (stepsNeeded == 0) {
            return bot.getBlockPos();
        }

        if (stepsNeeded > 12) {
            LOGGER.warn("Roof too high for pillaring: need {} steps", stepsNeeded);
            return null;
        }

        // Move to preferred base if provided
        if (preferredBase != null && !bot.getBlockPos().equals(preferredBase)) {
            moveToPosition(source, bot, preferredBase);
        }

        BlockPos base = bot.getBlockPos();
        
        // Pillar up
        BotActions.stop(bot);
        sleepQuiet(100L);
        
        // Enable sneak for stability
        bot.setSneaking(true);
        
        List<BlockPos> scaffolds = ScaffoldService.pillarUpWithPositions(bot, stepsNeeded);
        
        if (scaffolds.isEmpty()) {
            bot.setSneaking(false);
            LOGGER.warn("Failed to build roof access pillar at {}", base.toShortString());
            return null;
        }

        // Record the pillar for later cleanup
        RoofAccessPillar pillar = new RoofAccessPillar(base, scaffolds.size(), scaffolds);
        getRoofPillars(bot).add(pillar);

        LOGGER.info("Built roof access pillar at {} (height={})", base.toShortString(), scaffolds.size());
        
        return bot.getBlockPos();
    }

    /**
     * Walk a perimeter pattern on the roof, placing blocks within reach at each step.
     * 
     * @param world The world
     * @param source Command source
     * @param bot The bot
     * @param roofCenter Center of the roof
     * @param roofRadius Radius of the roof
     * @param roofY Y level of the roof blocks
     * @param placeCallback Called at each step with reachable positions to place
     */
    public static void walkRoofPerimeter(ServerWorld world,
                                         ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         BlockPos roofCenter,
                                         int roofRadius,
                                         int roofY,
                                         PlaceCallback placeCallback) {
        if (bot == null || placeCallback == null) {
            return;
        }

        int standY = roofY + 1; // Walk on top of roof
        List<BlockPos> perimeter = buildRoofPerimeter(roofCenter, roofRadius, standY);

        for (BlockPos walkPos : perimeter) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            // Move to this position on the roof
            directWalkTo(bot, walkPos);
            sleepQuiet(100L);

            // Call back to let the builder place blocks from here
            placeCallback.onPosition(walkPos);
        }
    }

    /**
     * Walk a serpentine pattern across the roof interior.
     */
    public static void walkRoofSerpentine(ServerWorld world,
                                          ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          BlockPos roofCenter,
                                          int roofRadius,
                                          int roofY,
                                          PlaceCallback placeCallback) {
        if (bot == null || placeCallback == null) {
            return;
        }

        int standY = roofY + 1;
        int cx = roofCenter.getX();
        int cz = roofCenter.getZ();

        // Serpentine: alternate direction each row
        for (int dz = -roofRadius; dz <= roofRadius; dz++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            int startX = (dz % 2 == 0) ? -roofRadius : roofRadius;
            int endX = (dz % 2 == 0) ? roofRadius : -roofRadius;
            int stepX = (dz % 2 == 0) ? 1 : -1;

            for (int dx = startX; stepX > 0 ? dx <= endX : dx >= endX; dx += stepX) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    break;
                }

                BlockPos walkPos = new BlockPos(cx + dx, standY, cz + dz);
                directWalkTo(bot, walkPos);
                sleepQuiet(80L);
                placeCallback.onPosition(walkPos);
            }
        }
    }

    /**
     * Descend from the roof back to ground level safely.
     * Uses the roof access pillar if available, otherwise finds a safe drop.
     */
    public static boolean descendFromRoof(ServerWorld world,
                                          ServerCommandSource source,
                                          ServerPlayerEntity bot,
                                          int groundY) {
        if (bot == null || world == null) {
            return false;
        }

        List<RoofAccessPillar> pillars = getRoofPillars(bot);
        
        // If we have a tracked pillar, use it to descend
        if (!pillars.isEmpty()) {
            RoofAccessPillar pillar = pillars.get(pillars.size() - 1);
            
            // Walk to pillar top
            BlockPos pillarTop = pillar.base.up(pillar.height);
            directWalkTo(bot, pillarTop);
            sleepQuiet(200L);
            
            // Tear down the pillar (we'll fall with it)
            teardownRoofPillar(bot, world, pillar);
            
            return bot.getBlockPos().getY() <= groundY + 2;
        }

        // Otherwise, find safe drop or use stairs
        // Look for a position that's safe to drop to
        LOGGER.info("No roof pillar tracked, attempting safe descent");
        
        // Try to find an edge and drop (would need more logic for real safety)
        return bot.getBlockPos().getY() <= groundY + 2;
    }

    /**
     * Tear down a specific roof access pillar.
     */
    public static int teardownRoofPillar(ServerPlayerEntity bot, ServerWorld world, RoofAccessPillar pillar) {
        if (pillar == null || pillar.scaffoldBlocks.isEmpty()) {
            return 0;
        }

        int removed = ScaffoldService.teardownScaffolds(bot, world, pillar.scaffoldBlocks, Collections.emptySet());
        getRoofPillars(bot).remove(pillar);
        
        LOGGER.info("Tore down roof pillar at {}: {} blocks removed", 
                pillar.base.toShortString(), removed);
        
        return removed;
    }

    /**
     * Cleanup all tracked roof access pillars.
     */
    public static int cleanupAllRoofPillars(ServerPlayerEntity bot, ServerWorld world) {
        List<RoofAccessPillar> pillars = getRoofPillars(bot);
        int totalRemoved = 0;
        
        // Remove in reverse order (newest first)
        List<RoofAccessPillar> copy = new ArrayList<>(pillars);
        Collections.reverse(copy);
        
        for (RoofAccessPillar pillar : copy) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            totalRemoved += teardownRoofPillar(bot, world, pillar);
        }
        
        clearRoofPillars(bot);
        return totalRemoved;
    }

    // ========== Callback Interface ==========

    @FunctionalInterface
    public interface PlaceCallback {
        void onPosition(BlockPos standPosition);
    }

    // ========== Helper Methods ==========

    private static List<BlockPos> buildRoofPerimeter(BlockPos center, int radius, int y) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX();
        int cz = center.getZ();

        // East edge (north -> south)
        for (int dz = -radius; dz <= radius; dz++) {
            out.add(new BlockPos(cx + radius, y, cz + dz));
        }
        // South edge (east-1 -> west)
        for (int dx = radius - 1; dx >= -radius; dx--) {
            out.add(new BlockPos(cx + dx, y, cz + radius));
        }
        // West edge (south-1 -> north)
        for (int dz = radius - 1; dz >= -radius; dz--) {
            out.add(new BlockPos(cx - radius, y, cz + dz));
        }
        // North edge (west+1 -> east-1)
        for (int dx = -radius + 1; dx <= radius - 1; dx++) {
            out.add(new BlockPos(cx + dx, y, cz - radius));
        }

        // Remove duplicates while preserving order
        LinkedHashSet<BlockPos> uniq = new LinkedHashSet<>(out);
        return new ArrayList<>(uniq);
    }

    private static void directWalkTo(ServerPlayerEntity bot, BlockPos target) {
        Vec3d targetVec = Vec3d.ofCenter(target);
        LookController.faceBlock(bot, target);
        
        // Simple walk toward target
        long start = System.currentTimeMillis();
        while (bot.getBlockPos().getSquaredDistance(target) > 1 && 
               (System.currentTimeMillis() - start) < 3000L) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }
            BotActions.moveForward(bot);
            sleepQuiet(50L);
        }
        BotActions.stop(bot);
    }

    private static boolean moveToPosition(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                bot, target, MovementService.MovementOptions.skillLoot());
        
        if (plan.isEmpty()) {
            return false;
        }

        MovementService.MovementResult result = MovementService.execute(source, bot, plan.get(), false, true);
        return result.success();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
