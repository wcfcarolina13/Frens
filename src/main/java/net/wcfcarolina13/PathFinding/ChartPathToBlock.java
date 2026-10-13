package net.wcfcarolina13.PathFinding;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.wcfcarolina13.CommandUtils;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.PlayerUtils.blockDetectionUnit;


public class ChartPathToBlock {

    /** Failure text returned when the Carpet {@code /player} command that drives the walk is unavailable. */
    public static final String CARPET_MISSING_MESSAGE =
            "Chart path needs the Carpet mod, which is not installed. The bot did not move.";

    /** Upper bound on walk steps (50 ms each, about 10 seconds) so the loop can never spin forever. */
    private static final int MAX_STEPS = 200;

    public static String chart(ServerPlayerEntity bot, BlockPos targetBlockPos, String blockType) {
        // The walk is driven by Carpet's /player command. Without Carpet the bot never moves, and the
        // loop below only exits when the block ahead is already opaque, so bail out up front.
        if (!CommandUtils.isCarpetLoaded()) {
            return CARPET_MISSING_MESSAGE;
        }
        ServerCommandSource botSource = bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS);
        String botName = bot.getName().getString();


        // Start micro nav
        for (int step = 0; step < MAX_STEPS; step++) {
            // Face the block
            LookController.faceBlock(bot, targetBlockPos);

            CommandUtils.run(botSource, "player " + botName + " move forward");

            // If the bot collides with a block, stop
            Vec3d nextPos = bot.getEntityPos().add(bot.getRotationVec(1.0f).multiply(0.1));

            // Convert manually to Vec3i
            Vec3i nextPosInt = new Vec3i(
                    MathHelper.floor(nextPos.x),
                    MathHelper.floor(nextPos.y),
                    MathHelper.floor(nextPos.z)
            );

            if (bot.getEntityWorld().getBlockState(new BlockPos(nextPosInt)).isOpaque()) {
                CommandUtils.run(botSource, "player " + botName + " stop");

                // Check if it’s the correct block
                BlockPos hitPos = blockDetectionUnit.detectBlocks(bot, blockType); // returns BlockPos instead of string
                if (hitPos.equals(targetBlockPos)) {
                    System.out.println("Bot is now in front of the target block!");
                    return "Bot is now in front of the target block! Bot is at " + bot.getBlockPos().getX() + " " + bot.getBlockPos().getY() + " " + bot.getBlockPos().getZ();
                } else {
                    System.out.println("Hit obstacle that is not target block! Need to adjust.");
                    return "Hit obstacle that is not target block! Need to adjust.";
                }
            }

            // Sleep for a short tick (pseudo)
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CommandUtils.run(botSource, "player " + botName + " stop");
                return "Chart path was interrupted before reaching the target block.";
            }
        }
        CommandUtils.run(botSource, "player " + botName + " stop");
        return "Chart path gave up after " + MAX_STEPS + " steps without reaching the target block.";
    }

}
