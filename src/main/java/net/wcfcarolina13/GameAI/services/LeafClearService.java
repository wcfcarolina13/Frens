package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.LeavesBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaf-obstruction handling for bot movement.
 *
 * <p>Moved verbatim out of {@link MovementService} (zero behaviour change). MovementService keeps
 * one-line delegating wrappers so no call site had to change. The per-bot mining cooldown
 * (1200 ms) and bypass failure-streak state live here.</p>
 */
public final class LeafClearService {

    private static final Logger LOGGER = LoggerFactory.getLogger("leaf-clear-service");

    private LeafClearService() {}

    // Leaves are an extremely common "soft" obstruction (foliage). We want to avoid them first, and only
    // break a single natural leaf occasionally as a last resort. Also, never block the server thread.
    public static final long LEAF_MINE_GLOBAL_COOLDOWN_MS = 1200L;
    public static final Map<UUID, Long> LEAF_MINE_LAST_MS = new ConcurrentHashMap<>();
    public static final Map<UUID, CompletableFuture<String>> LEAF_MINE_INFLIGHT = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> LEAF_MINE_TARGET = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> LEAF_BYPASS_FAILURE_STREAK = new ConcurrentHashMap<>();
    public static final Map<UUID, BlockPos> LEAF_LAST_BYPASS_ORIGIN = new ConcurrentHashMap<>();
    public static final int LEAF_BYPASS_FORCE_MINE_THRESHOLD = 3;

    private enum LeafMineResult {
        NONE,
        STARTED,
        IN_PROGRESS
    }

    public static boolean hasLeafInImmediatePath(ServerWorld world, BlockPos start, Direction toward) {
        if (world == null || start == null || toward == null) {
            return false;
        }
        BlockPos front = start.offset(toward);
        // Only consider leaf blocks that are plausibly colliding with the bot's body/head.
        return isLeafBlock(world, front)
                || isLeafBlock(world, front.up())
                || isLeafBlock(world, front.up(2));
    }

    public static boolean isLeafBlock(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return world.getBlockState(pos).isIn(BlockTags.LEAVES);
    }

    public static boolean tryLeafBypassInputStep(ServerPlayerEntity bot, Direction toward) {
        if (bot == null || toward == null) {
            return false;
        }
        ServerWorld world = MovementService.getWorld(bot);
        if (world == null) {
            return false;
        }
        BlockPos start = bot.getBlockPos();
        Direction left = toward.rotateYCounterclockwise();
        Direction right = toward.rotateYClockwise();

        // Prefer diagonal-forward to slip around leaf hedges without breaking.
        BlockPos[] candidates = new BlockPos[] {
                start.offset(toward).offset(left),
                start.offset(toward).offset(right),
                start.offset(left),
                start.offset(right)
        };

        BlockPos best = null;
        for (BlockPos cand : candidates) {
            if (cand == null) {
                continue;
            }
            if (MovementService.isSolidStandable(world, cand.down(), cand)) {
                best = cand;
                break;
            }
        }
        if (best == null) {
            return false;
        }

        final BlockPos bestPos = best.toImmutable();
        final Vec3d bestCenter = Vec3d.ofCenter(bestPos);
        return MovementService.runOnServerThread(bot, () -> {
            LookController.faceBlock(bot, bestPos);
            BotActions.sprint(bot, false);
            BotActions.autoJumpIfNeeded(bot);
            BotActions.applyMovementInput(bot, bestCenter, 0.16);
        });
    }

