package net.wcfcarolina13.GameAI;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.wcfcarolina13.EntityUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SneakLockService;
import net.wcfcarolina13.GameAI.services.BotArrowRecoveryService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.FoodConsumptionConfirmationService;
import net.wcfcarolina13.GameAI.services.HotbarLockService;




import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Minimal action executor that replaces the old Carpet "player" commands.
 * Adjusts the server-side player directly so that training steps can take effect
 * even without the Carpet mod.
 */
public final class BotActions {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-actions");

    private static final double STEP_DISTANCE = 0.45;
    private static final float TURN_DEGREES = 20.0f;
    private static final int BOW_MIN_CHARGE_TICKS = 15;
    private static final int RANGED_COOLDOWN_TICKS = 20;
    // Ranged repositioning: dripstone/uneven terrain often blocks arrows even when canSee() is true.
    // We treat repeated blocked shots / misses as a signal to strafe to a better angle.
    private static final int RANGED_MISSES_BEFORE_REPOSITION = 3;
    private static final int RANGED_BLOCKED_BEFORE_REPOSITION = 3;
    private static final int RANGED_REPOSITION_COOLDOWN_TICKS = 35;
    private static final int RANGED_REPOSITION_SUPPRESS_FIRE_TICKS = 10;
    private static final int RANGED_MISS_RECENT_SHOT_WINDOW_TICKS = 80;
    private static final double RANGED_REPOSITION_MIN_DISTANCE_SQ = 4.5D * 4.5D;
    // Extended search ring: 12 directions at 3 distances when close candidates all fail.
    private static final int EXTENDED_SEARCH_DIRECTIONS = 12;
    private static final double[] EXTENDED_SEARCH_DISTANCES = {3.0D, 4.5D, 6.0D};
    // Defilade: bonus for positions where the target's return fire is partially blocked.
    private static final double DEFILADE_COVER_BONUS = 5.0D;
    // Committed reposition: persist target across ticks for distant moves.
    private static final double COMMITTED_REPOSITION_ARRIVE_SQ = 1.5D * 1.5D;
    private static final int COMMITTED_REPOSITION_TIMEOUT_TICKS = 60;
    private static final double SURVIVAL_REACH_SQ = 4.5D * 4.5D;

    private static final Map<UUID, RangedAttackState> RANGED_STATE = new HashMap<>();

    private BotActions() {}

    public record PlaceResult(boolean success, String reason) {}

