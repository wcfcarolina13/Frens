package net.wcfcarolina13.GameAI;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.EntityUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.SneakLockService;
import net.wcfcarolina13.GameAI.services.BotArrowRecoveryService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.CompanionSafeZoneService;
import net.wcfcarolina13.GameAI.services.FoodConsumptionConfirmationService;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.HotbarLockService;
import net.wcfcarolina13.GameAI.services.ProtectedStructureBlockHelper;




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
import java.util.concurrent.ConcurrentHashMap;
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
    private static final double TRIDENT_THROW_MIN_DISTANCE = 7.0D;
    private static final double TRIDENT_THROW_CROWD_RADIUS = 4.0D;
    private static final double SPEAR_MIN_EFFECTIVE_DISTANCE = 1.35D;
    private static final double SPEAR_PREFERRED_CHARGE_DISTANCE = 2.35D;
    private static final double SPEAR_MAX_REACH_DISTANCE = 4.25D;

    private static final Map<UUID, RangedAttackState> RANGED_STATE = new HashMap<>();
    private static final Map<UUID, String> LAST_COMBAT_PROFILE = new HashMap<>();

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
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        boolean waterLike = isWaterLikeMovementContext(bot, world);
        double tunedImpulse = maxImpulse;
        if (waterLike) {
            tunedImpulse = Math.min(tunedImpulse, bot.isSubmergedInWater() ? 0.055D : 0.038D);
        }
        Vec3d impulse = delta.normalize().multiply(tunedImpulse);

        // Clamp horizontal velocity so repeated inputs do not spike speed. The cap is
        // sprint-aware: when the bot is flagged as sprinting (follow-catchup, flee, etc.),
        // allow a higher ceiling so the sprint impulse can actually accumulate past walk-
        // pace ground friction. Without this, the bot animates "sprint" but runs at walk
        // speed because its velocity never breaks past the walk cap.
        Vec3d current = bot.getVelocity();
        Vec3d horiz = new Vec3d(current.x, 0, current.z);
        double horizMag = horiz.length();
        double maxHoriz;
        if (waterLike) {
            maxHoriz = bot.isSubmergedInWater() ? 0.18D : 0.12D;
        } else if (bot.isSprinting()) {
            maxHoriz = 0.58D;
        } else {
            maxHoriz = 0.45D;
        }
        if (horizMag > maxHoriz) {
            double scale = maxHoriz / horizMag;
            current = new Vec3d(horiz.x * scale, current.y, horiz.z * scale);
            bot.setVelocity(current);
        }

        if (world != null) {
            // Self-heal: if the bot ended up standing inside a closed-door cell (e.g., the
            // door slammed shut behind it, or a race between the door-plan open step and a
            // vanilla auto-close), open the door so vanilla physics can carry the bot
            // through on the next tick. Without this, isPassableForMovement rejects the
            // feet cell indefinitely (a closed door fails the OPEN-property gate), and the
            // bot freezes until wolf-teleport or /rescue fires. Throttled per-bot.
            maybeAutoOpenCurrentDoor(bot, world);
            // Observe doorway traversals: cheap fast-path inside the service (returns
            // immediately when the bot's foot cell is unchanged from last tick).
            net.wcfcarolina13.GameAI.services.navigation.PassageAnchorService.onBotTick(bot, world);
            if (!canAcceptMovementImpulse(world, pos.x + impulse.x, bot.getY(), pos.z + impulse.z)) {
                net.wcfcarolina13.GameAI.services.navigation.NavHazardCache.recordRejection(
                        world, pos.x + impulse.x, bot.getY(), pos.z + impulse.z);
                diagnoseOccupancyRejection(bot, world, pos.x + impulse.x, bot.getY(), pos.z + impulse.z);
                return;
            }
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
        if (!isRangedWeapon(bot, active)) {
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
        if (bot == null) {
            return false;
        }
        PlayerInventory inventory = bot.getInventory();
        ItemStack priorHeld = bot.getMainHandStack();
        boolean priorWasFiltered = !priorHeld.isEmpty()
                && DurabilityPolicyService.shouldAvoid(bot, priorHeld);

        // First pass: compliant weapon
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            int score = combatWeaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }

        if (bestSlot != -1 && bestScore > 0) {
            int hotbarSlot = ensureHotbarAccess(bot, inventory, bestSlot);
            selectHotbarSlot(bot, hotbarSlot);
            boolean swapped = combatWeaponScore(bot.getMainHandStack()) > 0;
            if (swapped && priorWasFiltered) {
                CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
            }
            return swapped;
        }

        // Second pass: compliant mining tool as melee fallback
        int fallbackSlot = -1;
        int fallbackScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            int score = meleeFallbackToolScore(stack);
            if (score > fallbackScore) {
                fallbackScore = score;
                fallbackSlot = slot;
            }
        }

        if (fallbackSlot != -1 && fallbackScore > 0) {
            int hotbarSlot = ensureHotbarAccess(bot, inventory, fallbackSlot);
            selectHotbarSlot(bot, hotbarSlot);
            // Request fallback refresh so the fallback service tries to get a real weapon
            DurabilityFallbackService.requestRefresh(bot, DurabilityFallbackService.GearCategory.SWORD);
            if (priorWasFiltered) {
                CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
            }
            return true;
        }

        // No weapon or fallback tool found — fall back to fists rather than swinging food/blocks.
        // Request refresh if the bot was holding a preserved weapon that got filtered.
        ItemStack held = bot.getMainHandStack();
        if (!held.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, held)) {
            DurabilityFallbackService.requestRefresh(bot, DurabilityFallbackService.GearCategory.SWORD);
        }
        selectBareHandsForCombat(bot, "no-combat-weapon");
        return false;
    }

    public static boolean selectBestMeleeWeapon(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        PlayerInventory inventory = bot.getInventory();
        ItemStack priorHeld = bot.getMainHandStack();
        boolean priorWasFiltered = !priorHeld.isEmpty()
                && DurabilityPolicyService.shouldAvoid(bot, priorHeld);

        // First pass: compliant melee weapon
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            int score = meleeWeaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }

        if (bestSlot != -1 && bestScore > 0) {
            int hotbarSlot = ensureHotbarAccess(bot, inventory, bestSlot);
            selectHotbarSlot(bot, hotbarSlot);
            if (meleeWeaponScore(bot.getMainHandStack()) > 0) {
                if (priorWasFiltered) {
                    CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
                }
                return true;
            }
        }

        // Second pass: compliant mining tool as melee fallback
        int fallbackSlot = -1;
        int fallbackScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            int score = meleeFallbackToolScore(stack);
            if (score > fallbackScore) {
                fallbackScore = score;
                fallbackSlot = slot;
            }
        }

        if (fallbackSlot != -1 && fallbackScore > 0) {
            int hotbarSlot = ensureHotbarAccess(bot, inventory, fallbackSlot);
            selectHotbarSlot(bot, hotbarSlot);
            DurabilityFallbackService.requestRefresh(bot, DurabilityFallbackService.GearCategory.SWORD);
            if (priorWasFiltered) {
                CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
            }
            return true;
        }

        // Request refresh if held is filtered
        ItemStack held = bot.getMainHandStack();
        if (!held.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, held)) {
            DurabilityFallbackService.requestRefresh(bot, DurabilityFallbackService.GearCategory.SWORD);
        }
        selectBareHandsForCombat(bot, "no-melee-weapon");
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

    public static boolean selectHarvestToolOrHands(ServerPlayerEntity bot, String preferKeyword) {
        if (bot == null) {
            return false;
        }
        String normalizedPrefer = preferKeyword != null ? preferKeyword.toLowerCase(Locale.ROOT) : null;
        PlayerInventory inventory = bot.getInventory();
        int harmlessHotbarSlot = -1;
        int emptyHotbarSlot = -1;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                if (emptyHotbarSlot == -1) {
                    emptyHotbarSlot = slot;
                }
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (normalizedPrefer != null && key.contains(normalizedPrefer) && !isCombatClassItem(stack)) {
                selectHotbarSlot(bot, slot);
                return true;
            }
            if (harmlessHotbarSlot == -1 && isHarmlessHarvestFallback(stack)) {
                harmlessHotbarSlot = slot;
            }
        }

        int preferredMainSlot = -1;
        int harmlessMainSlot = -1;
        for (int slot = 9; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
            if (normalizedPrefer != null && key.contains(normalizedPrefer) && !isCombatClassItem(stack)) {
                preferredMainSlot = slot;
                break;
            }
            if (harmlessMainSlot == -1 && isHarmlessHarvestFallback(stack)) {
                harmlessMainSlot = slot;
            }
        }

        if (preferredMainSlot != -1) {
            int hotbarTarget = emptyHotbarSlot != -1 ? emptyHotbarSlot : (harmlessHotbarSlot != -1 ? harmlessHotbarSlot : 0);
            swapInventoryStacks(inventory, preferredMainSlot, hotbarTarget);
            selectHotbarSlot(bot, hotbarTarget);
            return true;
        }
        if (emptyHotbarSlot != -1) {
            selectHotbarSlot(bot, emptyHotbarSlot);
            return true;
        }
        if (harmlessHotbarSlot != -1) {
            selectHotbarSlot(bot, harmlessHotbarSlot);
            return true;
        }
        if (harmlessMainSlot != -1) {
            swapInventoryStacks(inventory, harmlessMainSlot, 0);
            selectHotbarSlot(bot, 0);
            return true;
        }
        return false;
    }

    public static boolean isCombatClassItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String key = stack.getItem().getTranslationKey();
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("sword")
                || lower.contains("trident")
                || lower.contains("mace")
                || lower.contains("spear")
                || lower.contains("dagger")
                || lower.contains("bow")
                || lower.contains("crossbow")
                || lower.contains("shield");
    }

    private static boolean isHarmlessHarvestFallback(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && !isCombatClassItem(stack)
                && stack.getMiningSpeedMultiplier(Blocks.STONE.getDefaultState()) <= 1.0f;
    }

    public static void jumpForward(ServerPlayerEntity bot) {
        jump(bot);
        moveRelative(bot, STEP_DISTANCE * 0.6, false, 0, 0);
    }

    /** Per-bot tick of last crit jump to avoid spam jumping. */
    private static final Map<UUID, Long> LAST_CRIT_JUMP_TICK = new HashMap<>();

    /**
     * Attacks a specific pre-selected target if within melee reach, visible,
     * and the attack cooldown has sufficiently reset (>= 0.9).
     * <p>
     * Also initiates a jump for critical hits: when cooldown is mid-reset (0.4–0.6)
     * and conditions allow, the bot jumps so it is falling by the time it swings.
     * Vanilla critical hit: 50% bonus damage when falling, not sprinting, not in water.
     * <p>
     * Caller is responsible for weapon selection beforehand.
     */
    public static void attackTarget(ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null) return;
        ItemStack mainHand = bot.getMainHandStack();
        boolean spear = isSpear(mainHand);
        double maxReach = spear ? SPEAR_MAX_REACH_DISTANCE : 3.0D;
        double distanceSq = target.squaredDistanceTo(bot);
        if (distanceSq > maxReach * maxReach || !bot.canSee(target)) return;
        if (spear && distanceSq < SPEAR_MIN_EFFECTIVE_DISTANCE * SPEAR_MIN_EFFECTIVE_DISTANCE) return;
        if (!ensureMeleeCombatReady(bot, target)) return;

        maybeLogCombatProfile(bot, describeMeleeProfile(bot.getMainHandStack()));
        float cooldown = bot.getAttackCooldownProgress(0.5f);

        // Crit jump: initiate jump mid-cooldown so we're falling when we swing.
        // Sweep attacks only work with swords and require staying on ground, so when
        // surrounded by 2+ hostiles AND holding a sword, prefer sweep over crit.
        // Non-sword weapons (axe, mace, trident) never sweep, so always prefer crits.
        // The mace especially benefits — its smash attack deals bonus fall-distance damage.
        // Spears are excluded — their charge attack benefits from horizontal velocity (sprint),
        // not from falling. The bot should sprint into targets with a spear, not jump.
        // Conditions: on ground, not sprinting, not in water, not already jumped recently.
        if (cooldown >= 0.4f && cooldown <= 0.6f
                && !isSpear(mainHand)
                && bot.isOnGround() && !bot.isSprinting()
                && !bot.isTouchingWater() && !bot.isSubmergedInWater()) {
            boolean preferSweep = false;
            if (isSword(mainHand)) {
                long nearbyHostiles = bot.getEntityWorld()
                        .getOtherEntities(bot, bot.getBoundingBox().expand(2.0), EntityUtil::isHostile)
                        .size();
                preferSweep = nearbyHostiles >= 2;
            }
            if (!preferSweep) {
                long serverTick = bot.getCommandSource().getServer().getTicks();
                long lastJump = LAST_CRIT_JUMP_TICK.getOrDefault(bot.getUuid(), 0L);
                if (serverTick - lastJump >= 20) { // at most once per second
                    bot.jump();
                    LAST_CRIT_JUMP_TICK.put(bot.getUuid(), serverTick);
                }
            }
        }

        if (spear && cooldown >= 0.7f && bot.isOnGround() && !isWaterLikeMovementContext(bot, bot.getEntityWorld() instanceof ServerWorld sw ? sw : null)) {
            sprint(bot, true);
        }

        // Swing when cooldown is ready (may be a crit if we're mid-fall from the jump above).
        if (cooldown >= 0.9f) {
            bot.attack(target);
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }

    public static void attackNearest(ServerPlayerEntity bot, List<Entity> nearbyEntities) {
        Entity target = nearbyEntities.stream()
                .filter(EntityUtil::isHostile)
                .filter(e -> e.getType() != net.minecraft.entity.EntityType.GHAST) // ranged/deflect only
                .filter(e -> e.getType() != net.minecraft.entity.EntityType.PHANTOM
                        || (e.getY() - bot.getY()) <= 3.0) // melee only on low phantoms
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(bot)))
                .orElse(null);

        if (target != null) {
            if (!selectBestMeleeWeapon(bot)) {
                selectBestWeapon(bot);
            }
            attackTarget(bot, target);
        }
    }

    /**
     * Returns true if the phantom is in a "shootable" dive state:
     * descending rapidly and within a reasonable altitude above the bot.
     * A phantom circling 20 blocks up is NOT shootable.
     */
    public static boolean isPhantomDiving(ServerPlayerEntity bot, Entity phantom) {
        if (bot == null || phantom == null) return false;
        double altitudeDiff = phantom.getY() - bot.getY();
        if (altitudeDiff > 10.0) return false;   // too high — still circling
        if (altitudeDiff < -2.0) return false;    // already past / below bot
        Vec3d velocity = phantom.getVelocity();
        return velocity.y < -0.15;                // descending = diving
    }

    /**
     * Finds the nearest position within searchRadius blocks that has a solid block
     * overhead (not sky-visible) and is walkable. Used for phantom evasion when the
     * bot lacks a ranged weapon. Returns the bot's current position if already covered,
     * or null if no cover is found.
     */
    public static BlockPos findNearestOverheadCover(ServerPlayerEntity bot, int searchRadius) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return null;
        BlockPos botPos = bot.getBlockPos();

        // Already under cover
        if (!world.isSkyVisible(botPos.up(2))) return botPos;

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int r = 1; r <= searchRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // shell only
                    BlockPos candidate = botPos.add(dx, 0, dz);
                    // Must have overhead cover and be walkable
                    if (world.isSkyVisible(candidate.up(2))) continue;
                    BlockState floor = world.getBlockState(candidate.down());
                    BlockState feet = world.getBlockState(candidate);
                    BlockState head = world.getBlockState(candidate.up());
                    if (!floor.isSolidBlock(world, candidate.down())) continue;
                    if (!feet.isReplaceable()
                            && feet.getCollisionShape(world, candidate)
                            != net.minecraft.util.shape.VoxelShapes.empty()) continue;
                    if (!head.isReplaceable()
                            && head.getCollisionShape(world, candidate.up())
                            != net.minecraft.util.shape.VoxelShapes.empty()) continue;
                    double distSq = botPos.getSquaredDistance(candidate);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = candidate.toImmutable();
                    }
                }
            }
            if (best != null) return best; // found cover at this ring, stop
        }
        return best;
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
        if (ProtectedStructureBlockHelper.isProtectedGlassLike(state)) {
            return false;
        }
        // Never destroy player-built roads.
        if (state.isOf(Blocks.DIRT_PATH)) {
            return false;
        }
        // Generic movement/unstuck breaking must stay conservative around player structures and villages.
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOOL)) {
            return false;
        }
        if (CompanionSafeZoneService.isProtected(world, pos, null)) {
            LOGGER.info("Generic break rejected for {} at {} due to protected zone",
                    bot != null ? bot.getName().getString() : "unknown-bot",
                    pos.toShortString());
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
        // Face the block before breaking (vanilla parity)
        LookController.faceBlock(bot, pos);
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

        if (stack.isOf(Items.TRIDENT)) return 40;  // dual-purpose melee/thrown

        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.contains("sword"))  return 100; // sweep attacks
        if (key.contains("mace"))   return 85;  // smash attack (fall-distance bonus)
        if (key.contains("spear"))  return 80;  // charge attack (horizontal velocity bonus)
        if (key.contains("axe"))    return 70;  // can disable shields
        return 0;
    }

    private static int combatWeaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        int meleeScore = meleeWeaponScore(stack);
        if (meleeScore > 0) {
            return meleeScore;
        }
        if (stack.getItem() instanceof BowItem) {
            return 55;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return 60;
        }
        return 0;
    }

    /**
     * Scores a stack as a "melee fallback tool" for use when no compliant weapon
     * is available. Axes already return positive values from {@link #meleeWeaponScore}
     * and are handled by the first pass; this covers pickaxe/shovel/hoe only.
     */
    private static int meleeFallbackToolScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.endsWith("_pickaxe")) return 55;
        if (key.endsWith("_shovel"))  return 40;
        if (key.endsWith("_hoe"))     return 35;
        return 0;
    }

    /** Returns true if the item stack is a sword (supports sweep attacks). */
    public static boolean isSword(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT).contains("sword");
    }

    /** Returns true if the item stack is a spear (benefits from charge/sprint attacks). */
    public static boolean isSpear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT).contains("spear");
    }

    public static double getPreferredMeleeStopDistance(ItemStack stack) {
        return isSpear(stack) ? 3.0D : 2.5D;
    }

    public static double getPreferredMeleeEngageDistance(ItemStack stack) {
        return isSpear(stack) ? 4.0D : 3.0D;
    }

    public static boolean shouldReopenSpearSpacing(ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null || !isSpear(bot.getMainHandStack())) {
            return false;
        }
        return bot.squaredDistanceTo(target) < SPEAR_MIN_EFFECTIVE_DISTANCE * SPEAR_MIN_EFFECTIVE_DISTANCE;
    }

    public static boolean shouldPressSpearCharge(ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null || !isSpear(bot.getMainHandStack())) {
            return false;
        }
        if (!bot.canSee(target)) {
            return false;
        }
        double distanceSq = bot.squaredDistanceTo(target);
        return distanceSq >= SPEAR_PREFERRED_CHARGE_DISTANCE * SPEAR_PREFERRED_CHARGE_DISTANCE
                && distanceSq <= SPEAR_MAX_REACH_DISTANCE * SPEAR_MAX_REACH_DISTANCE
                && !isWaterLikeMovementContext(bot, bot.getEntityWorld() instanceof ServerWorld sw ? sw : null);
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
        return key.contains("sword") || key.contains("axe") || key.contains("trident") || key.contains("mace") || key.contains("spear");
    }

    public static boolean hasAnyTrident(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(Items.TRIDENT)) {
                return true;
            }
        }
        return false;
    }

    private static int getEnchantmentLevel(ServerPlayerEntity bot, ItemStack stack, RegistryKey<Enchantment> key) {
        if (bot == null || stack == null || stack.isEmpty() || key == null
                || bot.getEntityWorld() == null || bot.getEntityWorld().getRegistryManager() == null) {
            return 0;
        }
        ItemEnchantmentsComponent enchantments = stack.getOrDefault(
                DataComponentTypes.ENCHANTMENTS,
                ItemEnchantmentsComponent.DEFAULT);
        if (enchantments == null || enchantments == ItemEnchantmentsComponent.DEFAULT) {
            return 0;
        }
        Registry<Enchantment> registry = bot.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        RegistryEntry<Enchantment> entry = registry.getOptional(key).orElse(null);
        if (entry == null) {
            return 0;
        }
        return enchantments.getLevel(entry);
    }

    private static boolean hasRiptide(ServerPlayerEntity bot, ItemStack stack) {
        return stack != null
                && stack.isOf(Items.TRIDENT)
                && getEnchantmentLevel(bot, stack, Enchantments.RIPTIDE) > 0;
    }

    private static boolean ensureMeleeCombatReady(ServerPlayerEntity bot, Entity target) {
        if (bot == null) {
            return false;
        }
        ItemStack held = bot.getMainHandStack();
        if (meleeWeaponScore(held) > 0 || held.isEmpty()) {
            return true;
        }

        if (selectBestMeleeWeapon(bot)) {
            return true;
        }
        if (selectBestWeapon(bot) && meleeWeaponScore(bot.getMainHandStack()) > 0) {
            return true;
        }

        ItemStack sanitized = bot.getMainHandStack();
        if (sanitized.isEmpty()) {
            return true;
        }

        LOGGER.warn("Combat attack suppressed for {} against {} because main hand is not combat-ready: {}",
                bot.getName().getString(),
                target != null ? target.getName().getString() : "unknown-target",
                sanitized.getName().getString());
        return false;
    }

    private static void selectBareHandsForCombat(ServerPlayerEntity bot, String reason) {
        if (bot == null) {
            return;
        }
        PlayerInventory inventory = bot.getInventory();
        int selectedSlot = MathHelper.clamp(inventory.getSelectedSlot(), 0, 8);
        ItemStack selected = inventory.getStack(selectedSlot);
        if (selected.isEmpty()) {
            return;
        }

        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i).isEmpty()) {
                selectHotbarSlot(bot, i);
                LOGGER.info("Combat hand sanitized for {} via empty hotbar slot {} ({})",
                        bot.getName().getString(), i, reason);
                return;
            }
        }

        for (int i = 9; i < PlayerInventory.MAIN_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                swapInventoryStacks(inventory, selectedSlot, i);
                selectHotbarSlot(bot, selectedSlot);
                LOGGER.info("Combat hand sanitized for {} by moving {} to inventory slot {} ({})",
                        bot.getName().getString(),
                        selected.getName().getString(),
                        i,
                        reason);
                return;
            }
        }

        LOGGER.warn("Combat hand for {} could not be cleared to fists; inventory full and selected item is {} ({})",
                bot.getName().getString(),
                selected.getName().getString(),
                reason);
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

        if (world != null && !canOccupyPosition(bot, world, newX, newY, newZ)) {
            return;
        }

        bot.refreshPositionAndAngles(newX, newY, newZ, bot.getYaw(), bot.getPitch());
    }
    
    /**
     * Checks if a position has clearance for the bot (2 blocks tall).
     * Prevents bot from moving into walls that would cause suffocation.
     */
    @SuppressWarnings("deprecation")
    private static boolean hasMovementClearance(ServerWorld world, BlockPos pos) {
        BlockPos headPos = pos.up();
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(headPos);
        return isPassableForMovement(feet, world, pos) && isPassableForMovement(head, world, headPos);
    }

    /**
     * Authoritative "can the bot step into this cell" check. {@code blocksMovement()} is a
     * static block-class flag set at registration time; in 1.21.11 it returns {@code true}
     * for pressure plates, doors, gates, and trapdoors regardless of their actual passability.
     * The diagnostic log in 1.1.11 confirmed an Oak Pressure Plate was returning
     * {@code hasClearance=false} from this check even with the bot standing on it, which
     * stalled the bot outside the doorway indefinitely. Three gates now:
     * <ol>
     *   <li>Air or {@code !blocksMovement()} → passable (old check, kept for fast path).</li>
     *   <li>Openable (Door/FenceGate/Trapdoor) with {@link Properties#OPEN} = true → passable.</li>
     *   <li>{@link WalkablePartialBlocks#isPathable} (pressure plates, carpets, rails,
     *       tripwire, lily pad, anything with collision max Y ≤ 0.125) → passable.</li>
     * </ol>
     */
    private static boolean isPassableForMovement(BlockState state, ServerWorld world, BlockPos pos) {
        if (state.isAir() || !state.blocksMovement()) {
            return true;
        }
        if (state.contains(Properties.OPEN) && Boolean.TRUE.equals(state.get(Properties.OPEN))) {
            var block = state.getBlock();
            if (block instanceof DoorBlock
                    || block instanceof FenceGateBlock
                    || block instanceof TrapdoorBlock) {
                return true;
            }
        }
        if (net.wcfcarolina13.GameAI.services.WalkablePartialBlocks.isPathable(state, world, pos)) {
            return true;
        }
        return false;
    }

    /**
     * Permissive feet-cell check used by {@link #canAcceptMovementImpulse}: returns true for
     * everything {@link #isPassableForMovement} returns true for, plus stairs/slabs/snow —
     * because the bot stands ON those (its blockpos floors into the partial cell). Adding
     * horizontal velocity from a stair-feet position is safe; vanilla physics will auto-step
     * via {@code stepHeight=0.6} and arbitrate the actual collision. The stricter
     * {@link #canOccupyPosition} stays in use for teleport-style movers like
     * {@code moveRelative}.
     */
    private static boolean isFeetPassableForMovement(BlockState state, ServerWorld world, BlockPos pos) {
        if (isPassableForMovement(state, world, pos)) {
            return true;
        }
        return net.wcfcarolina13.GameAI.services.WalkablePartialBlocks.isStandable(state, world, pos);
    }

    /**
     * Pre-gate for adding movement impulse to the bot's velocity. Vanilla entity physics
     * does the authoritative collision + auto-step on the next tick, so this check only
     * needs to confirm the destination cell is broadly navigable: feet may be a stair/slab/
     * snow (bot stands on top); head must be passable. Strictly box-clearing here would
     * reject every stair traversal because the stair's upper-half collision intersects the
     * bot's bbox before vanilla's auto-step lifts it (see 2026-05-06 stair-stuck autopsy).
     */
    private static boolean canAcceptMovementImpulse(ServerWorld world, double x, double y, double z) {
        if (world == null) {
            return false;
        }
        BlockPos feet = BlockPos.ofFloored(x, y, z);
        BlockPos head = feet.up();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        return isFeetPassableForMovement(feetState, world, feet)
                && isPassableForMovement(headState, world, head);
    }

    private static boolean canOccupyPosition(ServerPlayerEntity bot,
                                             ServerWorld world,
                                             double x,
                                             double y,
                                             double z) {
        if (bot == null || world == null) {
            return false;
        }
        BlockPos feet = BlockPos.ofFloored(x, y, z);
        // Sub-cell motion within the bot's current feet cell is always legal. Vanilla
        // physics has already accepted the bot at its current position, so a tiny nudge
        // that stays in the same cell cannot violate any invariant our stricter cell-
        // based passability check enforces. Without this short-circuit, a bot standing
        // in a wedge cell (closed door, stair-adjacent precision boundary, etc.) has
        // every impulse rejected, freezing it in place. Cell crossings still go through
        // the gates below; vanilla Entity.move() also runs its own collision check when
        // velocity actually carries the bot across a cell boundary.
        if (feet.equals(bot.getBlockPos())) {
            return true;
        }
        if (!hasMovementClearance(world, feet)) {
            return false;
        }
        Box targetBox = bot.getBoundingBox().offset(x - bot.getX(), y - bot.getY(), z - bot.getZ());
        if (world.isSpaceEmpty(bot, targetBox)) {
            return true;
        }
        // world.isSpaceEmpty is stricter than vanilla's actual movement physics: a pressure
        // plate's 1/16 collision strip at y=[0, 0.0625] intersects the bot's bounding box
        // (minY=64.0 < plateMaxY=64.0625 via Box#intersects strict less-than), causing a
        // spurious "space not empty" rejection when the bot tries to step onto the plate.
        // Vanilla would lift the bot 1/16 onto the plate and proceed. Same issue for carpets,
        // rails, open-door thin strips, snow layers 1-2. Do a second pass ignoring those
        // walkable-partial collisions so the bot doesn't stall outside doorways with plates.
        return isBoxClearIgnoringWalkablePartials(world, targetBox);
    }

    /**
     * Like {@link ServerWorld#isSpaceEmpty(Box)} but skips blocks whose state is a walkable
     * partial — pressure plates, carpets, rails, tripwire, open doors/gates/trapdoors, snow
     * layers ≤ 2, and anything else {@code isPassableForMovement} recognizes. These have
     * non-empty collision shapes but never obstruct horizontal movement in practice.
     */
    private static boolean isBoxClearIgnoringWalkablePartials(ServerWorld world, Box targetBox) {
        int minX = (int) Math.floor(targetBox.minX);
        int minY = (int) Math.floor(targetBox.minY);
        int minZ = (int) Math.floor(targetBox.minZ);
        int maxX = (int) Math.floor(targetBox.maxX - 1.0E-7);
        int maxY = (int) Math.floor(targetBox.maxY - 1.0E-7);
        int maxZ = (int) Math.floor(targetBox.maxZ - 1.0E-7);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (isPassableForMovement(state, world, pos)) {
                        continue;
                    }
                    VoxelShape collisionShape = state.getCollisionShape(world, pos);
                    if (collisionShape.isEmpty()) {
                        continue;
                    }
                    // Non-walkable block with a real collision shape — check exact overlap.
                    double ox = x;
                    double oy = y;
                    double oz = z;
                    for (Box shapeBox : collisionShape.getBoundingBoxes()) {
                        if (shapeBox.offset(ox, oy, oz).intersects(targetBox)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // Diagnostic: re-evaluate canOccupyPosition and log the specific rejection reason.
    // Called only from applyMovementInput's rejection branch. Throttled per-bot so a long
    // stall produces a steady heartbeat (~once/0.5s) instead of a tick-rate spam flood.
    // Named "applyMovementInput-reject" so the user can grep Prism logs.
    private static final ConcurrentHashMap<UUID, Long> LAST_OCCUPANCY_REJECT_DIAG_TICK = new ConcurrentHashMap<>();
    private static final long OCCUPANCY_REJECT_DIAG_THROTTLE_TICKS = 10L;

    private static void diagnoseOccupancyRejection(ServerPlayerEntity bot,
                                                   ServerWorld world,
                                                   double x,
                                                   double y,
                                                   double z) {
        if (bot == null || world == null) {
            return;
        }
        long tick = world.getTime();
        Long last = LAST_OCCUPANCY_REJECT_DIAG_TICK.get(bot.getUuid());
        if (last != null && tick - last < OCCUPANCY_REJECT_DIAG_THROTTLE_TICKS) {
            return;
        }
        LAST_OCCUPANCY_REJECT_DIAG_TICK.put(bot.getUuid(), tick);

        BlockPos feet = BlockPos.ofFloored(x, y, z);
        BlockPos head = feet.up();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        boolean feetOk = isFeetPassableForMovement(feetState, world, feet);
        boolean headOk = isPassableForMovement(headState, world, head);

        String reason;
        String offenderStr = "-";
        if (!feetOk) {
            reason = "feet-not-passable";
        } else if (!headOk) {
            reason = "head-not-passable";
        } else {
            Box targetBox = bot.getBoundingBox()
                    .offset(x - bot.getX(), y - bot.getY(), z - bot.getZ());
            if (world.isSpaceEmpty(bot, targetBox)) {
                // Geometry changed between the gate and this retry; effectively not rejecting
                // anymore. Log it so we know the diag fired against stale state.
                reason = "race-space-now-empty";
            } else {
                BlockPos offender = findFirstBoxClearOffender(world, targetBox);
                if (offender != null) {
                    reason = "box-clear-rejected";
                    offenderStr = offender.toShortString()
                            + "=" + blockRegistryId(world.getBlockState(offender));
                } else {
                    reason = "box-clear-rejected-unknown-cell";
                }
            }
        }

        LOGGER.info("applyMovementInput-reject bot={} from=({}, {}, {}) to=({}, {}, {}) reason={} feet={}={} head={}={} offender={}",
                bot.getName().getString(),
                String.format(Locale.ROOT, "%.2f", bot.getX()),
                String.format(Locale.ROOT, "%.2f", bot.getY()),
                String.format(Locale.ROOT, "%.2f", bot.getZ()),
                String.format(Locale.ROOT, "%.2f", x),
                String.format(Locale.ROOT, "%.2f", y),
                String.format(Locale.ROOT, "%.2f", z),
                reason,
                feet.toShortString(),
                blockRegistryId(feetState),
                head.toShortString(),
                blockRegistryId(headState),
                offenderStr);
    }

    private static BlockPos findFirstBoxClearOffender(ServerWorld world, Box targetBox) {
        int minX = (int) Math.floor(targetBox.minX);
        int minY = (int) Math.floor(targetBox.minY);
        int minZ = (int) Math.floor(targetBox.minZ);
        int maxX = (int) Math.floor(targetBox.maxX - 1.0E-7);
        int maxY = (int) Math.floor(targetBox.maxY - 1.0E-7);
        int maxZ = (int) Math.floor(targetBox.maxZ - 1.0E-7);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (isPassableForMovement(state, world, pos)) {
                        continue;
                    }
                    VoxelShape collisionShape = state.getCollisionShape(world, pos);
                    if (collisionShape.isEmpty()) {
                        continue;
                    }
                    double ox = x;
                    double oy = y;
                    double oz = z;
                    for (Box shapeBox : collisionShape.getBoundingBoxes()) {
                        if (shapeBox.offset(ox, oy, oz).intersects(targetBox)) {
                            return pos.toImmutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String blockRegistryId(BlockState state) {
        if (state == null) {
            return "null";
        }
        try {
            return net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
        } catch (Exception e) {
            return state.getBlock().getClass().getSimpleName();
        }
    }

    // Self-heal: when the bot's current feet cell is a closed door, open it so vanilla
    // physics can carry the bot through. Runs as a side-effect before canOccupyPosition
    // in applyMovementInput. Throttled per-bot so we don't spam interact packets.
    private static final ConcurrentHashMap<UUID, Long> LAST_AUTO_OPEN_CURRENT_DOOR_TICK = new ConcurrentHashMap<>();
    private static final long AUTO_OPEN_CURRENT_DOOR_THROTTLE_TICKS = 20L;

    private static void maybeAutoOpenCurrentDoor(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return;
        }
        BlockPos feet = bot.getBlockPos();
        BlockState feetState = world.getBlockState(feet);
        if (!(feetState.getBlock() instanceof DoorBlock)) {
            return;
        }
        if (!feetState.contains(Properties.OPEN)
                || Boolean.TRUE.equals(feetState.get(Properties.OPEN))) {
            return;
        }
        long tick = world.getTime();
        Long last = LAST_AUTO_OPEN_CURRENT_DOOR_TICK.get(bot.getUuid());
        if (last != null && tick - last < AUTO_OPEN_CURRENT_DOOR_THROTTLE_TICKS) {
            return;
        }
        LAST_AUTO_OPEN_CURRENT_DOOR_TICK.put(bot.getUuid(), tick);
        boolean opened = MovementService.tryOpenDoorAt(bot, feet);
        LOGGER.info("auto-open-current-door bot={} at={} block={} opened={}",
                bot.getName().getString(),
                feet.toShortString(),
                blockRegistryId(feetState),
                opened);
    }

    private static boolean isWaterLikeMovementContext(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null) {
            return false;
        }
        if (bot.isTouchingWater() || bot.isSwimming()) {
            return true;
        }
        if (world == null) {
            return false;
        }
        BlockPos feet = bot.getBlockPos();
        return world.getFluidState(feet).isIn(FluidTags.WATER)
                || world.getFluidState(feet.up()).isIn(FluidTags.WATER);
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

    /**
     * Faces the given threat entity and then raises the shield.
     * Vanilla shields only block damage from the direction the player is facing,
     * so this ensures the shield actually protects against the incoming threat.
     */
    public static boolean raiseShieldFacing(ServerPlayerEntity bot, Entity threat) {
        if (threat != null) {
            double dx = threat.getX() - bot.getX();
            double dz = threat.getZ() - bot.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            bot.setYaw(yaw);
            bot.setHeadYaw(yaw);
            bot.setBodyYaw(yaw);
        }
        return raiseShield(bot);
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
        if (prioritizedBlocks != null && !prioritizedBlocks.isEmpty()) {
            for (Item item : prioritizedBlocks) {
                int slot = findBlockItemSlot(inventory, stack -> stack.isOf(item));
                if (slot != -1) {
                    return slot;
                }
            }
            return -1;
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
            if (!shouldThrowTrident(bot, target, stack)) {
                return false;
            }
            state.ensureTarget(target);
            maybeLogCombatProfile(bot, "trident-throw");
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
                if (stack.getItem() instanceof net.minecraft.item.TridentItem) {
                    BotArrowRecoveryService.noteTridentThrown(bot, stack, serverTick);
                }
                bot.stopUsingItem();
                if (stack.getItem() instanceof net.minecraft.item.TridentItem) {
                    equipPostThrowFallbackWeapon(bot, target);
                }
                state.cooldownTick = serverTick + RANGED_COOLDOWN_TICKS;
                state.chargeStartTick = 0L;
                state.recordShot(bot, target, serverTick);
                if (!(stack.getItem() instanceof net.minecraft.item.TridentItem)) {
                    BotArrowRecoveryService.noteRangedShot(bot, serverTick);
                }
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
        if (weapon.getItem() instanceof net.minecraft.item.TridentItem) {
            if (hasRiptide(bot, weapon)) {
                return false;
            }
            return true;
        }
        ItemStack projectile = bot.getProjectileType(weapon);
        return !projectile.isEmpty() || bot.getAbilities().creativeMode;
    }

    public static boolean hasRangedWeapon(ServerPlayerEntity bot) {
        if (bot == null) return false;
        // Non-mutating check: scan inventory without equipping anything.
        // The actual weapon equip happens inside performRangedAttack() -> selectBestRangedWeapon().
        if (isRangedWeapon(bot, bot.getMainHandStack()) || isRangedWeapon(bot, bot.getOffHandStack())) {
            return true;
        }
        PlayerInventory inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            if (isRangedWeapon(bot, inventory.getStack(i))) return true;
        }
        return false;
    }

    private static Selection selectBestRangedWeapon(ServerPlayerEntity bot) {
        ItemStack priorHeld = bot.getMainHandStack();
        boolean priorWasFiltered = !priorHeld.isEmpty()
                && DurabilityPolicyService.shouldAvoid(bot, priorHeld);

        Selection best = null;
        int bestScore = Integer.MIN_VALUE;
        ItemStack main = bot.getMainHandStack();
        if (!DurabilityPolicyService.shouldAvoid(bot, main)) {
            int mainScore = rangedWeaponScore(bot, main);
            if (mainScore > bestScore) {
                best = new Selection(Hand.MAIN_HAND, main);
                bestScore = mainScore;
            }
        }

        ItemStack off = bot.getOffHandStack();
        if (!DurabilityPolicyService.shouldAvoid(bot, off)) {
            int offScore = rangedWeaponScore(bot, off);
            if (offScore > bestScore) {
                best = new Selection(Hand.OFF_HAND, off);
                bestScore = offScore;
            }
        }

        PlayerInventory inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            int score = rangedWeaponScore(bot, stack);
            if (score > bestScore) {
                int hotbarSlot = ensureHotbarAccess(bot, inventory, i);
                ItemStack moved = inventory.getStack(hotbarSlot);
                best = new Selection(Hand.MAIN_HAND, moved);
                bestScore = rangedWeaponScore(bot, moved);
            }
        }

        if (best == null || bestScore <= 0) {
            // No compliant ranged weapon found — request fallback refresh if prior was preserved-below
            if (priorWasFiltered && isRangedWeapon(bot, priorHeld)) {
                DurabilityFallbackService.GearCategory cat;
                if (priorHeld.getItem() instanceof net.minecraft.item.CrossbowItem) {
                    cat = DurabilityFallbackService.GearCategory.CROSSBOW;
                } else if (priorHeld.getItem() instanceof net.minecraft.item.TridentItem) {
                    cat = DurabilityFallbackService.GearCategory.TRIDENT;
                } else {
                    cat = DurabilityFallbackService.GearCategory.BOW;
                }
                DurabilityFallbackService.requestRefresh(bot, cat);
            }
            return null;
        }

        if (best.hand == Hand.MAIN_HAND) {
            int desiredSlot = hotbarSlotOf(bot.getInventory(), best.stack);
            if (desiredSlot >= 0) {
                selectHotbarSlot(bot, desiredSlot);
                ItemStack equipped = bot.getInventory().getStack(desiredSlot);
                if (priorWasFiltered && isRangedWeapon(bot, equipped)) {
                    CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
                }
                return new Selection(Hand.MAIN_HAND, equipped);
            }
        }
        if (priorWasFiltered && isRangedWeapon(bot, best.stack)) {
            CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
        }
        return best;
    }

    private static int rangedWeaponScore(ServerPlayerEntity bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.getItem() instanceof net.minecraft.item.CrossbowItem) {
            return 90;
        }
        if (stack.getItem() instanceof net.minecraft.item.BowItem) {
            return 80;
        }
        if (stack.getItem() instanceof net.minecraft.item.TridentItem) {
            return hasRiptide(bot, stack) ? 0 : 70;
        }
        return 0;
    }

    private static boolean isRangedWeapon(ServerPlayerEntity bot, ItemStack stack) {
        return rangedWeaponScore(bot, stack) > 0;
    }

    private static String describeMeleeProfile(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.isOf(Items.TRIDENT)) {
            return "trident-melee";
        }
        if (isSword(stack)) {
            return "sword-sweep";
        }
        if (isSpear(stack)) {
            return "spear-charge";
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (key.contains("mace")) {
            return "mace-standard";
        }
        return null;
    }

    private static void maybeLogCombatProfile(ServerPlayerEntity bot, String profile) {
        if (bot == null || profile == null || profile.isBlank()) {
            return;
        }
        String previous = LAST_COMBAT_PROFILE.get(bot.getUuid());
        if (profile.equals(previous)) {
            return;
        }
        LAST_COMBAT_PROFILE.put(bot.getUuid(), profile);
        LOGGER.info("Combat profile: bot={} profile={} weapon={}",
                bot.getName().getString(),
                profile,
                bot.getMainHandStack().isEmpty() ? "empty" : bot.getMainHandStack().getName().getString());
    }

    private static int hotbarSlotOf(PlayerInventory inventory, ItemStack stack) {
        if (inventory == null || stack == null || stack.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            if (inventory.getStack(i) == stack) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (ItemStack.areItemsAndComponentsEqual(inventory.getStack(i), stack)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean shouldThrowTrident(ServerPlayerEntity bot, LivingEntity target, ItemStack stack) {
        if (bot == null || target == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (hasRiptide(bot, stack)) {
            return false;
        }
        if (!bot.canSee(target)) {
            return false;
        }
        if (bot.squaredDistanceTo(target) < TRIDENT_THROW_MIN_DISTANCE * TRIDENT_THROW_MIN_DISTANCE) {
            return false;
        }
        if (countNearbyHostiles(bot, TRIDENT_THROW_CROWD_RADIUS) > 1) {
            return false;
        }
        return true;
    }

    private static void equipPostThrowFallbackWeapon(ServerPlayerEntity bot, LivingEntity target) {
        if (bot == null) {
            return;
        }
        ItemStack current = bot.getMainHandStack();
        if (!current.isEmpty() && !(current.getItem() instanceof net.minecraft.item.TridentItem)) {
            return;
        }

        if (!selectBestWeapon(bot)) {
            return;
        }

        ItemStack fallback = bot.getMainHandStack();
        if (fallback.isEmpty()) {
            return;
        }

        if (fallback.getItem() instanceof net.minecraft.item.TridentItem) {
            return;
        }

        LOGGER.info("Trident fallback armed: bot={} target={} weapon={}",
                bot.getName().getString(),
                target != null ? target.getName().getString() : "unknown-target",
                fallback.getName().getString());
    }

    private static int countNearbyHostiles(ServerPlayerEntity bot, double radius) {
        if (bot == null || bot.getEntityWorld() == null) {
            return 0;
        }
        Box box = bot.getBoundingBox().expand(radius);
        return bot.getEntityWorld().getEntitiesByClass(
                HostileEntity.class,
                box,
                hostile -> hostile != null && hostile.isAlive()).size();
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
        if (hit.getType() != HitResult.Type.MISS) {
            return true;
        }
        // Cobwebs have empty collision shapes, so the COLLIDER raycast walks right through
        // them — but shooting through cobwebs traps the arrow and often fails the hit.
        // More importantly, if a skeleton is on the far side of a cobweb, the bot should
        // NOT engage at range — it should retreat and let approach/cover logic take over.
        if (isRangedLineBlockedByCobweb(world, fromEye, aim)) {
            return true;
        }
        // Friendly-fire check: if a non-bot player is inside the fire cone between
        // the bot's eye and the aim point, cancel the shot.
        if (wouldRangedShotHitFriendly(bot, fromEye, aim)) {
            return true;
        }
        return false;
    }

    /**
     * Walks the ray from {@code fromEye} to {@code aim} sampling block cells at 0.5-block
     * intervals. Returns true if any sampled cell contains a cobweb. Cobwebs have empty
     * collision shapes so the vanilla COLLIDER raycast does not catch them.
     */
    private static boolean isRangedLineBlockedByCobweb(ServerWorld world, Vec3d fromEye, Vec3d aim) {
        if (world == null || fromEye == null || aim == null) return false;
        Vec3d delta = aim.subtract(fromEye);
        double totalDist = delta.length();
        if (totalDist < 0.25D) return false;
        Vec3d stepVec = delta.normalize().multiply(0.5D);
        int steps = (int) Math.ceil(totalDist / 0.5D);
        Vec3d cursor = fromEye;
        for (int i = 0; i <= steps; i++) {
            BlockPos bp = BlockPos.ofFloored(cursor);
            if (world.isChunkLoaded(bp) && world.getBlockState(bp).isOf(net.minecraft.block.Blocks.COBWEB)) {
                return true;
            }
            cursor = cursor.add(stepVec);
        }
        return false;
    }

    /**
     * Friendly-fire gate: returns true if a non-bot player is within the fire cone from
     * {@code fromEye} to {@code aim}. Uses a simple "distance from point to line segment"
     * test with a 1.5-block lateral threshold to account for player hitbox width plus bow
     * divergence.
     */
    private static boolean wouldRangedShotHitFriendly(ServerPlayerEntity bot, Vec3d fromEye, Vec3d aim) {
        if (bot == null || fromEye == null || aim == null) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        Vec3d delta = aim.subtract(fromEye);
        double maxDist = delta.length();
        if (maxDist < 0.5D) return false;
        Vec3d dir = delta.multiply(1.0D / maxDist);
        final double LATERAL_THRESHOLD = 1.5D;
        final double LATERAL_THRESHOLD_SQ = LATERAL_THRESHOLD * LATERAL_THRESHOLD;
        for (ServerPlayerEntity other : world.getPlayers()) {
            if (other == null || other == bot || other.isRemoved() || !other.isAlive()) continue;
            // Skip fellow bots — they're allies but also the only valid "in-fire-cone" targets
            // we might accidentally shoot. A bot-on-bot friendly-fire concern is a different
            // feature (tamed animal defense); here we only gate against human players.
            if (net.wcfcarolina13.GameAI.services.BotRegistry.isRegistered(other.getUuid())) continue;
            // Use the player's mid-body position (feet Y + half standing eye height).
            Vec3d otherMid = new Vec3d(other.getX(), other.getY() + other.getStandingEyeHeight() / 2.0D, other.getZ());
            Vec3d toOther = otherMid.subtract(fromEye);
            double along = toOther.dotProduct(dir);
            if (along <= 0.0D || along >= maxDist + 1.0D) continue;
            Vec3d perp = toOther.subtract(dir.multiply(along));
            if (perp.lengthSquared() <= LATERAL_THRESHOLD_SQ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the bot's current ranged line to the target is blocked by terrain,
     * cobweb, or a friendly (non-bot) player in the fire cone.
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

    /**
     * Returns true if there is a cobweb block between the bot's eye and the target entity.
     * Used by combat logic to switch from "engage" to "take cover" — the bot can't
     * effectively shoot or melee through a cobweb (arrows stop, path is deadly), so it
     * should back off with shield raised instead of sitting there taking arrows.
     */
    public static boolean isCobwebBetweenBotAndTarget(ServerPlayerEntity bot, LivingEntity target) {
        if (bot == null || target == null) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        Vec3d from = bot.getEyePos();
        Vec3d to = new Vec3d(target.getX(),
                target.getY() + target.getStandingEyeHeight() / 2.0D,
                target.getZ());
        return isRangedLineBlockedByCobweb(world, from, to);
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