    public static LeafMineResult startLeafMiningDetailed(ServerPlayerEntity bot, BlockPos leafPos, String label) {
        if (bot == null || leafPos == null) {
            return LeafMineResult.NONE;
        }
        UUID id = bot.getUuid();
        if (id == null) {
            return LeafMineResult.NONE;
        }

        CompletableFuture<String> inflight = LEAF_MINE_INFLIGHT.get(id);
        if (inflight != null && !inflight.isDone()) {
            BlockPos target = LEAF_MINE_TARGET.get(id);
            // If we're already mining this leaf, treat it as "handled".
            if (target != null && target.equals(leafPos)) {
                return LeafMineResult.IN_PROGRESS;
            }
            // Only mine one leaf at a time per bot.
            return LeafMineResult.NONE;
        }

        long now = System.currentTimeMillis();
        long last = LEAF_MINE_LAST_MS.getOrDefault(id, 0L);
        if (now - last < LEAF_MINE_GLOBAL_COOLDOWN_MS) {
            return LeafMineResult.NONE;
        }

        LEAF_MINE_LAST_MS.put(id, now);
        LEAF_MINE_TARGET.put(id, leafPos.toImmutable());
        selectHarmlessForLeaves(bot);

        // UX: when foliage is actually blocking us, show a short overhead line (not chat).
        CompanionOverheadDialogueService.tryShowLeafStuck(bot, label);

        LOGGER.debug("leaf-mine start [{}]: bot={} pos={}",
                label,
                bot.getName().getString(),
                leafPos.toShortString());

        CompletableFuture<String> future = MiningTool.mineBlock(bot, leafPos, true);
        LEAF_MINE_INFLIGHT.put(id, future);
        future.whenComplete((result, error) -> {
            LEAF_MINE_INFLIGHT.remove(id, future);
            LEAF_MINE_TARGET.remove(id);
        });
        return LeafMineResult.STARTED;
    }

    public static boolean startLeafMining(ServerPlayerEntity bot, BlockPos leafPos, String label) {
        LeafMineResult result = startLeafMiningDetailed(bot, leafPos, label);
        return result == LeafMineResult.STARTED || result == LeafMineResult.IN_PROGRESS;
    }

