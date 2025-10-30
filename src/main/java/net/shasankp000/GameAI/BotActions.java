package net.shasankp000.GameAI;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.BlockItem;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
        moveRelative(bot, STEP_DISTANCE);
    }

    public static void moveBackward(ServerPlayerEntity bot) {
        moveRelative(bot, -STEP_DISTANCE);
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

    public static void jumpForward(ServerPlayerEntity bot) {
        bot.jump();
        moveRelative(bot, STEP_DISTANCE * 0.6);
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
        if (!world.getBlockState(targetPos).isAir() && canBreak(world, targetPos, bot)) {
            boolean success = world.breakBlock(targetPos, true, bot);
            if (success) {
                bot.swingHand(Hand.MAIN_HAND, true);
                return true;
            }
        }

        // Try the block above-front if the direct block was air (stair carving)
        BlockPos upperPos = getRelativeBlockPos(bot, 1, 1);
        if (!world.getBlockState(upperPos).isAir() && canBreak(world, upperPos, bot)) {
            boolean success = world.breakBlock(upperPos, true, bot);
            if (success) {
                bot.swingHand(Hand.MAIN_HAND, true);
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

    public static void escapeStairs(ServerPlayerEntity bot) {
        boolean placed = placeSupportBlock(bot);
        if (!placed) {
            breakBlockAhead(bot);
        }
        jumpForward(bot);
    }

    private static boolean canBreak(ServerWorld world, BlockPos pos, ServerPlayerEntity bot) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.isOf(net.minecraft.block.Blocks.BEDROCK)) {
            return false;
        }

        float hardness = state.getHardness(world, pos);
        if (hardness < 0) {
            return false;
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

        String key = stack.getItem().getTranslationKey().toLowerCase(java.util.Locale.ROOT);
        return key.contains("sword") || key.contains("axe") || key.contains("trident") || key.contains("mace") || key.contains("dagger");
    }

    private static void moveRelative(ServerPlayerEntity bot, double distance) {
        float yaw = bot.getYaw();
        double yawRad = Math.toRadians(yaw);
        double dx = -Math.sin(yawRad) * distance;
        double dz = Math.cos(yawRad) * distance;

        bot.teleport(bot.getX() + dx, bot.getY(), bot.getZ() + dz, true);
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

    private static void rotate(ServerPlayerEntity bot, float deltaYaw) {
        float newYaw = bot.getYaw() + deltaYaw;
        bot.setYaw(newYaw);
        bot.setHeadYaw(newYaw);
        bot.setBodyYaw(newYaw);
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
