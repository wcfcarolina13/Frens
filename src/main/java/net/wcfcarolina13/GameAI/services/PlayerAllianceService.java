package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reciprocal player-to-player alliances.
 *
 * <p>Model: alliances are mutual, consent-based, and symmetric. One side sends an invitation;
 * the other side accepts (by sending an invite in the opposite direction). The service tracks
 * outstanding invites in {@link #pendingInvitesByRecipient} and confirmed bonds in
 * {@link #bondsByPlayer}. Revoke breaks a bond both ways OR cancels an outgoing invite.
 *
 * <p>State is persisted to {@code config/frens/alliances.json}. Name cache lets the UI and
 * commands show human-readable player names without needing the offline profile cache.
 *
 * <p>Callers that need an "are these two players on each other's side?" check use
 * {@link #areAllied(String, String)}. Used by Phase-5 overlap rejection and (optionally, if we
 * extend it later) {@link BotHomeService#canEditBase} for shared-editing semantics.
 */
public final class PlayerAllianceService {

    private static final Logger LOGGER = LoggerFactory.getLogger("player-alliances");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "alliances.json";
    private static final Object LOCK = new Object();

    private static RootData DATA = new RootData();
    private static boolean loaded = false;

    private PlayerAllianceService() {}

    public enum InviteResult {
        /** Invitation sent; target hasn't reciprocated yet. */
        INVITED,
        /** Target had already invited us — bond formed immediately. */
        ALLIED_NOW,
        /** Already in a confirmed bond. No-op. */
        ALREADY_BONDED,
        /** Invite already pending in the same direction. No-op. */
        ALREADY_INVITED,
        /** Inviter and target are the same player. Rejected. */
        SELF,
        /** Missing UUID input. Caller-side bug or unresolved target. */
        INVALID
    }

    public enum RevokeResult {
        BOND_BROKEN,
        INVITE_CANCELED,
        NOT_FOUND
    }

    // --- Public API ---------------------------------------------------------

    public static synchronized InviteResult invite(String inviterUuid, String inviterName,
                                                   String targetUuid, String targetName) {
        if (inviterUuid == null || targetUuid == null) return InviteResult.INVALID;
        if (inviterUuid.equals(targetUuid)) return InviteResult.SELF;
        ensureLoaded();
        synchronized (LOCK) {
            rememberName(inviterUuid, inviterName);
            rememberName(targetUuid, targetName);

            if (isBondedInternal(inviterUuid, targetUuid)) {
                return InviteResult.ALREADY_BONDED;
            }

            Set<String> incomingToInviter = DATA.pendingInvitesByRecipient
                    .getOrDefault(inviterUuid, new HashSet<>());
            if (incomingToInviter.contains(targetUuid)) {
                // Target previously invited us -> mutual consent now; form bond.
                incomingToInviter.remove(targetUuid);
                if (incomingToInviter.isEmpty()) {
                    DATA.pendingInvitesByRecipient.remove(inviterUuid);
                } else {
                    DATA.pendingInvitesByRecipient.put(inviterUuid, incomingToInviter);
                }
                addBond(inviterUuid, targetUuid);
                flush();
                return InviteResult.ALLIED_NOW;
            }

            Set<String> incomingToTarget = DATA.pendingInvitesByRecipient
                    .computeIfAbsent(targetUuid, k -> new HashSet<>());
            if (!incomingToTarget.add(inviterUuid)) {
                return InviteResult.ALREADY_INVITED;
            }
            flush();
            return InviteResult.INVITED;
        }
    }

    public static synchronized RevokeResult revoke(String actorUuid, String targetUuid) {
        if (actorUuid == null || targetUuid == null || actorUuid.equals(targetUuid)) {
            return RevokeResult.NOT_FOUND;
        }
        ensureLoaded();
        synchronized (LOCK) {
            if (isBondedInternal(actorUuid, targetUuid)) {
                removeBond(actorUuid, targetUuid);
                flush();
                return RevokeResult.BOND_BROKEN;
            }
            // Cancel outgoing invite (actor -> target)
            Set<String> incomingToTarget = DATA.pendingInvitesByRecipient.get(targetUuid);
            if (incomingToTarget != null && incomingToTarget.remove(actorUuid)) {
                if (incomingToTarget.isEmpty()) {
                    DATA.pendingInvitesByRecipient.remove(targetUuid);
                }
                flush();
                return RevokeResult.INVITE_CANCELED;
            }
            // Also cancel incoming invite (target -> actor), treated as "declining"
            Set<String> incomingToActor = DATA.pendingInvitesByRecipient.get(actorUuid);
            if (incomingToActor != null && incomingToActor.remove(targetUuid)) {
                if (incomingToActor.isEmpty()) {
                    DATA.pendingInvitesByRecipient.remove(actorUuid);
                }
                flush();
                return RevokeResult.INVITE_CANCELED;
            }
            return RevokeResult.NOT_FOUND;
        }
    }

    public static boolean areAllied(String uuidA, String uuidB) {
        if (uuidA == null || uuidB == null || uuidA.equals(uuidB)) return false;
        ensureLoaded();
        synchronized (LOCK) {
            return isBondedInternal(uuidA, uuidB);
        }
    }

    public record AllianceSnapshot(List<NamedUuid> bonds,
                                   List<NamedUuid> incomingInvites,
                                   List<NamedUuid> outgoingInvites) {}
    public record NamedUuid(String uuid, String name) {}

    public static AllianceSnapshot snapshot(String playerUuid) {
        ensureLoaded();
        synchronized (LOCK) {
            List<NamedUuid> bonds = new ArrayList<>();
            Set<String> b = DATA.bondsByPlayer.get(playerUuid);
            if (b != null) {
                for (String u : new TreeSet<>(b)) {
                    bonds.add(new NamedUuid(u, displayName(u)));
                }
            }
            List<NamedUuid> incoming = new ArrayList<>();
            Set<String> in = DATA.pendingInvitesByRecipient.get(playerUuid);
            if (in != null) {
                for (String u : new TreeSet<>(in)) {
                    incoming.add(new NamedUuid(u, displayName(u)));
                }
            }
            List<NamedUuid> outgoing = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : DATA.pendingInvitesByRecipient.entrySet()) {
                if (e.getValue() != null && e.getValue().contains(playerUuid)) {
                    outgoing.add(new NamedUuid(e.getKey(), displayName(e.getKey())));
                }
            }
            outgoing.sort((x, y) -> x.name().compareToIgnoreCase(y.name()));
            return new AllianceSnapshot(bonds, incoming, outgoing);
        }
    }

    // --- Internals ----------------------------------------------------------

    private static boolean isBondedInternal(String a, String b) {
        Set<String> aBonds = DATA.bondsByPlayer.get(a);
        return aBonds != null && aBonds.contains(b);
    }

    private static void addBond(String a, String b) {
        DATA.bondsByPlayer.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        DATA.bondsByPlayer.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    private static void removeBond(String a, String b) {
        Set<String> aBonds = DATA.bondsByPlayer.get(a);
        if (aBonds != null) {
            aBonds.remove(b);
            if (aBonds.isEmpty()) DATA.bondsByPlayer.remove(a);
        }
        Set<String> bBonds = DATA.bondsByPlayer.get(b);
        if (bBonds != null) {
            bBonds.remove(a);
            if (bBonds.isEmpty()) DATA.bondsByPlayer.remove(b);
        }
    }

    private static void rememberName(String uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        DATA.nameByUuid.put(uuid, name);
    }

    private static String displayName(String uuid) {
        String n = DATA.nameByUuid.get(uuid);
        return n != null ? n : uuid;
    }

    public static Optional<String> lookupName(String uuid) {
        ensureLoaded();
        synchronized (LOCK) {
            return Optional.ofNullable(DATA.nameByUuid.get(uuid));
        }
    }

    // --- Persistence --------------------------------------------------------

    private static Path stateFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("frens").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) return;
            try {
                Path p = stateFile();
                if (Files.exists(p)) {
                    try (Reader r = Files.newBufferedReader(p)) {
                        RootData parsed = GSON.fromJson(r, RootData.class);
                        if (parsed != null) {
                            DATA = parsed;
                            if (DATA.bondsByPlayer == null) DATA.bondsByPlayer = new HashMap<>();
                            if (DATA.pendingInvitesByRecipient == null) DATA.pendingInvitesByRecipient = new HashMap<>();
                            if (DATA.nameByUuid == null) DATA.nameByUuid = new HashMap<>();
                        }
                    }
                }
            } catch (IOException | com.google.gson.JsonParseException e) {
                LOGGER.warn("Failed to read alliances.json; starting empty: {}", e.getMessage());
                DATA = new RootData();
            }
            loaded = true;
        }
    }

    private static void flush() {
        Path p = stateFile();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(DATA, w);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write alliances.json: {}", e.getMessage());
        }
    }

    private static final class RootData {
        Map<String, Set<String>> bondsByPlayer = new HashMap<>();
        Map<String, Set<String>> pendingInvitesByRecipient = new HashMap<>();
        Map<String, String> nameByUuid = new HashMap<>();
    }
}
