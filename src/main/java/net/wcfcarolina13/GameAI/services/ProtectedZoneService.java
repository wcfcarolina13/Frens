package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing protected zones where bots cannot break blocks.
 * Zones are axis-aligned bounding boxes defined by min/max corner positions.
 */
public class ProtectedZoneService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProtectedZoneService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Map of worldId -> Map of label -> ProtectedZone
    private static final Map<String, Map<String, ProtectedZone>> zones = new ConcurrentHashMap<>();

    // Worlds whose zone file has been read off disk. Used by callers (e.g. base auto-zone
    // hooks) to know whether eager writes are safe; before load, the in-memory map is empty
    // and a write would be clobbered when loadZones runs. Migration covers the pre-load case.
    private static final Set<String> LOADED_WORLDS = ConcurrentHashMap.newKeySet();

    private static final String ZONE_ROOT_DIR = "bot_zones";
    private static final String ZONE_FILE_NAME = "protected_zones.json";

    private static Path getZoneDirectory(MinecraftServer server, String worldId) {
        return server.getRunDirectory()
                .resolve(ZONE_ROOT_DIR)
                .resolve(worldStorageKey(worldId));
    }

    private static Path getZoneFile(MinecraftServer server, String worldId) {
        return getZoneDirectory(server, worldId).resolve(ZONE_FILE_NAME);
    }

    private static String worldStorageKey(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return "unknown_world__0";
        }
        String sanitized = worldId
                .replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.isBlank()) {
            sanitized = "world";
        }
        return sanitized + "__" + Integer.toHexString(worldId.hashCode());
    }

    private static Path getLegacyZoneFile(MinecraftServer server, String worldId) {
        if (server == null || worldId == null || worldId.isBlank()) {
            return null;
        }
        try {
            return server.getRunDirectory()
                    .resolve(ZONE_ROOT_DIR)
                    .resolve(worldId)
                    .resolve(ZONE_FILE_NAME);
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    /**
     * Represents a protected zone as an axis-aligned bounding box.
     */
    public static class ProtectedZone {
        private final String label;
        private final String worldId;
        private final BlockPos minCorner;
        private final BlockPos maxCorner;
        private final UUID ownerUuid;
        private final String ownerName;
        private final long createdTime;
        private final String accessMode;
        private final Set<String> allowedOwnerUuids;

        public ProtectedZone(String label, String worldId, BlockPos minCorner, BlockPos maxCorner,
                             UUID ownerUuid, String ownerName) {
            this(label, worldId, minCorner, maxCorner, ownerUuid, ownerName,
                 System.currentTimeMillis(), "owner_only", Set.of());
        }

        public ProtectedZone(String label, String worldId, BlockPos minCorner, BlockPos maxCorner,
                             UUID ownerUuid, String ownerName, long createdTime,
                             String accessMode, Collection<String> allowedOwnerUuids) {
            this.label = label;
            this.worldId = worldId;
            this.minCorner = new BlockPos(
                    Math.min(minCorner.getX(), maxCorner.getX()),
                    Math.min(minCorner.getY(), maxCorner.getY()),
                    Math.min(minCorner.getZ(), maxCorner.getZ())
            );
            this.maxCorner = new BlockPos(
                    Math.max(minCorner.getX(), maxCorner.getX()),
                    Math.max(minCorner.getY(), maxCorner.getY()),
                    Math.max(minCorner.getZ(), maxCorner.getZ())
            );
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.createdTime = createdTime;
            this.accessMode = normalizeAccessMode(accessMode);
            this.allowedOwnerUuids = normalizeOwnerUuidSet(allowedOwnerUuids);
        }

        public String getLabel() { return label; }
        public String getWorldId() { return worldId; }
        public BlockPos getMinCorner() { return minCorner; }
        public BlockPos getMaxCorner() { return maxCorner; }
        public UUID getOwnerUuid() { return ownerUuid; }
        public String getOwnerName() { return ownerName; }
        public long getCreatedTime() { return createdTime; }
        public String getAccessMode() { return accessMode; }

        public Set<String> getAllowedOwnerUuids() {
            return new HashSet<>(allowedOwnerUuids);
        }

        public boolean contains(BlockPos pos) {
            if (pos == null) return false;
            return pos.getX() >= minCorner.getX() && pos.getX() <= maxCorner.getX()
                && pos.getY() >= minCorner.getY() && pos.getY() <= maxCorner.getY()
                && pos.getZ() >= minCorner.getZ() && pos.getZ() <= maxCorner.getZ();
        }

        public BlockPos getCentroid() {
            return new BlockPos(
                    (minCorner.getX() + maxCorner.getX()) / 2,
                    (minCorner.getY() + maxCorner.getY()) / 2,
                    (minCorner.getZ() + maxCorner.getZ()) / 2
            );
        }

        public double distanceFrom(BlockPos pos) {
            return Math.sqrt(pos.getSquaredDistance(getCentroid()));
        }
    }

    /**
     * Check if a position is protected in a specific world.
     */
    public static boolean isProtected(BlockPos pos, ServerWorld world, @Nullable UUID botOwner) {
        if (pos == null || world == null) return false;
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) return false;

        for (ProtectedZone zone : worldZones.values()) {
            if (!zone.contains(pos)) continue;
            if (!isMutationAllowed(zone, botOwner)) return true;
        }
        return false;
    }

    private static boolean isMutationAllowed(ProtectedZone zone, @Nullable UUID actorOwner) {
        if (zone == null) return true;
        String mode = normalizeAccessMode(zone.getAccessMode());
        if ("public".equals(mode)) return true;

        UUID claimOwner = zone.getOwnerUuid();
        if (claimOwner == null) return false;
        if (actorOwner != null && claimOwner.equals(actorOwner)) return true;
        if (actorOwner == null) return false;
        return zone.allowedOwnerUuids.contains(actorOwner.toString());
    }

    @Nullable
    public static ProtectedZone getZoneAt(BlockPos pos, ServerWorld world) {
        if (pos == null || world == null) return null;
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return null;

        for (ProtectedZone zone : worldZones.values()) {
            if (zone.contains(pos)) return zone;
        }
        return null;
    }

    /**
     * Create a new protected zone from two corner positions (AABB).
     */
    public static boolean createZone(ServerWorld world, BlockPos corner1, BlockPos corner2,
                                     String label, ServerPlayerEntity owner) {
        if (world == null || corner1 == null || corner2 == null || owner == null
                || label == null || label.isBlank()) {
            return false;
        }

        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());

        if (worldZones.containsKey(label)) return false;

        ProtectedZone zone = new ProtectedZone(label, worldId, corner1, corner2,
                owner.getUuid(), owner.getName().getString());
        worldZones.put(label, zone);
        save(world.getServer(), worldId);

        LOGGER.info("Created protected zone '{}' in world {} ({} → {}) by {}",
                    label, worldId, corner1.toShortString(), corner2.toShortString(),
                    owner.getName().getString());
        return true;
    }

    /**
     * Backward-compatible: create zone from center + radius (computes AABB).
     */
    public static boolean createZone(ServerWorld world, BlockPos center, int radius,
                                     String label, ServerPlayerEntity owner) {
        if (center == null) return false;
        BlockPos min = center.add(-radius, -radius, -radius);
        BlockPos max = center.add(radius, radius, radius);
        return createZone(world, min, max, label, owner);
    }

    public static boolean removeZone(ServerWorld world, String label,
                                     ServerPlayerEntity player, boolean isAdmin) {
        if (world == null || label == null || player == null) return false;

        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return false;

        ProtectedZone zone = worldZones.get(label);
        if (zone == null) return false;

        if (!isAdmin && !zone.getOwnerUuid().equals(player.getUuid())) return false;

        worldZones.remove(label);
        save(world.getServer(), worldId);

        LOGGER.info("Removed protected zone '{}' from world {}", label, worldId);
        return true;
    }

    public static boolean removeZoneByPosition(ServerWorld world, BlockPos pos,
                                               ServerPlayerEntity player, boolean isAdmin) {
        if (world == null || pos == null || player == null) return false;

        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) return false;

        ProtectedZone closest = null;
        double minDist = 5.0;

        for (ProtectedZone zone : worldZones.values()) {
            double dist = zone.distanceFrom(pos);
            if (dist < minDist) {
                minDist = dist;
                closest = zone;
            }
        }

        if (closest == null) return false;
        return removeZone(world, closest.getLabel(), player, isAdmin);
    }

    /**
     * Whether {@link #loadZones} has run for this world. Callers that want to upsert zones
     * eagerly (e.g. base auto-zone hooks) check this so they don't write into an unloaded
     * map that will get overwritten when load happens.
     */
    public static boolean isLoaded(String worldId) {
        return worldId != null && LOADED_WORLDS.contains(worldId);
    }

    /**
     * System-level upsert: create or replace a zone without an actor/owner permission check.
     * Used by services that need to keep zone state in sync with another data structure
     * (e.g. {@code BotHomeService} mirroring user-registered bases as auto-zones).
     *
     * <p>Owner is recorded directly from the supplied UUID/name so server-owned bases get
     * a null-owner zone that rejects all bot mutations (existing behavior in
     * {@link #isMutationAllowed}).
     */
    public static boolean upsertZoneInternal(ServerWorld world, String label,
                                             BlockPos minCorner, BlockPos maxCorner,
                                             @Nullable UUID ownerUuid, @Nullable String ownerName) {
        if (world == null || label == null || label.isBlank() || minCorner == null || maxCorner == null) {
            return false;
        }
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
        ProtectedZone existing = worldZones.get(label);
        long created = existing != null ? existing.getCreatedTime() : System.currentTimeMillis();
        String accessMode = existing != null ? existing.getAccessMode() : "owner_only";
        Set<String> allowed = existing != null ? existing.getAllowedOwnerUuids() : Set.of();
        ProtectedZone zone = new ProtectedZone(label, worldId, minCorner, maxCorner,
                ownerUuid, ownerName, created, accessMode, allowed);
        worldZones.put(label, zone);
        save(world.getServer(), worldId);
        return true;
    }

    /** System-level remove: no actor/owner check. */
    public static boolean removeZoneInternal(ServerWorld world, String label) {
        if (world == null || label == null) return false;
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return false;
        if (worldZones.remove(label) == null) return false;
        save(world.getServer(), worldId);
        LOGGER.info("Removed protected zone '{}' from world {} (internal)", label, worldId);
        return true;
    }

    /** System-level rename: no actor/owner check. Returns false if oldLabel missing or newLabel taken. */
    public static boolean renameZoneInternal(ServerWorld world, String oldLabel, String newLabel) {
        if (world == null || oldLabel == null || newLabel == null || newLabel.isBlank()) return false;
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return false;
        ProtectedZone zone = worldZones.get(oldLabel);
        if (zone == null) return false;
        if (worldZones.containsKey(newLabel)) return false;
        worldZones.remove(oldLabel);
        worldZones.put(newLabel, new ProtectedZone(
                newLabel, zone.getWorldId(), zone.getMinCorner(), zone.getMaxCorner(),
                zone.getOwnerUuid(), zone.getOwnerName(), zone.getCreatedTime(),
                zone.getAccessMode(), zone.getAllowedOwnerUuids()));
        save(world.getServer(), worldId);
        return true;
    }

    public static List<ProtectedZone> listZones(ServerWorld world) {
        if (world == null) return Collections.emptyList();
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(worldZones.values());
    }

    /** Renames a zone. Returns false if old label not found or new label already exists. */
    public static boolean renameZone(ServerWorld world, String oldLabel, String newLabel,
                                     ServerPlayerEntity player, boolean isAdmin) {
        if (world == null || oldLabel == null || newLabel == null || newLabel.isBlank()
                || player == null) {
            return false;
        }
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return false;

        ProtectedZone zone = worldZones.get(oldLabel);
        if (zone == null) return false;
        if (!isAdmin && (zone.getOwnerUuid() == null || !zone.getOwnerUuid().equals(player.getUuid()))) {
            return false;
        }
        if (worldZones.containsKey(newLabel)) return false;

        worldZones.remove(oldLabel);
        ProtectedZone renamed = new ProtectedZone(
                newLabel, zone.getWorldId(), zone.getMinCorner(), zone.getMaxCorner(),
                zone.getOwnerUuid(), zone.getOwnerName(), zone.getCreatedTime(),
                zone.getAccessMode(), zone.getAllowedOwnerUuids()
        );
        worldZones.put(newLabel, renamed);
        save(world.getServer(), worldId);
        LOGGER.info("Renamed protected zone '{}' → '{}' in world {}", oldLabel, newLabel, worldId);
        return true;
    }

    public static boolean grantZoneAccess(ServerWorld world, String label, UUID ownerUuid) {
        if (world == null || label == null || label.isBlank() || ownerUuid == null) return false;
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return false;
        ProtectedZone zone = worldZones.get(label);
        if (zone == null) return false;
        Set<String> allowed = zone.getAllowedOwnerUuids();
        allowed.add(ownerUuid.toString());
        worldZones.put(label, new ProtectedZone(
                zone.getLabel(), zone.getWorldId(), zone.getMinCorner(), zone.getMaxCorner(),
                zone.getOwnerUuid(), zone.getOwnerName(), zone.getCreatedTime(),
                zone.getAccessMode(), allowed
        ));
        save(world.getServer(), worldId);
        return true;
    }

    public static boolean revokeZoneAccess(ServerWorld world, String label, UUID ownerUuid) {
        if (world == null || label == null || label.isBlank() || ownerUuid == null) return false;
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return false;
        ProtectedZone zone = worldZones.get(label);
        if (zone == null) return false;
        Set<String> allowed = zone.getAllowedOwnerUuids();
        if (!allowed.remove(ownerUuid.toString())) return false;
        worldZones.put(label, new ProtectedZone(
                zone.getLabel(), zone.getWorldId(), zone.getMinCorner(), zone.getMaxCorner(),
                zone.getOwnerUuid(), zone.getOwnerName(), zone.getCreatedTime(),
                zone.getAccessMode(), allowed
        ));
        save(world.getServer(), worldId);
        return true;
    }

    public static boolean setZoneAccessMode(ServerWorld world, String label, String accessMode) {
        if (world == null || label == null || label.isBlank()) return false;
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) return false;
        ProtectedZone zone = worldZones.get(label);
        if (zone == null) return false;
        worldZones.put(label, new ProtectedZone(
                zone.getLabel(), zone.getWorldId(), zone.getMinCorner(), zone.getMaxCorner(),
                zone.getOwnerUuid(), zone.getOwnerName(), zone.getCreatedTime(),
                accessMode, zone.getAllowedOwnerUuids()
        ));
        save(world.getServer(), worldId);
        return true;
    }

    public static void loadZones(MinecraftServer server, String worldId) {
        Path zoneFile = getZoneFile(server, worldId);
        Path legacyFile = getLegacyZoneFile(server, worldId);

        // Mark loaded even if the file doesn't exist — empty world is a valid load result,
        // and post-load callers (auto-zone migration) need the green light to write.
        LOADED_WORLDS.add(worldId);

        Path fileToRead = null;
        if (Files.exists(zoneFile)) {
            fileToRead = zoneFile;
        } else if (legacyFile != null && Files.exists(legacyFile)) {
            fileToRead = legacyFile;
            LOGGER.info("Loading protected zones for world {} from legacy path {}", worldId, legacyFile);
        }

        if (fileToRead == null) return;

        try {
            String json = Files.readString(fileToRead);
            List<ZoneData> zoneDataList = GSON.fromJson(json, new TypeToken<List<ZoneData>>(){}.getType());

            if (zoneDataList == null || zoneDataList.isEmpty()) return;

            Map<String, ProtectedZone> worldZones = zones.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
            for (ZoneData data : zoneDataList) {
                try {
                    BlockPos minCorner;
                    BlockPos maxCorner;

                    if (data.hasLegacyFormat()) {
                        // Legacy center+radius → AABB migration
                        minCorner = new BlockPos(
                                data.centerX - data.radius,
                                data.centerY - data.radius,
                                data.centerZ - data.radius);
                        maxCorner = new BlockPos(
                                data.centerX + data.radius,
                                data.centerY + data.radius,
                                data.centerZ + data.radius);
                        LOGGER.info("Migrated legacy zone '{}' from center+radius to AABB", data.label);
                    } else {
                        minCorner = new BlockPos(data.minX, data.minY, data.minZ);
                        maxCorner = new BlockPos(data.maxX, data.maxY, data.maxZ);
                    }

                    UUID ownerUuid = data.ownerUuid != null && !data.ownerUuid.isBlank()
                            ? UUID.fromString(data.ownerUuid) : null;
                    long created = data.createdTime > 0 ? data.createdTime : System.currentTimeMillis();
                    ProtectedZone zone = new ProtectedZone(
                        data.label, worldId, minCorner, maxCorner,
                        ownerUuid, data.ownerName, created,
                        data.accessMode, data.allowedOwnerUuids
                    );
                    worldZones.put(data.label, zone);
                } catch (Exception e) {
                    LOGGER.warn("Skipping malformed protected zone '{}' in world {}", data.label, worldId);
                }
            }

            LOGGER.info("Loaded {} protected zones for world {}", zoneDataList.size(), worldId);

            // Re-save in new format if any legacy zones were migrated
            boolean hadLegacy = zoneDataList.stream().anyMatch(ZoneData::hasLegacyFormat);
            if (hadLegacy) {
                save(server, worldId);
                LOGGER.info("Re-saved zones for world {} in AABB format after legacy migration", worldId);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load protected zones for world {}", worldId, e);
        }
    }

    private static void save(MinecraftServer server, String worldId) {
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) return;

        try {
            Path zoneDir = getZoneDirectory(server, worldId);
            Files.createDirectories(zoneDir);

            List<ZoneData> zoneDataList = new ArrayList<>();
            for (ProtectedZone zone : worldZones.values()) {
                zoneDataList.add(new ZoneData(zone));
            }

            String json = GSON.toJson(zoneDataList);
            Files.writeString(getZoneFile(server, worldId), json);

            LOGGER.debug("Saved {} protected zones for world {}", zoneDataList.size(), worldId);
        } catch (IOException e) {
            LOGGER.error("Failed to save protected zones for world {}", worldId, e);
        }
    }

    public static String generateLabel(ServerWorld world) {
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) return "zone-1";

        int counter = 1;
        while (worldZones.containsKey("zone-" + counter)) {
            counter++;
        }
        return "zone-" + counter;
    }

    public static void clearWorld(ServerWorld world) {
        if (world == null) return;
        String worldId = getWorldId(world);
        zones.remove(worldId);
        LOGGER.info("Cleared all protected zones for world {}", worldId);
    }

    private static String getWorldId(ServerWorld world) {
        RegistryKey<World> key = world.getRegistryKey();
        return key.getValue().toString();
    }

    /**
     * Data class for JSON serialization. Supports both new AABB format and legacy center+radius.
     */
    private static class ZoneData {
        String label;
        // New AABB format
        int minX, minY, minZ;
        int maxX, maxY, maxZ;
        // Legacy format (read-only, for migration)
        int centerX, centerY, centerZ;
        int radius;
        // Common fields
        String ownerUuid;
        String ownerName;
        String accessMode;
        List<String> allowedOwnerUuids;
        long createdTime;

        @SuppressWarnings("unused")
        ZoneData() {}

        ZoneData(ProtectedZone zone) {
            this.label = zone.getLabel();
            this.minX = zone.getMinCorner().getX();
            this.minY = zone.getMinCorner().getY();
            this.minZ = zone.getMinCorner().getZ();
            this.maxX = zone.getMaxCorner().getX();
            this.maxY = zone.getMaxCorner().getY();
            this.maxZ = zone.getMaxCorner().getZ();
            this.ownerUuid = zone.getOwnerUuid() != null ? zone.getOwnerUuid().toString() : null;
            this.ownerName = zone.getOwnerName();
            this.accessMode = zone.getAccessMode();
            this.allowedOwnerUuids = new ArrayList<>(zone.getAllowedOwnerUuids());
            this.createdTime = zone.getCreatedTime();
        }

        boolean hasLegacyFormat() {
            return radius > 0 && minX == 0 && maxX == 0 && minY == 0 && maxY == 0
                    && minZ == 0 && maxZ == 0;
        }
    }

    private static String normalizeAccessMode(String mode) {
        if (mode == null || mode.isBlank()) return "owner_only";
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if ("public".equals(normalized)) return "public";
        if ("allowlist".equals(normalized) || "owner_only".equals(normalized)) return normalized;
        return "owner_only";
    }

    private static Set<String> normalizeOwnerUuidSet(Collection<String> values) {
        Set<String> out = new HashSet<>();
        if (values == null || values.isEmpty()) return out;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try {
                out.add(UUID.fromString(value.trim()).toString());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }
}
