package net.shasankp000.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

final class BotUtilityCommands {

    private BotUtilityCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildDirection() {
        return CommandManager.literal("direction")
                .then(CommandManager.literal("reset")
                        .executes(context -> modCommandRegistry.executeDirectionReset(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeDirectionReset(context,
                                        StringArgumentType.getString(context, "target"))))
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildZone() {
        return CommandManager.literal("zone")
                .then(CommandManager.literal("protect")
                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 100))
                                .executes(context -> modCommandRegistry.executeZoneProtect(context,
                                        IntegerArgumentType.getInteger(context, "radius"), null))
                                .then(CommandManager.argument("label", StringArgumentType.string())
                                        .executes(context -> modCommandRegistry.executeZoneProtect(context,
                                                IntegerArgumentType.getInteger(context, "radius"),
                                                StringArgumentType.getString(context, "label"))))
                        )
                )
                .then(CommandManager.literal("remove")
                        .then(CommandManager.argument("label", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeZoneRemove(context,
                                        StringArgumentType.getString(context, "label"))))
                )
                .then(CommandManager.literal("list")
                        .executes(modCommandRegistry::executeZoneList)
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildLookPlayer() {
        return CommandManager.literal("look_player")
                .executes(context -> modCommandRegistry.executeLookPlayerTargets(context, null, false))
                .then(CommandManager.literal("stop")
                        .executes(context -> modCommandRegistry.executeLookPlayerTargets(context, null, true))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeLookPlayerTargets(context,
                                        StringArgumentType.getString(context, "target"), true)))
                )
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeLookPlayerTargets(context,
                                StringArgumentType.getString(context, "target"), false)));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildFollow() {
        return CommandManager.literal("follow")
                .then(CommandManager.literal("stop")
                        .executes(context -> modCommandRegistry.executeFollowStopTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeFollowStopTargets(context,
                                        StringArgumentType.getString(context, "target"))))
                )
                .then(CommandManager.literal("toggle")
                        .executes(context -> modCommandRegistry.executeFollowToggleTargets(
                                context,
                                null,
                                context.getSource().getPlayer()))
                        .then(CommandManager.argument("bots", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeFollowToggleTargets(
                                        context,
                                        StringArgumentType.getString(context, "bots"),
                                        context.getSource().getPlayer()))
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(context -> modCommandRegistry.executeFollowToggleTargets(
                                                context,
                                                StringArgumentType.getString(context, "bots"),
                                                EntityArgumentType.getPlayer(context, "player"))))
                        )
                )
                .executes(context -> modCommandRegistry.executeFollowTargets(context, null, context.getSource().getPlayer()))
                .then(CommandManager.argument("bots", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeFollowTargets(context,
                                StringArgumentType.getString(context, "bots"),
                                context.getSource().getPlayer()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(context -> modCommandRegistry.executeFollowTargets(context,
                                        StringArgumentType.getString(context, "bots"),
                                        EntityArgumentType.getPlayer(context, "player"))))
                )
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(context -> modCommandRegistry.executeFollowTargets(context,
                                null,
                                EntityArgumentType.getPlayer(context, "player"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildFollowDistance() {
        return CommandManager.literal("follow-distance")
                .then(CommandManager.literal("reset")
                        .executes(context -> modCommandRegistry.executeFollowDistanceResetTargets(context, null))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeFollowDistanceResetTargets(context,
                                        StringArgumentType.getString(context, "target"))))
                )
                .then(CommandManager.argument("distance", DoubleArgumentType.doubleArg(1.0D, 64.0D))
                        // Default: active bot(s) follow the command issuer with a standoff.
                        .executes(context -> modCommandRegistry.executeFollowDistanceTargets(
                                context,
                                null,
                                context.getSource().getPlayer(),
                                DoubleArgumentType.getDouble(context, "distance")))
                        .then(CommandManager.argument("bots", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeFollowDistanceTargets(
                                        context,
                                        StringArgumentType.getString(context, "bots"),
                                        context.getSource().getPlayer(),
                                        DoubleArgumentType.getDouble(context, "distance")))
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(context -> modCommandRegistry.executeFollowDistanceTargets(
                                                context,
                                                StringArgumentType.getString(context, "bots"),
                                                EntityArgumentType.getPlayer(context, "player"),
                                                DoubleArgumentType.getDouble(context, "distance"))))
                        )
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildFollowCheck() {
        return CommandManager.literal("follow_check")
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .executes(context -> modCommandRegistry.executeFollowCheck(
                                context,
                                EntityArgumentType.getPlayer(context, "bot"),
                                null))
                        .then(CommandManager.argument("expected", StringArgumentType.word())
                                .executes(context -> modCommandRegistry.executeFollowCheck(
                                        context,
                                        EntityArgumentType.getPlayer(context, "bot"),
                                        StringArgumentType.getString(context, "expected")))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildSoundTest() {
        return CommandManager.literal("sound_test")
                .executes(context -> modCommandRegistry.executeSoundTestTargets(context, null))
                .then(CommandManager.argument("bots", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeSoundTestTargets(
                                context,
                                StringArgumentType.getString(context, "bots"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildTestChatter() {
        return CommandManager.literal("testchatter")
                .executes(context -> modCommandRegistry.executeTestChatterTargets(context, null))
                .then(CommandManager.argument("bots", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeTestChatterTargets(
                                context,
                                StringArgumentType.getString(context, "bots"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildDialogueTest() {
        return CommandManager.literal("dialogue_test")
                .then(CommandManager.argument("bot", EntityArgumentType.player())
                        .then(CommandManager.argument("trigger_key", StringArgumentType.word())
                                .executes(context -> modCommandRegistry.executeDialogueTest(
                                        context,
                                        EntityArgumentType.getPlayer(context, "bot"),
                                        StringArgumentType.getString(context, "trigger_key"),
                                        null))
                                .then(CommandManager.argument("line_id", StringArgumentType.word())
                                        .executes(context -> modCommandRegistry.executeDialogueTest(
                                                context,
                                                EntityArgumentType.getPlayer(context, "bot"),
                                                StringArgumentType.getString(context, "trigger_key"),
                                                StringArgumentType.getString(context, "line_id"))))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildChatCheck() {
        return CommandManager.literal("chat_check")
                .then(CommandManager.literal("inspect")
                        .then(CommandManager.argument("raw_message", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeChatCheck(
                                        context,
                                        StringArgumentType.getString(context, "raw_message"),
                                        null))))
                .then(CommandManager.literal("assert")
                        .then(CommandManager.argument("expected", StringArgumentType.word())
                                .then(CommandManager.argument("raw_message", StringArgumentType.greedyString())
                                        .executes(context -> modCommandRegistry.executeChatCheck(
                                                context,
                                                StringArgumentType.getString(context, "raw_message"),
                                                StringArgumentType.getString(context, "expected"))))));
    }
}
