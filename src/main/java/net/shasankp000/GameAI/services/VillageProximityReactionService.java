package net.shasankp000.GameAI.services;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.shasankp000.ChatUtils.BotDialoguePlayer;
import net.shasankp000.ChatUtils.BotDialogueSounds;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotEventHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Villager proximity and interaction reactions for Batch 3 Phase 1.
 */
public final class VillageProximityReactionService {

    private static final double RADIUS_XZ = 40.0;
    private static final double RADIUS_Y = 16.0;
    private static final long VILLAGER_NOISE_COOLDOWN_MS = 90_000L;
    private static final long VILLAGER_NEGOTIATE_COOLDOWN_MS = 90_000L;

    // Rarity weights: COMMON=10, UNCOMMON=5
    private static final int WEIGHT_COMMON = 10;
    private static final int WEIGHT_UNCOMMON = 5;

    private static final Random RNG = new Random();

    private static final ConcurrentHashMap<UUID, Boolean> LAST_NEAR_VILLAGERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_VILLAGER_NOISE_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_VILLAGER_NEGOTIATE_MS = new ConcurrentHashMap<>();

    private static final class WeightedLine {
        final String id;
        final String text;
        final net.minecraft.sound.SoundEvent sound;
        final int weight;

        WeightedLine(String id, String text, net.minecraft.sound.SoundEvent sound, int weight) {
            this.id = id;
            this.text = text;
            this.sound = sound;
            this.weight = Math.max(1, weight);
        }
    }

    private static final WeightedLine[] VILLAGER_NOISE_LINES = new WeightedLine[] {
            new WeightedLine("villager_all_you_say", "Is that all you can say?", BotDialogueSounds.LINE_VILLAGER_ALL_YOU_SAY, WEIGHT_COMMON),
            new WeightedLine("villager_rude", "That was rude.", BotDialogueSounds.LINE_VILLAGER_RUDE, WEIGHT_COMMON),
            new WeightedLine("villager_few_words", "A man of few words.", BotDialogueSounds.LINE_VILLAGER_FEW_WORDS, WEIGHT_COMMON),
            new WeightedLine("villager_foreign_tongue", "The local tongue is so foreign to me.", BotDialogueSounds.LINE_VILLAGER_FOREIGN_TONGUE, WEIGHT_COMMON),
            new WeightedLine("villager_no_idea", "I have no idea what that guy just said.", BotDialogueSounds.LINE_VILLAGER_NO_IDEA, WEIGHT_COMMON),
            new WeightedLine("villager_cant_tell_apart", "I can't tell them apart.", BotDialogueSounds.LINE_VILLAGER_CANT_TELL_APART, WEIGHT_COMMON),
            new WeightedLine("villager_committed_one_line", "He's really committed to that one line.", BotDialogueSounds.LINE_VILLAGER_COMMITTED_ONE_LINE, WEIGHT_UNCOMMON),
            new WeightedLine("villager_nod_understand", "We should nod like we understand.", BotDialogueSounds.LINE_VILLAGER_NOD_UNDERSTAND, WEIGHT_UNCOMMON)
    };

    private static final WeightedLine[] VILLAGER_NEGOTIATE_LINES = new WeightedLine[] {
            new WeightedLine("villager_negotiate", "Time to negotiate with the locals.", BotDialogueSounds.LINE_VILLAGER_NEGOTIATE, WEIGHT_COMMON)
    };

