package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pure privacy rule for digested player memories (1.1.224, Modrinth audit BLOCKER 3): what a
 * player told a bot in a DM must not reach a prompt whose output others may hear.
 *
 * <p>A {@link SoulTypes.MemoryVisibility#PUBLIC} memory is always admitted. A
 * {@link SoulTypes.MemoryVisibility#PRIVATE} memory (private to its own {@code playerId}) is
 * admitted only when
 * <ol>
 *   <li>the prompt is the addressed single-bot DM reply to that same player, or</li>
 *   <li>the output may be heard by others, but the only human players online are that player
 *       (single-player / alone-on-server).</li>
 * </ol>
 * An empty or {@code null} online-humans set means "unknown", never "alone": it withholds.
 * No Minecraft types — the online set is captured on the server thread by the caller.
 */
public final class SoulPrivacyPolicy {

    private SoulPrivacyPolicy() {
    }

    /**
     * The audience of one prompt's output.
     *
     * @param directToAddressee true only for the single-bot DM reply delivered to {@code addressee} alone
     * @param addressee the player the output is addressed to (may be null for ambient output)
     * @param onlineHumans human players online when the turn was created (fake bots excluded);
     *     empty means unknown and fails closed
     */
    public record Audience(boolean directToAddressee, UUID addressee, Set<UUID> onlineHumans) {
        public Audience {
            onlineHumans = onlineHumans == null ? Set.of() : Set.copyOf(onlineHumans);
        }

        /** The addressed single-bot DM reply to {@code player}. */
        public static Audience directMessage(UUID player) {
            return new Audience(true, player, Set.of());
        }

        /** Output that anyone in earshot may hear (group scene, local chime-in, banter). */
        public static Audience shared(UUID addressee, Set<UUID> onlineHumans) {
            return new Audience(false, addressee, onlineHumans);
        }
    }

    /** A filter result: the admitted memories, in input order, plus how many PRIVATE ones were held back. */
    public record Filtered(List<SoulTypes.PlayerMemory> admitted, int withheld) {
        public Filtered {
            admitted = admitted == null ? List.of() : List.copyOf(admitted);
        }
    }

    /** DIRECT transcripts are private to the player; every other channel was already shared. SYSTEM fails closed. */
    public static SoulTypes.MemoryVisibility visibilityFor(SoulTypes.Channel channel) {
        if (channel == null) {
            return SoulTypes.MemoryVisibility.PRIVATE;
        }
        return switch (channel) {
            case PARTY, LOCAL, BANTER -> SoulTypes.MemoryVisibility.PUBLIC;
            case DIRECT, SYSTEM -> SoulTypes.MemoryVisibility.PRIVATE;
        };
    }

    /** The stricter of two tags: PRIVATE wins; a null side counts as PRIVATE. */
    public static SoulTypes.MemoryVisibility stricter(SoulTypes.MemoryVisibility a, SoulTypes.MemoryVisibility b) {
        return a == SoulTypes.MemoryVisibility.PUBLIC && b == SoulTypes.MemoryVisibility.PUBLIC
                ? SoulTypes.MemoryVisibility.PUBLIC
                : SoulTypes.MemoryVisibility.PRIVATE;
    }

    /** Whether {@code memory} may enter a prompt whose output reaches {@code audience}. */
    public static boolean admits(SoulTypes.PlayerMemory memory, Audience audience) {
        if (memory == null) {
            return false;
        }
        if (memory.visibility() == SoulTypes.MemoryVisibility.PUBLIC) {
            return true;
        }
        return admitsPrivateOf(memory.playerId(), audience);
    }

    /**
     * The one PRIVATE rule, shared by memories and transcript records: material private to
     * {@code owner} may reach {@code audience} only as the addressed DM reply to that owner, or
     * when that owner is the only human online. A null owner or audience, or an unknown (empty)
     * online set on a shared audience, withholds.
     */
    public static boolean admitsPrivateOf(UUID owner, Audience audience) {
        if (owner == null || audience == null) {
            return false;
        }
        if (audience.directToAddressee() && owner.equals(audience.addressee())) {
            return true;
        }
        Set<UUID> online = audience.onlineHumans();
        return online.size() == 1 && online.contains(owner);
    }

    /**
     * Whether a transcript record may be replayed into a prompt whose output reaches
     * {@code audience}. An unmarked record ({@code privateTo == null}) is ordinary party speech
     * and always admitted; a record marked private to P (a line from a privately seeded scene)
     * is admitted only where P's PRIVATE memories would be ({@link #admitsPrivateOf}).
     */
    public static boolean admitsRecord(SoulTypes.ConversationRecord record, Audience audience) {
        if (record == null) {
            return false;
        }
        return record.privateTo() == null || admitsPrivateOf(record.privateTo(), audience);
    }

    /**
     * Whether {@code records} holds at least one PRIVATE-marked record that {@code audience}
     * admits — i.e. replaying them makes the prompt privately seeded for that record's owner.
     * Returns that owner, or null when every admitted record is ordinary.
     */
    public static UUID admittedPrivateRecordOwner(List<SoulTypes.ConversationRecord> records, Audience audience) {
        for (SoulTypes.ConversationRecord record : records == null ? List.<SoulTypes.ConversationRecord>of() : records) {
            if (record != null && record.privateTo() != null && admitsPrivateOf(record.privateTo(), audience)) {
                return record.privateTo();
            }
        }
        return null;
    }

    /**
     * Combines privacy provenance from the seed, ABOUT block, and admitted history. If distinct
     * owners appear, no single-owner playback gate can protect both; callers must discard the
     * scene before generation. The first owner remains in the decision for diagnostics only.
     */
    public record ScenePrivacy(UUID owner, boolean conflictingOwners) {
    }

    public static ScenePrivacy strictestOwner(UUID seed, UUID assembly, UUID history) {
        UUID first = seed != null ? seed : assembly != null ? assembly : history;
        boolean conflict = first != null
                && ((seed != null && !seed.equals(first))
                        || (assembly != null && !assembly.equals(first))
                        || (history != null && !history.equals(first)));
        return new ScenePrivacy(first, conflict);
    }

    /**
     * The tag of a memory digested from {@code sources} read off a {@code channel} transcript:
     * the channel's own tag ({@link #visibilityFor}), made PRIVATE when any source record is
     * marked {@code privateTo} — the stricter tag wins. A party digest only ever writes memories
     * about the party owner (the transcript key's player, whom the clerk's validator requires the
     * facts to name), and a privately seeded scene is always seeded for that same owner, so the
     * resulting PRIVATE memory is private to exactly the player the marked record was private to.
     * {@link SoulMemoryDigestOps#gather} additionally drops any record marked private to someone
     * other than the digested player, so no such memory can be written for anyone else.
     */
    public static SoulTypes.MemoryVisibility digestVisibility(SoulTypes.Channel channel,
                                                             List<SoulTypes.ConversationRecord> sources) {
        SoulTypes.MemoryVisibility fromSources = SoulTypes.MemoryVisibility.PUBLIC;
        for (SoulTypes.ConversationRecord record : sources == null ? List.<SoulTypes.ConversationRecord>of() : sources) {
            if (record != null && record.privateTo() != null) {
                fromSources = SoulTypes.MemoryVisibility.PRIVATE;
                break;
            }
        }
        return stricter(visibilityFor(channel), fromSources);
    }

    /**
     * Whether {@code record} may be fed to the memory digest of {@code digestedPlayer}: unmarked
     * records always; a marked record only when it is private to that same player. Records
     * private to anyone else are withheld from the material entirely (conservative: a memory
     * about another player is never derived from them).
     */
    public static boolean digestible(SoulTypes.ConversationRecord record, UUID digestedPlayer) {
        if (record == null) {
            return false;
        }
        return record.privateTo() == null || record.privateTo().equals(digestedPlayer);
    }

    /**
     * Per-line delivery gate for a privately seeded scene (a shared scene whose prompt admitted at
     * least one of {@code owner}'s PRIVATE memories under the alone-on-server exception). The
     * online set that admitted it was captured at turn creation, so playback re-checks every line
     * against its actual human recipients: the line may go out only when nobody but the owner
     * would receive it. No recipients delivers nothing, so it is harmless and allowed. A null
     * owner or recipient set fails closed.
     */
    public static boolean mayDeliverPrivatelySeededLine(UUID owner, Set<UUID> recipientHumans) {
        if (owner == null || recipientHumans == null) {
            return false;
        }
        for (UUID recipient : recipientHumans) {
            if (!owner.equals(recipient)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a scene may leave anything behind in the roster's minds that a later SHARED prompt
     * reads: an open thread from its closing question, peer stances from who asked whom, or the
     * {@code ##FRENS} side channel's relation facts and stance deltas. A privately seeded scene
     * ({@code privateSeedOwner} non-null) was built from the owner's DM-private material and heard
     * by the owner alone, so it derives none of them; its lines survive only in the party
     * transcript, marked {@code privateTo} the owner.
     */
    public static boolean mayDeriveSharedArtifacts(UUID privateSeedOwner) {
        return privateSeedOwner == null;
    }

    /** {@link #admits} as a predicate, for the memory readers in {@link SoulMemoryDigestOps}. */
    public static Predicate<SoulTypes.PlayerMemory> admitting(Audience audience) {
        return memory -> admits(memory, audience);
    }

    /** Splits {@code memories} into admitted (input order) and a withheld count. */
    public static Filtered filter(List<SoulTypes.PlayerMemory> memories, Audience audience) {
        List<SoulTypes.PlayerMemory> admitted = new ArrayList<>();
        int withheld = 0;
        for (SoulTypes.PlayerMemory memory : memories == null ? List.<SoulTypes.PlayerMemory>of() : memories) {
            if (memory == null) {
                continue;
            }
            if (admits(memory, audience)) {
                admitted.add(memory);
            } else {
                withheld++;
            }
        }
        return new Filtered(admitted, withheld);
    }
}
