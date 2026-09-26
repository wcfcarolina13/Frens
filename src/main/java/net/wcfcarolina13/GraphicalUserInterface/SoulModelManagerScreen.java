package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.souls.OllamaModelInstaller;
import net.wcfcarolina13.network.AiSetupNetworkManager;
import net.wcfcarolina13.network.AiSetupStatus;
import java.util.function.Consumer;

import java.util.ArrayList;
import java.util.List;

/**
 * Soul LLM model manager, opened from the "LLM…" chip on the Soul Chat row — the Ollama
 * counterpart of {@link PiperInstallerScreen}, with the same transparency rules: system
 * check (Ollama reachable, RAM, disk), registry-verified download sizes, per-model RAM
 * guidance, what is already installed, and streaming download progress. The mod cannot
 * install Ollama itself; when the daemon is missing the screen says exactly that.
 */
public class SoulModelManagerScreen extends Screen {

    private static final int POPUP_WIDTH = 430;
    private static final int POPUP_HEIGHT = 240;
    private static final int PAD = 8;
    private static final int COL_OK = 0xFF7FD97F;
    private static final int COL_BAD = 0xFFE07070;
    private static final int COL_DIM = 0xFFB0B0B0;
    private static final int COL_TXT = 0xFFEFEFEF;

    private final Screen parent;
    private final Consumer<AiSetupStatus> listener = fresh -> {
        remoteStatus = fresh;
        updateMode();
        refreshButtons();
    };
    private AiSetupStatus remoteStatus;
    private SoulSetupScreenPolicy.Mode mode;
    private boolean statusRequested;
    private boolean requestAccepted;
    private long statusRequestedAtMs;

    private volatile OllamaModelInstaller.Status status;

    private enum Phase { DETECTING, READY }

    private volatile Phase phase = Phase.DETECTING;
    /** Consumed outcome of the last background pull (green success / red failure line). */
    private String lastResult;

    private final List<ButtonWidget> modelButtons = new ArrayList<>();

    public SoulModelManagerScreen(Screen parent) {
        super(Text.literal("§bSoul LLM Models"));
        this.parent = parent;
        AiSetupNetworkManager.Client.registerOnce();
        remoteStatus = AiSetupNetworkManager.Client.latestStatus();
    }

    @Override
    protected void init() {
        updateMode();
        if (mode != SoulSetupScreenPolicy.Mode.LOCAL) {
            AiSetupNetworkManager.Client.setListener(listener);
            if (!statusRequested) {
                requestRemoteStatus(false);
            }
        }
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        modelButtons.clear();

        List<OllamaModelInstaller.KnownModel> models = OllamaModelInstaller.KNOWN_MODELS;
        int rowY = cy + 78;
        for (OllamaModelInstaller.KnownModel model : models) {
            ButtonWidget btn = ButtonWidget.builder(buttonLabel(model), b -> onModelButton(model))
                    .dimensions(cx + POPUP_WIDTH - PAD - 96, rowY - 2, 96, 18)
                    .build();
            modelButtons.add(btn);
            addDrawableChild(btn);
            rowY += 34;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> startDetect())
                .dimensions(cx + PAD, cy + POPUP_HEIGHT - 28, 80, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx + POPUP_WIDTH - PAD - 80, cy + POPUP_HEIGHT - 28, 80, 20).build());

