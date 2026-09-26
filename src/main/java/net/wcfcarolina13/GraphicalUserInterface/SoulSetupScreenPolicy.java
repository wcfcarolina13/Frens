package net.wcfcarolina13.GraphicalUserInterface;

/** Pure decisions shared by the local and server-hosted setup screens. */
public final class SoulSetupScreenPolicy {
    private SoulSetupScreenPolicy() {}

    public enum Mode { LOCAL, REMOTE_EDITOR, REMOTE_VIEWER, REMOTE_UNKNOWN }
    public enum ModelLabel { DOWNLOAD, SELECTED, USE }
    public record ModelButton(ModelLabel label, boolean enabled) {}

    public static Mode mode(boolean integratedServerRunning, boolean statusAvailable, boolean canEditServer) {
        if (integratedServerRunning) return Mode.LOCAL;
        if (!statusAvailable) return Mode.REMOTE_UNKNOWN;
        return canEditServer ? Mode.REMOTE_EDITOR : Mode.REMOTE_VIEWER;
    }

    public static ModelButton modelButton(Mode mode, boolean installed, boolean selected, boolean downloading) {
        ModelLabel label = !installed ? ModelLabel.DOWNLOAD : selected ? ModelLabel.SELECTED : ModelLabel.USE;
        boolean enabled = !downloading && !(installed && selected)
                && (mode == Mode.LOCAL || mode == Mode.REMOTE_EDITOR && label == ModelLabel.USE);
        return new ModelButton(label, enabled);
    }
}
