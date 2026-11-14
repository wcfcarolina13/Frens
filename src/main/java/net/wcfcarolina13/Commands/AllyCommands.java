package net.wcfcarolina13.Commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.services.PlayerAllianceService;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules;

import java.util.List;

/**
 * {@code /frens ally invite|revoke <player>} and {@code /frens ally list}.
 *
 * <p>Under the {@code /frens} root because {@code /bot} needs an operator or the host, and an
 * alliance needs both players to agree, so on a server or LAN the other player is usually not an
 * operator. Players only, no operator requirement: every action changes only the caller's own side
 * of an alliance (their invite, their bond), and a bond forms only when both players invite each
 * other. {@code /bot ally} runs the same code.
 */
public final class AllyCommands {

    public static final String COMMAND_ALLY = "ally";
    public static final String COMMAND_INVITE = "invite";
    public static final String COMMAND_REVOKE = "revoke";
    public static final String COMMAND_LIST = "list";
    private static final String ARG_PLAYER = "player";

    private AllyCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal(SupplyChestRules.COMMAND_ROOT)
                        .requires(source -> source.getPlayer() != null)
                        .then(CommandManager.literal(COMMAND_ALLY)
                                .then(CommandManager.literal(COMMAND_INVITE)
                                        .then(CommandManager.argument(ARG_PLAYER, EntityArgumentType.player())
                                                .executes(context -> invite(context,
                                                        EntityArgumentType.getPlayer(context, ARG_PLAYER)))))
                                .then(CommandManager.literal(COMMAND_REVOKE)
                                        .then(CommandManager.argument(ARG_PLAYER, EntityArgumentType.player())
                                                .executes(context -> revoke(context,
                                                        EntityArgumentType.getPlayer(context, ARG_PLAYER)))))
                                .then(CommandManager.literal(COMMAND_LIST)
                                        .executes(AllyCommands::list)))));
    }

    /** The command a player runs to invite, or accept an invite from, {@code playerName}. */
    public static String inviteCommand(String playerName) {
        return "/" + SupplyChestRules.COMMAND_ROOT + " " + COMMAND_ALLY + " " + COMMAND_INVITE + " " + playerName;
    }

    /** The command a player runs to decline an invite from, or leave an alliance with, {@code playerName}. */
    public static String revokeCommand(String playerName) {
        return "/" + SupplyChestRules.COMMAND_ROOT + " " + COMMAND_ALLY + " " + COMMAND_REVOKE + " " + playerName;
    }

    public static int invite(CommandContext<ServerCommandSource> context, ServerPlayerEntity target) throws CommandSyntaxException {
        ServerPlayerEntity self = context.getSource().getPlayerOrThrow();
        PlayerAllianceService.InviteResult r = PlayerAllianceService.invite(
                self.getUuid().toString(), self.getName().getString(),
                target.getUuid().toString(), target.getName().getString());
        String selfName = self.getName().getString();
        String targetName = target.getName().getString();
        switch (r) {
            case INVITED -> {
                ChatUtils.sendSystemMessage(context.getSource(), "Sent alliance invitation to " + targetName + ".");
                MutableText accept = Text.literal("[Accept]").styled(s -> s
                        .withColor(Formatting.GREEN)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(inviteCommand(selfName))));
                MutableText decline = Text.literal("[Decline]").styled(s -> s
                        .withColor(Formatting.RED)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(revokeCommand(selfName))));
                target.sendMessage(Text.literal(selfName + " has invited you to an alliance. ")
                        .append(accept)
                        .append(Text.literal(" "))
                        .append(decline), false);
            }
            case ALLIED_NOW -> {
                ChatUtils.sendSystemMessage(context.getSource(), "Alliance formed with " + targetName + ".");
                ChatUtils.sendSystemMessage(target.getCommandSource(), "Alliance formed with " + selfName + ".");
            }
            case ALREADY_BONDED -> ChatUtils.sendSystemMessage(context.getSource(),
                    "You're already allied with " + targetName + ".");
            case ALREADY_INVITED -> ChatUtils.sendSystemMessage(context.getSource(),
                    "You've already invited " + targetName + ", awaiting their response.");
            case SELF -> ChatUtils.sendSystemMessage(context.getSource(), "You can't ally with yourself.");
            case INVALID -> ChatUtils.sendSystemMessage(context.getSource(), "Couldn't resolve the target player.");
        }
        return r == PlayerAllianceService.InviteResult.INVITED
                || r == PlayerAllianceService.InviteResult.ALLIED_NOW ? 1 : 0;
    }

    public static int revoke(CommandContext<ServerCommandSource> context, ServerPlayerEntity target) throws CommandSyntaxException {
        ServerPlayerEntity self = context.getSource().getPlayerOrThrow();
        PlayerAllianceService.RevokeResult r = PlayerAllianceService.revoke(
                self.getUuid().toString(), target.getUuid().toString());
        String selfName = self.getName().getString();
        String targetName = target.getName().getString();
        switch (r) {
            case BOND_BROKEN -> {
                ChatUtils.sendSystemMessage(context.getSource(), "Alliance with " + targetName + " ended.");
                ChatUtils.sendSystemMessage(target.getCommandSource(), "Alliance with " + selfName + " ended.");
            }
            case INVITE_CANCELED -> ChatUtils.sendSystemMessage(context.getSource(),
                    "Canceled alliance invitation with " + targetName + ".");
            case NOT_FOUND -> ChatUtils.sendSystemMessage(context.getSource(),
                    "No alliance or pending invitation with " + targetName + ".");
        }
        return r == PlayerAllianceService.RevokeResult.NOT_FOUND ? 0 : 1;
    }

    public static int list(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity self = context.getSource().getPlayerOrThrow();
        PlayerAllianceService.AllianceSnapshot snap = PlayerAllianceService.snapshot(self.getUuid().toString());
        ChatUtils.sendSystemMessage(context.getSource(),
                "Allies (" + snap.bonds().size() + "): " + format(snap.bonds()));
        ChatUtils.sendSystemMessage(context.getSource(),
                "Incoming invites (" + snap.incomingInvites().size() + "): " + format(snap.incomingInvites()));
        ChatUtils.sendSystemMessage(context.getSource(),
                "Outgoing invites (" + snap.outgoingInvites().size() + "): " + format(snap.outgoingInvites()));
        return 1;
    }

    private static String format(List<PlayerAllianceService.NamedUuid> list) {
        if (list.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).name());
        }
        return sb.toString();
    }
}
