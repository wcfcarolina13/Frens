package net.shasankp000.GameAI.skills.impl;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.services.AnimalFeedingService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.ProtectedZoneService;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

public final class FeedAnimalsSkill implements Skill {
    private static final Logger LOGGER = LoggerFactory.getLogger("skill-feed-animals");
    private static final int DEFAULT_RADIUS = 12;
    private static final int MAX_FEEDS = 2;

    @Override
    public String name() {
        return "feed_animals";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("World unavailable.");
        }

        int radius = getIntParameter(context, "radius", DEFAULT_RADIUS);
        boolean ambientMode = isAmbientMode(context);
        List<net.minecraft.entity.LivingEntity> targets =
                ambientMode
                        ? AnimalFeedingService.findAmbientFeedTargets(world, bot, radius)
                        : AnimalFeedingService.findLowHealthAnimals(world, bot.getBlockPos(), radius);
        if (targets.isEmpty()) {
            return SkillExecutionResult.failure(ambientMode
                    ? "No feedable animals nearby."
                    : "No low-health animals nearby.");
        }

        int fed = 0;
        for (net.minecraft.entity.LivingEntity target : targets) {
            if (fed >= MAX_FEEDS) {
                break;
            }
            if (ProtectedZoneService.isProtected(target.getBlockPos(), world, null)) {
                continue;
            }
            if (!AnimalFeedingService.isWithinFeedRange(bot, target)) {
                BlockPos targetPos = target.getBlockPos();
                MovementService.MovementPlan plan = new MovementService.MovementPlan(
                        MovementService.Mode.DIRECT,
                        targetPos,
                        targetPos,
                        null,
                        null,
                        null
                );
                MovementService.MovementResult move = MovementService.execute(
                        source,
                        bot,
                        plan,
                        SkillPreferences.teleportDuringSkills(bot),
                        true
                );
                if (!move.success() && !AnimalFeedingService.isWithinFeedRange(bot, target)) {
                    continue;
                }
            }
            if (AnimalFeedingService.feedIfPossible(bot, target, !ambientMode)) {
                fed++;
            }
        }

        if (fed == 0) {
            ChatUtils.sendSystemMessage(source, "I couldn't feed any animals.");
            return SkillExecutionResult.failure("No animals fed.");
        }
        return SkillExecutionResult.success("Fed " + fed + " animal" + (fed == 1 ? "" : "s") + ".");
    }

    private static boolean isAmbientMode(SkillContext context) {
        if (context == null || context.parameters() == null) {
            return false;
        }
        Object origin = context.parameters().get("_origin");
        if (origin == null) {
            return false;
        }
        return origin.toString().toLowerCase(Locale.ROOT).contains("ambient");
    }

    private static int getIntParameter(SkillContext context, String key, int def) {
        if (context == null || key == null) {
            return def;
        }
        Object raw = context.parameters().get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
