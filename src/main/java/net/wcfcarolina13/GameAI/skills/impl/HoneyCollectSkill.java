package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.entity.BeehiveBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.BotBeehiveRegistryService;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.DropStandHelper;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.supply.SupplyServerHop;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Idle hobby: collect honey bottles or honeycombs from nearby smoked beehives.
 * Never breaks the hive. Only harvests when the hive is full (honey_level=5)
 * and calmed by smoke (campfire below).
 */
public final class HoneyCollectSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-honey-collect");

    private static final int DEFAULT_COUNT = 2;
    private static final int DEFAULT_RADIUS = 16;
    private static final long INTERACT_TIMEOUT_MS = 1000L;
    /** Horizontal search ring around the hive for a stand cell, and the feet heights above/below it. */
    private static final int STAND_RING = 3;
    private static final int STAND_DY_MAX = 1;
    private static final int STAND_DY_MAX_BELOW = 4;
    private static final int MAX_STAND_TRIES = 3;
    private static final double DROP_SWEEP_RADIUS = 6.0;
    private static final long DROP_SWEEP_DURATION_MS = 3000L;

    @Override
    public String name() {
        return "honey_collect";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("World unavailable.");
        }

        int count = getIntParameter(context.parameters(), "count", DEFAULT_COUNT);
        int radius = getIntParameter(context.parameters(), "radius", DEFAULT_RADIUS);

        // Determine harvest tool: prefer glass bottles (food), fall back to shears (crafting).
        // Check inside bundles and shulker boxes too.
        boolean hasBottles = hasItemIncludingContainers(bot, Items.GLASS_BOTTLE);
        boolean hasShears = hasItemIncludingContainers(bot, Items.SHEARS);
        if (!hasBottles && !hasShears) {
            return SkillExecutionResult.failure("No glass bottles or shears available.");
        }

        // Discover and find harvestable hives
        BotBeehiveRegistryService.discoverBeehivesNear(world, bot.getBlockPos(), radius, 6);
        List<BlockPos> candidates = findHarvestableHives(world, bot, radius);
        LOGGER.info("honey_collect: {} candidate hive(s) within r={} of {}: {}", candidates.size(), radius,
                bot.getBlockPos().toShortString(), formatPositions(candidates, 8));
        if (candidates.isEmpty()) {
            return SkillExecutionResult.failure(HoneyHarvestOutcome.NO_HIVES);
        }

        int harvested = 0;
        Map<HoneyHarvestOutcome, Integer> outcomes = new EnumMap<>(HoneyHarvestOutcome.class);

        for (BlockPos hivePos : candidates) {
            if (harvested >= count) break;
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Honey collection interrupted.");
            }

            // Re-validate before walking (state may have changed)
            if (!isHarvestable(world, hivePos)) {
                skip(outcomes, hivePos, HoneyHarvestOutcome.NOT_HARVESTABLE, "before walking");
                continue;
            }

            // Walk to a stand cell within reach of the hive
            String reachDetail = moveIntoReach(source, bot, world, hivePos);
            if (reachDetail != null) {
                skip(outcomes, hivePos, HoneyHarvestOutcome.UNREACHABLE, reachDetail);
                continue;
            }

            // Re-validate after walking (bees may have left, honey may have been taken)
            if (!isHarvestable(world, hivePos)) {
                skip(outcomes, hivePos, HoneyHarvestOutcome.NOT_HARVESTABLE, "after walking");
                continue;
            }

            // Determine which tool to use for this hive.
            // Bottles: vanilla takes one glass bottle and inserts the honey bottle, dropping it on the
            // ground if the inventory is full (the drop sweep below picks it up), so no free slot is required.
            // Shears drop 3 honeycombs on the ground and need inventory space.
            boolean useBottle = hasBottles;
            if (!useBottle && hasShears) {
                // Shears path: ensure at least 1 free slot for honeycomb pickup.
                // Try packing into containers first, then chest offload. Packing moves stacks, so it
                // runs on the server thread in one abandon-safe hop; a timeout reads as still full.
                boolean freeAfterPack = SupplyServerHop.call(world.getServer(), () -> {
                    if (!hasEmptySlot(bot)) {
                        packHoneyIntoContainers(bot);
                    }
                    return hasEmptySlot(bot);
                }, INTERACT_TIMEOUT_MS, false);
                if (!freeAfterPack) {
                    depositHoneyItemsIfFull(source, bot, world);
                }
                if (!hasEmptySlot(bot)) {
                    skip(outcomes, hivePos, HoneyHarvestOutcome.INVENTORY_FULL, "no free slot for honeycombs");
                    continue;
                }
            }

            // Extract tool from containers if not already in flat inventory, then equip it. Both move
            // stacks, so they run on the server thread in one abandon-safe hop; a timeout is EQUIP_FAILED.
            net.minecraft.item.Item tool = useBottle ? Items.GLASS_BOTTLE : Items.SHEARS;
            boolean equipped = SupplyServerHop.call(world.getServer(), () -> {
                if (!hasItem(bot, tool)) {
                    extractItemFromContainer(bot, tool);
                }
                return equipItem(bot, tool);
            }, INTERACT_TIMEOUT_MS, false);
            if (!equipped) {
                skip(outcomes, hivePos, HoneyHarvestOutcome.EQUIP_FAILED, "tool=" + tool);
                continue;
            }

            InteractResult interact = useOnHive(world, bot, hivePos);

            if (interact.accepted()) {
                harvested++;
                outcomes.merge(HoneyHarvestOutcome.HARVESTED, 1, Integer::sum);
                LOGGER.info("Harvested honey from beehive at {}", hivePos.toShortString());

                // Sweep dropped honeycombs (shears drop items, a bottle overflow drops too)
                try {
                    DropSweeper.sweep(source, DROP_SWEEP_RADIUS, 4.0D, 8, DROP_SWEEP_DURATION_MS);
                } catch (Exception e) {
                    LOGGER.debug("Drop sweep after honey harvest failed: {}", e.getMessage());
                }

                // Brief pause between hives
                sleepQuietly(300L);
            } else {
                skip(outcomes, hivePos, HoneyHarvestOutcome.INTERACT_REJECTED,
                        "result=" + interact.result() + " held=" + interact.heldItem());
            }

            // Refresh tool availability for next iteration (check containers too)
            hasBottles = hasItemIncludingContainers(bot, Items.GLASS_BOTTLE);
            hasShears = hasItemIncludingContainers(bot, Items.SHEARS);
            if (!hasBottles && !hasShears) break;
        }

        if (harvested <= 0) {
            return SkillExecutionResult.failure(HoneyHarvestOutcome.failureMessage(outcomes));
        }

        // Pack overflow into bundles/shulker boxes (server thread), then deposit remainder in nearby chests
        SupplyServerHop.call(world.getServer(), () -> {
            packHoneyIntoContainers(bot);
            return true;
        }, INTERACT_TIMEOUT_MS, false);
        depositHoneyItemsIfFull(source, bot, world);

        return SkillExecutionResult.success("Collected honey from " + harvested
                + (harvested == 1 ? " beehive." : " beehives."));
    }

    // ── Hive scanning ──────────────────────────────────────────────

    private static List<BlockPos> findHarvestableHives(ServerWorld world, ServerPlayerEntity bot, int radius) {
        List<BlockPos> out = new ArrayList<>();
        if (world == null || bot == null) return out;
        BlockPos center = bot.getBlockPos();
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(center.add(-r, -4, -r), center.add(r, 4, r))) {
            if (!world.isChunkLoaded(pos)) continue;
            if (isHarvestable(world, pos)) {
                out.add(pos.toImmutable());
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return out;
    }

    private static boolean isHarvestable(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!BotBeehiveRegistryService.isBeehiveBlock(state)) return false;
        // Must be full (honey_level == 5)
        if (state.get(Properties.HONEY_LEVEL) != 5) return false;
        // Must be smoked (campfire within 5 blocks below). getBlockEntity is null off the
        // server thread, so read the hive there (skill bodies run on a worker).
        BlockPos hivePos = pos.toImmutable();
        return SupplyServerHop.call(world.getServer(), () -> {
            BlockEntity be = world.getBlockEntity(hivePos);
            return be instanceof BeehiveBlockEntity hive && hive.isSmoked();
        }, INTERACT_TIMEOUT_MS, false);
    }

    // ── Interaction ─────────────────────────────────────────────────

    /** How one use on a hive ended: the vanilla result (null when the hop never ran) and what was held. */
    private record InteractResult(ActionResult result, String heldItem) {
        boolean accepted() {
            return result != null && result.isAccepted();
        }
    }

    /**
     * Harvest through the block interaction, as a player's right click does: BeehiveBlock handles
     * bottles and shears in onUseWithItem, which only ServerPlayerInteractionManager.interactBlock
     * reaches (ItemStack.useOnBlock returns PASS for both items on a hive). One bounded server hop.
     * A sneaking player skips the block's use, so the bot stands up first.
     */
    private static InteractResult useOnHive(ServerWorld world, ServerPlayerEntity bot, BlockPos hivePos) {
        InteractResult fallback = new InteractResult(null, describeHeld(bot));
        return SupplyServerHop.call(world.getServer(), () -> {
            if (bot.isSneaking()) {
                bot.setSneaking(false);
            }
            LookController.faceBlock(bot, hivePos);
            ItemStack held = bot.getMainHandStack();
            String heldName = describeStack(held);
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(hivePos), Direction.NORTH, hivePos, false);
            ActionResult result = bot.interactionManager.interactBlock(bot, world, held, Hand.MAIN_HAND, hit);
            if (result != null && result.isAccepted()) {
                bot.swingHand(Hand.MAIN_HAND, true);
            }
            return new InteractResult(result, heldName);
        }, INTERACT_TIMEOUT_MS, fallback);
    }

    private static String describeHeld(ServerPlayerEntity bot) {
        try {
            return describeStack(bot.getMainHandStack());
        } catch (Exception e) {
            return "?";
        }
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getCount() + "x " + Registries.ITEM.getId(stack.getItem());
    }

    // ── Movement ────────────────────────────────────────────────────

    /**
     * Walk to a standable cell from which the hive is within reach and in sight. Returns null when the
     * bot can use the hive now, otherwise a short reason for the skip log. The hive itself is never the
     * walk goal (the pathfinder would aim at the cell on top of it).
     */
    private static String moveIntoReach(ServerCommandSource source, ServerPlayerEntity bot,
                                        ServerWorld world, BlockPos hivePos) {
        if (canUseHive(world, bot, hivePos)) return null;
        List<BlockPos> stands = SupplyServerHop.call(world.getServer(),
                () -> findStandCellsNearHive(world, bot, hivePos), INTERACT_TIMEOUT_MS, List.of());
        if (stands.isEmpty()) {
            return "no stand cell within reach";
        }
        int tries = Math.min(MAX_STAND_TRIES, stands.size());
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < tries; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return "aborted";
            }
            BlockPos stand = stands.get(i);
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT, stand, stand, null, null, null);
            MovementService.MovementResult result = MovementService.execute(
                    source, bot, plan, SkillPreferences.teleportDuringSkills(bot), true);
            if (canUseHive(world, bot, hivePos)) {
                return null;
            }
            // The walk counts 3 blocks as arrived (MovementService.ARRIVAL_DISTANCE_SQ = 9), which can
            // leave the hive just past reach: drive the last stretch onto the stand cell (worker
            // thread, like FishingSkill).
            if (!SkillManager.shouldAbortSkill(bot)) {
                MovementService.nudgeTowardUntilClose(bot, stand, 0.7, 1500L, 0.18, "honey-stand");
                if (canUseHive(world, bot, hivePos)) {
                    return null;
                }
            }
            if (detail.length() > 0) detail.append(", ");
            detail.append(stand.toShortString()).append(' ')
                    .append(result == null ? "no-result" : (result.success() ? "arrived-out-of-reach" : "walk-failed"));
        }
        return "stands tried (" + tries + " of " + stands.size() + "): " + detail
                + " botPos=" + bot.getBlockPos().toShortString();
    }

    /** Reach from the feet plus line of sight, read on the server thread. */
    private static boolean canUseHive(ServerWorld world, ServerPlayerEntity bot, BlockPos hivePos) {
        // Same abandon-safe 1000 ms hop as the harvest: one slow tick must not read an in-reach hive as unreachable.
        return SupplyServerHop.call(world.getServer(),
                () -> BlockInteractionService.canInteract(bot, hivePos), INTERACT_TIMEOUT_MS, false);
    }

    /**
     * Two-high stand cells around the hive from which canInteract's reach (feet to hive centre) holds, nearest to the bot
     * first. Cells straight over a campfire are left out. A cell whose eye ray to the hive hits another
     * block first is left out too. Server thread only (block states).
     */
    private static List<BlockPos> findStandCellsNearHive(ServerWorld world, ServerPlayerEntity bot, BlockPos hivePos) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos botPos = bot.getBlockPos();
        for (int dy = STAND_DY_MAX; dy >= -STAND_DY_MAX_BELOW; dy--) {
            for (int dx = -STAND_RING; dx <= STAND_RING; dx++) {
                for (int dz = -STAND_RING; dz <= STAND_RING; dz++) {
                    // Offsets are stand minus hive; withinReach takes hive minus feet.
                    if (!HoneyHarvestOutcome.withinReach(-dx, -dy, -dz)) continue;
                    BlockPos feet = hivePos.add(dx, dy, dz);
                    if (!world.isChunkLoaded(feet)) continue;
                    if (!DropStandHelper.isTwoHighStand(world, feet)) continue;
                    if (world.getBlockState(feet.down()).isIn(BlockTags.CAMPFIRES)) continue;
                    if (!hasSightOfHive(world, bot, feet, hivePos)) continue;
                    out.add(feet.toImmutable());
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(botPos)));
        return out;
    }

    private static boolean hasSightOfHive(ServerWorld world, ServerPlayerEntity bot, BlockPos feet, BlockPos hivePos) {
        Vec3d eye = new Vec3d(feet.getX() + 0.5D, feet.getY() + HoneyHarvestOutcome.EYE_HEIGHT, feet.getZ() + 0.5D);
        BlockHitResult hit = world.raycast(new RaycastContext(eye, Vec3d.ofCenter(hivePos),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, bot));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(hivePos);
    }

    private static void skip(Map<HoneyHarvestOutcome, Integer> outcomes, BlockPos hivePos,
                             HoneyHarvestOutcome outcome, String detail) {
        outcomes.merge(outcome, 1, Integer::sum);
        LOGGER.info("honey_collect skip {}: {} {}", hivePos.toShortString(), outcome, detail);
    }

    private static String formatPositions(List<BlockPos> positions, int max) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(max, positions.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(positions.get(i).toShortString());
        }
        if (positions.size() > n) sb.append(", +").append(positions.size() - n).append(" more");
        return sb.append(']').toString();
    }

    // ── Inventory helpers ───────────────────────────────────────────

    private static boolean hasItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }

    /** Check flat inventory + inside bundles and shulker boxes. */
    private static boolean hasItemIncludingContainers(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        if (hasItem(bot, item)) return true;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            // Bundles
            var bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                for (ItemStack bundled : bundle.iterate()) {
                    if (bundled != null && bundled.isOf(item)) return true;
                }
            }
            // Shulker boxes
            var container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null && stack.getItem() instanceof net.minecraft.item.BlockItem blockItem
                    && blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                for (ItemStack contained : container.iterateNonEmpty()) {
                    if (contained.isOf(item)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract a single item from the first bundle or shulker box that contains it,
     * placing it into the bot's main inventory.
     */
    private static boolean extractItemFromContainer(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;

            // Try bundles
            var bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                int idx = 0;
                for (ItemStack bundled : bundle.iterate()) {
                    if (bundled != null && bundled.isOf(item)) {
                        // Remove from bundle, rebuild
                        List<ItemStack> remaining = new ArrayList<>();
                        int ri = 0;
                        ItemStack target = ItemStack.EMPTY;
                        for (ItemStack b : bundle.iterate()) {
                            if (ri == idx && target.isEmpty()) {
                                target = b.copy();
                            } else {
                                remaining.add(b.copy());
                            }
                            ri++;
                        }
                        if (target.isEmpty()) { idx++; continue; }
                        var builder = new net.minecraft.component.type.BundleContentsComponent.Builder(
                                net.minecraft.component.type.BundleContentsComponent.DEFAULT);
                        for (ItemStack r : remaining) builder.add(r);
                        stack.set(DataComponentTypes.BUNDLE_CONTENTS, builder.build());
                        if (bot.getInventory().insertStack(target)) {
                            LOGGER.debug("Extracted {} from bundle in slot {}", item, slot);
                            return true;
                        }
                        return false; // inventory full
                    }
                    idx++;
                }
            }

            // Try shulker boxes
            var container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null && stack.getItem() instanceof net.minecraft.item.BlockItem blockItem
                    && blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) {
                List<ItemStack> slots = new ArrayList<>();
                container.streamNonEmpty().forEach(s -> slots.add(s.copy()));
                for (int ci = 0; ci < slots.size(); ci++) {
                    if (slots.get(ci).isOf(item)) {
                        ItemStack target = slots.remove(ci);
                        stack.set(DataComponentTypes.CONTAINER,
                                net.minecraft.component.type.ContainerComponent.fromStacks(slots));
                        if (bot.getInventory().insertStack(target)) {
                            LOGGER.debug("Extracted {} from shulker box in slot {}", item, slot);
                            return true;
                        }
                        return false; // inventory full
                    }
                }
            }
        }
        return false;
    }

    /**
     * Pack loose honeycombs and honey bottles into bundles and shulker boxes
     * in the bot's inventory to free up main inventory slots. Server thread only (moves stacks).
     */
    private static void packHoneyIntoContainers(ServerPlayerEntity bot) {
        // First, use the existing BundleService to pack any bundlable items
        net.wcfcarolina13.GameAI.services.BundleService.packExistingBundles(bot);

        // Then try packing honey items specifically into shulker boxes
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (!stack.isOf(Items.HONEYCOMB) && !stack.isOf(Items.HONEY_BOTTLE)) continue;

            // Find a shulker box with space for this item
            for (int shulkerSlot = 0; shulkerSlot < 36; shulkerSlot++) {
                if (shulkerSlot == slot) continue;
                ItemStack shulkerStack = bot.getInventory().getStack(shulkerSlot);
                if (shulkerStack.isEmpty()) continue;
                var container = shulkerStack.get(DataComponentTypes.CONTAINER);
                if (container == null) continue;
                if (!(shulkerStack.getItem() instanceof net.minecraft.item.BlockItem blockItem
                        && blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock)) continue;

                // Collect current shulker contents into a mutable list (27 slots)
                List<ItemStack> slots = new ArrayList<>();
                for (ItemStack s : container.iterateNonEmpty()) {
                    slots.add(s.copy());
                }

                // Try stacking into an existing matching partial stack first
                boolean packed = false;
                for (ItemStack existing : slots) {
                    if (existing.isOf(stack.getItem()) && existing.getCount() < existing.getMaxCount()) {
                        int space = existing.getMaxCount() - existing.getCount();
                        int toMove = Math.min(space, stack.getCount());
                        existing.increment(toMove);
                        stack.decrement(toMove);
                        packed = true;
                        if (stack.isEmpty()) break;
                    }
                }

                // If still items left and shulker has room for a new slot (max 27)
                if (!stack.isEmpty() && slots.size() < 27) {
                    slots.add(stack.copy());
                    stack.setCount(0);
                    packed = true;
                }

                if (packed) {
                    shulkerStack.set(DataComponentTypes.CONTAINER,
                            net.minecraft.component.type.ContainerComponent.fromStacks(slots));
                    if (stack.isEmpty()) {
                        bot.getInventory().setStack(slot, ItemStack.EMPTY);
                        break;
                    }
                }
            }
        }
    }

    private static boolean hasEmptySlot(ServerPlayerEntity bot) {
        // Main inventory slots 0-35 (hotbar 0-8 + main 9-35)
        for (int i = 0; i < 36; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) return true;
        }
        return false;
    }

    private static boolean equipItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        // Already in main hand?
        if (bot.getMainHandStack().isOf(item)) return true;
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                BotActions.selectHotbarSlot(bot, i);
                return bot.getMainHandStack().isOf(item);
            }
        }
        // Item is in main inventory (slot >= 9), swap to hotbar
        for (int i = 9; i < bot.getInventory().size(); i++) {
            if (bot.getInventory().getStack(i).isOf(item)) {
                int hotbarSlot = 0;
                for (int h = 0; h < 9; h++) {
                    if (bot.getInventory().getStack(h).isEmpty()) {
                        hotbarSlot = h;
                        break;
                    }
                }
                ItemStack temp = bot.getInventory().getStack(hotbarSlot);
                bot.getInventory().setStack(hotbarSlot, bot.getInventory().getStack(i));
                bot.getInventory().setStack(i, temp);
                BotActions.selectHotbarSlot(bot, hotbarSlot);
                return bot.getMainHandStack().isOf(item);
            }
        }
        return false;
    }

    /**
     * Put loose honey in an existing nearby chest, only when the main inventory is still full after
     * packing (HoneyHarvestOutcome.shouldDeposit). Tries the deposit candidates in order and stops at
     * the first chest that takes anything. Never places a chest. With no taker the bot keeps the
     * honey (one log line, no chat). Worker thread: the walks run here, the transfers hop to the server.
     */
    /** Remembered chests farther than 32 blocks are never walked to for a honey deposit. */
    private static final double DEPOSIT_REMEMBERED_CHEST_DIST_SQ = 32.0D * 32.0D;

    private static void depositHoneyItemsIfFull(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        int[] state = SupplyServerHop.call(world.getServer(),
                () -> new int[]{countEmptyMainSlots(bot), countLooseHoneyStacks(bot)},
                INTERACT_TIMEOUT_MS, null);
        if (state == null || !HoneyHarvestOutcome.shouldDeposit(state[0], state[1])) {
            return;
        }
        java.util.function.Predicate<ItemStack> honey = HoneyCollectSkill::isHoneyItem;
        int tried = 0;
        try {
            for (ChestStoreService.StorageChestCandidate candidate : ChestStoreService.listDepositChestCandidates(
                    source, bot, null, 12, 6, DEPOSIT_REMEMBERED_CHEST_DIST_SQ)) {
                if (SkillManager.shouldAbortSkill(bot)) {
                    return;
                }
                tried++;
                int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, candidate.pos(), honey);
                if (moved > 0) {
                    LOGGER.info("honey_collect deposited {} honey item(s) in {} chest at {}",
                            moved, candidate.source(), candidate.pos().toShortString());
                    return;
                }
            }
        } catch (Exception e) {
            LOGGER.info("honey_collect deposit failed after {} chest(s): {}", tried, e.toString());
            return;
        }
        LOGGER.info("honey_collect inventory full, no existing chest took the honey ({} tried), keeping {} stack(s)",
                tried, state[1]);
    }

    private static boolean isHoneyItem(ItemStack stack) {
        return stack != null && (stack.isOf(Items.HONEYCOMB) || stack.isOf(Items.HONEY_BOTTLE));
    }

    /** Server thread only. Empty main inventory slots (hotbar 0-8 + main 9-35). */
    private static int countEmptyMainSlots(ServerPlayerEntity bot) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) n++;
        }
        return n;
    }

    /** Server thread only. Main inventory stacks of loose honeycombs or honey bottles. */
    private static int countLooseHoneyStacks(ServerPlayerEntity bot) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (isHoneyItem(bot.getInventory().getStack(i))) n++;
        }
        return n;
    }

    // ── Utilities ───────────────────────────────────────────────────

    private static int getIntParameter(Map<String, Object> params, String key, int def) {
        if (params == null || key == null) return def;
        Object raw = params.get(key);
        if (raw instanceof Number number) return number.intValue();
        if (raw instanceof String str) {
            try { return Integer.parseInt(str.trim()); }
            catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
