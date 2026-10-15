package net.wcfcarolina13.Commands;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.network.configNetworkManager;

public class configCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("configMan")
                    .requires(Frens::hasBotCommandPermission)
                    .executes(context -> {
                        // Ask the player's client to open Bot Controls with a fresh config snapshot
                        // (same packet on a dedicated or an integrated server).
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        configNetworkManager.sendOpenConfigPacket(player);
                        return 1;
                    })
            );
        });
    }
}
