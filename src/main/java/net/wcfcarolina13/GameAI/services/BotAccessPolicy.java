package net.wcfcarolina13.GameAI.services;

import java.util.UUID;

/**
 * Pure authorization rule for client packets that act on one named bot: its inventory screen,
 * the sunset return prompt, and the navigation spells. The same order as
 * {@link ChestRegistryAccessPolicy#authorize}.
 *
 * <p>Free of Minecraft imports so it can be unit-tested; {@code network/BotAccessGate} gathers
 * the inputs on the server thread.
 */
public final class BotAccessPolicy {

    private BotAccessPolicy() {}

    /** Outcome of {@link #authorize}; the order of the constants is not the evaluation order. */
    public enum Decision {
        ALLOW_OWNER,
        ALLOW_OP,
        ALLOW_HOST,
        DENY_NOT_BOT,
        DENY_NOT_OWNER,
        DENY_UNOWNED
    }

    /**
     * Decides whether {@code requester} may act on a bot.
     *
     * <p>Evaluation order: the target is not a registered Frens companion → deny (even for an
     * operator or the host, so no packet reaches a real player); owner → allow; operator → allow;
     * integrated-server host → allow (single-player with cheats off has no op); un-owned bot →
     * deny; otherwise → deny.
     *
     * @param requester                 UUID of the player who sent the packet, may be null
     * @param ownerOrNull               configured owner of the bot, or {@code null} if un-owned
     * @param targetIsRegisteredFakeBot whether the target is a fake player in the bot registry
     * @param requesterIsOp             whether the requester is an operator
     * @param requesterIsHost           whether the requester hosts the integrated server
     */
    public static Decision authorize(UUID requester, UUID ownerOrNull, boolean targetIsRegisteredFakeBot,
                                     boolean requesterIsOp, boolean requesterIsHost) {
        if (!targetIsRegisteredFakeBot) return Decision.DENY_NOT_BOT;
        if (requester != null && requester.equals(ownerOrNull)) return Decision.ALLOW_OWNER;
        if (requesterIsOp) return Decision.ALLOW_OP;
        if (requesterIsHost) return Decision.ALLOW_HOST;
        if (ownerOrNull == null) return Decision.DENY_UNOWNED;
        return Decision.DENY_NOT_OWNER;
    }

    /** True for the three {@code ALLOW_*} decisions; false for null and every deny. */
    public static boolean allowed(Decision d) {
        return d == Decision.ALLOW_OWNER || d == Decision.ALLOW_OP || d == Decision.ALLOW_HOST;
    }

    /** Longest client string {@link #logSafe} keeps before cutting. */
    public static final int LOG_SAFE_MAX_CHARS = 32;

    /**
     * A client-supplied string made safe for one log line: {@code null} → {@code "null"}; every
     * control character ({@code < 0x20} or {@code 0x7F}) → {@code '?'}; cut to
     * {@value #LOG_SAFE_MAX_CHARS} chars with a trailing {@code "…"} when longer.
     */
    public static String logSafe(String raw) {
        if (raw == null) return "null";
        int n = Math.min(raw.length(), LOG_SAFE_MAX_CHARS);
        StringBuilder sb = new StringBuilder(n + 1);
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        if (raw.length() > LOG_SAFE_MAX_CHARS) sb.append('…');
        return sb.toString();
    }

    /** Minimum gap between two identical deny WARNs (same sender, action and target). */
    public static final long DENY_LOG_INTERVAL_MS = 5_000L;

    /**
     * Whether a deny WARN may be written now, given when the same (sender, action, target) was
     * last logged ({@code null} = never). A clock that went backwards logs rather than stalls.
     */
    public static boolean shouldLogDeny(Long lastLoggedMs, long nowMs) {
        if (lastLoggedMs == null) return true;
        long gap = nowMs - lastLoggedMs;
        return gap < 0 || gap >= DENY_LOG_INTERVAL_MS;
    }
}
