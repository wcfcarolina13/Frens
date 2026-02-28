package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;

import java.util.List;

/**
 * Thin delegating skill that runs the moat-digging phase independently of the
 * perimeter wall build. Looks up the singleton {@link FortifyVillageSkill} from
 * the skill registry and calls its {@code executeMoat()} method.
 *
 * <p>Invoked via {@code /bot skill fortify_moat [wallName]} or
 * the Construction Screen "Fortify Moat" button.
 */
public final class FortifyMoatSkill implements Skill {

    @Override
    public String name() {
        return "fortify_moat";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("No bot player available.");
        }
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        MinecraftServer server = world.getServer();

        // Look up the fortify village skill singleton
        Skill raw = SkillManager.getSkill("fortify_village");
        if (!(raw instanceof FortifyVillageSkill fortifySkill)) {
            return SkillExecutionResult.failure("FortifyVillageSkill not registered.");
        }

        // Parse optional wall name from arguments
        String wallName = getArgument(context);

        if (wallName == null || wallName.isBlank()) {
            // Auto-detect nearest wall
            String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
            List<FortificationPersistenceService.SavedFortification> walls =
                    FortificationPersistenceService.listForWorld(server, worldKey);
            if (walls.isEmpty()) {
                return SkillExecutionResult.failure("No saved walls found. Build one first with `/bot fortify`.");
            }
            // Pick nearest by center distance
            FortificationPersistenceService.SavedFortification nearest = null;
            double best = Double.MAX_VALUE;
            for (var w : walls) {
                double d = bot.getBlockPos().getSquaredDistance(w.getCenter());
                if (d < best) {
                    best = d;
                    nearest = w;
                }
            }
            wallName = nearest.getName();
        }

        return fortifySkill.executeMoat(source, bot, world, server, wallName);
    }

    private String getArgument(SkillContext context) {
        Object opts = context.parameters().get("options");
        if (opts instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object val : list) {
                if (val != null) {
                    if (!sb.isEmpty()) sb.append(" ");
                    sb.append(val.toString());
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        Object argObj = context.parameters().get("arguments");
        if (argObj instanceof String s && !s.isEmpty()) return s;
        return null;
    }
}
