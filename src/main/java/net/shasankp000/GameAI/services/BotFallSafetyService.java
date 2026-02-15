package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.BotEventHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attempts a last-second water-bucket clutch when a lethal fall is detected.
 */
public final class BotFallSafetyService {

    private static final float MIN_LETHAL_FALL_DISTANCE = 7.5F;
    private static final double MIN_DESCENT_SPEED = -0.65D;
    private static final double MAX_PLACE_HEIGHT_DELTA = 4.2D;
    private static final long RETRY_INTERVAL_TICKS = 2L;
    private static final long SUCCESS_COOLDOWN_TICKS = 20L;

    private static final Map<UUID, Long> NEXT_ATTEMPT_TICK = new ConcurrentHashMap<>();

    private BotFallSafetyService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long nowTick = server.getTicks();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            maybeClutchFall(bot, nowTick);
        }
    }

    private static void maybeClutchFall(ServerPlayerEntity bot, long nowTick) {
        if (bot == null || !bot.isAlive() || bot.isRemoved()) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (isUltrawarm(world)) {
            return;
        }
        if (bot.isOnGround() || bot.isClimbing() || bot.isTouchingWater() || bot.isSubmergedInWater()) {
            return;
        }
        if (bot.getVelocity().y > MIN_DESCENT_SPEED) {
            return;
        }
        if (bot.fallDistance < MIN_LETHAL_FALL_DISTANCE) {
            return;
        }
        if (!isLikelyLethal(bot)) {
            return;
        }
        long next = NEXT_ATTEMPT_TICK.getOrDefault(bot.getUuid(), 0L);
        if (nowTick < next) {
            return;
        }

        LandingTarget landing = predictLanding(world, bot);
        if (landing == null) {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RETRY_INTERVAL_TICKS);
            return;
        }
        if (landing.heightDelta() > MAX_PLACE_HEIGHT_DELTA || landing.heightDelta() < 0.2D) {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RETRY_INTERVAL_TICKS);
            return;
        }
        if (!canPlaceWaterAt(world, landing.placePos())) {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RETRY_INTERVAL_TICKS);
            return;
        }

        int previousSlot = bot.getInventory().getSelectedSlot();
        boolean used = false;
        try {
            if (!BotActions.ensureHotbarItem(bot, Items.WATER_BUCKET)) {
                NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RETRY_INTERVAL_TICKS);
                return;
            }
            used = placeWaterOnLandingBlock(bot, landing.supportPos());
        } finally {
            BotActions.selectHotbarSlot(bot, previousSlot);
        }

        if (used) {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + SUCCESS_COOLDOWN_TICKS);
        } else {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RETRY_INTERVAL_TICKS);
        }
    }

    private static boolean isLikelyLethal(ServerPlayerEntity bot) {
        double effectiveHealth = bot.getHealth() + bot.getAbsorptionAmount();
        double projectedDamage = Math.max(0.0D, bot.fallDistance - 3.0D);
        return projectedDamage >= Math.max(4.0D, effectiveHealth - 0.5D);
    }

    private static boolean isUltrawarm(ServerWorld world) {
        if (world == null || world.getDimension() == null) {
            return false;
        }
        Object dim = world.getDimension();
        try {
            Object out = dim.getClass().getMethod("isUltrawarm").invoke(dim);
            if (out instanceof Boolean b) {
                return b;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Object out = dim.getClass().getMethod("ultrawarm").invoke(dim);
            if (out instanceof Boolean b) {
                return b;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    private record LandingTarget(BlockPos supportPos, BlockPos placePos, double heightDelta) {}

    private static LandingTarget predictLanding(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) {
            return null;
        }
        Vec3d from = new Vec3d(bot.getX(), bot.getBoundingBox().minY, bot.getZ());
        Vec3d to = from.add(0.0D, -24.0D, 0.0D);
        BlockHitResult hit = world.raycast(new RaycastContext(
                from,
                to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot
        ));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos support = hit.getBlockPos();
        BlockPos place = support.up();
        double delta = bot.getBoundingBox().minY - place.getY();
        return new LandingTarget(support.toImmutable(), place.toImmutable(), delta);
    }

    private static boolean canPlaceWaterAt(ServerWorld world, BlockPos placePos) {
        if (world == null || placePos == null) {
            return false;
        }
        if (!world.isChunkLoaded(placePos)) {
            return false;
        }
        if (!world.getFluidState(placePos).isEmpty()) {
            return false;
        }
        if (world.getFluidState(placePos.down()).isIn(FluidTags.LAVA)) {
            return false;
        }
        BlockState placeState = world.getBlockState(placePos);
        if (!placeState.isAir() && !placeState.isReplaceable()) {
            return false;
        }
        BlockState support = world.getBlockState(placePos.down());
        return !support.getCollisionShape(world, placePos.down()).isEmpty();
    }

    private static boolean placeWaterOnLandingBlock(ServerPlayerEntity bot, BlockPos supportPos) {
        if (bot == null || supportPos == null) {
            return false;
        }
        ItemStack stack = bot.getMainHandStack();
        if (stack.isEmpty() || !stack.isOf(Items.WATER_BUCKET)) {
            return false;
        }
        LookController.faceBlock(bot, supportPos);
        BlockHitResult hit = new BlockHitResult(
                Vec3d.ofCenter(supportPos).add(0.0D, 0.5D, 0.0D),
                Direction.UP,
                supportPos,
                false
        );
        ActionResult result = stack.useOnBlock(new ItemUsageContext(bot, Hand.MAIN_HAND, hit));
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        return bot.getMainHandStack().isOf(Items.BUCKET);
    }
}
