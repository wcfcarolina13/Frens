package net.wcfcarolina13.GameAI.services;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.skills.impl.HuntSkill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight "ambient hobbies" scheduler.
 *
 * <p>When enabled per-bot, and the bot is idle (not following, not guarding/patrolling, not running a task),
 * it will occasionally start a near-home hobby like fishing or hanging out.
 */
public final class BotIdleHobbiesService {

    private static final Logger LOGGER = LoggerFactory.getLogger("idle-hobbies");

    private static final Random RNG = new Random();

    // Don't start new hobbies too close to sunset, since sunset automation will shortly take over.
    private static final int DONT_START_AFTER_TOD = 11_000;

    private static final Map<UUID, Long> NEXT_DECISION_TICK = new ConcurrentHashMap<>();
    private static final long BLOCKED_REASON_LOG_INTERVAL_TICKS = 20L * 45L;
    private static final Map<UUID, String> LAST_BLOCKED_REASON_KEY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_BLOCKED_REASON_LOG_TICK = new ConcurrentHashMap<>();

    // When a bot first appears in the current server session, delay idle-hobby starts briefly.
    // Why: during join/restore, bots may be at a transient spawn position (or chunks around them
    // may not yet be loaded). Starting a hobby immediately can cause it to pick the wrong context
    // (e.g., hangout starts before the bot is restored near the campfire).
    private static final long JOIN_GRACE_TICKS = 60L; // ~3 seconds
    private static final Map<UUID, Long> FIRST_SEEN_TICK = new ConcurrentHashMap<>();

    private static final Map<UUID, String> LAST_HOBBY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_HOBBY_END_MS = new ConcurrentHashMap<>();

    /**
     * Clears all in-memory scheduler state.
     *
     * <p>Important for integrated-server world reloads: static state can survive across "leave world"
     * and "re-enter world", but {@link MinecraftServer#getTicks()} resets, which can strand bots behind
     * an old, huge {@code NEXT_DECISION_TICK} value.
     */
    public static void resetSession() {
        NEXT_DECISION_TICK.clear();
        LAST_HOBBY.clear();
        LAST_HOBBY_END_MS.clear();
        FIRST_SEEN_TICK.clear();
        LAST_BLOCKED_REASON_KEY.clear();
        LAST_BLOCKED_REASON_LOG_TICK.clear();
    }

    /** Returns the last idle-hobby skill name (e.g. "fish"/"hangout"), or null if unknown. */
    public static String getLastHobbyName(UUID botUuid) {
        if (botUuid == null) {
            return null;
        }
        return LAST_HOBBY.get(botUuid);
    }

    /** Returns the wall-clock time when the last idle hobby ended, or 0 if unknown. */
    public static long getLastHobbyEndMs(UUID botUuid) {
        if (botUuid == null) {
            return 0L;
        }
        return LAST_HOBBY_END_MS.getOrDefault(botUuid, 0L);
    }

    /**
     * Pushes the idle-hobby scheduler forward so it won't immediately start a hobby the moment a bot
     * becomes {@link net.wcfcarolina13.GameAI.BotEventHandler.Mode#IDLE}.
     *
     * <p>Used by commander commands like go-to/come to ensure "do what I said" has priority over
     * ambient hobbies.
     */
    public static void snoozeUntil(UUID botUuid, long nextDecisionTick) {
        if (botUuid == null) {
            return;
        }
        long next = Math.max(0L, nextDecisionTick);
        NEXT_DECISION_TICK.merge(botUuid, next, Math::max);
    }

