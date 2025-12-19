package net.shasankp000.GameAI;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
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
import net.minecraft.world.RaycastContext;

import net.shasankp000.GameAI.services.MovementService;




import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
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

    private static final double STEP_DISTANCE = 0.45;
    private static final float TURN_DEGREES = 20.0f;
    private static final int BOW_MIN_CHARGE_TICKS = 15;
    private static final int RANGED_COOLDOWN_TICKS = 20;
    private static final double SURVIVAL_REACH_SQ = 4.5D * 4.5D;

    private static final Map<UUID, RangedAttackState> RANGED_STATE = new HashMap<>();

    private BotActions() {}

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
        bot.velocityModified = true;
    }

    public static void stop(ServerPlayerEntity bot) {
        runOnServerThread(bot, () -> {
            bot.setVelocity(Vec3d.ZERO);
            bot.velocityDirty = true;
            bot.setSprinting(false);
            bot.setSneaking(false);
            resetRangedState(bot);
        }, 900L);
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
        if (bot.isOnGround() || bot.isTouchingWater() || bot.isInLava()) {
            bot.jump();
        }
    }

    public static void sneak(ServerPlayerEntity bot, boolean value) {
        runOnServerThread(bot, () -> bot.setSneaking(value), 900L);
    }

    public static void sprint(ServerPlayerEntity bot, boolean value) {
        runOnServerThread(bot, () -> bot.setSprinting(value), 900L);
    }

    public static boolean selectBestWeapon(ServerPlayerEntity bot) {
        int weaponSlot = findWeaponSlot(bot);
        if (weaponSlot == -1) {
            weaponSlot = findAnyOccupiedSlot(bot);
        }

        if (weaponSlot != -1) {
            selectHotbarSlot(bot, weaponSlot);
            return true;
        }

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
                .filter(entity -> entity instanceof HostileEntity)
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
            ActionResult result = stack.use(bot.getEntityWorld(), bot, Hand.MAIN_HAND);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
            }
        }, 1500L);
    }

    public static void selectHotbarSlot(ServerPlayerEntity bot, int index) {
        runOnServerThread(bot, () -> bot.getInventory().setSelectedSlot(MathHelper.clamp(index, 0, 8)), 900L);
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
        return callOnServerThread(bot, () -> {
            ServerWorld world = bot.getCommandSource().getWorld();
            if (world == null || target == null) {
                return false;
            }
            double distSq = bot.squaredDistanceTo(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            if (distSq > SURVIVAL_REACH_SQ) {
                return false;
            }
            if (!world.getBlockState(target).isAir() && world.getFluidState(target).isEmpty()) {
                // Allow replacing snow layers/blocks to avoid placement failures
                net.minecraft.block.BlockState state = world.getBlockState(target);
                if (!state.isOf(net.minecraft.block.Blocks.SNOW) && !state.isOf(net.minecraft.block.Blocks.SNOW_BLOCK)) {
                    return false;
                }
                world.breakBlock(target, false);
            }
            // Avoid placing while standing inside the target
            if (bot.getBoundingBox().intersects(new net.minecraft.util.math.Box(target))) {
                // Allow jump-pillaring: if the bot is airborne and placing into its current foot block,
                // vanilla collision resolution will move the player upward instead of trapping them.
                boolean allowJumpPillar = !bot.isOnGround()
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
                if (!allowJumpPillar) {
                    return false;
                }
            }
            Support support = resolvePlacementSupport(world, target, face);
            if (support == null) {
                return false;
            }
            int slot = findPreferredBlockItemSlot(bot, prioritizedBlocks);
            if (slot == -1) {
                return false;
            }
            PlayerInventory inventory = bot.getInventory();
            slot = ensureHotbarAccess(bot, inventory, slot);
            ItemStack stack = inventory.getStack(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            selectHotbarSlot(bot, slot);
            BlockHitResult hit = computePlacementHit(world, bot, support);
            if (hit == null) {
                return false;
            }
            ItemUsageContext usage = new ItemUsageContext(bot, Hand.MAIN_HAND, hit);
            ItemPlacementContext placementContext = new ItemPlacementContext(usage);
            ActionResult result = blockItem.place(placementContext);
            if (result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
                if (stack.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                }
                return true;
            }
            return false;
        }, 3500L, false);
    }

    private record Support(BlockPos clickPos, Direction face) {}

    /**
     * Resolve the actual block face we'd "right click" to place into {@code target}.
     * This prevents phantom placements where the support check passes but the clickPos is air.
     */
    private static Support resolvePlacementSupport(ServerWorld world, BlockPos target, Direction preferredFace) {
        if (world == null || target == null) {
            return null;
        }
        Direction preferred = preferredFace == null ? Direction.UP : preferredFace;

        // Prefer placing by clicking the block below (common for floors, pillars, etc).
        BlockPos below = target.down();
        if (preferred == Direction.UP && world.getBlockState(below).isSolidBlock(world, below)) {
            return new Support(below, Direction.UP);
        }

        // Otherwise use a solid horizontal neighbor face pointing toward the target.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = target.offset(dir);
            if (world.getBlockState(neighbor).isSolidBlock(world, neighbor)) {
                return new Support(neighbor, dir.getOpposite());
            }
        }

        // Last resort: if below is solid, allow non-UP preferred faces to still click below.
        if (world.getBlockState(below).isSolidBlock(world, below)) {
            return new Support(below, Direction.UP);
        }
        return null;
    }

    private static BlockHitResult computePlacementHit(ServerWorld world, ServerPlayerEntity bot, Support support) {
        if (world == null || bot == null || support == null) {
            return null;
        }
        Vec3d start = bot.getEyePos();
        Vec3d end = pointOnFace(support.clickPos(), support.face());
        RaycastContext ctx = new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot
        );
        BlockHitResult hit = world.raycast(ctx);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        if (!support.clickPos().equals(hit.getBlockPos())) {
            return null;
        }
        if (hit.getSide() != support.face()) {
            return null;
        }
        return hit;
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

    private static int findAnyOccupiedSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            if (!bot.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
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
                        // No safe step up or down -> don't move to avoid suffocation/clipping.
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
        BlockPos front = getRelativeBlockPos(bot, 1, 0);
        BlockPos frontAbove = front.up();

        boolean obstacleAhead = !world.getBlockState(front).isAir();
        boolean headSpace = world.getBlockState(frontAbove).isAir();

        if (obstacleAhead && headSpace) {
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

        // face target
        double targetX = target.getX();
        double targetY = target.getBodyY(0.333333333333d);
        double targetZ = target.getZ();
        double dx = targetX - bot.getX();
        double dy = targetY - bot.getEyeY();
        double dz = targetZ - bot.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
        bot.setPitch(pitch);

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
                bot.stopUsingItem();
                state.cooldownTick = serverTick + RANGED_COOLDOWN_TICKS;
                state.chargeStartTick = 0L;
                state.recordShot(bot, target);
            }
            return true;
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
            float velocity = 1.6F;
            float divergence = 14 - bot.getEntityWorld().getDifficulty().getId() * 4;
            crossbow.shootAll(bot.getEntityWorld(), bot, hand, stack, velocity, divergence, target);
            state.cooldownTick = serverTick + RANGED_COOLDOWN_TICKS;
            state.recordShot(bot, target);
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
        return selectBestRangedWeapon(bot) != null;
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
        return stack.getItem() instanceof net.minecraft.item.BowItem ||
                stack.getItem() instanceof net.minecraft.item.CrossbowItem ||
                stack.getItem() instanceof net.minecraft.item.TridentItem;
    }

    public static void resetRangedState(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        RangedAttackState state = RANGED_STATE.remove(bot.getUuid());
        if (state != null) {
            bot.stopUsingItem();
            state.forceMelee = false;
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
        int hoeSlot = findHoeSlot(bot);
        if (hoeSlot == -1) {
            return false; // No hoe found
        }

        selectHotbarSlot(bot, hoeSlot);

        // Simulate right-click on the block
        ServerWorld world = bot.getCommandSource().getWorld();
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

        void ensureTarget(LivingEntity target) {
            UUID targetUuid = target.getUuid();
            if (!Objects.equals(currentTarget, targetUuid)) {
                currentTarget = targetUuid;
                lowAngleStreak = 0;
                forceMelee = false;
            }
        }

        boolean isCurrentTarget(LivingEntity target) {
            return Objects.equals(currentTarget, target.getUuid());
        }

        void recordShot(ServerPlayerEntity bot, LivingEntity target) {
            if (!isCurrentTarget(target)) {
                ensureTarget(target);
            }
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
    }
}
