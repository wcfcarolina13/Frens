package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

final class BotEquipCommands {

    private BotEquipCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> build() {
        return CommandManager.literal("equip")
                .executes(context -> modCommandRegistry.executeEquip(context, modCommandRegistry.getActiveBotOrThrow(context)))
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .executes(context -> {
                            ServerPlayerEntity resolvedBot = EntityArgumentType.getPlayer(context, "bot");
                            if (resolvedBot == null) {
                                context.getSource().sendError(Text.literal("Error: Bot '" + StringArgumentType.getString(context, "bot") + "' not found or not a player."));
                                return 0;
                            }
                            return modCommandRegistry.executeEquip(context, resolvedBot);
                        })
                );
    }
}
