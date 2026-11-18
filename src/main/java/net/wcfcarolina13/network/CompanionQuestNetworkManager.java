package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.wcfcarolina13.ChatUtils.BotDialoguePlayer;
import net.wcfcarolina13.ChatUtils.VoiceLineCategory;
import net.wcfcarolina13.ChatUtils.DialogueTextMapper;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.SurvivalCompanionQuestService;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;

/** Server-side networking glue for the survival companion questline dialogue. */
public final class CompanionQuestNetworkManager {

    private static volatile boolean REGISTERED = false;

    private CompanionQuestNetworkManager() {
    }

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(CompanionQuestTopicPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null || player.isRemoved() || payload == null) {
                        return;
                    }
                    // Bounded, safe-charset alias and a bounded topic key before any lookup. The
                    // alias is echoed back, so an unchecked one would travel unbounded.
                    String alias = payload.botAlias();
                    var refusal = CompanionQuestTopicPolicy.refusal(alias, payload.topicKey());
                    if (refusal.isPresent()) {
                        BotAccessGate.warnDenied(player, alias, "companion_quest_topic", refusal.get());
                        return;
                    }
                    // The target has to be a registered bot the sender may act on. A real player
                    // or someone else's bot is refused, so nobody can play a dialogue sound at an
                    // entity they do not control.
                    ServerPlayerEntity bot = resolveBotByAlias(context.server(), alias);
                    if (bot == null || !BotAccessGate.permits(player, bot, "companion_quest_topic")) {
                        if (bot == null) {
                            BotAccessGate.warnDenied(player, alias, "companion_quest_topic", "NO_SUCH_BOT");
                        }
                        return;
                    }
                    SurvivalCompanionQuestService.Response resp =
                            SurvivalCompanionQuestService.handleTopic(player, alias, payload.topicKey());

                    // Keep dialogue transcript flow unchanged, but also voice the first returned line in-world.
                    String firstLine = firstNonBlankLine(resp.linesJoined());
                    if (firstLine != null && !firstLine.isBlank() && !bot.isRemoved()) {
                        SoundEvent sound = DialogueTextMapper.lookup(firstLine);
                        if (sound != null) {
                            BotDialoguePlayer.playSoundForBotDetailed(bot, sound, VoiceLineCategory.TOPICS_QUESTS);
                        }
                    }

                    ServerPlayNetworking.send(player, new CompanionQuestResponsePayload(
                            alias,
                            resp.linesJoined(),
                            resp.stage(),
                            resp.permanent()
                    ));
                }));

        ServerPlayNetworking.registerGlobalReceiver(CompanionQuestStateRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null || player.isRemoved()) {
                        return;
                    }
                    var server = player.getCommandSource().getServer();
                    int stage = 0;
                    boolean permanent = false;

                    if (server != null && SurvivalRecruitmentService.isEnabled(server)) {
                        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
                        if (st != null && st.isRecruited()) {
                            String recruited = st.getBotAlias();
                            String asked = payload.botAlias();
                            if (asked == null || asked.isBlank() || (recruited != null && recruited.equalsIgnoreCase(asked.trim()))) {
                                stage = st.getCompanionQuestStage();
                                permanent = st.isPermanentCompanion();
                            }
                        }
                    }

                    ServerPlayNetworking.send(player, new CompanionQuestStatePayload(payload.botAlias(), stage, permanent));
                }));
    }

    /** The online registered Frens bot named {@code alias} (ignoring case), or null. Never a real player. */
    private static ServerPlayerEntity resolveBotByAlias(MinecraftServer server, String alias) {
        if (server == null || alias == null || alias.isBlank() || server.getPlayerManager() == null) {
            return null;
        }
        String trimmed = alias.trim();
        ServerPlayerEntity found = server.getPlayerManager().getPlayer(trimmed);
        if (found == null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (p == null) {
                    continue;
                }
                String name = p.getName().getString();
                if (name != null && name.equalsIgnoreCase(trimmed)) {
                    found = p;
                    break;
                }
            }
        }
        if (found == null || found.isRemoved()) {
            return null;
        }
        if (!(found instanceof createFakePlayer) || !BotEventHandler.isRegisteredBot(found)) {
            return null;
        }
        return found;
    }

    private static String firstNonBlankLine(String linesJoined) {
        if (linesJoined == null || linesJoined.isBlank()) {
            return null;
        }
        String[] parts = linesJoined.split("\\n");
        for (String p : parts) {
            if (p != null) {
                String trimmed = p.trim();
                if (!trimmed.isBlank()) {
                    return trimmed;
                }
            }
        }
        return null;
    }
}
