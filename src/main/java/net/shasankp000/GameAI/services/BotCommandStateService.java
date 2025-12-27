package net.shasankp000.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.GameAI.BotEventHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage-2 refactor: centralized storage for per-bot command state (mode/follow/base/assist/shield).
 */
public final class BotCommandStateService {

    public static final class State {
        public BotEventHandler.Mode mode = BotEventHandler.Mode.IDLE;
        public UUID followTargetUuid;
        public boolean followNoTeleport;
        public double followStopRange = 0.0D;
        /**
         * When >0, FOLLOW mode keeps this horizontal distance from the commander while the bot has LoS
         * (i.e., "standoff" follow). When LoS breaks, the bot will still pursue/path-plan normally.
         */
        public double followStandoffRange = 0.0D;
        public BlockPos followFixedGoal;
        public double comeBestGoalDistSq = Double.NaN;
        public int comeTicksSinceBest = 0;
        public long comeNextSkillTick = 0L;
        public Vec3d baseTarget;
        public boolean assistAllies;
        public boolean shieldRaised;
        public long shieldDecisionTick;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private BotCommandStateService() {}

    public static State stateFor(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        return stateFor(bot.getUuid());
    }

    public static State stateFor(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return STATES.computeIfAbsent(uuid, ignored -> new State());
    }

    public static void clear(UUID uuid) {
        if (uuid == null) {
            return;
        }
        STATES.remove(uuid);
    }

    public static void clearAll() {
        STATES.clear();
    }
}
