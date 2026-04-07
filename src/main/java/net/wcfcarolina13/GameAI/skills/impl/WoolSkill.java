package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.DurabilityFallbackService;
import net.wcfcarolina13.GameAI.services.DurabilityPolicyService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.ToolProvisionService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Peaceful wool collection: shears adult sheep, collects drops, and deposits blocks if inventory is tight.
 */
public class WoolSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-wool");
    private static final int MIN_FREE_SLOTS = 5;
    private static final int PEN_SEARCH_RADIUS = 14;
    private static final int WILD_SEARCH_RADIUS = 48;
    private static final int SHEEP_VERTICAL_RANGE = 18;
    private static final int CHEST_SEARCH_RADIUS = 10;
    private static final int SUNSET_TIME_OF_DAY = 12000; // day phase; stop when sun starts going down
    private static final long MAX_JOB_MILLIS = 30 * 60_000L; // hard cap (day is ~20 minutes)
    private static final int DEFAULT_MIN_WOOL = 16;
    private static final double SHEAR_RANGE_SQ = 16.0; // 4 blocks linear — Minecraft interaction range
    private static final List<Item> DEPOSIT_PREFERRED = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE,
            Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.SAND, Items.RED_SAND,
            Items.GRAVEL, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
            Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.CRIMSON_STEM, Items.WARPED_STEM,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );
    private static final Map<UUID, BlockPos> LAST_SEEN_SHEEP = new HashMap<>();
    /** Per-bot zone visit timestamps for smarter exploration. Key = packed (cellX, cellZ), value = visit epoch ms. */
    private static final Map<UUID, Map<Long, Long>> EXPLORED_ZONES = new HashMap<>();
    private static final long ZONE_COOLDOWN_MS = 3 * 60_000L; // 3 minutes before revisiting a zone

    @Override
    public String name() {
        return "wool";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No active bot.");
        }
        ServerWorld world = source.getWorld();

        if (!ensureShears(bot, source)) {
            return SkillExecutionResult.failure("Missing shears and cannot craft.");
        }

        int radius = detectFenceNearby(world, bot.getBlockPos()) ? PEN_SEARCH_RADIUS : parseRange(context.parameters());
        BlockPos startPos = bot.getBlockPos();
        UUID followTargetUuid = BotEventHandler.getFollowTargetUuid(bot);
        int minWoolToCollect = parseCount(context.parameters());
        if (minWoolToCollect <= 0) {
            minWoolToCollect = DEFAULT_MIN_WOOL;
        }
        int woolAtStart = countWoolItems(bot.getInventory());
        long startedAt = System.currentTimeMillis();
        int timeOfDay = (int) (world.getTimeOfDay() % 24000L);
        if (timeOfDay >= SUNSET_TIME_OF_DAY) {
            ChatUtils.sendSystemMessage(source, "It's getting late; I'll collect wool tomorrow.");
            return SkillExecutionResult.failure("Too late to start wool run.");
        }

        ensureInventorySpace(bot, world, source);
        ChatUtils.sendSystemMessage(source, "Collecting at least " + minWoolToCollect + " wool before sunset.");
        ChatUtils.sendSystemMessage(source, "Looking for sheep within " + radius + " blocks (line-of-sight only).");

        int sheared = 0;
        Set<UUID> failedSheepIds = new HashSet<>(); // Track sheep we couldn't reach to avoid oscillating
        long lastFailedClearTime = System.currentTimeMillis();
        final long FAILED_CLEAR_INTERVAL_MS = 30_000L; // Reset failed list every 30 seconds to allow wool regrowth

        while (System.currentTimeMillis() - startedAt < MAX_JOB_MILLIS) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return SkillExecutionResult.failure("Wool job stopped.");
            }
            
            // Periodically clear the failed set to allow sheep with regrown wool to be re-tried
            if (System.currentTimeMillis() - lastFailedClearTime >= FAILED_CLEAR_INTERVAL_MS) {
                failedSheepIds.clear();
                lastFailedClearTime = System.currentTimeMillis();
                LOGGER.debug("Cleared failed sheep list for periodic re-scan");
            }
            int now = (int) (world.getTimeOfDay() % 24000L);
            if (now >= SUNSET_TIME_OF_DAY) {
                ChatUtils.sendSystemMessage(source, "It's getting late; I'm heading back.");
                returnFromWool(bot, source, startPos, followTargetUuid);
                break;
            }

            if (!ensureWoolCapacityOrDeposit(bot, world, source)) {
                break;
            }

            // Opportunistic sweep: pick up nearby drops before finding next sheep
            if (!world.getEntitiesByClass(ItemEntity.class,
                    Box.from(Vec3d.of(bot.getBlockPos())).expand(8, 4, 8),
                    e -> e.isAlive()).isEmpty()) {
                DropSweeper.sweep(source, 8.0, 4.0, 4, 3000L);
            }

            List<SheepEntity> candidates = visibleSheep(bot, world, radius);
            // Filter out sheep we already failed to reach (avoid oscillation)
            int beforeFilter = candidates.size();
            candidates.removeIf(s -> failedSheepIds.contains(s.getUuid()));
            if (beforeFilter > 0 && candidates.isEmpty()) {
                LOGGER.debug("All {} visible sheep are in failed set; will explore", beforeFilter);
            }
            
            if (candidates.isEmpty()) {
                // Clear failed set when exploring - give them another chance after moving
                failedSheepIds.clear();
                if (!exploreForSheep(bot, world, source, radius)) {
                    break;
                }
                continue;
            }

            // IMPORTANT: Pick ONE sheep and commit to it, don't iterate through all
            LOGGER.debug("Found {} shearable sheep candidates (radius={})", candidates.size(), radius);
            SheepEntity target = candidates.get(0); // Already sorted by distance
            LAST_SEEN_SHEEP.put(bot.getUuid(), target.getBlockPos());

            if (!approachSheep(bot, source, target)) {
                failedSheepIds.add(target.getUuid());
                LOGGER.debug("Failed to reach sheep {}, marking as unreachable", target.getUuid());
                continue;
            }

            // Re-check if sheep is still shearable after we moved to it
            if (!target.isAlive() || !target.isShearable() || target.isBaby()) {
                continue;
            }

            // CRITICAL: Ensure shears are equipped before each shearing attempt
            if (!ensureShearsEquipped(bot)) {
                LOGGER.warn("Lost shears, attempting to re-equip or craft");
                if (!ensureShears(bot, source)) {
                    return SkillExecutionResult.failure("Lost shears and cannot replace.");
                }
            }

            // Final range check — interactEntity has no distance guard
            if (bot.squaredDistanceTo(target) > SHEAR_RANGE_SQ) {
                MovementService.nudgeTowardUntilClose(bot, target.getBlockPos(), SHEAR_RANGE_SQ, 1500L, 0.2, "wool-shear-close");
            }
            if (bot.squaredDistanceTo(target) > SHEAR_RANGE_SQ) {
                failedSheepIds.add(target.getUuid());
                continue;
            }

            boolean shearSuccess = BotActions.interactEntity(bot, target, Hand.MAIN_HAND);
            if (shearSuccess) {
                sheared++;
                LOGGER.info("Sheared sheep {} (total={})", target.getUuid(), sheared);
            } else {
                LOGGER.debug("interactEntity returned false for sheep {}", target.getUuid());
                continue;
            }

            // Give the drops a tick to spawn, then sweep
            sleep(300);
            DropSweeper.sweep(source, 8.0, 4.0, 6, 4000L);

            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return SkillExecutionResult.failure("Wool job stopped.");
            }

            int collected = countWoolItems(bot.getInventory()) - woolAtStart;
            if (collected >= minWoolToCollect) {
                ChatUtils.sendSystemMessage(source, "Collected at least " + minWoolToCollect + " wool; heading back.");
                DropSweeper.sweep(source, 14.0, 6.0, 12, 7000L);
                returnFromWool(bot, source, startPos, followTargetUuid);
                return SkillExecutionResult.success("Collected " + collected + " wool and sheared " + sheared + " sheep.");
            }
        }

        DropSweeper.sweep(source, 14.0, 6.0, 12, 9000L);

        ensureInventorySpace(bot, world, source); // final deposit pass

        if (sheared == 0) {
            return SkillExecutionResult.failure("No shearable sheep found nearby.");
        }
        return SkillExecutionResult.success("Sheared " + sheared + " sheep and gathered wool.");
    }

    private int parseRange(Map<String, Object> params) {
        if (params != null) {
            Object value = params.get("range");
            if (value instanceof Number number) {
                return Math.max(16, Math.min(128, number.intValue()));
            }
            if (value instanceof String s) {
                try {
                    return Math.max(16, Math.min(128, Integer.parseInt(s)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return WILD_SEARCH_RADIUS;
    }

    private int parseCount(Map<String, Object> params) {
        if (params == null) {
            return 0;
        }
        Object value = params.get("count");
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String s) {
            try {
                return Math.max(0, Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private int countWoolItems(Inventory inv) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem().getTranslationKey().contains("wool")) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean ensureShears(ServerPlayerEntity bot, ServerCommandSource source) {
        int slot = findShearsSlot(bot);
        if (slot == -1) {
            boolean crafted = ToolProvisionService.ensureShears(bot, source, source.getPlayer());
            if (crafted) {
                slot = findShearsSlot(bot);
            }
        }
        if (slot == -1) {
            ChatUtils.sendSystemMessage(source, "I need shears to collect wool.");
            return false;
        }
        if (slot >= 9) {
            int empty = findEmptyHotbar(bot);
            if (empty == -1) {
                empty = bot.getInventory().getSelectedSlot();
            }
            // manual swap
            ItemStack from = bot.getInventory().getStack(slot);
            ItemStack to = bot.getInventory().getStack(empty);
            bot.getInventory().setStack(slot, to);
            bot.getInventory().setStack(empty, from);
            slot = empty;
        }
        BotActions.selectHotbarSlot(bot, slot);
        return true;
    }

    /**
     * Quick check if shears are currently in hand. If not, try to re-select them.
     * Returns true if shears are now equipped, false otherwise.
     */
    private boolean ensureShearsEquipped(ServerPlayerEntity bot) {
        ItemStack priorHeld = bot.getMainHandStack();
        boolean priorWasFiltered = !priorHeld.isEmpty() && DurabilityPolicyService.shouldAvoid(bot, priorHeld);
        ItemStack hand = bot.getMainHandStack();
        if (hand.isOf(Items.SHEARS)) {
            if (DurabilityPolicyService.shouldAvoid(bot, hand)) {
                // Held shears are preserved-below-threshold — request fallback refresh,
                // then fall through so the inventory scan can find a compliant pair.
                DurabilityFallbackService.requestRefresh(
                        bot, DurabilityFallbackService.GearCategory.SHEARS);
                // Don't return yet — fall through to look for a better pair in inventory.
            } else {
                return true;
            }
        }
        // Shears not in hand - find and re-select
        int slot = findShearsSlot(bot);
        if (slot == -1) {
            return false;
        }
        if (slot >= 9) {
            // Need to move to hotbar first
            int empty = findEmptyHotbar(bot);
            if (empty == -1) {
                empty = bot.getInventory().getSelectedSlot();
            }
            ItemStack from = bot.getInventory().getStack(slot);
            ItemStack to = bot.getInventory().getStack(empty);
            bot.getInventory().setStack(slot, to);
            bot.getInventory().setStack(empty, from);
            slot = empty;
        }
        BotActions.selectHotbarSlot(bot, slot);
        boolean equipped = bot.getMainHandStack().isOf(Items.SHEARS);
        if (equipped && priorWasFiltered) {
            CompanionOverheadDialogueService.tryShowGearPreserveSwap(bot);
        }
        return equipped;
    }

    /**
     * Approach a sheep using entity-pursuit pattern (like HuntSkill).
     * Uses planLootApproach for initial move, then nudge pursuit to handle sheep wandering.
     */
    private boolean approachSheep(ServerPlayerEntity bot, ServerCommandSource source, SheepEntity sheep) {
        if (sheep == null || sheep.isRemoved()) {
            return false;
        }

        // Initial approach via planLootApproach (proven pattern from HuntSkill)
        BlockPos targetPos = sheep.getBlockPos();
        java.util.Optional<MovementService.MovementPlan> planOpt =
                MovementService.planLootApproach(bot, targetPos, MovementService.MovementOptions.skillLoot());
        if (planOpt.isPresent()) {
            MovementService.MovementResult result = MovementService.execute(source, bot, planOpt.get(), false, true);
            if (result.success() || bot.squaredDistanceTo(sheep) <= SHEAR_RANGE_SQ) {
                return true;
            }
        } else {
            // Fallback: direct pathfind to sheep position
            moveTo(bot, source, targetPos, true);
            if (bot.squaredDistanceTo(sheep) <= SHEAR_RANGE_SQ) {
                return true;
            }
        }

        // Pursuit loop: sheep may have moved while we were walking
        long pursuitDeadline = System.currentTimeMillis() + 5000L;
        for (int i = 0; i < 3 && System.currentTimeMillis() < pursuitDeadline; i++) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            if (!sheep.isAlive() || sheep.isRemoved()) {
                return false;
            }
            // Re-read sheep's CURRENT position each iteration
            BlockPos currentPos = sheep.getBlockPos();
            boolean close = MovementService.nudgeTowardUntilClose(
                    bot, currentPos, SHEAR_RANGE_SQ, 1500L, 0.18, "wool-pursuit");
            if (close || bot.squaredDistanceTo(sheep) <= SHEAR_RANGE_SQ) {
                return true;
            }
        }

        return bot.squaredDistanceTo(sheep) <= SHEAR_RANGE_SQ;
    }

    /**
     * Return to the best available destination after wool collection.
     * Priority: follow target player > home service (base/bed) > start position.
     */
    private void returnFromWool(ServerPlayerEntity bot, ServerCommandSource source,
                                BlockPos startPos, UUID followTargetUuid) {
        BlockPos returnTarget = null;

        // Priority 1: return to the player we were following
        if (followTargetUuid != null) {
            MinecraftServer server = source.getServer();
            if (server != null) {
                ServerPlayerEntity commander = server.getPlayerManager().getPlayer(followTargetUuid);
                if (commander != null && commander.isAlive() && !commander.isRemoved()) {
                    returnTarget = commander.getBlockPos();
                    LOGGER.info("Returning to follow target {} at {}",
                            commander.getName().getString(), returnTarget.toShortString());
                }
            }
        }

        // Priority 2: home service (preferred base > last bed > nearest base)
        if (returnTarget == null) {
            java.util.Optional<BlockPos> homeTarget = BotHomeService.resolveHomeTarget(bot);
            if (homeTarget.isPresent()) {
                returnTarget = homeTarget.get();
                LOGGER.info("Returning to home target {}", returnTarget.toShortString());
            }
        }

        // Priority 3: original start position
        if (returnTarget == null) {
            returnTarget = startPos;
            LOGGER.info("Returning to start position {}", returnTarget.toShortString());
        }

        // If return target is in water, find nearest dry shore instead
        ServerWorld world = source.getWorld();
        if (world.getBlockState(returnTarget).isOf(Blocks.WATER)) {
            BlockPos shore = findNearestShore(world, returnTarget, 24);
            if (shore != null) {
                LOGGER.info("Return target {} is in water; rerouting to shore {}",
                        returnTarget.toShortString(), shore.toShortString());
                returnTarget = shore;
            }
        }

        if (bot.getBlockPos().getSquaredDistance(returnTarget) > 16.0) {
            moveTo(bot, source, returnTarget, false);
        }
    }

    /**
     * Spiral outward from a water position to find the nearest dry, standable block.
     */
    private BlockPos findNearestShore(ServerWorld world, BlockPos center, int maxRadius) {
        for (int r = 1; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // perimeter only
                    BlockPos candidate = center.add(dx, 0, dz);
                    // Search a few Y levels around center height
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos check = candidate.add(0, dy, 0);
                        BlockState feet = world.getBlockState(check);
                        BlockState below = world.getBlockState(check.down());
                        BlockState head = world.getBlockState(check.up());
                        if (!feet.isOf(Blocks.WATER) && feet.isAir()
                                && head.isAir()
                                && !below.isAir() && below.isOpaque()) {
                            return check;
                        }
                    }
                }
            }
        }
        return null;
    }

    private List<SheepEntity> visibleSheep(ServerPlayerEntity bot, ServerWorld world, int radius) {
        Box box = Box.from(Vec3d.of(bot.getBlockPos())).expand(radius, SHEEP_VERTICAL_RANGE, radius);
        List<SheepEntity> visible = world.getEntitiesByClass(
                SheepEntity.class,
                box,
                s -> !s.isBaby() && s.isShearable() && (bot.canSee(s) || bot.squaredDistanceTo(s) <= 64.0)
        );
        if (visible.isEmpty()) {
            return new ArrayList<>(); // Return mutable empty list so callers can safely use removeIf
        }
        visible.sort((a, b) -> Double.compare(bot.squaredDistanceTo(a), bot.squaredDistanceTo(b)));
        return visible;
    }

    private boolean exploreForSheep(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source, int radius) {
        BlockPos last = LAST_SEEN_SHEEP.get(bot.getUuid());
        BlockPos anchor = last != null ? last : bot.getBlockPos();

        Map<Long, Long> zones = EXPLORED_ZONES.computeIfAbsent(bot.getUuid(), k -> new HashMap<>());
        long now = System.currentTimeMillis();

        // Generate candidates: 8 directions at increasing radii, skip recently visited zones
        int[] radii = {16, 32, 48, 64};
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

        List<BlockPos> candidates = new ArrayList<>();
        for (int r : radii) {
            for (int[] dir : directions) {
                BlockPos probe = anchor.add(dir[0] * r, 0, dir[1] * r);
                long zoneKey = packZone(probe);
                Long visitedAt = zones.get(zoneKey);
                if (visitedAt == null || (now - visitedAt) > ZONE_COOLDOWN_MS) {
                    candidates.add(probe);
                }
            }
        }

        if (candidates.isEmpty()) {
            ChatUtils.sendSystemMessage(source, "All nearby areas explored recently; sheep may need time to regrow wool.");
            return false;
        }

        // Prefer unexplored zones first, then closest to bot
        candidates.sort((a, b) -> {
            boolean aVisited = zones.containsKey(packZone(a));
            boolean bVisited = zones.containsKey(packZone(b));
            if (aVisited != bVisited) return aVisited ? 1 : -1;
            return Double.compare(
                    bot.getBlockPos().getSquaredDistance(a),
                    bot.getBlockPos().getSquaredDistance(b));
        });

        int consecutiveFails = 0;
        for (BlockPos probe : candidates) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            probe = probe.withY(anchor.getY());
            if (moveTo(bot, source, probe, true)) {
                consecutiveFails = 0;
                zones.put(packZone(probe), now);
                if (!visibleSheep(bot, world, radius).isEmpty()) {
                    return true;
                }
            } else {
                consecutiveFails++;
                // After 2 consecutive failures, bot is likely stuck below terrain —
                // use SafePositionService to find the surface and navigate up
                if (consecutiveFails >= 2) {
                    BlockPos surface = SafePositionService.findSafeSurface(
                            world, bot.getBlockPos(), 4, 8);
                    if (surface != null && surface.getY() > bot.getBlockY()) {
                        LOGGER.info("Terrain recovery: bot at Y={}, surface at Y={} ({})",
                                bot.getBlockY(), surface.getY(), surface.toShortString());
                        if (moveTo(bot, source, surface, true)) {
                            consecutiveFails = 0;
                            continue; // recovered — retry exploration from surface
                        }
                    }
                    // Still stuck after recovery attempt — bail out of this cycle
                    LOGGER.warn("Exploration stuck after {} failures at Y={}; aborting explore cycle",
                            consecutiveFails, bot.getBlockY());
                    return true; // return true to keep the main loop alive for next iteration
                }
            }
        }
        return true; // explored but found nothing
    }

    private static long packZone(BlockPos pos) {
        int cellX = pos.getX() >> 4;
        int cellZ = pos.getZ() >> 4;
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }


    private boolean detectFenceNearby(ServerWorld world, BlockPos origin) {
        int scan = 12;
        for (BlockPos pos : BlockPos.iterate(origin.add(-scan, -1, -scan), origin.add(scan, 2, scan))) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.FENCES)) {
                return true;
            }
        }
        return false;
    }

    private void ensureInventorySpace(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source) {
        int free = countFreeSlots(bot.getInventory());
        if (free >= MIN_FREE_SLOTS) {
            return;
        }
        BlockPos chestPos = findNearbyChest(world, bot.getBlockPos(), CHEST_SEARCH_RADIUS);
        if (chestPos == null) {
            ChatUtils.sendSystemMessage(source, "Inventory is tight and no chest nearby; proceeding anyway.");
            return;
        }
        boolean reached = moveNextTo(bot, source, chestPos);
        if (!reached || !BlockInteractionService.canInteract(bot, chestPos)) {
            ChatUtils.sendSystemMessage(source, "I found a chest, but I can't reach it from here.");
            return;
        }
        ChestBlockEntity chest = world.getBlockEntity(chestPos) instanceof ChestBlockEntity c ? c : null;
        if (chest == null) {
            return;
        }
        int moved = depositPreferred(bot.getInventory(), chest);
        ChatUtils.sendSystemMessage(source, moved > 0 ? "Stored " + moved + " items to free space." : "Chest is full; continuing.");
    }

    private int depositPreferred(Inventory from, Inventory to) {
        int moved = 0;
        for (int i = 0; i < from.size(); i++) {
            ItemStack stack = from.getStack(i);
            if (stack.isEmpty()) continue;
            if (!DEPOSIT_PREFERRED.contains(stack.getItem())) continue;
            ItemStack copy = stack.copy();
            ItemStack leftover = insertInto(to, copy);
            int deposited = copy.getCount() - leftover.getCount();
            if (deposited > 0) {
                stack.decrement(deposited);
                moved += deposited;
            }
            if (countFreeSlots(from) >= MIN_FREE_SLOTS) {
                break;
            }
        }
        return moved;
    }

    private ItemStack insertInto(Inventory inv, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                inv.setStack(i, remaining);
                return ItemStack.EMPTY;
            }
            if (ItemStack.areItemsEqual(slot, remaining) && ItemStack.areEqual(slot, remaining) && slot.getCount() < slot.getMaxCount()) {
                int canAdd = Math.min(slot.getMaxCount() - slot.getCount(), remaining.getCount());
                slot.increment(canAdd);
                remaining.decrement(canAdd);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return remaining;
    }

    private BlockPos findNearbyChest(ServerWorld world, BlockPos origin, int radius) {
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -1, -radius), origin.add(radius, 2, radius))) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.CHEST) || state.isOf(net.minecraft.block.Blocks.TRAPPED_CHEST)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private boolean moveNextTo(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target) {
        List<BlockPos> stands = findStandCandidates(bot, source.getWorld(), target);
        for (BlockPos stand : stands) {
            if (SkillManager.shouldAbortSkill(bot)) {
                BotActions.stop(bot);
                return false;
            }
            if (moveTo(bot, source, stand, true)) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> findStandCandidates(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos foot = target.offset(dir);
            BlockPos below = foot.down();
            if (world.getBlockState(below).isSolidBlock(world, below) && world.isAir(foot)) {
                candidates.add(foot);
            }
        }
        candidates.sort((a, b) -> Double.compare(bot.squaredDistanceTo(Vec3d.ofCenter(a)), bot.squaredDistanceTo(Vec3d.ofCenter(b))));
        return candidates;
    }

    private boolean moveTo(ServerPlayerEntity bot, ServerCommandSource source, BlockPos target, boolean fastReplan) {
        if (SkillManager.shouldAbortSkill(bot)) {
            BotActions.stop(bot);
            return false;
        }
        
        // Early Y-level sanity check: don't pursue sheep that wandered into caves
        int dy = Math.abs(target.getY() - bot.getBlockY());
        if (dy > 12) {
            LOGGER.debug("Target {} too far vertically (dy={}), skipping", target.toShortString(), dy);
            return false;
        }
        
        double distSq = bot.getBlockPos().getSquaredDistance(target);
        final double ARRIVAL_THRESHOLD_SQ = 9.0D;
        
        // Short-range nudge for nearby targets
        if (distSq <= 196.0D && dy <= 2) {
            boolean close = MovementService.nudgeTowardUntilClose(
                    bot,
                    target,
                    ARRIVAL_THRESHOLD_SQ,
                    fastReplan ? 1800L : 2800L,
                    0.16,
                    "wool-short-move");
            if (close || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                return true;
            }
            // If short nudge failed, check for a blocking door
            BlockPos blockingDoor = BlockInteractionService.findBlockingDoor(bot, target, 12.0D);
            if (blockingDoor != null) {
                MovementService.tryOpenDoorAt(bot, blockingDoor);
                MovementService.tryTraverseOpenableToward(bot, blockingDoor, target, "wool-door-nearby");
                MovementService.nudgeTowardUntilClose(bot, target, ARRIVAL_THRESHOLD_SQ, 2000L, 0.18, "wool-door-cross");
                if (bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                    return true;
                }
            }
        }
        
        // Standard pathfinding for longer distances
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, fastReplan, true, false);
        if (res.success() || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
            return true;
        }
        
        // Check for blocking door on the direct line
        BlockPos directBlockingDoor = BlockInteractionService.findBlockingDoor(bot, target, 64.0D);
        if (directBlockingDoor != null) {
            MovementService.tryOpenDoorAt(bot, directBlockingDoor);
            MovementService.tryTraverseOpenableToward(bot, directBlockingDoor, target, "wool-doorway");
            res = MovementService.execute(source, bot, plan, false, true, false, false);
            if (res.success() || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                return true;
            }
        }
        
        // Door escape assist: if we appear to be in an enclosure, try "approach -> open -> step through"
        try {
            MovementService.DoorSubgoalPlan doorPlan = directBlockingDoor == null
                    ? MovementService.findDoorEscapePlan(bot, target, null)
                    : null;
            if (doorPlan != null) {
                LOGGER.info("wool door-escape: doorBase={} approach={} step={}",
                        doorPlan.doorBase().toShortString(),
                        doorPlan.approachPos().toShortString(),
                        doorPlan.stepThroughPos().toShortString());
                boolean approached = MovementService.nudgeTowardUntilClose(
                        bot, doorPlan.approachPos(), 2.25D, 2200L, 0.18, "wool-door-approach");
                if (approached) {
                    MovementService.tryOpenDoorAt(bot, doorPlan.doorBase());
                    MovementService.nudgeTowardUntilClose(
                            bot, doorPlan.stepThroughPos(), 4.0D, 2600L, 0.22, "wool-door-step");
                    // Retry the main move after stepping through
                    res = MovementService.execute(source, bot, plan, false, true, false, false);
                    if (res.success() || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Door escape helper threw: {}", t.getMessage());
        }
        
        // Final fallback: safe nudge for very close targets
        if (bot.getBlockPos().getSquaredDistance(target) <= 16.0) {
            boolean nudged = MovementService.nudgeTowardUntilClose(bot, target, ARRIVAL_THRESHOLD_SQ, 1500L, 0.2, "wool-final-nudge");
            if (nudged || bot.getBlockPos().getSquaredDistance(target) <= ARRIVAL_THRESHOLD_SQ) {
                return true;
            }
        }
        
        return false;
    }

    private boolean ensureWoolCapacityOrDeposit(ServerPlayerEntity bot, ServerWorld world, ServerCommandSource source) {
        int free = countFreeSlots(bot.getInventory());
        if (free > 0) {
            return true;
        }
        // If inventory is full, try deposit. If still full, stop with an explanation.
        ensureInventorySpace(bot, world, source);
        free = countFreeSlots(bot.getInventory());
        if (free > 0) {
            return true;
        }
        ChatUtils.sendSystemMessage(source, "I don't have space for more wool. I need a nearby chest to store items.");
        return false;
    }

    private int findShearsSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isOf(Items.SHEARS)) continue;
            if (DurabilityPolicyService.shouldAvoid(bot, stack)) continue;
            return i;
        }
        return -1;
    }

    private int findEmptyHotbar(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private int countFreeSlots(Inventory inv) {
        int free = 0;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isEmpty()) free++;
        }
        return free;
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