    private VillageProximityReactionService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTicks() % 20 != 0) {
            return;
        }

        Set<UUID> live = new java.util.HashSet<>();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }

            UUID botId = bot.getUuid();
            live.add(botId);

            boolean nearVillagers = hasNearbyVillagers(world, bot.getBlockPos());
            boolean wasNearVillagers = LAST_NEAR_VILLAGERS.getOrDefault(botId, false);
            LAST_NEAR_VILLAGERS.put(botId, nearVillagers);

            if (nearVillagers) {
                long now = System.currentTimeMillis();
                long last = LAST_VILLAGER_NOISE_MS.getOrDefault(botId, 0L);
                boolean cooldownReady = now - last >= VILLAGER_NOISE_COOLDOWN_MS;
                boolean edgeTrigger = !wasNearVillagers;
                boolean randomEligible = RNG.nextDouble() < 0.15D;
                if (cooldownReady && (edgeTrigger || randomEligible)) {
                    playVillagerNoise(bot, null);
                }
            }
        }

        LAST_NEAR_VILLAGERS.keySet().retainAll(live);
        LAST_VILLAGER_NOISE_MS.keySet().retainAll(live);
        LAST_VILLAGER_NEGOTIATE_MS.keySet().retainAll(live);
    }

    /**
     * Triggered when a player interacts with a villager.
     */
    public static void onPlayerInteractedVillager(ServerPlayerEntity player, VillagerEntity villager) {
        if (player == null || villager == null) {
            return;
        }
        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null || !(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        List<ServerPlayerEntity> candidates = new ArrayList<>();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            if (bot.getEntityWorld() != world) {
                continue;
            }
            if (bot.squaredDistanceTo(player) > (32.0D * 32.0D)) {
                continue;
            }
            candidates.add(bot);
        }

        if (candidates.isEmpty()) {
            return;
        }

        ServerPlayerEntity target = candidates.stream()
                .min(java.util.Comparator.comparingDouble(b -> b.squaredDistanceTo(player)))
                .orElse(null);
        if (target == null) {
            return;
        }
        playVillagerNegotiate(target, null);
    }

    public static boolean debugTrigger(ServerPlayerEntity bot, String triggerKey, String lineId) {
        if (bot == null || triggerKey == null) {
            return false;
        }
        String key = triggerKey.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "villager_noise_nearby", "villager" -> playVillagerNoise(bot, lineId);
            case "player_opens_villager_trade", "villager_negotiate" -> playVillagerNegotiate(bot, lineId);
            default -> false;
        };
    }

    private static boolean hasNearbyVillagers(ServerWorld world, BlockPos anchor) {
        if (world == null || anchor == null) {
            return false;
        }
        Box box = new Box(anchor).expand(RADIUS_XZ, RADIUS_Y, RADIUS_XZ);
        return !world.getEntitiesByClass(VillagerEntity.class, box, v -> v != null && v.isAlive()).isEmpty();
    }

    private static boolean playVillagerNoise(ServerPlayerEntity bot, String forcedLineId) {
        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_VILLAGER_NOISE_MS.getOrDefault(id, 0L);
        if (forcedLineId == null && now - last < VILLAGER_NOISE_COOLDOWN_MS) {
            return false;
        }
        WeightedLine line = pickWeightedLine(VILLAGER_NOISE_LINES, forcedLineId);
        if (line == null) {
            return false;
        }
        LAST_VILLAGER_NOISE_MS.put(id, now);
        return speak(bot, line);
    }

    private static boolean playVillagerNegotiate(ServerPlayerEntity bot, String forcedLineId) {
        UUID id = bot.getUuid();
        long now = System.currentTimeMillis();
        long last = LAST_VILLAGER_NEGOTIATE_MS.getOrDefault(id, 0L);
        if (forcedLineId == null && now - last < VILLAGER_NEGOTIATE_COOLDOWN_MS) {
            return false;
        }
        WeightedLine line = pickWeightedLine(VILLAGER_NEGOTIATE_LINES, forcedLineId);
        if (line == null) {
            return false;
        }
        LAST_VILLAGER_NEGOTIATE_MS.put(id, now);
        return speak(bot, line);
    }

    private static boolean speak(ServerPlayerEntity bot, WeightedLine line) {
        CompanionOverheadDialogueService.showOverheadLine(bot, line.text, 3_000, 48.0, "villager", line.id);
        BotDialoguePlayer.PlayResult result = BotDialoguePlayer.playSoundForBotDetailed(bot, line.sound);
        if (result == BotDialoguePlayer.PlayResult.PLAYED || result == BotDialoguePlayer.PlayResult.THROTTLED) {
            return true;
        }
        ChatUtils.sendChatMessages(
                bot.getCommandSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                line.text,
                true
        );
        return true;
    }

    private static WeightedLine pickWeightedLine(WeightedLine[] pool, String forcedLineId) {
        if (pool == null || pool.length == 0) {
            return null;
        }
        if (forcedLineId != null && !forcedLineId.isBlank()) {
            for (WeightedLine line : pool) {
                if (line != null && forcedLineId.equalsIgnoreCase(line.id)) {
                    return line;
                }
            }
            return null;
        }
        int total = 0;
        for (WeightedLine line : pool) {
            if (line != null) {
                total += line.weight;
            }
        }
        if (total <= 0) {
            return null;
        }
        int r = RNG.nextInt(total);
        int run = 0;
        for (WeightedLine line : pool) {
            if (line == null) {
                continue;
            }
            run += line.weight;
            if (r < run) {
                return line;
            }
        }
        return pool[pool.length - 1];
    }
}
