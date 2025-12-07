package net.shasankp000.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.support.TreeDetector;
import net.shasankp000.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Emergency shelter builder: erects a small hovel using cheap blocks (dirt/cobble/etc.)
 * around the bot, clearing interior space and adding a roof and torches.
 */
public final class ShelterSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-shelter");
    private static final List<Item> BUILD_BLOCKS = List.of(
            Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT,
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.SANDSTONE, Items.RED_SANDSTONE,
            Items.ANDESITE, Items.GRANITE, Items.DIORITE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
            Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

    @Override
    public String name() {
        return "shelter";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = Objects.requireNonNull(source.getPlayer(), "player");
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("No world available for shelter.");
        }
        BlockPos center = bot.getBlockPos();
        int radius = 3; // 7x7 footprint, enough for 3 players/beds
        int wallHeight = 3;
        int roofY = center.getY() + wallHeight;
        int neededBlocks = estimateBlockNeed(radius, wallHeight);
        ensureBuildStock(source, bot, neededBlocks);

        // Clear interior and shape walls/roof
        buildHovel(world, bot, center, radius, wallHeight);
        placeTorches(world, bot, center, radius);
        ChatUtils.sendSystemMessage(source, "Emergency hovel built.");
        return SkillExecutionResult.success("Shelter (hovel) built.");
    }

    private int estimateBlockNeed(int radius, int wallHeight) {
        int side = radius * 2 + 1;
        int roof = side * side;
        int perimeter = (side * 4 - 4) * wallHeight;
        return roof + perimeter + 10; // small buffer
    }

    private void ensureBuildStock(ServerCommandSource source, ServerPlayerEntity bot, int needed) {
        int available = countBuildBlocks(bot);
        if (available >= needed) {
            LOGGER.info("Shelter: {} blocks available (need {})", available, needed);
            return;
        }
        int toGather = needed - available;
        LOGGER.info("Shelter: short {} blocks; gathering dirt.", toGather);
        Map<String, Object> params = new HashMap<>();
        params.put("count", Math.max(toGather, 32));
        try {
            CollectDirtSkill collect = new CollectDirtSkill();
            SkillContext ctx = new SkillContext(source, new HashMap<>(), params);
            var res = collect.execute(ctx);
            if (!res.success()) {
                LOGGER.warn("Shelter dirt collection failed: {}", res.message());
            }
        } catch (Exception e) {
            LOGGER.warn("Shelter dirt collection errored: {}", e.getMessage());
        }
    }

    private void buildHovel(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius, int wallHeight) {
        int floorY = center.getY();
        int roofY = floorY + wallHeight;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos column = center.add(dx, 0, dz);
                boolean perimeter = Math.abs(dx) == radius || Math.abs(dz) == radius;
                // Clear interior space
                if (!perimeter) {
                    clearColumn(world, bot, column, floorY, roofY - 1);
                }
                // Ensure floor support
                BlockPos floor = new BlockPos(column.getX(), floorY, column.getZ());
                if (world.getBlockState(floor).isAir() || world.getBlockState(floor).isReplaceable()) {
                    placeBlock(bot, floor);
                }
                // Walls
                if (perimeter) {
                    for (int y = floorY + 1; y <= roofY; y++) {
                        BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                        BlockState state = world.getBlockState(pos);
                        if (!state.isAir() && !state.isReplaceable()) {
                            continue; // terrain wall is fine
                        }
                        placeBlock(bot, pos);
                    }
                }
                // Roof
                BlockPos roof = new BlockPos(column.getX(), roofY, column.getZ());
                BlockState roofState = world.getBlockState(roof);
                if (roofState.isAir() || roofState.isReplaceable() || roofState.isIn(BlockTags.LEAVES)) {
                    placeBlock(bot, roof);
                }
            }
        }
    }

    private void clearColumn(ServerWorld world, ServerPlayerEntity bot, BlockPos column, int floorY, int topY) {
        for (int y = floorY; y <= topY; y++) {
            BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (state.isIn(BlockTags.LEAVES) || state.isReplaceable() || state.isOf(Blocks.SNOW) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
                mineSoft(bot, pos);
            }
        }
    }

    private void placeTorches(ServerWorld world, ServerPlayerEntity bot, BlockPos center, int radius) {
        List<BlockPos> spots = new ArrayList<>();
        spots.add(center.add(radius - 1, 1, radius - 1));
        spots.add(center.add(-radius + 1, 1, -radius + 1));
        for (BlockPos pos : spots) {
            BlockPos floor = pos.down();
            if (!world.getBlockState(floor).isSolidBlock(world, floor)) {
                continue;
            }
            if (world.getBlockState(pos).isAir()) {
                BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(Items.TORCH));
            }
        }
    }

    private void placeBlock(ServerPlayerEntity bot, BlockPos pos) {
        Item blockItem = selectBuildItem(bot);
        if (blockItem == null) {
            return;
        }
        BlockState state = bot.getEntityWorld().getBlockState(pos);
        if (state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.SNOW)) {
            mineSoft(bot, pos);
        }
        BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(blockItem));
    }

    private Item selectBuildItem(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                return stack.getItem();
            }
        }
        return null;
    }

    private int countBuildBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (BUILD_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void mineSoft(ServerPlayerEntity bot, BlockPos pos) {
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        try {
            MiningTool.mineBlock(bot, pos).get(3_000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.warn("Shelter: failed to clear {}: {}", pos.toShortString(), e.getMessage());
        }
    }
}
