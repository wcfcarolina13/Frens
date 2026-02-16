package net.shasankp000.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.AIPlayer;
import net.shasankp000.GameAI.services.SurvivalRecruitmentService;

/** Server-side networking glue for the survival recruitment flow. */
public final class SurvivalRecruitmentNetworkManager {

    private static volatile boolean REGISTERED = false;

    private SurvivalRecruitmentNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestRecruitmentDialoguePayload.ID, (payload, context) ->
                context.server().execute(() -> SurvivalRecruitmentService.handleRequestOpen(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(ConfirmRecruitmentPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    try {
                        AIPlayer.LOGGER.info("Received ConfirmRecruitmentPayload from {} botAlias='{}'", context.player().getName().getString(), payload.botAlias());
                    } catch (Throwable ignored) {
                    }
                    SurvivalRecruitmentService.handleConfirmRecruit(context.player(), payload.botAlias());
                }));

        ServerPlayNetworking.registerGlobalReceiver(RequestRecruitmentReplayPayload.ID, (payload, context) ->
            context.server().execute(() -> SurvivalRecruitmentService.handleRequestReplayRecruitment(context.player(), payload.botAlias())));

        ServerPlayNetworking.registerGlobalReceiver(ModeSelectionChoicePayload.ID, (payload, context) ->
                context.server().execute(() -> SurvivalRecruitmentService.handleModeSelectionChoice(context.player(), payload.questingMode())));
    }

    public static void sendRecruitmentState(ServerPlayerEntity player) {
        SurvivalRecruitmentService.sendRecruitmentState(player);
    }
}
