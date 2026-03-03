package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Central ownership/claim guard for bot block mutations.
 *
 * <p>Current policy:
 * <ul>
 *   <li>Protected-zone claims: owner-only by default; explicit allowlist/public policy supported.</li>
 *   <li>Fort interior claims: if a saved fort has an owner, bots with different owners
 *       cannot mutate blocks inside that fort's convex hull interior unless policy permits.</li>
 * </ul>
 */
public final class BotTerritoryAuthorizationService {

    private static final AuthorizationResult ALLOW = new AuthorizationResult(true, "", "", "");

    private BotTerritoryAuthorizationService() {
    }

    public record AuthorizationResult(boolean allowed, String reason, String claimName, String claimOwnerName) {
    }

    public static AuthorizationResult authorizeBlockMutation(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return ALLOW;
        }

        UUID botOwner = resolveBotOwnerUuid(bot);

        // Protected-zone ownership (cubic claim)
        ProtectedZoneService.ProtectedZone zone = ProtectedZoneService.getZoneAt(pos, world);
        if (zone != null && !isZoneMutationAllowed(zone, botOwner)) {
            return new AuthorizationResult(
                    false,
                    "protected-zone",
                    safe(zone.getLabel()),
                    safe(zone.getOwnerName())
            );
        }

        // Fort interior ownership (convex-hull claim)
        MinecraftServer server = world.getServer();
        if (server == null) {
            return ALLOW;
        }

        String worldKey = FortificationPersistenceService.serverWorldKey(server, world);
        for (FortificationPersistenceService.SavedFortification fort
                : FortificationPersistenceService.listForWorld(server, worldKey)) {
            List<VillageFortificationLayoutService.WallPoint> hull = fort.getHullWallPoints();
            if (!containsXZ(hull, pos.getX(), pos.getZ())) {
                continue;
            }
            UUID fortOwner = tryParseUuid(fort.getOwnerUuid());
            if (isFortMutationAllowed(fort, fortOwner, botOwner)) {
                continue;
            }
            return new AuthorizationResult(
                    false,
                    "fort-claim",
                    safe(fort.getName()),
                    safe(fort.getOwnerName())
            );
        }

        return ALLOW;
    }

    private static boolean isZoneMutationAllowed(ProtectedZoneService.ProtectedZone zone, UUID actorOwner) {
        if (zone == null) {
            return true;
        }
        String mode = normalizePolicy(zone.getAccessMode());
        if ("public".equals(mode)) {
            return true;
        }
        UUID zoneOwner = zone.getOwnerUuid();
        if (zoneOwner == null) {
            return false;
        }
        if (actorOwner != null && zoneOwner.equals(actorOwner)) {
            return true;
        }
        if (actorOwner == null) {
            return false;
        }
        return zone.getAllowedOwnerUuids().contains(actorOwner.toString());
    }

    private static boolean isFortMutationAllowed(FortificationPersistenceService.SavedFortification fort,
                                                 UUID fortOwner,
                                                 UUID actorOwner) {
        // Legacy/unclaimed forts: no ownership lock.
        if (fortOwner == null) {
            return true;
        }
        if (actorOwner != null && fortOwner.equals(actorOwner)) {
            return true;
        }

        String policy = normalizePolicy(fort.getOwnershipPolicy());
        if ("public".equals(policy)) {
            return true;
        }

        if (actorOwner == null) {
            return false;
        }
        return fort.getAllowedOwnerUuids().contains(actorOwner.toString());
    }

    public static UUID resolveBotOwnerUuid(ServerPlayerEntity bot) {
        if (bot == null || Frens.CONFIG == null) {
            return null;
        }
        try {
            ManualConfig.BotOwnership ownership = Frens.CONFIG.getOwner(bot.getName().getString());
            if (ownership == null || ownership.ownerUuid() == null || ownership.ownerUuid().isBlank()) {
                return null;
            }
            return UUID.fromString(ownership.ownerUuid());
        } catch (Throwable ignored) {
            return null;
        }
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

    private static boolean containsXZ(List<VillageFortificationLayoutService.WallPoint> hull, int x, int z) {
        if (hull == null || hull.size() < 3) {
            return false;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (VillageFortificationLayoutService.WallPoint p : hull) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        return VillageFortificationLayoutService.pointInConvexHull(hull, x, z);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizePolicy(String value) {
        if (value == null || value.isBlank()) {
            return "owner_only";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("public".equals(normalized)) {
            return "public";
        }
        if ("allowlist".equals(normalized) || "owner_only".equals(normalized)) {
            return normalized;
        }
        return "owner_only";
    }
}