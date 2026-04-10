package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.entity.BeehiveBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.BotBeehiveRegistryService;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Idle hobby: collect honey bottles or honeycombs from nearby smoked beehives.
 * Never breaks the hive. Only harvests when the hive is full (honey_level=5)
 * and calmed by smoke (campfire below).
 */
public final class HoneyCollectSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-honey-collect");

    private static final int DEFAULT_COUNT = 2;
    private static final int DEFAULT_RADIUS = 16;
    private static final double REACH_SQ = 4.5 * 4.5;
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

        // Determine harvest tool: prefer glass bottles (food), fall back to shears (crafting)
        boolean hasBottles = hasItem(bot, Items.GLASS_BOTTLE);
        boolean hasShears = hasItem(bot, Items.SHEARS);
        if (!hasBottles && !hasShears) {
            return SkillExecutionResult.failure("No glass bottles or shears available.");
        }

        // Discover and find harvestable hives
        BotBeehiveRegistryService.discoverBeehivesNear(world, bot.getBlockPos(), radius, 6);
        List<BlockPos> candidates = findHarvestableHives(world, bot, radius);
        if (candidates.isEmpty()) {
            return SkillExecutionResult.failure("No harvestable beehives nearby.");
        }

        int harvested = 0;
        Set<BlockPos> unreachable = new HashSet<>();

        for (BlockPos hivePos : candidates) {
            if (harvested >= count) break;
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Honey collection interrupted.");
            }
            if (unreachable.contains(hivePos)) continue;

            // Re-validate before walking (state may have changed)
            if (!isHarvestable(world, hivePos)) continue;

            // Walk to the hive
            if (!moveIntoReach(source, bot, hivePos)) {
                unreachable.add(hivePos);
                continue;
            }

            // Re-validate after walking (bees may have left, honey may have been taken)
            if (!isHarvestable(world, hivePos)) continue;

            // Equip the right tool and harvest
            boolean used;
            if (hasBottles && equipItem(bot, Items.GLASS_BOTTLE)) {
                used = useOnHive(bot, hivePos);
            } else if (hasShears && equipItem(bot, Items.SHEARS)) {
                used = useOnHive(bot, hivePos);
            } else {
                continue;
            }

            if (used) {
                harvested++;
                LOGGER.info("Harvested honey from beehive at {}", hivePos.toShortString());

                // Sweep dropped honeycombs (shears drop items; bottles go into hand)
                try {
                    DropSweeper.sweep(source, DROP_SWEEP_RADIUS, 4.0D, 8, DROP_SWEEP_DURATION_MS);
                } catch (Exception e) {
                    LOGGER.debug("Drop sweep after honey harvest failed: {}", e.getMessage());
                }

                // Brief pause between hives
                sleepQuietly(300L);
            }

            // Refresh tool availability for next iteration
            hasBottles = hasItem(bot, Items.GLASS_BOTTLE);
            hasShears = hasItem(bot, Items.SHEARS);
            if (!hasBottles && !hasShears) break;
        }

        if (harvested <= 0) {
            return SkillExecutionResult.failure("Couldn't harvest any beehives.");
        }

        // Deposit honeycombs and honey bottles in nearby chests
        depositHoneyItems(source, bot, world);

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
        // Must be smoked (campfire within 5 blocks below)
        BlockEntity be = world.getBlockEntity(pos);
        return be instanceof BeehiveBlockEntity hive && hive.isSmoked();
    }

    // ── Interaction ─────────────────────────────────────────────────

    private static boolean useOnHive(ServerPlayerEntity bot, BlockPos hivePos) {
        LookController.faceBlock(bot, hivePos);
        ItemStack handStack = bot.getMainHandStack();
        var result = handStack.useOnBlock(new net.minecraft.item.ItemUsageContext(
                bot, Hand.MAIN_HAND,
                new net.minecraft.util.hit.BlockHitResult(
                        Vec3d.ofCenter(hivePos), Direction.NORTH, hivePos, false)));
        if (result.isAccepted()) {
            bot.swingHand(Hand.MAIN_HAND, true);
            return true;
        }
        return false;
    }

    // ── Movement ────────────────────────────────────────────────────

    private static boolean moveIntoReach(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (bot.getBlockPos().getSquaredDistance(target) <= REACH_SQ) return true;
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, target, target, null, null, null);
        MovementService.MovementResult result = MovementService.execute(
                source, bot, plan, SkillPreferences.teleportDuringSkills(bot), true);
        return result != null && (result.success() || bot.getBlockPos().getSquaredDistance(target) <= REACH_SQ);
    }

    // ── Inventory helpers ───────────────────────────────────────────

    private static boolean hasItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) return true;
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

    private static void depositHoneyItems(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        try {
            ChestStoreService.depositMatchingWalkOnly(source, bot, bot.getBlockPos(),
                    stack -> stack != null && stack.isOf(Items.HONEYCOMB));
            ChestStoreService.depositMatchingWalkOnly(source, bot, bot.getBlockPos(),
                    stack -> stack != null && stack.isOf(Items.HONEY_BOTTLE));
        } catch (Exception e) {
            LOGGER.debug("Honey deposit failed: {}", e.getMessage());
        }
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
