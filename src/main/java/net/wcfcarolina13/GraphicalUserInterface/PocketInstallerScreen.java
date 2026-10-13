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

    // Designed size when it fits the screen, else clamped so 320x240 stays usable.
    private int popupW() { return UiFitPolicy.popupExtent(POPUP_WIDTH, this.width, 16); }
    private int popupH() { return UiFitPolicy.popupExtent(POPUP_HEIGHT, this.height, 8); }

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
        int cx = (this.width - popupW()) / 2;
        int cy = UiFitPolicy.popupOrigin(this.height, popupH());
        int btnY = cy + popupH() - 28;
        int btnW = 130;

        installButton = ButtonWidget.builder(Text.literal("Install"), b -> startInstall())
                .dimensions(cx + PAD, btnY, btnW, 20).build();
        addDrawableChild(installButton);
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(cx + popupW() - PAD - 80, btnY, 80, 20).build());
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
        int cx = (this.width - popupW()) / 2;
        int cy = UiFitPolicy.popupOrigin(this.height, popupH());
        context.fill(cx - 1, cy - 1, cx + popupW() + 1, cy + popupH() + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + popupW(), cy + popupH(), 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 8, 0xFFFFFFFF);

        int y = cy + 24;
        PocketInstaller.Plan p = plan;
        if (phase == Phase.DETECTING) {
            context.drawTextWithShadow(this.textRenderer, "Checking your system…", cx + PAD, y, COL_DIM);
        } else if (phase == Phase.FAILED) {
            drawLines(context, fit("§c" + detectError, textW()), cx + PAD, y, COL_BAD);
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
                context.drawTextWithShadow(this.textRenderer, fitTail(job.stage(), 66, textW()), cx + PAD, y, COL_TXT);
                y += 12;
                // pip reports no byte totals — an indeterminate bar says "still working".
                int barW = popupW() - PAD * 2;
                context.fill(cx + PAD, y, cx + PAD + barW, y + 6, 0xFF303030);
                int sweep = barW / 4;
                int offset = (int) ((System.currentTimeMillis() / 12) % (barW + sweep)) - sweep;
                int left = Math.max(cx + PAD, cx + PAD + offset);
                int right = Math.min(cx + PAD + barW, cx + PAD + offset + sweep);
                if (right > left) {
                    context.fill(left, y, right, y + 6, 0xFF4FA8FF);
                }
            } else if (lastResult != null) {
                for (String line : UiFitPolicy.keepOrWrap(wrap(lastResult, 66), lastResult, textW(), this.textRenderer::getWidth)) {
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
            String cmd = rt.args().isEmpty() ? rt.executable().toString()
                    : rt.executable() + " " + String.join(" ", rt.args());
            drawIcon(context, cx, y, true);
            context.drawTextWithShadow(this.textRenderer,
                    fitTail("Runtime: " + rt.version() + " (" + cmd + ")", 64, textW() - 12), cx + PAD + 12, y, COL_TXT);
        } else {
            drawIcon(context, cx, y, false);
            context.drawTextWithShadow(this.textRenderer,
                    fitTail(PocketInstaller.missingRuntimeHint(), 66, textW() - 12), cx + PAD + 12, y, COL_TXT);
        }
        y += 11;
        y += 11 + drawRow(context, cx, y, diskOk(p), "Disk space: need ~2 GB free ("
                + (p.freeDiskBytes() < 0 ? "unknown" : mb(p.freeDiskBytes()) + " available") + ")");
        // Download plan
        String download = "Download: ~850 MB of Python packages (pypi.org) + 228 MB model"
                + " (huggingface.co) on first run";
        for (String line : UiFitPolicy.keepOrWrap(wrap(download, 66), download, textW(), this.textRenderer::getWidth)) {
            context.drawTextWithShadow(this.textRenderer, line, cx + PAD, y, COL_TXT);
            y += 10;
        }
        y += 1;
        y += 11 + drawLines(context,
                fit("Engine: pocket-tts 3.0.2  ·  CPU only  ·  21 English voices", textW()), cx + PAD, y, COL_DIM);
        context.drawTextWithShadow(this.textRenderer,
                fitTail("Install to: " + p.installDir(), 66, textW()), cx + PAD, y, COL_DIM);
        y += 13;
        // Voice cloning gate (presets never need it)
        String cloning = p.hfTokenPresent()
                ? "Voice cloning: Hugging Face token found"
                : "Voice cloning: needs a Hugging Face login (accept terms at"
                + " huggingface.co/kyutai/pocket-tts, then hf auth login)";
        for (String line : UiFitPolicy.keepOrWrap(wrap(cloning, 66), cloning, textW(), this.textRenderer::getWidth)) {
            context.drawTextWithShadow(this.textRenderer, line, cx + PAD, y, COL_DIM);
            y += 10;
        }
        return y + 1;
    }

    private void drawIcon(DrawContext context, int cx, int y, boolean ok) {
        context.drawTextWithShadow(this.textRenderer, ok ? "✔" : "✘", cx + PAD, y,
                ok ? COL_OK : COL_BAD);
    }

    /** Draws a check row; returns the extra height used when the text had to wrap. */
    private int drawRow(DrawContext context, int cx, int y, boolean ok, String text) {
        drawIcon(context, cx, y, ok);
        return drawLines(context, fit(text, textW() - 12), cx + PAD + 12, y, COL_TXT);
    }

    /** Text width inside the popup (it shrinks with the popup on narrow screens). */
    private int textW() {
        return popupW() - PAD * 2;
    }

    /** One line, kept exactly as it was when it fits, else wrapped by measured width. */
    private java.util.List<String> fit(String text, int maxWidth) {
        return UiFitPolicy.wrapIfNeeded(text, maxWidth, this.textRenderer::getWidth);
    }

    /** The old character-count elision when it fits, else elided by width (keeps the end). */
    private String fitTail(String text, int legacyChars, int maxWidth) {
        String legacy = elide(text, legacyChars);
        return this.textRenderer.getWidth(legacy) <= maxWidth
                ? legacy
                : UiFitPolicy.elideStart(text, maxWidth, this.textRenderer::getWidth);
    }

    /** Draws lines 10 px apart; returns the extra height beyond the first line. */
    private int drawLines(DrawContext context, java.util.List<String> lines, int x, int y, int color) {
        for (int i = 0; i < lines.size(); i++) {
            context.drawTextWithShadow(this.textRenderer, lines.get(i), x, y + i * 10, color);
        }
        return Math.max(0, lines.size() - 1) * 10;
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