    private static boolean onServerThread(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource() == null || bot.getCommandSource().getServer() == null) {
            return true;
        }
        return bot.getCommandSource().getServer().isOnThread();
    }

    private static boolean runOnServerThread(ServerPlayerEntity bot, Runnable action, long timeoutMs) {
        if (bot == null || action == null) {
            return false;
        }
        var server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null || server.isOnThread()) {
            action.run();
            return true;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                action.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            future.get(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private static <T> T callOnServerThread(ServerPlayerEntity bot, Supplier<T> action, long timeoutMs, T fallback) {
        if (bot == null || action == null) {
            return fallback;
        }
        var server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null || server.isOnThread()) {
            return action.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (ExecutionException | TimeoutException e) {
            return fallback;
        }
    }

    public static void moveForward(ServerPlayerEntity bot) {
        runOnServerThread(bot, () -> moveRelative(bot, STEP_DISTANCE, false, 0, 0), 750L);
    }

    public static void moveBackward(ServerPlayerEntity bot) {
        runOnServerThread(bot, () -> moveRelative(bot, -STEP_DISTANCE, false, 0, 0), 750L);
    }

    public static void moveForwardStep(ServerPlayerEntity bot, double distance) {
        runOnServerThread(bot, () -> moveRelative(bot, distance, false, 0, 0), 750L);
    }

    public static void moveToward(ServerPlayerEntity bot, Vec3d target, double maxStep) {
        if (!onServerThread(bot)) {
            runOnServerThread(bot, () -> moveToward(bot, target, maxStep), 900L);
            return;
        }
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1e-4) {
            return;
        }
        double step = Math.min(maxStep, horizontal);
        moveRelative(bot, step, true, dx / horizontal, dz / horizontal);
    }

    /**
     * Pushes the bot toward the given target using velocity, mimicking held movement keys.
     * This avoids teleport-style position snaps and lets collisions/physics resolve naturally.
     */
    public static void applyMovementInput(ServerPlayerEntity bot, Vec3d target, double maxImpulse) {
        if (bot == null || target == null) {
            return;
        }
        if (!onServerThread(bot)) {
            runOnServerThread(bot, () -> applyMovementInput(bot, target, maxImpulse), 900L);
            return;
        }
        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Vec3d delta = target.subtract(pos);
        double lenSq = delta.lengthSquared();
        if (lenSq < 1e-6) {
            return;
        }
        Vec3d impulse = delta.normalize().multiply(maxImpulse);

        // Clamp horizontal velocity so repeated inputs do not spike speed.
        Vec3d current = bot.getVelocity();
        Vec3d horiz = new Vec3d(current.x, 0, current.z);
        double horizMag = horiz.length();
        double maxHoriz = 0.6;
        if (horizMag > maxHoriz) {
            double scale = maxHoriz / horizMag;
            current = new Vec3d(horiz.x * scale, current.y, horiz.z * scale);
            bot.setVelocity(current);
        }

        bot.addVelocity(impulse.x, 0, impulse.z);
        bot.velocityDirty = true;
    }

    public static void stop(ServerPlayerEntity bot) {
        runOnServerThread(bot, () -> {
            bot.setVelocity(Vec3d.ZERO);
            bot.velocityDirty = true;
            bot.setSprinting(false);
            if (!SneakLockService.isLocked(bot.getUuid())) {
                bot.setSneaking(false);
            }
            // If a bow/trident is currently being "used" (drawn), calling stopUsingItem() will RELEASE a shot.
            // During FOLLOW/idle transitions we want to cancel safely to avoid friendly-fire.
            cancelRangedUseSafely(bot);
            resetRangedState(bot);
        }, 900L);
    }

    /**
     * Cancels active ranged item use without triggering a release shot.
     *
     * <p>In vanilla, {@code stopUsingItem()} for bows/tridents calls the item's "stopped using" handler,
     * which fires the projectile. {@code clearActiveItem()} cancels the use state without firing.</p>
     */
    private static void cancelRangedUseSafely(ServerPlayerEntity bot) {
        if (bot == null || !bot.isUsingItem()) {
            return;
        }
        ItemStack active = bot.getActiveItem();
        if (active == null || active.isEmpty()) {
            return;
        }
        if (!isRangedWeapon(active)) {
            return;
        }
        bot.clearActiveItem();
    }

    public static void turnLeft(ServerPlayerEntity bot) {
        rotate(bot, -TURN_DEGREES);
    }

    public static void turnRight(ServerPlayerEntity bot) {
        rotate(bot, TURN_DEGREES);
    }

    public static void jump(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        if (!onServerThread(bot)) {
            runOnServerThread(bot, () -> jump(bot), 900L);
            return;
        }
        // Prevent "multi-jump" / air-jump behavior caused by repeated calls while airborne.
        // Allow jumping only when grounded or swimming (vanilla-like controls).
        if (bot.isOnGround() || bot.isClimbing() || bot.isTouchingWater() || bot.isInLava()) {
            bot.jump();
        }
    }

    public static void sneak(ServerPlayerEntity bot, boolean value) {
        if (bot == null) {
            return;
        }
        if (!value && bot != null && SneakLockService.isLocked(bot.getUuid())) {
            return;
        }
        runOnServerThread(bot, () -> bot.setSneaking(value), 900L);
    }

    public static void sprint(ServerPlayerEntity bot, boolean value) {
        runOnServerThread(bot, () -> bot.setSprinting(value), 900L);
    }

    public static boolean selectBestWeapon(ServerPlayerEntity bot) {
        int weaponSlot = findWeaponSlot(bot);
        if (weaponSlot != -1) {
            selectHotbarSlot(bot, weaponSlot);
            return true;
        }
        // No weapon found — use fists rather than swinging food/blocks.
        return false;
    }

    public static boolean selectBestMeleeWeapon(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        PlayerInventory inventory = bot.getInventory();
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            int score = meleeWeaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }

        if (bestSlot == -1 || bestScore <= 0) {
            return false;
        }

        int hotbarSlot = ensureHotbarAccess(bot, inventory, bestSlot);
        selectHotbarSlot(bot, hotbarSlot);
        return true;
    }

    public static boolean selectBestTool(ServerPlayerEntity bot, String preferKeyword, String avoidKeyword) {
        if (bot == null) {
            return false;
        }
        preferKeyword = preferKeyword != null ? preferKeyword.toLowerCase(Locale.ROOT) : null;
        avoidKeyword = avoidKeyword != null ? avoidKeyword.toLowerCase(Locale.ROOT) : null;
        PlayerInventory inventory = bot.getInventory();
        int hotbarSize = 9;
        int fallbackSlot = -1;

        for (int slot = 0; slot < hotbarSize; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (avoidKeyword != null && key.contains(avoidKeyword)) {
                continue;
            }
            if (preferKeyword != null && key.contains(preferKeyword)) {
                selectHotbarSlot(bot, slot);
                return true;
            }
            if (fallbackSlot == -1 && (avoidKeyword == null || !key.contains(avoidKeyword))) {
                fallbackSlot = slot;
            }
        }
        if (fallbackSlot != -1 && preferKeyword == null) {
            selectHotbarSlot(bot, fallbackSlot);
            return true;
        }

        int preferredSlot = -1;
        for (int slot = hotbarSize; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (avoidKeyword != null && key.contains(avoidKeyword)) {
                continue;
            }
            if (preferKeyword != null && key.contains(preferKeyword)) {
                preferredSlot = slot;
                break;
            }
            if (preferredSlot == -1 && preferKeyword == null && (avoidKeyword == null || !key.contains(avoidKeyword))) {
                preferredSlot = slot;
            }
        }

        if (preferredSlot != -1) {
            int hotbarTarget = findEmptyHotbarSlot(inventory);
            if (hotbarTarget == -1) {
                hotbarTarget = fallbackSlot != -1 ? fallbackSlot : 0;
            }
            swapInventoryStacks(inventory, preferredSlot, hotbarTarget);
            selectHotbarSlot(bot, hotbarTarget);
            return true;
        }

        if (fallbackSlot != -1) {
            selectHotbarSlot(bot, fallbackSlot);
            return true;
        }

        return false;
    }

    public static void jumpForward(ServerPlayerEntity bot) {
        jump(bot);
        moveRelative(bot, STEP_DISTANCE * 0.6, false, 0, 0);
    }

    public static void attackNearest(ServerPlayerEntity bot, List<Entity> nearbyEntities) {
        Entity target = nearbyEntities.stream()
                .filter(EntityUtil::isHostile)
                .filter(e -> e.getType() != net.minecraft.entity.EntityType.GHAST) // ranged/deflect only
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(bot)))
                .orElse(null);

        if (target != null) {
            if (!selectBestMeleeWeapon(bot)) {
                selectBestWeapon(bot);
            }
            double distanceSq = target.squaredDistanceTo(bot);
            if (distanceSq <= 9.0 && bot.canSee(target)) {
                bot.attack(target);
                bot.swingHand(Hand.MAIN_HAND, true);
            }
        }
    }

    public static void useSelectedItem(ServerPlayerEntity bot) {
        runOnServerThread(bot, () -> {
            ItemStack stack = bot.getMainHandStack();
            if (stack.isEmpty()) {
                return;
            }

            // Global safeguard: prevent wasting rare consumables (e.g., Golden Apples / potions)
            // even when some other system (like RL actions) decides to "use item".
            if (FoodConsumptionConfirmationService.isConsumable(stack)
                    && FoodConsumptionConfirmationService.isRareOrExpensiveConsumable(stack)) {
                int foodLevel = bot.getHungerManager() != null ? bot.getHungerManager().getFoodLevel() : 20;
                boolean extremeEmergency = bot.getHealth() <= 6.0F || foodLevel <= 2;
                if (!extremeEmergency
                        && !FoodConsumptionConfirmationService.allowConsumptionOrRequest(bot, stack, "manual use")) {
                    return;
                }
            }

            ActionResult result = stack.use(bot.getEntityWorld(), bot, Hand.MAIN_HAND);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
            }
        }, 1500L);
    }

    public static void selectHotbarSlot(ServerPlayerEntity bot, int index) {
        if (bot == null) {
            return;
        }
        int clamped = MathHelper.clamp(index, 0, 8);
        Integer lockedSlot = HotbarLockService.getLockedSlot(bot);
        if (lockedSlot != null && lockedSlot != clamped) {
            HotbarLockService.maybeLogBlocked(bot, "select-hotbar");
            return;
        }
        // Immediate change; placement code may need the slot this tick.
        bot.getInventory().setSelectedSlot(clamped);
        bot.getInventory().markDirty();
    }

    public static boolean ensureHotbarItem(ServerPlayerEntity bot, Item desired) {
        if (bot == null || desired == null) {
            return false;
        }
        PlayerInventory inventory = bot.getInventory();
        int slot = findItemSlot(inventory, desired);
        if (slot == -1) {
            return false;
        }
        int hotbarSlot = slot;
        if (hotbarSlot >= 9) {
            int emptySlot = findEmptyHotbarSlot(inventory);
            if (emptySlot == -1) {
                emptySlot = 0;
            }
            swapInventoryStacks(inventory, slot, emptySlot);
            hotbarSlot = emptySlot;
        }
        selectHotbarSlot(bot, hotbarSlot);
        return true;
    }

    private static int findItemSlot(PlayerInventory inventory, Item desired) {
        if (inventory == null || desired == null) {
            return -1;
        }
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(desired)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean interactEntity(ServerPlayerEntity bot, Entity target, Hand hand) {
        return callOnServerThread(bot, () -> {
            if (bot == null || target == null || hand == null) {
                return false;
            }
            ActionResult res = bot.interact(target, hand);
            if (res.isAccepted()) {
                bot.swingHand(hand, true);
                return true;
            }
            return false;
        }, 2000L, false);
    }

    public static boolean breakBlockAhead(ServerPlayerEntity bot) {
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos targetPos = getRelativeBlockPos(bot, 1, 0);
        BlockState frontState = world.getBlockState(targetPos);
        if (frontState.getBlock() instanceof DoorBlock) {
            // Prefer opening doors rather than destroying them during escape routines.
            return MovementService.tryOpenDoorAt(bot, targetPos);
        }
        if (!frontState.isAir() && canBreak(world, targetPos, bot, false)) {
            boolean success = breakBlock(world, targetPos, bot);
            if (success) {
                return true;
            }
        }

        // Try the block above-front if the direct block was air (stair carving)
        BlockPos upperPos = getRelativeBlockPos(bot, 1, 1);
        BlockState upperState = world.getBlockState(upperPos);
        if (upperState.getBlock() instanceof DoorBlock) {
            return MovementService.tryOpenDoorAt(bot, upperPos);
        }
        if (!upperState.isAir() && canBreak(world, upperPos, bot, false)) {
            boolean success = breakBlock(world, upperPos, bot);
            if (success) {
                return true;
            }
        }

        return false;
    }

    public static boolean placeSupportBlock(ServerPlayerEntity bot) {
        return callOnServerThread(bot, () -> {
            ServerWorld world = bot.getCommandSource().getWorld();
            int slot = findPlaceableHotbarSlot(bot);
            if (slot == -1) {
                return false;
            }

            ItemStack stack = bot.getInventory().getStack(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }

            selectHotbarSlot(bot, slot);

            BlockPos below = bot.getBlockPos().down();
            BlockPos target = world.getBlockState(below).isAir() ? below : getRelativeBlockPos(bot, 0, -1);
            if (!world.getBlockState(target).isAir()) {
                // Try front-lower spot for stair stepping
                target = getRelativeBlockPos(bot, 1, -1);
            }

            if (!world.getBlockState(target).isAir()) {
                return false;
            }

            var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, target);
            if (!auth.allowed()) {
                return false;
            }

            BlockState stateToPlace = blockItem.getBlock().getDefaultState();
            if (!stateToPlace.canPlaceAt(world, target)) {
                return false;
            }

            boolean placed = world.setBlockState(target, stateToPlace);
            if (placed) {
                stack.decrement(1);
                if (stack.isEmpty()) {
                    bot.getInventory().setStack(slot, ItemStack.EMPTY);
                }
                bot.swingHand(Hand.MAIN_HAND, true);
                return true;
            }
            return false;
        }, 2500L, false);
    }

    public static boolean placeBlockAt(ServerPlayerEntity bot, BlockPos target) {
        return placeBlockAt(bot, target, Direction.UP, Collections.emptyList());
    }

    public static boolean placeBlockAt(ServerPlayerEntity bot, BlockPos target, List<Item> prioritizedBlocks) {
        return placeBlockAt(bot, target, Direction.UP, prioritizedBlocks);
    }

    public static boolean placeBlockAt(ServerPlayerEntity bot, BlockPos target, Direction face, List<Item> prioritizedBlocks) {
        return tryPlaceBlockAt(bot, target, face, prioritizedBlocks).success();
    }

    public static PlaceResult tryPlaceBlockAt(ServerPlayerEntity bot, BlockPos target, Direction face, List<Item> prioritizedBlocks) {
        return tryPlaceBlockAt(bot, target, face, prioritizedBlocks, false);
    }

    public static PlaceResult tryPlaceBlockAt(ServerPlayerEntity bot, BlockPos target, Direction face, List<Item> prioritizedBlocks, boolean allowIntersecting) {
        PlaceResult fallback = new PlaceResult(false, "timeout-or-thread-error");
        return callOnServerThread(bot, () -> {
            ServerWorld world = bot.getCommandSource().getWorld();
            if (world == null || target == null) {
                return new PlaceResult(false, "no-world-or-target");
            }

            var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, target);
            if (!auth.allowed()) {
                return new PlaceResult(false, "claim-denied " + auth.reason());
            }

            double distSq = bot.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            if (distSq > SURVIVAL_REACH_SQ) {
                return new PlaceResult(false, "out-of-reach distSq=" + String.format(Locale.ROOT, "%.2f", distSq));
            }

            if (!world.getBlockState(target).isAir() && world.getFluidState(target).isEmpty()) {
                // Allow replacing certain "clutter" blocks to avoid placement failures.
                // (E.g., grass/flowers/snow layers/carpets that occupy the cell but should not block building.)
                net.minecraft.block.BlockState state = world.getBlockState(target);
                boolean allowReplace = state.isOf(net.minecraft.block.Blocks.SNOW)
                        || state.isOf(net.minecraft.block.Blocks.SNOW_BLOCK)
                        || state.isIn(BlockTags.WOOL_CARPETS)
                        || (state.isReplaceable() && state.getCollisionShape(world, target).isEmpty());
                if (!allowReplace) {
                    return new PlaceResult(false, "occupied=" + state.getBlock().getName().getString());
                }
                boolean cleared = world.breakBlock(target, false);
                if (!cleared && !world.getBlockState(target).isAir()) {
                    return new PlaceResult(false, "failed-to-clear-occupied=" + state.getBlock().getName().getString());
                }
            }

            // Avoid placing while standing inside the target
            boolean isIntersecting = bot.getBoundingBox().intersects(new net.minecraft.util.math.Box(target));
            boolean allowJumpPillar = false;
            if (isIntersecting) {
                // Allow jump-pillaring: if the bot is airborne and placing into its current foot block,
                // vanilla collision resolution will move the player upward instead of trapping them.
                allowJumpPillar = !bot.isOnGround()
                        && target.equals(bot.getBlockPos())
                        && bot.getY() > target.getY() + 0.05D;
                // Also allow placing into the block below the current foot block while airborne
                // (the bot's blockPos can advance during a jump, but the intended pillar cell is still underfoot).
                if (!allowJumpPillar) {
                    BlockPos below = bot.getBlockPos().down();
                    allowJumpPillar = !bot.isOnGround()
                            && target.equals(below)
                            && bot.getY() > below.getY() + 0.05D;
                }
                if (!allowJumpPillar && !allowIntersecting) {
                    return new PlaceResult(false, "bot-intersects-target");
                }
            }

            List<Support> supports = resolvePlacementSupports(world, bot, target, face);
            if (supports.isEmpty()) {
                return new PlaceResult(false, "no-solid-support");
            }

            int slot = findPreferredBlockItemSlot(bot, prioritizedBlocks);
            if (slot == -1) {
                return new PlaceResult(false, "no-block-item-available");
            }
            PlayerInventory inventory = bot.getInventory();
            slot = ensureHotbarAccess(bot, inventory, slot);
            ItemStack stack = inventory.getStack(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return new PlaceResult(false, "selected-item-not-block");
            }
            selectHotbarSlot(bot, slot);

            Support support = null;
            BlockHitResult hit = null;
            for (Support candidate : supports) {
                BlockHitResult candidateHit = computePlacementHit(world, bot, candidate);
                if (candidateHit != null) {
                    support = candidate;
                    hit = candidateHit;
                    break;
                }
            }
            if (support == null) {
                support = supports.get(0);
            }
            if (hit == null) {
                if (allowIntersecting || (isIntersecting && allowJumpPillar)) {
                    hit = new BlockHitResult(Vec3d.ofCenter(support.clickPos()), support.face(), support.clickPos(), false);
                } else {
                    return new PlaceResult(false, "no-line-of-sight-to-support support="
                            + support.clickPos().toShortString()
                            + " face=" + support.face()
                            + " candidates=" + supports.size());
                }
            }
            ItemUsageContext usage = new ItemUsageContext(bot, Hand.MAIN_HAND, hit);
            ItemPlacementContext placementContext = new ItemPlacementContext(usage);
            ActionResult result = blockItem.place(placementContext);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
                if (stack.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
                return new PlaceResult(true, null);
            }
            // Include context in the reason string so scaffold/tower logs show useful diagnostics
            // (the obfuscated ActionResult alone, e.g. "class_9857[]", is not actionable)
            String reason = String.format("place-rejected item=%s target=%s support=%s face=%s placementPos=%s",
                    stack.getItem().getName().getString(),
                    target.toShortString(),
                    support.clickPos().toShortString(),
                    support.face(),
                    placementContext.getBlockPos().toShortString());
            PlaceResult fail = new PlaceResult(false, reason);
            return fail;
        }, 3500L, fallback);
    }

    /**
     * Force-place a block at the given position using {@code world.setBlockState()},
     * bypassing vanilla placement validation (entity collision, etc.).
     * <p>Only use for repair operations where we know the block must exist
     * (e.g., carve repairs after breaking wall blocks for navigation).
     */
    public static PlaceResult forceReplaceBlock(ServerPlayerEntity bot, BlockPos target, List<Item> prioritizedBlocks) {
        PlaceResult fallback = new PlaceResult(false, "timeout-or-thread-error");
        return callOnServerThread(bot, () -> {
            ServerWorld world = bot.getCommandSource().getWorld();
            if (world == null || target == null) {
                return new PlaceResult(false, "no-world-or-target");
            }

            var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, target);
            if (!auth.allowed()) {
                return new PlaceResult(false, "claim-denied " + auth.reason());
            }

            if (!world.getBlockState(target).isAir()) {
                return new PlaceResult(true, null); // already filled
            }
            int slot = findPreferredBlockItemSlot(bot, prioritizedBlocks);
            if (slot == -1) {
                return new PlaceResult(false, "no-block-item-available");
            }
            PlayerInventory inventory = bot.getInventory();
            slot = ensureHotbarAccess(bot, inventory, slot);
            ItemStack stack = inventory.getStack(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return new PlaceResult(false, "selected-item-not-block");
            }
            selectHotbarSlot(bot, slot);

            BlockState stateToPlace = blockItem.getBlock().getDefaultState();
            boolean placed = world.setBlockState(target, stateToPlace);
            if (placed) {
                stack.decrement(1);
                if (stack.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
                bot.swingHand(Hand.MAIN_HAND, true);
                return new PlaceResult(true, null);
            }
            return new PlaceResult(false, "setBlockState-rejected");
        }, 3500L, fallback);
    }

    private record Support(BlockPos clickPos, Direction face) {}

    private static boolean isClickablePlacementSupport(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // Avoid using fluid blocks (water/lava) as support for precise placement.
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        // Replaceable blocks (grass, vines, etc.) are unreliable supports for raycast + placement.
        if (state.isReplaceable()) {
            return false;
        }
        // Accept any block with collision as a clickable support (includes scaffolding, slabs, stairs, etc.).
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Resolve the actual block face we'd "right click" to place into {@code target}.
     * This prevents phantom placements where the support check passes but the clickPos is air.
     */
    private static List<Support> resolvePlacementSupports(ServerWorld world,
                                                          ServerPlayerEntity bot,
                                                          BlockPos target,
                                                          Direction preferredFace) {
        if (world == null || target == null) {
            return List.of();
        }
        Direction preferred = preferredFace == null ? Direction.UP : preferredFace;
        LinkedHashSet<Support> ordered = new LinkedHashSet<>();

        BlockPos below = target.down();
        if (preferred == Direction.UP) {
            maybeAddSupport(ordered, world, below, Direction.UP);
        }

        List<Direction> horizontal = new ArrayList<>(List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        horizontal.sort((left, right) -> Double.compare(
                supportPriorityScore(bot, target, preferred, left),
                supportPriorityScore(bot, target, preferred, right)));
        for (Direction dir : horizontal) {
            maybeAddSupport(ordered, world, target.offset(dir), dir.getOpposite());
        }

        if (preferred != Direction.UP) {
            maybeAddSupport(ordered, world, below, Direction.UP);
        }

        return List.copyOf(ordered);
    }

    private static void maybeAddSupport(LinkedHashSet<Support> ordered,
                                        ServerWorld world,
                                        BlockPos clickPos,
                                        Direction face) {
        if (ordered == null || world == null || clickPos == null || face == null) {
            return;
        }
        if (isClickablePlacementSupport(world, clickPos)) {
            ordered.add(new Support(clickPos.toImmutable(), face));
        }
    }

    private static double supportPriorityScore(ServerPlayerEntity bot,
                                               BlockPos target,
                                               Direction preferredFace,
                                               Direction supportOffsetDirection) {
        double score = 0.0D;
        if (preferredFace != null && preferredFace.getAxis().isHorizontal()
                && supportOffsetDirection == preferredFace.getOpposite()) {
            score -= 4.0D;
        }
        if (bot == null || target == null || supportOffsetDirection == null) {
            return score;
        }
        BlockPos clickPos = target.offset(supportOffsetDirection);
        Direction face = supportOffsetDirection.getOpposite();
        Vec3d eye = bot.getEyePos();
        Vec3d facePoint = pointOnFace(clickPos, face);
        score += eye.squaredDistanceTo(facePoint);
        return score;
    }

    private static BlockHitResult computePlacementHit(ServerWorld world, ServerPlayerEntity bot, Support support) {
        if (world == null || bot == null || support == null) {
            return null;
        }
        Vec3d start = bot.getEyePos();

        // Raycast to multiple points on the support face. Using only the face center is too brittle:
        // a single neighboring block can occlude the center point while the face is still clickable
        // at an edge/corner.
        for (Vec3d end : candidatePointsOnFace(support.clickPos(), support.face())) {
            RaycastContext ctx = new RaycastContext(
                    start,
                    end,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    bot
            );
            BlockHitResult hit = world.raycast(ctx);
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            if (!support.clickPos().equals(hit.getBlockPos())) {
                continue;
            }
            if (hit.getSide() != support.face()) {
                continue;
            }
            return hit;
        }
        return null;
    }

    private static List<Vec3d> candidatePointsOnFace(BlockPos pos, Direction face) {
        Vec3d center = Vec3d.ofCenter(pos);
        if (face == null) {
            return List.of(center);
        }
        double o = 0.32D;
        double f = 0.49D;
        List<Vec3d> points = new ArrayList<>(9);
        points.add(pointOnFace(pos, face));
        switch (face) {
            case UP -> {
                points.add(center.add(o, f, 0));
                points.add(center.add(-o, f, 0));
                points.add(center.add(0, f, o));
                points.add(center.add(0, f, -o));
                points.add(center.add(o, f, o));
                points.add(center.add(-o, f, o));
                points.add(center.add(o, f, -o));
                points.add(center.add(-o, f, -o));
            }
            case DOWN -> {
                points.add(center.add(o, -f, 0));
                points.add(center.add(-o, -f, 0));
                points.add(center.add(0, -f, o));
                points.add(center.add(0, -f, -o));
                points.add(center.add(o, -f, o));
                points.add(center.add(-o, -f, o));
                points.add(center.add(o, -f, -o));
                points.add(center.add(-o, -f, -o));
            }
            case NORTH -> {
                points.add(center.add(o, 0, -f));
                points.add(center.add(-o, 0, -f));
                points.add(center.add(0, o, -f));
                points.add(center.add(0, -o, -f));
                points.add(center.add(o, o, -f));
                points.add(center.add(-o, o, -f));
                points.add(center.add(o, -o, -f));
                points.add(center.add(-o, -o, -f));
            }
            case SOUTH -> {
                points.add(center.add(o, 0, f));
                points.add(center.add(-o, 0, f));
                points.add(center.add(0, o, f));
                points.add(center.add(0, -o, f));
                points.add(center.add(o, o, f));
                points.add(center.add(-o, o, f));
                points.add(center.add(o, -o, f));
                points.add(center.add(-o, -o, f));
            }
            case EAST -> {
                points.add(center.add(f, 0, o));
                points.add(center.add(f, 0, -o));
                points.add(center.add(f, o, 0));
                points.add(center.add(f, -o, 0));
                points.add(center.add(f, o, o));
                points.add(center.add(f, o, -o));
                points.add(center.add(f, -o, o));
                points.add(center.add(f, -o, -o));
            }
            case WEST -> {
                points.add(center.add(-f, 0, o));
                points.add(center.add(-f, 0, -o));
                points.add(center.add(-f, o, 0));
                points.add(center.add(-f, -o, 0));
                points.add(center.add(-f, o, o));
                points.add(center.add(-f, o, -o));
                points.add(center.add(-f, -o, o));
                points.add(center.add(-f, -o, -o));
            }
        }
        return points;
    }

    private static Vec3d pointOnFace(BlockPos pos, Direction face) {
        Vec3d center = Vec3d.ofCenter(pos);
        if (face == null) {
            return center;
        }
        return switch (face) {
            case UP -> center.add(0, 0.49, 0);
            case DOWN -> center.add(0, -0.49, 0);
            case NORTH -> center.add(0, 0, -0.49);
            case SOUTH -> center.add(0, 0, 0.49);
            case EAST -> center.add(0.49, 0, 0);
            case WEST -> center.add(-0.49, 0, 0);
        };
    }

    public static void escapeStairs(ServerPlayerEntity bot) {
        boolean placed = placeSupportBlock(bot);
        if (!placed) {
            breakBlockAhead(bot);
        }
        jumpForward(bot);
    }

    public static boolean digOut(ServerPlayerEntity bot) {
        return digOut(bot, false);
    }

    public static boolean digOut(ServerPlayerEntity bot, boolean forceBreak) {
        // Programmatic block breaking disabled - bot must mine blocks physically using tools and time
        // This prevents instant block removal and relies on the bot's natural mining ability
        return false;
    }
    
    /**
     * Breaks a specific block at the given position.
     * DISABLED: Programmatic block breaking removed to rely on physical, time-based mining.
     * @param bot The bot
     * @param pos The position of the block to break
     * @param forceBreak If true, breaks blocks up to hardness 5.0 even without proper tool
     * @return false (always returns false as programmatic breaking is disabled)
     */
    public static boolean breakBlockAt(ServerPlayerEntity bot, BlockPos pos, boolean forceBreak) {
        // Programmatic block breaking disabled - bot must mine blocks physically using tools and time
        return false;
    }

    private static boolean canBreak(ServerWorld world, BlockPos pos, ServerPlayerEntity bot, boolean forceBreak) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isOf(net.minecraft.block.Blocks.BEDROCK)) {
            return false;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return false;
        }
        // Never grief player storage / beds during generic movement/unstuck logic.
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL) || state.isOf(Blocks.ENDER_CHEST)) {
            return false;
        }
        if (state.isIn(BlockTags.BEDS) || state.isIn(BlockTags.SHULKER_BOXES)) {
            return false;
        }
        // Avoid griefing player-built enclosures/rails: never break fences/walls/gates as part of generic "unstuck".
        if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS) || state.isIn(BlockTags.FENCE_GATES)) {
            return false;
        }
        // Never destroy player-built roads.
        if (state.isOf(Blocks.DIRT_PATH)) {
            return false;
        }

        float hardness = state.getHardness(world, pos);
        if (hardness < 0) {
            return false;
        }

        if (forceBreak) {
            return hardness <= 5.0f && !state.isAir() && !state.isOf(Blocks.BEDROCK);
        }

        ItemStack tool = bot.getMainHandStack();
        if (!tool.isEmpty() && tool.isSuitableFor(state)) {
            return true;
        }

        float allowedHardness = 0.5f; // fist baseline – dirt, sand, gravel, glass
        if (!tool.isEmpty()) {
            float miningSpeed = tool.getMiningSpeedMultiplier(state);
            if (miningSpeed > 1.0f) {
                allowedHardness = 3.0f; // capable tool, allow stone-tier
            } else {
                allowedHardness = 1.0f; // miscellaneous item, slightly better than fist
            }
        }

        return hardness <= allowedHardness;
    }

    private static boolean breakBlock(ServerWorld world, BlockPos pos, ServerPlayerEntity bot) {
        return breakBlock(world, pos, bot, false);
    }

    private static boolean breakBlock(ServerWorld world, BlockPos pos, ServerPlayerEntity bot, boolean forceBreak) {
        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos);
        if (!auth.allowed()) {
            return false;
        }
        if (!canBreak(world, pos, bot, forceBreak)) {
            return false;
        }
        // Attempt a physical break using the interaction manager (no instant removal)
        boolean success = bot.interactionManager.tryBreakBlock(pos);
        if (success) {
            bot.swingHand(Hand.MAIN_HAND, true);
        }
        return success;
    }

    private static int findWeaponSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && isLikelyWeapon(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static int meleeWeaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW)) {
            return 0;
        }

        // Prefer swords first (player expectation for "defense"), then other melee options.
        if (stack.isOf(Items.TRIDENT)) {
            return 40;
        }

        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.contains("sword")) {
            return 100;
        }
        if (key.contains("mace")) {
            return 85;
        }
        if (key.contains("axe")) {
            return 70;
        }
        if (key.contains("dagger")) {
            return 60;
        }
        return 0;
    }

    /**
     * Non-mutating check: does the bot have any melee weapon in inventory?
     * Unlike {@link #selectBestMeleeWeapon}, this does NOT change the selected hotbar slot.
     */
    public static boolean hasMeleeWeapon(ServerPlayerEntity bot) {
        if (bot == null) return false;
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (meleeWeaponScore(inventory.getStack(slot)) > 0) return true;
        }
        return false;
    }

    private static boolean isLikelyWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        if (stack.isOf(Items.BOW) || stack.isOf(Items.CROSSBOW) || stack.isOf(Items.TRIDENT)) {
            return true;
        }

        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        return key.contains("sword") || key.contains("axe") || key.contains("trident") || key.contains("mace") || key.contains("dagger");
    }

    private static void moveRelative(ServerPlayerEntity bot, double distance, boolean customDirection, double dirX, double dirZ) {
        float yaw = bot.getYaw();
        double dx;
        double dz;

        if (customDirection) {
            dx = dirX * distance;
            dz = dirZ * distance;
        } else {
            double yawRad = Math.toRadians(yaw);
            dx = -Math.sin(yawRad) * distance;
            dz = Math.cos(yawRad) * distance;
        }

        double baseX = bot.getX();
        double baseY = bot.getY();
        double baseZ = bot.getZ();

        double newX = baseX + dx;
        double newY = baseY;
        double newZ = baseZ + dz;

        // Try to move; allow a 1-block step up or down for stairs rather than treating it as a hard collision.
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world != null) {
            BlockPos targetPos = new BlockPos(
                    MathHelper.floor(newX),
                    MathHelper.floor(newY),
                    MathHelper.floor(newZ)
            );

            // If direct move is blocked, first attempt a 1-block step up.
            if (!hasMovementClearance(world, targetPos)) {
                BlockPos stepUpPos = targetPos.up();
                if (hasMovementClearance(world, stepUpPos)) {
                    newY += 1.0;
                    targetPos = stepUpPos;
                } else {
                    // If step up fails, attempt a gentle 1-block step down (walking off a stair edge).
                    BlockPos stepDownPos = targetPos.down();
                    if (hasMovementClearance(world, stepDownPos)) {
                        newY -= 1.0;
                        targetPos = stepDownPos;
                    } else {
                        // No safe step up or down -> apply a small physics-based "escape" nudge.
                        // This helps in corner-wedge situations where discrete step movement can't resolve collisions.
                        Vec3d pos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
                        Vec3d forward = new Vec3d(dx, 0, dz);
                        if (forward.lengthSquared() > 1.0E-6) {
                            Vec3d dir = forward.normalize();
                            if (bot.isOnGround()) {
                                // Attempt a hop; autoJumpIfNeeded can miss diagonals, so do a direct jump too.
                                autoJumpIfNeeded(bot);
                                jump(bot);
                            }

                            // Prefer a slight sidestep if forward is blocked.
                            Vec3d left = new Vec3d(-dir.z, 0, dir.x);
                            Vec3d right = new Vec3d(dir.z, 0, -dir.x);
                            BlockPos leftProbe = BlockPos.ofFloored(pos.x + left.x * 0.65D, pos.y, pos.z + left.z * 0.65D);
                            BlockPos rightProbe = BlockPos.ofFloored(pos.x + right.x * 0.65D, pos.y, pos.z + right.z * 0.65D);
                            boolean leftClear = hasMovementClearance(world, leftProbe);
                            boolean rightClear = hasMovementClearance(world, rightProbe);
                            Vec3d escapeDir = (leftClear && !rightClear) ? left : (rightClear && !leftClear) ? right : dir;

                            Vec3d escapeTarget = pos.add(escapeDir.multiply(Math.max(0.9D, Math.abs(distance) * 1.6D)));
                            applyMovementInput(bot, escapeTarget, 0.22D);
                        }
                        return;
                    }
                }
            }
        }

        bot.refreshPositionAndAngles(newX, newY, newZ, bot.getYaw(), bot.getPitch());
    }
    
    /**
     * Checks if a position has clearance for the bot (2 blocks tall).
     * Prevents bot from moving into walls that would cause suffocation.
     */
    @SuppressWarnings("deprecation")
    private static boolean hasMovementClearance(ServerWorld world, BlockPos pos) {
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        
        // Allow if both feet and head positions are passable
        // Air, water, lava (bot can handle), and other non-solid blocks
        return (feet.isAir() || !feet.blocksMovement()) && 
               (head.isAir() || !head.blocksMovement());
    }

    private static int findEmptyHotbarSlot(PlayerInventory inventory) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static void swapInventoryStacks(PlayerInventory inventory, int from, int to) {
        if (from == to) {
            return;
        }
        ItemStack fromStack = inventory.getStack(from);
        ItemStack toStack = inventory.getStack(to);
        inventory.setStack(from, toStack);
        inventory.setStack(to, fromStack);
        inventory.markDirty();
    }

    private static void rotate(ServerPlayerEntity bot, float angle) {
        float newYaw = bot.getYaw() + angle;
        bot.setYaw(newYaw);
        bot.setHeadYaw(newYaw);
        bot.setBodyYaw(newYaw); // Keep body, head, and yaw aligned for simplicity
    }

    public static void autoJumpIfNeeded(ServerPlayerEntity bot) {
        if (!bot.isOnGround()) {
            return;
        }
        ServerWorld world = bot.getCommandSource().getWorld();

        // Use current motion direction when possible (better for diagonal moves); otherwise fall back to yaw.
        Vec3d vel = bot.getVelocity();
        double dirX = vel.x;
        double dirZ = vel.z;
        double magSq = dirX * dirX + dirZ * dirZ;
        if (magSq < 1.0E-4) {
            double yawRad = Math.toRadians(bot.getYaw());
            dirX = -Math.sin(yawRad);
            dirZ = Math.cos(yawRad);
            magSq = dirX * dirX + dirZ * dirZ;
        }
        if (magSq < 1.0E-6) {
            return;
        }
        double invMag = 1.0 / Math.sqrt(magSq);
        dirX *= invMag;
        dirZ *= invMag;

        // Probe the block we're about to collide with (continuous direction -> blockpos).
        // 0.65 is enough to reach into the neighboring cell even when standing near an edge.
        BlockPos front = BlockPos.ofFloored(bot.getX() + dirX * 0.65D, bot.getY(), bot.getZ() + dirZ * 0.65D);
        BlockPos frontAbove = front.up();

        BlockState frontState = world.getBlockState(front);
        if (frontState.getBlock() instanceof DoorBlock) {
            return; // doors handled elsewhere; don't bunny-hop at them
        }
        if (frontState.getCollisionShape(world, front).isEmpty()) {
            return;
        }
        // Don't attempt to jump over tall collision shapes (fences/walls); that's a real obstacle.
        double maxY = frontState.getCollisionShape(world, front).getMax(Direction.Axis.Y);
        if (maxY > 1.01D) {
            return;
        }

        boolean headSpace = world.getBlockState(frontAbove).getCollisionShape(world, frontAbove).isEmpty()
                && world.getBlockState(frontAbove.up()).getCollisionShape(world, frontAbove.up()).isEmpty();
        if (headSpace) {
            jump(bot);
        }
    }

    public static boolean raiseShield(ServerPlayerEntity bot) {
        if (bot.isBlocking() || bot.isUsingItem()) {
            return true;
        }

        Hand shieldHand = findShieldHand(bot);
        if (shieldHand == null) {
            return false;
        }

        bot.setCurrentHand(shieldHand);
        return true;
    }

    public static void lowerShield(ServerPlayerEntity bot) {
        if (bot.isBlocking() || bot.isUsingItem()) {
            ItemStack active = bot.getActiveItem();
            if (active.isOf(Items.SHIELD)) {
                bot.clearActiveItem();
            }
        }
    }

    private static Hand findShieldHand(ServerPlayerEntity bot) {
        if (bot.getOffHandStack().isOf(Items.SHIELD)) {
            return Hand.OFF_HAND;
        }
        if (bot.getMainHandStack().isOf(Items.SHIELD)) {
            return Hand.MAIN_HAND;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(Items.SHIELD)) {
                selectHotbarSlot(bot, i);
                return Hand.MAIN_HAND;
            }
        }
        return null;
    }

    private static BlockPos getRelativeBlockPos(ServerPlayerEntity bot, int forwardOffset, int verticalOffset) {
        Direction facing = getFacingDirection(bot);
        BlockPos basePos = bot.getBlockPos().add(0, verticalOffset, 0);
        return basePos.offset(facing, forwardOffset);
    }

    private static Direction getFacingDirection(ServerPlayerEntity bot) {
        int index = MathHelper.floor((bot.getYaw() * 4.0F / 360.0F) + 0.5D) & 3;
        return switch (index) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private static int findPlaceableHotbarSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
    }

    private static int findPreferredBlockItemSlot(ServerPlayerEntity bot, List<Item> prioritizedBlocks) {
        PlayerInventory inventory = bot.getInventory();
        if (prioritizedBlocks != null) {
            for (Item item : prioritizedBlocks) {
                int slot = findBlockItemSlot(inventory, stack -> stack.isOf(item));
                if (slot != -1) {
                    return slot;
                }
            }
        }
        return findBlockItemSlot(inventory, stack -> true);
    }

    private static int findBlockItemSlot(PlayerInventory inventory, Predicate<ItemStack> predicate) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem && predicate.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static int ensureHotbarAccess(ServerPlayerEntity bot, PlayerInventory inventory, int slot) {
        if (HotbarLockService.isLocked(bot)) {
            HotbarLockService.maybeLogBlocked(bot, "ensure-hotbar");
            return slot;
        }
        if (slot < 9) {
            return slot;
        }
        int target = findEmptyHotbarSlot(inventory);
        if (target == -1) {
            target = 0;
        }
        swapInventoryStacks(inventory, slot, target);
        return target;
    }




    public static boolean performRangedAttack(ServerPlayerEntity bot, LivingEntity target, long serverTick) {
        if (bot == null || target == null || bot.getEntityWorld() == null) {
            return false;
        }

        Selection selection = selectBestRangedWeapon(bot);
        if (selection == null) {
            return false;
        }

        ItemStack stack = selection.stack;
        UUID botUuid = bot.getUuid();
        RangedAttackState state = RANGED_STATE.computeIfAbsent(botUuid, uuid -> new RangedAttackState());

        // Face target.
        // Prefer an aim point that is not ray-blocked (dripstone / uneven terrain often blocks torso shots).
        double targetX = target.getX();
        double targetY = target.getBodyY(0.333333333333d);
        double targetZ = target.getZ();
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            Vec3d aim = pickPreferredAimPoint(world, bot, bot.getEyePos(), target);
            if (aim != null) {
                targetX = aim.x;
                targetY = aim.y;
                targetZ = aim.z;
            }
        }
        double dx = targetX - bot.getX();
        double dy = targetY - bot.getEyeY();
        double dz = targetZ - bot.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) Math.max(-90.0, Math.min(90.0,
                Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
        bot.setPitch(pitch);

        // If we're currently repositioning, suppress firing for a few ticks while we move.
        // (Otherwise the bot keeps releasing arrows from the same blocked angle.)
        if (state.repositioningUntilTick > serverTick && state.isCurrentTarget(target)) {
            return true;
        }

        if (stack.getItem() instanceof net.minecraft.item.CrossbowItem crossbow) {
            if (state.forceMelee && state.isCurrentTarget(target)) {
                return false;
            }
            state.ensureTarget(target);
            return handleCrossbow(bot, target, selection.hand, stack, crossbow, state, serverTick);
        }

        if (stack.getItem() instanceof net.minecraft.item.BowItem) {
            if (state.forceMelee && state.isCurrentTarget(target)) {
                return false;
            }
            state.ensureTarget(target);
            return handleChargeWeapon(bot, target, selection.hand, stack, state, serverTick, BOW_MIN_CHARGE_TICKS);
        }

        if (stack.getItem() instanceof net.minecraft.item.TridentItem) {
            if (state.forceMelee && state.isCurrentTarget(target)) {
                return false;
            }
            state.ensureTarget(target);
            return handleChargeWeapon(bot, target, selection.hand, stack, state, serverTick, 10);
        }

        return false;
    }

    private static boolean handleChargeWeapon(ServerPlayerEntity bot, LivingEntity target, Hand hand, ItemStack stack, RangedAttackState state, long serverTick, int minChargeTicks) {
        if (!canFire(bot, stack)) {
            return false;
        }

        if (state.cooldownTick > serverTick) {
            return true;
        }

        if (bot.isUsingItem()) {
            if (state.chargeStartTick == 0L) {
                state.chargeStartTick = serverTick - 1;
            }
            if (serverTick - state.chargeStartTick >= minChargeTicks) {
                // Don't release into blocked terrain — cancel the draw and let approach logic take over.
                if (bot.getEntityWorld() instanceof ServerWorld sw
                        && isRangedLineBlocked(sw, bot, bot.getEyePos(), target)) {
                    cancelRangedUseSafely(bot);
                    state.chargeStartTick = 0L;
                    return false;
                }
                bot.stopUsingItem();
                state.cooldownTick = serverTick + RANGED_COOLDOWN_TICKS;
                state.chargeStartTick = 0L;
                state.recordShot(bot, target, serverTick);
                BotArrowRecoveryService.noteRangedShot(bot, serverTick);
            }
            return true;
        }

        // Don't start drawing if line is already blocked — save the arrow.
        if (bot.getEntityWorld() instanceof ServerWorld sw
                && isRangedLineBlocked(sw, bot, bot.getEyePos(), target)) {
            return false;
        }

        bot.setCurrentHand(hand);
        state.chargeStartTick = serverTick;
        return true;
    }

    private static boolean handleCrossbow(ServerPlayerEntity bot, LivingEntity target, Hand hand, ItemStack stack, net.minecraft.item.CrossbowItem crossbow, RangedAttackState state, long serverTick) {
        if (!canFire(bot, stack)) {
            return false;
        }

        if (state.cooldownTick > serverTick) {
            return true;
        }

        if (net.minecraft.item.CrossbowItem.isCharged(stack)) {
            // Don't fire into blocked terrain — let approach logic take over.
            if (bot.getEntityWorld() instanceof ServerWorld sw
                    && isRangedLineBlocked(sw, bot, bot.getEyePos(), target)) {
                return false;
            }
            float velocity = 1.6F;
            float divergence = 14 - bot.getEntityWorld().getDifficulty().getId() * 4;
            crossbow.shootAll(bot.getEntityWorld(), bot, hand, stack, velocity, divergence, target);
            state.cooldownTick = serverTick + RANGED_COOLDOWN_TICKS;
            state.recordShot(bot, target, serverTick);
            BotArrowRecoveryService.noteRangedShot(bot, serverTick);
            return true;
        }

        if (bot.isUsingItem()) {
            if (net.minecraft.item.CrossbowItem.isCharged(stack)) {
                bot.stopUsingItem();
                state.cooldownTick = serverTick + 2;
            }
            return true;
        }

        bot.setCurrentHand(hand);
        state.chargeStartTick = serverTick;
        return true;
    }

    private static boolean canFire(ServerPlayerEntity bot, ItemStack weapon) {
        ItemStack projectile = bot.getProjectileType(weapon);
        return !projectile.isEmpty() || bot.getAbilities().creativeMode;
    }

    public static boolean hasRangedWeapon(ServerPlayerEntity bot) {
        if (bot == null) return false;
        // Non-mutating check: scan inventory without equipping anything.
        // The actual weapon equip happens inside performRangedAttack() -> selectBestRangedWeapon().
        if (isRangedWeapon(bot.getMainHandStack()) || isRangedWeapon(bot.getOffHandStack())) {
            return true;
        }
        PlayerInventory inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (isRangedWeapon(inventory.getStack(i))) return true;
        }
        return false;
    }

    private static Selection selectBestRangedWeapon(ServerPlayerEntity bot) {
        ItemStack main = bot.getMainHandStack();
        if (isRangedWeapon(main)) {
            return new Selection(Hand.MAIN_HAND, main);
        }

        ItemStack off = bot.getOffHandStack();
        if (isRangedWeapon(off)) {
            return new Selection(Hand.OFF_HAND, off);
        }

        PlayerInventory inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isRangedWeapon(stack)) {
                int hotbarSlot = ensureHotbarAccess(bot, inventory, i);
                ItemStack moved = inventory.getStack(hotbarSlot);
                selectHotbarSlot(bot, hotbarSlot);
                return new Selection(Hand.MAIN_HAND, moved);
            }
        }
        return null;
    }

    private static boolean isRangedWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // Trident excluded: canFire() fails because tridents don't use getProjectileType().
        // Treating them as ranged causes the bot to waste ticks on failed ranged attacks.
        return stack.getItem() instanceof net.minecraft.item.BowItem ||
                stack.getItem() instanceof net.minecraft.item.CrossbowItem;
    }

    public static void resetRangedState(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        RangedAttackState state = RANGED_STATE.remove(bot.getUuid());
        if (state != null) {
            // Never use stopUsingItem() here: if the bot is holding a drawn bow/trident, that would fire.
            cancelRangedUseSafely(bot);
            state.forceMelee = false;
        }
    }

    /**
     * Called when arrow tracking observes a bot-owned arrow become stationary during active combat.
     * This is a strong proxy for a "miss" (arrow hit terrain).
     */
    public static void noteRangedMiss(ServerPlayerEntity bot, long serverTick) {
        if (bot == null) {
            return;
        }
        RangedAttackState state = RANGED_STATE.get(bot.getUuid());
        if (state == null) {
            return;
        }
        state.noteMiss(serverTick);
    }

    /**
     * If repeated misses (or repeated blocked line checks) are observed, strafe to a new firing angle.
     * Returns true if this call drove movement.
     */
    public static boolean tryRepositionForRanged(ServerPlayerEntity bot, LivingEntity target, long serverTick) {
        if (bot == null || target == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        RangedAttackState state = RANGED_STATE.get(bot.getUuid());
        if (state == null || !state.isCurrentTarget(target)) {
            return false;
        }
        if (state.forceMelee) {
            return false;
        }

        // --- Committed multi-tick movement: continue driving toward a distant reposition target ---
        if (state.committedRepositionTarget != null) {
            if (serverTick - state.committedRepositionStartTick > COMMITTED_REPOSITION_TIMEOUT_TICKS) {
                state.committedRepositionTarget = null;
                return false;
            }
            // If LoS cleared mid-move, stop and let the bot fire from here.
            if (!isRangedLineBlocked(world, bot, bot.getEyePos(), target)) {
                state.committedRepositionTarget = null;
                return false;
            }
            double distSq = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).squaredDistanceTo(state.committedRepositionTarget);
            if (distSq <= COMMITTED_REPOSITION_ARRIVE_SQ) {
                state.committedRepositionTarget = null;
                state.repositioningUntilTick = serverTick + RANGED_REPOSITION_SUPPRESS_FIRE_TICKS;
                state.repositionCooldownTick = serverTick + RANGED_REPOSITION_COOLDOWN_TICKS;
                return false; // arrived — let combat loop re-evaluate
            }
            // Cancel active bow draw so we can move freely.
            cancelRangedUseSafely(bot);
            moveToward(bot, state.committedRepositionTarget, 0.9D);
            return true;
        }

        if (bot.hasVehicle() || bot.isUsingItem()) {
            return false;
        }
        if (state.repositionCooldownTick > serverTick || state.repositioningUntilTick > serverTick) {
            return false;
        }

        // Update blocked-shot streak from the current stance.
        boolean blockedNow = isRangedLineBlocked(world, bot, bot.getEyePos(), target);
        state.noteBlockedCheck(blockedNow, serverTick);

        // Decay miss streak if we've gone a while without additional miss signals.
        state.decayMisses(serverTick);

        if (state.missStreak < RANGED_MISSES_BEFORE_REPOSITION && state.blockedShotStreak < RANGED_BLOCKED_BEFORE_REPOSITION) {
            return false;
        }

        // Don't waste time shuffling when already close enough to just melee.
        if (bot.squaredDistanceTo(target) <= RANGED_REPOSITION_MIN_DISTANCE_SQ) {
            return false;
        }

        Vec3d best = pickRangedReposition(world, bot, target);
        if (best == null) {
            return false;
        }

        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double distToBest = botPos.distanceTo(best);

        // Distant repositions (>2 blocks): commit to reaching the target over multiple ticks.
        if (distToBest > 2.0D) {
            state.committedRepositionTarget = best;
            state.committedRepositionStartTick = serverTick;
            cancelRangedUseSafely(bot);
            moveToward(bot, best, 0.9D);
            state.missStreak = 0;
            state.blockedShotStreak = 0;
            return true;
        }

        // Close repositions: single-tick step (original behavior).
        double dyToBest = best.y - bot.getY();
        if (Math.abs(dyToBest) > 0.35D) {
            moveToward(bot, best, 0.9D);
        } else {
            applyMovementInput(bot, best, 0.24D);
            if (bot.isOnGround()) {
                autoJumpIfNeeded(bot);
            }
        }

        state.repositioningUntilTick = serverTick + RANGED_REPOSITION_SUPPRESS_FIRE_TICKS;
        state.repositionCooldownTick = serverTick + RANGED_REPOSITION_COOLDOWN_TICKS;
        state.missStreak = 0;
        state.blockedShotStreak = 0;
        return true;
    }

    private record ScoredPosition(Vec3d stand, double score) {}

    private static Vec3d pickRangedReposition(ServerWorld world, ServerPlayerEntity bot, LivingEntity target) {
        // Compute a local strafe basis around the target.
        Vec3d toTarget = new Vec3d(target.getX() - bot.getX(), 0.0D, target.getZ() - bot.getZ());
        double lenSq = toTarget.lengthSquared();
        if (lenSq < 1.0E-6) {
            return null;
        }
        Vec3d dir = toTarget.normalize();
        Vec3d left = new Vec3d(-dir.z, 0.0D, dir.x);
        Vec3d right = new Vec3d(dir.z, 0.0D, -dir.x);

        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        double eyeHeight = bot.getStandingEyeHeight();
        ScoredPosition best = null;

        // --- Phase 1: Close candidates (original 7, within ~1.5 blocks) ---
        List<Vec3d> closeOffsets = List.of(
                left.multiply(1.45D),
                right.multiply(1.45D),
                dir.multiply(-1.35D),
                dir.multiply(-0.9D).add(left.multiply(1.0D)),
                dir.multiply(-0.9D).add(right.multiply(1.0D)),
                left.multiply(0.95D),
                right.multiply(0.95D)
        );

        for (Vec3d off : closeOffsets) {
            ScoredPosition sp = scoreCandidate(world, bot, target, botPos, eyeHeight, off);
            if (sp != null && (best == null || sp.score > best.score)) {
                best = sp;
            }
        }

        // --- Phase 2: Extended ring (12 directions × 3 distances) when close ring lacks clear LoS ---
        if (best == null || best.score < 1_000_000.0D) {
            double angleStep = 2.0D * Math.PI / EXTENDED_SEARCH_DIRECTIONS;
            for (int i = 0; i < EXTENDED_SEARCH_DIRECTIONS; i++) {
                double angle = i * angleStep;
                double ox = Math.cos(angle);
                double oz = Math.sin(angle);
                for (double dist : EXTENDED_SEARCH_DISTANCES) {
                    Vec3d off = new Vec3d(ox * dist, 0.0D, oz * dist);
                    ScoredPosition sp = scoreCandidate(world, bot, target, botPos, eyeHeight, off);
                    if (sp != null && (best == null || sp.score > best.score)) {
                        best = sp;
                    }
                }
            }
        }

        // Only reposition if we found a meaningfully better angle than current stance.
        if (best == null) {
            return null;
        }
        Vec3d curEye = bot.getEyePos();
        double curVisibility = rangedVisibilityScore(world, bot, curEye, target);
        double curCover = defiladeScore(world, botPos, eyeHeight, target);
        double curScore = curVisibility + curCover;
        if (best.score <= curScore + 0.5D) {
            return null;
        }
        return best.stand;
    }

    /**
     * Score a single candidate offset for ranged repositioning.
     * Evaluates visibility (can we hit the target?), defilade cover (is return fire blocked?),
     * and movement cost (how far do we have to move?).
     */
    private static ScoredPosition scoreCandidate(ServerWorld world, ServerPlayerEntity bot, LivingEntity target,
                                                  Vec3d botPos, double eyeHeight, Vec3d offset) {
        BlockPos base = BlockPos.ofFloored(botPos.x + offset.x, botPos.y, botPos.z + offset.z);
        Vec3d stand = resolveNearbyStandPos(world, base);
        if (stand == null) {
            return null;
        }
        double moveCost = stand.squaredDistanceTo(botPos);
        Vec3d eye = stand.add(0.0D, eyeHeight, 0.0D);

        double visibility = rangedVisibilityScore(world, bot, eye, target);
        double cover = defiladeScore(world, stand, eyeHeight, target);
        double score = visibility + cover - moveCost * 2.0D;
        return new ScoredPosition(stand, score);
    }

    /**
     * Evaluate how much cover a candidate position provides from the target's return fire.
     * Checks raycasts from target's eye to 3 points on the bot (head, torso, legs).
     * More blocked rays = better defilade cover.
     */
    private static double defiladeScore(ServerWorld world, Vec3d candidateStandPos, double eyeHeight, LivingEntity target) {
        Vec3d targetEye = target.getEyePos();
        Vec3d[] botPoints = {
                candidateStandPos.add(0.0D, eyeHeight, 0.0D),        // head
                candidateStandPos.add(0.0D, eyeHeight * 0.5D, 0.0D), // torso
                candidateStandPos.add(0.0D, 0.3D, 0.0D)              // legs
        };
        int blocked = 0;
        for (Vec3d point : botPoints) {
            RaycastContext ctx = new RaycastContext(
                    targetEye, point,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    target
            );
            BlockHitResult hit = world.raycast(ctx);
            if (hit.getType() == HitResult.Type.BLOCK) {
                blocked++;
            }
        }
        return blocked * DEFILADE_COVER_BONUS;
    }

    private static Vec3d resolveNearbyStandPos(ServerWorld world, BlockPos base) {
        if (world == null || base == null) {
            return null;
        }
        // Probe a small vertical window to cope with uneven terrain.
        int[] dyOrder = new int[]{0, 1, -1, 2, -2};
        for (int dy : dyOrder) {
            BlockPos pos = base.up(dy);
            if (!hasMovementClearance(world, pos)) {
                continue;
            }
            // Avoid stepping into fluids.
            if (!world.getFluidState(pos).isEmpty()) {
                continue;
            }
            BlockPos below = pos.down();
            BlockState belowState = world.getBlockState(below);
            if (belowState.isAir()) {
                continue;
            }
            if (belowState.getCollisionShape(world, below).isEmpty()) {
                continue;
            }
            return new Vec3d(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        }
        return null;
    }

    private static double rangedVisibilityScore(ServerWorld world, ServerPlayerEntity bot, Vec3d fromEye, LivingEntity target) {
        // Score is the best (most open) among several aim points on the target.
        // If ANY point is clear (no block collision), return a large value.
        Vec3d[] aimPoints = new Vec3d[]{
                target.getEyePos(),
                new Vec3d(target.getX(), target.getBodyY(0.333333333333d), target.getZ()),
                new Vec3d(target.getX(), target.getBodyY(0.55D), target.getZ())
        };

        double bestBlockedDist = 0.0D;
        for (Vec3d aim : aimPoints) {
            RaycastContext ctx = new RaycastContext(
                    fromEye,
                    aim,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    bot
            );
            BlockHitResult hit = world.raycast(ctx);
            if (hit.getType() == HitResult.Type.MISS) {
                return 1_000_000.0D;
            }
            if (hit.getType() == HitResult.Type.BLOCK) {
                double dist = fromEye.distanceTo(hit.getPos());
                if (dist > bestBlockedDist) {
                    bestBlockedDist = dist;
                }
            }
        }
        return bestBlockedDist;
    }

    /**
     * Choose an aim point on the target that is not blocked by terrain.
     * If none are clear, falls back to the usual torso-ish point.
     */
    private static Vec3d pickPreferredAimPoint(ServerWorld world, ServerPlayerEntity bot, Vec3d fromEye, LivingEntity target) {
        if (world == null || bot == null || fromEye == null || target == null) {
            return null;
        }
        Vec3d body = new Vec3d(target.getX(), target.getBodyY(0.333333333333d), target.getZ());
        Vec3d eye = target.getEyePos();
        Vec3d mid = new Vec3d(target.getX(), target.getBodyY(0.55D), target.getZ());

        // Priority: body (natural), then eye (often above dripstone spikes), then mid.
        Vec3d[] candidates = new Vec3d[]{body, eye, mid};
        for (Vec3d aim : candidates) {
            RaycastContext ctx = new RaycastContext(
                    fromEye,
                    aim,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    bot
            );
            BlockHitResult hit = world.raycast(ctx);
            if (hit.getType() == HitResult.Type.MISS) {
                return aim;
            }
        }
        return body;
    }

    private static boolean isRangedLineBlocked(ServerWorld world, ServerPlayerEntity bot, Vec3d fromEye, LivingEntity target) {
        if (world == null || bot == null || fromEye == null || target == null) {
            return false;
        }
        Vec3d aim = pickPreferredAimPoint(world, bot, fromEye, target);
        if (aim == null) {
            return false;
        }
        RaycastContext ctx = new RaycastContext(
                fromEye,
                aim,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot
        );
        BlockHitResult hit = world.raycast(ctx);
        return hit.getType() != HitResult.Type.MISS;
    }

    /**
     * Returns true if the bot's current ranged line to the target is blocked by terrain.
     * Used by engageHostiles to decide whether to fall through to melee approach.
     */
    public static boolean isRangedLineCurrentlyBlocked(ServerPlayerEntity bot, LivingEntity target) {
        if (bot == null || target == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        return isRangedLineBlocked(world, bot, bot.getEyePos(), target);
    }

    /** Cancel any committed reposition in progress (e.g. when LoS clears or target changes). */
    public static void cancelCommittedReposition(ServerPlayerEntity bot) {
        if (bot == null) return;
        RangedAttackState state = RANGED_STATE.get(bot.getUuid());
        if (state != null) {
            state.committedRepositionTarget = null;
        }
    }

    public static void resetRangedState(UUID uuid) {
        if (uuid == null) {
            return;
        }
        RangedAttackState state = RANGED_STATE.remove(uuid);
        if (state != null) {
            state.forceMelee = false;
        }
    }

    public static void clearForceMelee(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        RangedAttackState state = RANGED_STATE.get(bot.getUuid());
        if (state != null) {
            state.forceMelee = false;
            state.lowAngleStreak = 0;
        }
    }

    public static boolean useHoe(ServerPlayerEntity bot, BlockPos targetPos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        var auth = BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, targetPos);
        if (!auth.allowed()) {
            return false;
        }
        int hoeSlot = findHoeSlot(bot);
        if (hoeSlot == -1) {
            return false; // No hoe found
        }

        selectHotbarSlot(bot, hoeSlot);

        // Simulate right-click on the block
        ItemStack hoeStack = bot.getMainHandStack();
        ActionResult result = hoeStack.useOnBlock(new net.minecraft.item.ItemUsageContext(bot, Hand.MAIN_HAND, new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false)));

        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
            bot.addExhaustion(0.02F); // Using a hoe causes exhaustion
            return true;
        }
        return false;
    }

    private static int findHoeSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.item.HoeItem) {
                return i;
            }
        }
        return -1;
    }

    private record Selection(Hand hand, ItemStack stack) {}



    private static class RangedAttackState {
        long chargeStartTick = 0L;
        long cooldownTick = 0L;
        UUID currentTarget = null;
        int lowAngleStreak = 0;
        boolean forceMelee = false;

        // Miss/reposition bookkeeping.
        long lastShotTick = -1L;
        long lastMissTick = -1L;
        int missStreak = 0;
        int blockedShotStreak = 0;
        long repositionCooldownTick = 0L;
        long repositioningUntilTick = 0L;

        // Committed multi-tick reposition for distant moves.
        Vec3d committedRepositionTarget = null;
        long committedRepositionStartTick = 0L;

        void ensureTarget(LivingEntity target) {
            UUID targetUuid = target.getUuid();
            if (!Objects.equals(currentTarget, targetUuid)) {
                currentTarget = targetUuid;
                lowAngleStreak = 0;
                forceMelee = false;

                lastShotTick = -1L;
                lastMissTick = -1L;
                missStreak = 0;
                blockedShotStreak = 0;
                repositionCooldownTick = 0L;
                repositioningUntilTick = 0L;
                committedRepositionTarget = null;
                committedRepositionStartTick = 0L;
            }
        }

        boolean isCurrentTarget(LivingEntity target) {
            return Objects.equals(currentTarget, target.getUuid());
        }

        void recordShot(ServerPlayerEntity bot, LivingEntity target, long serverTick) {
            if (!isCurrentTarget(target)) {
                ensureTarget(target);
            }
            lastShotTick = serverTick;
            double verticalDiff = bot.getY() - target.getY();
            if (verticalDiff > 1.5D) {
                lowAngleStreak++;
                if (lowAngleStreak >= 3) {
                    forceMelee = true;
                }
            } else {
                lowAngleStreak = 0;
            }
        }

        void noteMiss(long serverTick) {
            // Only count misses that correlate with a recent shot.
            if (lastShotTick < 0L || serverTick < lastShotTick) {
                return;
            }
            if (serverTick - lastShotTick > RANGED_MISS_RECENT_SHOT_WINDOW_TICKS) {
                return;
            }
            lastMissTick = serverTick;
            missStreak = Math.min(12, missStreak + 1);
        }

        void noteBlockedCheck(boolean blockedNow, long serverTick) {
            if (blockedNow) {
                blockedShotStreak = Math.min(12, blockedShotStreak + 1);
            } else {
                blockedShotStreak = 0;
            }
        }

        void decayMisses(long serverTick) {
            if (missStreak <= 0) {
                return;
            }
            if (lastMissTick >= 0L && serverTick - lastMissTick > 60L) {
                missStreak = 0;
            }
        }
    }
}
