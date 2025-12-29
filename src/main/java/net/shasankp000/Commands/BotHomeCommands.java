package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

/**
 * Home/base related commands.
 */
final class BotHomeCommands {

    private BotHomeCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildAutoReturnSunset() {
        return CommandManager.literal("auto_return_sunset")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetToggleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target")))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildAutoReturnSunsetGuardPatrolEligible() {
        return CommandManager.literal("auto_return_sunset_guard_patrol")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetGuardPatrolSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetGuardPatrolSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetGuardPatrolSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetGuardPatrolSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetGuardPatrolToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetGuardPatrolToggleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target")))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildAutoReturnSunsetPreferLastBed() {
        return CommandManager.literal("auto_return_sunset_prefer_last_bed")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetPreferLastBedSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetPreferLastBedSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetPreferLastBedSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetPreferLastBedSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeAutoReturnSunsetPreferLastBedToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSunsetPreferLastBedToggleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target")))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildIdleHobbies() {
        return CommandManager.literal("idle_hobbies")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeIdleHobbiesSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeIdleHobbiesSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("on_and_idle")
                        .executes(context -> modCommandRegistry.executeIdleHobbiesSetAndIdleTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeIdleHobbiesSetAndIdleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeIdleHobbiesSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeIdleHobbiesSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeIdleHobbiesToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeIdleHobbiesToggleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target")))))
                .then(CommandManager.literal("toggle_and_idle")
                        .executes(context -> modCommandRegistry.executeIdleHobbiesToggleAndIdleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeIdleHobbiesToggleAndIdleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target")))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildIdleNow() {
        return CommandManager.literal("idle_now")
                .executes(context -> modCommandRegistry.executeIdleNowTargets(context, null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeIdleNowTargets(
                                context,
                                StringArgumentType.getString(context, "target"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildBase() {
        return CommandManager.literal("base")
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("label", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeBaseSet(context,
                                        StringArgumentType.getString(context, "label")))))
                .then(CommandManager.literal("rename")
                        .then(CommandManager.argument("old_label", StringArgumentType.string())
                                .then(CommandManager.argument("new_label", StringArgumentType.string())
                                        .executes(context -> modCommandRegistry.executeBaseRename(
                                                context,
                                                StringArgumentType.getString(context, "old_label"),
                                                StringArgumentType.getString(context, "new_label"))))))
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("label", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeBaseRemove(context,
                                        StringArgumentType.getString(context, "label")))))
                .then(CommandManager.literal("list")
                        .executes(modCommandRegistry::executeBaseList));
    }
}
