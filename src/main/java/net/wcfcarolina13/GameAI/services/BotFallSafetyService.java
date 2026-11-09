package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attempts a last-second water-bucket clutch when a lethal fall is detected.
 */
public final class BotFallSafetyService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("bot-fall-safety");

    /** A fall shorter than this so far is a step or a hop, not worth a landing scan. */
    private static final float MIN_ARM_FALL_DISTANCE = 2.0F;
    private static final double MIN_DESCENT_SPEED = -0.65D;
    /** How far below the feet the landing scan looks. */
    private static final double LANDING_SCAN_DEPTH = 64.0D;
    private static final long RETRY_INTERVAL_TICKS = 1L;
    private static final long SUCCESS_COOLDOWN_TICKS = 20L;

    private static final Map<UUID, Long> NEXT_ATTEMPT_TICK = new ConcurrentHashMap<>();

    /**
     * Fall-safety attempt gate.
     * Called at SERVER_STOPPED: a singleplayer quit keeps static state, the next world's server
     * restarts its tick counter at 0 and the same bots keep their UUIDs, so tick-based gates from
     * the old world would hold back behaviour for as long as that world had run.
     */
    public static void resetSession() {
        NEXT_ATTEMPT_TICK.clear();
    }


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
        if (ElytraFlightService.isInFlight(bot.getUuid())) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (bot.isOnGround() || bot.isClimbing() || bot.isTouchingWater() || bot.isSubmergedInWater()) {
            return;
        }
        if (bot.getVelocity().y > MIN_DESCENT_SPEED) {
            return;
        }
        if (bot.fallDistance < MIN_ARM_FALL_DISTANCE) {
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
        // Judge the whole fall (fallen so far plus still to go), so the clutch is armed and
        // waiting when the landing comes into reach instead of arming two ticks before impact.
        double effectiveHealth = bot.getHealth() + bot.getAbsorptionAmount();
        if (!FallClutchPolicy.projectedLethal(bot.fallDistance, landing.heightDelta(), effectiveHealth)) {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RETRY_INTERVAL_TICKS);
            return;
        }
        if (waterEvaporatesAt(world, bot.getBlockPos())) {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + SUCCESS_COOLDOWN_TICKS);
            LOGGER.info("Fall clutch: {} skipped the water bucket, water evaporates here (fallDistance={})",
                    bot.getName().getString(), String.format(java.util.Locale.ROOT, "%.1f", bot.fallDistance));
            return;
        }

        if (!FallClutchPolicy.inPlaceWindow(landing.heightDelta(), bot.getStandingEyeHeight(), bot.getBlockInteractionRange())) {
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

        LOGGER.info("Fall clutch: {} {} water at {} (fallDistance={})", bot.getName().getString(),
                used ? "placed" : "failed to place", landing.supportPos().toShortString(),
                String.format(java.util.Locale.ROOT, "%.1f", bot.fallDistance));
        if (used) {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + SUCCESS_COOLDOWN_TICKS);
        } else {
            NEXT_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RETRY_INTERVAL_TICKS);
        }
    }

    /**
     * True where placed water evaporates (the Nether in vanilla), so a clutch there would only
     * empty the bucket. 1.21.11 moved this from {@code DimensionType} to the environment attribute
     * {@code WATER_EVAPORATES_GAMEPLAY}, which vanilla's bucket checks per position. The old
     * reflective {@code isUltrawarm()} lookup found no such method and always answered false.
     */
    private static boolean waterEvaporatesAt(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        Boolean evaporates = world.getEnvironmentAttributes()
                .getAttributeValue(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY, pos);
        return Boolean.TRUE.equals(evaporates);
    }

    private record LandingTarget(BlockPos supportPos, BlockPos placePos, double heightDelta) {}

    private static LandingTarget predictLanding(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) {
            return null;
        }
        Vec3d from = new Vec3d(bot.getX(), bot.getBoundingBox().minY, bot.getZ());
        Vec3d to = from.add(0.0D, -LANDING_SCAN_DEPTH, 0.0D);
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
        if (waterEvaporatesAt(world, placePos)) {
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
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, supportPos.up());
        if (!auth.allowed()) {
            return false;
        }
        ItemStack stack = bot.getMainHandStack();
        if (stack.isEmpty() || !stack.isOf(Items.WATER_BUCKET)) {
            return false;
        }
        // A bucket places through Item.use (a raycast from the eyes), not useOnBlock, which a
        // bucket never overrides: the old useOnBlock call always passed and never placed water.
        // Look straight down and use it as a player would, within the player's block reach.
        bot.setPitch(90.0F);
        ActionResult result = bot.interactionManager.interactItem(bot, world, stack, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
        }
        return bot.getMainHandStack().isOf(Items.BUCKET);
    }
}
