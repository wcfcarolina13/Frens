package net.wcfcarolina13.PathFinding;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.services.MovementService;
import org.slf4j.Logger;

import static net.wcfcarolina13.PathFinding.PathFinder.LOGGER;

public class GoTo {

    public static String goTo(ServerCommandSource botSource, int x, int y, int z, boolean sprint) {
        ServerPlayerEntity bot = botSource.getPlayer();

        if (bot == null) {
            System.out.println("Bot not found!");
            return "Bot not found!";
        }

        System.out.println("Found bot: " + botSource.getName());

        try {
            BlockPos target = new BlockPos(x, y, z);
            Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            Vec3d targetCenter = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            if (botPos.squaredDistanceTo(targetCenter) <= 0.75D * 0.75D) {
                return String.format("Bot moved to position - x: %d y: %d z: %d",
                        target.getX(), target.getY(), target.getZ());
            }

            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    target,
                    target,
                    null,
                    null,
                    bot.getHorizontalFacing()
            );
            MovementService.MovementResult res = MovementService.execute(botSource, bot, plan, false, true, false, false);

            String finalOutput = "";
            if (res.success()) {
                finalOutput = String.format("Bot moved to position - x: %d y: %d z: %d",
                        (int) bot.getX(), (int) bot.getY(), (int) bot.getZ());
            } else {
                finalOutput = "Error. Failed to reach destination: " + res.detail();
            }

            System.out.println("Final path output: " + finalOutput);
            return finalOutput;

        } catch (Exception e) {
            LOGGER.error("Error executing goTo: ", e);
            return "Failed to execute goTo: " + e.getMessage();
        }
    }
}
