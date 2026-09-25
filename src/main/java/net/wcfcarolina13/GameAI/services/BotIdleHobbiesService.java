package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
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
import net.wcfcarolina13.GameAI.services.SmeltingService;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.PlayerUtils.CombatInventoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final long LONG_IDLE_PROMOTION_TICKS = 24_000L;
    private static final Map<UUID, Long> IDLE_SINCE_TICK = new ConcurrentHashMap<>();
    private static final long WOODEN_FALLBACK_COOLDOWN_TICKS = 20L * 12L;
    private static final long WOODEN_FALLBACK_CRAFT_RETRY_TICKS = 20L * 20L;
    private static final int WOODEN_FALLBACK_STARVING_HUNGER = 10;
    private static final long FOOD_RECHECK_TICKS = 40L;
    /**
     * Minimum ticks between two wooden-fallback availability probes for one bot (100 ticks = 5 s).
     *
     * <p>See {@link HobbyBackoffPolicy#AVAILABILITY_PROBE_TICKS} for why the probe exists (a long
     * backoff must still notice an axe appearing in a reachable chest) and why it is throttled
     * (the probe scans every accessible container, and this method runs every tick).
     */
    private static final long WOODEN_FALLBACK_PROBE_TICKS = HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS;
    private static final Map<UUID, Long> NEXT_WOODEN_FALLBACK_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> LAST_WOODEN_FALLBACK_SIGNATURE = new ConcurrentHashMap<>();
    /** Tick of each bot's last wooden-fallback availability probe; written on the server tick thread. */
    private static final Map<UUID, Long> LAST_WOODEN_FALLBACK_PROBE_TICK = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> NEXT_LEATHER_ARMOR_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_COBBLESTONE_TOOLS_TICK = new ConcurrentHashMap<>();

    /**
     * Backoff key (in the per-hobby maps below) for the wooden fallback's chest asks, separate from
     * the woodcut it may lead to. A pull that asked the owner's supply ledger and got nothing (a
     * refusal, an ignored prompt coming back {@code NOT_PERMITTED}, a cooldown, the reserve) is one
     * failure on the {@link HobbyBackoffPolicy} ladder, and no pull asks again until it runs out:
     * the probe below runs every 5 s during a woodcut backoff and would otherwise re-prompt an
     * owner who ignores it as soon as the ledger allowed. A pull still waiting for an answer is not
     * a failure (the next pull must be free to redeem the grant); a pull that moved something and
     * missed nothing clears it.
     */
    private static final String IDLE_SUPPLY_BACKOFF_KEY = "idle-supply";
    /**
     * How soon the wooden fallback looks again while the owner has a supply prompt open for the
     * weapon or axe it lacks (the probe's pace): quick enough to take a grant, which lives 60 s
     * from the owner's click, and the repeat asks are quiet.
     */
    private static final long WOODEN_FALLBACK_SUPPLY_WAIT_TICKS = HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS;
    /**
     * Failure-counter keys (in {@link #HOBBY_FAILURE_COUNT}) for the idle craft fallbacks. A craft
     * can fail because {@code CraftingHelper} is waiting on a supply prompt, so after the first
     * failure each keeps climbing {@link SupplyPullPolicy#craftRetryTicks} instead of retrying at
     * its flat pace forever.
     */
    private static final String CRAFT_WOODEN_FALLBACK_KEY = "craft-wooden-fallback";
    private static final String CRAFT_LEATHER_ARMOR_KEY = "craft-leather-armor";
    private static final String CRAFT_WOODEN_TOOLS_KEY = "craft-wooden-tools";
    private static final String CRAFT_STONE_TOOLS_KEY = "craft-stone-tools";

    /**
     * Per-bot, per-hobby escalating backoff (see {@link HobbyBackoffPolicy}).
     *
     * <p>Keyed per hobby rather than globally so a hobby that keeps failing (woodcut with no axe
     * and no reachable tree) never suppresses one that would succeed. All three maps are written
     * only from the server tick thread — the picker itself, or the {@code server.execute} block in
     * {@link #startAmbientSkill}'s completion handler — so the inner maps need no extra locking
     * beyond being concurrent.
     */
    private static final Map<UUID, Map<String, Long>> HOBBY_NEXT_ALLOWED_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Integer>> HOBBY_FAILURE_COUNT = new ConcurrentHashMap<>();
    /** Next-allowed tick most recently logged as "still backing off", so we log once per window. */
    private static final Map<UUID, Map<String, Long>> HOBBY_BACKOFF_LOGGED_FOR = new ConcurrentHashMap<>();

    private static final Map<UUID, String> LAST_HOBBY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_HOBBY_END_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PREFER_COOKING_UNTIL = new ConcurrentHashMap<>();
    private static final long PREFER_COOKING_DURATION_MS = 5L * 60L * 1000L; // 5 minutes
    /** When canStartFallbackWoodcut finds a distant tree via directional expansion, stores the radius to use. */
    private static final Map<UUID, Integer> EXPANDED_WOODCUT_RADIUS = new ConcurrentHashMap<>();
    private static final int DIRECTIONAL_WOODCUT_RADIUS = 48;

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
        IDLE_SINCE_TICK.clear();
        NEXT_WOODEN_FALLBACK_TICK.clear();
        LAST_WOODEN_FALLBACK_SIGNATURE.clear();
        LAST_WOODEN_FALLBACK_PROBE_TICK.clear();
        NEXT_LEATHER_ARMOR_TICK.clear();
        NEXT_COBBLESTONE_TOOLS_TICK.clear();
        HOBBY_NEXT_ALLOWED_TICK.clear();
        HOBBY_FAILURE_COUNT.clear();
        HOBBY_BACKOFF_LOGGED_FOR.clear();
        LAST_BLOCKED_REASON_KEY.clear();
        LAST_BLOCKED_REASON_LOG_TICK.clear();
        PREFER_COOKING_UNTIL.clear();
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

    /**
     * Signals that the next idle hobby for this bot should strongly prefer cooking raw food.
     * Expires after {@link #PREFER_COOKING_DURATION_MS}.
     */
    public static void setPreferCooking(UUID botUuid) {
        if (botUuid == null) return;
        PREFER_COOKING_UNTIL.put(botUuid, System.currentTimeMillis() + PREFER_COOKING_DURATION_MS);
    }

    public static void clearPreferCooking(UUID botUuid) {
        if (botUuid != null) PREFER_COOKING_UNTIL.remove(botUuid);
    }

    private static boolean shouldPreferCooking(UUID botUuid) {
        Long until = PREFER_COOKING_UNTIL.get(botUuid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            PREFER_COOKING_UNTIL.remove(botUuid);
            return false;
        }
        return true;
    }

    private static final AtomicInteger AMBIENT_THREAD_ID = new AtomicInteger(0);
    private static volatile ExecutorService AMBIENT_EXECUTOR = createAmbientExecutor();

    private static ExecutorService createAmbientExecutor() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ambient-hobby-" + AMBIENT_THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** Interrupt all in-flight ambient hobby tasks. Called during server shutdown. */
    public static void shutdownExecutors() {
        AMBIENT_EXECUTOR.shutdownNow();
    }

    /** Re-create the ambient hobby executor if it was shut down. Called from {@code SERVER_STARTED}. */
    public static void restartExecutors() {
        if (AMBIENT_EXECUTOR.isShutdown()) {
            AMBIENT_EXECUTOR = createAmbientExecutor();
        }
    }

    private BotIdleHobbiesService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) {
            return;
        }

        long nowTick = server.getTicks();

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            UUID botUuid = bot.getUuid();
            if (!BotHomeService.isIdleHobbiesEnabled(bot)) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "hobbies-disabled");
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                continue;
            }
            if (world.getRegistryKey() != World.OVERWORLD) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "not-overworld");
                continue;
            }
            if (bot.isSleeping()) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "sleeping");
                continue;
            }

            // Only start hobbies when truly idle (not following, not guard/patrol, not returning).
            if (BotEventHandler.getCurrentMode(bot) != BotEventHandler.Mode.IDLE) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "mode-not-idle", String.valueOf(BotEventHandler.getCurrentMode(bot)));
                continue;
            }

            // Extra safety: if follow/base intent is set but mode is momentarily IDLE, don't start a hobby.
            // This prevents hobbies from stealing control while a follow is active.
            if (BotEventHandler.getFollowTargetUuid(bot) != null || BotEventHandler.getBaseTarget(bot) != null) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "follow-or-base-intent");
                continue;
            }

            // Never compete with a task, but let starvation preempt low-priority ambient work.
            var activeTask = TaskService.getActiveTaskInfo(bot.getUuid());
            if (activeTask.isPresent()) {
                if (tryInterruptLowPriorityAmbientForFood(bot, world, activeTask.get(), nowTick)) {
                    IDLE_SINCE_TICK.remove(botUuid);
                    clearWoodenFallbackState(botUuid);
                    noteBlocked(bot, nowTick, "food-preempt");
                    continue;
                }
                IDLE_SINCE_TICK.remove(botUuid);
                // Do NOT wipe the wooden-fallback cooldown while the fallback's own woodcut run is
                // the active task. That wipe is what turned a doomed one-tree woodcut into a
                // two-tick restart storm (2026-09-06 field log): :1195 armed a 12 s cooldown, this
                // branch removed it on the very next tick, the skill exited inside one tick with
                // "I have no axe…", and the picker restarted it immediately. Clearing after an
                // *unrelated* task is still correct — the world may have changed underneath — so
                // narrow the wipe instead of dropping it.
                if (!isWoodenFallbackOwnTask(activeTask.get())) {
                    clearWoodenFallbackState(botUuid);
                }
                noteBlocked(bot, nowTick, "active-task");
                continue;
            }
            if (BotFleeService.isBreakingFree(bot.getUuid())) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "break-free");
                continue;
            }
            if (BotFleeService.isInShelter(bot.getUuid())) {
                // Auto-clear stale shelter if it's daytime — validateAndTickShelter may not have run yet
                if (world.isDay() && !world.isThundering()) {
                    // fire-and-forget: break-free runs on worker thread; isBreakingFree guards next tick
                    BotFleeService.clearShelterAndBreakFree(bot);
                    LOGGER.info("Cleared stale shelter for {} (daytime) — break-free launched, skipping hobby tick",
                            bot.getName().getString());
                    continue; // let break-free finish before starting hobbies
                } else {
                    IDLE_SINCE_TICK.remove(botUuid);
                    clearWoodenFallbackState(botUuid);
                    noteBlocked(bot, nowTick, "sheltered");
                    continue;
                }
            }

            if (BotFleeService.tryResumeInterruptedSurvival(bot, server)) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "resume-survival");
                continue;
            }
            if (BotFleeService.hasPendingInterruptedSurvival(botUuid)) {
                IDLE_SINCE_TICK.remove(botUuid);
                clearWoodenFallbackState(botUuid);
                noteBlocked(bot, nowTick, "pending-survival-resume");
                continue;
            }

            IDLE_SINCE_TICK.putIfAbsent(botUuid, nowTick);

            int tod = (int) (world.getTimeOfDay() % 24_000L);
            if (tod >= DONT_START_AFTER_TOD) {
                // R4 exception: allow underground night mining when stuck with no bed
                boolean undergroundNightException = hasAnyPickaxe(bot)
                        && !BotFleeService.isAtSurface(bot, world)
                        && !world.isSkyVisible(bot.getBlockPos().up())
                        && bot.getHungerManager().getFoodLevel() >= 10
                        && !hasNearbyBed(world, bot.getBlockPos(), 16)
                        && hasNearbyStone(world, bot.getBlockPos(), 12);
                if (!undergroundNightException) {
                    noteBlocked(bot, nowTick, "late-day");
                    continue;
                }
            }

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

            if (HealingService.isHungry(bot)) {
                if (BotMutualAidService.tryUrgentFoodRecovery(bot, world)) {
                    NEXT_DECISION_TICK.put(botUuid, nowTick + FOOD_RECHECK_TICKS);
                    noteBlocked(bot, nowTick, "food-urgent");
                    continue;
                }
                if (BotFleeService.isSurfaceRecoveryActive(botUuid)) {
                    NEXT_DECISION_TICK.put(botUuid, nowTick + FOOD_RECHECK_TICKS);
                    noteBlocked(bot, nowTick, "surface-recovery");
                    continue;
                }
                if (HealingService.isStarving(bot)
                        && BotEmergencyRescueService.tryEmergencyRescue(bot, world, "idle-hungry")) {
                    NEXT_DECISION_TICK.put(botUuid, nowTick + FOOD_RECHECK_TICKS);
                    noteBlocked(bot, nowTick, "food-rescue");
                    continue;
                }
                BotAutoHuntService.requestDecisionNow(bot);
                NEXT_DECISION_TICK.put(botUuid, nowTick + FOOD_RECHECK_TICKS);
                noteBlocked(bot, nowTick, "hungry-block");
                continue;
            }

            // Skip resource fallbacks while surface recovery is in progress — prevents tight spin loops
            // where the fallback fires, startAmbientSkill detects "not at surface," ensureAtSurface is
            // already running, and the loop retries next tick.
            if (BotFleeService.isSurfaceRecoveryActive(botUuid)) {
                NEXT_DECISION_TICK.put(botUuid, nowTick + 200L);
                noteBlocked(bot, nowTick, "surface-recovery-fallback");
                continue;
            }

            if (maybeHandleIdleWoodenFallback(server, bot, world, nowTick)) {
                continue;
            }
            if (maybeHandleIdleLeatherArmorFallback(server, bot, world, nowTick)) {
                continue;
            }
            if (maybeHandleIdleCobblestoneToolsFallback(server, bot, world, nowTick)) {
                continue;
            }
            if (maybeHandleIdleStoneToolUpgrades(server, bot, world, nowTick)) {
                continue;
            }
            if (maybeHandleIdleWoodcutForResources(server, bot, world, nowTick)) {
                continue;
            }

            long next = NEXT_DECISION_TICK.getOrDefault(botUuid, 0L);

            // Grace period: if NEXT_DECISION_TICK was never set (bot just finished a command skill
            // or was just spawned), defer hobby scheduling by 5 seconds so the bot doesn't
            // instantly jump into a hobby after /bot stop.
            if (next == 0L && nowTick > 0L) {
                NEXT_DECISION_TICK.put(botUuid, nowTick + 100L);
                next = nowTick + 100L;
            }

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

            boolean preferFood = hasBeenIdleLong(botUuid, nowTick);
            String hobby = pickHobby(bot, world, preferFood);
            if (hobby == null) {
                hobby = pickFallbackHobby(bot, world, preferFood);
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

    /** Convenience: per-bot hobby toggle from BotHomeService. Default = enabled. */
    private static boolean hobbyAllowed(ServerPlayerEntity bot, String name) {
        return BotHomeService.isHobbyEnabled(bot, name);
    }

    private static String pickHobby(ServerPlayerEntity bot, ServerWorld world, boolean preferFood) {
        if (bot == null || world == null) {
            return null;
        }

        // Cooking check: if prefer-cooking flag is set and bot has raw food + fuel, cook first
        boolean canCook = SmeltingService.hasCookableFoodInventoryItems(bot, world)
                && (SmeltingService.hasFuelForAutoCook(bot, world)
                    || hasNearbyLeafLitter(world, bot.getBlockPos(), 14));
        if (shouldPreferCooking(bot.getUuid()) && canCook && hobbyAllowed(bot, "cook")) {
            clearPreferCooking(bot.getUuid());
            return "cook";
        }

        boolean hasRod = hasItem(bot, Items.FISHING_ROD);
        boolean hasWaterNearby = hasRod && hasNearbyBlock(world, bot.getBlockPos(), 12, net.minecraft.block.Blocks.WATER);
        // Campfire can be a bit further away; it still "feels" local but avoids being too finicky.
        int campfireRadius = 24;
        boolean hasCampfireNearby = hasNearbyBlock(world, bot.getBlockPos(), campfireRadius, net.minecraft.block.Blocks.CAMPFIRE)
            || hasNearbyBlock(world, bot.getBlockPos(), campfireRadius, net.minecraft.block.Blocks.SOUL_CAMPFIRE);

        boolean healthy = bot.getHealth() >= 18.0F && bot.getHungerManager().getFoodLevel() >= 16;
        boolean canHunt = healthy
                && !world.isThundering()
                && HuntSkill.hasAmbientHuntCandidate(bot, world);
        boolean canFeedAnimals = hasFeedTargets(bot, world);
        boolean canPickFlowers = hasNearbyFlowers(world, bot.getBlockPos(), 18);
        boolean canGatherSeeds = hasNearbyGrass(world, bot.getBlockPos(), 16);
        boolean canShadowCompanion = hasNearbyAmbientCompanion(bot, world);
        boolean canMineStone = hasAnyPickaxe(bot) && hasNearbyStone(world, bot.getBlockPos(), 12);
        if (canMineStone && shouldSuppressMiningHobby(bot, world)) {
            canMineStone = false;
        }
        boolean undergroundNightMining = canMineStone && isUndergroundNightMiningCandidate(bot, world);
        boolean canCollectLeafLitter = hasNearbyLeafLitter(world, bot.getBlockPos(), 14);
        boolean inLeafLitterBiome = canCollectLeafLitter && isLeafLitterFriendlyBiome(world, bot.getBlockPos());
        boolean leafLitterSurvival = canCollectLeafLitter && needsLeafLitterForSurvival(bot, world);
        boolean canCollectHoney = (hasItem(bot, Items.SHEARS) || hasItem(bot, Items.GLASS_BOTTLE))
                && hasNearbyHarvestableBeehive(world, bot.getBlockPos(), 16);

        // Build weighted options so available hobbies actually trigger instead of idling on random misses.
        ArrayList<String> weighted = new ArrayList<>();
        if (preferFood) {
            if (canHunt) {
                weighted.add("hunt");
                weighted.add("hunt");
                weighted.add("hunt");
            }
            if (hasWaterNearby) {
                weighted.add("fish");
                weighted.add("fish");
            }
        }
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
        if (canGatherSeeds) {
            weighted.add("grass_seeds");
            weighted.add("grass_seeds");
        }
        if (canHunt) {
            weighted.add("hunt");
        }
        if (canMineStone) {
            weighted.add("mining");
            if (undergroundNightMining) {
                // R4: underground at night with food, no bed — mining is productive
                weighted.add("mining");
                weighted.add("mining");
            }
        }
        if (canShadowCompanion) {
            weighted.add("shadow_companion");
        }
        // Hangout is only valid when a campfire is actually nearby.
        if (hasCampfireNearby) {
            weighted.add("hangout");
        }
        if (canCook) {
            weighted.add("cook");
            weighted.add("cook");
        }
        if (leafLitterSurvival) {
            weighted.add("leaf_litter");
            weighted.add("leaf_litter");
        } else if (inLeafLitterBiome) {
            weighted.add("leaf_litter");
        }
        if (canCollectHoney) {
            weighted.add("honey_collect");
        }
        // Strip hobbies the user has unchecked for this bot.
        weighted.removeIf(name -> !hobbyAllowed(bot, name));
        if (weighted.isEmpty()) {
            return null;
        }
        return weighted.get(RNG.nextInt(weighted.size()));
    }

    private static String pickFallbackHobby(ServerPlayerEntity bot, ServerWorld world, boolean preferFood) {
        if (bot == null || world == null) {
            return null;
        }

        // Cooking takes priority in fallback if flag is set
        boolean canCook = SmeltingService.hasCookableFoodInventoryItems(bot, world)
                && (SmeltingService.hasFuelForAutoCook(bot, world)
                    || hasNearbyLeafLitter(world, bot.getBlockPos(), 14));
        if (shouldPreferCooking(bot.getUuid()) && canCook && hobbyAllowed(bot, "cook")) {
            clearPreferCooking(bot.getUuid());
            return "cook";
        }

        boolean hasRod = hasItem(bot, Items.FISHING_ROD);
        boolean hasWaterNearby = hasRod && hasNearbyBlock(world, bot.getBlockPos(), 12, net.minecraft.block.Blocks.WATER) && hobbyAllowed(bot, "fish");
        boolean hasCampfireNearby = hasNearbyBlock(world, bot.getBlockPos(), 24, net.minecraft.block.Blocks.CAMPFIRE)
                || hasNearbyBlock(world, bot.getBlockPos(), 24, net.minecraft.block.Blocks.SOUL_CAMPFIRE);
        boolean canFeedAnimals = hasFeedTargets(bot, world) && hobbyAllowed(bot, "feed_animals");
        boolean canPickFlowers = hasNearbyFlowers(world, bot.getBlockPos(), 18) && hobbyAllowed(bot, "flowers");
        boolean canHunt = bot.getHealth() >= 18.0F
                && bot.getHungerManager().getFoodLevel() >= 16
                && !world.isThundering()
                && HuntSkill.hasAmbientHuntCandidate(bot, world)
                && hobbyAllowed(bot, "hunt");
        boolean canCollectLeafLitter = hasNearbyLeafLitter(world, bot.getBlockPos(), 14) && hobbyAllowed(bot, "leaf_litter");
        boolean canForageMushrooms = hasNearbyMushrooms(world, bot.getBlockPos(), 16) && hobbyAllowed(bot, "mushrooms");
        boolean canGatherSeeds = hasNearbyGrass(world, bot.getBlockPos(), 16) && hobbyAllowed(bot, "grass_seeds");
        boolean canShadowCompanion = hasNearbyAmbientCompanion(bot, world) && hobbyAllowed(bot, "shadow_companion");
        boolean canMineStone = hasAnyPickaxe(bot) && hasNearbyStone(world, bot.getBlockPos(), 12) && hobbyAllowed(bot, "mining");
        if (canMineStone && shouldSuppressMiningHobby(bot, world)) {
            canMineStone = false;
        }

        if (preferFood) {
            if (canHunt) {
                return "hunt";
            }
            if (hasWaterNearby) {
                return "fish";
            }
        }

        // Survival leaf litter: if bot needs fuel for cooking, prioritize collection
        if (canCollectLeafLitter && needsLeafLitterForSurvival(bot, world)) {
            return "leaf_litter";
        }

        if (canFeedAnimals) {
            return "feed_animals";
        }
        if (hasWaterNearby) {
            return "fish";
        }
        if (canPickFlowers) {
            return "flowers";
        }
        if (canGatherSeeds) {
            return "grass_seeds";
        }
        if (canHunt) {
            return "hunt";
        }
        if (canMineStone) {
            return "mining";
        }
        if (canShadowCompanion) {
            return "shadow_companion";
        }
        // Never force hangout unless a campfire exists nearby.
        if (hasCampfireNearby && hobbyAllowed(bot, "hangout")) {
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
        if (hobbyAllowed(bot, "wander")) {
            return "wander";
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

    private static boolean hasNearbyHarvestableBeehive(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) return false;
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -4, -r), origin.add(r, 4, r))) {
            if (!world.isChunkLoaded(pos)) continue;
            var state = world.getBlockState(pos);
            if (!BotBeehiveRegistryService.isBeehiveBlock(state)) continue;
            if (state.get(net.minecraft.state.property.Properties.HONEY_LEVEL) != 5) continue;
            net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof net.minecraft.block.entity.BeehiveBlockEntity hive && hive.isSmoked()) {
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

    /** True when the bot is in a biome where leaf litter naturally generates. */
    private static boolean isLeafLitterFriendlyBiome(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        var biomeEntry = world.getBiome(pos);
        if (biomeEntry == null) {
            return false;
        }
        String biomeKey = biomeEntry.getKey()
                .map(k -> k.getValue().toString())
                .orElse("")
                .toLowerCase(Locale.ROOT);
        return biomeKey.contains("forest")
                || biomeKey.contains("taiga")
                || biomeKey.contains("grove");
    }

    /** True when the bot would benefit from collecting leaf litter as fuel. */
    private static boolean needsLeafLitterForSurvival(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        if (shouldPreferCooking(bot.getUuid())) {
            return true;
        }
        if (SmeltingService.hasCookableFoodInventoryItems(bot, world)
                && !SmeltingService.hasFuelForAutoCook(bot, world)) {
            return true;
        }
        return bot.getHungerManager().getFoodLevel() <= 14
                && SmeltingService.hasCookableFoodInventoryItems(bot, world);
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

    private static boolean hasNearbyGrass(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) {
            return false;
        }
        int r = Math.max(6, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            var state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.SHORT_GRASS)
                    || state.isOf(net.minecraft.block.Blocks.TALL_GRASS)
                    || state.isOf(net.minecraft.block.Blocks.FERN)
                    || state.isOf(net.minecraft.block.Blocks.LARGE_FERN)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyAmbientCompanion(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        UUID owner = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (owner == null) {
            return false;
        }
        for (ServerPlayerEntity other : BotEventHandler.getRegisteredBots(world.getServer())) {
            if (other == null || other == bot || other.isRemoved() || !other.isAlive()) {
                continue;
            }
            if (other.getEntityWorld() != world) {
                continue;
            }
            if (bot.squaredDistanceTo(other) > 18.0D * 18.0D) {
                continue;
            }
            if (!owner.equals(BotTerritoryAuthorizationService.resolveBotOwnerUuid(other))) {
                continue;
            }
            TaskService.ActiveTaskInfo active = TaskService.getActiveTaskInfo(other.getUuid()).orElse(null);
            if (active == null || active.origin() != TaskService.Origin.AMBIENT) {
                continue;
            }
            return true;
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
        if (server == null || bot == null || skillName == null || skillName.isBlank() || TaskService.isServerStopping()) {
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

        String runSkillName = skillName;

        if ("wander".equalsIgnoreCase(runSkillName)) {
            params.put("radius", 10 + RNG.nextInt(6)); // 10-15
            params.put("steps", 3 + RNG.nextInt(2));   // 3-4
        }

        if ("shadow_companion".equalsIgnoreCase(runSkillName)) {
            params.put("shadow_companion", true);
            params.put("radius", 6);
            params.put("steps", 2 + RNG.nextInt(2));
            runSkillName = "wander";
        }

        if ("leaf_litter".equalsIgnoreCase(runSkillName)) {
            ServerWorld sw = bot.getEntityWorld() instanceof ServerWorld s ? s : null;
            boolean survival = sw != null && needsLeafLitterForSurvival(bot, sw);
            boolean forestBiome = sw != null && isLeafLitterFriendlyBiome(sw, bot.getBlockPos());

            int countMin  = survival ? 6 : 3;
            int countRange = (survival ? 5 : 3) + (forestBiome ? 2 : 0);   // survival 6-10(+2), ambient 3-5(+2)
            int radiusMin  = survival ? 14 : 10;
            int radiusRange = (survival ? 7 : 5) + (forestBiome ? 4 : 0);  // survival 14-20(+4), ambient 10-14(+4)

            params.put("count", countMin + RNG.nextInt(countRange));
            params.put("radius", radiusMin + RNG.nextInt(radiusRange));
            if (survival) {
                params.put("survival_mode", true);
            }
            if (forestBiome) {
                params.put("forest_biome", true);
            }
        }

        if ("honey_collect".equalsIgnoreCase(runSkillName)) {
            params.put("count", 1 + RNG.nextInt(3)); // 1-3 hives
            params.put("radius", 16);
        }

        if ("mushrooms".equalsIgnoreCase(runSkillName)) {
            params.put("count", 2 + RNG.nextInt(2));   // 2-3
            params.put("radius", 10 + RNG.nextInt(5)); // 10-14
        }

        if ("grass_seeds".equalsIgnoreCase(runSkillName)) {
            params.put("count", 3 + RNG.nextInt(3));
            params.put("radius", 10 + RNG.nextInt(5));
        }

        if ("mining".equalsIgnoreCase(runSkillName)) {
            params.put("count", 8 + RNG.nextInt(5));  // 8-12 cobblestone
            params.put("searchRadius", 10);
            params.put("verticalRange", 4);
            // R3/R4: if underground at night with no bed, mine in place without surface escape
            if (bot.getEntityWorld() instanceof ServerWorld sw
                    && isUndergroundNightMiningCandidate(bot, sw)
                    && !shouldSuppressMiningHobby(bot, sw)) {
                params.put("_skip_surface_escape", true);
            }
        }

        if ("woodcut".equalsIgnoreCase(runSkillName)) {
            params.put("count", 1);
            int woodcutRadius = EXPANDED_WOODCUT_RADIUS.getOrDefault(bot.getUuid(), 12);
            EXPANDED_WOODCUT_RADIUS.remove(bot.getUuid());
            params.put("searchRadius", woodcutRadius);
            params.put("verticalRange", 6);
            params.put("wooden_fallback", true);
        }


        // Cook hobby: runs SmeltingService.cookAllFoodSync directly (no registered Skill needed)
        if ("cook".equalsIgnoreCase(runSkillName)) {
            try {
            AMBIENT_EXECUTOR.submit(() -> {
                try {
                    if (TaskService.isServerStopping()) return;
                    ServerWorld sw = bot.getEntityWorld() instanceof ServerWorld s ? s : null;
                    if (sw == null) return;
                    if (!BotFleeService.isAtSurface(bot, sw) && !BotFleeService.ensureAtSurface(bot, sw)) {
                        LOGGER.info("Cook hobby: {} could not reach surface, skipping", bot.getName().getString());
                        LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
                        return;
                    }
                    LOGGER.info("Cook hobby starting for {}", bot.getName().getString());
                    boolean result = SmeltingService.cookAllFoodSync(bot, botSource, sw);
                    LOGGER.info("Cook hobby finished for {}: success={}", bot.getName().getString(), result);
                    LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
                    net.wcfcarolina13.GameAI.souls.SoulEventObserver.onHobbySession(botUuid, "cook");
                    clearPreferCooking(botUuid);
                    server.execute(() -> {
                        if (TaskService.isServerStopping() || bot.isRemoved() || !bot.isAlive()) return;
                        long now = server.getTicks();
                        NEXT_DECISION_TICK.put(botUuid, now + (result ? 300L + RNG.nextInt(300) : 200L));
                    });
                } catch (Throwable t) {
                    LOGGER.warn("Cook hobby crashed for {}: {}", bot.getName().getString(), t.getMessage());
                    LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
                }
            });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Executor shut down during server stop — safe to ignore.
            }
            return;
        }

        // Ambient hangouts should be short so they don't starve other hobbies (like fishing) after failures.
        // If a user explicitly runs /bot ... hangout they can still pass duration_sec.
        if ("hangout".equalsIgnoreCase(runSkillName)) {
            params.put("duration_sec", 25 + RNG.nextInt(26)); // 25–50s
            params.put("until_sunset", true);
        }

        final String skillToRun = runSkillName;
        final boolean woodenFallback = Boolean.TRUE.equals(params.get("wooden_fallback"));

        try {
        AMBIENT_EXECUTOR.submit(() -> {
            try {
                if (TaskService.isServerStopping()) {
                    return;
                }
                if (requiresOperationalSurface(skillToRun)
                        && !Boolean.TRUE.equals(params.get("_skip_surface_escape"))
                        && bot.getEntityWorld() instanceof ServerWorld sw
                        && !BotFleeService.isAtSurface(bot, sw)) {
                    if (!BotFleeService.isAtSurface(bot, sw)) {
                        LOGGER.info("Idle hobby: {} not at surface — escaping before '{}'",
                                bot.getName().getString(), skillToRun);
                        if (!BotFleeService.ensureAtSurfaceForHobby(bot, sw)) {
                            LOGGER.warn("Idle hobby: {} could not reach surface, skipping '{}'",
                                    bot.getName().getString(), skillToRun);
                            server.execute(() -> {
                                if (!bot.isRemoved() && bot.isAlive()) {
                                    NEXT_DECISION_TICK.put(botUuid, server.getTicks() + 600L);
                                    if (woodenFallback) {
                                        NEXT_WOODEN_FALLBACK_TICK.put(botUuid, server.getTicks() + 600L);
                                    }
                                }
                            });
                            LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
                            return;
                        }
                    }
                }
                SkillContext skillContext = new SkillContext(botSource, SharedStateService.safeSharedState("idle-hobbies"), params, botSource);
                SkillExecutionResult result = SkillManager.runSkill(skillToRun, skillContext);
                // Sampled here, on the worker, in the same window SkillManager's own finally block
                // reads it: TaskService.complete does not clear ABORT_LATCH, but a later tick can.
                // A cancelled run must not count as a failure against the hobby's backoff.
                final boolean abortRequested = TaskService.isAbortRequested(botUuid);
                // We intentionally do not echo result here; many skills already speak during execution.
                LOGGER.info("Idle hobby '{}' finished for {}: success={} msg='{}'",
                        skillToRun, bot.getName().getString(), result != null && result.success(), result != null ? result.message() : "null");
                LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
                net.wcfcarolina13.GameAI.souls.SoulEventObserver.onHobbySession(botUuid, skillToRun);

                // Schedule the next decision relative to completion time.
                // - Success: wait a bit so hobbies feel “occasional”, not spammy.
                // - Failure: retry sooner so the bot can pick a different hobby or re-attempt after moving.
                server.execute(() -> {
                    if (TaskService.isServerStopping()) {
                        return;
                    }
                    if (bot.isRemoved() || !bot.isAlive()) {
                        return;
                    }
                    long now = server.getTicks();
                    boolean ok = result != null && result.success();
                    // Arm the per-(bot, hobby) backoff before the remaining guards: if the bot is
                    // momentarily busy again, the outcome of this run must still be remembered, or
                    // a deterministic failure would keep restarting behind a 12 s flat cooldown.
                    recordHobbyAttempt(botUuid, skillToRun, ok, abortRequested, now);
                    if (!BotHomeService.isIdleHobbiesEnabled(bot)) {
                        return;
                    }
                    if (TaskService.hasActiveTask(botUuid)) {
                        return;
                    }
                    long delay;
                    if (woodenFallback) {
                        delay = ok ? 20L : WOODEN_FALLBACK_COOLDOWN_TICKS;
                    } else {
                        delay = ok
                                ? (300L + RNG.nextInt(300))   // 15–30s
                                : 200L;                       // 10s
                    }
                    NEXT_DECISION_TICK.put(botUuid, now + delay);
                });
            } catch (Throwable t) {
                LOGGER.warn("Idle hobby '{}' crashed for {}: {}",
                        skillToRun,
                        bot.getName().getString(),
                        t.getClass().getSimpleName(),
                        t);
                LAST_HOBBY_END_MS.put(botUuid, System.currentTimeMillis());
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor shut down during server stop — safe to ignore.
        }
    }

    private static boolean requiresOperationalSurface(String skillName) {
        if (skillName == null) {
            return false;
        }
        String normalized = skillName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "hunt", "flowers", "wander", "leaf_litter", "mushrooms", "feed_animals", "hangout", "grass_seeds", "woodcut", "mining", "collect_dirt", "cook" -> true;
            default -> false;
        };
    }

    private static boolean maybeHandleIdleWoodenFallback(MinecraftServer server,
                                                         ServerPlayerEntity bot,
                                                         ServerWorld world,
                                                         long nowTick) {
        if (server == null || bot == null || world == null || bot.getCommandSource() == null) {
            return false;
        }
        UUID botUuid = bot.getUuid();
        if (bot.getHungerManager().getFoodLevel() <= WOODEN_FALLBACK_STARVING_HUNGER) {
            return false;
        }
        boolean needWeapon = !ToolProvisionService.hasServiceableMeleeWeapon(bot);
        boolean needAxe = !ToolProvisionService.hasUsableAxe(bot);
        if (!needWeapon && !needAxe) {
            clearWoodenFallbackState(botUuid);
            // The definitive availability flip, and the only safe place to reset the escalation
            // ladder: reaching this line means the bot actually holds a usable axe AND a
            // serviceable weapon, so the fallback has nothing left to do. It cannot fire while the
            // bot is toolless, so it cannot re-open the restart storm.
            clearWoodenFallbackBackoff(botUuid);
            LAST_WOODEN_FALLBACK_PROBE_TICK.remove(botUuid);
            return false;
        }

        int signature = ToolProvisionService.computeAccessibleIdleFallbackSignature(bot, world);
        Integer lastSignature = LAST_WOODEN_FALLBACK_SIGNATURE.get(botUuid);
        if (lastSignature == null || lastSignature.intValue() != signature) {
            Long pendingCooldown = NEXT_WOODEN_FALLBACK_TICK.get(botUuid);
            if (lastSignature != null && pendingCooldown != null && nowTick < pendingCooldown) {
                // Diagnostic for the 1.1.199 field log: the fallback restarted a doomed one-tree
                // woodcut ~14×/s. Something flips this signature between ticks and wipes the
                // 12 s cooldown; log what so the field session can name it.
                LOGGER.info("Idle wooden fallback: {} signature changed {} -> {} with {} ticks of cooldown left; resetting",
                        bot.getName().getString(), lastSignature, signature, pendingCooldown - nowTick);
            }
            LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, signature);
            NEXT_WOODEN_FALLBACK_TICK.remove(botUuid);
            // Deliberately does NOT reset the HobbyBackoffPolicy escalation ladder here. This
            // coarse signature is not a valid "world changed" trigger: it hashes the containers
            // scanned at the bot's CURRENT position (ToolProvisionService
            // .computeAccessibleIdleFallbackSignature), so it flips on movement alone. A following
            // bot with no axe would wipe its own backoff simply by walking, and the two-tick
            // restart storm cd4120ca fixed would come straight back. The backoff is reset only on
            // a real availability flip: the toolless early-out above, or the throttled probe below.
        }

        long nextAllowed = NEXT_WOODEN_FALLBACK_TICK.getOrDefault(botUuid, 0L);
        if (nowTick < nextAllowed) {
            // Nothing below this early return re-reads the world, and a long backoff is mirrored
            // onto this flat cooldown further down, so without a probe here a bot four failures
            // deep serves out the full ten minutes no matter what the commander does. Probe
            // instead — throttled, because the scan is not free at tick rate.
            probeWoodenFallbackAvailability(bot, world, needWeapon, needAxe, nowTick);
            return true;
        }

        SupplyPullPolicy.Pull pull = pullIdleFallbackSupplies(bot, world, needWeapon, needAxe, nowTick);
        boolean moved = pull.movedAny();
        boolean stillMissingTool = !ToolProvisionService.hasServiceableMeleeWeapon(bot)
                || !ToolProvisionService.hasUsableAxe(bot);
        if (SupplyPullPolicy.holdsIdleFallback(pull, stillMissingTool)) {
            // The owner has a supply prompt open for what the bot lacks (or a grant waits for it):
            // don't craft or cut a tree over their answer. Look again at the probe's pace; the next
            // pull takes the grant, or comes back NOT_PERMITTED (refused or expired) and counts as
            // a failure on the idle supply backoff, after which this pass crafts or cuts as before.
            NEXT_WOODEN_FALLBACK_TICK.put(botUuid, nowTick + WOODEN_FALLBACK_SUPPLY_WAIT_TICKS);
            LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, ToolProvisionService.computeAccessibleIdleFallbackSignature(bot, world));
            return true;
        }
        boolean craftReady = ToolProvisionService.canCraftIdleWoodenFallback(bot, needWeapon, needAxe);
        boolean crafted = craftReady && tryCraftIdleWoodenFallback(bot);
        if (crafted) {
            idleCraftFailureWaitTicks(botUuid, CRAFT_WOODEN_FALLBACK_KEY, true, WOODEN_FALLBACK_CRAFT_RETRY_TICKS);
        }
        CombatInventoryManager.ensureCombatLoadout(bot);
        if (!ToolProvisionService.hasServiceableMeleeWeapon(bot) || !ToolProvisionService.hasUsableAxe(bot)) {
            if (!BotFleeService.isAtSurface(bot, world)) {
                NEXT_WOODEN_FALLBACK_TICK.put(botUuid, nowTick + 600L);
                LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, ToolProvisionService.computeAccessibleIdleFallbackSignature(bot, world));
                return true;
            }
            if (craftReady && !crafted) {
                long waitTicks = idleCraftFailureWaitTicks(botUuid, CRAFT_WOODEN_FALLBACK_KEY, false,
                        WOODEN_FALLBACK_CRAFT_RETRY_TICKS);
                NEXT_WOODEN_FALLBACK_TICK.put(botUuid, nowTick + waitTicks);
                LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, ToolProvisionService.computeAccessibleIdleFallbackSignature(bot, world));
                LOGGER.warn("Idle wooden fallback: {} craft attempt failed; backing off for {} ticks",
                        bot.getName().getString(),
                        waitTicks);
                return true;
            }
            if (canStartFallbackWoodcut(world, bot)) {
                // Escalating per-(bot, hobby) backoff, not the flat 12 s cooldown: a woodcut that
                // exits inside one tick with "I have no axe…" is deterministic, and a flat cooldown
                // retries it forever at the tick rate. See HobbyBackoffPolicy.
                if (isHobbyBackedOff(bot, WOODEN_FALLBACK_HOBBY, nowTick)) {
                    // Mirror the backoff onto the flat cooldown so the pull/craft probe above this
                    // point isn't re-run every 12 s while the woodcut itself is suppressed. The one
                    // exception is a pass that actually moved or crafted something: the situation
                    // genuinely changed, and both of those are self-limiting (the chest empties),
                    // so the usual short retry still applies.
                    long backoffNext = hobbyNextAllowedTick(botUuid, WOODEN_FALLBACK_HOBBY);
                    NEXT_WOODEN_FALLBACK_TICK.put(botUuid,
                            (moved || crafted) ? Math.min(backoffNext, nowTick + 40L) : backoffNext);
                    LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, ToolProvisionService.computeAccessibleIdleFallbackSignature(bot, world));
                    return true;
                }
                // Short in-flight guard only; the authoritative wait after a failure is armed by
                // recordHobbyAttempt when the run finishes.
                NEXT_WOODEN_FALLBACK_TICK.put(botUuid, nowTick + WOODEN_FALLBACK_COOLDOWN_TICKS);
                LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, ToolProvisionService.computeAccessibleIdleFallbackSignature(bot, world));
                LAST_HOBBY.put(botUuid, "woodcut");
                startAmbientSkill(server, bot, "woodcut");
                LOGGER.info("Idle wooden fallback: starting one-tree woodcut for {}", bot.getName().getString());
                return true;
            }
            if (moved || crafted) {
                NEXT_WOODEN_FALLBACK_TICK.put(botUuid, nowTick + 40L);
                LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, ToolProvisionService.computeAccessibleIdleFallbackSignature(bot, world));
                return true;
            }
            long retryAt = isTooLateForWoodcut(world)
                    ? nowTick + ticksUntilMorning(world)
                    : nowTick + WOODEN_FALLBACK_COOLDOWN_TICKS;
            NEXT_WOODEN_FALLBACK_TICK.put(botUuid, retryAt);
            LAST_WOODEN_FALLBACK_SIGNATURE.put(botUuid, signature);
            return true;
        }

        clearWoodenFallbackState(botUuid);
        if (moved || crafted) {
            NEXT_DECISION_TICK.put(botUuid, nowTick + 20L);
            LOGGER.info("Idle wooden fallback: {} restored minimal gear (weapon={}, axe={})",
                    bot.getName().getString(),
                    ToolProvisionService.hasServiceableMeleeWeapon(bot),
                    ToolProvisionService.hasUsableAxe(bot));
            return true;
        }
        return false;
    }

    private static boolean tryCraftIdleWoodenFallback(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource() == null) {
            return false;
        }
        ServerCommandSource source = bot.getCommandSource().withSilent();
        ServerPlayerEntity commander = resolveWoodenFallbackHistoryOwner(bot);
        boolean crafted = false;
        if (!ToolProvisionService.hasServiceableMeleeWeapon(bot)) {
            crafted |= ToolProvisionService.ensureSword(bot, source, commander, true);
        }
        if (!ToolProvisionService.hasUsableAxe(bot)) {
            crafted |= ToolProvisionService.ensureAxe(bot, source, commander, true);
        }
        if (crafted) {
            CombatInventoryManager.ensureCombatLoadout(bot);
        }
        return crafted;
    }

    private static boolean maybeHandleIdleLeatherArmorFallback(MinecraftServer server,
                                                                ServerPlayerEntity bot,
                                                                ServerWorld world,
                                                                long nowTick) {
        if (server == null || bot == null || world == null || bot.getCommandSource() == null) {
            return false;
        }
        if (!ToolProvisionService.hasAnyEmptyArmorSlot(bot)) {
            return false;
        }
        // Cooldown before the chest scan: both just return false, and the scan is not free every tick.
        long nextAllowed = NEXT_LEATHER_ARMOR_TICK.getOrDefault(bot.getUuid(), 0L);
        if (nowTick < nextAllowed) {
            return false;
        }
        // At least 4 leather needed (boots, the cheapest piece)
        if (ToolProvisionService.countLeatherAvailable(bot, world) < 4) {
            return false;
        }

        ServerCommandSource source = bot.getCommandSource().withSilent();
        ServerPlayerEntity commander = resolveWoodenFallbackHistoryOwner(bot);
        boolean crafted = false;
        // Cheapest first: boots(4) → helmet(5) → leggings(7) → chestplate(8)
        for (EquipmentSlot slot : java.util.List.of(EquipmentSlot.FEET, EquipmentSlot.HEAD,
                EquipmentSlot.LEGS, EquipmentSlot.CHEST)) {
            crafted |= ToolProvisionService.ensureLeatherArmorForSlot(bot, source, commander, slot);
        }
        if (crafted) {
            net.wcfcarolina13.PlayerUtils.armorUtils.autoEquipArmor(bot);
            CombatInventoryManager.ensureCombatLoadout(bot);
            LOGGER.info("Idle leather armor: {} crafted leather armor", bot.getName().getString());
        }
        // Retry sooner if succeeded (might have more leather for next piece), longer backoff if no craft
        long failureWait = idleCraftFailureWaitTicks(bot.getUuid(), CRAFT_LEATHER_ARMOR_KEY, crafted, 2400L);
        NEXT_LEATHER_ARMOR_TICK.put(bot.getUuid(), nowTick + (crafted ? 200L : failureWait));
        return crafted;
    }

    /**
     * Crafts wooden pickaxe and wooden shovel when the bot has planks/logs but lacks these tools.
     * Enables cobblestone gathering as an idle hobby once tools are available.
     */
    private static boolean maybeHandleIdleCobblestoneToolsFallback(MinecraftServer server,
                                                                    ServerPlayerEntity bot,
                                                                    ServerWorld world,
                                                                    long nowTick) {
        if (server == null || bot == null || world == null || bot.getCommandSource() == null) {
            return false;
        }
        if (bot.getHungerManager().getFoodLevel() <= WOODEN_FALLBACK_STARVING_HUNGER) {
            return false;
        }
        boolean needPickaxe = !hasAnyPickaxe(bot);
        boolean needShovel = !hasAnyShovel(bot);
        if (!needPickaxe && !needShovel) {
            return false;
        }
        // Only craft if the bot has planks/logs to work with
        if (!ToolProvisionService.hasPlanksOrLogsInInventory(bot)) {
            return false;
        }
        long nextAllowed = NEXT_COBBLESTONE_TOOLS_TICK.getOrDefault(bot.getUuid(), 0L);
        if (nowTick < nextAllowed) {
            return false;
        }

        ServerCommandSource source = bot.getCommandSource().withSilent();
        ServerPlayerEntity commander = resolveWoodenFallbackHistoryOwner(bot);
        boolean crafted = false;
        if (needPickaxe) {
            crafted |= ToolProvisionService.ensurePickaxe(bot, source, commander, true);
        }
        if (needShovel) {
            crafted |= ToolProvisionService.ensureShovel(bot, source, commander, true);
        }
        if (crafted) {
            CombatInventoryManager.ensureCombatLoadout(bot);
            LOGGER.info("Idle cobblestone tools: {} crafted wooden tools (pickaxe={}, shovel={})",
                    bot.getName().getString(),
                    !hasAnyPickaxe(bot) ? "needed" : "ready",
                    !hasAnyShovel(bot) ? "needed" : "ready");
        }
        long failureWait = idleCraftFailureWaitTicks(bot.getUuid(), CRAFT_WOODEN_TOOLS_KEY, crafted, 2400L);
        NEXT_COBBLESTONE_TOOLS_TICK.put(bot.getUuid(), nowTick + (crafted ? 200L : failureWait));
        return crafted;
    }

    private static boolean hasAnyPickaxe(ServerPlayerEntity bot) {
        if (bot == null) return false;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT).contains("pickaxe")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyShovel(ServerPlayerEntity bot) {
        if (bot == null) return false;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT).contains("shovel")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyBed(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) return false;
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -4, -r), origin.add(r, 4, r))) {
            if (!world.isChunkLoaded(pos)) continue;
            if (world.getBlockState(pos).isIn(BlockTags.BEDS)) return true;
        }
        return false;
    }

    /**
     * Returns true if the mining hobby should be suppressed at the bot's current location.
     * Gates: near base (R1), near village (R2), near surface while underground (R5).
     */
    private static boolean shouldSuppressMiningHobby(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos pos = bot.getBlockPos();

        // R1: Don't mine near any saved base
        if (BotHomeService.isNearAnyBase(bot, 24.0)) return true;

        // R2: Don't mine near villages (mapped or signal-detected)
        if (MappedVillageService.isInsideMappedVillage(world, pos)
                || MappedVillageService.isNearMappedVillage(world, pos, 8)
                || SurvivalRecruitmentService.isVillageSignalNearby(world, pos)) {
            return true;
        }

        // R5: If underground but close to the surface, prefer escape over mining
        if (!BotFleeService.isAtSurface(bot, world)) {
            int surfaceY = SafePositionService.getWalkableGroundY(world, bot.getBlockX(), bot.getBlockZ());
            if (bot.getBlockY() >= surfaceY - 3) return true;
            // Trees nearby = surface indicator; only check when near-ish surface to limit scan cost
            if (bot.getBlockY() >= surfaceY - 6
                    && TreeDetector.findNearestTree(bot, 8, 4, null).isPresent()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if the bot qualifies for underground night mining (R4):
     * underground, nighttime, enough food, no nearby bed to sleep in.
     */
    private static boolean isUndergroundNightMiningCandidate(ServerPlayerEntity bot, ServerWorld world) {
        if (BotFleeService.isAtSurface(bot, world)) return false;
        if (world.isSkyVisible(bot.getBlockPos().up())) return false;
        if (world.isDay() && !world.isThundering()) return false;
        if (bot.getHungerManager().getFoodLevel() < 10) return false;
        if (hasNearbyBed(world, bot.getBlockPos(), 16)) return false;
        return true;
    }

    private static boolean hasNearbyStone(ServerWorld world, BlockPos origin, int radius) {
        if (world == null || origin == null) return false;
        int r = Math.max(1, radius);
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) continue;
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.STONE)
                    || state.isOf(net.minecraft.block.Blocks.COBBLESTONE)
                    || state.isOf(net.minecraft.block.Blocks.ANDESITE)
                    || state.isOf(net.minecraft.block.Blocks.DIORITE)
                    || state.isOf(net.minecraft.block.Blocks.GRANITE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Upgrades wooden tools to stone when cobblestone is available.
     * Tech tree progression: wood → stone. The bot keeps both tiers; existing tool-selection
     * logic naturally prefers the better tier.
     */
    private static boolean maybeHandleIdleStoneToolUpgrades(MinecraftServer server,
                                                             ServerPlayerEntity bot,
                                                             ServerWorld world,
                                                             long nowTick) {
        if (server == null || bot == null || world == null || bot.getCommandSource() == null) return false;
        if (bot.getHungerManager().getFoodLevel() <= WOODEN_FALLBACK_STARVING_HUNGER) return false;

        boolean canUpgradePickaxe = ToolProvisionService.hasOnlyWoodenTool(bot, "pickaxe");
        boolean canUpgradeSword   = ToolProvisionService.hasOnlyWoodenTool(bot, "sword");
        boolean canUpgradeAxe     = ToolProvisionService.hasOnlyWoodenTool(bot, "axe");
        boolean canUpgradeShovel  = ToolProvisionService.hasOnlyWoodenTool(bot, "shovel");
        if (!canUpgradePickaxe && !canUpgradeSword && !canUpgradeAxe && !canUpgradeShovel) return false;

        // Cooldown before the chest scan: both just return false, and the scan (three materials
        // across every chest in range) is not free every tick.
        long nextAllowed = NEXT_COBBLESTONE_TOOLS_TICK.getOrDefault(bot.getUuid(), 0L);
        if (nowTick < nextAllowed) return false;

        if (!ToolProvisionService.hasStoneMaterialsAvailable(bot, world)) return false;

        ServerCommandSource source = bot.getCommandSource().withSilent();
        ServerPlayerEntity commander = resolveWoodenFallbackHistoryOwner(bot);
        boolean crafted = false;

        // Upgrade order: pickaxe → sword → axe → shovel
        if (canUpgradePickaxe) crafted |= CraftingHelper.craftGeneric(source, bot, commander, "pickaxe", 1, "stone") > 0;
        if (canUpgradeSword)   crafted |= CraftingHelper.craftGeneric(source, bot, commander, "sword", 1, "stone") > 0;
        if (canUpgradeAxe)     crafted |= CraftingHelper.craftGeneric(source, bot, commander, "axe", 1, "stone") > 0;
        if (canUpgradeShovel)  crafted |= CraftingHelper.craftGeneric(source, bot, commander, "shovel", 1, "stone") > 0;

        if (crafted) {
            CombatInventoryManager.ensureCombatLoadout(bot);
            LOGGER.info("Idle stone upgrade: {} upgraded wooden tools to stone", bot.getName().getString());
        }
        long failureWait = idleCraftFailureWaitTicks(bot.getUuid(), CRAFT_STONE_TOOLS_KEY, crafted, 2400L);
        NEXT_COBBLESTONE_TOOLS_TICK.put(bot.getUuid(), nowTick + (crafted ? 200L : failureWait));
        return crafted;
    }

    /**
     * When the bot needs a pickaxe or shovel but has no planks/logs (in inventory or nearby chests)
     * to craft them, trigger woodcutting to gather resources. This prevents the bot from idling on
     * low-priority hobbies (grass seeds, wander) while lacking essential tools.
     */
    private static boolean maybeHandleIdleWoodcutForResources(MinecraftServer server,
                                                               ServerPlayerEntity bot,
                                                               ServerWorld world,
                                                               long nowTick) {
        if (server == null || bot == null || world == null || bot.getCommandSource() == null) return false;
        // Respect the decision cooldown — prevents spin loop when surface recovery fails
        if (nowTick < NEXT_DECISION_TICK.getOrDefault(bot.getUuid(), 0L)) return false;
        if (bot.getHungerManager().getFoodLevel() <= WOODEN_FALLBACK_STARVING_HUNGER) return false;

        boolean needPickaxe = !hasAnyPickaxe(bot);
        boolean needShovel = !hasAnyShovel(bot);
        if (!needPickaxe && !needShovel) return false;

        // Only trigger if there's no wood available at all (inventory + nearby chests)
        if (ToolProvisionService.hasPlanksOrLogsAvailable(bot)) return false;

        if (!canStartFallbackWoodcut(world, bot)) return false;

        LAST_HOBBY.put(bot.getUuid(), "woodcut");
        startAmbientSkill(server, bot, "woodcut");
        LOGGER.info("Idle resource woodcut: {} needs tools but has no wood — starting woodcut",
                bot.getName().getString());
        return true;
    }

    private static boolean tryInterruptLowPriorityAmbientForFood(ServerPlayerEntity bot,
                                                                 ServerWorld world,
                                                                 TaskService.ActiveTaskInfo activeTask,
                                                                 long nowTick) {
        if (bot == null || world == null || activeTask == null || !HealingService.isStarving(bot)) {
            return false;
        }
        if (activeTask.origin() != TaskService.Origin.AMBIENT) {
            return false;
        }
        if (!isLowPriorityAmbientTask(activeTask.name())) {
            return false;
        }
        if (!TaskService.interruptAmbientTask(bot.getUuid(), "§cStopping low-priority hobby to deal with starvation.")) {
            return false;
        }
        LOGGER.info("Idle hobby: interrupting '{}' for {} due to starvation (food={})",
                activeTask.name(),
                bot.getName().getString(),
                bot.getHungerManager().getFoodLevel());
        if (!BotMutualAidService.tryUrgentFoodRecovery(bot, world)) {
            BotAutoHuntService.requestDecisionNow(bot);
        }
        NEXT_DECISION_TICK.put(bot.getUuid(), nowTick + FOOD_RECHECK_TICKS);
        return true;
    }

    private static boolean isLowPriorityAmbientTask(String taskName) {
        if (taskName == null || taskName.isBlank()) {
            return false;
        }
        String normalized = taskName.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("skill:")) {
            normalized = normalized.substring("skill:".length());
        }
        return switch (normalized) {
            case "grass_seeds", "flowers", "wander", "leaf_litter", "mushrooms",
                 "hangout", "shadow_companion", "feed_animals", "mining" -> true;
            default -> false;
        };
    }

    private static ServerPlayerEntity resolveWoodenFallbackHistoryOwner(ServerPlayerEntity bot) {
        if (bot == null || bot.getCommandSource() == null) {
            return null;
        }
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid == null) {
            return bot;
        }
        ServerPlayerEntity owner = bot.getCommandSource().getServer().getPlayerManager().getPlayer(ownerUuid);
        return owner != null ? owner : bot;
    }

    private static boolean canStartFallbackWoodcut(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) {
            return false;
        }
        if (isTooLateForWoodcut(world)) {
            return false;
        }
        if (TaskService.hasActiveTask(bot.getUuid())) {
            return false;
        }
        // Check for trees/logs that are NOT in a protected zone.
        // Previously only checked if the bot was in a protected zone, but the trees
        // themselves could be protected even when the bot is just outside the boundary.
        var tree = TreeDetector.findNearestTree(bot, 12, 6, Collections.emptySet());
        if (tree.isPresent() && !TreeDetector.isProtectedForWoodcut(world, tree.get().base(), Math.max(4, tree.get().height()))) {
            return true;
        }
        var looseLog = TreeDetector.findNearestLooseLog(bot, 12, 6, Collections.emptySet());
        if (looseLog.isPresent() && !TreeDetector.isProtectedForWoodcut(world, looseLog.get(), 4)) {
            return true;
        }
        var anyLog = TreeDetector.findNearestAnyLog(bot, 12, 6, Collections.emptySet());
        if (anyLog.isPresent() && !TreeDetector.isProtectedForWoodcut(world, anyLog.get(), 4)) {
            return true;
        }

        // Final fallback: expand search in the direction the bot is facing.
        // If all nearby trees are protected, look further ahead for unprotected ones.
        BlockPos directionalTarget = findDirectionalUnprotectedTree(world, bot);
        if (directionalTarget != null) {
            EXPANDED_WOODCUT_RADIUS.put(bot.getUuid(), DIRECTIONAL_WOODCUT_RADIUS);
            LOGGER.info("Idle wooden fallback: {} found distant unprotected tree at {} (directional expansion)",
                    bot.getName().getString(), directionalTarget.toShortString());
            return true;
        }

        LOGGER.info("Idle wooden fallback: {} blocked — no safe woodcut target near or ahead of {}",
                bot.getName().getString(),
                bot.getBlockPos().toShortString());
        return false;
    }

    /**
     * Searches for an unprotected tree in a forward cone (±60°) at an expanded radius,
     * skipping the inner radius already checked by the normal search.
     */
    private static BlockPos findDirectionalUnprotectedTree(ServerWorld world, ServerPlayerEntity bot) {
        float yaw = bot.getYaw();
        double yawRad = Math.toRadians(yaw);
        // Minecraft yaw: 0=south(+Z), 90=west(-X), 180=north(-Z), 270=east(+X)
        double facingX = -Math.sin(yawRad);
        double facingZ = Math.cos(yawRad);
        return TreeDetector.findNearestUnprotectedTreeInRing(
                world, bot.getBlockPos(),
                12, DIRECTIONAL_WOODCUT_RADIUS, 6,
                facingX, facingZ);
    }

    private static boolean isTooLateForWoodcut(ServerWorld world) {
        if (world == null) {
            return true;
        }
        int tod = (int) (world.getTimeOfDay() % 24_000L);
        return tod >= DONT_START_AFTER_TOD;
    }

    private static long ticksUntilMorning(ServerWorld world) {
        if (world == null) {
            return WOODEN_FALLBACK_COOLDOWN_TICKS;
        }
        long tod = world.getTimeOfDay() % 24_000L;
        return Math.max(40L, (24_000L - tod) + 40L);
    }

    private static void clearWoodenFallbackState(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        // Deliberately does NOT call clearWoodenFallbackBackoff (1.1.216 fix-wave correction).
        // This method fires from ordinary scheduling churn — not-idle, following, sheltered,
        // an unrelated active task — many times a minute in normal play. None of that is a
        // world change, so it must not touch the escalating per-(bot, hobby) failure count or
        // its next-allowed tick: doing so kept resetting the ladder back to its 60-second first
        // step every time the bot switched tasks, so it never escalated far enough to actually
        // suppress a doomed woodcut in practice. Only the flat NEXT_WOODEN_FALLBACK_TICK /
        // LAST_WOODEN_FALLBACK_SIGNATURE in-flight state is cleared here; the backoff itself is
        // reset only where the tool signature actually flips (see clearWoodenFallbackBackoff).
        NEXT_WOODEN_FALLBACK_TICK.remove(botUuid);
        LAST_WOODEN_FALLBACK_SIGNATURE.remove(botUuid);
    }

    /**
     * Drops the woodcut backoff and its failure count for one bot.
     *
     * <p>The backoff otherwise cleared only on success or {@code resetSession}, so it had no
     * "world changed" reset (1.1.216 review finding 7): four failures arm a ten-minute wait, the
     * commander drops an axe in a nearby chest thirty seconds later, the accessible-supply
     * signature flips — and the fallback still stays suppressed for another nine minutes.
     *
     * <p>Called from exactly two sites, both of which observe a <em>definitive</em> availability
     * flip: the {@code !needWeapon && !needAxe} early-out in {@link #maybeHandleIdleWoodenFallback}
     * (the bot demonstrably holds both tools) and {@link #probeWoodenFallbackAvailability} (an axe
     * is now held or craftable). Two earlier versions called it from weaker signals and both were
     * wrong: {@link #clearWoodenFallbackState} fires on every ordinary
     * not-idle/follow/sheltered/active-task tick, which reset the ladder to its 60-second first
     * step on unrelated scheduling churn; and the tool-signature-change branch looked like a
     * "world changed" signal but hashes containers scanned at the bot's current position, so a
     * toolless bot wiped its own backoff just by walking and the restart storm returned. Keep this
     * call scoped to real availability only.
     *
     * <p>Scoped to {@link #WOODEN_FALLBACK_HOBBY} deliberately: this is wooden-fallback state, and
     * only the fallback's own start site consults the backoff gate. Clearing every hobby's counter
     * here would let an unrelated active task wipe backoffs that nothing in this call path knows
     * anything about.
     */
    private static void clearWoodenFallbackBackoff(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        String key = normalizeHobbyKey(WOODEN_FALLBACK_HOBBY);
        Map<String, Long> allowed = HOBBY_NEXT_ALLOWED_TICK.get(botUuid);
        if (allowed != null) {
            allowed.remove(key);
        }
        Map<String, Integer> counts = HOBBY_FAILURE_COUNT.get(botUuid);
        if (counts != null) {
            counts.remove(key);
        }
        Map<String, Long> logged = HOBBY_BACKOFF_LOGGED_FOR.get(botUuid);
        if (logged != null) {
            logged.remove(key);
        }
    }

    /**
     * Re-checks, at most once every {@link #WOODEN_FALLBACK_PROBE_TICKS}, whether a bot serving out
     * a woodcut backoff can now get an axe — and drops the backoff if it can.
     *
     * <p><b>Why it exists.</b> The flat-cooldown early return in
     * {@link #maybeHandleIdleWoodenFallback} fires before any world read, and a running backoff is
     * mirrored onto that cooldown, so a bot four failures deep would otherwise wait out its full
     * ten minutes however the world changes around it. The field check this serves is: put an axe
     * in a chest beside a backed-off bot and it retries within about five seconds.
     *
     * <p><b>Why it is throttled.</b> {@code maybeHandleIdleWoodenFallback} runs every server tick
     * for every idle bot, and the probe pulls from every accessible container around the bot.
     * Unthrottled that trades a restart storm for a scan storm; once per 100 ticks reads as
     * immediate to a player and costs one scan per five seconds per toolless idle bot.
     *
     * <p><b>Why only these two signals reset.</b> Only an axe now held, or the inputs to craft one
     * now held, count — the definitive availability flip. Movement, a pickup, or a container coming
     * into range are not resets: that is exactly what the position-dependent accessible-supply
     * signature measures, and resetting on it is what re-opens the storm.
     *
     * <p>The pull is a world/inventory mutation, so this must stay on the server tick thread; its
     * only caller already is. It goes through {@link #pullIdleFallbackSupplies}, so a probe asks the
     * owner nothing while the idle supply backoff runs, and a probe that asks and gets nothing
     * extends it: an ignored prompt is not re-sent every five seconds.
     *
     * @return true when the backoff was cleared by this probe
     */
    private static boolean probeWoodenFallbackAvailability(ServerPlayerEntity bot,
                                                           ServerWorld world,
                                                           boolean needWeapon,
                                                           boolean needAxe,
                                                           long nowTick) {
        if (bot == null || world == null) {
            return false;
        }
        UUID botUuid = bot.getUuid();
        boolean backoffRunning = hobbyNextAllowedTick(botUuid, WOODEN_FALLBACK_HOBBY) > nowTick;
        if (!HobbyBackoffPolicy.shouldProbeAvailability(backoffRunning,
                LAST_WOODEN_FALLBACK_PROBE_TICK.get(botUuid), nowTick, WOODEN_FALLBACK_PROBE_TICKS)) {
            return false;
        }
        LAST_WOODEN_FALLBACK_PROBE_TICK.put(botUuid, nowTick);
        pullIdleFallbackSupplies(bot, world, needWeapon, needAxe, nowTick);
        boolean axeHeld = ToolProvisionService.hasUsableAxe(bot);
        boolean craftable = ToolProvisionService.canCraftIdleWoodenFallback(bot, needWeapon, needAxe);
        if (!HobbyBackoffPolicy.probeClearsBackoff(axeHeld, craftable)) {
            // Found nothing: leave both the backoff and the throttle counter's meaning intact —
            // the bot keeps waiting exactly as long as it was going to.
            return false;
        }
        clearWoodenFallbackBackoff(botUuid);
        // Also drop the mirrored flat cooldown, or the bot would keep waiting on the copy of the
        // backoff we just cleared.
        NEXT_WOODEN_FALLBACK_TICK.remove(botUuid);
        LAST_WOODEN_FALLBACK_PROBE_TICK.remove(botUuid);
        LOGGER.info("Idle wooden fallback: {} availability probe cleared the woodcut backoff (axe={}, craftable={})",
                bot.getName().getString(), axeHeld, craftable);
        return true;
    }

    /**
     * The wooden fallback's chest pull ({@link ToolProvisionService#pullNearbyAccessibleIdleFallbackSupplies}),
     * behind the idle supply backoff ({@link #IDLE_SUPPLY_BACKOFF_KEY}): while it runs nothing is
     * asked and {@link SupplyPullPolicy.Pull#NOTHING} comes back; afterwards the pull's outcome is
     * folded in ({@link SupplyPullPolicy#idleBackoff}). Server tick thread only, like every writer
     * of the backoff maps.
     */
    private static SupplyPullPolicy.Pull pullIdleFallbackSupplies(ServerPlayerEntity bot,
                                                                  ServerWorld world,
                                                                  boolean needWeapon,
                                                                  boolean needAxe,
                                                                  long nowTick) {
        if (isHobbyBackedOff(bot, IDLE_SUPPLY_BACKOFF_KEY, nowTick)) {
            return SupplyPullPolicy.Pull.NOTHING;
        }
        SupplyPullPolicy.Pull pull =
                ToolProvisionService.pullNearbyAccessibleIdleFallbackSupplies(bot, world, needWeapon, needAxe);
        switch (SupplyPullPolicy.idleBackoff(pull)) {
            case SUCCESS -> recordHobbyAttempt(bot.getUuid(), IDLE_SUPPLY_BACKOFF_KEY, true, false, nowTick);
            case FAILURE -> recordHobbyAttempt(bot.getUuid(), IDLE_SUPPLY_BACKOFF_KEY, false, false, nowTick);
            case NONE -> {
            }
        }
        return pull;
    }

    /**
     * Folds one idle craft attempt into its failure counter ({@code key} in
     * {@link #HOBBY_FAILURE_COUNT}) and returns the wait before the next attempt if it failed:
     * {@code flatTicks} the first time, then {@link SupplyPullPolicy#craftRetryTicks} up the ladder.
     * A success clears the counter (and the returned wait is unused). Server tick thread only.
     */
    private static long idleCraftFailureWaitTicks(UUID botUuid, String key, boolean crafted, long flatTicks) {
        int prior = hobbyFailureCount(botUuid, key);
        int updated = HobbyBackoffPolicy.nextFailureCount(prior, crafted);
        if (updated <= 0) {
            Map<String, Integer> counts = HOBBY_FAILURE_COUNT.get(botUuid);
            if (counts != null) {
                counts.remove(normalizeHobbyKey(key));
            }
        } else {
            HOBBY_FAILURE_COUNT.computeIfAbsent(botUuid, u -> new ConcurrentHashMap<>())
                    .put(normalizeHobbyKey(key), updated);
        }
        return SupplyPullPolicy.craftRetryTicks(flatTicks, prior);
    }

    /** Hobby the wooden fallback dispatches; the only hobby reachable without going through pickHobby. */
    private static final String WOODEN_FALLBACK_HOBBY = "woodcut";

    /**
     * True when the currently active task is the run the wooden fallback itself started.
     *
     * <p>{@code ActiveTaskInfo.name()} is the ticket name, which {@code TaskService.beginSkill}
     * builds as {@code "skill:" + skillName} — so the prefix has to come off before comparing.
     * Ambient hobbies do not carry {@code Origin.AMBIENT} (they go through
     * {@code SkillManager.runSkill} → {@code beginSkill}, not {@code beginAmbientSkill}), so the
     * name is the only usable discriminator; a commander-issued {@code /bot woodcut} therefore also
     * matches, which merely means the fallback waits out its remaining cooldown afterwards.
     */
    private static boolean isWoodenFallbackOwnTask(TaskService.ActiveTaskInfo activeTask) {
        if (activeTask == null || activeTask.name() == null) {
            return false;
        }
        return WOODEN_FALLBACK_HOBBY.equals(normalizeHobbyKey(activeTask.name()));
    }

    /**
     * Canonical key for the per-(bot, hobby) backoff maps.
     *
     * <p>Delegates to {@link HobbyBackoffPolicy#normalizeHobbyKey} so the write site and the read
     * site cannot drift: this used to lower-case only while {@code isWoodenFallbackOwnTask}
     * stripped the {@code "skill:"} ticket prefix, so a caller passing a ticket name would have
     * stored {@code "skill:woodcut"} against a gate reading {@code "woodcut"} and silently
     * disabled the backoff (1.1.216 review finding 8).
     */
    private static String normalizeHobbyKey(String hobby) {
        return HobbyBackoffPolicy.normalizeHobbyKey(hobby);
    }

    /** Earliest tick this (bot, hobby) pair may start again, per {@link HobbyBackoffPolicy}. */
    private static long hobbyNextAllowedTick(UUID botUuid, String hobby) {
        if (botUuid == null) {
            return 0L;
        }
        Map<String, Long> perHobby = HOBBY_NEXT_ALLOWED_TICK.get(botUuid);
        return perHobby == null ? 0L : perHobby.getOrDefault(normalizeHobbyKey(hobby), 0L);
    }

    private static int hobbyFailureCount(UUID botUuid, String hobby) {
        if (botUuid == null) {
            return 0;
        }
        Map<String, Integer> perHobby = HOBBY_FAILURE_COUNT.get(botUuid);
        return perHobby == null ? 0 : perHobby.getOrDefault(normalizeHobbyKey(hobby), 0);
    }

    /**
     * Records a finished hobby attempt into the per-(bot, hobby) backoff.
     *
     * <p><b>Server tick thread only.</b> Every other writer of these maps is the picker in
     * {@link #onServerTick}; keeping this one inside the completion handler's
     * {@code server.execute} block keeps the counter tick-ordered with the gate that reads it.
     */
    private static void recordHobbyAttempt(UUID botUuid, String hobby, boolean success,
                                           boolean abortRequested, long nowTick) {
        if (botUuid == null) {
            return;
        }
        String key = normalizeHobbyKey(hobby);
        if (key.isEmpty()) {
            return;
        }
        int prior = hobbyFailureCount(botUuid, key);
        int updated = HobbyBackoffPolicy.nextFailureCount(prior, success, abortRequested);
        long nextAllowed = HobbyBackoffPolicy.nextAllowedTick(
                new HobbyBackoffPolicy.Attempt(key, success, abortRequested, prior, nowTick));

        if (updated <= 0) {
            Map<String, Integer> counts = HOBBY_FAILURE_COUNT.get(botUuid);
            if (counts != null) {
                counts.remove(key);
            }
        } else {
            HOBBY_FAILURE_COUNT.computeIfAbsent(botUuid, u -> new ConcurrentHashMap<>()).put(key, updated);
        }

        if (nextAllowed <= nowTick) {
            Map<String, Long> allowed = HOBBY_NEXT_ALLOWED_TICK.get(botUuid);
            if (allowed != null) {
                allowed.remove(key);
            }
            Map<String, Long> logged = HOBBY_BACKOFF_LOGGED_FOR.get(botUuid);
            if (logged != null) {
                logged.remove(key);
            }
            return;
        }

        HOBBY_NEXT_ALLOWED_TICK.computeIfAbsent(botUuid, u -> new ConcurrentHashMap<>()).put(key, nextAllowed);
        Map<String, Long> logged = HOBBY_BACKOFF_LOGGED_FOR.get(botUuid);
        if (logged != null) {
            logged.remove(key);
        }
    }

    /**
     * True while a (bot, hobby) pair is still inside its failure backoff window.
     *
     * <p>Logs the skip at most once per window — the gate is consulted every tick, so a per-tick
     * log would recreate the very spam the backoff exists to stop.
     */
    private static boolean isHobbyBackedOff(ServerPlayerEntity bot, String hobby, long nowTick) {
        if (bot == null) {
            return false;
        }
        UUID botUuid = bot.getUuid();
        String key = normalizeHobbyKey(hobby);
        long nextAllowed = hobbyNextAllowedTick(botUuid, key);
        if (nextAllowed <= nowTick) {
            return false;
        }
        Map<String, Long> logged = HOBBY_BACKOFF_LOGGED_FOR.computeIfAbsent(botUuid, u -> new ConcurrentHashMap<>());
        Long alreadyLogged = logged.get(key);
        if (alreadyLogged == null || alreadyLogged.longValue() != nextAllowed) {
            logged.put(key, nextAllowed);
            LOGGER.info("Idle hobbies: {} skipping {}",
                    bot.getName().getString(),
                    HobbyBackoffPolicy.describe(key, hobbyFailureCount(botUuid, key), nextAllowed - nowTick));
        }
        return true;
    }

    private static boolean hasBeenIdleLong(UUID botUuid, long nowTick) {
        if (botUuid == null) {
            return false;
        }
        long idleSince = IDLE_SINCE_TICK.getOrDefault(botUuid, nowTick);
        return nowTick - idleSince >= LONG_IDLE_PROMOTION_TICKS;
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