    public static boolean isBreakableLeaf(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        var state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.LEAVES)) {
            return false;
        }
        // Respect player-placed leaves: vanilla sets PERSISTENT=true when placed.
        if (state.contains(LeavesBlock.PERSISTENT) && Boolean.TRUE.equals(state.get(LeavesBlock.PERSISTENT))) {
            return false;
        }
        return true;
    }

    public static boolean isWithinReach(ServerPlayerEntity player, BlockPos pos) {
        if (player == null || pos == null) {
            return false;
        }
        return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= 20.25D; // ~4.5 blocks
    }

    public static void selectHarmlessForLeaves(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        // Prefer shears, otherwise an empty slot or a non-tool/weapon hotbar item.
        if (BotActions.selectBestTool(player, "shears", "")) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(player, i);
                return;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase();
            if (key.contains("sword") || key.contains("axe") || key.contains("pickaxe") || key.contains("shovel") || key.contains("hoe")) {
                continue;
            }
            BotActions.selectHotbarSlot(player, i);
            return;
        }
    }

    public static List<BlockPos> leafCandidates(BlockPos start, Direction toward) {
        if (start == null || toward == null) {
            return List.of();
        }
        // Prefer only the blocks that plausibly collide with the bot's body/head in the travel direction.
        // Avoid scanning high canopy blocks (which caused unnecessary leaf breaking).
        BlockPos front = start.offset(toward);
        BlockPos front2 = front.offset(toward);
        return List.of(
                front,
                front.up(),
                front.up(2),
                front2,
                front2.up(),
                front2.up(2),
                // If we're already wedged into leaves, try the body/head blocks too.
                start.up(),
                start.up(2)
        );
    }

    public static MovementService.LeafClearResult clearLeafObstructionDetailed(ServerPlayerEntity player, Direction toward) {
        if (player == null || toward == null) {
            return new MovementService.LeafClearResult(MovementService.LeafClearAction.NO_CLEAR, false, 0, null, "invalid-input");
        }
        ServerWorld world = MovementService.getWorld(player);
        if (world == null) {
            return new MovementService.LeafClearResult(MovementService.LeafClearAction.NO_CLEAR, false, 0, null, "no-world");
        }

        UUID id = player.getUuid();
        int bypassFailures = id != null ? LEAF_BYPASS_FAILURE_STREAK.getOrDefault(id, 0) : 0;
        boolean leafInPath = hasLeafInImmediatePath(world, player.getBlockPos(), toward);
        if (!leafInPath) {
            if (id != null) {
                LEAF_BYPASS_FAILURE_STREAK.remove(id);
                LEAF_LAST_BYPASS_ORIGIN.remove(id);
            }
            return new MovementService.LeafClearResult(MovementService.LeafClearAction.NO_OBSTRUCTION, false, 0, null, "no-leaf-in-path");
        }

        if (id != null) {
            BlockPos previousBypassOrigin = LEAF_LAST_BYPASS_ORIGIN.get(id);
            if (previousBypassOrigin != null && !previousBypassOrigin.equals(player.getBlockPos())) {
                LEAF_BYPASS_FAILURE_STREAK.remove(id);
                LEAF_LAST_BYPASS_ORIGIN.remove(id);
                return new MovementService.LeafClearResult(MovementService.LeafClearAction.BYPASS_PROGRESS, true, 0, null, "bypass-progress");
            }
            CompletableFuture<String> inflight = LEAF_MINE_INFLIGHT.get(id);
            if (inflight != null && !inflight.isDone()) {
                BlockPos target = LEAF_MINE_TARGET.get(id);
                return new MovementService.LeafClearResult(MovementService.LeafClearAction.MINING_IN_PROGRESS, true, bypassFailures, target, "inflight");
            }
        }

        // First try a non-destructive "route around" nudge. This is safe to run even on the server thread.
        boolean forceMineOnly = bypassFailures >= LEAF_BYPASS_FORCE_MINE_THRESHOLD;
        if (!forceMineOnly && tryLeafBypassInputStep(player, toward)) {
            int nextFailures = bypassFailures + 1;
            if (id != null) {
                LEAF_BYPASS_FAILURE_STREAK.put(id, nextFailures);
                LEAF_LAST_BYPASS_ORIGIN.put(id, player.getBlockPos().toImmutable());
            }
            return new MovementService.LeafClearResult(MovementService.LeafClearAction.BYPASS_STEP, true, nextFailures, null, "bypass-step");
        }

        // If bypass isn't possible, start mining a single natural leaf asynchronously (no blocking waits).
        BlockPos start = player.getBlockPos();
        for (BlockPos leaf : leafCandidates(start, toward)) {
            if (!isBreakableLeaf(world, leaf) || !isWithinReach(player, leaf)) {
                continue;
            }
            LeafMineResult mineResult = startLeafMiningDetailed(player, leaf, "clearLeafObstruction");
            if (mineResult == LeafMineResult.STARTED || mineResult == LeafMineResult.IN_PROGRESS) {
                if (id != null) {
                    LEAF_BYPASS_FAILURE_STREAK.remove(id);
                    LEAF_LAST_BYPASS_ORIGIN.remove(id);
                }
                return new MovementService.LeafClearResult(
                        mineResult == LeafMineResult.STARTED ? MovementService.LeafClearAction.MINING_STARTED : MovementService.LeafClearAction.MINING_IN_PROGRESS,
                        true,
                        0,
                        leaf.toImmutable(),
                        mineResult == LeafMineResult.STARTED ? "mine-started" : "mine-in-progress");
            }
        }

        // Leaves are present in the immediate collision space, but we couldn't clear them (persistent/unreachable).
        // Emit an overhead hint so it doesn't feel like silent failure.
        if (id != null) {
            LEAF_BYPASS_FAILURE_STREAK.put(id, Math.min(10, bypassFailures + 1));
            LEAF_LAST_BYPASS_ORIGIN.put(id, player.getBlockPos().toImmutable());
        }
        CompanionOverheadDialogueService.tryShowLeafStuck(player, "clearLeafObstruction-noClear");
        return new MovementService.LeafClearResult(MovementService.LeafClearAction.NO_CLEAR, true, id != null ? LEAF_BYPASS_FAILURE_STREAK.getOrDefault(id, 0) : bypassFailures + 1, null, "no-clear");
    }

    public static boolean clearLeafObstruction(ServerPlayerEntity player, Direction toward) {
        MovementService.LeafClearResult result = clearLeafObstructionDetailed(player, toward);
        if (result == null) {
            return false;
        }
        return result.countsAsCleared() || result.action() == MovementService.LeafClearAction.BYPASS_STEP;
    }

    public static boolean hasLeafObstruction(ServerPlayerEntity player, Direction toward) {
        if (player == null || toward == null) {
            return false;
        }
        ServerWorld world = MovementService.getWorld(player);
        if (world == null) {
            return false;
        }
        // Only treat leaves as an obstruction when they're in the immediate forward collision space.
        return hasLeafInImmediatePath(world, player.getBlockPos(), toward);
    }
}
