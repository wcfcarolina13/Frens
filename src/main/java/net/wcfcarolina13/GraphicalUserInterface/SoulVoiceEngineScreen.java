package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.souls.SoulRuntime;
import net.wcfcarolina13.GameAI.souls.voice.SoulVoiceSettings;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Soul-voice engine chooser, opened from the "Eng…" chip on the Soul Voice row.
 * Two engines, user's choice:
 * <ul>
 *   <li><b>Dreamsleeve</b> — the bot's cloned voice via a local Qwen3-TTS warm server.
 *       Best quality, uses the GPU, needs the local Dreamsleeve setup.</li>
 *   <li><b>Piper</b> — lightweight generic voice, CPU-only, one-time ~80 MB download via
 *       the transparent {@link PiperInstallerScreen}.</li>
 * </ul>
 * Selecting an available engine writes the config and hot-reloads the soul runtime.
 */
public class SoulVoiceEngineScreen extends Screen {

    private static final int POPUP_WIDTH = 400;
    private static final int POPUP_HEIGHT = 170;
    private static final int PAD = 8;

    private final Screen parent;
    private ButtonWidget dreamsleeveButton;
    private ButtonWidget piperButton;

    public SoulVoiceEngineScreen(Screen parent) {
        super(Text.literal("§bSoul Voice Engine"));
        this.parent = parent;
    }

    private static boolean dreamsleeveAvailable(ManualConfig cfg) {
        String dir = cfg.getSoulVoiceDreamsleeveDir();
        return dir != null && !dir.isBlank()
                && Files.isRegularFile(Path.of(dir, "scripts", "tts_server.py"));
    }

    private static boolean piperAvailable(ManualConfig cfg) {
        String bin = cfg.getSoulVoicePiperBinary();
        String model = cfg.getSoulVoiceModel();
        return bin != null && !bin.isBlank() && Files.isExecutable(Path.of(bin))
                && model != null && !model.isBlank() && Files.isRegularFile(Path.of(model));
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        ManualConfig cfg = Frens.CONFIG;
        boolean dsAvail = cfg != null && dreamsleeveAvailable(cfg);
        boolean piperAvail = cfg != null && piperAvailable(cfg);
        String current = cfg != null ? cfg.getSoulVoiceEngine() : "";

        int btnW = 130;
        dreamsleeveButton = ButtonWidget.builder(
                        Text.literal(SoulVoiceSettings.ENGINE_DREAMSLEEVE.equals(current)
                                ? "§aDreamsleeve ✔" : "Use Dreamsleeve"),
                        b -> selectEngine(SoulVoiceSettings.ENGINE_DREAMSLEEVE))
                .dimensions(cx + POPUP_WIDTH - PAD - btnW, cy + 46, btnW, 20).build();
        dreamsleeveButton.active = dsAvail && !SoulVoiceSettings.ENGINE_DREAMSLEEVE.equals(current);
        addDrawableChild(dreamsleeveButton);

        piperButton = ButtonWidget.builder(
                        Text.literal(piperAvail
                                ? (SoulVoiceSettings.ENGINE_PIPER.equals(current) ? "§aPiper ✔" : "Use Piper")
                                : "Install Piper…"),
                        b -> {
                            if (piperAvailable(Frens.CONFIG)) {
                                selectEngine(SoulVoiceSettings.ENGINE_PIPER);
                            } else if (this.client != null) {
                                this.client.setScreen(new PiperInstallerScreen(this));
                            }
                        })
                .dimensions(cx + POPUP_WIDTH - PAD - btnW, cy + 96, btnW, 20).build();
        piperButton.active = !(piperAvail && SoulVoiceSettings.ENGINE_PIPER.equals(current));
        addDrawableChild(piperButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + POPUP_WIDTH - PAD - 80, cy + POPUP_HEIGHT - 28, 80, 20).build());
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
        int cy = (this.height - POPUP_HEIGHT) / 2;
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xE0181818);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 8, 0xFFFFFFFF);

        ManualConfig cfg = Frens.CONFIG;
        boolean dsAvail = cfg != null && dreamsleeveAvailable(cfg);
        boolean piperAvail = cfg != null && piperAvailable(cfg);

        context.drawTextWithShadow(this.textRenderer, "Dreamsleeve — cloned bot voice", cx + PAD, cy + 42, 0xFFEFEFEF);
        context.drawTextWithShadow(this.textRenderer,
                "§7Best quality. Uses the GPU. " + (dsAvail ? "Available." : "Not set up on this machine."),
                cx + PAD, cy + 54, 0xFFB0B0B0);

        context.drawTextWithShadow(this.textRenderer, "Piper — lightweight generic voice", cx + PAD, cy + 92, 0xFFEFEFEF);
        context.drawTextWithShadow(this.textRenderer,
                "§7Fast, CPU-only, frees the GPU. " + (piperAvail ? "Installed." : "One-time ~80 MB download."),
                cx + PAD, cy + 104, 0xFFB0B0B0);

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
