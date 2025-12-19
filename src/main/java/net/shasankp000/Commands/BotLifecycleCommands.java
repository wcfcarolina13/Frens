package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

final class BotLifecycleCommands {

    private BotLifecycleCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildList() {
        return CommandManager.literal("list")
                .executes(modCommandRegistry::executeListBots);
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildDespawn() {
        return CommandManager.literal("despawn")
                .executes(context -> modCommandRegistry.executeDespawnTargets(context, (String) null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeDespawnTargets(context,
                                StringArgumentType.getString(context, "target"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildStop() {
        return CommandManager.literal("stop")
                .executes(context -> modCommandRegistry.executeStopTargets(context, (String) null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeStopTargets(context,
                                StringArgumentType.getString(context, "target"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildResume() {
        return CommandManager.literal("resume")
                .executes(context -> modCommandRegistry.executeResumeTargets(context, (String) null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeResumeTargets(context,
                                StringArgumentType.getString(context, "target"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildHeal() {
        return CommandManager.literal("heal")
                .executes(context -> modCommandRegistry.executeHealTargets(context, (String) null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeHealTargets(context,
                                StringArgumentType.getString(context, "target"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildSleep() {
        return CommandManager.literal("sleep")
                .executes(context -> modCommandRegistry.executeSleepTargets(context, (String) null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeSleepTargets(context,
                                StringArgumentType.getString(context, "target"))));
    }
}
