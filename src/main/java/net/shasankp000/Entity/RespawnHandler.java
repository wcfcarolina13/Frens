package net.shasankp000.Entity;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.GameAI.BotEventHandler;

public class RespawnHandler {
    public static void registerRespawnListener(ServerPlayerEntity bot) {

        String botName = bot.getName().getString();

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (newPlayer.getName().getString().equals(botName)) {
                System.out.println("Detected bot respawn for " + newPlayer.getName().getString());
                BotEventHandler.onBotRespawn(newPlayer); // Ensure state reset and idle mode
                AutoFaceEntity.handleBotRespawn(newPlayer); // Restart tick loop
            }
        });
    }
}
