package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Commander-scoped persistent woodcut memory shared across a player's bots.
 *
 * <p>This stores local tree-site knowledge that can survive bot restarts:
 * remembered leftover logs, last-seen envelopes, and column outcomes.
 * It is treated as probabilistic memory and always revalidated against live blocks.</p>
 */
public final class WoodcutKnowledgeService {

    private static final Logger LOGGER = LoggerFactory.getLogger("woodcut-knowledge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "woodcut_knowledge.json";
    private static final Object LOCK = new Object();
    private static final int MAX_SITES_PER_SHARE = 192;
    private static final int MAX_LOGS_PER_SITE = 192;
    private static final int TARGET_MATCH_RADIUS = 6;
    private static final long SITE_EXPIRY_MS = 14L * 24L * 60L * 60L * 1000L;

    private static RootData DATA = new RootData();
    private static boolean loaded = false;

    private WoodcutKnowledgeService() {
    }

    public static Optional<TreeDetector.TreeTarget> findRememberedTarget(ServerPlayerEntity bot,
                                                                         ServerWorld world,
                                                                         BlockPos origin,
                                                                         int radius,
                                                                         int vertical,
                                                                         Set<BlockPos> visitedBases,
                                                                         Set<BlockPos> failedBases) {
        if (bot == null || world == null || origin == null) {
            return Optional.empty();
        }
        List<SiteRecord> sites = snapshotSites(bot, world);
        TreeDetector.TreeTarget best = null;
        double bestDist = Double.MAX_VALUE;
        for (SiteRecord site : sites) {
            BlockPos base = siteBase(site);
            if (base == null) {
                continue;
            }
            if (visitedBases != null && visitedBases.contains(base)) {
                continue;
            }
            if (failedBases != null && failedBases.contains(base)) {
                continue;
            }
            if (Math.abs(base.getX() - origin.getX()) > radius + 4
                    || Math.abs(base.getY() - origin.getY()) > vertical + 12
                    || Math.abs(base.getZ() - origin.getZ()) > radius + 4) {
                continue;
            }
            List<BlockPos> liveLogs = liveLogsForSite(world, site);
            if (liveLogs.isEmpty()) {
                continue;
            }
            TreeDetector.TreeTarget target = buildTarget(site, liveLogs);
            if (visitedBases != null && visitedBases.contains(target.base())) {
                continue;
            }
            if (failedBases != null && failedBases.contains(target.base())) {
                continue;
            }
            double distSq = origin.getSquaredDistance(target.base());
            if (distSq < bestDist) {
                bestDist = distSq;
                best = target;
            }
        }
        return Optional.ofNullable(best);
    }

    public static List<BlockPos> mergeRememberedLogs(ServerPlayerEntity bot,
                                                     ServerWorld world,
                                                     TreeDetector.TreeTarget target,
                                                     List<BlockPos> liveLogs) {
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        if (liveLogs != null) {
            liveLogs.stream().filter(pos -> pos != null).map(BlockPos::toImmutable).forEach(merged::add);
        }
        if (bot == null || world == null || target == null) {
            return new ArrayList<>(merged);
        }
        SiteRecord site = findMatchingSite(bot, world, target);
        if (site == null) {
            return new ArrayList<>(merged);
        }
        BlockPos min = target.envelopeMin().add(-2, -2, -2);
        BlockPos max = target.envelopeMax().add(2, 4, 2);
        for (BlockPos pos : liveLogsForSite(world, site)) {
            if (isWithin(pos, min, max)) {
                merged.add(pos.toImmutable());
            }
        }
        List<BlockPos> ordered = new ArrayList<>(merged);
        ordered.sort(Comparator
                .comparingInt(BlockPos::getY)
                .thenComparingInt(pos -> Math.abs(pos.getX() - target.base().getX()) + Math.abs(pos.getZ() - target.base().getZ())));
        return ordered;
    }

    public static void updateTreeSite(ServerPlayerEntity bot,
                                      ServerWorld world,
                                      TreeDetector.TreeTarget target,
                                      List<BlockPos> remainingLogs,
                                      Map<Long, ?> visitedColumns) {
        if (bot == null || world == null || target == null) {
            return;
        }
        synchronized (LOCK) {
            WorldData worldData = worldData(world.getServer(), world);
            List<SiteRecord> sites = worldData.sitesByShare.computeIfAbsent(shareKey(bot), ignored -> new ArrayList<>());
            SiteRecord site = findMatchingSite(sites, target);
            if (remainingLogs == null || remainingLogs.isEmpty()) {
                if (site != null) {
                    sites.remove(site);
                    flush();
                }
                return;
            }
            if (site == null) {
                site = new SiteRecord();
                sites.add(site);
            }
            BlockPos min = target.envelopeMin();
            BlockPos max = target.envelopeMax();
            for (BlockPos pos : remainingLogs) {
                if (pos == null) {
                    continue;
                }
                min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
                max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
            }
            site.baseX = target.base().getX();
            site.baseY = target.base().getY();
            site.baseZ = target.base().getZ();
            site.topX = target.top().getX();
            site.topY = target.top().getY();
            site.topZ = target.top().getZ();
            site.minX = min.getX();
            site.minY = min.getY();
            site.minZ = min.getZ();
            site.maxX = max.getX();
            site.maxY = max.getY();
            site.maxZ = max.getZ();
            site.updatedAtMs = System.currentTimeMillis();
            site.confidence = Math.min(10, Math.max(1, site.confidence + 1));
            site.rememberedLogs = cappedLogs(remainingLogs);
            site.columnStatuses = new LinkedHashMap<>();
            if (visitedColumns != null) {
                for (Map.Entry<Long, ?> entry : visitedColumns.entrySet()) {
                    Long key = entry.getKey();
                    Object value = entry.getValue();
                    if (key != null && value != null) {
                        site.columnStatuses.put(columnKey(key), String.valueOf(value));
                    }
                }
            }
            pruneExpiredSitesLocked();
            trimSites(sites);
            flush();
        }
    }

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            Path file = stateFile();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    RootData parsed = GSON.fromJson(reader, RootData.class);
                    if (parsed != null) {
                        DATA = parsed;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load woodcut knowledge: {}", e.getMessage());
                    DATA = new RootData();
                }
            }
            pruneExpiredSitesLocked();
            loaded = true;
        }
    }

    private static void flush() {
        synchronized (LOCK) {
            try {
                Path file = stateFile();
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    GSON.toJson(DATA, writer);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to save woodcut knowledge: {}", e.getMessage());
            }
        }
    }

    private static WorldData worldData(MinecraftServer server, ServerWorld world) {
        ensureLoaded();
        String key = BotWorldStateService.currentWorldKey(server);
        synchronized (LOCK) {
            if (DATA.worlds == null) {
                DATA.worlds = new HashMap<>();
            }
            pruneExpiredSitesLocked();
            return DATA.worlds.computeIfAbsent(key, ignored -> new WorldData());
        }
    }

    private static List<SiteRecord> snapshotSites(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || world.getServer() == null) {
            return List.of();
        }
        synchronized (LOCK) {
            WorldData worldData = worldData(world.getServer(), world);
            List<SiteRecord> sites = worldData.sitesByShare.get(shareKey(bot));
            return sites == null ? List.of() : new ArrayList<>(sites);
        }
    }

    private static SiteRecord findMatchingSite(ServerPlayerEntity bot, ServerWorld world, TreeDetector.TreeTarget target) {
        synchronized (LOCK) {
            WorldData worldData = worldData(world.getServer(), world);
            List<SiteRecord> sites = worldData.sitesByShare.get(shareKey(bot));
            return findMatchingSite(sites, target);
        }
    }

    private static SiteRecord findMatchingSite(List<SiteRecord> sites, TreeDetector.TreeTarget target) {
        if (sites == null || target == null) {
            return null;
        }
        SiteRecord nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (SiteRecord site : sites) {
            BlockPos siteBase = siteBase(site);
            if (siteBase == null) {
                continue;
            }
            boolean matches = siteBase.getSquaredDistance(target.base()) <= (TARGET_MATCH_RADIUS * TARGET_MATCH_RADIUS)
                    || envelopesOverlap(site, target);
            if (!matches) {
                continue;
            }
            double distSq = siteBase.getSquaredDistance(target.base());
            if (distSq < bestDist) {
                bestDist = distSq;
                nearest = site;
            }
        }
        return nearest;
    }

    private static boolean envelopesOverlap(SiteRecord site, TreeDetector.TreeTarget target) {
        if (site == null || target == null) {
            return false;
        }
        return site.minX <= target.envelopeMax().getX() + 2
                && site.maxX >= target.envelopeMin().getX() - 2
                && site.minY <= target.envelopeMax().getY() + 2
                && site.maxY >= target.envelopeMin().getY() - 2
                && site.minZ <= target.envelopeMax().getZ() + 2
                && site.maxZ >= target.envelopeMin().getZ() - 2;
    }

    private static TreeDetector.TreeTarget buildTarget(SiteRecord site, List<BlockPos> liveLogs) {
        BlockPos base = siteBase(site);
        BlockPos top = new BlockPos(site.topX, site.topY, site.topZ);
        BlockPos min = new BlockPos(site.minX, site.minY, site.minZ);
        BlockPos max = new BlockPos(site.maxX, site.maxY, site.maxZ);
        if (!liveLogs.isEmpty()) {
            BlockPos lowest = liveLogs.stream().min(Comparator.comparingInt(BlockPos::getY)).orElse(base);
            BlockPos highest = liveLogs.stream().max(Comparator.comparingInt(BlockPos::getY)).orElse(top);
            base = lowest != null ? lowest.toImmutable() : base;
            top = highest != null ? highest.toImmutable() : top;
            for (BlockPos pos : liveLogs) {
                min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
                max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
            }
        }
        int height = Math.max(1, top.getY() - base.getY() + 1);
        return new TreeDetector.TreeTarget(base.toImmutable(), top.toImmutable(), height, min.toImmutable(), max.toImmutable(), Map.of(), Set.of());
    }

    private static List<BlockPos> liveLogsForSite(ServerWorld world, SiteRecord site) {
        LinkedHashSet<BlockPos> logs = new LinkedHashSet<>();
        if (world == null || site == null) {
            return List.of();
        }
        if (site.rememberedLogs != null) {
            for (Long packed : site.rememberedLogs) {
                if (packed == null) {
                    continue;
                }
                BlockPos pos = BlockPos.fromLong(packed);
                if (world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                    logs.add(pos.toImmutable());
                }
            }
        }
        if (logs.isEmpty()) {
            BlockPos min = new BlockPos(site.minX, site.minY, site.minZ);
            BlockPos max = new BlockPos(site.maxX, site.maxY, site.maxZ);
            for (BlockPos pos : BlockPos.iterate(min, max)) {
                if (world.getBlockState(pos).isIn(BlockTags.LOGS)) {
                    logs.add(pos.toImmutable());
                }
            }
        }
        return new ArrayList<>(logs);
    }

    private static List<Long> cappedLogs(List<BlockPos> remainingLogs) {
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        if (remainingLogs != null) {
            remainingLogs.stream()
                    .filter(pos -> pos != null)
                    .sorted(Comparator.comparingInt(BlockPos::getY))
                    .forEach(pos -> {
                        if (unique.size() < MAX_LOGS_PER_SITE) {
                            unique.add(pos.asLong());
                        }
                    });
        }
        return new ArrayList<>(unique);
    }

    private static String shareKey(ServerPlayerEntity bot) {
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid != null) {
            return "owner:" + ownerUuid;
        }
        return "bot:" + bot.getName().getString().toLowerCase(Locale.ROOT);
    }

    private static BlockPos siteBase(SiteRecord site) {
        if (site == null) {
            return null;
        }
        return new BlockPos(site.baseX, site.baseY, site.baseZ);
    }

    private static void trimSites(List<SiteRecord> sites) {
        if (sites == null || sites.size() <= MAX_SITES_PER_SHARE) {
            return;
        }
        sites.sort(Comparator.comparingLong((SiteRecord site) -> site.updatedAtMs).reversed());
        while (sites.size() > MAX_SITES_PER_SHARE) {
            sites.remove(sites.size() - 1);
        }
    }

    private static void pruneExpiredSitesLocked() {
        if (DATA.worlds == null || DATA.worlds.isEmpty()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - SITE_EXPIRY_MS;
        boolean changed = false;
        List<String> emptyWorlds = new ArrayList<>();
        for (Map.Entry<String, WorldData> worldEntry : DATA.worlds.entrySet()) {
            WorldData worldData = worldEntry.getValue();
            if (worldData == null || worldData.sitesByShare == null || worldData.sitesByShare.isEmpty()) {
                emptyWorlds.add(worldEntry.getKey());
                continue;
            }
            List<String> emptyShares = new ArrayList<>();
            for (Map.Entry<String, List<SiteRecord>> shareEntry : worldData.sitesByShare.entrySet()) {
                List<SiteRecord> sites = shareEntry.getValue();
                if (sites == null || sites.isEmpty()) {
                    emptyShares.add(shareEntry.getKey());
                    continue;
                }
                int before = sites.size();
                sites.removeIf(site -> site == null || site.updatedAtMs <= 0L || site.updatedAtMs < cutoff);
                if (sites.size() != before) {
                    changed = true;
                }
                if (sites.isEmpty()) {
                    emptyShares.add(shareEntry.getKey());
                }
            }
            if (!emptyShares.isEmpty()) {
                emptyShares.forEach(worldData.sitesByShare::remove);
                changed = true;
            }
            if (worldData.sitesByShare.isEmpty()) {
                emptyWorlds.add(worldEntry.getKey());
            }
        }
        if (!emptyWorlds.isEmpty()) {
            emptyWorlds.forEach(DATA.worlds::remove);
            changed = true;
        }
        if (changed) {
            LOGGER.info("Pruned expired woodcut knowledge older than {} day(s)",
                    SITE_EXPIRY_MS / (24L * 60L * 60L * 1000L));
        }
    }

    private static boolean isWithin(BlockPos pos, BlockPos min, BlockPos max) {
        return pos != null
                && min != null
                && max != null
                && pos.getX() >= min.getX()
                && pos.getX() <= max.getX()
                && pos.getY() >= min.getY()
                && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ()
                && pos.getZ() <= max.getZ();
    }

    private static String columnKey(long packed) {
        BlockPos pos = BlockPos.fromLong(packed);
        return pos.getX() + "," + pos.getZ();
    }

    private static final class RootData {
        Map<String, WorldData> worlds = new HashMap<>();
    }

    private static final class WorldData {
        Map<String, List<SiteRecord>> sitesByShare = new HashMap<>();
    }

    private static final class SiteRecord {
        int baseX;
        int baseY;
        int baseZ;
        int topX;
        int topY;
        int topZ;
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
        long updatedAtMs;
        int confidence;
        List<Long> rememberedLogs = new ArrayList<>();
        Map<String, String> columnStatuses = new LinkedHashMap<>();
    }
}
