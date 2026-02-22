package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.TaskService;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Server-side receiver for looked-at bot task peek requests. */
public final class BotTaskPeekNetworkManager {

    private static volatile boolean REGISTERED = false;

    private BotTaskPeekNetworkManager() {
    }

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(BotTaskPeekRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> handle(context.server(), context.player(), payload))
        );
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity requester, BotTaskPeekRequestPayload payload) {
        if (server == null || requester == null || requester.isRemoved() || requester instanceof createFakePlayer) {
            return;
        }
        String uuidRaw = payload != null ? payload.botUuid() : null;
        UUID botUuid;
        try {
            botUuid = uuidRaw != null ? UUID.fromString(uuidRaw.trim()) : null;
        } catch (Throwable ignored) {
            botUuid = null;
        }
        if (botUuid == null) {
            return;
        }

        ServerPlayerEntity bot = server.getPlayerManager() != null ? server.getPlayerManager().getPlayer(botUuid) : null;
        if (bot == null || bot.isRemoved() || !BotEventHandler.isRegisteredBot(bot)) {
            ServerPlayNetworking.send(requester,
                    new BotTaskPeekStatusPayload(botUuid.toString(), "bot", false, false, false, ""));
            return;
        }

        Optional<TaskService.ActiveTaskInfo> infoOpt = TaskService.getActiveTaskInfo(botUuid);
        boolean active = false;
        boolean paused = false;
        boolean returningHome = BotEventHandler.isReturningToBase(bot);
        String taskLabel = "";

        if (infoOpt.isPresent()) {
            TaskService.ActiveTaskInfo info = infoOpt.get();
            TaskService.State state = info.state();
            paused = state == TaskService.State.PAUSED;
            active = paused || state == TaskService.State.RUNNING;
            if (active) {
                taskLabel = humanizeTaskName(info.name());
            }
        }

        String alias = bot.getName() != null ? bot.getName().getString() : "bot";
        ServerPlayNetworking.send(requester,
                new BotTaskPeekStatusPayload(botUuid.toString(), alias, active, paused, returningHome, taskLabel));
    }

    private static String humanizeTaskName(String taskName) {
        if (taskName == null || taskName.isBlank()) {
            return "working";
        }
        String raw = taskName;
        if (raw.startsWith("skill:")) {
            raw = raw.substring("skill:".length());
        }
        raw = raw.replace('_', ' ').trim();
        if (raw.isEmpty()) {
            return "working";
        }
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "farm":
                return "farming";
            case "collect dirt":
                return "collecting dirt";
            case "drop sweep":
                return "running cleanup";
            case "woodcut":
                return "woodcutting";
            case "stripmine":
                return "strip mining";
            case "hangout":
                return "hanging out";
            case "hunt":
                return "hunting";
            case "fish":
                return "fishing";
            case "flowers":
                return "collecting flowers";
            case "leaf litter":
                return "collecting leaf litter";
            case "mushrooms":
                return "foraging mushrooms";
            case "feed animals":
                return "feeding animals";
            case "wander":
                return "wandering";
            default:
                return raw;
        }
    }
}
