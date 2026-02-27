package net.wcfcarolina13.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.wcfcarolina13.GameAI.services.LearningModeService;

final class BotLearningCommands {

    private BotLearningCommands() {
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildLearn() {
        return CommandManager.literal("learn")
                .then(CommandManager.literal("status")
                        .executes(context -> LearningModeService.executeStatus(context.getSource())))
                .then(CommandManager.literal("arm")
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> LearningModeService.executeArm(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "options")))))
                .then(CommandManager.literal("start")
                        .executes(context -> LearningModeService.executeStart(context.getSource(), null))
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> LearningModeService.executeStart(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "options")))))
                .then(CommandManager.literal("stop")
                        .executes(context -> LearningModeService.executeStop(context.getSource(), null))
                        .then(CommandManager.argument("outcome", StringArgumentType.word())
                                .executes(context -> LearningModeService.executeStop(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "outcome")))))
                .then(CommandManager.literal("mark")
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> LearningModeService.executeMark(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "options")))))
                .then(CommandManager.literal("list")
                        .executes(context -> LearningModeService.executeList(context.getSource(), null))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(context -> LearningModeService.executeList(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count")))))
                .then(CommandManager.literal("report")
                        .executes(context -> LearningModeService.executeReport(context.getSource(), "latest"))
                        .then(CommandManager.argument("session", StringArgumentType.string())
                                .executes(context -> LearningModeService.executeReport(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "session")))));
    }
}
