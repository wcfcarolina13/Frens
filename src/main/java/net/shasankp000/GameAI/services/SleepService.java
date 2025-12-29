package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.LookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.MinecraftServer;
import net.shasankp000.GameAI.BotActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SleepService {

    private static final Logger LOGGER = LoggerFactory.getLogger("sleep-service");
    private static final double STAND_REACH_SQ = 4.5D * 4.5D;
    private static final List<Item> BED_ITEMS = List.of(
            Items.WHITE_BED, Items.BLACK_BED, Items.BLUE_BED, Items.BROWN_BED, Items.CYAN_BED, Items.GRAY_BED,
            Items.GREEN_BED, Items.LIGHT_BLUE_BED, Items.LIGHT_GRAY_BED, Items.LIME_BED, Items.MAGENTA_BED,
            Items.ORANGE_BED, Items.PINK_BED, Items.PURPLE_BED, Items.RED_BED, Items.YELLOW_BED
    );

    private enum BedUseResult {
        SUCCESS,
        FAIL_NOT_SLEEP_TIME,
        FAIL_OTHER
    }

    private SleepService() {}

    public static boolean sleep(ServerCommandSource source, ServerPlayerEntity bot) {
        if (source == null || bot == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (!dimensionAllowsBeds(world)) {
            ChatUtils.sendSystemMessage(source, "Beds don't work in this dimension.");
            return false;
        }
        if (bot.isSleeping()) {
            return true;
        }

        boolean canSleepNow = canSleepNow(world);

        // 1) Try existing nearby bed.
        Optional<BlockPos> bed = findNearestBed(world, bot.getBlockPos(), 24);
        if (bed.isPresent()) {
            BedUseResult res = tryUseBed(source, bot, bed.get());
            if (res == BedUseResult.SUCCESS) {
                return true;
            }
            // If a nearby bed exists but we simply can't sleep yet (daytime), don't start crafting/placing.
            if (res == BedUseResult.FAIL_NOT_SLEEP_TIME) {
                return false;
            }
        }

        // If we can't sleep right now, avoid crafting/placing beds.
        if (!canSleepNow) {
            ChatUtils.sendSystemMessage(source, "I couldn't sleep right now (not night/thunder).");
            return false;
        }

        // 2) Ensure we have a bed item (craft if possible).
        if (!hasAnyBed(bot)) {
            ServerPlayerEntity commander = source.getPlayer();
            if (commander == null) {
                ChatUtils.sendSystemMessage(source, "Only players can ask bots to craft a bed.");
                return false;
            }
            ensureCraftingTableNearby(source, bot, world);
            int crafted = CraftingHelper.craftGeneric(source, bot, commander, "bed", 1, null);
            if (crafted <= 0 && !hasAnyBed(bot)) {
                ChatUtils.sendSystemMessage(source, "I couldn't craft a bed (need 3 wool + 3 planks).");
                return false;
            }
        }

        // 3) Place a bed near the bot and sleep in it.
        BlockPos placed = placeBedNearby(source, bot, world, 4);
        if (placed == null) {
            ChatUtils.sendSystemMessage(source, "I couldn't find a safe place to set a bed down.");
            return false;
        }
        return tryUseBed(source, bot, placed) == BedUseResult.SUCCESS;
    }

    private static BedUseResult tryUseBed(ServerCommandSource source, ServerPlayerEntity bot, BlockPos bedPos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return BedUseResult.FAIL_OTHER;
        }

        List<BlockPos> stands = findStandableAdjacent(world, bedPos);
        for (BlockPos stand : stands) {
            if (!moveToStand(source, bot, stand)) {
                continue;
            }
            if (!MovementService.tryOpenDoorToward(bot, bedPos)) {
                // ok, might not need door.
            }
            runOnServerThread(bot, () -> LookController.faceBlock(bot, bedPos));
            if (bot.getBlockPos().getSquaredDistance(stand) > STAND_REACH_SQ) {
                continue;
            }

            ActionResult result = interactBed(bot, world, bedPos);
            LOGGER.info("Bed interact result={} botSleeping={}", result, bot.isSleeping());
            if (bot.isSleeping()) {
                BotHomeService.recordLastSleep(bot, bedPos.toImmutable());
                ChatUtils.sendSystemMessage(source, bot.getName().getString() + " is sleeping.");
                return BedUseResult.SUCCESS;
            }
        }

        if (canSleepNow(world)) {
            ChatUtils.sendSystemMessage(source, "I couldn't get into the bed (blocked or unsafe).");
            return BedUseResult.FAIL_OTHER;
        }
        ChatUtils.sendSystemMessage(source, "I couldn't sleep right now (not night/thunder).");
        return BedUseResult.FAIL_NOT_SLEEP_TIME;
    }

    private static boolean canSleepNow(ServerWorld world) {
        if (world == null) {
            return false;
        }
        // Vanilla allows sleeping at night, and also during thunderstorms.
        return !world.isDay() || world.isThundering();
    }

    private static ActionResult interactBed(ServerPlayerEntity bot, ServerWorld world, BlockPos bedPos) {
        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        runOnServerThread(bot, () -> {
            selectEmptyOrSafeHand(bot);
            Vec3d hitVec = Vec3d.ofCenter(bedPos);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, bedPos, false);
            future.complete(bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit));
        });
        try {
            return future.get(750, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionResult.FAIL;
        } catch (ExecutionException | TimeoutException e) {
            return ActionResult.FAIL;
        }
    }

    private static void selectEmptyOrSafeHand(ServerPlayerEntity bot) {
        // Avoid holding placeable blocks/food when right-clicking the bed.
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                BotActions.selectHotbarSlot(bot, i);
                return;
            }
        }
        // Fallback: keep current slot.
    }

    private static boolean moveToStand(ServerCommandSource source, ServerPlayerEntity bot, BlockPos stand) {
        if (bot.getBlockPos().getSquaredDistance(stand) <= 2.0D) {
            return true;
        }
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                stand,
                stand,
                null,
                null,
                bot.getHorizontalFacing()
        );
        MovementService.MovementResult res = MovementService.execute(source, bot, plan, false, true, true, false);
        return res.success() || bot.getBlockPos().getSquaredDistance(stand) <= STAND_REACH_SQ;
    }

    private static Optional<BlockPos> findNearestBed(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return Optional.empty();
        }
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -3, -radius), origin.add(radius, 3, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.BEDS)) {
                continue;
            }
            double sq = origin.getSquaredDistance(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = pos.toImmutable();
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean dimensionAllowsBeds(ServerWorld world) {
        if (world == null) {
            return false;
        }
        RegistryKey<World> key = world.getRegistryKey();
        return key != World.NETHER && key != World.END;
    }

    private static List<BlockPos> findStandableAdjacent(ServerWorld world, BlockPos bedPos) {
        List<BlockPos> stands = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos stand = bedPos.offset(dir);
            if (isStandable(world, stand)) {
                stands.add(stand.toImmutable());
            }
        }
        stands.sort((a, b) -> Double.compare(a.getSquaredDistance(bedPos), b.getSquaredDistance(bedPos)));
        return stands;
    }

    private static boolean isStandable(ServerWorld world, BlockPos foot) {
        if (world == null || foot == null) {
            return false;
        }
        BlockPos below = foot.down();
        if (!world.getBlockState(below).isSolidBlock(world, below)) {
            return false;
        }
        if (!world.getBlockState(foot).getCollisionShape(world, foot).isEmpty()) {
            return false;
        }
        return world.getBlockState(foot.up()).getCollisionShape(world, foot.up()).isEmpty();
    }

    private static boolean hasAnyBed(ServerPlayerEntity bot) {
        return findBedItem(bot).isPresent();
    }

    private static Optional<Item> findBedItem(ServerPlayerEntity bot) {
        if (bot == null) {
            return Optional.empty();
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BED_ITEMS.contains(stack.getItem())) {
                return Optional.of(stack.getItem());
            }
        }
        return Optional.empty();
    }

    private static BlockPos placeBedNearby(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world, int radius) {
        Optional<Item> bedItemOpt = findBedItem(bot);
        if (bedItemOpt.isEmpty()) {
            return null;
        }
        Item bedItem = bedItemOpt.get();
        BlockPos origin = bot.getBlockPos();

        // Try a few placements around the bot. Ensure 2-block clearance for the bed footprint.
        for (Direction facing : Direction.Type.HORIZONTAL) {
            for (int r = 1; r <= radius; r++) {
                BlockPos foot = origin.offset(facing, r);
                if (!isPlaceableBedFoot(world, foot, facing)) {
                    continue;
                }
                if (origin.getSquaredDistance(foot) > STAND_REACH_SQ) {
                    continue;
                }
                boolean placed = tryPlaceBed(bot, world, foot, facing, bedItem);
                if (placed && world.getBlockState(foot).isIn(BlockTags.BEDS) && world.getBlockState(foot.offset(facing)).isIn(BlockTags.BEDS)) {
                    LOGGER.info("Placed bed at {}", foot.toShortString());
                    ChatUtils.sendSystemMessage(source, "Placed a bed.");
                    return foot.toImmutable();
                }
            }
        }
        return null;
    }

    private static boolean tryPlaceBed(ServerPlayerEntity bot,
                                      ServerWorld world,
                                      BlockPos foot,
                                      Direction facing,
                                      Item bedItem) {
        if (bot == null || world == null || foot == null || facing == null || bedItem == null) {
            return false;
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        runOnServerThread(bot, () -> {
            try {
                if (!BotActions.ensureHotbarItem(bot, bedItem)) {
                    future.complete(false);
                    return;
                }
                bot.setYaw(yawForFacing(facing));
                bot.setPitch(0.0f);

                BlockPos support = foot.down();
                Vec3d hitVec = Vec3d.ofCenter(support).add(0, 0.49, 0);
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, support, false);
                ActionResult res = bot.interactionManager.interactBlock(bot, world, bot.getMainHandStack(), Hand.MAIN_HAND, hit);
                if (res.isAccepted()) {
                    bot.swingHand(Hand.MAIN_HAND, true);
                }
                future.complete(world.getBlockState(foot).isIn(BlockTags.BEDS));
            } catch (Throwable t) {
                future.complete(false);
            }
        });
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private static float yawForFacing(Direction facing) {
        if (facing == null) {
            return 0.0f;
        }
        return switch (facing) {
            case NORTH -> 180.0f;
            case SOUTH -> 0.0f;
            case EAST -> -90.0f;
            case WEST -> 90.0f;
            default -> 0.0f;
        };
    }

    private static boolean isPlaceableBedFoot(ServerWorld world, BlockPos foot, Direction facing) {
        if (world == null || foot == null || facing == null) {
            return false;
        }
        BlockPos head = foot.offset(facing);
        BlockPos footBelow = foot.down();
        BlockPos headBelow = head.down();

        if (!world.getBlockState(footBelow).isSolidBlock(world, footBelow)) return false;
        if (!world.getBlockState(headBelow).isSolidBlock(world, headBelow)) return false;
        if (!world.getBlockState(foot).isAir()) return false;
        if (!world.getBlockState(head).isAir()) return false;
        if (!world.getBlockState(foot.up()).getCollisionShape(world, foot.up()).isEmpty()) return false;
        if (!world.getBlockState(head.up()).getCollisionShape(world, head.up()).isEmpty()) return false;
        return true;
    }

    private static void ensureCraftingTableNearby(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        if (source == null || bot == null || world == null) {
            return;
        }
        // If a crafting table is already within reach, we're good.
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-3, -1, -3), origin.add(3, 1, 3))) {
            if (!world.isChunkLoaded(pos)) continue;
            if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
                if (origin.getSquaredDistance(pos) <= STAND_REACH_SQ) {
                    return;
                }
            }
        }

        // Place from inventory, or craft one (2x2) and place.
        boolean hasTable = countItem(bot, Items.CRAFTING_TABLE) > 0;
        if (!hasTable) {
            ServerPlayerEntity commander = source.getPlayer();
            if (commander != null) {
                CraftingHelper.craftGeneric(source, bot, commander, "crafting_table", 1, null);
            }
        }
        if (countItem(bot, Items.CRAFTING_TABLE) <= 0) {
            return;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos placeAt = origin.offset(dir);
            if (!world.getBlockState(placeAt).isAir()) continue;
            if (!world.getBlockState(placeAt.down()).isSolidBlock(world, placeAt.down())) continue;
            if (BotActions.placeBlockAt(bot, placeAt, List.of(Items.CRAFTING_TABLE))) {
                return;
            }
        }
    }

    private static int countItem(ServerPlayerEntity bot, Item item) {
        if (bot == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean runOnServerThread(ServerPlayerEntity player, Runnable action) {
        MinecraftServer server = player != null && player.getCommandSource() != null
                ? player.getCommandSource().getServer()
                : null;
        if (server == null) {
            action.run();
            return true;
        }
        if (server.isOnThread()) {
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
            future.get(750, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }
}
