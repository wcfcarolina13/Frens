package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

final class BotInventoryCommands {

    private BotInventoryCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> build() {
        return CommandManager.literal("inventory")
                .executes(context -> modCommandRegistry.executeInventorySummaryTargetsV2(context, null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeInventorySummaryTargetsV2(context,
                                StringArgumentType.getString(context, "target"))))
                .then(CommandManager.literal("count")
                        .then(CommandManager.argument("item", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeInventoryCountTargets(context,
                                        null,
                                        StringArgumentType.getString(context, "item")))
                                .then(CommandManager.argument("targets", StringArgumentType.string())
                                        .executes(context -> modCommandRegistry.executeInventoryCountTargets(context,
                                                StringArgumentType.getString(context, "targets"),
                                                StringArgumentType.getString(context, "item"))))
                        )
                )
                .then(CommandManager.literal("save")
                        .executes(context -> modCommandRegistry.executeInventorySaveTargets(context, null))
                        .then(CommandManager.argument("targets", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeInventorySaveTargets(context,
                                        StringArgumentType.getString(context, "targets"))))
                )
                .then(CommandManager.literal("load")
                        .executes(context -> modCommandRegistry.executeInventoryLoadTargets(context, null))
                        .then(CommandManager.argument("targets", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeInventoryLoadTargets(context,
                                        StringArgumentType.getString(context, "targets"))))
                );
    }
}

