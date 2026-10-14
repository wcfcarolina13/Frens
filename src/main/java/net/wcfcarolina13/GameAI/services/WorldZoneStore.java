package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * On-disk layout shared by {@link ProtectedZoneService} and {@link LockableBlockService}.
 *
 * <p>Since 1.1.234: {@code <run dir>/bot_zones/<safe fingerprint>/<safe dimension>/}. Before that
 * the store was {@code bot_zones/<safe dimension>/}, shared by every save in the instance, so one
 * world's zones and locked doors loaded in every other world. The legacy directory is never
 * deleted or rewritten; a world that adopts it records itself in {@link #CLAIM_FILE} beside it
 * (see {@link WorldStoreKeyPolicy#decide}). Call on the server thread at world load.
 */
final class WorldZoneStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens");

    static final String ROOT_DIR = "bot_zones";
    /** Plain-text marker next to the legacy data: the fingerprint of the save that adopted it. */
    static final String CLAIM_FILE = "claimed_by_world.txt";

    private WorldZoneStore() {}

    static String fingerprint(MinecraftServer server) {
        return BotWorldStateService.currentWorldKey(server);
    }

    /** This save's directory for one dimension. */
    static Path ownDir(MinecraftServer server, String dimensionId) {
        return server.getRunDirectory()
                .resolve(ROOT_DIR)
                .resolve(WorldStoreKeyPolicy.safeDirName(fingerprint(server)))
                .resolve(WorldStoreKeyPolicy.safeDirName(dimensionId));
    }

    /** The pre-1.1.234 dimension-only directory (shared by all saves). Read-only apart from the claim marker. */
    static Path legacyDir(MinecraftServer server, String dimensionId) {
        return server.getRunDirectory()
                .resolve(ROOT_DIR)
                .resolve(WorldStoreKeyPolicy.safeDirName(dimensionId));
    }

    /** Overworld game time right now; at SERVER_STARTED this is the time the save was loaded with. */
    static long overworldGameTime(MinecraftServer server) {
        try {
            return server.getOverworld() != null ? server.getOverworld().getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    static String readClaimant(Path legacyDir) {
        Path marker = legacyDir.resolve(CLAIM_FILE);
        if (!Files.exists(marker)) {
            return null;
        }
        try {
            String text = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            LOGGER.warn("Could not read world claim marker {}: {}", marker, e.getMessage());
            return null;
        }
    }

    static void writeClaim(Path legacyDir, String fingerprint) {
        Path marker = legacyDir.resolve(CLAIM_FILE);
        try {
            Files.createDirectories(legacyDir);
            Files.writeString(marker, fingerprint, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write world claim marker {}: {}", marker, e.getMessage());
        }
    }

    /**
     * Decide adoption for one store file of one dimension.
     *
     * @param legacyFile the legacy file to adopt from, or null when none exists
     */
    static WorldStoreKeyPolicy.Adoption decide(MinecraftServer server, String dimensionId, Path ownFile, Path legacyFile) {
        boolean hasOwn = Files.exists(ownFile);
        boolean hasLegacy = legacyFile != null && Files.exists(legacyFile);
        String claimant = hasOwn || !hasLegacy ? null : readClaimant(legacyDir(server, dimensionId));
        long gameTime = overworldGameTime(server);
        String fingerprint = fingerprint(server);
        WorldStoreKeyPolicy.Adoption adoption = WorldStoreKeyPolicy.decide(hasOwn, hasLegacy, gameTime, claimant, fingerprint);
        if (adoption != WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY) {
            return adoption;
        }
        // Zones and locks of one dimension read the same zone file and home entry, so they agree.
        boolean belongs = legacyBelongsToThisSave(server, dimensionId);
        adoption = WorldStoreKeyPolicy.decideZoneStore(hasOwn, hasLegacy, gameTime, claimant, fingerprint, belongs);
        if (adoption == WorldStoreKeyPolicy.Adoption.START_EMPTY_NOT_THIS_SAVE) {
            LOGGER.info("Legacy zone/lock store for {} does not match this save ({}): starting empty, legacy left unclaimed",
                    dimensionId, fingerprint);
        }
        return adoption;
    }

    /**
     * MC adapter for {@link WorldStoreKeyPolicy#legacyZonesBelongToThisSave}: this save's legacy
     * bot-home bases against the legacy zone file of the dimension.
     */
    static boolean legacyBelongsToThisSave(MinecraftServer server, String dimensionId) {
        java.util.List<WorldStoreKeyPolicy.LegacyBase> bases = BotHomeService.legacyBasesForSave(server);
        return WorldStoreKeyPolicy.legacyZonesBelongToThisSave(bases != null, bases,
                ProtectedZoneService.legacyZones(server, dimensionId));
    }
}
