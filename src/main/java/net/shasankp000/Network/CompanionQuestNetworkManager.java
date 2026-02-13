package net.shasankp000.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.shasankp000.ChatUtils.BotDialoguePlayer;
import net.shasankp000.ChatUtils.DialogueTextMapper;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.services.SurvivalCompanionQuestService;
import net.shasankp000.GameAI.services.SurvivalRecruitmentService;

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
                    SurvivalCompanionQuestService.Response resp =
                            SurvivalCompanionQuestService.handleTopic(player, payload.botAlias(), payload.topicKey());

                    // Keep dialogue transcript flow unchanged, but also voice the first returned line in-world.
                    String firstLine = firstNonBlankLine(resp.linesJoined());
                    if (firstLine != null && !firstLine.isBlank()) {
                        ServerPlayerEntity bot = resolveBotByAlias(context.server(), payload.botAlias());
                        if (bot != null && !bot.isRemoved()) {
                            SoundEvent sound = DialogueTextMapper.lookup(firstLine);
                            if (sound != null) {
                                BotDialoguePlayer.playSoundForBotDetailed(bot, sound);
                            }
                        }
                    }

                    ServerPlayNetworking.send(player, new CompanionQuestResponsePayload(
                            payload.botAlias(),
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

    private static ServerPlayerEntity resolveBotByAlias(MinecraftServer server, String alias) {
        if (server == null || alias == null || alias.isBlank() || server.getPlayerManager() == null) {
            return null;
        }
        String trimmed = alias.trim();
        ServerPlayerEntity exact = server.getPlayerManager().getPlayer(trimmed);
        if (exact != null) {
            return exact;
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p == null) {
                continue;
            }
            String name = p.getName().getString();
            if (name != null && name.equalsIgnoreCase(trimmed)) {
                return p;
            }
        }
        return null;
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
