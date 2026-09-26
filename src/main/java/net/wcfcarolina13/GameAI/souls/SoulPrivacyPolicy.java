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
        if (audience == null) {
            return false;
        }
        UUID owner = memory.playerId();
        if (audience.directToAddressee() && owner.equals(audience.addressee())) {
            return true;
        }
        Set<UUID> online = audience.onlineHumans();
        return online.size() == 1 && online.contains(owner);
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
