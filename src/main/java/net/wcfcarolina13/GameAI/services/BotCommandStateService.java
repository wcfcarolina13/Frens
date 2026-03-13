package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotEventHandler;

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
        /**
         * When true, mounted-follow may take animals tied to fences (unleashing them).
         * When false, the bot will not disturb fence-tethered animals and will follow on foot instead.
         */
        public boolean unleashTetheredMounts = false;
        /**
         * When true, the bot will leash its mount with a lead (and tie to a fence if nearby) upon dismounting.
         * When false, the mount is left as-is after dismounting.
         */
        public boolean leashMountsOnDismount = true;
        public BlockPos followFixedGoal;
        public double comeBestGoalDistSq = Double.NaN;
        public int comeTicksSinceBest = 0;
        /**
         * Number of replan/reroute attempts made before launching mining recovery skills for /come.
         */
        public int comeRerouteAttempts = 0;
        /**
         * Tick gate for the next reroute attempt (keeps planner churn bounded when stuck).
         */
        public long comeNextRerouteTick = 0L;
        public long comeNextSkillTick = 0L;
        /**
         * True while a /come recovery skill launch is in flight (queued/running).
         * Used to prevent duplicate launch spam while stuck.
         */
        public boolean comeRecoverySkillInFlight = false;
        /**
         * Server tick when the current /come recovery launch was queued.
         * Used for stale in-flight timeout cleanup.
         */
        public long comeRecoverySkillStartTick = 0L;
        /** Total recovery skill attempts in this come session (pillar, stair, stripmine). */
        public int comeRecoverySkillAttempts = 0;
        /**
         * When true, the bot may launch short "recovery" skills (e.g., collect_dirt ascent / stripmine)
         * while trying to reach a fixed /come goal. When false ("safe regroup"), it will only path/walk
         * and will NOT start digging itself out, avoiding the common "ascend then get lost" failure.
         */
        public boolean comeAllowRecoverySkills = true;
        public Vec3d baseTarget;
        public boolean assistAllies;
        public boolean shieldRaised;
        public long shieldDecisionTick;
        /** Server tick when the last dimension-handoff notification was sent (throttle). */
        public long dimHandoffNotifiedTick = 0L;
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
