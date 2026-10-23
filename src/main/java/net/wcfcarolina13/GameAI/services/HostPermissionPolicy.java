package net.wcfcarolina13.GameAI.services;

import java.util.UUID;

/**
 * Who counts as a server admin for Frens: an operator, or the host of an integrated server
 * (the player who owns the singleplayer or LAN world). The host is not in ops.json when the
 * world has commands off, yet vanilla lets them turn commands on at any time through Open to
 * LAN, so gating Frens on op alone protects nothing for them. LAN guests are not the host.
 * A dedicated server has no host.
 */
public final class HostPermissionPolicy {

    private HostPermissionPolicy() {
    }

    /**
     * The {@code /bot} root gate. Console and command blocks (no player) always pass, then an
     * operator, then the integrated-server host. Everyone else is refused.
     */
    public static boolean allowsBotCommands(boolean isConsoleOrNoPlayer, boolean isOperator, boolean isIntegratedHost) {
        if (isConsoleOrNoPlayer) {
            return true;
        }
        return isServerAdmin(isOperator, isIntegratedHost);
    }

    /** Host identity comes from the integrated server profile, never the display name. */
    public static boolean isIntegratedHost(boolean dedicated, UUID requester, UUID host) {
        return !dedicated && requester != null && requester.equals(host);
    }

    /** Admin-tab and world-level gates for a real player: operator or integrated host. */
    public static boolean isServerAdmin(boolean isOperator, boolean isIntegratedHost) {
        return isOperator || isIntegratedHost;
    }
}
