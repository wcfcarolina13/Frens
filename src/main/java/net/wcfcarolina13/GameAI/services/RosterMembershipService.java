package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GraphicalUserInterface.RosterPresencePolicy;
import net.wcfcarolina13.GraphicalUserInterface.RosterScopePolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Companions roster lists, read from the server's own stores. Shared by the singleplayer
 * roster (integrated server) and the multiplayer roster request ({@code RosterNetworkManager}),
 * so both scope companions to the open world the same way. Read-only; call on the server thread.
 */
public final class RosterMembershipService {

    private RosterMembershipService() {
    }

    /** Lower-cased aliases with a per-world record in the open save, and with one in any save. */
    public record Membership(Set<String> inThisWorld, Set<String> withAnyWorld) {
    }

    /**
     * Every alias with a saved record in {@code config} (profile, owner, alias list, quest memory),
     * trimmed, without blanks or the placeholder {@code default}, one entry per name ignoring case
     * ({@link #preferredSpelling}), sorted case-insensitively.
     */
    public static List<String> savedAliases(ManualConfig config) {
        LinkedHashSet<String> raw = new LinkedHashSet<>();
        if (config != null) {
            raw.addAll(config.getBotGameProfile().keySet());
            raw.addAll(config.getBotOwnership().keySet());
            raw.addAll(config.getAllBotAliases());
            raw.addAll(config.getBotQuestMemory().keySet());
        }
        Map<String, String> byKey = new java.util.LinkedHashMap<>();
        for (String alias : raw) {
            if (alias == null) {
                continue;
            }
            String trimmed = alias.trim();
            if (trimmed.isEmpty() || "default".equalsIgnoreCase(trimmed)) {
                continue;
            }
            byKey.merge(RosterPresencePolicy.normalize(trimmed), trimmed, RosterMembershipService::preferredSpelling);
        }
        List<String> out = new ArrayList<>(byKey.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Of two spellings of one name, the one to show: some stores keep lower-cased keys, so an
     * all-lower-case spelling loses to one with capitals ("Abby" over "abby"); otherwise the first
     * seen stays.
     */
    static String preferredSpelling(String kept, String other) {
        boolean keptLower = kept.equals(kept.toLowerCase(java.util.Locale.ROOT));
        boolean otherLower = other.equals(other.toLowerCase(java.util.Locale.ROOT));
        return keptLower && !otherLower ? other : kept;
    }

    /** Which companions have a spawn point or saved position in the open save. */
    public static Membership membership(MinecraftServer server, ManualConfig config) {
        String fingerprint = BotWorldStateService.currentWorldKey(server);
        String levelName = BotWorldStateService.currentLevelName(server);
        Set<String> here = new HashSet<>();
        Set<String> any = new HashSet<>();
        Map<String, Set<String>> keysByAlias = new HashMap<>();
        if (config != null) {
            for (Map.Entry<String, Map<String, ManualConfig.BotSpawn>> e : config.getBotSpawnPointsByWorld().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    keysByAlias.computeIfAbsent(RosterPresencePolicy.normalize(e.getKey()), k -> new HashSet<>())
                            .addAll(e.getValue().keySet());
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : BotWorldStateService.worldKeysByAlias().entrySet()) {
            keysByAlias.computeIfAbsent(RosterPresencePolicy.normalize(e.getKey()), k -> new HashSet<>())
                    .addAll(e.getValue());
        }
        for (Map.Entry<String, Set<String>> e : keysByAlias.entrySet()) {
            for (String key : e.getValue()) {
                if (RosterScopePolicy.isUnscopedKey(key)) {
                    continue;
                }
                any.add(e.getKey());
                if (RosterScopePolicy.isCurrentWorldKey(key, fingerprint, levelName)) {
                    here.add(e.getKey());
                }
            }
        }
        return new Membership(here, any);
    }
}
