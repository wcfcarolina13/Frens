package net.shasankp000.GameAI.services.construction;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handles scaffolding (pillaring) operations for construction.
 * Extracted from HovelPerimeterBuilder to be reusable across all construction skills.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>pillarUp - jump-place to build a temporary column</li>
 *   <li>teardownScaffolding - break scaffold blocks from top down</li>
 *   <li>scaffold memory - track placed scaffolds for later cleanup</li>
 * </ul>
 */
public final class ScaffoldService {

    private static final Logger LOGGER = LoggerFactory.getLogger("scaffold-service");

    // Blocks suitable for scaffolding (cheap, easily broken)
    public static final List<Item> SCAFFOLD_BLOCKS = List.of(
            Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK
    );

    private static final int MAX_SCAFFOLD_HEIGHT = 12;
    private static final long JUMP_TIMEOUT_MS = 800L;
    private static final long LAND_TIMEOUT_MS = 1200L;

    private ScaffoldService() {}

    /**
     * Tracked scaffold positions per bot (UUID -> positions).
     * This allows multiple bots to build simultaneously without interference.
     */
    private static final Map<UUID, Set<BlockPos>> scaffoldMemory = new HashMap<>();

    /**
     * Get or create the scaffold memory set for a bot.
     */
    public static Set<BlockPos> getScaffoldMemory(ServerPlayerEntity bot) {
        return scaffoldMemory.computeIfAbsent(bot.getUuid(), k -> new HashSet<>());
    }

    /**
     * Clear scaffold memory for a bot.
     */
    public static void clearScaffoldMemory(ServerPlayerEntity bot) {
        scaffoldMemory.remove(bot.getUuid());
    }

    /**
     * Pillar up by the specified number of blocks using jump-place technique.
     *
     * @param bot The bot to pillar
     * @param steps Number of blocks to pillar up
     * @param trackForTeardown Whether to track these positions for later teardown
     * @return true if successfully pillared up, false otherwise
     */
    public static boolean pillarUp(ServerPlayerEntity bot, int steps, boolean trackForTeardown) {
        if (bot == null || steps <= 0 || steps > MAX_SCAFFOLD_HEIGHT) {
            return steps <= 0;
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        Set<BlockPos> memory = getScaffoldMemory(bot);

        LOGGER.debug("Pillaring up {} blocks for {}", steps, bot.getGameProfile().name());

        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }

            // Stop horizontal movement
            BotActions.stop(bot);
            sleepQuiet(60L);

            // Wait to be on ground
            if (!waitForOnGround(bot, LAND_TIMEOUT_MS)) {
                LOGGER.warn("Not on ground for pillar step {}", i);
                return false;
            }

            BlockPos targetPos = bot.getBlockPos();
            double startY = bot.getY();

            // Jump
            BotActions.jump(bot);

            // Wait until airborne
            if (!waitForAirborne(bot, JUMP_TIMEOUT_MS)) {
                LOGGER.warn("Failed to become airborne for pillar step {}", i);
                return false;
            }

            // Wait for good placement window (near apex of jump)
            waitForJumpPlaceWindow(bot, startY, 600L);

            // Place scaffold block
            boolean placed = false;
            for (int attempt = 0; attempt < 3 && !placed; attempt++) {
                placed = BotActions.placeBlockAt(bot, targetPos, Direction.UP, SCAFFOLD_BLOCKS);
                if (!placed) sleepQuiet(50L);
            }

            if (!placed) {
                LOGGER.warn("Failed to place scaffold block at {}", targetPos.toShortString());
                return false;
            }

            // Track scaffold for later removal
            if (trackForTeardown) {
                memory.add(targetPos.toImmutable());
            }

            // Wait for bot to land on the new block
            waitForYIncrease(bot, targetPos.getY(), 1000L);
        }

