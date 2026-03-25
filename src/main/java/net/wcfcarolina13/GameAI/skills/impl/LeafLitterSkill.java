package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.CompanionSafeZoneService;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Gentle ambient hobby: gather nearby leaf litter without using tools.
 */
public final class LeafLitterSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-leaf-litter");
    private static final Random RNG = new Random();

    private static final int DEFAULT_COUNT = 5;
    private static final int DEFAULT_RADIUS = 12;
    private static final int BASE_AVOID_RADIUS = 16;
    private static final int STORAGE_RADIUS = 12;
    private static final int STORAGE_YSPAN = 5;
    private static final double REACH_SQ = 20.25D;

    private static final long DIALOGUE_COOLDOWN_MS = 22_000L;
    private static final double DIALOGUE_CHANCE = 0.20D;
    private static final Map<UUID, Long> LAST_DIALOGUE_MS = new ConcurrentHashMap<>();
    private static final String[] DIALOGUE_LINES = new String[] {
            "Collecting some leaf litter",
            "Can use this as a fire starter",
            "Gathering kindling",
            "Leaves everywhere",
            "Can start a fire with this",
            "gathering leaves",
            "collecting leaves"
    };

    @Override
    public String name() {
        return "leaf_litter";
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

        int count = clamp(getIntParameter(context.parameters(), "count", DEFAULT_COUNT), 1, 16);
        int radius = clamp(getIntParameter(context.parameters(), "radius", DEFAULT_RADIUS), 6, 24);

        List<BlockPos> candidates = findLeafLitter(world, bot, radius);
        if (candidates.isEmpty()) {
            return SkillExecutionResult.failure("No leaf litter nearby.");
        }

        int gathered = 0;
        for (BlockPos target : candidates) {
            if (gathered >= count) {
                break;
            }
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Leaf litter collection paused by another task.");
            }
            if (!isValidTarget(world, bot, target)) {
                continue;
            }
            if (!moveIntoReach(source, bot, target)) {
                continue;
            }
            if (!equipSafeHandItem(bot)) {
                continue;
            }

            LookController.faceBlock(bot, target);
            try {
                String result = MiningTool.mineBlock(bot, target, true).get(8, TimeUnit.SECONDS);
                if (result != null && result.toLowerCase(Locale.ROOT).contains("complete")) {
                    gathered++;
                    maybeShowDialogue(bot);
                    sleepQuietly(180L + RNG.nextInt(120));
                }
            } catch (Exception e) {
                LOGGER.debug("Leaf litter break failed at {}: {}", target.toShortString(), e.getMessage());
            }
        }

        if (gathered <= 0) {
            return SkillExecutionResult.failure("Couldn't gather leaf litter.");
        }

        int deposited = depositNearbyLeafLitter(source, bot, world);
        String msg = "Collected " + gathered + " leaf litter";
        if (deposited > 0) {
            msg += " and stored " + deposited + ".";
        } else {
            msg += ".";
        }
        return SkillExecutionResult.success(msg);
    }

    private static boolean moveIntoReach(ServerCommandSource source, ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(target) <= REACH_SQ) {
            return true;
        }
        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                target,
                target,
                null,
                null,
                null
        );
        MovementService.MovementResult result = MovementService.execute(
                source,
                bot,
                plan,
                SkillPreferences.teleportDuringSkills(bot),
                true
        );
        return result != null && (result.success() || bot.getBlockPos().getSquaredDistance(target) <= REACH_SQ);
    }

    private static boolean isValidTarget(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        if (world == null || bot == null || pos == null) {
            return false;
        }
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        if (!world.getBlockState(pos).isOf(Blocks.LEAF_LITTER)) {
            return false;
        }
        if (CompanionSafeZoneService.isProtected(world, pos, null)) {
            return false;
        }
        if (isNearHome(world, bot, pos)) {
            return false;
        }
        return !TreeDetector.isNearHumanBlocks(world, pos, 3);
    }

    private static List<BlockPos> findLeafLitter(ServerWorld world, ServerPlayerEntity bot, int radius) {
        List<BlockPos> out = new ArrayList<>();
        if (world == null || bot == null) {
            return out;
        }
        BlockPos center = bot.getBlockPos();
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(center.add(-r, -2, -r), center.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (!world.getBlockState(pos).isOf(Blocks.LEAF_LITTER)) {
                continue;
            }
            out.add(pos.toImmutable());
        }
        out.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return out;
    }

    private static boolean isNearHome(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        if (world == null || bot == null || pos == null) {
            return false;
        }
        Optional<BlockPos> bed = BotHomeService.getLastSleep(bot);
        if (bed.isPresent() && bed.get().getSquaredDistance(pos) <= (double) BASE_AVOID_RADIUS * BASE_AVOID_RADIUS) {
            return true;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        for (BotHomeService.BaseEntry base : BotHomeService.listBases(server, world)) {
            if (base.pos() != null && base.pos().getSquaredDistance(pos) <= (double) BASE_AVOID_RADIUS * BASE_AVOID_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static boolean equipSafeHandItem(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        int selected = bot.getInventory().getSelectedSlot();
        ItemStack selectedStack = bot.getInventory().getStack(selected);
        if (isSafeHandStack(selectedStack)) {
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack == null || stack.isEmpty()) {
                BotActions.selectHotbarSlot(bot, slot);
                return true;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (isSafeHandStack(stack)) {
                BotActions.selectHotbarSlot(bot, slot);
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeHandStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        if (stack.isDamageable()) {
            return false;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        return !key.contains("sword")
                && !key.contains("axe")
                && !key.contains("pickaxe")
                && !key.contains("shovel")
                && !key.contains("hoe")
                && !key.contains("shears")
                && !key.contains("trident")
                && !key.contains("mace")
                && !key.contains("bow")
                && !key.contains("crossbow");
    }

    private static void maybeShowDialogue(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        if (RNG.nextDouble() > DIALOGUE_CHANCE) {
            return;
        }
        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_DIALOGUE_MS.getOrDefault(id, 0L);
        if (now - last < DIALOGUE_COOLDOWN_MS) {
            return;
        }
        LAST_DIALOGUE_MS.put(id, now);
        CompanionOverheadDialogueService.showOverheadLine(
                bot,
                DIALOGUE_LINES[RNG.nextInt(DIALOGUE_LINES.length)],
                2_800,
                32.0D,
                "leaf-litter-hobby",
                "ambient"
        );
    }

    private static int depositNearbyLeafLitter(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        if (source == null || bot == null || world == null) {
            return 0;
        }
        for (BlockPos chestPos : findNearbyStorage(world, bot.getBlockPos(), STORAGE_RADIUS, STORAGE_YSPAN)) {
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, stack -> stack != null && stack.isOf(Items.LEAF_LITTER));
            if (moved > 0) {
                return moved;
            }
        }
        return 0;
    }

    private static List<BlockPos> findNearbyStorage(ServerWorld world, BlockPos origin, int radius, int ySpan) {
        List<BlockPos> out = new ArrayList<>();
        if (world == null || origin == null) {
            return out;
        }
        int r = Math.max(2, radius);
        int ys = Math.max(2, ySpan);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -ys, -r), origin.add(r, ys, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(Blocks.CHEST)
                    || world.getBlockState(pos).isOf(Blocks.TRAPPED_CHEST)
                    || world.getBlockState(pos).isOf(Blocks.BARREL)) {
                out.add(pos.toImmutable());
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        return out;
    }

    private static int getIntParameter(Map<String, Object> params, String key, int def) {
        if (params == null || key == null) {
            return def;
        }
        Object raw = params.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
