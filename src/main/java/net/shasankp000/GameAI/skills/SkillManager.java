package net.shasankp000.GameAI.skills;

import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.GameAI.skills.impl.DirtShovelSkill;
import net.shasankp000.GameAI.skills.impl.CollectDirtSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class SkillManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-manager");
    private static final Map<String, Skill> SKILLS = new ConcurrentHashMap<>();

    static {
        register(new DirtShovelSkill());
        register(new CollectDirtSkill());
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
        UUID resumeFollowUuid = null;
        if (botPlayer != null && BotEventHandler.getCurrentMode() == BotEventHandler.Mode.FOLLOW) {
            UUID currentFollow = BotEventHandler.getFollowTargetUuid();
            if (currentFollow != null) {
                resumeFollowUuid = currentFollow;
            }
            BotEventHandler.stopFollowing(botPlayer);
        }
        BotEventHandler.setExternalOverrideActive(true);
        try {
            return skill.execute(context);
        } finally {
            BotEventHandler.setExternalOverrideActive(false);
            if (botPlayer != null && resumeFollowUuid != null) {
                ServerPlayerEntity target = context.botSource().getServer().getPlayerManager().getPlayer(resumeFollowUuid);
                if (target != null) {
                    BotEventHandler.setFollowMode(botPlayer, target);
                }
            }
        }
    }
}