    /**
     * Convenience wrapper around {@link #snoozeUntil(UUID, long)} using the current server tick.
     */
    public static void snoozeFor(ServerPlayerEntity bot, long delayTicks) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        long nowTick = server.getTicks();
        snoozeUntil(bot.getUuid(), nowTick + Math.max(0L, delayTicks));
    }

    /**
     * Requests the scheduler to consider starting an idle hobby as soon as possible.
     * This does not bypass eligibility checks; it simply clears the per-bot backoff.
     */
    public static void requestDecisionNow(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        long now = server.getTicks();
        NEXT_DECISION_TICK.put(bot.getUuid(), now);
    }

    private static final AtomicInteger AMBIENT_THREAD_ID = new AtomicInteger(0);
    private static final ExecutorService AMBIENT_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ambient-hobby-" + AMBIENT_THREAD_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private BotIdleHobbiesService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        long nowTick = server.getTicks();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            if (!BotHomeService.isIdleHobbiesEnabled(bot)) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            if (world.getRegistryKey() != World.OVERWORLD) {
                noteBlocked(bot, nowTick, "not-overworld");
                continue;
            }
            if (bot.isSleeping()) {
                noteBlocked(bot, nowTick, "sleeping");
                continue;
            }

            // Only start hobbies when truly idle (not following, not guard/patrol, not returning).
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
                noteBlocked(bot, nowTick, "mode-not-idle", String.valueOf(BotEventHandler.getCurrentMode(bot)));
                continue;
            }

            // Extra safety: if follow/base intent is set but mode is momentarily IDLE, don't start a hobby.
            // This prevents hobbies from stealing control while a follow is active.
            if (BotEventHandler.getFollowTargetUuid(bot) != null || BotEventHandler.getBaseTarget(bot) != null) {
                noteBlocked(bot, nowTick, "follow-or-base-intent");
                continue;
            }

            // Never compete with a task.
            if (TaskService.hasActiveTask(bot.getUuid())) {
                noteBlocked(bot, nowTick, "active-task");
                continue;
            }

            int tod = (int) (world.getTimeOfDay() % 24_000L);
            if (tod >= DONT_START_AFTER_TOD) {
                noteBlocked(bot, nowTick, "late-day");
                continue;
            }

            UUID botUuid = bot.getUuid();

            // First-seen grace window: let persistence restoration and chunk ticketing settle.
            Long firstSeen = FIRST_SEEN_TICK.putIfAbsent(botUuid, nowTick);
            if (firstSeen == null) {
                noteBlocked(bot, nowTick, "first-seen-grace");
                continue;
            }
            if (nowTick - firstSeen < JOIN_GRACE_TICKS) {
                noteBlocked(bot, nowTick, "join-grace");
                continue;
            }

            long next = NEXT_DECISION_TICK.getOrDefault(botUuid, 0L);

            // Integrated-server reload safety: if we carried a huge "next" tick value from a prior
            // world instance, the new server tick counter starts near 0 and the bot may never idle.
            // Treat it as stale and request a decision now.
            if (nowTick < 4_000L && next - nowTick > 20_000L) {
                NEXT_DECISION_TICK.put(botUuid, nowTick);
                next = nowTick;
            }

            if (nowTick < next) {
                noteBlocked(bot, nowTick, "backoff", (next - nowTick) + "t");
                continue;
            }

            boolean hobbiesAnywhere = Frens.CONFIG != null && Frens.CONFIG.isIdleHobbiesAnywhereEnabled();
            if (!hobbiesAnywhere) {
                // Default behavior: keep hobbies local to home/base.
                Vec3d home = resolveHomeAnchor(world, bot);
                if (home == null) {
                    NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 200L);
                    noteBlocked(bot, nowTick, "no-home-anchor");
                    continue;
                }
                double distHome = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).distanceTo(home);
                if (distHome > 24.0D) {
                    NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 200L);
                    noteBlocked(bot, nowTick, "too-far-from-home", String.format(Locale.ROOT, "%.1f", distHome));
                    continue;
                }
            }

            String hobby = pickHobby(bot, world);
            if (hobby == null) {
                hobby = pickFallbackHobby(bot, world);
                if (hobby == null) {
                    // No suitable hobby for current inventory/terrain; try again later.
                    NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 400L + RNG.nextInt(400));
                    noteBlocked(bot, nowTick, "no-hobby-candidates");
                    continue;
                }
                LOGGER.debug("Idle hobbies fallback '{}' for {}", hobby, bot.getName().getString());
            }

            // Minimal backoff: the running task will block additional hobby starts via TaskService.
            // We schedule the true “cooldown” when the hobby finishes (success/failure).
            NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 80L + RNG.nextInt(80));
            clearBlockedNote(bot.getUuid());

            LOGGER.info("Starting idle hobby '{}' for {}", hobby, bot.getName().getString());
            LAST_HOBBY.put(bot.getUuid(), hobby.toLowerCase(Locale.ROOT));
            startAmbientSkill(server, bot, hobby);
        }
    }

    private static Vec3d resolveHomeAnchor(ServerWorld world, ServerPlayerEntity bot) {
        return BotHomeService.resolveHomeTarget(bot)
                .map(Vec3d::ofCenter)
                .orElseGet(() -> Vec3d.ofCenter(resolveSpawnPoint(world)));
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

    private static String pickHobby(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }
        boolean hasRod = hasItem(bot, Items.FISHING_ROD);
        boolean hasWaterNearby = hasRod && hasNearbyBlock(world, bot.getBlockPos(), 12, net.minecraft.block.Blocks.WATER);
        // Campfire can be a bit further away; it still "feels" local but avoids being too finicky.
        int campfireRadius = 24;
        boolean hasCampfireNearby = hasNearbyBlock(world, bot.getBlockPos(), campfireRadius, net.minecraft.block.Blocks.CAMPFIRE)
            || hasNearbyBlock(world, bot.getBlockPos(), campfireRadius, net.minecraft.block.Blocks.SOUL_CAMPFIRE);

        boolean huntUnlocked = HuntHistoryService.hasAnyFoodKill(world);
        boolean healthy = bot.getHealth() >= 18.0F && bot.getHungerManager().getFoodLevel() >= 16;
        boolean canHunt = huntUnlocked
                && healthy
                && !world.isThundering()
                && HuntSkill.hasAmbientHuntCandidate(bot, world);
        boolean canFeedAnimals = hasFeedTargets(bot, world);
        boolean canPickFlowers = hasNearbyFlowers(world, bot.getBlockPos(), 18);

        // Build weighted options so available hobbies actually trigger instead of idling on random misses.
        ArrayList<String> weighted = new ArrayList<>();
        if (hasWaterNearby) {
            weighted.add("fish");
            weighted.add("fish");
            weighted.add("fish");
        }
        if (canFeedAnimals) {
            weighted.add("feed_animals");
            weighted.add("feed_animals");
        }
        if (canPickFlowers) {
            weighted.add("flowers");
            weighted.add("flowers");
        }
        if (canHunt) {
            weighted.add("hunt");
        }
        // Hangout is only valid when a campfire is actually nearby.
        if (hasCampfireNearby) {
            weighted.add("hangout");
        }
        if (weighted.isEmpty()) {
            return null;
        }
        return weighted.get(RNG.nextInt(weighted.size()));
    }

    private static String pickFallbackHobby(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }
        boolean hasRod = hasItem(bot, Items.FISHING_ROD);
        boolean hasWaterNearby = hasRod && hasNearbyBlock(world, bot.getBlockPos(), 12, net.minecraft.block.Blocks.WATER);
        boolean hasCampfireNearby = hasNearbyBlock(world, bot.getBlockPos(), 24, net.minecraft.block.Blocks.CAMPFIRE)
                || hasNearbyBlock(world, bot.getBlockPos(), 24, net.minecraft.block.Blocks.SOUL_CAMPFIRE);
        boolean canFeedAnimals = hasFeedTargets(bot, world);
        boolean canPickFlowers = hasNearbyFlowers(world, bot.getBlockPos(), 18);
        boolean canHunt = HuntHistoryService.hasAnyFoodKill(world)
                && bot.getHealth() >= 18.0F
                && bot.getHungerManager().getFoodLevel() >= 16
                && !world.isThundering()
                && HuntSkill.hasAmbientHuntCandidate(bot, world);
        boolean canCollectLeafLitter = hasNearbyLeafLitter(world, bot.getBlockPos(), 14);
        boolean canForageMushrooms = hasNearbyMushrooms(world, bot.getBlockPos(), 16);

        if (canFeedAnimals) {
            return "feed_animals";
        }
        if (hasWaterNearby) {
            return "fish";
        }
        if (canPickFlowers) {
            return "flowers";
        }
        if (canHunt) {
            return "hunt";
        }
        // Never force hangout unless a campfire exists nearby.
        if (hasCampfireNearby) {
            return "hangout";
        }
        if (canCollectLeafLitter && canForageMushrooms) {
            return RNG.nextBoolean() ? "leaf_litter" : "mushrooms";
        }
        if (canCollectLeafLitter) {
            return "leaf_litter";
        }
        if (canForageMushrooms) {
            return "mushrooms";
        }
        // If no specific hobby candidates exist, still do a low-intensity local stroll.
        return "wander";
    }

    private static boolean hasItem(ServerPlayerEntity bot, net.minecraft.item.Item item) {
        if (bot == null || item == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyBlock(ServerWorld world, BlockPos origin, int radius, net.minecraft.block.Block block) {
        if (world == null || origin == null || block == null) {
            return false;
        }
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(block)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyFlowers(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return false;
        }
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isIn(BlockTags.FLOWERS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyLeafLitter(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return false;
        }
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.LEAF_LITTER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyMushrooms(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return false;
        }
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.RED_MUSHROOM)
                    || world.getBlockState(pos).isOf(net.minecraft.block.Blocks.BROWN_MUSHROOM)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFeedTargets(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        if (!hasAnyFeedItem(bot)) {
            return false;
        }
        return !AnimalFeedingService.findAmbientFeedTargets(world, bot, 12).isEmpty();
    }

    private static boolean hasAnyFeedItem(ServerPlayerEntity bot) {
        return hasItem(bot, Items.WHEAT)
                || hasItem(bot, Items.SUGAR)
                || hasItem(bot, Items.APPLE)
                || hasItem(bot, Items.HAY_BLOCK)
                || hasItem(bot, Items.CARROT)
                || hasItem(bot, Items.POTATO)
                || hasItem(bot, Items.BEETROOT)
                || hasItem(bot, Items.WARPED_FUNGUS);
    }

    private static void startAmbientSkill(MinecraftServer server, ServerPlayerEntity bot, String skillName) {
        if (server == null || bot == null || skillName == null || skillName.isBlank()) {
            return;
        }
        UUID botUuid = bot.getUuid();

        // Re-check task slot to avoid race if something started between decision and scheduling.
        if (TaskService.hasActiveTask(botUuid)) {
            return;
        }

        ServerCommandSource botSource = bot.getCommandSource().withSilent();

        Map<String, Object> params = new HashMap<>();
        params.put("_origin", "ambient");
        params.put("open_ended", false);

        // Fishing already interprets missing count as "until sunset"; keep it open-ended.
        if ("fish".equalsIgnoreCase(skillName)) {
            params.put("options", java.util.List.of("until_sunset"));
            params.put("open_ended", true);
        }

        if ("hunt".equalsIgnoreCase(skillName)) {
            params.put("options", java.util.List.of("hobby"));
            params.put("count", 1);
        }

        if ("feed_animals".equalsIgnoreCase(skillName)) {
            params.put("radius", 14);
        }

        if ("flowers".equalsIgnoreCase(skillName)) {
            params.put("count", 6);
            params.put("radius", 18);
        }

        if ("wander".equalsIgnoreCase(skillName)) {
            params.put("radius", 10 + RNG.nextInt(6)); // 10-15
            params.put("steps", 1 + RNG.nextInt(2));   // 1-2
        }

        if ("leaf_litter".equalsIgnoreCase(skillName)) {
            params.put("count", 3 + RNG.nextInt(3));   // 3-5
            params.put("radius", 10 + RNG.nextInt(5)); // 10-14
        }

        if ("mushrooms".equalsIgnoreCase(skillName)) {
            params.put("count", 2 + RNG.nextInt(2));   // 2-3
            params.put("radius", 10 + RNG.nextInt(5)); // 10-14
        }


        // Ambient hangouts should be short so they don't starve other hobbies (like fishing) after failures.
        // If a user explicitly runs /bot ... hangout they can still pass duration_sec.
        if ("hangout".equalsIgnoreCase(skillName)) {
            params.put("duration_sec", 25 + RNG.nextInt(26)); // 25–50s
            params.put("until_sunset", true);
        }

        AMBIENT_EXECUTOR.submit(() -> {
            try {
                SkillContext skillContext = new SkillContext(botSource, SharedStateService.safeSharedState("idle-hobbies"), params, botSource);
                SkillExecutionResult result = SkillManager.runSkill(skillName, skillContext);
                // We intentionally do not echo result here; many skills already speak during execution.
                LOGGER.info("Idle hobby '{}' finished for {}: success={} msg='{}'",
                        skillName, bot.getName().getString(), result != null && result.success(), result != null ? result.message() : "null");
                LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());

                // Schedule the next decision relative to completion time.
                // - Success: wait a bit so hobbies feel “occasional”, not spammy.
                // - Failure: retry sooner so the bot can pick a different hobby or re-attempt after moving.
                server.execute(() -> {
                    if (bot.isRemoved() || !bot.isAlive()) {
                        return;
                    }
                    if (!BotHomeService.isIdleHobbiesEnabled(bot)) {
                        return;
                    }
                    if (TaskService.hasActiveTask(botUuid)) {
                        return;
                    }
                    long now = server.getTicks();
                    boolean ok = result != null && result.success();
                    long delay = ok
                            ? (300L + RNG.nextInt(300))   // 15–30s
                            : 200L;                       // 10s
                    NEXT_DECISION_TICK.put(botUuid, now + delay);
                });
            } catch (Throwable t) {
                LOGGER.warn("Idle hobby '{}' crashed for {}: {}",
                        skillName,
                        bot.getName().getString(),
                        t.getClass().getSimpleName(),
                        t);
                LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
            }
        });
    }

    private static void noteBlocked(ServerPlayerEntity bot, long nowTick, String reasonKey) {
        noteBlocked(bot, nowTick, reasonKey, null);
    }

    private static void noteBlocked(ServerPlayerEntity bot, long nowTick, String reasonKey, String detail) {
        if (bot == null || reasonKey == null || reasonKey.isBlank()) {
            return;
        }
        UUID id = bot.getUuid();
        String key = reasonKey.trim().toLowerCase(Locale.ROOT);
        String previous = LAST_BLOCKED_REASON_KEY.get(id);
        long lastTick = LAST_BLOCKED_REASON_LOG_TICK.getOrDefault(id, Long.MIN_VALUE);
        boolean reasonChanged = !key.equals(previous);
        if (!reasonChanged && nowTick - lastTick < BLOCKED_REASON_LOG_INTERVAL_TICKS) {
            return;
        }
        LAST_BLOCKED_REASON_KEY.put(id, key);
        LAST_BLOCKED_REASON_LOG_TICK.put(id, nowTick);

        String reason = key;
        if (detail != null && !detail.isBlank()) {
            reason = reason + ":" + detail.trim();
        }
        LOGGER.info("Idle hobbies blocked for {}: {}", bot.getName().getString(), reason);
    }

    private static void clearBlockedNote(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        LAST_BLOCKED_REASON_KEY.remove(botUuid);
        LAST_BLOCKED_REASON_LOG_TICK.remove(botUuid);
    }
}
