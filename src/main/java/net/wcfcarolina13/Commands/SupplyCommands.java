package net.wcfcarolina13.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.supply.SupplyChestRules;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Response;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.ResponseStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Choice;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /frens supply answer <requestId> once|always|no} and {@code /frens supply revoke <x> <y> <z>}.
 *
 * <p>A root of its own because {@code /bot} is op-only and a bot's owner need not be an operator.
 * Players only; there is no operator requirement. Authority is the request id plus the ledger's
 * owner check for {@code answer}, and the caller's own UUID for {@code revoke} (a player can only
 * withdraw permissions they gave). This class never opens a request or moves an item.
 */
public final class SupplyCommands {

    private static final String ARG_REQUEST_ID = "requestId";
    private static final String ARG_CHOICE = "choice";
    private static final String ARG_POS = "pos";

    private SupplyCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal(SupplyChestRules.COMMAND_ROOT)
                        .requires(source -> source.getPlayer() != null)
                        .then(CommandManager.literal(SupplyChestRules.COMMAND_SUPPLY)
                                .then(CommandManager.literal(SupplyChestRules.COMMAND_ANSWER)
                                        .then(CommandManager.argument(ARG_REQUEST_ID, StringArgumentType.word())
                                                .then(CommandManager.argument(ARG_CHOICE, StringArgumentType.word())
                                                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                                                Arrays.stream(Choice.values())
                                                                        .map(SupplyChestRules::choiceToken)
                                                                        .toList(),
                                                                builder))
                                                        .executes(SupplyCommands::answer))))
                                .then(CommandManager.literal(SupplyChestRules.COMMAND_REVOKE)
                                        .then(CommandManager.argument(ARG_POS, BlockPosArgumentType.blockPos())
                                                .executes(SupplyCommands::revoke))))));
    }

    private static int answer(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only a player can answer a companion's supply request."));
            return 0;
        }
        if (!SupplyRequestService.isRunning()) {
            source.sendError(Text.literal("Companion supplies aren't ready yet. Try again in a moment."));
            return 0;
        }
        UUID requestId;
        try {
            requestId = UUID.fromString(StringArgumentType.getString(context, ARG_REQUEST_ID));
        } catch (IllegalArgumentException malformed) {
            source.sendError(Text.literal("That isn't a supply request id. Use the buttons in the request message."));
            return 0;
        }
        Optional<Choice> choice = SupplyChestRules.parseChoice(StringArgumentType.getString(context, ARG_CHOICE));
        if (choice.isEmpty()) {
            source.sendError(Text.literal("Answer with once, always or no."));
            return 0;
        }
        Response response = SupplyRequestService.answer(player, requestId, choice.get());
        ResponseStatus status = response == null ? null : response.status();
        return status == ResponseStatus.GRANTED_ONCE || status == ResponseStatus.GRANTED_ALWAYS
                || status == ResponseStatus.REJECTED ? 1 : 0;
    }

    private static int revoke(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only a player can revoke a supply permission."));
            return 0;
        }
        if (!SupplyRequestService.isRunning()) {
            source.sendError(Text.literal("Companion supplies aren't ready yet. Try again in a moment."));
            return 0;
        }
        // Loaded: the partner half of a double chest must be readable to find the chest's key.
        BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(context, ARG_POS);
        String where = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        if (SupplyRequestService.revoke(player, pos)) {
            source.sendFeedback(() -> Text.literal(
                    "Revoked: your companions will ask again before taking from the chest at " + where + "."), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal(
                "Nothing to revoke: you hadn't allowed \"always\" for the chest at " + where + "."), false);
        return 0;
    }
}
