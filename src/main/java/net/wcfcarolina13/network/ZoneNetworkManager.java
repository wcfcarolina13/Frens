package net.wcfcarolina13.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.ProtectedZoneService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.ZoneVisualizerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side networking for protected zone management. */
public final class ZoneNetworkManager {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(ZoneNetworkManager.class);
    private static final Map<String, Long> DENIED_LOG_TIMES = new ConcurrentHashMap<>();
    private static volatile boolean REGISTERED = false;

    /** Tracks wand corner state per player: null = no corner set, non-null = corner 1 position. */
    private static final Map<UUID, BlockPos> PENDING_CORNER1 = new ConcurrentHashMap<>();

    public static final String ZONE_WAND_TAG = "frens_zone_wand";

    private ZoneNetworkManager() {}

    public static boolean isZoneWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return false;
        NbtCompound nbt = customData.copyNbt();
        return nbt.getBoolean(ZONE_WAND_TAG).orElse(false);
    }

    public static ItemStack createZoneWand() {
        ItemStack wand = new ItemStack(Items.BLAZE_ROD);
        wand.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("\u00A7b\u00A7lZone Wand"));
        wand.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("\u00A77Right-click to select zone corners"),
                Text.literal("\u00A77Press [=] to confirm and name the zone")
        )));
        wand.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        NbtCompound tag = new NbtCompound();
        tag.putBoolean(ZONE_WAND_TAG, true);
        wand.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return wand;
    }

    /** Called from UseBlockCallback when a zone wand right-clicks a block. */
    public static void handleWandUse(ServerPlayerEntity player, BlockPos hitPos) {
        UUID uuid = player.getUuid();
        BlockPos corner1 = PENDING_CORNER1.get(uuid);

        if (corner1 == null) {
            // Set corner 1
            PENDING_CORNER1.put(uuid, hitPos.toImmutable());
            ServerPlayNetworking.send(player, new ZoneCornerSetPayload(1, hitPos.getX(), hitPos.getY(), hitPos.getZ()));
        } else {
            // Set corner 2 — clear corner 1 so next click starts fresh
            PENDING_CORNER1.remove(uuid);
            ServerPlayNetworking.send(player, new ZoneCornerSetPayload(2, hitPos.getX(), hitPos.getY(), hitPos.getZ()));
        }
    }

    public static void clearPendingCorner(UUID playerUuid) {
        PENDING_CORNER1.remove(playerUuid);
    }

    public static void registerReceiversOnce() {
        if (REGISTERED) return;
        REGISTERED = true;

        // Wand request — give wand item
        ServerPlayNetworking.registerGlobalReceiver(ZoneWandRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null || !Frens.isOperator(player.getCommandSource())) return;
                    player.getInventory().insertStack(createZoneWand());
                    ChatUtils.sendSystemMessage(player.getCommandSource(),
                            "\u00A7aZone Wand given. Right-click blocks to set corners.");
                }));

        // Zone confirm — create zone from two corners
        ServerPlayNetworking.registerGlobalReceiver(ZoneConfirmPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null || !Frens.isOperator(player.getCommandSource())) return;

                    String name = payload.name();
                    if (name == null || name.isBlank()) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(), "\u00A7cZone name cannot be empty.");
                        return;
                    }
                    if (name.length() > 32) {
                        name = name.substring(0, 32);
                    }

                    ServerWorld world = player.getCommandSource().getWorld();
                    BlockPos corner1 = new BlockPos(payload.x1(), payload.y1(), payload.z1());
                    BlockPos corner2 = new BlockPos(payload.x2(), payload.y2(), payload.z2());

                    boolean ok = ProtectedZoneService.createZone(world, corner1, corner2, name, player);
                    if (ok) {
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "\u00A7aZone \"" + name + "\" created!");
                    } else {
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "\u00A7cFailed to create zone. Name \"" + name + "\" may already exist.");
                    }
                }));

        // Zone edit (rename)
        ServerPlayNetworking.registerGlobalReceiver(ZoneEditPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null || !Frens.isOperator(player.getCommandSource())) return;

                    String oldLabel = payload.label();
                    String newName = payload.newName();
                    if (oldLabel == null || newName == null || newName.isBlank()) return;

                    ServerWorld world = player.getCommandSource().getWorld();
                    boolean ok = ProtectedZoneService.renameZone(world, oldLabel, newName, player, true);
                    if (ok) {
                        ZoneVisualizerService.renameZone(oldLabel, newName);
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "\u00A7aZone renamed to \"" + newName + "\".");
                        sendZoneList(player);
                    } else {
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "\u00A7cFailed to rename zone. New name may already be in use.");
                    }
                }));

        // Zone delete
        ServerPlayNetworking.registerGlobalReceiver(ZoneDeletePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null || !Frens.isOperator(player.getCommandSource())) return;

                    String label = payload.label();
                    if (label == null || label.isBlank()) return;

                    ServerWorld world = player.getCommandSource().getWorld();
                    boolean ok = ProtectedZoneService.removeZone(world, label, player, true);
                    if (ok) {
                        ZoneVisualizerService.removeZone(label);
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "\u00A7aZone \"" + label + "\" deleted.");
                        sendZoneList(player);
                    } else {
                        ChatUtils.sendSystemMessage(player.getCommandSource(),
                                "\u00A7cFailed to delete zone \"" + label + "\".");
                    }
                }));

        // Toggle view — any player
        ServerPlayNetworking.registerGlobalReceiver(ZoneToggleViewPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    if (payload.enabled()) {
                        if (!ZoneVisualizerService.startViewing(payload.label(), player)) {
                            warnDeniedView(player, payload.label());
                        }
                    } else {
                        ZoneVisualizerService.stopViewing(payload.label(), player.getUuid());
                    }
                }));

        // Request zone list
        ServerPlayNetworking.registerGlobalReceiver(RequestZoneListPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null) return;
                    sendZoneList(player);
                }));
    }

    public static void sendZoneList(ServerPlayerEntity player) {
        ServerWorld world = player.getCommandSource().getWorld();
        List<ProtectedZoneService.ProtectedZone> zones = ProtectedZoneService.listZones(world);
        List<ZoneDto> dtos = new ArrayList<>();
        for (ProtectedZoneService.ProtectedZone zone : zones) {
            if (!isZoneVisibleToViewer(world, zone, player)) continue;
            dtos.add(new ZoneDto(
                    zone.getLabel(),
                    zone.getMinCorner().getX(), zone.getMinCorner().getY(), zone.getMinCorner().getZ(),
                    zone.getMaxCorner().getX(), zone.getMaxCorner().getY(), zone.getMaxCorner().getZ(),
                    zone.getOwnerName(),
                    zone.getAccessMode()
            ));
        }
        String json = GSON.toJson(dtos);
        ServerPlayNetworking.send(player, new ZoneListPayload(json));
    }

    public static boolean isZoneVisibleToViewer(ServerWorld world,
                                                ProtectedZoneService.ProtectedZone zone,
                                                ServerPlayerEntity viewer) {
        if (world == null || zone == null || viewer == null || zone.getLabel() == null) return false;
        String label = zone.getLabel();
        if (!label.startsWith("base:")) return true;
        String baseLabel = label.substring("base:".length()).trim().toLowerCase(java.util.Locale.ROOT);
        for (BotHomeService.BaseEntry base : BotHomeService.listBases(viewer.getCommandSource().getServer(), world)) {
            if (base != null && base.label() != null
                    && baseLabel.equals(base.label().trim().toLowerCase(java.util.Locale.ROOT))) {
                return BaseNetworkManager.isBaseVisibleToViewer(base, viewer.getUuid().toString(),
                        Frens.isOperator(viewer), BotAccessGate.isHost(viewer));
            }
        }
        return false;
    }

    private static void warnDeniedView(ServerPlayerEntity player, String label) {
        String target = label == null ? "" : label.replaceAll("[\\r\\n\\t]", " ");
        if (target.length() > 64) target = target.substring(0, 64);
        String key = player.getUuid() + ":zone_view:" + target;
        long now = System.currentTimeMillis();
        Long prior = DENIED_LOG_TIMES.put(key, now);
        if (prior != null && now - prior < 5_000L) return;
        if (DENIED_LOG_TIMES.size() > 1024) DENIED_LOG_TIMES.clear();
        LOGGER.warn("[bot-access] denied action={} sender={} target={} reason={}",
                "zone_view", player.getUuid(), target, "missing_invisible_or_limit");
    }

    public record ZoneDto(String label, int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ,
                          String ownerName, String accessMode) {}
}
