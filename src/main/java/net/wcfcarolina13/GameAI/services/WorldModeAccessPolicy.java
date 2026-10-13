package net.wcfcarolina13.GameAI.services;

/**
 * Who may open the World Mode chooser: a player with {@code /bot} permission (operator, or the
 * integrated host through {@link HostPermissionPolicy}), the integrated host, or a player the
 * world delegated the choice to.
 */
public final class WorldModeAccessPolicy {

    private WorldModeAccessPolicy() {
    }

    public static boolean canChoose(boolean botCommandPermission, boolean integratedHost, boolean delegated) {
        return botCommandPermission || integratedHost || delegated;
    }
}
