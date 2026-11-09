package net.wcfcarolina13.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.services.GuideChatAnswerService;
import net.wcfcarolina13.guide.GuideCatalog;
import net.wcfcarolina13.guide.GuideQuestionMatcher;
import net.wcfcarolina13.network.GuideOpenTopicPayload;

/**
 * {@code /frens guide <topic> [bot]}: opens the Guide on one card. It is what the
 * [Open in Guide] link on a fren's chat answer runs ({@link GuideChatAnswerService}).
 *
 * <p>Registered under the players-only {@code /frens} root ({@link SupplyCommands} registers the
 * same root with the same requirement; Brigadier merges the two). It only sends the asker a
 * payload that opens a screen: no world, bot or inventory change. An admin-only or quest-only
 * card the player may not see is refused, as the Guide screen would hide it.
 */
public final class GuideCommands {

    private static final String ROOT = "frens";
    private static final String GUIDE = "guide";
    private static final String ARG_TOPIC = "topic";
    private static final String ARG_BOT = "bot";

    private GuideCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal(ROOT)
                        .requires(source -> source.getPlayer() != null)
                        .then(CommandManager.literal(GUIDE)
                                .then(CommandManager.argument(ARG_TOPIC, StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                                GuideCatalog.topics().stream().map(GuideCatalog.Topic::id).toList(),
                                                builder))
                                        .executes(context -> open(context, ""))
                                        .then(CommandManager.argument(ARG_BOT, StringArgumentType.word())
                                                .executes(context -> open(context,
                                                        StringArgumentType.getString(context, ARG_BOT))))))));
    }

    private static int open(CommandContext<ServerCommandSource> context, String bot) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        String topicId = StringArgumentType.getString(context, ARG_TOPIC);
        GuideQuestionMatcher.Visibility visibility = GuideChatAnswerService.visibilityFor(player);
        boolean visible = false;
        for (GuideCatalog.Topic topic : GuideCatalog.topics()) {
            if (topic.id().equals(topicId)) {
                visible = (!topic.adminOnly() || visibility.admin())
                        && (!topic.questingOnly() || visibility.questingMode());
                break;
            }
        }
        if (!visible) {
            source.sendError(Text.literal("There's no Guide card called " + topicId + "."));
            return 0;
        }
        if (!ServerPlayNetworking.canSend(player, GuideOpenTopicPayload.ID)) {
            source.sendError(Text.literal("Your game can't open the Frens Guide (is Frens installed on your side?)."));
            return 0;
        }
        ServerPlayNetworking.send(player, new GuideOpenTopicPayload(topicId, bot == null ? "" : bot));
        return 1;
    }
}
