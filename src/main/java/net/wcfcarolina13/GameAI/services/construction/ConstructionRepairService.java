package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry for active construction repair sessions.
 */
public final class ConstructionRepairService {

    private static final Logger LOGGER = LoggerFactory.getLogger("construction-repair-registry");
    private static final ConcurrentHashMap<UUID, ActiveBuildRepairSession> ACTIVE_REPAIRS = new ConcurrentHashMap<>();

    private ConstructionRepairService() {}

    public static void register(UUID botId, ActiveBuildRepairSession session) {
        if (botId == null || session == null) {
            return;
        }
        ACTIVE_REPAIRS.put(botId, session);
    }

    public static void clear(UUID botId) {
        if (botId == null) {
            return;
        }
        ACTIVE_REPAIRS.remove(botId);
    }

    public static boolean hasActiveSession(UUID botId) {
        return botId != null && ACTIVE_REPAIRS.containsKey(botId);
    }

    public static ActiveBuildRepairSession.RepairSweepResult noteDamageAndAttemptImmediateRepair(ServerPlayerEntity bot,
                                                                                                 ServerWorld world,
                                                                                                 BlockPos pos,
                                                                                                 String cause,
                                                                                                 double reachDistanceSq) {
        if (bot == null || world == null || pos == null) {
            return ActiveBuildRepairSession.RepairSweepResult.empty();
        }
        ActiveBuildRepairSession session = ACTIVE_REPAIRS.get(bot.getUuid());
        if (session == null) {
            return ActiveBuildRepairSession.RepairSweepResult.empty();
        }

        boolean trackedDamage = session.recordObservedDamage(world, pos, cause);
        if (!trackedDamage) {
            return ActiveBuildRepairSession.RepairSweepResult.empty();
        }

        ActiveBuildRepairSession.RepairSweepResult sweep = session.sweep(
                bot.getCommandSource(), bot, world, reachDistanceSq, true);

        LOGGER.info("construction immediate repair hook: bot={} pos={} cause={} repaired={} queued={} remainingQueue={} throttled={}",
                bot.getName().getString(),
                pos.toShortString(),
                cause == null ? "unknown" : cause,
                sweep.repairedCount(),
                sweep.queuedCount(),
                sweep.remainingQueue(),
                sweep.throttled());
        return sweep;
    }
}