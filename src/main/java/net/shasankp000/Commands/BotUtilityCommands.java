package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

final class BotUtilityCommands {

    private BotUtilityCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildDirection() {
        return CommandManager.literal("direction")
                .then(CommandManager.literal("reset")
                        .executes(context -> modCommandRegistry.executeDirectionReset(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeDirectionReset(context,
                                        StringArgumentType.getString(context, "target"))))
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildZone() {
        return CommandManager.literal("zone")
                .then(CommandManager.literal("protect")
                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 100))
                                .executes(context -> modCommandRegistry.executeZoneProtect(context,
                                        IntegerArgumentType.getInteger(context, "radius"), null))
                                .then(CommandManager.argument("label", StringArgumentType.string())
                                        .executes(context -> modCommandRegistry.executeZoneProtect(context,
                                                IntegerArgumentType.getInteger(context, "radius"),
                                                StringArgumentType.getString(context, "label"))))
                        )
                )
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("label", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeZoneRemove(context,
                                        StringArgumentType.getString(context, "label"))))
                )
                .then(CommandManager.literal("list")
                        .executes(modCommandRegistry::executeZoneList)
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildLookPlayer() {
        return CommandManager.literal("look_player")
                .executes(context -> modCommandRegistry.executeLookPlayerTargets(context, null, false))
                .then(CommandManager.literal("stop")
                        .executes(context -> modCommandRegistry.executeLookPlayerTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeLookPlayerTargets(context,
                                        StringArgumentType.getString(context, "target"), true)))
                )
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeLookPlayerTargets(context,
                                StringArgumentType.getString(context, "target"), false)));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildFollow() {
        return CommandManager.literal("follow")
                .then(CommandManager.literal("stop")
                        .executes(context -> modCommandRegistry.executeFollowStopTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeFollowStopTargets(context,
                                        StringArgumentType.getString(context, "target"))))
                )
                .executes(context -> modCommandRegistry.executeFollowTargets(context, null, context.getSource().getPlayer()))
                .then(CommandManager.argument("bots", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeFollowTargets(context,
                                StringArgumentType.getString(context, "bots"),
                                context.getSource().getPlayer()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(context -> modCommandRegistry.executeFollowTargets(context,
                                        StringArgumentType.getString(context, "bots"),
                                        EntityArgumentType.getPlayer(context, "player"))))
                )
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> modCommandRegistry.executeFollowTargets(context,
                                null,
                                EntityArgumentType.getPlayer(context, "player"))));
    }
}

