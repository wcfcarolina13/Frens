package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.registry.tag.FluidTags;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.LookController;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.services.ChestStoreService;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FishingSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-fishing");
    private static final int DEFAULT_TARGET_FISH = 1;
    private static final int MAX_ATTEMPTS_PER_FISH = 6;
    private static final double APPROACH_REACH_SQ = 1.44D;
    private static final int WATER_SEARCH_RADIUS = 10;
    private static final long CAST_WAIT_MS = 6_000L;
    private static final Set<Item> FISH_ITEMS = Set.of(
            Items.COD,
            Items.SALMON,
            Items.TROPICAL_FISH,
            Items.PUFFERFISH
    );

    private static final record FishingSpot(BlockPos water, BlockPos stand) {}

    private static final Field CAUGHT_FISH_FIELD = findCaughtFishField();

    @Override
    public String name() {
        return "fish";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("Bot not available.");
        }

        if (!ensureFishingRod(source, bot)) {
            return SkillExecutionResult.failure("Need a fishing rod (3 sticks + 2 strings) before fishing.");
        }

        FishingSpot spot = findFishingSpot(bot, WATER_SEARCH_RADIUS);
        if (spot == null) {
            if (hasNearbyWater(bot, WATER_SEARCH_RADIUS)) {
                return SkillExecutionResult.failure("No safe shoreline block to stand on.");
            }
            return SkillExecutionResult.failure("I need to be standing near open water to fish.");
        }

        BlockPos stand = spot.stand();
        boolean approached = MovementService.nudgeTowardUntilClose(bot, stand, APPROACH_REACH_SQ, 2600L, 0.16D, "fishing-approach");
        if (!approached) {
            return SkillExecutionResult.failure("Can't reach the fishing spot.");
        }

        if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
            return SkillExecutionResult.failure("Unable to equip the fishing rod.");
        }

        boolean untilSunset = isUntilSunset(context.parameters());
        int targetFish = getIntParameter(context.parameters(), "count", -1);
        
        if (targetFish <= 0) {
            targetFish = untilSunset ? Integer.MAX_VALUE : DEFAULT_TARGET_FISH;
        }

        int maxAttempts = targetFish == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(targetFish * MAX_ATTEMPTS_PER_FISH, MAX_ATTEMPTS_PER_FISH);
        int caught = 0;
        int attempts = 0;
        int baseline = countFish(bot);
        ServerWorld world = (ServerWorld) bot.getEntityWorld();

        String modeDesc = untilSunset ? "until sunset" : (targetFish + " catches");
        LOGGER.info("Starting fishing session for {} (mode: {})", bot.getName().getString(), modeDesc);

        while (caught < targetFish && attempts < maxAttempts) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Fishing paused by another task.");
            }

            if (untilSunset) {
                long timeOfDay = world.getTimeOfDay() % 24000;
                if (timeOfDay >= 13000 && timeOfDay < 23000) {
                    ChatUtils.sendSystemMessage(source, "Sun has set. Stopping fishing.");
                    break;
                }
            }

            // Inventory Check
            if (isInventoryFull(bot)) {
                LOGGER.info("Inventory full. Attempting to store items.");
                boolean cleared = handleFullInventory(bot, source, stand);
                if (!cleared) {
                    ChatUtils.sendSystemMessage(source, "Inventory full and couldn't store items. Stopping.");
                    break;
                }
                // Re-equip rod after potential crafting/placing
                if (!BotActions.ensureHotbarItem(bot, Items.FISHING_ROD)) {
                    return SkillExecutionResult.failure("Lost fishing rod during storage routine.");
                }
                // Re-approach stand just in case we moved
                MovementService.nudgeTowardUntilClose(bot, stand, APPROACH_REACH_SQ, 1500L, 0.16D, "fishing-reapproach");
            }

            aimTowardWater(bot, spot.water());
            BotActions.useSelectedItem(bot); // Cast
            boolean caughtFish = waitForBite(bot);
            
            if (!caughtFish) {
                 // Retract (if bobber still exists)
                 BotActions.useSelectedItem(bot);
                 // Don't count as attempt if interrupted or just missed? 
                 // Actually, if we timed out, it's an attempt.
                 attempts++;
                 continue;
            }

            // Reel in
            aimTowardWater(bot, spot.water());
            BotActions.useSelectedItem(bot);
            waitForBobberRemoval(bot);
            
            // Wait for item to arrive (rod pulls it)
            sleep(600L); 
            
            // Verify catch
            int now = countFish(bot);
            int delta = now - baseline;
            if (delta > 0) {
                caught += delta;
                baseline = now;
            } else {
                caught += 1; // assume bite still produced a fish/item even if it dropped or inventory stayed constant
                // keep baseline in sync so we don't regrow the same delta if we pick it up later
                baseline = now;
            }
            attempts++;
            
            // Prevent bot from wandering into water for drops
            // We rely on the rod pulling the fish. If it didn't reach us, ignore it for safety.
            BotActions.stop(bot); 
        }

        if (caught == 0 && attempts > 0) {
            return SkillExecutionResult.failure("No bites after " + attempts + " casts.");
        }
        
        ChatUtils.sendSystemMessage(source, "Fishing session finished. Caught " + caught + " items.");
        return SkillExecutionResult.success("Fishing succeeded (" + caught + " items).");
    }

    private static boolean handleFullInventory(ServerPlayerEntity bot, ServerCommandSource source, BlockPos safeStand) {
        BlockPos chestPos = findNearbyChestWithSpace(bot);
        if (chestPos == null) {
            if (!hasItem(bot, Items.CHEST)) {
                CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "chest", 1, null);
            }
            if (hasItem(bot, Items.CHEST)) {
                chestPos = placeChestNearby(bot, safeStand);
            }
        }
        if (chestPos != null) {
            LOGGER.info("Depositing items to chest at {}", chestPos.toShortString());
            int deposited = ChestStoreService.depositAll(source, bot, chestPos);
            if (deposited > 0) {
                return true;
            } else {
                LOGGER.warn("Failed to deposit items to chest at {}", chestPos.toShortString());
            }
        }
        return !isInventoryFull(bot);
    }

    private static BlockPos findNearbyChestWithSpace(ServerPlayerEntity bot) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-5, -2, -5), origin.add(5, 2, 5))) {
            if (world.getBlockState(pos).isOf(Blocks.CHEST) || world.getBlockState(pos).isOf(Blocks.TRAPPED_CHEST)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos placeChestNearby(ServerPlayerEntity bot, BlockPos near) {
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = near.offset(dir);
            if (world.getBlockState(candidate).isAir() && 
                world.getBlockState(candidate.down()).isSolidBlock(world, candidate.down())) {
                
                BotActions.placeBlockAt(bot, candidate, List.of(Items.CHEST));
                if (world.getBlockState(candidate).isOf(Blocks.CHEST)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean ensureFishingRod(ServerCommandSource source, ServerPlayerEntity bot) {
        if (hasItem(bot, Items.FISHING_ROD)) {
            return true;
        }
        int crafted = CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "fishing_rod", 1, null);
        return crafted > 0 && hasItem(bot, Items.FISHING_ROD);
    }

    private static FishingSpot findFishingSpot(ServerPlayerEntity bot, int radius) {
        if (bot == null) {
            return null;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) {
            return null;
        }
        BlockPos origin = bot.getBlockPos();
        FishingSpot best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos water : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(water)) {
                continue;
            }
            if (!world.getFluidState(water).isIn(FluidTags.WATER)) {
                continue;
            }
            BlockPos stand = findStandPosition(world, water, origin);
            if (stand == null) {
                continue;
            }
            double score = origin.getSquaredDistance(stand);
            if (score < bestScore) {
                bestScore = score;
                best = new FishingSpot(water.toImmutable(), stand);
            }
        }
        return best;
    }

    private static boolean hasNearbyWater(ServerPlayerEntity bot, int radius) {
        if (bot == null) {
            return false;
        }
        ServerWorld world = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
        if (world == null) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getFluidState(pos).isIn(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos findStandPosition(ServerWorld world, BlockPos water, BlockPos botPos) {
        if (world == null || water == null || botPos == null) {
            return null;
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos min = water.add(-2, -1, -2);
        BlockPos max = water.add(2, 1, 2);
        for (BlockPos candidate : BlockPos.iterate(min, max)) {
            if (candidate.equals(water)) {
                continue;
            }
            if (!isAdjacentToWater(candidate, water)) {
                continue;
            }
            if (!isStandable(world, candidate)) {
                continue;
            }
            double dist = botPos.getSquaredDistance(candidate);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        if (best == null) {
            BlockPos above = water.up();
            if (isStandable(world, above)) {
                best = above;
            }
        }
        return best;
    }

    private static boolean isAdjacentToWater(BlockPos candidate, BlockPos water) {
        if (candidate == null || water == null) {
            return false;
        }
        int dx = Math.abs(candidate.getX() - water.getX());
        int dz = Math.abs(candidate.getZ() - water.getZ());
        int dy = Math.abs(candidate.getY() - water.getY());
        return dx <= 1 && dz <= 1 && dy <= 1;
    }

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState body = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockPos below = pos.down();
        BlockState belowState = world.getBlockState(below);
        if (!isSpaceClear(world, pos) || !isSpaceClear(world, pos.up())) {
            return false;
        }
        return !belowState.isAir() && !belowState.getCollisionShape(world, below).isEmpty();
    }

    private static boolean isSpaceClear(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static void aimTowardWater(ServerPlayerEntity bot, BlockPos water) {
        if (bot == null || water == null) {
            return;
        }
        LookController.faceBlock(bot, water);
    }

    private static boolean waitForBite(ServerPlayerEntity bot) {
        long deadline = System.currentTimeMillis() + CAST_WAIT_MS * 4;
        while (!SkillManager.shouldAbortSkill(bot) && System.currentTimeMillis() < deadline) {
            FishingBobberEntity bobber = findActiveBobber(bot);
            boolean bite = hasFishBite(bobber);
            if (bite) {
                LOGGER.info("Fishing bite detected near {} for {}", bobber != null ? bobber.getBlockPos().toShortString() : "unknown", bot.getName().getString());
                return true;
            }
            sleep(250L);
        }
        return false;
    }

    private static boolean hasFishBite(FishingBobberEntity bobber) {
        if (bobber == null) {
            return false;
        }
        if (CAUGHT_FISH_FIELD != null) {
            try {
                return CAUGHT_FISH_FIELD.getBoolean(bobber);
            } catch (IllegalAccessException e) {
                LOGGER.debug("Caught-fish field access failed", e);
            }
        }
        return bobber.getHookedEntity() != null;
    }

    private static Field findCaughtFishField() {
        String[] candidates = {"caughtFish", "field_23232"};
        for (String candidate : candidates) {
            Field field = tryField(candidate);
            if (field != null) {
                LOGGER.info("Fishing bite detection bound to field {}", field.getName());
                return field;
            }
        }
        LOGGER.warn("Unable to access a fishing bite flag field, tried {}", Arrays.toString(candidates));
        return null;
    }

    private static Field tryField(String name) {
        try {
            Field field = FishingBobberEntity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            LOGGER.debug("Fishing bobber field {} not found", name, e);
        } catch (Throwable throwable) {
            LOGGER.warn("Unexpected error while binding fishing bobber field {}", name, throwable);
        }
        return null;
    }

    private static void waitForBobberRemoval(ServerPlayerEntity bot) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return;
            }
            if (findActiveBobber(bot) == null) {
                return;
            }
            sleep(150L);
        }
    }

    private static FishingBobberEntity findActiveBobber(ServerPlayerEntity bot) {
        if (bot == null || bot.getEntityWorld() == null) {
            return null;
        }
        World world = bot.getEntityWorld();
        if (!(world instanceof ServerWorld serverWorld)) {
            return null;
        }
        var bbox = bot.getBoundingBox().expand(8.0D, 4.0D, 8.0D);
        for (FishingBobberEntity bobber : serverWorld.getEntitiesByClass(FishingBobberEntity.class, bbox, entity -> {
            var owner = entity.getPlayerOwner();
            return owner != null && Objects.equals(owner.getUuid(), bot.getUuid());
        })) {
            if (!bobber.isRemoved()) {
                return bobber;
            }
        }
        return null;
    }

    private static int countFish(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int total = 0;
        for (Item fish : FISH_ITEMS) {
            total += countItem(bot, fish);
        }
        return total;
    }

    private static boolean isInventoryFull(ServerPlayerEntity bot) {
        return bot != null && bot.getInventory().getEmptySlot() == -1;
    }

    private static int countItem(ServerPlayerEntity bot, Item item) {
        if (bot == null || item == null) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            Item stackItem = bot.getInventory().getStack(i).getItem();
            if (stackItem == item) {
                sum += bot.getInventory().getStack(i).getCount();
            }
        }
        return sum;
    }

    private static boolean hasItem(ServerPlayerEntity bot, Item item) {
        return countItem(bot, item) > 0;
    }

    private static int getIntParameter(Map<String, Object> params, String key, int defaultValue) {
        if (params == null || key == null) {
            return defaultValue;
        }
        Object raw = params.get(key);
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (raw instanceof String str) {
            try {
                return Math.max(0, Integer.parseInt(str));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
    
    private static boolean isUntilSunset(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        if (params.containsKey("until_sunset")) {
             Object val = params.get("until_sunset");
             if (val instanceof Boolean b) return b;
             if (val instanceof String s) return Boolean.parseBoolean(s);
        }
        if (params.containsKey("options") && params.get("options") instanceof List<?> list) {
            for (Object obj : list) {
                if (obj instanceof String s) {
                    if (s.equalsIgnoreCase("until_sunset") || s.equalsIgnoreCase("sunset")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
