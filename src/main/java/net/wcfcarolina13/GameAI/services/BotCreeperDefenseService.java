package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Always-on defensive interrupt for bots threatened by an ignited creeper.
 *
 * <p>Pre-empts skills, drop-sweep, follow, and idle hobbies when any
 * {@link CreeperEntity} within {@link #SCAN_RADIUS} blocks of the bot has
 * {@link CreeperEntity#isIgnited()} == true OR {@code getFuseSpeed() > 0} (proximity swelling). Drives the bot directly away
 * from the creeper at sprint speed until safe distance is held with
 * hysteresis, then releases control. Charged creepers get a larger safe
 * distance because their blast radius is ~2x normal.</p>
 *
 * <p>Distinct from the existing inline creeper flee in
 * {@code BotEventHandler.engageHostiles} (which only runs once combat is
 * already engaged) and from {@link BotFleeService} (IDLE-only shelter
 * machine for "outnumbered or critically wounded"). This service covers
 * the gap: a creeper fusing next to a bot mid-mining / mid-follow /
 * mid-anything.</p>
 *
 * <p>Pre-emption is enforced via two mechanisms:</p>
 * <ol>
 *   <li>{@link #tickBackoff(ServerPlayerEntity, MinecraftServer)} called
 *       early in {@code updateBehavior}; when it returns true the caller
 *       short-circuits.</li>
 *   <li>When backoff first triggers, the active task is force-aborted via
 *       {@link TaskService#forceAbort(UUID, String)} so worker-thread skills
 *       stop fighting our movement inputs. The latch is cleared when
 *       backoff resolves, mirroring the
 *       [[feedback-abort-latch-ownership]] discipline.</li>
 * </ol>
 */
public final class BotCreeperDefenseService {

    private static final Logger LOGGER = LoggerFactory.getLogger("creeper-defense");

    /** Search box half-size for ignited creepers (XYZ). Wider than normal hostile
     *  scans because we need to react before a creeper closes to point-blank. */
    private static final double SCAN_RADIUS = 16.0D;

    /** Backoff target distance for a normal creeper (blocks of horizontal sep). */
    private static final double SAFE_DIST_NORMAL = 9.0D;
    /** Backoff target distance for a charged (powered) creeper. */
    private static final double SAFE_DIST_CHARGED = 17.0D;

    /** Hysteresis: distance must be ≥ safe for this many consecutive ticks
     *  before backoff releases. Prevents oscillation near the threshold. */
    private static final int RELEASE_HOLD_TICKS = 10;

    /** Hard timeout — vanilla fuse is ~30 ticks; double for safety + travel. */
    private static final int MAX_BACKOFF_TICKS = 60;

    /** Stuck detection: if distance hasn't grown by ≥ this over the window
     *  below, treat as cornered and raise shield. Mirrors the inline-flee
     *  pattern in {@code BotEventHandler.engageHostiles}. */
    private static final double STUCK_PROGRESS_THRESHOLD = 0.3D;
    private static final int STUCK_WINDOW_TICKS = 20;

    /** Skip distant threats when behind a wall — vanilla CreeperIgniteGoal
     *  needs proximity ≤ 3 + LOS to ignite, so a wall-blocked creeper at
     *  ≥ this distance shouldn't have ignited from the bot anyway. */
    private static final double LOS_REQUIRED_BEYOND = 4.0D;

    /** How long to suppress drop-sweep after backoff fires. Short — the
     *  creeper either dies or despawns within ~3 s. */
    private static final long DROP_SWEEP_SUPPRESS_MS = 3_000L;

    private record BackoffState(UUID creeperUuid,
                                long startedTick,
                                double bestDistance,
                                long bestDistanceTick,
                                int safeHoldTicks,
                                boolean abortLatchOwned) {
    }

    private static final ConcurrentMap<UUID, BackoffState> ACTIVE = new ConcurrentHashMap<>();

    private BotCreeperDefenseService() {}

    /** Lightweight query for other systems to defer to backoff. */
    public static boolean isBackingOff(UUID botId) {
        return botId != null && ACTIVE.containsKey(botId);
    }

    /**
     * Per-tick defensive interrupt. Returns true if the bot is currently
     * backing off — the caller MUST short-circuit further behavior dispatch
     * this tick.
     *
     * <p>State machine per bot:</p>
     * <ul>
     *   <li><b>No active state, no threat:</b> return false.</li>
     *   <li><b>No active state, threat present:</b> initialize, abort
     *       active skill, suppress drop-sweep, dismount if mounted, return
     *       true (drive movement next tick to avoid race with the same-tick
     *       dismount).</li>
     *   <li><b>Active state:</b> revalidate the tracked creeper. If still
     *       a threat, drive movement and return true. If resolved, clear
     *       state + abort latch, return false.</li>
     * </ul>
     */
    public static boolean tickBackoff(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || bot.isRemoved() || !bot.isAlive() || server == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        UUID botId = bot.getUuid();
        long nowTick = server.getTicks();

        BackoffState state = ACTIVE.get(botId);
        if (state == null) {
            return tryEngage(bot, world, botId, nowTick);
        }
        CreeperEntity tracked = resolveCreeper(world, state.creeperUuid());
        if (!isStillThreat(bot, tracked, nowTick - state.startedTick())) {
            releaseBackoff(bot, state, "resolved");
            return false;
        }

        // Active state + tracked creeper still threatening.
        boolean charged = tracked.isCharged();
        double safeDist = charged ? SAFE_DIST_CHARGED : SAFE_DIST_NORMAL;
        double dist = horizontalDistance(bot, tracked);

        // Stuck tracking — update bestDistance every tick.
        double bestDist = state.bestDistance();
        long bestTick = state.bestDistanceTick();
        if (dist > bestDist + STUCK_PROGRESS_THRESHOLD) {
            bestDist = dist;
            bestTick = nowTick;
        }
        boolean cornered = (nowTick - bestTick) >= STUCK_WINDOW_TICKS;

        // Hysteresis on safe distance.
        int safeHold = dist >= safeDist ? state.safeHoldTicks() + 1 : 0;
        if (safeHold >= RELEASE_HOLD_TICKS) {
            releaseBackoff(bot, state, "safe-distance-held");
            return false;
        }

        ACTIVE.put(botId, new BackoffState(state.creeperUuid(), state.startedTick(),
                bestDist, bestTick, safeHold, state.abortLatchOwned()));

        // Movement: horizontal-away from creeper.
        double dx = bot.getX() - tracked.getX();
        double dz = bot.getZ() - tracked.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01D) { dx = 1.0D; dz = 0.0D; len = 1.0D; }
        double moveDist = charged ? SAFE_DIST_CHARGED + 4.0D : SAFE_DIST_NORMAL + 4.0D;
        Vec3d fleeTarget = new Vec3d(
                bot.getX() + (dx / len) * moveDist,
                bot.getY(),
                bot.getZ() + (dz / len) * moveDist);

        // Shield raise: when close or cornered, eat the blast through the shield.
        double shieldRaiseRadius = charged ? 8.0D : 4.5D;
        boolean hasShield = bot.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)
                || bot.getMainHandStack().isOf(net.minecraft.item.Items.SHIELD);
        if (hasShield && (dist <= shieldRaiseRadius || cornered)) {
            BotActions.raiseShieldFacing(bot, tracked);
        }

        boolean sprint = !bot.isSubmergedInWater();
        BotActions.sprint(bot, sprint);
        FollowMovementService.moveToward(bot, fleeTarget, 1.0D, sprint, null);
        return true;
    }

    /** First-tick engagement: scan for an ignited creeper, set up backoff state,
     *  abort active skills, suppress drop-sweep, dismount if needed. Returns true
     *  if a threat was found (caller short-circuits this tick). */
    private static boolean tryEngage(ServerPlayerEntity bot, ServerWorld world, UUID botId, long nowTick) {
        CreeperEntity threat = pickThreat(bot, world);
        if (threat == null) {
            return false;
        }
        if (bot.hasVehicle()) {
            bot.dismountVehicle();
        }
        boolean ownedLatch = false;
        if (!TaskService.isAbortRequested(botId)) {
            TaskService.forceAbort(botId, "creeper-fuse");
            ownedLatch = true;
        }
        DropSweepService.requestCancel(bot, "creeper-fuse");
        DropSweepService.suppressFor(botId, DROP_SWEEP_SUPPRESS_MS);
        BotActions.lowerShield(bot);
        double dist = horizontalDistance(bot, threat);
        ACTIVE.put(botId, new BackoffState(threat.getUuid(), nowTick, dist, nowTick, 0, ownedLatch));
        LOGGER.info("[creeper-defense] {} engaging backoff: creeper at dist {} charged={}",
                bot.getName().getString(),
                String.format("%.1f", dist),
                threat.isCharged());
        // Don't drive movement this tick — the dismount + skill abort need a
        // tick to settle. Next tick picks up the active state.
        return true;
    }

    /**
     * "About to blow" for either ignition path. {@code isIgnited()} is only set by flint & steel /
     * {@code CreeperIgniteGoal}; a creeper swelling from player proximity has {@code getFuseSpeed() > 0}
     * and {@code isIgnited() == false} (vanilla {@code tick()} sets fuse speed 1 when ignited, so the
     * fuse-speed check subsumes the flag). Filtering on the flag alone meant the interrupt never fired
     * for the ordinary case — the 2026-05-09 "still doesn't back off" report.
     */
    static boolean isFusing(CreeperEntity c) {
        return c.isIgnited() || c.getFuseSpeed() > 0;
    }

    private static CreeperEntity pickThreat(ServerPlayerEntity bot, ServerWorld world) {
        Box box = bot.getBoundingBox().expand(SCAN_RADIUS, SCAN_RADIUS / 2.0D, SCAN_RADIUS);
        List<CreeperEntity> candidates = world.getEntitiesByClass(
                CreeperEntity.class, box,
                c -> c.isAlive() && isFusing(c));
        if (candidates.isEmpty()) return null;

        CreeperEntity best = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        for (CreeperEntity c : candidates) {
            double dx = c.getX() - bot.getX();
            double dz = c.getZ() - bot.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq >= bestDistSq) continue;
            // LOS gate for distant creepers (the wall blocks both ignition and damage).
            if (Math.sqrt(distSq) >= LOS_REQUIRED_BEYOND && !bot.canSee(c)) continue;
            best = c;
            bestDistSq = distSq;
        }
        return best;
    }

    private static CreeperEntity resolveCreeper(ServerWorld world, UUID id) {
        if (id == null) return null;
        var e = world.getEntity(id);
        return (e instanceof CreeperEntity c) ? c : null;
    }

    private static boolean isStillThreat(ServerPlayerEntity bot, CreeperEntity creeper, long ticksSinceStart) {
        if (ticksSinceStart > MAX_BACKOFF_TICKS) return false;
        if (creeper == null || creeper.isRemoved() || !creeper.isAlive()) return false;
        if (!isFusing(creeper)) return false;
        // If creeper is in another world (rare), treat as resolved.
        return creeper.getEntityWorld() == bot.getEntityWorld();
    }

    private static void releaseBackoff(ServerPlayerEntity bot, BackoffState state, String reason) {
        UUID botId = bot.getUuid();
        ACTIVE.remove(botId);
        if (state != null && state.abortLatchOwned()) {
            // Per [[feedback-abort-latch-ownership]] — we set the latch, we clear it.
            TaskService.clearAbortLatch(botId);
        }
        BotActions.stop(bot);
        LOGGER.info("[creeper-defense] {} released ({}, after {} ticks)",
                bot.getName().getString(), reason,
                state != null ? state.bestDistanceTick() : -1);
    }

    private static double horizontalDistance(ServerPlayerEntity bot, CreeperEntity creeper) {
        double dx = creeper.getX() - bot.getX();
        double dz = creeper.getZ() - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Clear all backoff state — called on server shutdown to mirror other services. */
    public static void reset() {
        ACTIVE.clear();
    }
}
