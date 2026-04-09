package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Skill to leash animals the bot is holding with a lead to the nearest fence post.
 * 
 * Usage:
 *   /bot leash      - Ties all leashed animals to nearby fences
 *   /bot hitch      - Same as leash (alias)
 * 
 * The bot will:
 * 1. Find all animals it's currently holding on a lead
 * 2. For each animal, find or place a nearby fence
 * 3. Attach the lead to the fence
 */
public final class LeashToFenceSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-leash");
    private static final int FENCE_SEARCH_RADIUS = 8;
    private static final int SAFE_FENCE_RADIUS = 4;
    private static final double REACH_DISTANCE_SQ = 20.25D;

    private static final List<Item> FENCE_ITEMS = List.of(
            Items.OAK_FENCE,
            Items.SPRUCE_FENCE,
            Items.BIRCH_FENCE,
            Items.JUNGLE_FENCE,
            Items.ACACIA_FENCE,
            Items.DARK_OAK_FENCE,
            Items.MANGROVE_FENCE,
            Items.CHERRY_FENCE,
            Items.BAMBOO_FENCE,
            Items.CRIMSON_FENCE,
            Items.WARPED_FENCE
    );

    @Override
    public String name() {
        return "leash";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("No world available.");
        }

        // If the bot is currently mounted, the user is asking it to dismount.
        // The /bot leash command (invoked by the ' keybind's dismount-and-
        // tether HUD path) runs through a dismount-first flow that tries to
        // fence-tether or hold-lead the horse, but ALWAYS dismounts the bot
        // regardless of whether securing succeeded. The user's explicit
        // command overrides the stay-mounted safety net: they may be inside
        // a fenced pen, without a lead, and don't care about wandering.
        if (bot.hasVehicle()) {
            String message = net.wcfcarolina13.GameAI.services.RideSyncService.forceDismountAndSecure(bot);
            if (message != null) {
                ChatUtils.sendChatMessages(source, message);
                return SkillExecutionResult.success(message);
            }
        }

        // Find all animals the bot is currently leashing
        List<MobEntity> leashedAnimals = findLeashedAnimals(bot, world);
        
        if (leashedAnimals.isEmpty()) {
            ChatUtils.sendChatMessages(source, "I'm not holding any animals on a lead.");
            return SkillExecutionResult.failure("No leashed animals found.");
        }

        ChatUtils.sendChatMessages(source, "Tying " + leashedAnimals.size() + " animal(s) to a nearby fence...");
        LOGGER.info("Leash skill: {} animals to tie", leashedAnimals.size());

        // One fence interaction ties ALL leads currently held by the player.
        // To avoid snapping leads, do NOT walk away with multiple animals.
        // Prefer a fence we can interact with from our current position; otherwise place one adjacent.
        BlockPos fencePos = findNearbyFence(world, bot, bot.getBlockPos(), SAFE_FENCE_RADIUS);
        if (fencePos == null) {
            fencePos = tryPlaceFence(bot, world, bot.getBlockPos());
        }
        if (fencePos == null) {
            ChatUtils.sendChatMessages(source, "I can't find a fence within reach, and I can't place one nearby.");
            return SkillExecutionResult.failure("No reachable fence found or placeable.");
        }
        if (!isWithinReach(bot, fencePos)) {
            // As a last resort, consider walking only when handling a single lead.
            double distSq = bot.squaredDistanceTo(Vec3d.ofCenter(fencePos));
            if (leashedAnimals.size() == 1 && distSq <= 36.0D) { // <= 6 blocks
                boolean moved = moveToReach(source, bot, fencePos);
                if (!moved) {
                    LOGGER.warn("Cannot reach fence at {}", fencePos.toShortString());
                    return SkillExecutionResult.failure("Couldn't reach the fence.");
                }
            } else {
                ChatUtils.sendChatMessages(source, "I need a fence closer to tie these leads without snapping them.");
                return SkillExecutionResult.failure("Fence not within safe reach.");
            }
        }

        // Hold a lead when interacting (vanilla reliably transfers leads to the fence when a lead is in-hand).
        // If we don't have one, fall back to an empty/non-placeable slot to avoid accidental block placement.
        selectLeadOrSafeHand(bot);
        boolean interacted = attachLeadsToFence(bot, world, fencePos);
        sleepQuiet(150);

        int tiedNow = 0;
        int stillHeld = 0;
        for (MobEntity animal : leashedAnimals) {
            if (animal == null || !animal.isAlive()) {
                continue;
            }
            if (animal.isLeashed() && animal.getLeashHolder() == bot) {
                stillHeld++;
            } else {
                tiedNow++;
            }
        }

        if (tiedNow > 0) {
            ChatUtils.sendChatMessages(source, "Tied " + tiedNow + " animal(s) to fence at " + fencePos.toShortString() + ".");
            LOGGER.info("Leash skill: tiedNow={} stillHeld={} fence={} interacted={}", tiedNow, stillHeld, fencePos.toShortString(), interacted);
            return SkillExecutionResult.success("Tied " + tiedNow + " animal(s) to a fence.");
        }

        LOGGER.warn("Leash skill: fence interaction did not transfer any leads (stillHeld={} fence={} interacted={})", stillHeld, fencePos.toShortString(), interacted);
        return SkillExecutionResult.failure("Couldn't attach the leads to the fence. Make sure the animals are still leashed and the fence is reachable.");
    }

    /**
     * Find all animals the bot is currently holding on a lead.
     */
    private List<MobEntity> findLeashedAnimals(ServerPlayerEntity bot, ServerWorld world) {
        Box searchBox = new Box(bot.getBlockPos()).expand(16.0);
        return world.getEntitiesByClass(MobEntity.class, searchBox,
                mob -> mob != null && mob.isAlive() && mob.isLeashed() && mob.getLeashHolder() == bot);
    }

    /**
     * Find the nearest fence block.
     */
    private BlockPos findNearbyFence(ServerWorld world, ServerPlayerEntity bot, BlockPos origin) {
        return findNearbyFence(world, bot, origin, FENCE_SEARCH_RADIUS);
    }

    private BlockPos findNearbyFence(ServerWorld world, ServerPlayerEntity bot, BlockPos origin, int radius) {
        if (world == null || bot == null || origin == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(
                origin.add(-radius, -2, -radius),
                origin.add(radius, 2, radius))) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.FENCES)) {
                continue;
            }
            // Prefer fences we can interact with without moving.
            if (!isWithinReach(bot, pos)) {
                continue;
            }
            double dist = origin.getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    /**
     * Try to place a fence near the given position.
     */
    private BlockPos tryPlaceFence(ServerPlayerEntity bot, ServerWorld world, BlockPos origin) {
        // Check if bot has a fence in inventory
        Item fenceItem = null;
        for (Item item : FENCE_ITEMS) {
            if (hasItemInInventory(bot, item)) {
                fenceItem = item;
                break;
            }
        }
        
        if (fenceItem == null) {
            LOGGER.debug("No fence items in inventory");
            return null;
        }

        // Switch to fence item
        if (!BotActions.ensureHotbarItem(bot, fenceItem)) {
            return null;
        }

        // Find a suitable placement position
        List<BlockPos> candidates = List.of(
            origin.add(1, 0, 0),
            origin.add(-1, 0, 0),
            origin.add(0, 0, 1),
            origin.add(0, 0, -1),
            origin.add(2, 0, 0),
            origin.add(-2, 0, 0),
            origin.add(0, 0, 2),
            origin.add(0, 0, -2),
            origin.add(1, 0, 1),
            origin.add(-1, 0, -1),
            origin.add(1, 0, -1),
            origin.add(-1, 0, 1)
        );

        for (BlockPos candidate : candidates) {
            BlockPos below = candidate.down();
            
            // Check if we can place here: solid ground below, air at candidate
            if (!world.getBlockState(below).isAir() && 
                world.getBlockState(candidate).isAir()) {
                
                // Move close if needed
                if (!isWithinReach(bot, candidate)) {
                    MovementService.nudgeTowardUntilClose(bot, candidate, 3.0, 3000, 0.12, "leash-fence");
                }

                if (isWithinReach(bot, candidate)) {
                    LookController.faceBlock(bot, candidate);
                    boolean placed = BotActions.placeBlockAt(bot, candidate, Direction.UP, FENCE_ITEMS);
                    if (placed) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Attach the lead to a fence post.
     */
    private boolean attachLeadsToFence(ServerPlayerEntity bot, ServerWorld world, BlockPos fencePos) {
        // Look at the fence
        LookController.faceBlock(bot, fencePos);
        sleepQuiet(50);

        // Use the fence to attach the lead
        Vec3d hitVec = Vec3d.ofCenter(fencePos);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, fencePos, false);
        
        // The lead attachment happens when we right-click the fence while holding a leashed animal
        var result = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
        return result != null && result.isAccepted();
    }

    /**
     * Prefer an empty hotbar slot; otherwise select a non-placeable item.
     * This avoids accidentally placing blocks when right-clicking a fence.
     */
    private void selectLeadOrSafeHand(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        // Prefer holding a lead (most reliable interaction behavior).
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.LEAD)) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!(stack.getItem() instanceof BlockItem)) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
    }

    /**
     * Check if a position is within reach.
     */
    private boolean isWithinReach(ServerPlayerEntity bot, BlockPos pos) {
        Vec3d eye = bot.getEyePos();
        double distSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
        return distSq <= REACH_DISTANCE_SQ;
    }

    /**
     * Move close enough to interact with a position.
     */
    private boolean moveToReach(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        return MovementService.nudgeTowardUntilClose(bot, target, 3.0, 5000, 0.12, "leash-reach");
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check if the bot has a specific item in inventory.
     */
    private boolean hasItemInInventory(ServerPlayerEntity bot, Item item) {
        if (bot == null || item == null) return false;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the bot is currently holding any animal on a lead.
     * This can be called externally to determine if the leash button should be shown.
     */
    public static boolean isHoldingLeashedAnimal(ServerPlayerEntity bot) {
        if (bot == null) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        
        Box searchBox = new Box(bot.getBlockPos()).expand(16.0);
        List<MobEntity> leashed = world.getEntitiesByClass(MobEntity.class, searchBox,
                mob -> mob != null && mob.isAlive() && mob.isLeashed() && mob.getLeashHolder() == bot);
        
        return !leashed.isEmpty();
    }
}
