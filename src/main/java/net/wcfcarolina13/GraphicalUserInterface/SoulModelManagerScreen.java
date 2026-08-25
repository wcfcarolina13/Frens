package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.souls.OllamaModelInstaller;

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

    private volatile OllamaModelInstaller.Status status;

    private enum Phase { DETECTING, READY }

    private volatile Phase phase = Phase.DETECTING;
    /** Consumed outcome of the last background pull (green success / red failure line). */
    private String lastResult;

    private final List<ButtonWidget> modelButtons = new ArrayList<>();

    public SoulModelManagerScreen(Screen parent) {
        super(Text.literal("§bSoul LLM Models"));
        this.parent = parent;
    }

    @Override
    protected void init() {
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

        if (status == null && phase == Phase.DETECTING) {
            startDetect();
        }
        refreshButtons();
    }

    private void startDetect() {
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
        OllamaModelInstaller.Status s = status;
        if (s == null) {
            return Text.literal("…");
        }
        if (model.tag().equals(s.currentModel())) {
            return Text.literal("§aIn use ✔");
        }
        if (s.isInstalled(model.tag())) {
            return Text.literal("Use");
        }
        return Text.literal("Download");
    }

    private void onModelButton(OllamaModelInstaller.KnownModel model) {
        OllamaModelInstaller.Status s = status;
        if (s == null || OllamaModelInstaller.activeJob() != null) {
            return;
        }
        if (s.isInstalled(model.tag())) {
            OllamaModelInstaller.select(model.tag());
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
        OllamaModelInstaller.Status s = status;
        boolean interactable = s != null && s.reachable()
                && OllamaModelInstaller.activeJob() == null;
        List<OllamaModelInstaller.KnownModel> models = OllamaModelInstaller.KNOWN_MODELS;
        for (int i = 0; i < modelButtons.size() && i < models.size(); i++) {
            OllamaModelInstaller.KnownModel m = models.get(i);
            modelButtons.get(i).setMessage(buttonLabel(m));
            modelButtons.get(i).active = interactable && s != null && !m.tag().equals(s.currentModel());
        }
    }

    private static String gb(long bytes) {
        return String.format("%.1f GB", bytes / 1073741824.0);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refreshButtons();
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 8, 0xFFFFFFFF);

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

            int rowY = cy + 72;
            for (OllamaModelInstaller.KnownModel m : OllamaModelInstaller.KNOWN_MODELS) {
                boolean installed = s.isInstalled(m.tag());
                boolean ramOk = s.totalRamBytes() <= 0
                        || s.totalRamBytes() >= (long) (m.recommendedRamGb() * 1073741824L);
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
