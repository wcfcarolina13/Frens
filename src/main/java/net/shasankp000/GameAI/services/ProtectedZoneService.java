package net.shasankp000.GameAI.services;

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
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing protected zones where bots cannot break blocks.
 * Zones are cubic regions defined by center position and radius.
 */
public class ProtectedZoneService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProtectedZoneService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Map of worldId -> Map of label -> ProtectedZone
    private static final Map<String, Map<String, ProtectedZone>> zones = new ConcurrentHashMap<>();
    
    private static Path getZoneDirectory(MinecraftServer server, String worldId) {
        return server.getRunDirectory().resolve("bot_zones").resolve(worldId);
    }
    
    private static Path getZoneFile(MinecraftServer server, String worldId) {
        return getZoneDirectory(server, worldId).resolve("protected_zones.json");
    }
    
    /**
     * Represents a protected zone in the world
     */
    public static class ProtectedZone {
        private final String label;
        private final String worldId;
        private final BlockPos center;
        private final int radius;
        private final UUID ownerUuid;
        private final String ownerName;
        private final long createdTime;
        
        public ProtectedZone(String label, String worldId, BlockPos center, int radius, UUID ownerUuid, String ownerName) {
            this.label = label;
            this.worldId = worldId;
            this.center = center.toImmutable();
            this.radius = radius;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.createdTime = System.currentTimeMillis();
        }
        
        public String getLabel() {
            return label;
        }
        
        public String getWorldId() {
            return worldId;
        }
        
        public BlockPos getCenter() {
            return center;
        }
        
        public int getRadius() {
            return radius;
        }
        
        public UUID getOwnerUuid() {
            return ownerUuid;
        }
        
        public String getOwnerName() {
            return ownerName;
        }
        
        public long getCreatedTime() {
            return createdTime;
        }
        
        public boolean contains(BlockPos pos) {
            if (pos == null) {
                return false;
            }
            // Cubic distance check
            int dx = Math.abs(pos.getX() - center.getX());
            int dy = Math.abs(pos.getY() - center.getY());
            int dz = Math.abs(pos.getZ() - center.getZ());
            return dx <= radius && dy <= radius && dz <= radius;
        }
        
        public double distanceFrom(BlockPos pos) {
            return Math.sqrt(pos.getSquaredDistance(center));
        }
    }
    
    /**
     * Check if a position is protected in a specific world.
     * Returns true if the position is inside any protected zone.
     */
    public static boolean isProtected(BlockPos pos, ServerWorld world, @Nullable UUID botOwner) {
        if (pos == null || world == null) {
            return false;
        }
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) {
            return false;
        }
        
        for (ProtectedZone zone : worldZones.values()) {
            if (zone.contains(pos)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get the zone that contains a position, if any.
     */
    @Nullable
    public static ProtectedZone getZoneAt(BlockPos pos, ServerWorld world) {
        if (pos == null || world == null) {
            return null;
        }
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) {
            return null;
        }
        
        for (ProtectedZone zone : worldZones.values()) {
            if (zone.contains(pos)) {
                return zone;
            }
        }
        return null;
    }
    
    /**
     * Create a new protected zone.
     */
    public static boolean createZone(ServerWorld world, BlockPos center, int radius, String label, ServerPlayerEntity owner) {
        if (world == null || center == null || owner == null || label == null || label.isBlank()) {
            return false;
        }
        
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
        
        if (worldZones.containsKey(label)) {
            return false;  // Zone with this label already exists
        }
        
        ProtectedZone zone = new ProtectedZone(label, worldId, center, radius, owner.getUuid(), owner.getName().getString());
        worldZones.put(label, zone);
        
        // Save to disk
        save(world.getServer(), worldId);
        
        LOGGER.info("Created protected zone '{}' in world {} at {} radius {} by {}", 
                    label, worldId, center, radius, owner.getName().getString());
        return true;
    }
    
    /**
     * Remove a protected zone.
     */
    public static boolean removeZone(ServerWorld world, String label, ServerPlayerEntity player, boolean isAdmin) {
        if (world == null || label == null || player == null) {
            return false;
        }
        
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null) {
            return false;
        }
        
        ProtectedZone zone = worldZones.get(label);
        if (zone == null) {
            return false;
        }
        
        // Permission check: owner or admin
        if (!isAdmin && !zone.getOwnerUuid().equals(player.getUuid())) {
            return false;
        }
        
        worldZones.remove(label);
        save(world.getServer(), worldId);
        
        LOGGER.info("Removed protected zone '{}' from world {}", label, worldId);
        return true;
    }
    
    /**
     * Remove zone by position (finds closest zone center within 5 blocks).
     */
    public static boolean removeZoneByPosition(ServerWorld world, BlockPos pos, ServerPlayerEntity player, boolean isAdmin) {
        if (world == null || pos == null || player == null) {
            return false;
        }
        
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) {
            return false;
        }
        
        // Find closest zone center
        ProtectedZone closest = null;
        double minDist = 5.0;  // Max 5 blocks away
        
        for (ProtectedZone zone : worldZones.values()) {
            double dist = Math.sqrt(pos.getSquaredDistance(zone.getCenter()));
            if (dist < minDist) {
                minDist = dist;
                closest = zone;
            }
        }
        
        if (closest == null) {
            return false;
        }
        
        return removeZone(world, closest.getLabel(), player, isAdmin);
    }
    
    /**
     * List all zones in a world.
     */
    public static List<ProtectedZone> listZones(ServerWorld world) {
        if (world == null) {
            return Collections.emptyList();
        }
        
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) {
            return Collections.emptyList();
        }
        
        return new ArrayList<>(worldZones.values());
    }
    
    /**
     * Load zones from disk for a specific world.
     */
    public static void loadZones(MinecraftServer server, String worldId) {
        Path zoneFile = getZoneFile(server, worldId);
        if (!Files.exists(zoneFile)) {
            return;
        }
        
        try {
            String json = Files.readString(zoneFile);
            List<ZoneData> zoneDataList = GSON.fromJson(json, new TypeToken<List<ZoneData>>(){}.getType());
            
            if (zoneDataList == null || zoneDataList.isEmpty()) {
                return;
            }
            
            Map<String, ProtectedZone> worldZones = zones.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
            for (ZoneData data : zoneDataList) {
                BlockPos center = new BlockPos(data.centerX, data.centerY, data.centerZ);
                ProtectedZone zone = new ProtectedZone(
                    data.label,
                    worldId,
                    center,
                    data.radius,
                    UUID.fromString(data.ownerUuid),
                    data.ownerName
                );
                worldZones.put(data.label, zone);
            }
            
            LOGGER.info("Loaded {} protected zones for world {}", zoneDataList.size(), worldId);
        } catch (IOException e) {
            LOGGER.error("Failed to load protected zones for world {}", worldId, e);
        }
    }
    
    /**
     * Save zones to disk for a specific world.
     */
    private static void save(MinecraftServer server, String worldId) {
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) {
            return;
        }
        
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
    
    /**
     * Generate a unique zone label for a world.
     */
    public static String generateLabel(ServerWorld world) {
        String worldId = getWorldId(world);
        Map<String, ProtectedZone> worldZones = zones.get(worldId);
        if (worldZones == null || worldZones.isEmpty()) {
            return "zone-1";
        }
        
        int counter = 1;
        while (worldZones.containsKey("zone-" + counter)) {
            counter++;
        }
        return "zone-" + counter;
    }
    
    /**
     * Clear all zones for a world.
     */
    public static void clearWorld(ServerWorld world) {
        if (world == null) {
            return;
        }
        String worldId = getWorldId(world);
        zones.remove(worldId);
        LOGGER.info("Cleared all protected zones for world {}", worldId);
    }
    
    private static String getWorldId(ServerWorld world) {
        RegistryKey<World> key = world.getRegistryKey();
        return key.getValue().toString();
    }
    
    /**
     * Data class for JSON serialization.
     */
    private static class ZoneData {
        String label;
        int centerX, centerY, centerZ;
        int radius;
        String ownerUuid;
        String ownerName;
        
        ZoneData() {}
        
        ZoneData(ProtectedZone zone) {
            this.label = zone.getLabel();
            this.centerX = zone.getCenter().getX();
            this.centerY = zone.getCenter().getY();
            this.centerZ = zone.getCenter().getZ();
            this.radius = zone.getRadius();
            this.ownerUuid = zone.getOwnerUuid().toString();
            this.ownerName = zone.getOwnerName();
        }
    }
}
