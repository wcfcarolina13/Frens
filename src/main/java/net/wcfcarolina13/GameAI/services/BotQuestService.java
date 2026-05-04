package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ChatUtils.BotMoodManager;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.quest.QuestDefinition;
import net.wcfcarolina13.GameAI.quest.QuestPackLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal, seed-agnostic quest runtime.
 *
 * <p>Design goals:
 * <ul>
 *   <li>1–3 actions per quest</li>
 *   <li>2–5 minute sessions</li>
 *   <li>Predicate-based constraints (no fixed coords/biomes/structures)</li>
 *   <li>Non-power rewards (dialogue + memory tags only)</li>
 * </ul>
 */
public final class BotQuestService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-quest");

    private static final long PROPOSE_MIN_DELAY_TICKS = 20L * 60L * 2L; // 2 minutes
    private static final long PROPOSE_MAX_DELAY_TICKS = 20L * 60L * 5L; // 5 minutes

    private static final long DEFAULT_QUEST_TIMEOUT_TICKS = 20L * 60L * 4L; // ~4 minutes

    private static final int MAX_VISITED_CHUNKS_PER_PLAYER = 512;

    private static final Map<UUID, ActiveQuestRuntime> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_PROPOSE_TICK = new ConcurrentHashMap<>();
    private static final Set<String> DISABLED_QUEST_IDS = Set.of(
            // Disabled for current questing overhaul pass: ambient/exploration/curiosity templates.
            "bonding_stay_with_me",
            "bonding_stay_close_under_cover",
            "explore_somewhere_new",
            "curiosity_step_into_the_light",
            "exploration_find_something_built",
            "curiosity_take_a_look_around_here",
            "curiosity_check_the_lighting_here",
            // Disabled: ambient combat quests need full rework.
            "combat_clear_the_nearby_threat"
    );

    // Cached environment scan for structure-like clusters (cheap + avoids repeated block scans).
    private static final long STRUCTURE_CACHE_TTL_TICKS = 20L * 30L; // 30 seconds
    private static final Map<String, StructureClusterCacheEntry> STRUCTURE_CLUSTER_CACHE = new ConcurrentHashMap<>();

    // Session-only: tracks chunks a real player has visited recently (seed-agnostic exploration hook).
    private static final Map<UUID, LinkedHashSet<Long>> PLAYER_VISITED_CHUNKS = new ConcurrentHashMap<>();

    private BotQuestService() {
    }

    public static void resetSession() {
        ACTIVE.clear();
        NEXT_PROPOSE_TICK.clear();
        PLAYER_VISITED_CHUNKS.clear();
        QuestPackLoader.resetCache();
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

        // Update visited chunks (cheap and seed-agnostic).
        tickVisitedChunks(server);

        if (nowTick % 200L == 0L) {
            pruneStructureClusterCache(nowTick);
        }

        // Run the quest runtime.
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (!(bot instanceof createFakePlayer)) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            if (world.getRegistryKey() != World.OVERWORLD) {
                continue;
            }

            // Don't compete with explicit skills/tasks.
            if (TaskService.hasActiveTask(bot.getUuid())) {
                continue;
            }

            // Active quest update
            ActiveQuestRuntime active = ACTIVE.get(bot.getUuid());
            if (active != null) {
                tickActiveQuest(server, bot, world, active, nowTick);
                continue;
            }

            // Passive proposing is a questing-mode feature. In admin mode the
            // bot should not spontaneously pop into sidequests (e.g. "secure
            // this place" triggered by a nearby hostile). The explicit
            // tryHandleQuestPrompt chat hook is unaffected.
            if (!SurvivalRecruitmentService.isEnabled(server)) {
                continue;
            }

            // Passive proposing (low frequency)
            Long next = NEXT_PROPOSE_TICK.get(bot.getUuid());
            if (next == null) {
                // First-seen bot: avoid immediately proposing on join/world load.
                scheduleNextProposal(bot.getUuid(), nowTick);
                continue;
            }
            if (nowTick < next) {
                continue;
            }

            // Only propose when a real player is nearby or being followed.
            ServerPlayerEntity commander = resolveCommander(server, bot, world);
            if (commander == null) {
                // No commander: try again later.
                scheduleNextProposal(bot.getUuid(), nowTick);
                continue;
            }

            // Avoid spam if the commander is far; treat this as a "presence" gating.
            if (commander.squaredDistanceTo(bot) > (10.0D * 10.0D)) {
                scheduleNextProposal(bot.getUuid(), nowTick);
                continue;
            }

            proposeAndStartQuest(bot, commander, world, nowTick, false);
        }
    }

    /**
     * Chat hook: player explicitly asks for a quest.
     */
    public static boolean tryHandleQuestPrompt(ServerPlayerEntity bot, ServerPlayerEntity player, String prompt) {
        if (bot == null || bot.isRemoved() || player == null || player.isRemoved()) {
            return false;
        }
        if (BotEventHandler.isRegisteredBot(player)) {
            return false;
        }
        if (prompt == null) {
            return false;
        }

        String normalized = prompt.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }

        // Keep conservative: only intercept short, obviously quest-related prompts.
        boolean looksLikeQuestAsk = normalized.equals("quest")
                || normalized.equals("sidequest")
                || normalized.equals("side quest")
                || normalized.equals("mission")
                || normalized.startsWith("quest ")
                || normalized.startsWith("sidequest ")
                || normalized.startsWith("side quest ")
                || normalized.startsWith("got a quest")
                || normalized.startsWith("any quests")
                || normalized.startsWith("give me a quest")
                || normalized.startsWith("give us a quest");

        if (!looksLikeQuestAsk) {
            return false;
        }

        // If we already have a quest, report it.
        ActiveQuestRuntime active = ACTIVE.get(bot.getUuid());
        if (active != null) {
            say(bot, "Current side quest: " + active.quest.intent);
            return true;
        }

        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }

        if (TaskService.hasActiveTask(bot.getUuid())) {
            say(bot, "I'm busy with another task right now.");
            return true;
        }

        proposeAndStartQuest(bot, player, world, bot.getCommandSource().getServer().getTicks(), true);
        return true;
    }

    private static void tickVisitedChunks(MinecraftServer server) {
        if (server == null) {
            return;
        }
        // Update every tick; bounded by player count and tiny work.
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null || p.isRemoved()) {
                continue;
            }
            if (BotEventHandler.isRegisteredBot(p)) {
                continue;
            }
            ChunkPos cp = p.getChunkPos();
            long key = cp.toLong();
            LinkedHashSet<Long> set = PLAYER_VISITED_CHUNKS.computeIfAbsent(p.getUuid(), ignored -> new LinkedHashSet<>());
            if (!set.contains(key)) {
                set.add(key);
                // Bound size
                while (set.size() > MAX_VISITED_CHUNKS_PER_PLAYER) {
                    Long first = set.iterator().next();
                    set.remove(first);
                }
            }
        }

        // Cleanup stale player entries occasionally.
        if (server.getTicks() % 200 == 0) {
            Set<UUID> alive = new HashSet<>();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p != null && !p.isRemoved() && !BotEventHandler.isRegisteredBot(p)) {
                    alive.add(p.getUuid());
                }
            }
            PLAYER_VISITED_CHUNKS.keySet().retainAll(alive);
        }
    }

    private static void tickActiveQuest(MinecraftServer server, ServerPlayerEntity bot, ServerWorld world, ActiveQuestRuntime active, long nowTick) {
        if (server == null || bot == null || world == null || active == null) {
            return;
        }

        // Timeout
        if (nowTick >= active.expiresTick) {
            completeQuest(bot, active, false);
            ACTIVE.remove(bot.getUuid(), active);
            scheduleNextProposal(bot.getUuid(), nowTick);
            return;
        }

        ServerPlayerEntity commander = resolveCommander(server, bot, world);

        // If no commander is around, the quest doesn't progress, but also shouldn't instantly fail.
        // Keep ticking until timeout.
        if (commander == null) {
            return;
        }

        if (active.quest.actions == null || active.quest.actions.isEmpty()) {
            completeQuest(bot, active, false);
            ACTIVE.remove(bot.getUuid(), active);
            scheduleNextProposal(bot.getUuid(), nowTick);
            return;
        }

        if (active.actionIndex < 0 || active.actionIndex >= active.quest.actions.size()) {
            completeQuest(bot, active, true);
            ACTIVE.remove(bot.getUuid(), active);
            scheduleNextProposal(bot.getUuid(), nowTick);
            return;
        }

        QuestDefinition.Action action = active.quest.actions.get(active.actionIndex);
        boolean stepDone = evalActionStep(bot, commander, world, active, action);
        if (stepDone) {
            active.actionIndex++;
            active.stepTicks = 0L;
            active.sawHostile = false;
            // If we finished all actions, complete quest.
            if (active.actionIndex >= active.quest.actions.size()) {
                completeQuest(bot, active, true);
                ACTIVE.remove(bot.getUuid(), active);
                scheduleNextProposal(bot.getUuid(), nowTick);
            }
        }
    }

    private static boolean evalActionStep(ServerPlayerEntity bot,
                                         ServerPlayerEntity commander,
                                         ServerWorld world,
                                         ActiveQuestRuntime active,
                                         QuestDefinition.Action action) {
        if (action == null) {
            return false;
        }

        String type = action.typeOrEmpty().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> p = action.params();

        return switch (type) {
            case "wait" -> evalWait(bot, commander, active, p);
            case "move" -> evalMove(bot, commander, world, active, p);
            case "observe" -> evalObserve(bot, commander, world, active, p);
            case "fight" -> evalFight(bot, world, active, p);
            case "collect" -> evalCollect(bot, commander, active, p);
            default -> false;
        };
    }

    private static boolean evalCollect(ServerPlayerEntity bot,
                                       ServerPlayerEntity commander,
                                       ActiveQuestRuntime active,
                                       Map<String, Object> params) {
        if (bot == null || commander == null || params == null) {
            return false;
        }

        String itemIdStr = String.valueOf(params.getOrDefault("item_id", "")).trim();
        if (itemIdStr.isEmpty()) {
            return false;
        }

        Identifier itemId;
        try {
            itemId = Identifier.of(itemIdStr);
        } catch (Exception ignored) {
            return false;
        }
        if (!Registries.ITEM.containsId(itemId)) {
            return false;
        }
        Item item = Registries.ITEM.get(itemId);

        int need = (int) Math.ceil(asDouble(params.getOrDefault("count", 1), 1));
        need = Math.max(1, need);

        String who = String.valueOf(params.getOrDefault("who", "player")).trim().toLowerCase(Locale.ROOT);
        ServerPlayerEntity target = who.equals("bot") ? bot : commander;

        int have = countItemInInventory(target, item);
        return have >= need;
    }

    private static boolean evalWait(ServerPlayerEntity bot,
                                   ServerPlayerEntity commander,
                                   ActiveQuestRuntime active,
                                   Map<String, Object> params) {
        double within = asDouble(params.getOrDefault("player_within_blocks", 6.0D), 6.0D);
        long durationTicks = secondsToTicks(asDouble(params.getOrDefault("duration_seconds", 30.0D), 30.0D));

        boolean close = commander.squaredDistanceTo(bot) <= (within * within);
        if (close) {
            active.stepTicks++;
        } else {
            active.stepTicks = 0L;
        }
        return active.stepTicks >= durationTicks;
    }

    private static boolean evalMove(ServerPlayerEntity bot,
                                   ServerPlayerEntity commander,
                                   ServerWorld world,
                                   ActiveQuestRuntime active,
                                   Map<String, Object> params) {
        String predicate = String.valueOf(params.getOrDefault("predicate", "")).trim();
        if (predicate.isEmpty()) {
            return false;
        }

        // For player_unvisited_chunk, we want the COMMANDER to move to a chunk that wasn't in the
        // visited set at quest start (recent-history based).
        if (predicate.equalsIgnoreCase("player_unvisited_chunk")) {
            if (active.playerVisitedSnapshot == null) {
                active.playerVisitedSnapshot = snapshotVisitedChunks(commander.getUuid());
            }
            long key = commander.getChunkPos().toLong();
            boolean isNewToSnapshot = !active.playerVisitedSnapshot.contains(key);
            return isNewToSnapshot;
        }

        // Other predicates are evaluated against the bot's current environment.
        return evalPredicate(predicate, bot, commander, world);
    }

    private static boolean evalObserve(ServerPlayerEntity bot,
                                      ServerPlayerEntity commander,
                                      ServerWorld world,
                                      ActiveQuestRuntime active,
                                      Map<String, Object> params) {
        String predicate = String.valueOf(params.getOrDefault("predicate", "")).trim();
        long durationTicks = secondsToTicks(asDouble(params.getOrDefault("duration_seconds", 2.0D), 2.0D));
        if (predicate.isEmpty()) {
            return false;
        }

        boolean ok = evalPredicate(predicate, bot, commander, world);
        if (ok) {
            active.stepTicks++;
        } else {
            active.stepTicks = 0L;
        }
        return active.stepTicks >= durationTicks;
    }

    private static boolean evalFight(ServerPlayerEntity bot,
                                    ServerWorld world,
                                    ActiveQuestRuntime active,
                                    Map<String, Object> params) {
        double radius = asDouble(params.getOrDefault("radius_blocks", 12.0D), 12.0D);
        int hostiles = BotSenseService.countHostileEntities(bot, radius);
        if (hostiles > 0) {
            active.sawHostile = true;
            return false;
        }
        // Require that there was something to "fight" first.
        return active.sawHostile;
    }

    private static boolean evalPredicate(String rawPredicate,
                                        ServerPlayerEntity bot,
                                        ServerPlayerEntity commander,
                                        ServerWorld world) {
        if (rawPredicate == null) {
            return false;
        }
        String pred = rawPredicate.trim();
        if (pred.isEmpty()) {
            return false;
        }

        // --- State predicates ---
        if (pred.equalsIgnoreCase("time_night")) {
            long tod = Math.floorMod(world.getTimeOfDay(), 24_000L);
            return tod >= 13_000L && tod <= 23_000L;
        }
        if (pred.equalsIgnoreCase("player_low_health")) {
            if (commander == null) {
                return false;
            }
            float hp = commander.getHealth();
            float max = commander.getMaxHealth();
            return hp <= Math.max(6.0f, max * 0.35f);
        }
        if (pred.equalsIgnoreCase("bot_recently_damaged")) {
            return BotMoodManager.wasRecentlyInCombat(bot);
        }

        // distance_from_spawn > X (supports > and >=)
        if (pred.toLowerCase(Locale.ROOT).startsWith("distance_from_spawn")) {
            Double threshold = parseComparatorThreshold(pred);
            if (threshold == null) {
                return false;
            }
            // Resolve actual spawn in a mapping-safe way (SpawnPoint wrapper types differ across versions/mappings).
            BlockPos spawn = resolveSpawnPoint(world);
            double sx = spawn.getX() + 0.5D;
            double sz = spawn.getZ() + 0.5D;
            double dx = bot.getX() - sx;
            double dz = bot.getZ() - sz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            boolean ge = pred.contains(">=");
            return ge ? dist >= threshold : dist > threshold;
        }

        // --- Environment predicates ---
        if (pred.equalsIgnoreCase("low_light")) {
            int blockLight;
            try {
                blockLight = world.getLightLevel(LightType.BLOCK, bot.getBlockPos());
            } catch (Exception ignored) {
                blockLight = 15;
            }
            return blockLight <= 4;
        }
        if (pred.equalsIgnoreCase("structure_like_block_cluster")) {
            long nowTick = 0L;
            try {
                MinecraftServer server = world.getServer();
                if (server != null) {
                    nowTick = server.getTicks();
                }
            } catch (Exception ignored) {
            }
            return evalStructureLikeBlockCluster(bot, world, nowTick);
        }
        if (pred.equalsIgnoreCase("enclosed_space")) {
            BotStuckService.EnvironmentSnapshot env = BotStuckService.analyzeEnvironment(bot);
            return env.enclosed();
        }
        if (pred.equalsIgnoreCase("open_area")) {
            BotStuckService.EnvironmentSnapshot env = BotStuckService.analyzeEnvironment(bot);
            return !env.enclosed() && env.hasHeadroom() && env.hasEscapeRoute();
        }
        if (pred.equalsIgnoreCase("high_elevation")) {
            int sea = world.getSeaLevel();
            return bot.getBlockPos().getY() >= sea + 40;
        }

        // --- Entity predicates ---
        if (pred.equalsIgnoreCase("any_hostile_mob")) {
            return BotSenseService.countHostileEntities(bot, 12.0D) > 0;
        }
        if (pred.equalsIgnoreCase("multiple_mobs")) {
            return BotSenseService.countHostileEntities(bot, 12.0D) >= 2;
        }
        if (pred.equalsIgnoreCase("mob_dense")) {
            return BotSenseService.countHostileEntities(bot, 12.0D) >= 3;
        }
        if (pred.equalsIgnoreCase("non_hostile_entity")) {
            return BotSenseService.countNonHostileLivingEntities(bot, 12.0D) > 0;
        }

        return false;
    }

    private static boolean evalStructureLikeBlockCluster(ServerPlayerEntity bot, ServerWorld world, long nowTick) {
        if (bot == null || world == null) {
            return false;
        }
        BlockPos center = bot.getBlockPos();
        ChunkPos cp = new ChunkPos(center);
        String cacheKey = world.getRegistryKey().getValue() + ":" + cp.x + ":" + cp.z;

        StructureClusterCacheEntry cached = STRUCTURE_CLUSTER_CACHE.get(cacheKey);
        if (cached != null && (nowTick - cached.tick) <= STRUCTURE_CACHE_TTL_TICKS) {
            return cached.value;
        }

        boolean value = computeStructureLikeBlockCluster(world, center);
        STRUCTURE_CLUSTER_CACHE.put(cacheKey, new StructureClusterCacheEntry(nowTick, value));
        return value;
    }

    private static boolean computeStructureLikeBlockCluster(ServerWorld world, BlockPos center) {
        if (world == null || center == null) {
            return false;
        }

        // Keep this intentionally cheap and seed-agnostic:
        // scan a small local volume and look for a *cluster* of human-ish blocks.
        final int r = 10;
        final int down = 3;
        final int up = 4;

        BlockPos min = center.add(-r, -down, -r);
        BlockPos max = center.add(r, up, r);

        int built = 0;
        int strong = 0;
        int logs = 0;
        int blockEntities = 0;
        int beds = 0;
        int lights = 0;

        for (BlockPos pos : BlockPos.iterate(min, max)) {
            BlockState state;
            try {
                state = world.getBlockState(pos);
            } catch (Exception ignored) {
                continue;
            }
            if (state == null || state.isAir()) {
                continue;
            }

            // Skip the most common natural noise.
            if (state.isIn(BlockTags.LEAVES)) {
                continue;
            }

            boolean isBuilt = false;
            boolean isStrong = false;

            if (state.isIn(BlockTags.PLANKS)
                    || state.isIn(BlockTags.WOOL)
                    || state.isIn(BlockTags.WOOL_CARPETS)
                    || state.isIn(BlockTags.FENCES)
                    || state.isIn(BlockTags.WALLS)
                    || state.isIn(BlockTags.FENCE_GATES)
                    || state.isIn(BlockTags.WOODEN_DOORS)
                    || state.isIn(BlockTags.WOODEN_PRESSURE_PLATES)) {
                isBuilt = true;
            }

            if (state.isIn(BlockTags.LOGS)) {
                logs++;
                // Logs can be natural, so treat as weak evidence.
                isBuilt = true;
            }

            if (state.isIn(BlockTags.BEDS)) {
                beds++;
                isBuilt = true;
                isStrong = true;
                strong += 2;
            }

            if (state.isIn(BlockTags.SHULKER_BOXES)) {
                isBuilt = true;
                isStrong = true;
                strong += 2;
            }

            // Strong "this was built" indicators.
            if (state.hasBlockEntity()) {
                blockEntities++;
                isBuilt = true;
                isStrong = true;
                strong += 2;
            }

            if (isLightLike(state)) {
                lights++;
                isBuilt = true;
                isStrong = true;
                strong += 1;
            }

            if (isWorkBlock(state)) {
                isBuilt = true;
                isStrong = true;
                strong += 2;
            }

            if (isBuilt) {
                built++;
            }

            // Early exits.
            if (built >= 28 && strong >= 6) {
                return true;
            }
            if (blockEntities >= 2 && built >= 18) {
                return true;
            }
            if (beds >= 1 && built >= 14) {
                return true;
            }
            if (strong >= 12) {
                return true;
            }
        }

        // Avoid forests triggering just because of lots of logs.
        if (built > 0) {
            double logRatio = (double) logs / (double) built;
            if (logRatio > 0.80 && strong < 6) {
                return false;
            }
        }

        // Final fallback threshold.
        return built >= 32 && strong >= 6 && (blockEntities + lights + beds) >= 2;
    }

    private static boolean isWorkBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        // A small set of common "human activity" blocks.
        return state.isOf(Blocks.CRAFTING_TABLE)
                || state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER)
                || state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.BARREL)
                || state.isOf(Blocks.ANVIL)
                || state.isOf(Blocks.ENCHANTING_TABLE)
                || state.isOf(Blocks.ENDER_CHEST);
    }

    private static boolean isLightLike(BlockState state) {
        if (state == null) {
            return false;
        }
        // Conservative: a handful of common player-placed light sources.
        return state.isOf(Blocks.TORCH)
                || state.isOf(Blocks.WALL_TORCH)
                || state.isOf(Blocks.SOUL_TORCH)
                || state.isOf(Blocks.SOUL_WALL_TORCH)
                || state.isOf(Blocks.REDSTONE_TORCH)
                || state.isOf(Blocks.REDSTONE_WALL_TORCH)
                || state.isOf(Blocks.LANTERN)
                || state.isOf(Blocks.SOUL_LANTERN)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE);
    }

    private static void pruneStructureClusterCache(long nowTick) {
        if (STRUCTURE_CLUSTER_CACHE.isEmpty()) {
            return;
        }
        // Remove old entries; keep the map bounded in long-running servers.
        STRUCTURE_CLUSTER_CACHE.entrySet().removeIf(e -> (nowTick - e.getValue().tick) > (STRUCTURE_CACHE_TTL_TICKS * 4L));
    }

    private record StructureClusterCacheEntry(long tick, boolean value) {
    }

    private static Double parseComparatorThreshold(String predicate) {
        // Accept forms like:
        // - distance_from_spawn > 128
        // - distance_from_spawn >= 64
        if (predicate == null) {
            return null;
        }
        String p = predicate.replaceAll("\\s+", " ").trim();
        String[] parts;
        boolean ge = p.contains(">=");
        if (ge) {
            parts = p.split(">=");
        } else if (p.contains(">")) {
            parts = p.split(">");
        } else {
            return null;
        }
        if (parts.length != 2) {
            return null;
        }
        String rhs = parts[1].trim();
        try {
            return Double.parseDouble(rhs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long secondsToTicks(double seconds) {
        double s = Math.max(0.0D, seconds);
        return (long) Math.ceil(s * 20.0D);
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static void proposeAndStartQuest(ServerPlayerEntity bot,
                                            ServerPlayerEntity commander,
                                            ServerWorld world,
                                            long nowTick,
                                            boolean forced) {
        List<QuestDefinition> candidates = pickEligibleQuests(bot, commander, world);
        if (candidates.isEmpty()) {
            if (forced) {
                say(bot, "I don't have a good side quest idea right now.");
            }
            scheduleNextProposal(bot.getUuid(), nowTick);
            return;
        }

        QuestDefinition chosen = candidates.get((int) (Math.random() * candidates.size()));

        ActiveQuestRuntime active = new ActiveQuestRuntime(chosen, nowTick, nowTick + DEFAULT_QUEST_TIMEOUT_TICKS);
        ACTIVE.put(bot.getUuid(), active);

        // Persist minimal memory (completed IDs handled on completion).
        say(bot, "Side quest: " + chosen.intent);
        say(bot, "(1–3 steps, quick.)");

        scheduleNextProposal(bot.getUuid(), nowTick);
    }

    private static List<QuestDefinition> pickEligibleQuests(ServerPlayerEntity bot,
                                                           ServerPlayerEntity commander,
                                                           ServerWorld world) {
        List<QuestDefinition> all = QuestPackLoader.getDefaultPack();
        if (all.isEmpty()) {
            return List.of();
        }

        String botName = bot.getName().getString();
        ManualConfig.BotQuestMemory mem = getQuestMemory(botName);
        Set<String> completed = mem != null ? new HashSet<>(mem.getCompletedQuestIds()) : Set.of();

        List<QuestDefinition> eligible = new ArrayList<>();
        for (QuestDefinition q : all) {
            if (q == null || !q.isValid()) {
                continue;
            }
            if (DISABLED_QUEST_IDS.contains(q.idOrEmpty())) {
                continue;
            }
            // Avoid repeating the exact same quest too frequently.
            if (completed.contains(q.idOrEmpty())) {
                continue;
            }
            if (!constraintsMatch(q, bot, commander, world)) {
                continue;
            }
            eligible.add(q);
        }

        // If everything is "completed", allow repeats (seed-agnostic templates are meant to recur).
        if (eligible.isEmpty()) {
            for (QuestDefinition q : all) {
                if (q != null
                        && q.isValid()
                        && !DISABLED_QUEST_IDS.contains(q.idOrEmpty())
                        && constraintsMatch(q, bot, commander, world)) {
                    eligible.add(q);
                }
            }
        }

        return eligible;
    }

    private static boolean constraintsMatch(QuestDefinition q,
                                           ServerPlayerEntity bot,
                                           ServerPlayerEntity commander,
                                           ServerWorld world) {
        if (q == null) {
            return false;
        }
        QuestDefinition.Constraints c = q.constraints;
        if (c == null) {
            return true;
        }
        for (String p : c.env()) {
            if (!evalPredicate(p, bot, commander, world)) {
                return false;
            }
        }
        for (String p : c.entities()) {
            if (!evalPredicate(p, bot, commander, world)) {
                return false;
            }
        }
        for (String p : c.state()) {
            if (!evalPredicate(p, bot, commander, world)) {
                return false;
            }
        }
        return true;
    }

    private static void completeQuest(ServerPlayerEntity bot, ActiveQuestRuntime active, boolean success) {
        if (bot == null || active == null || active.quest == null) {
            return;
        }

        String line = success ? active.quest.bot_reflection.successOrEmpty() : active.quest.bot_reflection.failureOrEmpty();
        if (line == null || line.isBlank()) {
            line = success ? "Done." : "Not this time.";
        }
        say(bot, line);

        // Persist completion in config (non-power reward: memory/continuity).
        try {
            String botName = bot.getName().getString();
            ManualConfig.BotQuestMemory mem = getQuestMemory(botName);
            if (mem != null) {
                mem.recordCompletion(active.quest.idOrEmpty(), success);
                Frens.CONFIG.save();
            }
        } catch (Exception e) {
            LOGGER.debug("Quest memory save failed: {}", e.getMessage());
        }
    }

    private static ManualConfig.BotQuestMemory getQuestMemory(String botName) {
        if (botName == null || botName.isBlank()) {
            return null;
        }
        try {
            return Frens.CONFIG.getOrCreateBotQuestMemory(botName);
        } catch (Exception e) {
            LOGGER.debug("Quest memory unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static Set<Long> snapshotVisitedChunks(UUID playerUuid) {
        if (playerUuid == null) {
            return Set.of();
        }
        LinkedHashSet<Long> set = PLAYER_VISITED_CHUNKS.get(playerUuid);
        if (set == null || set.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(set);
    }

    private static ServerPlayerEntity resolveCommander(MinecraftServer server, ServerPlayerEntity bot, ServerWorld world) {
        if (server == null || bot == null || world == null) {
            return null;
        }

        // Prefer follow target when applicable.
        if (BotEventHandler.isFollowingPlayer(bot)) {
            UUID targetUuid = BotEventHandler.getFollowTargetUuid(bot);
            if (targetUuid != null) {
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
                if (target != null && !target.isRemoved() && !BotEventHandler.isRegisteredBot(target)) {
                    return target;
                }
            }
        }

        // Otherwise, nearest real player in world.
        double bestDistSq = Double.POSITIVE_INFINITY;
        ServerPlayerEntity best = null;
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p == null || p.isRemoved()) {
                continue;
            }
            if (p == bot) {
                continue;
            }
            if (BotEventHandler.isRegisteredBot(p)) {
                continue;
            }
            double d = p.squaredDistanceTo(bot);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = p;
            }
        }
        return best;
    }

    private static void scheduleNextProposal(UUID botUuid, long nowTick) {
        long delay = PROPOSE_MIN_DELAY_TICKS + (long) (Math.random() * (PROPOSE_MAX_DELAY_TICKS - PROPOSE_MIN_DELAY_TICKS));
        NEXT_PROPOSE_TICK.put(botUuid, nowTick + delay);
    }

    private static void say(ServerPlayerEntity bot, String msg) {
        if (bot == null || msg == null || msg.isBlank()) {
            return;
        }
        ServerCommandSource source = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);
        ChatUtils.sendChatMessages(source, msg, true);
    }

    private static int countItemInInventory(ServerPlayerEntity player, Item item) {
        if (player == null || item == null) {
            return 0;
        }
        int total = 0;
        try {
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (!stack.isEmpty() && stack.isOf(item)) {
                    total += stack.getCount();
                }
            }
        } catch (Exception ignored) {
            return 0;
        }
        return total;
    }

    private static BlockPos resolveSpawnPoint(ServerWorld world) {
        if (world == null) {
            return BlockPos.ORIGIN;
        }
        try {
            net.minecraft.world.WorldProperties.SpawnPoint sp = world.getSpawnPoint();
            if (sp != null) {
                BlockPos pos = sp.getPos();
                if (pos != null) {
                    return pos;
                }
            }
        } catch (Throwable ignored) {
        }
        return BlockPos.ORIGIN;
    }

    private static final class ActiveQuestRuntime {
        final QuestDefinition quest;
        final long expiresTick;

        int actionIndex = 0;
        long stepTicks = 0L;
        boolean sawHostile = false;

        Set<Long> playerVisitedSnapshot;

        ActiveQuestRuntime(QuestDefinition quest, long startedTick, long expiresTick) {
            this.quest = quest;
            this.expiresTick = expiresTick;
        }
    }

    /**
     * Very small, cheap sensing helpers used for predicates.
     */
    private static final class BotSenseService {
        private BotSenseService() {
        }

        static int countHostileEntities(ServerPlayerEntity bot, double radius) {
            if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
                return 0;
            }
            try {
                var box = bot.getBoundingBox().expand(radius);
                return world.getOtherEntities(null, box, e -> e instanceof net.minecraft.entity.mob.HostileEntity).size();
            } catch (Exception ignored) {
                return 0;
            }
        }

        static int countNonHostileLivingEntities(ServerPlayerEntity bot, double radius) {
            if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
                return 0;
            }
            try {
                var box = bot.getBoundingBox().expand(radius);
                return world.getOtherEntities(null, box, e -> (e instanceof net.minecraft.entity.LivingEntity) && !(e instanceof net.minecraft.entity.mob.HostileEntity)).size();
            } catch (Exception ignored) {
                return 0;
            }
        }
    }
}
