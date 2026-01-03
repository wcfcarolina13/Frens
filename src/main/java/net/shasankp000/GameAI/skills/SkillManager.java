package net.shasankp000.GameAI.skills;

import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.BotCommandStateService;
import net.shasankp000.GameAI.services.DebugFileLogger;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import net.shasankp000.GameAI.skills.impl.DirtShovelSkill;
import net.shasankp000.GameAI.skills.impl.DropSweepSkill;
import net.shasankp000.GameAI.skills.impl.FishingSkill;
import net.shasankp000.GameAI.skills.impl.HuntSkill;
import net.shasankp000.GameAI.skills.impl.MiningSkill;
import net.shasankp000.GameAI.skills.impl.ShelterSkill;
import net.shasankp000.GameAI.skills.impl.StripMineSkill;
import net.shasankp000.GameAI.skills.impl.WoodcutCleanupSkill;
import net.shasankp000.GameAI.skills.impl.WoodcutSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SkillManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-manager");
    private static final Map<String, Skill> SKILLS = new ConcurrentHashMap<>();
    private static final double DROP_SWEEP_RADIUS = 8.0;
    private static final double DROP_SWEEP_VERTICAL = 4.0;
    private static final int DROP_SWEEP_MAX_TARGETS = 6;
    private static final long DROP_SWEEP_MAX_DURATION_MS = 10_000L;

    // Ephemeral woodcut state (kept across woodcut/woodcut_cleanup runs, cleared when any other skill starts).
    private static final String WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY = "woodcut.scaffoldMemory.positions";
    private static final String WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY = "woodcut.scaffoldMemory.dimension";
    private static final String WOODCUT_SCAFFOLD_MEMORY_UPDATED_AT_KEY = "woodcut.scaffoldMemory.updatedAt";

    static {
        DebugFileLogger.log("SkillManager.staticInit start");
        register(new DirtShovelSkill());
        register(new CollectDirtSkill());
        register(new MiningSkill());
        register(new DropSweepSkill());
        register(new StripMineSkill());
        register(new WoodcutSkill());
        register(new WoodcutCleanupSkill());
        register(new ShelterSkill());
        register(new FishingSkill());
        register(new net.shasankp000.GameAI.skills.impl.HangoutSkill());
        register(new net.shasankp000.GameAI.skills.impl.FarmSkill());
        register(new net.shasankp000.GameAI.skills.impl.WoolSkill());
        register(new net.shasankp000.GameAI.skills.impl.FlareSkill());
        register(new HuntSkill());
        DebugFileLogger.log("SkillManager.staticInit end");
    }

    private SkillManager() {
    }

    public static void register(Skill skill) {
        if (skill != null) {
            DebugFileLogger.log("SkillManager.register " + skill.name());
        }
        SKILLS.put(skill.name(), skill);
    }

    public static SkillExecutionResult runSkill(String name, SkillContext context) {
        Skill skill = SKILLS.get(name);
        if (skill == null) {
            LOGGER.warn("Requested unknown skill '{}'", name);
            return SkillExecutionResult.failure("Skill '" + name + "' not available.");
        }
        ServerPlayerEntity botPlayer = context.botSource().getPlayer();
        String botName = botPlayer != null ? botPlayer.getName().getString() : "(unknown)";
        System.out.println("[SkillManager] start name=" + name + " bot=" + botName + " thread=" + Thread.currentThread().getName());
        DebugFileLogger.log("SkillManager.start name=" + name + " bot=" + botName + " thread=" + Thread.currentThread().getName());
        LOGGER.info("Skill '{}' starting for bot {} on thread {}", name, botName, Thread.currentThread().getName());
        UUID botUuid = botPlayer != null ? botPlayer.getUuid() : null;
        var ticketOpt = TaskService.beginSkill(name, context.botSource(), botUuid);
        if (ticketOpt.isEmpty()) {
            System.out.println("[SkillManager] blocked name=" + name + " bot=" + botName);
            DebugFileLogger.log("SkillManager.blocked name=" + name + " bot=" + botName);
            LOGGER.warn("Skill '{}' blocked for bot {}: active task already running", name, botName);
            return SkillExecutionResult.failure("Another skill is already running.");
        }
        TaskService.TaskTicket ticket = ticketOpt.get();

        // Clear woodcut scaffold memory when switching away from woodcut flows.
        try {
            if (context != null && context.sharedState() != null
                    && name != null
                    && !"woodcut".equalsIgnoreCase(name)
                    && !"woodcut_cleanup".equalsIgnoreCase(name)) {
                context.sharedState().remove(WOODCUT_SCAFFOLD_MEMORY_POSITIONS_KEY);
                context.sharedState().remove(WOODCUT_SCAFFOLD_MEMORY_DIMENSION_KEY);
                context.sharedState().remove(WOODCUT_SCAFFOLD_MEMORY_UPDATED_AT_KEY);
            }
        } catch (Exception ignored) {
        }

        // Tag task metadata early so tick-driven automation can make safe decisions.
        try {
            TaskService.Origin origin = inferOrigin(context);
            ticket.setOrigin(origin);
            ticket.setOpenEnded(inferOpenEnded(name, context));
        } catch (Exception ignored) {
        }
        TaskService.attachExecutingThread(ticket, Thread.currentThread());

        UUID resumeFollowUuid = null;
        net.minecraft.util.math.BlockPos resumeFixedGoal = null;
        double resumeStopRange = 0.0D;
        boolean resumeAllowRecoverySkills = true;
        if (botPlayer != null && BotEventHandler.getCurrentMode(botPlayer) == BotEventHandler.Mode.FOLLOW) {
            UUID currentFollow = BotEventHandler.getFollowTargetUuid(botPlayer);
            resumeFollowUuid = currentFollow;
            BotCommandStateService.State st = BotCommandStateService.stateFor(botPlayer);
            if (st != null) {
                resumeFixedGoal = st.followFixedGoal;
                resumeStopRange = st.followStopRange;
                resumeAllowRecoverySkills = st.comeAllowRecoverySkills;
            }
            BotEventHandler.stopFollowing(botPlayer);
        }

        BotEventHandler.setExternalOverrideActive(true);
        SkillExecutionResult result = SkillExecutionResult.failure("Skill '" + name + "' ended unexpectedly.");
        try {
            LOGGER.info("Skill '{}' executing for bot {}", name, botName);
            result = skill.execute(context);
            if (TaskService.isAbortRequested(botUuid)) {
                String reason = TaskService.getCancelReason(botUuid)
                        .orElse("Skill '" + name + "' paused due to nearby threat.");
                result = SkillExecutionResult.failure(reason);
            }
            return result;
        } catch (Throwable t) {
            LOGGER.error("Skill '{}' crashed: {}", name, t.getMessage(), t);
            result = SkillExecutionResult.failure("Skill '" + name + "' crashed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            return result;
        } finally {
            boolean abortRequested = TaskService.isAbortRequested(botUuid);
            String abortReason = TaskService.getCancelReason(botUuid).orElse("");
            String resultMsg = result != null ? result.message() : "null";
            DebugFileLogger.log("SkillManager.exit name=" + name + " bot=" + botName
                    + " success=" + (result != null && result.success())
                    + " abortRequested=" + abortRequested
                    + " reason=" + abortReason
                    + " msg=" + resultMsg);
            LOGGER.info("Skill '{}' exit: success={} abortRequested={} reason='{}' message='{}'",
                    name,
                    result != null && result.success(),
                    abortRequested,
                    abortReason,
                    resultMsg);
            try {
                // Only perform post-task drop_sweep if inventory isn't full and the skill permits it.
                // Woodcut handles its own sweep after completion to avoid tower disruption.
                if (botPlayer != null
                        && !abortRequested
                        && !isInventoryFull(botPlayer)
                        && !"woodcut".equalsIgnoreCase(name)
                        && !"shelter".equalsIgnoreCase(name)
                        && !"wool".equalsIgnoreCase(name)) {
                    DropSweeper.sweep(
                            context.botSource().withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS),
                            DROP_SWEEP_RADIUS,
                            DROP_SWEEP_VERTICAL,
                            DROP_SWEEP_MAX_TARGETS,
                            DROP_SWEEP_MAX_DURATION_MS
                    );
                } else if (botPlayer != null && !abortRequested && isInventoryFull(botPlayer)) {
                    LOGGER.info("Skipping post-task drop_sweep for '{}' - inventory full", name);
                }
            } catch (Exception sweepError) {
                LOGGER.warn("Drop sweep after skill '{}' failed: {}", name, sweepError.getMessage(), sweepError);
            }
            BotEventHandler.setExternalOverrideActive(false);
            if (botPlayer != null && !abortRequested) {
                if (resumeFixedGoal != null) {
                    ServerPlayerEntity target = resumeFollowUuid != null
                            ? context.botSource().getServer().getPlayerManager().getPlayer(resumeFollowUuid)
                            : null;
                    double stopRange = resumeStopRange > 0.0D ? resumeStopRange : 3.2D;
                    BotEventHandler.setComeModeWalk(botPlayer, target, resumeFixedGoal, stopRange, resumeAllowRecoverySkills);
                } else if (resumeFollowUuid != null) {
                    ServerPlayerEntity target = context.botSource().getServer().getPlayerManager().getPlayer(resumeFollowUuid);
                    if (target != null) {
                        BotEventHandler.setFollowMode(botPlayer, target);
                    }
                }
            }
            boolean success = result != null && result.success() && !abortRequested;
            TaskService.complete(ticket, success);
        }
    }

    public static boolean shouldAbortSkill(ServerPlayerEntity botPlayer) {
        UUID botUuid = botPlayer != null ? botPlayer.getUuid() : null;
        return Thread.currentThread().isInterrupted() || TaskService.isAbortRequested(botUuid);
    }

    public static boolean requestSkillPause(ServerPlayerEntity bot, String reason) {
        UUID uuid = bot != null ? bot.getUuid() : null;
        return TaskService.requestPause(uuid, reason);
    }

    private static boolean isInventoryFull(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return player.getInventory().getEmptySlot() == -1;
    }

    private static TaskService.Origin inferOrigin(SkillContext context) {
        if (context == null || context.parameters() == null) {
            return TaskService.Origin.COMMAND;
        }
        Object raw = context.parameters().get("_origin");
        if (raw instanceof String s) {
            if ("ambient".equalsIgnoreCase(s)) {
                return TaskService.Origin.AMBIENT;
            }
            if ("system".equalsIgnoreCase(s)) {
                return TaskService.Origin.SYSTEM;
            }
        }
        return TaskService.Origin.COMMAND;
    }

    /**
     * Best-effort classification for "open-ended" skills.
     * <p>
     * Open-ended tasks are eligible for sunset automation interruption.
     */
    private static boolean inferOpenEnded(String skillName, SkillContext context) {
        if (skillName == null || context == null) {
            return false;
        }
        Map<String, Object> params = context.parameters();
        if (params == null) {
            return false;
        }

        // Explicit override.
        Object explicit = params.get("open_ended");
        if (explicit instanceof Boolean b) {
            return b;
        }
        if (explicit instanceof String s) {
            if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) {
                return true;
            }
        }

        // Common option token pattern.
        Object opts = params.get("options");
        if (opts instanceof Iterable<?> iterable) {
            for (Object o : iterable) {
                if (o instanceof String str) {
                    if ("until_sunset".equalsIgnoreCase(str)
                            || "sunset".equalsIgnoreCase(str)
                            || "open_ended".equalsIgnoreCase(str)
                            || "open-ended".equalsIgnoreCase(str)) {
                        return true;
                    }
                }
            }
        }

        // Fishing is open-ended when no explicit count was provided.
        if ("fish".equalsIgnoreCase(skillName) || "fishing".equalsIgnoreCase(skillName)) {
            return !params.containsKey("count");
        }

        // Ambient-origin tasks are treated as open-ended by default.
        return inferOrigin(context) == TaskService.Origin.AMBIENT;
    }
}
