package net.shasankp000.GameAI.services;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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
     * becomes {@link net.shasankp000.GameAI.BotEventHandler.Mode#IDLE}.
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
                continue;
            }
            if (bot.isSleeping()) {
                continue;
            }

            // Only start hobbies when truly idle (not following, not guard/patrol, not returning).
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
                continue;
            }

            // Extra safety: if follow/base intent is set but mode is momentarily IDLE, don't start a hobby.
            // This prevents hobbies from stealing control while a follow is active.
            if (BotEventHandler.getFollowTargetUuid(bot) != null || BotEventHandler.getBaseTarget(bot) != null) {
                continue;
            }

            // Never compete with a task.
            if (TaskService.hasActiveTask(bot.getUuid())) {
                continue;
            }

            int tod = (int) (world.getTimeOfDay() % 24_000L);
            if (tod >= DONT_START_AFTER_TOD) {
                continue;
            }

            UUID botUuid = bot.getUuid();

            // First-seen grace window: let persistence restoration and chunk ticketing settle.
            Long firstSeen = FIRST_SEEN_TICK.putIfAbsent(botUuid, nowTick);
            if (firstSeen == null) {
                continue;
            }
            if (nowTick - firstSeen < JOIN_GRACE_TICKS) {
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
                continue;
            }

            // Require being near home; keeps hobbies "local".
            Vec3d home = resolveHomeAnchor(world, bot);
            if (home == null) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 200L);
                continue;
            }
            double distHome = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).distanceTo(home);
            if (distHome > 24.0D) {
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 200L);
                continue;
            }

            String hobby = pickHobby(bot, world);
            if (hobby == null) {
                // No suitable hobby for current inventory/terrain; try again later.
                NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 400L + RNG.nextInt(400));
                continue;
            }

            // Minimal backoff: the running task will block additional hobby starts via TaskService.
            // We schedule the true “cooldown” when the hobby finishes (success/failure).
            NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + 80L + RNG.nextInt(80));

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
        Object spawnPoint = world.getSpawnPoint();
        if (spawnPoint != null) {
            Class<?> clazz = spawnPoint.getClass();
            try {
                Object result = clazz.getMethod("pos").invoke(spawnPoint);
                if (result instanceof BlockPos blockPos) {
                    return blockPos;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                Object result = clazz.getMethod("toImmutable").invoke(spawnPoint);
                if (result instanceof BlockPos blockPos) {
                    return blockPos;
                }
            } catch (ReflectiveOperationException ignored) {
            }
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField("pos");
                field.setAccessible(true);
                Object result = field.get(spawnPoint);
                if (result instanceof BlockPos blockPos) {
                    return blockPos;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return BlockPos.ORIGIN;
    }

    private static String pickHobby(ServerPlayerEntity bot, ServerWorld world) {
        boolean hasRod = hasItem(bot, Items.FISHING_ROD);
        boolean hasWaterNearby = hasRod && hasNearbyBlock(world, bot.getBlockPos(), 12, net.minecraft.block.Blocks.WATER);
        // Campfire can be a bit further away; it still "feels" local but avoids being too finicky.
        int campfireRadius = 24;
        boolean hasCampfireNearby = hasNearbyBlock(world, bot.getBlockPos(), campfireRadius, net.minecraft.block.Blocks.CAMPFIRE)
            || hasNearbyBlock(world, bot.getBlockPos(), campfireRadius, net.minecraft.block.Blocks.SOUL_CAMPFIRE);

        // Weighted-ish selection: prefer fishing if possible.
        if (hasWaterNearby && RNG.nextDouble() < 0.70D) {
            return "fish";
        }
        if (hasCampfireNearby) {
            return "hangout";
        }
        if (hasWaterNearby) {
            return "fish";
        }

        return null;
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
        params.put("open_ended", true);

        // Fishing already interprets missing count as "until sunset"; keep it open-ended.
        if ("fish".equalsIgnoreCase(skillName)) {
            params.put("options", java.util.List.of("until_sunset"));
        }

        // Ambient hangouts should be short so they don't starve other hobbies (like fishing) after failures.
        // If a user explicitly runs /bot ... hangout they can still pass duration_sec.
        if ("hangout".equalsIgnoreCase(skillName)) {
            params.put("duration_sec", 25 + RNG.nextInt(26)); // 25–50s
            params.put("until_sunset", true);
        }

        AMBIENT_EXECUTOR.submit(() -> {
            try {
                SkillContext skillContext = new SkillContext(botSource, net.shasankp000.FunctionCaller.FunctionCallerV2.getSharedState(), params, botSource);
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
            } catch (Exception e) {
                LOGGER.warn("Idle hobby '{}' crashed for {}: {}", skillName, bot.getName().getString(), e.getMessage(), e);
                LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
            }
        });
    }
}