        if (mode == SoulSetupScreenPolicy.Mode.LOCAL && status == null && phase == Phase.DETECTING) {
            startDetect();
        }
        refreshButtons();
    }

    private void updateMode() {
        mode = SoulSetupScreenPolicy.mode(this.client != null && this.client.isInSingleplayer(),
                AiSetupNetworkManager.Client.isAvailable() && requestAccepted,
                remoteStatus != null, remoteStatus != null && remoteStatus.viewer().canEditServer(),
                net.minecraft.util.Util.getMeasuringTimeMs() - statusRequestedAtMs);
    }

    private void requestRemoteStatus(boolean refresh) {
        remoteStatus = refresh ? null : AiSetupNetworkManager.Client.latestStatus();
        statusRequested = true;
        statusRequestedAtMs = net.minecraft.util.Util.getMeasuringTimeMs();
        requestAccepted = AiSetupNetworkManager.Client.requestStatus();
        updateMode();
    }

    private void startDetect() {
        if (mode != SoulSetupScreenPolicy.Mode.LOCAL) {
            requestRemoteStatus(true);
            return;
        }
        phase = Phase.DETECTING;
        Thread t = new Thread(() -> {
            status = OllamaModelInstaller.detect();
            if (phase == Phase.DETECTING) {
                phase = Phase.READY;
            }
        }, "frens-ollama-detect");
        t.setDaemon(true);
        t.start();
    }

    private Text buttonLabel(OllamaModelInstaller.KnownModel model) {
        if (mode != SoulSetupScreenPolicy.Mode.LOCAL) {
            AiSetupStatus.Ollama ollama = remoteStatus == null ? null : remoteStatus.ollama();
            if (ollama == null) return Text.literal("…");
            boolean selected = remoteStatus.runtime().model().equals(model.tag());
            return labelFor(SoulSetupScreenPolicy.modelButton(mode, ollama.isInstalled(model.tag()), selected, false),
                    model, (long) (ollama.hostRamGb() * 1073741824.0));
        }
        OllamaModelInstaller.Status s = status;
        if (s == null) {
            return Text.literal("…");
        }
        return labelFor(SoulSetupScreenPolicy.modelButton(mode, s.isInstalled(model.tag()),
                model.tag().equals(s.currentModel()), OllamaModelInstaller.activeJob() != null), model, s.totalRamBytes());
    }

    private Text labelFor(SoulSetupScreenPolicy.ModelButton decision,
                          OllamaModelInstaller.KnownModel model, long ramBytes) {
        return switch (decision.label()) {
            case SELECTED -> Text.literal("§aSelected ✔");
            case USE -> Text.literal("Use");
            case DOWNLOAD -> Text.literal(OllamaModelInstaller.ramShortfallGb(model, ramBytes) > 0
                    ? "Download ⚠" : "Download");
        };
    }

    private void onModelButton(OllamaModelInstaller.KnownModel model) {
        if (mode != SoulSetupScreenPolicy.Mode.LOCAL) {
            AiSetupStatus.Ollama ollama = remoteStatus == null ? null : remoteStatus.ollama();
            if (mode == SoulSetupScreenPolicy.Mode.REMOTE_EDITOR && ollama != null
                    && ollama.isInstalled(model.tag()) && !model.tag().equals(remoteStatus.runtime().model())) {
                AiSetupNetworkManager.Client.selectModel(model.tag());
            }
            return;
        }
        OllamaModelInstaller.Status s = status;
        if (s == null || OllamaModelInstaller.activeJob() != null) {
            return;
        }
        if (s.isInstalled(model.tag())) {
            OllamaModelInstaller.select(model.tag());
            lastResult = "§aSelected " + model.tag() + ".";
            startDetect();
            return;
        }
        // The pull runs in the SERVICE, not this screen: closing and reopening the menu
        // re-attaches to it, and pullAsync's compare-and-set prevents duplicate pulls.
        OllamaModelInstaller.clearFinishedJob();
        lastResult = null;
        OllamaModelInstaller.pullAsync(model.tag());
        refreshButtons();
    }

    private void refreshButtons() {
        if (mode != SoulSetupScreenPolicy.Mode.LOCAL) {
            AiSetupStatus.Ollama ollama = remoteStatus == null ? null : remoteStatus.ollama();
            for (int i = 0; i < modelButtons.size(); i++) {
                OllamaModelInstaller.KnownModel model = OllamaModelInstaller.KNOWN_MODELS.get(i);
                modelButtons.get(i).setMessage(buttonLabel(model));
                modelButtons.get(i).active = ollama != null && ollama.reachable()
                        && SoulSetupScreenPolicy.modelButton(mode, ollama.isInstalled(model.tag()),
                        model.tag().equals(remoteStatus.runtime().model()), false).enabled();
            }
            return;
        }
        OllamaModelInstaller.Status s = status;
        boolean interactable = s != null && s.reachable()
                && OllamaModelInstaller.activeJob() == null;
        List<OllamaModelInstaller.KnownModel> models = OllamaModelInstaller.KNOWN_MODELS;
        for (int i = 0; i < modelButtons.size() && i < models.size(); i++) {
            OllamaModelInstaller.KnownModel m = models.get(i);
            modelButtons.get(i).setMessage(buttonLabel(m));
            modelButtons.get(i).active = interactable && s != null
                    && SoulSetupScreenPolicy.modelButton(mode, s.isInstalled(m.tag()),
                    m.tag().equals(s.currentModel()), OllamaModelInstaller.activeJob() != null).enabled();
        }
    }

    private static String gb(long bytes) {
        return String.format("%.1f GB", bytes / 1073741824.0);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateMode();
        refreshButtons();
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 8, 0xFFFFFFFF);

        if (mode != SoulSetupScreenPolicy.Mode.LOCAL) {
            renderRemote(context, cx, cy);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        OllamaModelInstaller.Status s = status;
        int y = cy + 24;
        if (s == null) {
            context.drawTextWithShadow(this.textRenderer, "Checking Ollama…", cx + PAD, y, COL_DIM);
        } else {
            if (s.reachable()) {
                context.drawTextWithShadow(this.textRenderer, "✔", cx + PAD, y, COL_OK);
                context.drawTextWithShadow(this.textRenderer,
                        "Ollama " + s.version() + " running at " + s.baseUrl(), cx + PAD + 12, y, COL_TXT);
            } else {
                context.drawTextWithShadow(this.textRenderer, "✘", cx + PAD, y, COL_BAD);
                context.drawTextWithShadow(this.textRenderer,
                        "Ollama not reachable at " + s.baseUrl() + " — install/start it (ollama.com)",
                        cx + PAD + 12, y, COL_BAD);
            }
            y += 11;
            String ram = s.totalRamBytes() > 0 ? gb(s.totalRamBytes()) : "unknown";
            String disk = s.freeDiskBytes() > 0 ? gb(s.freeDiskBytes()) + " free" : "unknown";
            context.drawTextWithShadow(this.textRenderer,
                    "System: " + ram + " RAM  ·  disk " + disk
                            + "  ·  models download from registry.ollama.ai",
                    cx + PAD, y, COL_DIM);
            y += 14;
            context.drawTextWithShadow(this.textRenderer,
                    "New requests use your selection; the first reply may take longer.",
                    cx + PAD, y, COL_DIM);

            int rowY = cy + 72;
            for (OllamaModelInstaller.KnownModel m : OllamaModelInstaller.KNOWN_MODELS) {
                boolean installed = s.isInstalled(m.tag());
                boolean ramOk = OllamaModelInstaller.ramShortfallGb(m, s.totalRamBytes()) <= 0;
                context.drawTextWithShadow(this.textRenderer,
                        m.label() + " §7(" + m.tag() + ")"
                                + (installed ? " §a· installed" : String.format(" §7· %.1f GB download", m.downloadGb())),
                        cx + PAD, rowY, COL_TXT);
                context.drawTextWithShadow(this.textRenderer,
                        "§7" + m.description() + (ramOk ? "" : " §c(recommends ≥" + (int) m.recommendedRamGb() + " GB RAM)"),
                        cx + PAD, rowY + 10, COL_DIM);
                rowY += 34;
            }

            // Pull state lives in the service — a screen reopened mid-download re-attaches
            // here; a finished job's outcome is consumed once into lastResult + a refresh.
            net.wcfcarolina13.GameAI.souls.InstallJob job = OllamaModelInstaller.activeJob();
            if (job != null && job.finished()) {
                lastResult = job.error() == null
                        ? "§aDownloaded and selected " + job.description() + "."
                        : "§c" + job.error();
                OllamaModelInstaller.clearFinishedJob();
                startDetect();
                job = null;
            }
            if (job != null) {
                int py = rowY + 2;
                String line = job.bytesTotal() > 0
                        ? job.description() + ": " + job.stage() + "  " + gb(job.bytesDone()) + " / " + gb(job.bytesTotal())
                        : job.description() + ": " + job.stage();
                context.drawTextWithShadow(this.textRenderer, line, cx + PAD, py, COL_TXT);
                int barW = POPUP_WIDTH - PAD * 2;
                context.fill(cx + PAD, py + 11, cx + PAD + barW, py + 17, 0xFF303030);
                if (job.bytesTotal() > 0) {
                    int fill = (int) (barW * Math.min(1.0, job.bytesDone() / (double) job.bytesTotal()));
                    context.fill(cx + PAD, py + 11, cx + PAD + fill, py + 17, 0xFF4FA8FF);
                }
            } else if (lastResult != null) {
                context.drawTextWithShadow(this.textRenderer, lastResult, cx + PAD, rowY + 2,
                        lastResult.startsWith("§a") ? COL_OK : COL_BAD);
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderRemote(DrawContext context, int cx, int cy) {
        context.drawTextWithShadow(this.textRenderer, "Runs on the server host", cx + PAD, cy + 24, COL_DIM);
        AiSetupStatus.Ollama ollama = remoteStatus == null ? null : remoteStatus.ollama();
        String message = SoulSetupScreenPolicy.statusMessage(mode);
        if (!message.isEmpty()) {
            int y = cy + 38;
            for (var line : this.textRenderer.wrapLines(Text.literal(message), POPUP_WIDTH - PAD * 2)) {
                context.drawTextWithShadow(this.textRenderer, line, cx + PAD, y, COL_DIM);
                y += 11;
            }
        } else if (ollama != null) {
            context.drawTextWithShadow(this.textRenderer,
                    ollama.reachable() ? "Ollama " + ollama.version() + " is reachable"
                            : "Ollama is not reachable on the server host", cx + PAD, cy + 38,
                    ollama.reachable() ? COL_OK : COL_BAD);
            context.drawTextWithShadow(this.textRenderer,
                    "Server RAM: " + String.format("%.1f GB", ollama.hostRamGb()), cx + PAD, cy + 49, COL_DIM);
        }
        int rowY = cy + 72;
        for (OllamaModelInstaller.KnownModel model : OllamaModelInstaller.KNOWN_MODELS) {
            boolean installed = ollama != null && ollama.isInstalled(model.tag());
            context.drawTextWithShadow(this.textRenderer, model.label() + " §7(" + model.tag() + ")"
                    + (ollama == null ? "" : installed ? " §a· installed" : " §7· missing on server"),
                    cx + PAD, rowY, COL_TXT);
            context.drawTextWithShadow(this.textRenderer,
                    ollama == null ? "Server model list unavailable."
                            : installed ? model.description()
                            : "Pull it on the server machine: ollama pull " + model.tag(),
                    cx + PAD, rowY + 10, COL_DIM);
            rowY += 34;
        }
    }

    @Override
    public void removed() {
        AiSetupNetworkManager.Client.clearListener(listener);
        super.removed();
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input != null ? input.key() : -1;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
