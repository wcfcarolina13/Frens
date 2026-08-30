package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.souls.SoulRuntime;
import net.wcfcarolina13.GameAI.souls.voice.PocketInstaller;
import net.wcfcarolina13.GameAI.souls.voice.PocketVoiceEngine;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Soul-voice engine chooser, opened from the "Eng…" chip on the Soul Voice row.
 * One row per engine, user's choice:
 * <ul>
 *   <li><b>Dreamsleeve</b> — the bot's cloned voice via a local Qwen3-TTS warm server.
 *       Best quality, uses the GPU, needs the local Dreamsleeve setup.</li>
 *   <li><b>Pocket TTS</b> — Kyutai's 100M-param CPU model with 21 English preset voices,
 *       one-time ~1 GB install via the transparent {@link PocketInstallerScreen}.</li>
 *   <li><b>Piper</b> — lightweight generic voice, CPU-only, one-time ~80 MB download via
 *       the transparent {@link PiperInstallerScreen}.</li>
 * </ul>
 * Selecting an available engine writes the config and hot-reloads the soul runtime; an
 * unavailable engine's button opens its installer instead.
 */
public class SoulVoiceEngineScreen extends Screen {

    private static final int POPUP_WIDTH = 400;
    private static final int ROW_H = 50;
    private static final int PAD = 8;
    private static final int BTN_W = 130;

    /**
     * One engine's presentation. {@code installer} is null when the engine cannot be set up
     * from in-game (Dreamsleeve); {@code shortName} is what fits on the 130 px button.
     */
    private record EngineRow(String id, String shortName, String title, String blurb,
                             boolean available, Runnable installer) {
    }

    private final Screen parent;
    /** Built in {@link #init()}; {@link #render} draws titles/blurbs from the same list. */
    private List<EngineRow> rows = List.of();

    public SoulVoiceEngineScreen(Screen parent) {
        super(Text.literal("§bSoul Voice Engine"));
        this.parent = parent;
    }

    private static boolean dreamsleeveAvailable(ManualConfig cfg) {
        String dir = cfg.getSoulVoiceDreamsleeveDir();
        return dir != null && !dir.isBlank()
                && Files.isRegularFile(Path.of(dir, "scripts", "tts_server.py"));
    }

    private static boolean pocketAvailable(ManualConfig cfg) {
        String dir = cfg.getSoulVoicePocketDir();
        if (dir == null || dir.isBlank()) {
            dir = PocketInstaller.defaultInstallDir().toString();
        }
        return Files.isExecutable(PocketVoiceEngine.binaryPath(dir));
    }

    private static boolean piperAvailable(ManualConfig cfg) {
        String bin = cfg.getSoulVoicePiperBinary();
        String model = cfg.getSoulVoiceModel();
        return bin != null && !bin.isBlank() && Files.isExecutable(Path.of(bin))
                && model != null && !model.isBlank() && Files.isRegularFile(Path.of(model));
    }

    private int popupHeight() {
        return 40 + rows.size() * ROW_H + 36;
    }

    private List<EngineRow> buildRows(ManualConfig cfg) {
        boolean dsAvail = cfg != null && dreamsleeveAvailable(cfg);
        boolean pocketAvail = cfg != null && pocketAvailable(cfg);
        boolean piperAvail = cfg != null && piperAvailable(cfg);
        List<EngineRow> out = new ArrayList<>(3);
        out.add(new EngineRow(SoulVoiceSettings.ENGINE_DREAMSLEEVE, "Dreamsleeve",
                "Dreamsleeve — cloned bot voice",
                "§7Best quality. Uses the GPU. " + (dsAvail ? "Available." : "Not set up on this machine."),
                dsAvail, null));
        out.add(new EngineRow(SoulVoiceSettings.ENGINE_POCKET, "Pocket",
                "Pocket TTS — natural CPU voices",
                "§7Kyutai, 100M params, 21 presets. " + (pocketAvail ? "Installed." : "One-time ~1 GB install."),
                pocketAvail, () -> {
                    if (this.client != null) {
                        this.client.setScreen(new PocketInstallerScreen(this));
                    }
                }));
        out.add(new EngineRow(SoulVoiceSettings.ENGINE_PIPER, "Piper",
                "Piper — lightweight generic voice",
                "§7Fast, CPU-only, frees the GPU. " + (piperAvail ? "Installed." : "One-time ~80 MB download."),
                piperAvail, () -> {
                    if (this.client != null) {
                        this.client.setScreen(new PiperInstallerScreen(this));
                    }
                }));
        return out;
    }

    @Override
    protected void init() {
        ManualConfig cfg = Frens.CONFIG;
        rows = buildRows(cfg);
        String current = cfg != null ? cfg.getSoulVoiceEngine() : "";
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - popupHeight()) / 2;

        for (int i = 0; i < rows.size(); i++) {
            EngineRow row = rows.get(i);
            boolean isCurrent = row.id().equals(current);
            int y = cy + 40 + i * ROW_H;
            String label = isCurrent && row.available() ? "§a" + row.shortName() + " ✔"
                    : row.available() ? "Use " + row.shortName()
                    : "Install " + row.shortName() + "…";
            ButtonWidget button = ButtonWidget.builder(Text.literal(label), b -> {
                        if (row.available()) {
                            selectEngine(row.id());
                        } else if (row.installer() != null) {
                            row.installer().run();
                        }
                    })
                    .dimensions(cx + POPUP_WIDTH - PAD - BTN_W, y + 4, BTN_W, 20).build();
            button.active = !(row.available() && isCurrent) && (row.available() || row.installer() != null);
            addDrawableChild(button);
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + POPUP_WIDTH - PAD - 80, cy + popupHeight() - 28, 80, 20).build());
    }

    private void selectEngine(String engine) {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            return;
        }
        cfg.setSoulVoiceEngine(engine);
        cfg.save();
        SoulRuntime.current().ifPresent(rt -> rt.reloadSettings(cfg).exceptionally(ex -> {
            Frens.LOGGER.warn("[souls] reloadSettings failed after engine switch: {}", ex.toString());
            return null;
        }));
        this.clearChildren();
        this.init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - popupHeight()) / 2;
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + popupHeight() + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + popupHeight(), 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 8, 0xFFFFFFFF);

        for (int i = 0; i < rows.size(); i++) {
            EngineRow row = rows.get(i);
            int y = cy + 40 + i * ROW_H;
            context.drawTextWithShadow(this.textRenderer, row.title(), cx + PAD, y + 2, 0xFFEFEFEF);
            context.drawTextWithShadow(this.textRenderer, row.blurb(), cx + PAD, y + 14, 0xFFB0B0B0);
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
