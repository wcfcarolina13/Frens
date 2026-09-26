package net.wcfcarolina13.GraphicalUserInterface;

/** Pure decisions shared by the local and server-hosted setup screens. */
public final class SoulSetupScreenPolicy {
    private SoulSetupScreenPolicy() {}

    public static final long STATUS_TIMEOUT_MS = 5_000;

    public enum Mode { LOCAL, REMOTE_EDITOR, REMOTE_VIEWER, REMOTE_UNSUPPORTED, REMOTE_WAITING, REMOTE_TIMEOUT }
    public enum ModelLabel { DOWNLOAD, SELECTED, USE }
    public record ModelButton(ModelLabel label, boolean enabled) {}

    public static Mode mode(boolean integratedServerRunning, boolean surfaceAvailable,
                            boolean statusAvailable, boolean canEditServer, long elapsedMs) {
        if (integratedServerRunning) return Mode.LOCAL;
        if (!surfaceAvailable) return Mode.REMOTE_UNSUPPORTED;
        if (!statusAvailable) return elapsedMs >= STATUS_TIMEOUT_MS ? Mode.REMOTE_TIMEOUT : Mode.REMOTE_WAITING;
        return canEditServer ? Mode.REMOTE_EDITOR : Mode.REMOTE_VIEWER;
    }

    public static String statusMessage(Mode mode) {
        return switch (mode) {
            case REMOTE_UNSUPPORTED -> "This server's Frens version can't be set up from here — ask the host to update Frens.";
            case REMOTE_WAITING -> "Asking the server…";
            case REMOTE_TIMEOUT -> "The server didn't answer — try Refresh.";
            case REMOTE_VIEWER -> "Only the server operator or host can change this.";
            case LOCAL, REMOTE_EDITOR -> "";
        };
    }

    public static ModelButton modelButton(Mode mode, boolean installed, boolean selected, boolean downloading) {
        ModelLabel label = !installed ? ModelLabel.DOWNLOAD : selected ? ModelLabel.SELECTED : ModelLabel.USE;
        boolean enabled = !downloading && !(installed && selected)
                && (mode == Mode.LOCAL || mode == Mode.REMOTE_EDITOR && label == ModelLabel.USE);
        return new ModelButton(label, enabled);
    }
}
