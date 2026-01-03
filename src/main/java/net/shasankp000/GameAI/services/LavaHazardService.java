package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;

import java.util.List;
import java.util.Locale;

/**
 * Lava avoidance/containment helpers shared by mining-style skills.
 */
public final class LavaHazardService {

    private static final double MIN_SAFE_DISTANCE = 2.0D;
    private static final double RETREAT_DISTANCE = 2.6D;
    private static final List<Item> SAFETY_BLOCKS = List.of(
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.STONE,
            Items.DEEPSLATE,
            Items.DEEPSLATE_BRICKS,
            Items.GRAVEL,
            Items.DIRT
    );

    private LavaHazardService() {
    }

    public static LavaResponse respondToLava(ServerPlayerEntity bot,
                                            ServerCommandSource source,
                                            Direction directionHint,
                                            BlockPos plugPositionHint,
                                            BlockPos hazardPos) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new LavaResponse(false, false);
        }

        Direction direction = directionHint != null ? directionHint : inferDirection(bot.getBlockPos(), hazardPos);
        if (direction == null) {
            direction = bot.getHorizontalFacing();
        }

        BlockPos plugPos = plugPositionHint;
        if (plugPos == null && hazardPos != null) {
            BlockPos adjacent = bot.getBlockPos().offset(direction);
            BlockPos ahead = adjacent.offset(direction);
            if (hazardPos.equals(adjacent) || hazardPos.equals(adjacent.up())) {
                plugPos = adjacent;
            } else if (hazardPos.equals(ahead) || hazardPos.equals(ahead.up())) {
                plugPos = adjacent;
            }
        }

        boolean usedWater = false;
        boolean plugged = false;
        if (plugPos != null) {
            if (ensureWaterBucket(bot)) {
                usedWater = placeWaterAt(bot, world, plugPos);
            }
            if (!usedWater) {
                plugged = BotActions.placeBlockAt(bot, plugPos, SAFETY_BLOCKS);
            }
        }

        backAway(bot, direction);
        if (isLavaWithin(world, bot.getBlockPos(), MIN_SAFE_DISTANCE)) {
            backAway(bot, direction);
        }

        return new LavaResponse(usedWater, plugged);
    }

    public static LavaResponse respondToLava(ServerPlayerEntity bot,
                                            ServerCommandSource source,
                                            BlockPos hazardPos) {
        return respondToLava(bot, source, null, null, hazardPos);
    }

    private static Direction inferDirection(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return null;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void backAway(ServerPlayerEntity bot, Direction direction) {
        if (bot == null || direction == null) {
            return;
        }
        Vec3d pos = bot.getPos();
        Vec3d target = pos.add(
                direction.getOpposite().getOffsetX() * RETREAT_DISTANCE,
                0,
                direction.getOpposite().getOffsetZ() * RETREAT_DISTANCE
        );
        BotActions.moveToward(bot, target, RETREAT_DISTANCE);
        if (bot.isOnGround()) {
            BotActions.jump(bot);
        }
        BotActions.stop(bot);
    }

    private static boolean ensureWaterBucket(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        if (bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
            return true;
        }
        BotActions.ensureHotbarItem(bot, Items.WATER_BUCKET);
        return bot.getMainHandStack().isOf(Items.WATER_BUCKET);
    }

    private static boolean placeWaterAt(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        if (!bot.getMainHandStack().isOf(Items.WATER_BUCKET)) {
            return false;
        }
        if (bot.squaredDistanceTo(Vec3d.ofCenter(pos)) > 9.0D) {
            return false;
        }

        LookController.faceBlock(bot, pos);
        sleep(120L);

        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);
        if (belowState.isSolidBlock(world, below)) {
            Vec3d hitVec = Vec3d.of(below).add(0.5, 1.0, 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, below, false);
            ActionResult result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
                sleep(60L);
                return world.getFluidState(pos).isIn(FluidTags.WATER) || world.getBlockState(pos).isOf(Blocks.WATER);
            }
        }

        ActionResult result = bot.interactionManager.interactItem(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND);
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
            sleep(60L);
            return world.getFluidState(pos).isIn(FluidTags.WATER) || world.getBlockState(pos).isOf(Blocks.WATER);
        }

        return false;
    }

    private static boolean isLavaWithin(ServerWorld world, BlockPos origin, double range) {
        if (world == null || origin == null) {
            return false;
        }
        int r = Math.max(1, (int) Math.ceil(range));
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -1, -r), origin.add(r, 1, r))) {
            FluidState fluid = world.getFluidState(pos);
            if (fluid.isEmpty()) {
                continue;
            }
            if (fluid.isIn(FluidTags.LAVA)) {
                if (origin.getSquaredDistance(pos) <= range * range) {
                    return true;
                }
                continue;
            }
            Identifier id = Registries.FLUID.getId(fluid.getFluid());
            if (id != null) {
                String path = id.getPath().toLowerCase(Locale.ROOT);
                if (path.contains("lava") || path.contains("molten")) {
                    if (origin.getSquaredDistance(pos) <= range * range) {
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

    public record LavaResponse(boolean usedWater, boolean plugged) {
    }
}
