package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.souls.InstallJob;
import net.wcfcarolina13.GameAI.souls.voice.PocketInstaller;

/**
 * Transparent installer for the Kyutai Pocket TTS engine. Shows the user, before anything
 * happens: which Python runtime will build the environment (uv preferred), disk space,
 * exactly what will be downloaded (sizes and hosts), where it will be installed, and
 * whether voice cloning is unlocked (Hugging Face token). All work runs on a daemon
 * worker thread owned by {@link PocketInstaller}; this screen only renders volatile
 * progress state and re-attaches to a running job when reopened.
 */
public class PocketInstallerScreen extends Screen {

    private static final int POPUP_WIDTH = 420;
    private static final int POPUP_HEIGHT = 230;
    private static final int PAD = 8;

    private static final int COL_OK = 0xFF7FD97F;
    private static final int COL_BAD = 0xFFE07070;
    private static final int COL_DIM = 0xFFB0B0B0;
    private static final int COL_TXT = 0xFFEFEFEF;

    private final Screen parent;

    private volatile PocketInstaller.Plan plan;
    private volatile String detectError;

    private enum Phase { DETECTING, READY, FAILED }

    private volatile Phase phase = Phase.DETECTING;
    /** Consumed outcome of the last background job (green success / red failure line). */
    private String lastResult;

    private ButtonWidget installButton;

    public PocketInstallerScreen(Screen parent) {
        super(Text.literal("§bPocket TTS Installer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        int btnY = cy + POPUP_HEIGHT - 28;
        int btnW = 130;

        installButton = ButtonWidget.builder(Text.literal("Install"), b -> startInstall())
                .dimensions(cx + PAD, btnY, btnW, 20).build();
        addDrawableChild(installButton);
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx + POPUP_WIDTH - PAD - 80, btnY, 80, 20).build());
        refreshButtons();

        if (plan == null && phase == Phase.DETECTING) {
            Thread t = new Thread(() -> {
                try {
                    plan = PocketInstaller.detect();
                    phase = Phase.READY;
                } catch (Throwable ex) {
                    detectError = "Detection failed: " + ex.getMessage();
                    phase = Phase.FAILED;
                }
            }, "frens-pocket-detect");
            t.setDaemon(true);
            t.start();
        }
    }

    private static boolean diskOk(PocketInstaller.Plan p) {
        return p.freeDiskBytes() < 0 || p.freeDiskBytes() > PocketInstaller.NEED_FREE_BYTES;
    }

    private void refreshButtons() {
        PocketInstaller.Plan p = plan;
        boolean jobRunning = PocketInstaller.activeJob() != null;
        if (installButton != null) {
            installButton.active = phase == Phase.READY && p != null && p.runtime() != null
                    && diskOk(p) && !jobRunning;
            installButton.setMessage(Text.literal(p != null && p.alreadyInstalled() ? "Use Installed" : "Install"));
        }
    }

    private void startInstall() {
        PocketInstaller.Plan p = plan;
        if (p == null) {
            return;
        }
        // The job lives in the SERVICE, not this screen: closing and reopening the menu
        // re-attaches to it, and installAsync's compare-and-set makes double-starts
        // impossible. An already-installed venv skips straight to the smoke test + config.
        PocketInstaller.clearFinishedJob();
        lastResult = null;
        PocketInstaller.installAsync(p);
        refreshButtons();
    }

    private static String mb(long bytes) {
        return String.format("%.0f MB", bytes / 1048576.0);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refreshButtons();
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 8, 0xFFFFFFFF);

