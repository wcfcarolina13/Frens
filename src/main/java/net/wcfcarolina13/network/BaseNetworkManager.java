package net.wcfcarolina13.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import net.wcfcarolina13.GameAI.services.LodestoneCompassService;
import net.wcfcarolina13.GameAI.services.MappedVillageService;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Networking glue for the in-inventory Bases manager screen. */
public final class BaseNetworkManager {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean REGISTERED = false;
    private static final Map<UUID, String> PLAYER_BOT_ALIAS_CONTEXT = new ConcurrentHashMap<>();

    private BaseNetworkManager() {}

    public record BaseDto(String kind, String label, int x, int y, int z, boolean home, String detailText, String ownerName, int radius) {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestBasesPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    String botAlias = sanitizeBotAlias(payload != null ? payload.query() : null);
                    rememberBotAliasContext(player, botAlias);
                    sendBasesList(player, botAlias);
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseSetPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases can only be saved in the Overworld.");
                        return;
                    }
                    String label = payload.label();
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Enter a base name first.");
                        return;
                    }
                    MinecraftServer server = player.getCommandSource().getServer();
                    if (labelInUse(server, world, label, null)) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "That name is already used by another base, wall, or village.");
                        return;
                    }
                    BlockPos pos = player.getBlockPos().toImmutable();
                    String ownerUuid = player.getUuid().toString();
                    String ownerName = player.getName().getString();
                    // New bases start at the default protection radius. Check that radius against
                    // existing other-owner / non-allied bases before committing so we fail fast
                    // with a clear message instead of silently creating a base that's already
                    // inside someone else's sphere.
                    if (!Frens.isOperator(player)) {
                        var overlap = BotHomeService.findOverlappingBase(server, world, pos,
                                BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS, ownerUuid, null);
                        if (overlap.isPresent()) {
                            ChatUtils.sendSystemMessage(player.getCommandSource(),
                                    formatOverlapReject(pos, BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS, overlap.get()));
                            sendBasesList(player, currentBotAliasContext(player));
                            return;
                        }
                    }
                    boolean ok = BotHomeService.addBase(server, world, label, pos, ownerUuid, ownerName);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Saved base '" + label + "' at " + pos.toShortString() + "."
                            : "Failed to save base.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseMapVillagePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Villages are only managed in the Overworld.");
                        return;
                    }
                    String label = payload != null ? payload.label() : null;
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Enter a village name first.");
                        return;
                    }
                    MinecraftServer server = player.getCommandSource().getServer();
                    if (labelInUse(server, world, label, null)) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "That name is already used by another base, wall, or village.");
                        return;
                    }
                    var layout = VillageFortificationLayoutService.generateLayout(world, player.getBlockPos(), 64);
                    if (layout.hullVertices() == null || layout.hullVertices().size() < 3) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Couldn't map a village here. Move closer to the settlement center and try again.");
                        return;
                    }
                    boolean ok = MappedVillageService.save(
                            server,
                            world,
                            MappedVillageService.create(
                                    label.trim(),
                                    FortificationPersistenceService.serverWorldKey(server, world),
                                    world.getRegistryKey().getValue().toString(),
                                    layout.center(),
                                    layout.hullVertices()
                            )
                    );
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Mapped village '" + label.trim() + "' with " + layout.hullVertices().size() + " perimeter vertices."
                            : "Failed to save mapped village '" + label.trim() + "'.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseRemovePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String label = payload.label();
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a base to remove.");
                        return;
                    }
                    MinecraftServer srv = player.getCommandSource().getServer();
                    if (!checkBaseEditPermission(player, srv, world, label)) {
                        sendBasesList(player, currentBotAliasContext(player));
                        return;
                    }
                    boolean removed = removeAnyEntry(srv, world, label);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), removed
                            ? "Removed '" + label + "'."
                            : "No base, wall, or village named '" + label + "' found.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseRenamePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String oldLabel = payload.oldLabel();
                    String newLabel = payload.newLabel();
                    if (oldLabel == null || oldLabel.isBlank() || newLabel == null || newLabel.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a base and enter a new name.");
                        return;
                    }
                    MinecraftServer srv = player.getCommandSource().getServer();
                    if (!checkBaseEditPermission(player, srv, world, oldLabel)) {
                        sendBasesList(player, currentBotAliasContext(player));
                        return;
                    }
                    if (labelInUse(srv, world, newLabel, oldLabel)) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "That name is already used by another base, wall, or village.");
                        return;
                    }
                    boolean ok = renameAnyEntry(srv, world, oldLabel, newLabel);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Renamed '" + oldLabel + "' -> '" + newLabel + "'."
                            : "Rename failed (does it exist? is the new name already used?).");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseSetRadiusPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String label = payload.label();
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a base first.");
                        return;
                    }
                    MinecraftServer srv = player.getCommandSource().getServer();
                    if (!checkBaseEditPermission(player, srv, world, label)) {
                        sendBasesList(player, currentBotAliasContext(player));
                        return;
                    }
                    int cap = BotHomeService.getMaxBaseRadius(srv, world);
                    int requested = payload.radius();
                    int radius = Math.max(1, Math.min(requested, cap));
                    if (requested > cap) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "Radius " + requested + " exceeds server cap of " + cap + "; clamped.");
                    }
                    // Check whether growing to `radius` would intrude on another owner's base.
                    // Pass `label` as excludeLabel so the base being resized doesn't conflict
                    // with itself. Operator bypass applies here too.
                    if (!Frens.isOperator(player)) {
                        BotHomeService.BaseEntry self = null;
                        for (BotHomeService.BaseEntry b : BotHomeService.listBases(srv, world)) {
                            if (b != null && b.label() != null && b.label().equalsIgnoreCase(label)) {
                                self = b;
                                break;
                            }
                        }
                        if (self != null) {
                            var overlap = BotHomeService.findOverlappingBase(srv, world, self.pos(),
                                    radius, player.getUuid().toString(), label);
                            if (overlap.isPresent()) {
                                ChatUtils.sendSystemMessage(player.getCommandSource(),
                                        formatOverlapReject(self.pos(), radius, overlap.get()));
                                sendBasesList(player, currentBotAliasContext(player));
                                return;
                            }
                        }
                    }
                    boolean ok = BotHomeService.setBaseRadius(srv, world, label, radius);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Set protection radius for '" + label + "' to " + radius + " blocks."
                            : "No base named '" + label + "' found.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseSetHomePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String botAlias = sanitizeBotAlias(payload != null ? payload.botAlias() : null);
                    String label = payload != null ? payload.label() : null;
                    ServerPlayerEntity bot = resolveControlledBot(player.getCommandSource().getServer(), botAlias, player);
                    if (bot == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bot '" + botAlias + "' is not available (or you don't own it).");
                        return;
                    }
                    if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)
                            || botWorld.getRegistryKey() != world.getRegistryKey()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Set Home only works when you and the bot are in the same dimension.");
                        return;
                    }
                    boolean ok = BotHomeService.setPreferredHomeBase(bot, label);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? bot.getName().getString() + " will treat '" + label + "' as home."
                            : "Couldn't set home. Does that base exist?");
                    rememberBotAliasContext(player, botAlias);
                    sendBasesList(player, botAlias);
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseGoToPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    String botAlias = sanitizeBotAlias(payload != null ? payload.botAlias() : null);
                    String label = payload != null ? payload.label() : null;
                    ServerPlayerEntity bot = resolveControlledBot(player.getCommandSource().getServer(), botAlias, player);
                    if (bot == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bot '" + botAlias + "' is not available (or you don't own it).");
                        return;
                    }
                    if (!(bot.getEntityWorld() instanceof ServerWorld botWorld)
                            || botWorld.getRegistryKey() != world.getRegistryKey()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), bot.getName().getString() + " can't go to that base across dimensions.");
                        return;
                    }

                    var baseOpt = BotHomeService.getBaseByLabel(player.getCommandSource().getServer(), world, label);
                    if (baseOpt.isEmpty()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "No base named '" + label + "' found.");
                        return;
                    }

                    String result = BotEventHandler.setReturnToBase(bot, Vec3d.ofCenter(baseOpt.get()));
                    ChatUtils.sendSystemMessage(player.getCommandSource(), result);
                    rememberBotAliasContext(player, botAlias);
                    sendBasesList(player, botAlias);
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseClaimWallPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Walls are only managed in the Overworld.");
                        return;
                    }
                    String label = payload != null ? payload.label() : null;
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a wall to claim.");
                        return;
                    }
                    MinecraftServer server = player.getCommandSource().getServer();
                    String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
                    var opt = FortificationPersistenceService.load(server, worldKey, label);
                    if (opt.isEmpty()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "No wall named '" + label + "' found.");
                        return;
                    }

                    var fort = opt.get();
                    UUID currentOwner = tryParseUuid(fort.getOwnerUuid());
                    boolean isOp = Frens.isOperator(player);
                    if (currentOwner != null && !currentOwner.equals(player.getUuid()) && !isOp) {
                        String ownerName = fort.getOwnerName() == null || fort.getOwnerName().isBlank()
                                ? "another player"
                                : fort.getOwnerName();
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "'" + label + "' is already claimed by " + ownerName + ".");
                        return;
                    }

                    boolean ok = FortificationPersistenceService.setOwner(
                            server,
                            worldKey,
                            label,
                            player.getUuid(),
                            player.getName().getString());
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Claimed wall '" + label + "' as " + player.getName().getString() + "."
                            : "Failed to claim wall '" + label + "'.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseUnclaimWallPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Walls are only managed in the Overworld.");
                        return;
                    }
                    String label = payload != null ? payload.label() : null;
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a wall to unclaim.");
                        return;
                    }
                    MinecraftServer server = player.getCommandSource().getServer();
                    String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
                    var opt = FortificationPersistenceService.load(server, worldKey, label);
                    if (opt.isEmpty()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "No wall named '" + label + "' found.");
                        return;
                    }

                    var fort = opt.get();
                    UUID currentOwner = tryParseUuid(fort.getOwnerUuid());
                    boolean isOp = Frens.isOperator(player);
                    if (currentOwner != null && !currentOwner.equals(player.getUuid()) && !isOp) {
                        String ownerName = fort.getOwnerName() == null || fort.getOwnerName().isBlank()
                                ? "another player"
                                : fort.getOwnerName();
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Only " + ownerName + " (or an operator) can unclaim this wall.");
                        return;
                    }

                    boolean ok = FortificationPersistenceService.clearOwner(server, worldKey, label);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Removed claim from wall '" + label + "'."
                            : "Failed to unclaim wall '" + label + "'.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseGrantWallAccessPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Walls are only managed in the Overworld.");
                        return;
                    }

                    String label = payload != null ? payload.label() : null;
                    String granteeRaw = payload != null ? payload.grantee() : null;
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a wall first.");
                        return;
                    }
                    if (granteeRaw == null || granteeRaw.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Enter a player name, UUID, or bot alias owner to permit.");
                        return;
                    }

                    MinecraftServer server = player.getCommandSource().getServer();
                    String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
                    var opt = FortificationPersistenceService.load(server, worldKey, label);
                    if (opt.isEmpty()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "No wall named '" + label + "' found.");
                        return;
                    }

                    var fort = opt.get();
                    UUID currentOwner = tryParseUuid(fort.getOwnerUuid());
                    boolean isOp = Frens.isOperator(player);
                    if (currentOwner == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Wall '" + label + "' is unclaimed. Claim it first.");
                        return;
                    }
                    if (!currentOwner.equals(player.getUuid()) && !isOp) {
                        String ownerName = fort.getOwnerName() == null || fort.getOwnerName().isBlank()
                                ? "another player"
                                : fort.getOwnerName();
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Only " + ownerName + " (or an operator) can grant access.");
                        return;
                    }

                    OwnerSubject subject = resolveOwnerSubject(server, granteeRaw);
                    if (subject == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Couldn't resolve owner from '" + granteeRaw + "'. Use player name, UUID, or a bot alias.");
                        return;
                    }
                    if (subject.ownerUuid().equals(currentOwner)) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "That owner already controls this wall.");
                        return;
                    }

                    boolean ok = FortificationPersistenceService.grantOwnerAccess(server, worldKey, label, subject.ownerUuid());
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Granted wall access for '" + label + "' to " + subject.ownerName() + "."
                            : subject.ownerName() + " already has access to '" + label + "'.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseRevokeWallAccessPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) {
                        return;
                    }
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Walls are only managed in the Overworld.");
                        return;
                    }

                    String label = payload != null ? payload.label() : null;
                    String granteeRaw = payload != null ? payload.grantee() : null;
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a wall first.");
                        return;
                    }
                    if (granteeRaw == null || granteeRaw.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Enter a player name, UUID, or bot alias owner to revoke.");
                        return;
                    }

                    MinecraftServer server = player.getCommandSource().getServer();
                    String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
                    var opt = FortificationPersistenceService.load(server, worldKey, label);
                    if (opt.isEmpty()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "No wall named '" + label + "' found.");
                        return;
                    }

                    var fort = opt.get();
                    UUID currentOwner = tryParseUuid(fort.getOwnerUuid());
                    boolean isOp = Frens.isOperator(player);
                    if (currentOwner == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Wall '" + label + "' is unclaimed.");
                        return;
                    }
                    if (!currentOwner.equals(player.getUuid()) && !isOp) {
                        String ownerName = fort.getOwnerName() == null || fort.getOwnerName().isBlank()
                                ? "another player"
                                : fort.getOwnerName();
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Only " + ownerName + " (or an operator) can revoke access.");
                        return;
                    }

                    OwnerSubject subject = resolveOwnerSubject(server, granteeRaw);
                    if (subject == null) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Couldn't resolve owner from '" + granteeRaw + "'. Use player name, UUID, or a bot alias.");
                        return;
                    }
                    if (subject.ownerUuid().equals(currentOwner)) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "You can't revoke the primary owner.");
                        return;
                    }

                    boolean ok = FortificationPersistenceService.revokeOwnerAccess(server, worldKey, label, subject.ownerUuid());
                    ChatUtils.sendSystemMessage(player.getCommandSource(), ok
                            ? "Revoked wall access for '" + label + "' from " + subject.ownerName() + "."
                            : subject.ownerName() + " did not have explicit access to '" + label + "'.");
                    sendBasesList(player, currentBotAliasContext(player));
                }));

        ServerPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.AdminMaxBaseRadiusPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    MinecraftServer srv = player.getCommandSource().getServer();
                    if (srv == null) return;
                    int requested = payload.value();
                    if (requested >= 0) {
                        // Set request: require operator permission. Non-ops silently fall through to a query.
                        if (!Frens.isOperator(player)) {
                            ChatUtils.sendSystemMessage(player.getCommandSource(),
                                    "Only operators can change the world max base radius.");
                        } else {
                            int applied = BotHomeService.setMaxBaseRadius(srv, world, requested);
                            ChatUtils.sendSystemMessage(player.getCommandSource(),
                                    "World max base radius set to " + applied + ".");
                        }
                    }
                    int current = BotHomeService.getMaxBaseRadius(srv, world);
                    ServerPlayNetworking.send(player, new net.wcfcarolina13.network.AdminMaxBaseRadiusStatePayload(
                            current, BotHomeService.HARD_MAX_BASE_RADIUS_LIMIT));
                }));

        ServerPlayNetworking.registerGlobalReceiver(BaseSetOwnerPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    ServerWorld world = player.getCommandSource().getWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Bases are only managed in the Overworld.");
                        return;
                    }
                    if (!Frens.isOperator(player)) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Only operators can reassign base ownership.");
                        return;
                    }
                    String label = payload.label();
                    if (label == null || label.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "Select a base first.");
                        return;
                    }
                    MinecraftServer srv = player.getCommandSource().getServer();
                    String newUuid = payload.newOwnerUuid();
                    String newName = payload.newOwnerName();
                    // Normalize blank strings to null so canEditBase treats cleared owners as legacy.
                    if (newUuid != null && newUuid.isBlank()) newUuid = null;
                    if (newName != null && newName.isBlank()) newName = null;
                    boolean ok = BotHomeService.setBaseOwner(srv, world, label, newUuid, newName);
                    if (ok) {
                        String display = newName != null ? newName
                                : (BotHomeService.SERVER_OWNER_UUID.equals(newUuid) ? BotHomeService.SERVER_OWNER_NAME : "(unowned)");
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "Reassigned '" + label + "' owner to " + display + ".");
                    } else {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "No base named '" + label + "' found.");
                    }
                    sendBasesList(player, currentBotAliasContext(player));
                }));
    }

    public static void sendBasesList(ServerPlayerEntity player) {
        sendBasesList(player, currentBotAliasContext(player));
    }

    public static void sendBasesList(ServerPlayerEntity player, String botAliasContext) {
        if (player == null || player.isRemoved()) {
            return;
        }
        ServerWorld world = player.getCommandSource().getWorld();
        if (world.getRegistryKey() != World.OVERWORLD) {
            // Still respond (empty list) so UI can clear itself.
            ServerPlayNetworking.send(player, new BasesListPayload("[]"));
            return;
        }

        String homeLabel = null;
        ServerPlayerEntity selectedBot = resolveControlledBot(player.getCommandSource().getServer(), botAliasContext, null);
        if (selectedBot != null) {
            homeLabel = BotHomeService.getPreferredHomeBaseLabel(selectedBot).orElse(null);
        }
        String homeNorm = homeLabel != null ? homeLabel.trim().toLowerCase(java.util.Locale.ROOT) : "";

        MinecraftServer server = player.getCommandSource().getServer();
        List<BotHomeService.BaseEntry> bases = BotHomeService.listBases(server, world);
        List<BaseDto> out = new ArrayList<>(bases.size());
        // PvP-style visibility: non-operators see only their own, allied, or server-owned
        // bases. Legacy/null-owner bases are hidden for non-ops (admin can reassign to
        // SERVER to expose as a public landmark). Operators see everything.
        boolean isOp = net.wcfcarolina13.Frens.isOperator(player);
        String viewerUuid = player.getUuid().toString();
        for (BotHomeService.BaseEntry b : bases) {
            if (b == null || b.pos() == null) continue;
            if (!isOp && !isBaseVisibleToViewer(b, viewerUuid)) continue;
            String label = b.label() != null ? b.label() : "";
            boolean home = !homeNorm.isBlank() && homeNorm.equals(label.trim().toLowerCase(java.util.Locale.ROOT));
            String displayOwner = b.ownerName() != null && !b.ownerName().isBlank()
                    ? b.ownerName() : null;
            out.add(new BaseDto("base", label, b.pos().getX(), b.pos().getY(), b.pos().getZ(), home, null, displayOwner, b.radius()));
        }

        // Include saved fortification walls — visible to owner, explicitly granted owners,
        // allied owners, and operators. Matches the "walls belong to the player that built
        // them and their alliances" rule.
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        java.util.Set<String> baseLabelsLower = new java.util.HashSet<>();
        for (BaseDto dto : out) {
            if (dto.label() != null) baseLabelsLower.add(dto.label().trim().toLowerCase(java.util.Locale.ROOT));
        }
        for (FortificationPersistenceService.SavedFortification f : FortificationPersistenceService.listForWorld(server, worldKey)) {
            String fName = f.getName();
            if (fName == null) continue;
            // Skip if already present as a base
            if (baseLabelsLower.contains(fName.trim().toLowerCase(java.util.Locale.ROOT))) continue;
            if (!isOp && !isWallVisibleToViewer(f, viewerUuid)) continue;
            net.minecraft.util.math.BlockPos center = f.getCenter();
            int totalEdges = f.getHullWallPoints().size();
            String status = f.isComplete() ? "complete"
                    : f.getCompletedEdges().size() + "/" + totalEdges + " edges";
            out.add(new BaseDto("wall", fName, center.getX(), center.getY(), center.getZ(), false, status, f.getOwnerName(), 0));
        }

        for (MappedVillageService.MappedVillage village : MappedVillageService.listForWorld(server, world)) {
            BlockPos center = village.getCenter();
            String detail = village.getVertexCount() + " vertices";
            out.add(new BaseDto("village", village.getName(), center.getX(), center.getY(), center.getZ(), false, detail, null, 0));
        }

        // Include lodestone compass destinations
        if (selectedBot != null) {
            for (LodestoneCompassService.LodestoneCompassEntry lc : LodestoneCompassService.findLodestoneCompasses(selectedBot)) {
                String compassLabel = lc.displayName();
                BlockPos lPos = lc.target().pos();
                String dim = lc.target().dimension().getValue().toString();
                boolean botSameDim = world.getRegistryKey().getValue().toString().equals(dim);
                String detail = botSameDim ? "Lodestone" : "Lodestone (" + dim + ")";
                boolean isHome = compassLabel.equalsIgnoreCase(
                        LodestoneCompassService.getHomeCompassName(selectedBot));
                out.add(new BaseDto("lodestone", compassLabel, lPos.getX(), lPos.getY(), lPos.getZ(),
                        isHome, detail, null, 0));
            }
        }

        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new BasesListPayload(json));
    }

    /**
     * Returns true if {@code player} may modify the base with the given label — i.e. the base
     * doesn't exist (likely a wall/village, whose ownership is gated elsewhere), OR the player
     * owns it, OR the player is an operator. On denial, sends a chat message explaining who
     * owns it; the caller still needs to short-circuit its own flow.
     */
    private static boolean checkBaseEditPermission(ServerPlayerEntity player,
                                                   MinecraftServer server,
                                                   ServerWorld world,
                                                   String label) {
        if (player == null || server == null || world == null || label == null) return false;
        String norm = label.trim().toLowerCase(java.util.Locale.ROOT);
        BotHomeService.BaseEntry target = null;
        for (BotHomeService.BaseEntry b : BotHomeService.listBases(server, world)) {
            if (b.label() != null && b.label().trim().toLowerCase(java.util.Locale.ROOT).equals(norm)) {
                target = b;
                break;
            }
        }
        if (target == null) {
            // Not a base — falls through so wall/village-specific handlers can apply their own gating.
            return true;
        }
        if (BotHomeService.canEditBase(player, target)) return true;

        String ownerName = target.ownerName() != null && !target.ownerName().isBlank()
                ? target.ownerName() : "the server";
        ChatUtils.sendSystemMessage(player.getCommandSource(),
                "Only " + ownerName + " (or an operator) can modify '" + label + "'.");
        return false;
    }

    /**
     * Builds a user-facing rejection message for a base overlap. Explains the conflicting base,
     * its owner (or "the server" for server-owned / legacy), and the minimum distance the user
     * would need to move to clear the collision — computed from current center-to-center distance
     * vs the sum of protection radii.
     */
    private static String formatOverlapReject(BlockPos proposedCenter, int proposedRadius,
                                               BotHomeService.BaseEntry conflicting) {
        String ownerDisplay = conflicting.ownerName() != null && !conflicting.ownerName().isBlank()
                ? conflicting.ownerName()
                : "the server";
        int otherRadius = conflicting.radius() > 0 ? conflicting.radius()
                : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
        double dist = Math.sqrt(conflicting.pos().getSquaredDistance(proposedCenter));
        double needed = (double) proposedRadius + (double) otherRadius;
        int moveBy = Math.max(1, (int) Math.ceil(needed - dist));
        String conflictLabel = conflicting.label() != null ? conflicting.label() : "(unnamed)";
        return "Overlaps " + ownerDisplay + "'s base '" + conflictLabel + "'. "
                + "Move at least " + moveBy + " blocks farther (or ally with " + ownerDisplay + ") and try again.";
    }

    /**
     * Visibility rule for bases (PvP-friendly default). Returns true if the non-op viewer should
     * see this base in their list. Operators bypass this and see everything.
     *
     * <p>Visible: viewer owns it, viewer is allied with the owner, or it's a server-owned base
     * (Spawn and admin-claimed public landmarks). Hidden: another player's base, legacy/null-owner
     * bases (admin can reassign to SERVER to expose as a public landmark).
     */
    private static boolean isBaseVisibleToViewer(BotHomeService.BaseEntry base, String viewerUuid) {
        if (base == null) return false;
        String ownerUuid = base.ownerUuid();
        if (ownerUuid == null || ownerUuid.isBlank()) return false;
        if (BotHomeService.SERVER_OWNER_UUID.equals(ownerUuid)) return true;
        if (viewerUuid != null && viewerUuid.equals(ownerUuid)) return true;
        return net.wcfcarolina13.GameAI.services.PlayerAllianceService.areAllied(viewerUuid, ownerUuid);
    }

    /**
     * Visibility rule for fortification walls. Owner, explicitly granted owners, and allied
     * owners see the wall. Operators bypass.
     */
    private static boolean isWallVisibleToViewer(FortificationPersistenceService.SavedFortification wall,
                                                  String viewerUuid) {
        if (wall == null || viewerUuid == null) return false;
        String ownerUuid = wall.getOwnerUuid();
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            if (viewerUuid.equals(ownerUuid)) return true;
            if (net.wcfcarolina13.GameAI.services.PlayerAllianceService.areAllied(viewerUuid, ownerUuid)) return true;
        }
        java.util.Set<String> granted = wall.getAllowedOwnerUuids();
        if (granted != null && granted.contains(viewerUuid)) return true;
        return false;
    }

    private static void rememberBotAliasContext(ServerPlayerEntity player, String botAlias) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return;
        }
        String alias = sanitizeBotAlias(botAlias);
        if (alias.isBlank()) {
            PLAYER_BOT_ALIAS_CONTEXT.remove(playerId);
            return;
        }
        PLAYER_BOT_ALIAS_CONTEXT.put(playerId, alias);
    }

    private static String currentBotAliasContext(ServerPlayerEntity player) {
        if (player == null || player.getUuid() == null) {
            return "";
        }
        return sanitizeBotAlias(PLAYER_BOT_ALIAS_CONTEXT.get(player.getUuid()));
    }

    private static String sanitizeBotAlias(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    /**
     * Resolve a bot by alias.  When {@code requestingPlayer} is non-null,
     * ownership is enforced: the player must own the bot (or be an operator).
     */
    private static ServerPlayerEntity resolveControlledBot(MinecraftServer server, String alias,
                                                           ServerPlayerEntity requestingPlayer) {
        if (server == null || alias == null || alias.isBlank()) {
            return null;
        }
        ServerPlayerEntity bot = null;
        if (server.getPlayerManager() != null) {
            bot = server.getPlayerManager().getPlayer(alias);
            if (bot == null) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p != null && p.getName() != null
                            && alias.equalsIgnoreCase(p.getName().getString())) {
                        bot = p;
                        break;
                    }
                }
            }
        }
        if (bot == null || bot.isRemoved()) {
            return null;
        }
        if (!(bot instanceof createFakePlayer)) {
            return null;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            return null;
        }
        // Ownership gate: requestingPlayer (when provided) must own or be op
        if (requestingPlayer != null
                && !CompanionCommunicationPolicy.isAllowedToControl(requestingPlayer, bot)) {
            return null;
        }
        return bot;
    }

    private static UUID tryParseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean removeAnyEntry(MinecraftServer server, ServerWorld world, String label) {
        if (server == null || world == null || label == null || label.isBlank()) {
            return false;
        }
        if (BotHomeService.removeBase(server, world, label)) {
            return true;
        }
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        if (FortificationPersistenceService.delete(server, worldKey, label)) {
            return true;
        }
        return MappedVillageService.delete(server, world, label);
    }

    private static boolean renameAnyEntry(MinecraftServer server, ServerWorld world, String oldLabel, String newLabel) {
        if (server == null || world == null || oldLabel == null || oldLabel.isBlank() || newLabel == null || newLabel.isBlank()) {
            return false;
        }
        if (BotHomeService.renameBase(server, world, oldLabel, newLabel)) {
            return true;
        }
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        if (FortificationPersistenceService.rename(server, worldKey, oldLabel, newLabel)) {
            return true;
        }
        return MappedVillageService.rename(server, world, oldLabel, newLabel);
    }

    private static boolean labelInUse(MinecraftServer server, ServerWorld world, String label, String excludeLabel) {
        if (server == null || world == null || label == null || label.isBlank()) {
            return false;
        }
        String wanted = normalizeLabel(label);
        String exclude = normalizeLabel(excludeLabel);
        for (BotHomeService.BaseEntry base : BotHomeService.listBases(server, world)) {
            if (base != null && normalizeLabel(base.label()).equals(wanted) && !wanted.equals(exclude)) {
                return true;
            }
        }
        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        for (FortificationPersistenceService.SavedFortification fort : FortificationPersistenceService.listForWorld(server, worldKey)) {
            if (fort != null && normalizeLabel(fort.getName()).equals(wanted) && !wanted.equals(exclude)) {
                return true;
            }
        }
        return MappedVillageService.containsLabel(server, world, label) && !wanted.equals(exclude);
    }

    private static String normalizeLabel(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record OwnerSubject(UUID ownerUuid, String ownerName) {}

    private static OwnerSubject resolveOwnerSubject(MinecraftServer server, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = raw.trim();

        UUID parsed = tryParseUuid(token);
        if (parsed != null) {
            String display = token;
            if (server != null && server.getPlayerManager() != null) {
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(parsed);
                if (online != null && online.getName() != null) {
                    display = online.getName().getString();
                }
            }
            return new OwnerSubject(parsed, display);
        }

        if (server != null && server.getPlayerManager() != null) {
            ServerPlayerEntity byName = server.getPlayerManager().getPlayer(token);
            if (byName == null) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    if (p != null && p.getName() != null && token.equalsIgnoreCase(p.getName().getString())) {
                        byName = p;
                        break;
                    }
                }
            }
            if (byName != null && byName.getUuid() != null) {
                String display = byName.getName() != null ? byName.getName().getString() : token;
                return new OwnerSubject(byName.getUuid(), display);
            }
        }

        if (Frens.CONFIG != null) {
            ManualConfig.BotOwnership owner = Frens.CONFIG.getOwner(token);
            if (owner != null && owner.ownerUuid() != null && !owner.ownerUuid().isBlank()) {
                UUID ownerUuid = tryParseUuid(owner.ownerUuid());
                if (ownerUuid != null) {
                    String display = owner.ownerName() != null && !owner.ownerName().isBlank()
                            ? owner.ownerName()
                            : token;
                    return new OwnerSubject(ownerUuid, display);
                }
            }
        }

        return null;
    }
}