        return true;
    }

    /**
     * Pillar up and return the list of positions placed.
     */
    public static List<BlockPos> pillarUpWithPositions(ServerPlayerEntity bot, int steps) {
        List<BlockPos> positions = new ArrayList<>();
        
        if (bot == null || steps <= 0 || steps > MAX_SCAFFOLD_HEIGHT) {
            return positions;
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();

        for (int i = 0; i < steps; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            BotActions.stop(bot);
            sleepQuiet(60L);

            if (!waitForOnGround(bot, LAND_TIMEOUT_MS)) {
                break;
            }

            BlockPos targetPos = bot.getBlockPos();
            double startY = bot.getY();

            BotActions.jump(bot);

            if (!waitForAirborne(bot, JUMP_TIMEOUT_MS)) {
                break;
            }

            waitForJumpPlaceWindow(bot, startY, 600L);

            boolean placed = false;
            for (int attempt = 0; attempt < 3 && !placed; attempt++) {
                placed = BotActions.placeBlockAt(bot, targetPos, Direction.UP, SCAFFOLD_BLOCKS);
                if (!placed) sleepQuiet(50L);
            }

            if (!placed) {
                break;
            }

            positions.add(targetPos.toImmutable());
            waitForYIncrease(bot, targetPos.getY(), 1000L);
        }

        return positions;
    }

    /**
     * Tear down scaffold blocks that were tracked.
     * Removes from top to bottom and clears memory.
     *
     * @param bot The bot doing the teardown
     * @return Number of blocks removed
     */
    public static int teardownTrackedScaffolds(ServerPlayerEntity bot) {
        Set<BlockPos> memory = scaffoldMemory.get(bot.getUuid());
        if (memory == null || memory.isEmpty()) {
            return 0;
        }

        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        int removed = teardownScaffolds(bot, world, new ArrayList<>(memory), Collections.emptySet());
        memory.clear();
        return removed;
    }

    /**
     * Tear down specific scaffold positions.
     * 
     * @param bot The bot doing the teardown
     * @param world The world
     * @param scaffolds List of positions to tear down
     * @param keepBlocks Positions to NOT tear down (e.g., if they became part of the structure)
     * @return Number of blocks removed
     */
    public static int teardownScaffolds(ServerPlayerEntity bot, ServerWorld world,
                                         List<BlockPos> scaffolds, Set<BlockPos> keepBlocks) {
        if (scaffolds.isEmpty()) {
            return 0;
        }

        // Sort from top to bottom for safe teardown
        List<BlockPos> sorted = new ArrayList<>(scaffolds);
        sorted.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        int removed = 0;
        for (BlockPos pos : sorted) {
            if (SkillManager.shouldAbortSkill(bot)) {
                break;
            }

            // Skip if in keep set
            if (keepBlocks != null && keepBlocks.contains(pos)) {
                continue;
            }

            var state = world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }

            // Check if we're standing on this block
            BlockPos botPos = bot.getBlockPos();
            if (botPos.equals(pos.up())) {
                // We're standing on it - let gravity work
                sleepQuiet(100L);
            }

            // Only break scaffold-type blocks
            boolean isScaffoldType = SCAFFOLD_BLOCKS.stream()
                    .anyMatch(item -> state.getBlock().asItem().equals(item));
            
            if (isScaffoldType) {
                boolean broke = world.breakBlock(pos, true);
                if (broke) {
                    removed++;
                    sleepQuiet(50L);
                }
            }
        }

        return removed;
    }

    /**
     * Get the height a bot needs to scaffold to reach a target position.
     * Accounts for eye height and reach distance.
     */
    public static int calculateScaffoldHeight(ServerPlayerEntity bot, BlockPos target) {
        int currentY = bot.getBlockPos().getY();
        // Bot's eyes are at Y+1.62, reach is ~4.5 blocks
        // To reach a block at targetY, we need bot at approximately targetY-3
        int optimalY = target.getY() - 2;
        return Math.max(0, optimalY - currentY);
    }

    // ========== Helper Methods ==========

    private static boolean waitForOnGround(ServerPlayerEntity bot, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (!bot.isOnGround() && (System.currentTimeMillis() - start) < timeoutMs) {
            sleepQuiet(20L);
        }
        return bot.isOnGround();
    }

    private static boolean waitForAirborne(ServerPlayerEntity bot, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (bot.isOnGround() && (System.currentTimeMillis() - start) < timeoutMs) {
            sleepQuiet(20L);
        }
        return !bot.isOnGround();
    }

    private static void waitForJumpPlaceWindow(ServerPlayerEntity bot, double startY, long maxWaitMs) {
        long start = System.currentTimeMillis();
        double lastY = bot.getY();
        
        while ((System.currentTimeMillis() - start) < maxWaitMs) {
            sleepQuiet(20L);
            double currentY = bot.getY();
            
            // Wait until we're at least 0.5 blocks up and velocity is slowing
            if (currentY > startY + 0.5 && currentY <= lastY + 0.05) {
                break; // Near apex
            }
            lastY = currentY;
        }
    }

    private static void waitForYIncrease(ServerPlayerEntity bot, int targetBlockY, long maxWaitMs) {
        long start = System.currentTimeMillis();
        while (bot.getBlockPos().getY() <= targetBlockY && (System.currentTimeMillis() - start) < maxWaitMs) {
            sleepQuiet(30L);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
