package net.wcfcarolina13.Commands;

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

    static ArgumentBuilder<ServerCommandSource, ?> buildAutoReturnSkipPermission() {
        return CommandManager.literal("auto_return_skip_permission")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeAutoReturnSkipPermissionSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSkipPermissionSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeAutoReturnSkipPermissionSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSkipPermissionSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeAutoReturnSkipPermissionToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoReturnSkipPermissionToggleTargets(
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

    static ArgumentBuilder<ServerCommandSource, ?> buildAutoHuntStarving() {
        return CommandManager.literal("auto_hunt_starving")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeAutoHuntStarvingSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoHuntStarvingSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeAutoHuntStarvingSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoHuntStarvingSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeAutoHuntStarvingToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeAutoHuntStarvingToggleTargets(
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

    static ArgumentBuilder<ServerCommandSource, ?> buildUnleashTethered() {
        return CommandManager.literal("unleash_tethered")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeUnleashTetheredSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeUnleashTetheredSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeUnleashTetheredSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeUnleashTetheredSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeUnleashTetheredToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeUnleashTetheredToggleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target")))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildLeashOnDismount() {
        return CommandManager.literal("leash_on_dismount")
                .then(CommandManager.literal("on")
                        .executes(context -> modCommandRegistry.executeLeashOnDismountSetTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeLeashOnDismountSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        true))))
                .then(CommandManager.literal("off")
                        .executes(context -> modCommandRegistry.executeLeashOnDismountSetTargets(context, null, false))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeLeashOnDismountSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        false))))
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeLeashOnDismountToggleTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeLeashOnDismountToggleTargets(
                                        context,
                                        StringArgumentType.getString(context, "target")))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildNavMode() {
        return CommandManager.literal("nav_mode")
                .then(CommandManager.literal("walk")
                        .executes(context -> modCommandRegistry.executeNavModeSetTargets(context, null, "WALK"))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeNavModeSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        "WALK"))))
                .then(CommandManager.literal("teleport")
                        .executes(context -> modCommandRegistry.executeNavModeSetTargets(context, null, "TELEPORT_DELAY"))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeNavModeSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        "TELEPORT_DELAY"))))
                .then(CommandManager.literal("fast_travel")
                        .executes(context -> modCommandRegistry.executeNavModeSetTargets(context, null, "TELEPORT_DELAY"))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeNavModeSetTargets(
                                        context,
                                        StringArgumentType.getString(context, "target"),
                                        "TELEPORT_DELAY"))));
    }
}
