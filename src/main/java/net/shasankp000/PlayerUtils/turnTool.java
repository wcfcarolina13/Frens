package net.shasankp000.PlayerUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.shasankp000.CommandUtils;


public class turnTool {
    public static void turn(ServerCommandSource botSource, String direction) {

        MinecraftServer server = botSource.getServer();
        if (server == null) {
            return;
        }
        String botName = botSource.getName();
        CommandUtils.run(botSource, "player " + botName + " turn " + direction);

    }
}
