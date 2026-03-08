package net.wcfcarolina13.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.wcfcarolina13.GameAI.services.HuntCatalog;
import net.wcfcarolina13.GameAI.services.HuntConfigService;
import net.wcfcarolina13.GameAI.services.HuntHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Networking glue for the Huntables menu. */
public final class HuntablesNetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("huntables-network");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean REGISTERED = false;

    /** Players currently in target-picking mode: player UUID → bot name. */
    private static final Map<UUID, String> TARGET_PICKERS = new ConcurrentHashMap<>();

    /** Pending entity targets set by the target picker, consumed by HuntSkill. */
    private static final Map<UUID, UUID> PENDING_TARGET_ENTITY = new ConcurrentHashMap<>();
    /** Timestamps for pending target entries, used for expiry cleanup. */
    private static final Map<UUID, Long> PENDING_TARGET_TIMESTAMPS = new ConcurrentHashMap<>();
    private static final long PENDING_TARGET_EXPIRY_MS = 30_000L;

    private static final DustParticleEffect GREEN_DUST = new DustParticleEffect(0x66CC33, 1.0f);
    private static final double PARTICLE_RANGE = 32.0;
    private static final int PARTICLE_INTERVAL_TICKS = 10;
    private static int particleTickCounter;

    private HuntablesNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RequestHuntablesPayload.ID, (payload, context) ->
                context.server().execute(() -> sendHuntablesList(context.player())));

        ServerPlayNetworking.registerGlobalReceiver(SaveHuntConfigPayload.ID, (payload, context) ->
                context.server().execute(() -> handleSaveHuntConfig(context.player(), context.server(), payload.configJson())));

        ServerPlayNetworking.registerGlobalReceiver(HuntTargetModePayload.ID, (payload, context) ->
                context.server().execute(() -> handleTargetMode(context.player(), payload.json())));

        ServerPlayNetworking.registerGlobalReceiver(HuntTargetPayload.ID, (payload, context) ->
                context.server().execute(() -> handleTargetEntity(context.player(), context.server(), payload.json())));
    }

    private static void handleSaveHuntConfig(ServerPlayerEntity player, net.minecraft.server.MinecraftServer server, String json) {
        if (player == null || player.isRemoved() || json == null || server == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (parsed == null) return;

            String botName = parsed.get("botName") instanceof String s ? s : null;
            if (botName == null || botName.isBlank()) return;

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot == null) return;

            boolean depop = parsed.get("depopulationEnabled") instanceof Boolean b ? b : true;
            String zoneName = parsed.get("zone") instanceof String s ? s : "STANDARD";
            List<String> targets = parsed.get("selectedTargets") instanceof List<?> list
                    ? list.stream().filter(o -> o instanceof String).map(o -> (String) o).toList()
                    : List.of();

            HuntConfigService.HuntConfig config = new HuntConfigService.HuntConfig(
                    depop, HuntConfigService.HuntZone.fromName(zoneName), targets);
            HuntConfigService.saveConfig(bot, config);

            sendHuntConfig(player, bot);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse hunt config save: {}", e.getMessage());
        }
    }

    public static void sendHuntConfig(ServerPlayerEntity player, ServerPlayerEntity bot) {
        if (player == null || player.isRemoved() || bot == null) return;
        HuntConfigService.HuntConfig config = HuntConfigService.getConfig(bot);
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("botName", bot.getName().getString());
        out.put("depopulationEnabled", config.depopulationEnabled);
        out.put("zone", config.zone);
        out.put("selectedTargets", config.selectedTargets);
        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new HuntConfigPayload(json));
    }

    public static void sendHuntablesList(ServerPlayerEntity player) {
        if (player == null || player.isRemoved()) {
            return;
        }

        Set<net.minecraft.util.Identifier> unlocked = HuntHistoryService.getHistory(player);
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        for (HuntCatalog.HuntTarget target : HuntCatalog.listAll()) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", target.id().toString());
            entry.put("label", target.label());
            entry.put("food", target.foodMob());
            entry.put("unlocked", unlocked.contains(target.id()));
            out.add(entry);
        }
        String json = GSON.toJson(out);
        ServerPlayNetworking.send(player, new HuntablesPayload(json));
    }

    // ── Target picking mode ──────────────────────────────────────────────

    private static void handleTargetMode(ServerPlayerEntity player, String json) {
        if (player == null || player.isRemoved() || json == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (parsed == null) return;
            boolean active = parsed.get("active") instanceof Boolean b && b;
            String botName = parsed.get("botName") instanceof String s ? s : null;
            if (active && botName != null && !botName.isBlank()) {
                TARGET_PICKERS.put(player.getUuid(), botName);
                LOGGER.info("Target-picking mode ON for {} (bot={})", player.getName().getString(), botName);
            } else {
                TARGET_PICKERS.remove(player.getUuid());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse target mode payload: {}", e.getMessage());
        }
    }

    private static void handleTargetEntity(ServerPlayerEntity player, MinecraftServer server, String json) {
        if (player == null || player.isRemoved() || server == null || json == null) return;
        try {
            Map<String, Object> parsed = GSON.fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (parsed == null) return;

            String botName = parsed.get("botName") instanceof String s ? s : null;
            String entityUuidStr = parsed.get("entityUuid") instanceof String s ? s : null;
            String entityType = parsed.get("entityType") instanceof String s ? s : null;
            if (botName == null || botName.isBlank() || entityUuidStr == null) return;

            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
            if (bot == null) return;

            UUID entityUuid;
            try {
                entityUuid = UUID.fromString(entityUuidStr);
            } catch (IllegalArgumentException e) {
                return;
            }

            // Validate entity type is a huntable food mob
            if (entityType != null && !entityType.isBlank()) {
                net.minecraft.util.Identifier typeId = net.minecraft.util.Identifier.tryParse(entityType);
                if (typeId != null) {
                    var optType = net.minecraft.registry.Registries.ENTITY_TYPE.getOptionalValue(typeId);
                    if (optType.isPresent() && !HuntCatalog.isFoodMob(optType.get())) {
                        LOGGER.warn("Rejected non-huntable target type: {}", entityType);
                        return;
                    }
                }
            }

            // Store the pending target for HuntSkill to consume
            PENDING_TARGET_ENTITY.put(bot.getUuid(), entityUuid);
            PENDING_TARGET_TIMESTAMPS.put(bot.getUuid(), System.currentTimeMillis());

            // Clear target-picking mode
            TARGET_PICKERS.remove(player.getUuid());

            // Resolve entity type name for the command
            String targetName = "";
            if (entityType != null && !entityType.isBlank()) {
                net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(entityType);
                if (id != null) {
                    targetName = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
                }
            }

            // Dispatch hunt command for 1 target
            String command = "bot skill hunt 1 " + targetName + " " + botName;
            LOGGER.info("Target dispatch: {} -> {}", player.getName().getString(), command.trim());
            net.wcfcarolina13.CommandUtils.run(player.getCommandSource(), command.trim());

        } catch (Exception e) {
            LOGGER.warn("Failed to handle hunt target: {}", e.getMessage());
        }
    }

    /** Consume a pending target entity UUID for a bot (called by HuntSkill). */
    public static UUID consumePendingTarget(UUID botId) {
        if (botId == null) return null;
        PENDING_TARGET_TIMESTAMPS.remove(botId);
        return PENDING_TARGET_ENTITY.remove(botId);
    }

    /**
     * Called from the server tick loop. Spawns green particles above huntable
     * mobs near players who are in target-picking mode.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;

        // Expire stale pending targets
        if (!PENDING_TARGET_TIMESTAMPS.isEmpty()) {
            long now = System.currentTimeMillis();
            PENDING_TARGET_TIMESTAMPS.entrySet().removeIf(e -> {
                if (now - e.getValue() > PENDING_TARGET_EXPIRY_MS) {
                    PENDING_TARGET_ENTITY.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }

        if (TARGET_PICKERS.isEmpty()) return;

        // Clean up disconnected players (safe iteration via removeIf)
        TARGET_PICKERS.entrySet().removeIf(e -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            return p == null || p.isRemoved();
        });

        // Throttle particles to every N ticks
        particleTickCounter++;
        if (particleTickCounter < PARTICLE_INTERVAL_TICKS) return;
        particleTickCounter = 0;

        for (var entry : TARGET_PICKERS.entrySet()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null || player.isRemoved()) continue;
            if (!(player.getEntityWorld() instanceof ServerWorld world)) continue;

            Box scanBox = player.getBoundingBox().expand(PARTICLE_RANGE, PARTICLE_RANGE / 2.0, PARTICLE_RANGE);
            for (Entity entity : world.getOtherEntities(player, scanBox)) {
                if (!(entity instanceof LivingEntity living)) continue;
                if (living.isDead() || living.isRemoved()) continue;
                if (!HuntCatalog.isFoodMob(entity.getType())) continue;

                double px = entity.getX();
                double py = entity.getY() + entity.getHeight() + 0.5;
                double pz = entity.getZ();
                world.spawnParticles(GREEN_DUST, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}
