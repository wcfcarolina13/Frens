package net.shasankp000.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
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
}
