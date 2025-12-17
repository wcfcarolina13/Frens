package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

final class BotSkillCommands {

    private BotSkillCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildSkill() {
        return CommandManager.literal("skill")
                .then(CommandManager.argument("skill_name", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeSkillTargets(context,
                                StringArgumentType.getString(context, "skill_name"),
                                null))
                        .then(CommandManager.argument("arguments", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context,
                                        StringArgumentType.getString(context, "skill_name"),
                                        StringArgumentType.getString(context, "arguments"))))
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildFish() {
        return CommandManager.literal("fish")
                .executes(context -> modCommandRegistry.executeSkillTargets(context, "fish", null))
                .then(CommandManager.argument("arguments", StringArgumentType.greedyString())
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "fish",
                                StringArgumentType.getString(context, "arguments"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildShelter() {
        return CommandManager.literal("shelter")
                .then(CommandManager.literal("hovel")
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter", "hovel"))
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        "hovel " + StringArgumentType.getString(context, "options"))))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        StringArgumentType.getString(context, "target") + " hovel"))
                                .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                                StringArgumentType.getString(context, "target") + " hovel "
                                                        + StringArgumentType.getString(context, "options"))))
                        )
                )
                .then(CommandManager.literal("burrow")
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter", "burrow"))
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        "burrow " + StringArgumentType.getString(context, "options"))))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        StringArgumentType.getString(context, "target") + " burrow"))
                                .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                                StringArgumentType.getString(context, "target") + " burrow "
                                                        + StringArgumentType.getString(context, "options"))))
                        )
                );
    }
}
