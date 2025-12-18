package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

final class BotMovementCommands {

    private BotMovementCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildCome() {
        return CommandManager.literal("come")
                .executes(context -> modCommandRegistry.executeComeTargets(context, null))
                .then(CommandManager.argument("bots", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeComeTargets(context,
                                StringArgumentType.getString(context, "bots"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildGuard() {
        return CommandManager.literal("guard")
                .executes(context -> modCommandRegistry.executeGuard(context, modCommandRegistry.getActiveBotOrThrow(context), modCommandRegistry.DEFAULT_GUARD_RADIUS))
                .then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(3.0D, 32.0D))
                        .executes(context -> modCommandRegistry.executeGuard(context, modCommandRegistry.getActiveBotOrThrow(context), DoubleArgumentType.getDouble(context, "radius")))
                )
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .executes(context -> modCommandRegistry.executeGuard(context, EntityArgumentType.getPlayer(context, "bot"), modCommandRegistry.DEFAULT_GUARD_RADIUS))
                        .then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(3.0D, 32.0D))
                                .executes(context -> modCommandRegistry.executeGuard(context, EntityArgumentType.getPlayer(context, "bot"), DoubleArgumentType.getDouble(context, "radius")))
                        )
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildPatrol() {
        return CommandManager.literal("patrol")
                .executes(context -> modCommandRegistry.executePatrol(context, modCommandRegistry.getActiveBotOrThrow(context), modCommandRegistry.DEFAULT_GUARD_RADIUS))
                .then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(3.0D, 32.0D))
                        .executes(context -> modCommandRegistry.executePatrol(context, modCommandRegistry.getActiveBotOrThrow(context), DoubleArgumentType.getDouble(context, "radius")))
                )
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .executes(context -> modCommandRegistry.executePatrol(context, EntityArgumentType.getPlayer(context, "bot"), modCommandRegistry.DEFAULT_GUARD_RADIUS))
                        .then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(3.0D, 32.0D))
                                .executes(context -> modCommandRegistry.executePatrol(context, EntityArgumentType.getPlayer(context, "bot"), DoubleArgumentType.getDouble(context, "radius")))
                        )
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildStay(String literal) {
        return CommandManager.literal(literal)
                .executes(context -> modCommandRegistry.executeStay(context, modCommandRegistry.getActiveBotOrThrow(context)))
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .executes(context -> modCommandRegistry.executeStay(context, EntityArgumentType.getPlayer(context, "bot"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildReturn(String literal) {
        return CommandManager.literal(literal)
                .executes(context -> modCommandRegistry.executeReturnToBase(context, modCommandRegistry.getActiveBotOrThrow(context), context.getSource().getPlayer()))
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .executes(context -> modCommandRegistry.executeReturnToBase(context, EntityArgumentType.getPlayer(context, "bot"), context.getSource().getPlayer())));
    }
}
