package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.services.construction.FortificationPersistenceService;
import net.wcfcarolina13.GameAI.services.construction.OwnedScaffoldRegistry;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService;
import net.wcfcarolina13.GameAI.skills.support.WoodcutBaseAccessPolicy;

import java.util.ArrayList;
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
        return authorizeBlockMutation(bot, world, pos, false);
    }

    /**
     * @param treeBlock true when the block is a log or leaves. Only then may the admin
     *                  "Cut trees in the Spawn base" toggle exempt a base zone owned literally by SERVER
     *                  ({@link WoodcutBaseAccessPolicy#miningExempt}). Every other zone, and every
     *                  non-tree block, is decided exactly as before.
     */
    public static AuthorizationResult authorizeBlockMutation(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                                             boolean treeBlock) {
        return authorizeBlockMutation(bot, world, pos,
                treeBlock ? WoodcutBaseAccessPolicy.MutationKind.TREE_BLOCK : WoodcutBaseAccessPolicy.MutationKind.OTHER,
                false);
    }

    /** {@link #authorizeBlockMutation(ServerPlayerEntity, ServerWorld, BlockPos, WoodcutBaseAccessPolicy.MutationKind, boolean)} for a break. */
    public static AuthorizationResult authorizeBlockMutation(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                                             WoodcutBaseAccessPolicy.MutationKind kind) {
        return authorizeBlockMutation(bot, world, pos, kind, false);
    }

    /**
     * Claim gate with the Spawn-base mutation kind ({@link WoodcutBaseAccessPolicy#mutationExempt}).
     * Only a SERVER-owned base zone can be exempted, and never for {@code OTHER}.
     *
     * <p>{@code OWN_SCAFFOLD_BREAK} is not trusted from the caller: ownership is re-read from this
     * bot's {@link OwnedScaffoldRegistry} entry and the live block must still be a scaffold
     * material, so passing the kind can never open a block the bot did not place.
     *
     * @param placeMaterialIsScaffold read only for {@code OWN_SCAFFOLD_PLACE}: the block about to
     *                                be placed is a scaffold material
     */
    public static AuthorizationResult authorizeBlockMutation(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                                             WoodcutBaseAccessPolicy.MutationKind kind,
                                                             boolean placeMaterialIsScaffold) {
        if (bot == null || world == null || pos == null) {
            return ALLOW;
        }
        if (kind == null) {
            kind = WoodcutBaseAccessPolicy.MutationKind.OTHER;
        }

        UUID botOwner = resolveBotOwnerUuid(bot);
        MinecraftServer server = world.getServer();
        boolean needsToggle = kind == WoodcutBaseAccessPolicy.MutationKind.TREE_BLOCK
                || kind == WoodcutBaseAccessPolicy.MutationKind.OWN_SCAFFOLD_PLACE;
        boolean spawnBaseWoodcutAllowed = needsToggle && BotHomeService.isSpawnBaseWoodcutAllowed(server);
        boolean ownedByThisBot = false;
        boolean scaffoldMaterial = false;
        if (kind == WoodcutBaseAccessPolicy.MutationKind.OWN_SCAFFOLD_BREAK) {
            ownedByThisBot = OwnedScaffoldRegistry.isOwned(bot, world, pos);
            scaffoldMaterial = ownedByThisBot && OwnedScaffoldRegistry.isScaffoldMaterial(world.getBlockState(pos));
        } else if (kind == WoodcutBaseAccessPolicy.MutationKind.OWN_SCAFFOLD_PLACE) {
            scaffoldMaterial = placeMaterialIsScaffold;
        }

        // Protected-zone ownership (cubic claim). Every containing zone is asked (ZoneOverlapPolicy):
        // any refusing zone refuses, except a SERVER-owned base zone (the Spawn base) yields to an
        // overlapping player zone that explicitly grants the bot's owner, so a host's base drawn over
        // Spawn keeps working. The Spawn toggle only ever opens logs/leaves and (1.1.268) the bot's
        // own flagged woodcut scaffold; removing owned scaffold needs no toggle.
        List<ProtectedZoneService.ProtectedZone> containing = new ArrayList<>();
        List<ZoneOverlapPolicy.ZoneVerdict> verdicts = new ArrayList<>();
        for (ProtectedZoneService.ProtectedZone zone : ProtectedZoneService.listZones(world)) {
            if (zone == null || !zone.contains(pos)) {
                continue;
            }
            boolean grantsOwner = isZoneExplicitGrant(zone, botOwner);
            boolean refuses = !isZoneMutationAllowed(zone, botOwner);
            // Only zones without a player owner can be server bases; skip the base lookup otherwise.
            boolean serverOwned = zone.getOwnerUuid() == null
                    && BotHomeService.isServerOwnedBaseZone(server, world, zone);
            if (refuses && WoodcutBaseAccessPolicy.mutationExempt(serverOwned, kind, ownedByThisBot,
                    scaffoldMaterial, spawnBaseWoodcutAllowed)) {
                refuses = false;
            }
            containing.add(zone);
            verdicts.add(new ZoneOverlapPolicy.ZoneVerdict(refuses, serverOwned, grantsOwner));
        }
        int refusing = ZoneOverlapPolicy.firstRefusingIndex(verdicts);
        if (refusing >= 0) {
            ProtectedZoneService.ProtectedZone zone = containing.get(refusing);
            return new AuthorizationResult(
                    false,
                    "protected-zone",
                    safe(zone.getLabel()),
                    safe(zone.getOwnerName())
            );
        }

        // Fort interior ownership (convex-hull claim)
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

    /**
     * The zone names the bot's owner: as the zone owner or on its allowlist. Public mode is not an
     * explicit grant, so a public area cannot open an overlapping Spawn base to everyone.
     */
    private static boolean isZoneExplicitGrant(ProtectedZoneService.ProtectedZone zone, UUID actorOwner) {
        if (zone == null || actorOwner == null) {
            return false;
        }
        UUID zoneOwner = zone.getOwnerUuid();
        if (zoneOwner == null) {
            return false;
        }
        return zoneOwner.equals(actorOwner) || zone.getAllowedOwnerUuids().contains(actorOwner.toString());
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