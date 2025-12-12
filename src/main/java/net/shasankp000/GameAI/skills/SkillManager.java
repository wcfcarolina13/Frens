package net.shasankp000.GameAI.skills;

import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.TaskService;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import net.shasankp000.GameAI.skills.impl.DirtShovelSkill;
import net.shasankp000.GameAI.skills.impl.DropSweepSkill;
import net.shasankp000.GameAI.skills.impl.MiningSkill;
import net.shasankp000.GameAI.skills.impl.ShelterSkill;
import net.shasankp000.GameAI.skills.impl.StripMineSkill;
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

    static {
        register(new DirtShovelSkill());
        register(new CollectDirtSkill());
        register(new MiningSkill());
        register(new DropSweepSkill());
        register(new StripMineSkill());
        register(new WoodcutSkill());
        register(new ShelterSkill());
        register(new net.shasankp000.GameAI.skills.impl.FarmSkill());
        register(new net.shasankp000.GameAI.skills.impl.WoolSkill());
    }

    private SkillManager() {
    }

    public static void register(Skill skill) {
        SKILLS.put(skill.name(), skill);
    }

    public static SkillExecutionResult runSkill(String name, SkillContext context) {
        Skill skill = SKILLS.get(name);
        if (skill == null) {
            LOGGER.warn("Requested unknown skill '{}'", name);
            return SkillExecutionResult.failure("Skill '" + name + "' not available.");
        }
        ServerPlayerEntity botPlayer = context.botSource().getPlayer();
        UUID botUuid = botPlayer != null ? botPlayer.getUuid() : null;
        var ticketOpt = TaskService.beginSkill(name, context.botSource(), botUuid);
        if (ticketOpt.isEmpty()) {
            return SkillExecutionResult.failure("Another skill is already running.");
        }
        TaskService.TaskTicket ticket = ticketOpt.get();

        UUID resumeFollowUuid = null;
        if (botPlayer != null && BotEventHandler.getCurrentMode(botPlayer) == BotEventHandler.Mode.FOLLOW) {
            UUID currentFollow = BotEventHandler.getFollowTargetUuid(botPlayer);
            if (currentFollow != null) {
                resumeFollowUuid = currentFollow;
            }
            BotEventHandler.stopFollowing(botPlayer);
        }

        BotEventHandler.setExternalOverrideActive(true);
        SkillExecutionResult result = SkillExecutionResult.failure("Skill '" + name + "' ended unexpectedly.");
        try {
            result = skill.execute(context);
            if (TaskService.isAbortRequested(botUuid)) {
                String reason = TaskService.getCancelReason(botUuid)
                        .orElse("Skill '" + name + "' paused due to nearby threat.");
                result = SkillExecutionResult.failure(reason);
            }
            return result;
        } finally {
            boolean abortRequested = TaskService.isAbortRequested(botUuid);
            try {
                // Only perform post-task drop_sweep if inventory isn't full and the skill permits it.
                // Woodcut handles its own sweep after completion to avoid tower disruption.
                if (botPlayer != null
                        && !abortRequested
                        && !isInventoryFull(botPlayer)
                        && !"woodcut".equalsIgnoreCase(name)
                        && !"shelter".equalsIgnoreCase(name)) {
                    DropSweeper.sweep(
                            context.botSource().withSilent().withMaxLevel(4),
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
            if (botPlayer != null && resumeFollowUuid != null && !abortRequested) {
                ServerPlayerEntity target = context.botSource().getServer().getPlayerManager().getPlayer(resumeFollowUuid);
                if (target != null) {
                    BotEventHandler.setFollowMode(botPlayer, target);
                }
            }
            boolean success = result != null && result.success() && !abortRequested;
            TaskService.complete(ticket, success);
        }
    }

    public static boolean shouldAbortSkill(ServerPlayerEntity botPlayer) {
        UUID botUuid = botPlayer != null ? botPlayer.getUuid() : null;
        return TaskService.isAbortRequested(botUuid);
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
}
