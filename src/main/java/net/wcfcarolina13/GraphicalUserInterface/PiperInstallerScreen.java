package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.souls.voice.PiperInstaller;

import java.nio.file.Path;

/**
 * Transparent installer for the lightweight Piper TTS engine. Shows the user, before
 * anything happens: a system check (platform, disk space), exactly what will be
 * downloaded (sizes and hosts), where it will be installed, and any pre-installed Piper
 * found on the machine (usable only if it passes the same synthesis smoke test a fresh
 * download gets). All work runs on a daemon worker thread; this screen only renders
 * volatile progress state.
 */
public class PiperInstallerScreen extends Screen {

    private static final int POPUP_WIDTH = 420;
    private static final int POPUP_HEIGHT = 250;
    private static final int PAD = 8;

    private static final int COL_OK = 0xFF7FD97F;
    private static final int COL_BAD = 0xFFE07070;
    private static final int COL_DIM = 0xFFB0B0B0;
    private static final int COL_TXT = 0xFFEFEFEF;

    private final Screen parent;

    private volatile PiperInstaller.Plan plan;
    private volatile String detectError;

    private enum Phase { DETECTING, READY, FAILED }

    private volatile Phase phase = Phase.DETECTING;
    /** Consumed outcome of the last background job (green success / red failure line). */
    private String lastResult;

    private ButtonWidget installButton;
    private ButtonWidget useExistingButton;

    public PiperInstallerScreen(Screen parent) {
        super(Text.literal("§bPiper Installer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        int btnY = cy + POPUP_HEIGHT - 28;
        int btnW = (POPUP_WIDTH - PAD * 4) / 3;

        installButton = ButtonWidget.builder(Text.literal("Download & Install"),
                        b -> startInstall(null))
                .dimensions(cx + PAD, btnY, btnW + 30, 20).build();
        useExistingButton = ButtonWidget.builder(Text.literal("Use Existing"),
                        b -> {
                            PiperInstaller.Plan p = plan;
                            if (p != null && !p.existingBinaries().isEmpty()) {
                                startInstall(p.existingBinaries().get(0).path());
                            }
                        })
                .dimensions(cx + PAD * 2 + btnW + 30, btnY, btnW - 15, 20).build();
        addDrawableChild(installButton);
        addDrawableChild(useExistingButton);
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx + POPUP_WIDTH - PAD - (btnW - 15), btnY, btnW - 15, 20).build());
        refreshButtons();

        if (plan == null && phase == Phase.DETECTING) {
            Thread t = new Thread(() -> {
                try {
                    plan = PiperInstaller.detect();
                    phase = Phase.READY;
                } catch (Throwable ex) {
                    detectError = "Detection failed: " + ex.getMessage();
                    phase = Phase.FAILED;
                }
            }, "frens-piper-detect");
            t.setDaemon(true);
            t.start();
        }
    }

    private void refreshButtons() {
        PiperInstaller.Plan p = plan;
        boolean jobRunning = PiperInstaller.activeJob() != null;
        boolean ready = phase == Phase.READY && p != null && !jobRunning;
        boolean diskOk = p != null && (p.freeDiskBytes() < 0 || p.freeDiskBytes() > 300L * 1024 * 1024);
        if (installButton != null) {
            installButton.active = ready && p.platformSupported() && diskOk;
        }
        if (useExistingButton != null) {
            useExistingButton.active = ready && !p.existingBinaries().isEmpty();
        }
    }

    private void startInstall(Path existingBinary) {
        PiperInstaller.Plan p = plan;
        if (p == null) {
            return;
        }
        // The job lives in the SERVICE, not this screen: closing and reopening the menu
        // re-attaches to it, and installAsync's compare-and-set makes double-starts
        // impossible.
        PiperInstaller.clearFinishedJob();
        lastResult = null;
        PiperInstaller.installAsync(p, existingBinary);
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
        PiperInstaller.Plan p = plan;
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
            net.wcfcarolina13.GameAI.souls.InstallJob job = PiperInstaller.activeJob();
            if (job != null && job.finished()) {
                lastResult = job.error() == null
                        ? "§aInstalled. Piper is now the soul voice engine."
                        : "§c" + job.error();
                PiperInstaller.clearFinishedJob();
                job = null;
            }
            y += 4;
            if (job != null) {
                String pct = job.bytesTotal() > 0
                        ? job.stage() + "  " + mb(job.bytesDone()) + " / " + mb(job.bytesTotal())
                        : job.stage();
                context.drawTextWithShadow(this.textRenderer, pct, cx + PAD, y, COL_TXT);
                y += 12;
                int barW = POPUP_WIDTH - PAD * 2;
                context.fill(cx + PAD, y, cx + PAD + barW, y + 6, 0xFF303030);
                if (job.bytesTotal() > 0) {
                    int fill = (int) (barW * Math.min(1.0, job.bytesDone() / (double) job.bytesTotal()));
                    context.fill(cx + PAD, y, cx + PAD + fill, y + 6, 0xFF4FA8FF);
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

    private int drawPlan(DrawContext context, PiperInstaller.Plan p, int cx, int y) {
        // System check
        drawRow(context, cx, y, p.platformSupported(),
                "System: " + p.platformName()
                        + (p.platformSupported() ? "" : " — no prebuilt Piper available"));
        y += 11;
        boolean diskOk = p.freeDiskBytes() < 0 || p.freeDiskBytes() > 300L * 1024 * 1024;
        drawRow(context, cx, y, diskOk, "Disk space: need ~300 MB free — "
                + (p.freeDiskBytes() < 0 ? "unknown" : mb(p.freeDiskBytes()) + " available"));
        y += 11;
        // Download plan
        long dl = p.downloadBytes();
        String voicePart = p.voiceAlreadyPresent() ? " (voice already present)" : " incl. voice";
        context.drawTextWithShadow(this.textRenderer,
                "Download: " + mb(dl) + voicePart + "  —  github.com + huggingface.co",
                cx + PAD, y, COL_TXT);
        y += 11;
        context.drawTextWithShadow(this.textRenderer,
                "Engine: piper " + PiperInstaller.RELEASE_TAG + "  ·  Voice: " + PiperInstaller.VOICE_NAME
                        + "  ·  checksums verified", cx + PAD, y, COL_DIM);
        y += 11;
        context.drawTextWithShadow(this.textRenderer,
                elide("Install to: " + p.installDir(), 66), cx + PAD, y, COL_DIM);
        y += 13;
        // Existing installs
        if (p.existingBinaries().isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    "Pre-installed Piper: none found", cx + PAD, y, COL_DIM);
            y += 11;
        } else {
            PiperInstaller.ExistingBinary first = p.existingBinaries().get(0);
            context.drawTextWithShadow(this.textRenderer,
                    "Pre-installed Piper found (" + first.origin() + "):", cx + PAD, y, COL_TXT);
            y += 11;
            context.drawTextWithShadow(this.textRenderer,
                    elide("  " + first.path(), 66), cx + PAD, y, COL_DIM);
            y += 11;
            context.drawTextWithShadow(this.textRenderer,
                    "  \"Use Existing\" runs a synthesis test first — a pip/pipx Piper may be",
                    cx + PAD, y, COL_DIM);
            y += 10;
            context.drawTextWithShadow(this.textRenderer,
                    "  incompatible and will be rejected safely (then use Download).",
                    cx + PAD, y, COL_DIM);
            y += 11;
        }
        return y;
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