        int y = cy + 24;
        PocketInstaller.Plan p = plan;
        if (phase == Phase.DETECTING) {
            context.drawTextWithShadow(this.textRenderer, "Checking your system…", cx + PAD, y, COL_DIM);
        } else if (phase == Phase.FAILED) {
            context.drawTextWithShadow(this.textRenderer, "§c" + detectError, cx + PAD, y, COL_BAD);
        } else {
            if (p != null) {
                y = drawPlan(context, p, cx, y);
            }
            // Job state lives in the service — a screen reopened mid-install re-attaches
            // here; a finished job's outcome is consumed once into lastResult.
            InstallJob job = PocketInstaller.activeJob();
            if (job != null && job.finished()) {
                lastResult = job.error() == null
                        ? "§aInstalled. Pocket TTS is now the soul voice engine."
                        : "§c" + job.error();
                PocketInstaller.clearFinishedJob();
                job = null;
            }
            y += 4;
            if (job != null) {
                context.drawTextWithShadow(this.textRenderer, elide(job.stage(), 66), cx + PAD, y, COL_TXT);
                y += 12;
                // pip reports no byte totals — an indeterminate bar says "still working".
                int barW = POPUP_WIDTH - PAD * 2;
                context.fill(cx + PAD, y, cx + PAD + barW, y + 6, 0xFF303030);
                int sweep = barW / 4;
                int offset = (int) ((System.currentTimeMillis() / 12) % (barW + sweep)) - sweep;
                int left = Math.max(cx + PAD, cx + PAD + offset);
                int right = Math.min(cx + PAD + barW, cx + PAD + offset + sweep);
                if (right > left) {
                    context.fill(left, y, right, y + 6, 0xFF4FA8FF);
                }
            } else if (lastResult != null) {
                for (String line : wrap(lastResult, 66)) {
                    context.drawTextWithShadow(this.textRenderer, line,
                            cx + PAD, y, lastResult.startsWith("§a") ? COL_OK : COL_BAD);
                    y += 10;
                }
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private int drawPlan(DrawContext context, PocketInstaller.Plan p, int cx, int y) {
        // Runtime check
        PocketInstaller.Runtime rt = p.runtime();
        if (rt != null) {
            drawRow(context, cx, y, true,
                    elide("Runtime: " + rt.version() + " (" + rt.executable() + ")", 64));
        } else {
            drawRow(context, cx, y, false, elide(PocketInstaller.missingRuntimeHint(), 66));
        }
        y += 11;
        drawRow(context, cx, y, diskOk(p), "Disk space: need ~2 GB free — "
                + (p.freeDiskBytes() < 0 ? "unknown" : mb(p.freeDiskBytes()) + " available"));
        y += 11;
        // Download plan
        for (String line : wrap("Download: ~850 MB of Python packages (pypi.org) + 228 MB model"
                + " (huggingface.co) on first run", 66)) {
            context.drawTextWithShadow(this.textRenderer, line, cx + PAD, y, COL_TXT);
            y += 10;
        }
        y += 1;
        context.drawTextWithShadow(this.textRenderer,
                "Engine: pocket-tts 3.0.2  ·  CPU only  ·  21 English voices", cx + PAD, y, COL_DIM);
        y += 11;
        context.drawTextWithShadow(this.textRenderer,
                elide("Install to: " + p.installDir(), 66), cx + PAD, y, COL_DIM);
        y += 13;
        // Voice cloning gate (presets never need it)
        String cloning = p.hfTokenPresent()
                ? "Voice cloning: Hugging Face token found"
                : "Voice cloning: needs a Hugging Face login (accept terms at"
                + " huggingface.co/kyutai/pocket-tts, then hf auth login)";
        for (String line : wrap(cloning, 66)) {
            context.drawTextWithShadow(this.textRenderer, line, cx + PAD, y, COL_DIM);
            y += 10;
        }
        return y + 1;
    }

    private void drawRow(DrawContext context, int cx, int y, boolean ok, String text) {
        context.drawTextWithShadow(this.textRenderer, ok ? "✔" : "✘", cx + PAD, y,
                ok ? COL_OK : COL_BAD);
        context.drawTextWithShadow(this.textRenderer, text, cx + PAD + 12, y, COL_TXT);
    }

    private String elide(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max + 1);
    }

    private static java.util.List<String> wrap(String s, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String rest = s == null ? "" : s;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut <= 0) {
                cut = max;
            }
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
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
