package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pure observability for the "bot gets stuck on a pressure plate" complaint.
 *
 * <p>No behavior change. Two log signals when conditions are met:</p>
 *
 * <ol>
 *   <li><b>Stuck near plate</b> — bot's block position hasn't changed for
 *       {@link #STAGNANT_THRESHOLD_TICKS} ticks AND an
 *       {@link AbstractPressurePlateBlock} is within {@link #PLATE_SEARCH_RADIUS}
 *       blocks of its feet. Indicates pathfinder may be confused about a
 *       plate-controlled door / gate / piston.</li>
 *   <li><b>Plate oscillation</b> — bot transitioned on/off a pressure plate
 *       {@link #OSCILLATION_TRANSITIONS_THRESHOLD} or more times within
 *       {@link #OSCILLATION_WINDOW_TICKS} ticks. Classic symptom of a route
 *       that depends on holding the plate (e.g. iron door) but the bot
 *       walks off, the door closes behind it, the bot turns back, etc.</li>
 * </ol>
 *
 * <p>Per-bot per-signal throttle: each event type logs at most once per
 * {@link #LOG_THROTTLE_TICKS} per bot to avoid flooding the server log.
 * Once we have a confirmed repro from the field, replace the log lines with
 * the actual fix (likely pathfinder-side awareness of plate-controlled
 * blockers ahead).</p>
 */
public final class BotPressurePlateDiagnosticService {

    private static final Logger LOGGER = LoggerFactory.getLogger("plate-diag");

    /** Tick cadence — 10 ticks (0.5 s) is fast enough to catch oscillation,
     *  slow enough to not be hot-path. */
    private static final int TICK_INTERVAL = 10;

    /** A bot is "stuck" if its block position hasn't changed for this many ticks. */
    private static final int STAGNANT_THRESHOLD_TICKS = 60;

    /** Cubic radius around the bot's feet to look for pressure plates. 2 covers
     *  the 1-block-ahead plate that would trip a door right in front of the bot. */
    private static final int PLATE_SEARCH_RADIUS = 2;

    /** Throttle: per-bot per-signal, don't re-log within this many ticks. */
    private static final int LOG_THROTTLE_TICKS = 600; // 30 s

    /** Window for counting plate on/off transitions. */
    private static final int OSCILLATION_WINDOW_TICKS = 100; // 5 s

    /** Trip oscillation logging when this many transitions happen inside the window. */
    private static final int OSCILLATION_TRANSITIONS_THRESHOLD = 3;

    private record BotState(BlockPos lastPos, int stagnantTicks, boolean wasOnPlate,
                            BlockPos lastPlatePos, Deque<Long> transitionTicks,
                            long lastStuckLogTick, long lastOscillationLogTick) {
        BotState withMovement(BlockPos pos) {
            return new BotState(pos, 0, wasOnPlate, lastPlatePos, transitionTicks,
                    lastStuckLogTick, lastOscillationLogTick);
        }
        BotState withStagnant(int n) {
            return new BotState(lastPos, n, wasOnPlate, lastPlatePos, transitionTicks,
                    lastStuckLogTick, lastOscillationLogTick);
        }
        BotState withPlateState(boolean onPlate, BlockPos platePos) {
            return new BotState(lastPos, stagnantTicks, onPlate, platePos, transitionTicks,
                    lastStuckLogTick, lastOscillationLogTick);
        }
        BotState withStuckLog(long tick) {
            return new BotState(lastPos, stagnantTicks, wasOnPlate, lastPlatePos, transitionTicks,
                    tick, lastOscillationLogTick);
        }
        BotState withOscillationLog(long tick) {
            return new BotState(lastPos, stagnantTicks, wasOnPlate, lastPlatePos, transitionTicks,
                    lastStuckLogTick, tick);
        }
    }

    private static final ConcurrentMap<UUID, BotState> STATES = new ConcurrentHashMap<>();

    private BotPressurePlateDiagnosticService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        long tick = server.getTicks();
        if (tick % TICK_INTERVAL != 0) return;
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                if (bot != null) STATES.remove(bot.getUuid());
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;
            tickBot(bot, world, tick);
        }
    }

    private static void tickBot(ServerPlayerEntity bot, ServerWorld world, long tick) {
        UUID id = bot.getUuid();
        BlockPos feet = bot.getBlockPos();
        BotState prev = STATES.get(id);
        if (prev == null) {
            STATES.put(id, new BotState(feet, 0, false, null, new ArrayDeque<>(), 0L, 0L));
            return;
        }

        // Movement / stagnant tracking.
        BotState s;
        if (!feet.equals(prev.lastPos())) {
            s = prev.withMovement(feet);
        } else {
            s = prev.withStagnant(prev.stagnantTicks() + TICK_INTERVAL);
        }

        // Plate on/off transition tracking.
        BlockPos platePos = findPlateAtFeet(world, feet);
        boolean onPlate = platePos != null;
        if (onPlate != s.wasOnPlate()) {
            s.transitionTicks().addLast(tick);
            // Trim transitions outside the window.
            while (!s.transitionTicks().isEmpty()
                    && tick - s.transitionTicks().peekFirst() > OSCILLATION_WINDOW_TICKS) {
                s.transitionTicks().pollFirst();
            }
            s = s.withPlateState(onPlate, platePos != null ? platePos : s.lastPlatePos());
            if (s.transitionTicks().size() >= OSCILLATION_TRANSITIONS_THRESHOLD
                    && tick - s.lastOscillationLogTick() >= LOG_THROTTLE_TICKS) {
                BlockPos here = s.lastPlatePos() != null ? s.lastPlatePos() : feet;
                LOGGER.info("[plate-osc] {} oscillated on/off pressure plate at {} ({} transitions in {}t)",
                        bot.getName().getString(), here.toShortString(),
                        s.transitionTicks().size(), OSCILLATION_WINDOW_TICKS);
                s = s.withOscillationLog(tick);
            }
        }

        // Stuck-near-plate logging.
        if (s.stagnantTicks() >= STAGNANT_THRESHOLD_TICKS
                && tick - s.lastStuckLogTick() >= LOG_THROTTLE_TICKS) {
            BlockPos nearbyPlate = findPlateNearby(world, feet, PLATE_SEARCH_RADIUS);
            if (nearbyPlate != null) {
                LOGGER.info("[plate-stuck] {} stagnant {}t near pressure plate at {} (feet={}, dim={})",
                        bot.getName().getString(), s.stagnantTicks(),
                        nearbyPlate.toShortString(), feet.toShortString(),
                        world.getRegistryKey().getValue());
                s = s.withStuckLog(tick);
            }
        }

        STATES.put(id, s);
    }

    private static BlockPos findPlateAtFeet(ServerWorld world, BlockPos feet) {
        if (!world.isChunkLoaded(feet)) return null;
        if (world.getBlockState(feet).getBlock() instanceof AbstractPressurePlateBlock) return feet;
        return null;
    }

    private static BlockPos findPlateNearby(ServerWorld world, BlockPos feet, int radius) {
        for (BlockPos pos : BlockPos.iterate(
                feet.add(-radius, -1, -radius),
                feet.add(radius, 1, radius))) {
            if (!world.isChunkLoaded(pos)) continue;
            if (world.getBlockState(pos).getBlock() instanceof AbstractPressurePlateBlock) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    public static void reset() {
        STATES.clear();
    }
}
