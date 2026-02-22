package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic identity diagnostics for alias/UUID persistence consistency.
 * This is intentionally read-only and safe to run in production worlds.
 */
public final class BotIdentityService {
    private static final Logger LOGGER = LoggerFactory.getLogger("bot-identity");

    private BotIdentityService() {}

    public static String normalizeAlias(String alias) {
        if (alias == null) {
            return "";
        }
        return alias.trim().toLowerCase(Locale.ROOT);
    }

    public static IdentityDebugSnapshot inspect(MinecraftServer server, String aliasInput) {
        String requestedAlias = aliasInput == null ? "" : aliasInput.trim();
        String normalizedAlias = normalizeAlias(aliasInput);
        ManualConfig config = Frens.CONFIG;

        Map<String, String> profileMap = config != null && config.getBotGameProfile() != null
                ? config.getBotGameProfile()
                : Collections.emptyMap();
        Map<String, ManualConfig.BotOwnership> ownerMap = config != null && config.getBotOwnership() != null
                ? config.getBotOwnership()
                : Collections.emptyMap();
        Map<String, ManualConfig.BotSpawn> spawnMap = config != null && config.getBotSpawnPoints() != null
                ? config.getBotSpawnPoints()
                : Collections.emptyMap();
        Map<String, ManualConfig.BotControlSettings> controlMap = config != null && config.getBotControls() != null
                ? config.getBotControls()
                : Collections.emptyMap();
        Map<String, ManualConfig.BotQuestMemory> questMap = config != null && config.getBotQuestMemory() != null
                ? config.getBotQuestMemory()
                : Collections.emptyMap();

        List<String> profileAliasKeys = matchingAliasKeys(profileMap.keySet(), normalizedAlias);
        List<String> ownerAliasKeys = matchingAliasKeys(ownerMap.keySet(), normalizedAlias);
        List<String> spawnAliasKeys = matchingAliasKeys(spawnMap.keySet(), normalizedAlias);
        List<String> controlAliasKeys = matchingAliasKeys(controlMap.keySet(), normalizedAlias);
        List<String> questAliasKeys = matchingAliasKeys(questMap.keySet(), normalizedAlias);

        List<String> warnings = new ArrayList<>();
        Map<String, Set<String>> mapVariants = Map.of(
                "profiles", new LinkedHashSet<>(profileAliasKeys),
                "ownership", new LinkedHashSet<>(ownerAliasKeys),
                "spawn", new LinkedHashSet<>(spawnAliasKeys),
                "controls", new LinkedHashSet<>(controlAliasKeys),
                "questMemory", new LinkedHashSet<>(questAliasKeys)
        );
        for (Map.Entry<String, Set<String>> entry : mapVariants.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add(entry.getKey() + " has case/whitespace variants: " + String.join(", ", entry.getValue()));
            }
        }

        String configAliasKey = first(profileAliasKeys);
        String configUuidRaw = configAliasKey != null ? profileMap.get(configAliasKey) : null;
        UUID configUuid = tryParseUuid(configUuidRaw);
        if (configUuidRaw != null && configUuid == null) {
            warnings.add("config profile UUID is invalid: " + configUuidRaw);
        }
        if (configAliasKey == null) {
            warnings.add("no profile alias key found in config for requested alias");
        }

