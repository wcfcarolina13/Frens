package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Shared replacement/material selection logic for active construction repairs.
 *
 * <p>Modeled on fortify replacement behavior, but scoped to reusable service-level
 * helpers for schematic and other construction tasks.</p>
 */
public final class BlockReplacementService {

    private static final Logger LOGGER = LoggerFactory.getLogger("construction-replacement");

    private static final int MANDATORY_REPLACE_RETRIES = 2;
    private static final long MANDATORY_REPLACE_RETRY_SLEEP_MS = 120L;

    private static final List<Item> STONE_BRICK_FALLBACKS = List.of(
            Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE,
            Items.COBBLED_DEEPSLATE, Items.ANDESITE, Items.DIRT
    );
    private static final List<Item> OAK_LOG_FALLBACKS = List.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.JUNGLE_LOG, Items.COBBLESTONE, Items.DIRT
    );
    private static final List<Item> CHISELED_FALLBACKS = List.of(
            Items.CHISELED_STONE_BRICKS, Items.STONE_BRICKS, Items.COBBLESTONE, Items.DIRT
    );
    private static final List<Item> SLAB_FALLBACKS = List.of(
            Items.STONE_BRICK_SLAB, Items.COBBLESTONE_SLAB, Items.STONE_SLAB,
            Items.COBBLESTONE, Items.DIRT
    );
    private static final List<Item> COBBLE_FALLBACKS = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DIRT
    );

    private BlockReplacementService() {}

    public enum ReplaceFailureKind {
        NONE,
        BOT_OCCUPIES,
        OUT_OF_REACH,
        LOS_BLOCKED,
        NO_MATERIAL,
        OTHER;

        public boolean retryable() {
            return this == BOT_OCCUPIES || this == OUT_OF_REACH || this == LOS_BLOCKED;
        }
    }

    public record ReplaceResult(
            boolean success,
            ReplaceFailureKind failureKind,
            String reason,
            boolean forced
    ) {
        public static ReplaceResult success(boolean forced) {
            return new ReplaceResult(true, ReplaceFailureKind.NONE, null, forced);
        }

        public boolean retryable() {
            return failureKind != null && failureKind.retryable();
        }
    }

    public static boolean isRepairCandidate(BlockState desiredState) {
        return desiredState != null
                && !desiredState.isAir()
                && !(desiredState.getBlock() instanceof DoorBlock);
    }

    public static boolean stateSatisfies(BlockState currentState, BlockState desiredState) {
        if (desiredState == null) {
            return currentState == null;
        }
        if (currentState == null) {
            return false;
        }
        if (currentState.equals(desiredState)) {
            return true;
        }
        if (desiredState.isAir()) {
            return currentState.isAir();
        }
        if (currentState.isAir() || currentState.isReplaceable()) {
            return false;
        }

        Item currentItem = currentState.getBlock().asItem();
        if (currentItem == Items.AIR) {
            return false;
        }
        return buildReplacementCandidates(desiredState, false).contains(currentItem);
    }

    public static List<Item> buildReplacementCandidates(BlockState desiredState, boolean mandatory) {
        if (desiredState == null) {
            return List.of(Items.COBBLESTONE, Items.DIRT);
        }
        Item primary = desiredState.getBlock().asItem();
        LinkedHashSet<Item> candidates = new LinkedHashSet<>();

        if (primary == Items.STONE_BRICKS || primary == Items.STONE_BRICK_STAIRS) {
            candidates.addAll(STONE_BRICK_FALLBACKS);
        } else if (primary == Items.STONE_BRICK_SLAB || primary == Items.COBBLESTONE_SLAB
                || primary == Items.STONE_SLAB) {
            candidates.addAll(SLAB_FALLBACKS);
        } else if (primary == Items.CHISELED_STONE_BRICKS) {
            candidates.addAll(CHISELED_FALLBACKS);
        } else if (primary == Items.OAK_LOG) {
            candidates.addAll(OAK_LOG_FALLBACKS);
        } else if (primary == Items.COBBLESTONE || primary == Items.COBBLED_DEEPSLATE
                || primary == Items.STONE || primary == Items.ANDESITE) {
            candidates.addAll(COBBLE_FALLBACKS);
        } else if (primary == Items.OAK_PLANKS) {
            candidates.add(Items.OAK_PLANKS);
            candidates.add(Items.SPRUCE_PLANKS);
            candidates.add(Items.BIRCH_PLANKS);
            candidates.add(Items.JUNGLE_PLANKS);
            candidates.add(Items.ACACIA_PLANKS);
            candidates.add(Items.DARK_OAK_PLANKS);
            candidates.add(Items.COBBLESTONE);
            candidates.add(Items.DIRT);
        } else {
            if (primary != Items.AIR) {
                candidates.add(primary);
            }
            candidates.add(Items.COBBLESTONE);
            candidates.add(Items.DIRT);
        }

        if (mandatory) {
            candidates.add(Items.COBBLESTONE);
            candidates.add(Items.STONE);
            candidates.add(Items.DIRT);
        }

        return List.copyOf(candidates);
    }

    public static ReplaceResult tryReplaceBlock(ServerPlayerEntity bot,
                                                ServerWorld world,
                                                BlockPos pos,
                                                BlockState desiredState,
                                                boolean mandatory,
                                                String context) {
        if (bot == null || world == null || pos == null || desiredState == null) {
            return new ReplaceResult(false, ReplaceFailureKind.OTHER, "invalid-args", false);
        }

        if (stateSatisfies(world.getBlockState(pos), desiredState)) {
            return ReplaceResult.success(false);
        }

        List<Item> candidates = buildReplacementCandidates(desiredState, mandatory);
        int attempts = mandatory ? (1 + MANDATORY_REPLACE_RETRIES) : 1;
        ReplaceResult last = new ReplaceResult(false, ReplaceFailureKind.OTHER, "not-attempted", false);
        Direction preferredFace = preferredFaceFor(desiredState);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            BotActions.PlaceResult place = BotActions.tryPlaceBlockAt(bot, pos, preferredFace, candidates, false);
            if (place.success() || stateSatisfies(world.getBlockState(pos), desiredState)) {
                LOGGER.info("construction replace success: ctx={} pos={} mandatory={} forced=false",
                        context == null ? "construction" : context,
                        pos.toShortString(),
                        mandatory);
                return ReplaceResult.success(false);
            }

            last = new ReplaceResult(false, classifyReplaceFailureKind(place.reason()), place.reason(), false);
            if (!mandatory || attempt >= attempts || !last.retryable()) {
                break;
            }

            if (last.failureKind() == ReplaceFailureKind.BOT_OCCUPIES) {
                moveBotOutOfReplacementCell(bot, world, pos);
            }
            BotActions.stop(bot);
            sleepQuiet(MANDATORY_REPLACE_RETRY_SLEEP_MS);
            if (stateSatisfies(world.getBlockState(pos), desiredState)) {
                return ReplaceResult.success(false);
            }
        }

        if (mandatory) {
            BotActions.PlaceResult forced = BotActions.forceReplaceBlock(bot, pos, candidates);
            if (forced.success() || stateSatisfies(world.getBlockState(pos), desiredState)) {
                LOGGER.info("construction replace success: ctx={} pos={} mandatory=true forced=true",
                        context == null ? "construction" : context,
                        pos.toShortString());
                return ReplaceResult.success(true);
            }
            last = new ReplaceResult(false, classifyReplaceFailureKind(forced.reason()), forced.reason(), true);
        }

        if (mandatory) {
            LOGGER.warn("construction replace failed: ctx={} pos={} mandatory=true reason={}",
                    context == null ? "construction" : context,
                    pos.toShortString(),
                    last.reason());
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("construction replace failed: ctx={} pos={} mandatory=false reason={}",
                    context == null ? "construction" : context,
                    pos.toShortString(),
                    last.reason());
        }
        return last;
    }

    private static ReplaceFailureKind classifyReplaceFailureKind(String reason) {
        if (reason == null || reason.isBlank()) {
            return ReplaceFailureKind.OTHER;
        }
        if (reason.startsWith("bot-intersects-target")) {
            return ReplaceFailureKind.BOT_OCCUPIES;
        }
        if (reason.startsWith("out-of-reach")) {
            return ReplaceFailureKind.OUT_OF_REACH;
        }
        if (reason.startsWith("no-line-of-sight")) {
            return ReplaceFailureKind.LOS_BLOCKED;
        }
        if (reason.startsWith("no-block-item-available") || reason.startsWith("no-material")) {
            return ReplaceFailureKind.NO_MATERIAL;
        }
        return ReplaceFailureKind.OTHER;
    }

    private static Direction preferredFaceFor(BlockState desiredState) {
        if (desiredState == null) {
            return Direction.UP;
        }
        if (desiredState.contains(Properties.HORIZONTAL_FACING)) {
            Direction direction = desiredState.get(Properties.HORIZONTAL_FACING);
            if (direction != null && direction.getAxis().isHorizontal()) {
                return direction;
            }
        }
        if (desiredState.contains(Properties.FACING)) {
            Direction direction = desiredState.get(Properties.FACING);
            if (direction != null && direction.getAxis().isHorizontal()) {
                return direction;
            }
        }
        return Direction.UP;
    }

    private static boolean moveBotOutOfReplacementCell(ServerPlayerEntity bot,
                                                       ServerWorld world,
                                                       BlockPos blockedTarget) {
        if (bot == null || world == null || blockedTarget == null) {
            return false;
        }
        net.minecraft.util.math.Box blockedBox = new net.minecraft.util.math.Box(blockedTarget);
        if (!bot.getBoundingBox().intersects(blockedBox)) {
            return true;
        }

        BlockPos origin = bot.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos candidate = new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                    if (!SafePositionService.isSpawnable(world, candidate)) {
                        continue;
                    }
                    if (new net.minecraft.util.math.Box(candidate).intersects(blockedBox)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(candidate -> candidate.getSquaredDistance(origin)));
        for (BlockPos candidate : candidates) {
            Optional<MovementService.MovementPlan> plan = MovementService.planLootApproach(
                    bot, candidate, MovementService.MovementOptions.skillLoot());
            if (plan.isEmpty()) {
                continue;
            }
            MovementService.MovementResult result = MovementService.execute(
                    bot.getCommandSource(), bot, plan.get(), false, true, true, false);
            if (result.success() && !bot.getBoundingBox().intersects(blockedBox)) {
                return true;
            }
        }
        return !bot.getBoundingBox().intersects(blockedBox);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}