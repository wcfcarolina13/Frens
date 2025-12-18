package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

final class BotCombatCommands {

    private BotCombatCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildAssist() {
        return CommandManager.literal("fight_with_me")
                .then(CommandManager.argument("mode", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeAssistToggle(context, modCommandRegistry.getActiveBotOrThrow(context), modCommandRegistry.parseAssistMode(StringArgumentType.getString(context, "mode"))))
                )
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .then(CommandManager.argument("mode", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAssistToggle(context, EntityArgumentType.getPlayer(context, "bot"), modCommandRegistry.parseAssistMode(StringArgumentType.getString(context, "mode"))))
                        )
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildDefend() {
        return CommandManager.literal("defend")
                // Shorthand: /bot defend on|off [targets]
                .then(CommandManager.argument("mode", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeDefendTargets(
                                context,
                                StringArgumentType.getString(context, "mode"),
                                null))
                        .then(CommandManager.argument("targets", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeDefendTargets(
                                        context,
                                        StringArgumentType.getString(context, "mode"),
                                        StringArgumentType.getString(context, "targets"))))
                )
                .then(CommandManager.literal("nearby")
                        // Shorthand: /bot defend nearby on|off [targets]
                        .then(CommandManager.argument("mode", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeDefendTargets(
                                        context,
                                        StringArgumentType.getString(context, "mode"),
                                        null))
                                .then(CommandManager.argument("targets", StringArgumentType.string())
                                        .executes(context -> modCommandRegistry.executeDefendTargets(
                                                context,
                                                StringArgumentType.getString(context, "mode"),
                                                StringArgumentType.getString(context, "targets"))))
                        )
                        .then(CommandManager.literal("bots")
                                .then(CommandManager.argument("mode", StringArgumentType.string())
                                        .executes(context -> modCommandRegistry.executeDefendTargets(
                                                context,
                                                StringArgumentType.getString(context, "mode"),
                                                null))
                                        .then(CommandManager.argument("targets", StringArgumentType.string())
                                                .executes(context -> modCommandRegistry.executeDefendTargets(
                                                        context,
                                                        StringArgumentType.getString(context, "mode"),
                                                        StringArgumentType.getString(context, "targets"))))
                                )
                        )
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildStance() {
        return CommandManager.literal("stance")
                .then(CommandManager.argument("style", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeCombatStyle(context, modCommandRegistry.getActiveBotOrThrow(context), modCommandRegistry.parseCombatStyle(StringArgumentType.getString(context, "style"))))
                )
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .then(CommandManager.argument("style", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeCombatStyle(context, EntityArgumentType.getPlayer(context, "bot"), modCommandRegistry.parseCombatStyle(StringArgumentType.getString(context, "style"))))
                        )
                );
    }
}
