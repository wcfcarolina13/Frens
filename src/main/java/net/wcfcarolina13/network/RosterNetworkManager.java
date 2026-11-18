package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.RosterMembershipService;
import net.wcfcarolina13.GraphicalUserInterface.RosterScopePolicy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers {@link RosterRequestPayload} with the server's own roster, filtered by
 * {@link RosterVisibilityPolicy}. Without this, a player on a remote server saw the names in
 * their own local config, which has nothing to do with the server's companions.
 */
public final class RosterNetworkManager {

    private static volatile boolean REGISTERED = false;
    private static final Map<UUID, Long> LAST_REQUEST_MS = new ConcurrentHashMap<>();

    private RosterNetworkManager() {
    }

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RosterRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> answer(context.server(), context.player())));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler != null && handler.player != null) {
                LAST_REQUEST_MS.remove(handler.player.getUuid());
            }
        });
    }

    private static void answer(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST_MS.get(player.getUuid());
        if (!RosterVisibilityPolicy.requestAllowed(last == null ? 0L : last, now)) {
            return;
        }
        LAST_REQUEST_MS.put(player.getUuid(), now);

        boolean admin = BotAccessGate.isServerAdmin(player);
        ManualConfig config = Frens.CONFIG;
        List<String> saved = RosterMembershipService.savedAliases(config);
        List<String> visible = RosterVisibilityPolicy.visible(saved, alias -> {
            ManualConfig.BotOwnership owner = config == null ? null : config.getOwner(alias);
            return owner == null ? null : owner.ownerUuid();
        }, player.getUuidAsString(), admin);
        RosterMembershipService.Membership membership = RosterMembershipService.membership(server, config);
        RosterScopePolicy.Scope scope = RosterScopePolicy.scope(visible,
                membership.inThisWorld(), membership.withAnyWorld(), true);
        List<String> thisWorld = RosterVisibilityPolicy.capForPayload(scope.thisWorld());
        List<String> otherWorlds = RosterVisibilityPolicy.capForPayload(
                RosterVisibilityPolicy.otherWorldsFor(scope.otherWorlds(), admin));
        if (thisWorld.size() < scope.thisWorld().size()) {
            Frens.LOGGER.info("[roster] {} companions in this world, sending the first {}",
                    scope.thisWorld().size(), thisWorld.size());
        }
        ServerPlayNetworking.send(player, new RosterListPayload(thisWorld, otherWorlds, admin));
    }
}
