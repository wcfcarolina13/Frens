package net.shasankp000.GameAI;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;





import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

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

    private static final Map<UUID, RangedAttackState> RANGED_STATE = new HashMap<>();

    private BotActions() {}

    public static void moveForward(ServerPlayerEntity bot) {
        moveRelative(bot, STEP_DISTANCE, false, 0, 0);
    }

    public static void moveBackward(ServerPlayerEntity bot) {
        moveRelative(bot, -STEP_DISTANCE, false, 0, 0);
    }

    public static void moveForwardStep(ServerPlayerEntity bot, double distance) {
        moveRelative(bot, distance, false, 0, 0);
    }

    public static void moveToward(ServerPlayerEntity bot, Vec3d target, double maxStep) {
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1e-4) {
            return;
        }
        double step = Math.min(maxStep, horizontal);
        moveRelative(bot, step, true, dx / horizontal, dz / horizontal);
    }

    public static void stop(ServerPlayerEntity bot) {
        bot.setVelocity(Vec3d.ZERO);
        bot.velocityDirty = true;
        bot.setSprinting(false);
        bot.setSneaking(false);
        resetRangedState(bot);
    }

    public static void turnLeft(ServerPlayerEntity bot) {
        rotate(bot, -TURN_DEGREES);
    }

    public static void turnRight(ServerPlayerEntity bot) {
        rotate(bot, TURN_DEGREES);
    }

    public static void jump(ServerPlayerEntity bot) {
        bot.jump();
    }

    public static void sneak(ServerPlayerEntity bot, boolean value) {
        bot.setSneaking(value);
    }

    public static void sprint(ServerPlayerEntity bot, boolean value) {
        bot.setSprinting(value);
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
        bot.jump();
        moveRelative(bot, STEP_DISTANCE * 0.6, false, 0, 0);
    }

    public static void attackNearest(ServerPlayerEntity bot, List<Entity> nearbyEntities) {
        Entity target = nearbyEntities.stream()
                .filter(entity -> entity instanceof HostileEntity)
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(bot)))
                .orElse(null);

        if (target != null) {
            selectBestWeapon(bot);
            double distanceSq = target.squaredDistanceTo(bot);
            if (distanceSq <= 9.0 && bot.canSee(target)) {
                bot.attack(target);
                bot.swingHand(Hand.MAIN_HAND, true);
            }
        }
    }

    public static void useSelectedItem(ServerPlayerEntity bot) {
        ItemStack stack = bot.getMainHandStack();
        if (stack.isEmpty()) {
            return;
        }

        ActionResult result = stack.use(bot.getEntityWorld(), bot, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }

    public static void selectHotbarSlot(ServerPlayerEntity bot, int index) {
        bot.getInventory().setSelectedSlot(MathHelper.clamp(index, 0, 8));
    }

    public static boolean breakBlockAhead(ServerPlayerEntity bot) {
        ServerWorld world = bot.getCommandSource().getWorld();
        BlockPos targetPos = getRelativeBlockPos(bot, 1, 0);
        if (!world.getBlockState(targetPos).isAir() && canBreak(world, targetPos, bot, false)) {
            boolean success = breakBlock(world, targetPos, bot);
            if (success) {
                return true;
            }
        }

        // Try the block above-front if the direct block was air (stair carving)
        BlockPos upperPos = getRelativeBlockPos(bot, 1, 1);
        if (!world.getBlockState(upperPos).isAir() && canBreak(world, upperPos, bot, false)) {
            boolean success = breakBlock(world, upperPos, bot);
            if (success) {
                return true;
            }
        }

        return false;
    }

    public static boolean placeSupportBlock(ServerPlayerEntity bot) {
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
    }

    public static boolean placeBlockAt(ServerPlayerEntity bot, BlockPos target) {
        return placeBlockAt(bot, target, Collections.emptyList());
    }

    public static boolean placeBlockAt(ServerPlayerEntity bot, BlockPos target, List<Item> prioritizedBlocks) {
        ServerWorld world = bot.getCommandSource().getWorld();
        if (world == null || target == null) {
            return false;
        }
        if (!world.getBlockState(target).isAir() && world.getFluidState(target).isEmpty()) {
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
        BlockState stateToPlace = blockItem.getBlock().getDefaultState();
        if (!stateToPlace.canPlaceAt(world, target)) {
            return false;
        }
        selectHotbarSlot(bot, slot);
        boolean placed = world.setBlockState(target, stateToPlace);
        if (placed) {
            stack.decrement(1);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        return false;
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
        ServerWorld world = bot.getCommandSource().getWorld();
        if (world == null) {
            return false;
        }

        BlockPos origin = bot.getBlockPos();
        boolean brokeAny = false;

        BlockPos[] verticalTargets = new BlockPos[] {
                origin,
                origin.up(),
                origin.up(2)
        };
        for (BlockPos target : verticalTargets) {
            brokeAny |= breakBlock(world, target, bot, forceBreak);
        }

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos horizontal = origin.offset(direction);
            brokeAny |= breakBlock(world, horizontal, bot, forceBreak);
            brokeAny |= breakBlock(world, horizontal.up(), bot, forceBreak);
        }

        return brokeAny;
    }

    private static boolean canBreak(ServerWorld world, BlockPos pos, ServerPlayerEntity bot, boolean forceBreak) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isOf(net.minecraft.block.Blocks.BEDROCK)) {
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
        boolean success = world.breakBlock(pos, true, bot);
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

        bot.refreshPositionAndAngles(bot.getX() + dx, bot.getY(), bot.getZ() + dz, bot.getYaw(), bot.getPitch());
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
        BlockPos frontBelow = front.down();

        boolean obstacleAhead = !world.getBlockState(front).isAir();
        boolean headSpace = world.getBlockState(frontAbove).isAir();
        boolean gapAhead = world.getBlockState(front).isAir() && world.getBlockState(frontBelow).isAir();

        if (obstacleAhead && headSpace) {
            bot.jump();
        } else if (gapAhead) {
            bot.jump();
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

        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isRangedWeapon(stack)) {
                selectHotbarSlot(bot, i);
                return new Selection(Hand.MAIN_HAND, stack);
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
