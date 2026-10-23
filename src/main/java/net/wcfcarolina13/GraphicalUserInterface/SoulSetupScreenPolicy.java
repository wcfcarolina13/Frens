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
            case REMOTE_UNSUPPORTED -> "This server's Frens version can't be set up from here. Ask the host to update Frens.";
            case REMOTE_WAITING -> "Asking the server…";
            case REMOTE_TIMEOUT -> "The server didn't answer. Try Refresh.";
            case REMOTE_VIEWER -> "Only the server operator or host can change this.";
            case LOCAL, REMOTE_EDITOR -> "";
        };
    }

    /** Most lines an engine row's blurb may take: y+14, +24, +34 ends at y+43, inside the 50 px row. */
    public static final int ENGINE_BLURB_MAX_LINES = 3;
    /** Clear space kept between a row's text and its button. */
    public static final int ENGINE_TEXT_BUTTON_GAP = 6;

    /**
     * Width an engine row's title and blurb may use so they stop before the row's button
     * (which sits at the popup's right edge, inside {@code pad}). Never below 1.
     */
    public static int engineTextWidth(int popupWidth, int pad, int buttonWidth) {
        return Math.max(1, popupWidth - pad * 2 - buttonWidth - ENGINE_TEXT_BUTTON_GAP);
    }

    /**
     * The Model screen's header under the Ollama line: system figures, where models come from,
     * and the switch note, each short enough for the 326 px popup (scale 5 in a 342 px window).
     */
    public static java.util.List<String> modelHeaderLines(String ram, String disk) {
        return java.util.List.of(
                "System: " + ram + " RAM  ·  disk " + disk,
                "Models download from registry.ollama.ai.",
                "First reply after a switch may be slow.");
    }

    public static ModelButton modelButton(Mode mode, boolean installed, boolean selected, boolean downloading) {
        ModelLabel label = !installed ? ModelLabel.DOWNLOAD : selected ? ModelLabel.SELECTED : ModelLabel.USE;
        boolean enabled = !downloading && !(installed && selected)
                && (mode == Mode.LOCAL || mode == Mode.REMOTE_EDITOR && label == ModelLabel.USE);
        return new ModelButton(label, enabled);
    }
}
