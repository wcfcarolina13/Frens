package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shows ambient overhead dialogue when a bot lingers near an enchanting table
 * with enough XP to make it worth mentioning.
 *
 * <p>Two thresholds: 20+ levels (general awareness) and 30+ levels (ready for
 * top-tier enchantments). Per-bot cooldown + random chance prevent spam.</p>
 */
public final class EnchantingAmbientDialogueService {

    private static final int TABLE_RADIUS = 5;
    private static final int XP_THRESHOLD_MID = 20;
    private static final int XP_THRESHOLD_HIGH = 30;

    /** Minimum time between enchanting lines for the same bot. */
    private static final long COOLDOWN_MS = 120_000L; // 2 minutes

    /** Probability of speaking when the cooldown has elapsed (per tick). */
    private static final double SPEAK_CHANCE = 0.005; // ~0.5% per tick ~ once per ~10s of proximity

    private static final int DURATION_MS = 3_500;
    private static final double RANGE = 32.0;

    private static final ConcurrentHashMap<UUID, Long> LAST_LINE_MS = new ConcurrentHashMap<>();

    private static final String[] MID_XP_LINES = {
            "All this experience and nothing to show for it...",
            "I wonder what enchantments I could get with these levels.",
            "That enchanting table is calling my name.",
            "I've got some levels saved up. Could be useful."
    };

    private static final String[] HIGH_XP_LINES = {
            "Thirty levels. I could get something really good.",
            "I'm sitting on a goldmine of experience here.",
            "Time for some serious enchantments, don't you think?",
            "These levels won't spend themselves. Just saying."
    };

    private EnchantingAmbientDialogueService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long now = System.currentTimeMillis();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld)) {
                continue;
            }

            int xp = bot.experienceLevel;
            if (xp < XP_THRESHOLD_MID) {
                continue;
            }

            if (!isNearEnchantingTable(bot)) {
                continue;
            }

            UUID id = bot.getUuid();

            // Per-bot cooldown
            long last = LAST_LINE_MS.getOrDefault(id, 0L);
            if (now - last < COOLDOWN_MS) {
                continue;
            }

            // Check global overhead suppression (avoid stomping other dialogue)
            if (CompanionOverheadDialogueService.isRecentlyShown(id)) {
                continue;
            }

            // Random chance gate
            if (ThreadLocalRandom.current().nextDouble() > SPEAK_CHANCE) {
                continue;
            }

            LAST_LINE_MS.put(id, now);

            String[] pool = xp >= XP_THRESHOLD_HIGH ? HIGH_XP_LINES : MID_XP_LINES;
            String line = pool[ThreadLocalRandom.current().nextInt(pool.length)];

            CompanionOverheadDialogueService.showOverheadLine(
                    bot, line, DURATION_MS, RANGE, "enchant-ambient", null);
        }
    }

    private static boolean isNearEnchantingTable(ServerPlayerEntity bot) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(
                origin.add(-TABLE_RADIUS, -2, -TABLE_RADIUS),
                origin.add(TABLE_RADIUS, 2, TABLE_RADIUS))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    public static void clearCooldowns() {
        LAST_LINE_MS.clear();
    }
}
