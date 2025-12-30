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
        return buildComeLike("come");
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildRegroup() {
        return CommandManager.literal("regroup")
                .executes(context -> modCommandRegistry.executeRegroupTargets(context, null))
                .then(CommandManager.argument("bots", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeRegroupTargets(context,
                                StringArgumentType.getString(context, "bots"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildGoToLook() {
        return CommandManager.literal("go_to_look")
                .executes(context -> modCommandRegistry.executeGoToLookTargets(context, null))
                .then(CommandManager.argument("bots", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeGoToLookTargets(context,
                                StringArgumentType.getString(context, "bots"))));
    }

    private static ArgumentBuilder<ServerCommandSource, ?> buildComeLike(String literal) {
        return CommandManager.literal(literal)
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

    /**
     * Build shelter at the position where the player is looking.
     * Usage: /bot shelter_look <hovel|burrow> [bot]
     */
    static ArgumentBuilder<ServerCommandSource, ?> buildShelterLook() {
        return CommandManager.literal("shelter_look")
                .then(CommandManager.literal("hovel")
                        .executes(context -> modCommandRegistry.executeShelterLook(context, "hovel", null))
                        .then(CommandManager.argument("bot", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeShelterLook(context, "hovel",
                                        StringArgumentType.getString(context, "bot")))))
                .then(CommandManager.literal("burrow")
                        .executes(context -> modCommandRegistry.executeShelterLook(context, "burrow", null))
                        .then(CommandManager.argument("bot", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeShelterLook(context, "burrow",
                                        StringArgumentType.getString(context, "bot")))));
    }
}
