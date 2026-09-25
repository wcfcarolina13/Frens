package net.wcfcarolina13.GameAI.services;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure authorization rules for the storage screen's chest-registry network payloads
 * ({@code network/ChestRegistryNetworkManager}) and the fast-travel "withdraw" arrival
 * ({@code NavigationArtifactService}).
 *
 * <p>Deliberately free of any Minecraft imports so it can be unit-tested. Each gate protects:
 * <ul>
 *   <li>{@link #authorize} — who may use the screen on a bot at all: its owner, an operator, or
 *       the integrated-server host, and only for a fake-player companion. Protects every bot's
 *       chest coordinates and contents (request), its registry records (dismiss), and its
 *       movement and inventory (collect, go, Quick Store / Quick Fetch).</li>
 *   <li>{@link #checkTarget} — Go / Collect may only target a live chest in the bot's own
 *       registry, so a forged payload cannot fast-travel the bot to arbitrary coordinates.</li>
 *   <li>{@link #parseMode} / {@link #parseReturnTo} — only the values the screen sends are
 *       accepted, so no arbitrary string reaches the post-arrival action type.</li>
 *   <li>{@link #withinArrivalReach} — the arrival withdraw only takes from a chest the bot
 *       actually landed next to.</li>
 * </ul>
 *
 * <p>The screen is owner-initiated (a person explicitly sends their own bot to their own chest),
 * so it is exempt from the supplies policy that governs autonomous chest use.
 */
public final class ChestRegistryAccessPolicy {

    /**
     * Farthest a legitimate arrival can land from the chest it travelled to. The arrival spawn
     * is {@code SafePositionService.findSafeNear(dest, 3)}, whose columns scan dy -3..+2, else the
     * destination itself, so a real arrival is at most about 5.5 blocks from the chest centre;
     * 8 leaves margin without admitting a bot that landed somewhere else entirely.
     */
    public static final double ARRIVAL_MAX_DISTANCE = 8.0;

    private ChestRegistryAccessPolicy() {}

    /** Outcome of {@link #authorize}; the order of the constants is not the evaluation order. */
    public enum Access {
        ALLOW_OWNER,
        ALLOW_OPERATOR,
        ALLOW_HOST,
        DENY_NO_REQUESTER,
        DENY_NOT_A_BOT,
        DENY_UNOWNED,
        DENY_NOT_OWNER;

        public boolean allowed() {
            return this == ALLOW_OWNER || this == ALLOW_OPERATOR || this == ALLOW_HOST;
        }
    }

    /**
     * Decides whether {@code requester} may drive the chest-registry screen for a bot.
     *
     * <p>Evaluation order: no requester → deny; target is not a fake-player companion → deny
     * (even for an operator); owner → allow; operator → allow; host → allow; un-owned bot → deny;
     * otherwise → deny.
     *
     * @param requester           UUID of the player who sent the payload
     * @param botOwner            configured owner of the bot, or {@code null} if un-owned
     * @param botIsFakePlayer     whether the named player is a Frens companion (not a real human)
     * @param requesterIsOperator whether the requester is an op
     * @param requesterIsHost     whether the requester hosts the integrated server
     */
    public static Access authorize(UUID requester, UUID botOwner, boolean botIsFakePlayer,
                                   boolean requesterIsOperator, boolean requesterIsHost) {
        if (requester == null) return Access.DENY_NO_REQUESTER;
        if (!botIsFakePlayer) return Access.DENY_NOT_A_BOT;
        if (requester.equals(botOwner)) return Access.ALLOW_OWNER;
        if (requesterIsOperator) return Access.ALLOW_OPERATOR;
        if (requesterIsHost) return Access.ALLOW_HOST;
        if (botOwner == null) return Access.DENY_UNOWNED;
        return Access.DENY_NOT_OWNER;
    }

    /** One registry record reduced to what the target check needs. */
    public record ChestKey(int x, int y, int z, boolean destroyed) {}

    /** Outcome of {@link #checkTarget}. */
    public enum Target { OK, DESTROYED, NOT_REGISTERED }

    /**
     * Whether {@code (x, y, z)} is a usable chest in {@code records}: any live record at those
     * coordinates → {@link Target#OK}; only destroyed ones → {@link Target#DESTROYED}; none (or a
     * {@code null} list) → {@link Target#NOT_REGISTERED}.
     */
    public static Target checkTarget(List<ChestKey> records, int x, int y, int z) {
        if (records == null) return Target.NOT_REGISTERED;
        boolean matched = false;
        for (ChestKey r : records) {
            if (r == null || r.x() != x || r.y() != y || r.z() != z) continue;
            if (!r.destroyed()) return Target.OK;
            matched = true;
        }
        return matched ? Target.DESTROYED : Target.NOT_REGISTERED;
    }

    /** Collect-screen modes. */
    public enum Mode { GO, COLLECT }

    /**
     * Parses the payload's {@code mode}: absent ({@code null}) → {@link Mode#COLLECT}, the
     * pre-existing default; {@code "go"} / {@code "collect"} → that mode; anything else → empty.
     * Exact and case-sensitive — the client sends lowercase.
     */
    public static Optional<Mode> parseMode(String raw) {
        if (raw == null) return Optional.of(Mode.COLLECT);
        return switch (raw) {
            case "go" -> Optional.of(Mode.GO);
            case "collect" -> Optional.of(Mode.COLLECT);
            default -> Optional.empty();
        };
    }

    /**
     * Parses the payload's {@code returnTo}: absent ({@code null}) → {@code "stay"};
     * {@code "stay"} / {@code "player"} / {@code "home"} → itself; anything else → empty.
     */
    public static Optional<String> parseReturnTo(String raw) {
        if (raw == null) return Optional.of("stay");
        return switch (raw) {
            case "stay", "player", "home" -> Optional.of(raw);
            default -> Optional.empty();
        };
    }

    /**
     * True when an arrived bot is within {@link #ARRIVAL_MAX_DISTANCE} of the chest centre
     * (inclusive). {@code NaN} and negative inputs are rejected.
     */
    public static boolean withinArrivalReach(double distanceSq) {
        if (Double.isNaN(distanceSq) || distanceSq < 0) return false;
        return distanceSq <= ARRIVAL_MAX_DISTANCE * ARRIVAL_MAX_DISTANCE;
    }
}
