package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.services.BotAutoHuntService;
import net.wcfcarolina13.GameAI.services.BotChestRegistryService;
import net.wcfcarolina13.GameAI.services.BotMutualAidService;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.HealingService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.ProtectedZoneService;
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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ambient hobby: break nearby grass to collect seeds.
 */
public final class GrassSeedSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-grass-seeds");
    private static final Random RNG = new Random();

    private static final int DEFAULT_COUNT = 4;
    private static final int DEFAULT_RADIUS = 12;
    private static final double REACH_SQ = 20.25D;

    private static final long DIALOGUE_COOLDOWN_MS = 20_000L;
    private static final Map<UUID, Long> LAST_DIALOGUE_MS = new ConcurrentHashMap<>();
    private static final String[] DIALOGUE_LINES = new String[] {
            "Breaking some grass for seeds",
            "Could use a few more seeds",
            "Gathering seeds for later",
            "Picking through the grass"
    };

    @Override
    public String name() {
        return "grass_seeds";
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

        int count = clamp(getInt(context.parameters(), "count", DEFAULT_COUNT), 1, 16);
        int radius = clamp(getInt(context.parameters(), "radius", DEFAULT_RADIUS), 6, 20);
        List<BlockPos> targets = findGrass(world, bot.getBlockPos(), radius);
        if (targets.isEmpty()) {
            return SkillExecutionResult.failure("No grass nearby.");
        }

        int gathered = 0;
        for (BlockPos target : targets) {
            if (gathered >= count) {
                break;
            }
            if (shouldYieldForFood(bot, world)) {
                return SkillExecutionResult.failure("Stopped collecting seeds to deal with starvation.");
            }
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Grass gathering paused by another task.");
            }
            if (!isValidTarget(world, target)) {
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
                    sleepQuietly(150L + RNG.nextInt(120));
                }
            } catch (Exception e) {
                LOGGER.debug("Grass break failed at {}: {}", target.toShortString(), e.getMessage());
            }
        }

        if (gathered <= 0) {
            return SkillExecutionResult.failure("Couldn't gather seeds.");
        }

        int stored = depositSeeds(source, bot, world);
        String msg = "Gathered grass for seeds.";
        if (stored > 0) {
            msg = "Gathered seeds and stored " + stored + ".";
        }
        return SkillExecutionResult.success(msg);
    }

    private static List<BlockPos> findGrass(ServerWorld world, BlockPos center, int radius) {
        List<BlockPos> out = new ArrayList<>();
        if (world == null || center == null) {
            return out;
        }
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(center.add(-r, -2, -r), center.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (isGrassLike(world.getBlockState(pos))) {
                out.add(pos.toImmutable());
            }
        }
        out.sort(Comparator.comparingDouble(center::getSquaredDistance));
        return out;
    }

    private static boolean isValidTarget(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null || !world.isChunkLoaded(pos)) {
            return false;
        }
        if (!isGrassLike(world.getBlockState(pos))) {
            return false;
        }
        if (ProtectedZoneService.isProtected(pos, world, null)) {
            return false;
        }
        return !TreeDetector.isNearHumanBlocks(world, pos, 3);
    }

    private static boolean isGrassLike(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN);
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
            if (stack == null || stack.isEmpty() || isSafeHandStack(stack)) {
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

    private static int depositSeeds(ServerCommandSource source, ServerPlayerEntity bot, ServerWorld world) {
        if (source == null || bot == null || world == null) {
            return 0;
        }
        for (BotChestRegistryService.ChestRecord record : BotChestRegistryService.listChestsForOwner(bot, world)) {
            if (record == null || record.destroyed) {
                continue;
            }
            BlockPos chestPos = record.toBlockPos();
            if (!world.isChunkLoaded(chestPos)) {
                continue;
            }
            int moved = ChestStoreService.depositMatchingWalkOnly(source, bot, chestPos, stack -> stack != null && stack.isOf(Items.WHEAT_SEEDS));
            if (moved > 0) {
                return moved;
            }
        }
        return 0;
    }

    private static boolean shouldYieldForFood(ServerPlayerEntity bot, ServerWorld world) {
        if (!HealingService.isStarving(bot)) {
            return false;
        }
        if (HealingService.autoEat(bot)) {
            return true;
        }
        if (BotMutualAidService.tryUrgentFoodRecovery(bot, world)) {
            return true;
        }
        BotAutoHuntService.requestDecisionNow(bot);
        return true;
    }

    private static void maybeShowDialogue(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) {
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
                "grass-seed-hobby",
                "ambient"
        );
    }

    private static int getInt(Map<String, Object> params, String key, int fallback) {
        if (params == null || key == null) {
            return fallback;
        }
        Object raw = params.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
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