        ServerPlayerEntity onlineBot = null;
        if (server != null && server.getPlayerManager() != null) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!(player instanceof createFakePlayer) || player.isRemoved()) {
                    continue;
                }
                if (normalizeAlias(player.getGameProfile().name()).equals(normalizedAlias)) {
                    onlineBot = player;
                    break;
                }
            }
        }
        String onlineAlias = onlineBot != null ? onlineBot.getGameProfile().name() : null;
        UUID onlineUuid = onlineBot != null ? onlineBot.getUuid() : null;

        if (configUuid != null && onlineUuid != null && !configUuid.equals(onlineUuid)) {
            warnings.add("online bot UUID does not match config profile UUID");
        }
        if (onlineBot == null) {
            warnings.add("no online fake-player currently matches alias");
        }

        boolean activeTask = onlineUuid != null && TaskService.hasActiveTask(onlineUuid);
        SkillResumeService.DebugSnapshot resumeSnapshot = SkillResumeService.debugForBot(
                onlineUuid != null ? onlineUuid : configUuid
        );

        boolean worldStatePresentCurrentWorld = false;
        List<String> worldStateAliasKeys = List.of();
        if (server != null) {
            worldStatePresentCurrentWorld = BotWorldStateService.loadState(server, normalizedAlias).isPresent();
            worldStateAliasKeys = matchingAliasKeys(BotWorldStateService.debugAliasWorldCounts().keySet(), normalizedAlias);
            if (!worldStatePresentCurrentWorld && !worldStateAliasKeys.isEmpty()) {
                warnings.add("world-state alias exists but not for current world key");
            }
        }

        List<String> inventoryFiles = findInventorySnapshots(server, normalizedAlias, profileAliasKeys);
        if (inventoryFiles.size() > 1) {
            warnings.add("multiple inventory snapshots exist for alias; verify UUID consistency");
        }

        List<String> relatedAliases = collectRelatedAliasKeys(
                profileAliasKeys,
                ownerAliasKeys,
                spawnAliasKeys,
                controlAliasKeys,
                questAliasKeys,
                worldStateAliasKeys
        );

        return new IdentityDebugSnapshot(
                requestedAlias,
                normalizedAlias,
                configAliasKey,
                configUuidRaw,
                configUuid,
                onlineAlias,
                onlineUuid,
                relatedAliases,
                profileAliasKeys,
                ownerAliasKeys,
                spawnAliasKeys,
                controlAliasKeys,
                questAliasKeys,
                worldStateAliasKeys,
                worldStatePresentCurrentWorld,
                inventoryFiles,
                activeTask,
                resumeSnapshot,
                warnings
        );
    }

    private static List<String> collectRelatedAliasKeys(List<String>... lists) {
        Set<String> unique = new LinkedHashSet<>();
        for (List<String> list : lists) {
            if (list == null) {
                continue;
            }
            unique.addAll(list);
        }
        List<String> out = new ArrayList<>(unique);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
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

    private static List<String> matchingAliasKeys(Collection<String> keys, String normalizedAlias) {
        if (keys == null || normalizedAlias == null || normalizedAlias.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String key : keys) {
            if (normalizeAlias(key).equals(normalizedAlias)) {
                matches.add(key);
            }
        }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        return matches;
    }

    private static List<String> findInventorySnapshots(MinecraftServer server, String normalizedAlias, List<String> profileAliasKeys) {
        if (server == null) {
            return List.of();
        }
        Path dir = server.getSavePath(WorldSavePath.PLAYERDATA).resolve("frens").resolve("inventories");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Set<String> prefixes = new HashSet<>();
        prefixes.add(BotInventoryStorageService.sanitizeAliasForStorage(normalizedAlias));
        if (profileAliasKeys != null) {
            for (String key : profileAliasKeys) {
                prefixes.add(BotInventoryStorageService.sanitizeAliasForStorage(key));
            }
        }
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".nbt"))
                    .forEach(name -> {
                        for (String prefix : prefixes) {
                            if (!prefix.isBlank() && name.startsWith(prefix + "_")) {
                                out.add(name);
                                return;
                            }
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("Failed listing inventory snapshots in {}: {}", dir, e.getMessage());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    public record IdentityDebugSnapshot(
            String requestedAlias,
            String normalizedAlias,
            String configAliasKey,
            String configUuidRaw,
            UUID configUuid,
            String onlineAlias,
            UUID onlineUuid,
            List<String> relatedAliasKeys,
            List<String> profileAliasKeys,
            List<String> ownerAliasKeys,
            List<String> spawnAliasKeys,
            List<String> controlAliasKeys,
            List<String> questAliasKeys,
            List<String> worldStateAliasKeys,
            boolean worldStatePresentCurrentWorld,
            List<String> inventoryFiles,
            boolean taskActive,
            SkillResumeService.DebugSnapshot resumeDebug,
            List<String> warnings
    ) {
    }
}
