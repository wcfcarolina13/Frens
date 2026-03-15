package net.wcfcarolina13.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Companion-specific QoL commands (permanent companion summon / home).
 *
 * <p>These are server-validated against survival recruitment + permanent companion state.
 */
final class BotCompanionCommands {

    private BotCompanionCommands() {
    }

    static ArgumentBuilder<ServerCommandSource, ?> build() {
        return CommandManager.literal("companion")
                .then(CommandManager.literal("come")
                        .executes(ctx -> modCommandRegistry.executeCompanionComeTargets(ctx, null))
                        .then(CommandManager.argument("bot", StringArgumentType.string())
                                .executes(ctx -> modCommandRegistry.executeCompanionComeTargets(
                                        ctx,
                                        StringArgumentType.getString(ctx, "bot")))))
                .then(CommandManager.literal("summon")
                        .executes(ctx -> modCommandRegistry.executeCompanionSummonTargets(ctx, null))
                        .then(CommandManager.argument("bot", StringArgumentType.string())
                                .executes(ctx -> modCommandRegistry.executeCompanionSummonTargets(
                                        ctx,
                                        StringArgumentType.getString(ctx, "bot")))))
                .then(CommandManager.literal("home")
                        .executes(ctx -> modCommandRegistry.executeCompanionHomeTargets(ctx, null))
                        .then(CommandManager.argument("bot", StringArgumentType.string())
                                .executes(ctx -> modCommandRegistry.executeCompanionHomeTargets(
                                        ctx,
                                        StringArgumentType.getString(ctx, "bot")))))
                .then(CommandManager.literal("open")
                        .executes(ctx -> modCommandRegistry.executeCompanionOpenTargets(ctx, null))
                        .then(CommandManager.argument("bot", StringArgumentType.string())
                                .executes(ctx -> modCommandRegistry.executeCompanionOpenTargets(
                                        ctx,
                                        StringArgumentType.getString(ctx, "bot")))))
                .then(CommandManager.literal("soulofender")
                        .executes(ctx -> modCommandRegistry.executeCompanionSoulOfEnderTargets(ctx, null))
                        .then(CommandManager.argument("bot", StringArgumentType.string())
                                .executes(ctx -> modCommandRegistry.executeCompanionSoulOfEnderTargets(
                                        ctx,
                                        StringArgumentType.getString(ctx, "bot")))));
    }
}
