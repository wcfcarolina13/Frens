package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.dialogue.DialoguePacing;
import net.wcfcarolina13.network.ConfigJsonUtil;
import net.wcfcarolina13.network.configNetworkManager;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * Four dialogue-frequency sliders (Scripted lines, Idle banter, Active banter, Local
 * chime-ins), opened from the "Rates…" chip on the Banter row of {@link BotControlScreen}.
 * 0–100, 50 = shipped cadence; the caption on each knob shows the resulting band. Writes
 * settings.json5 on every change (autosave ruling), so closing any way never loses a value.
 * The toggles on the parent panel remain the on/off switches — a slider never means "off".
 */
public class DialogueSettingsScreen extends Screen {

    private static final int POPUP_WIDTH = 360;
    private static final int POPUP_HEIGHT = 232;
    private static final int PAD = 8;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 16;

    private final Screen parent;

    public DialogueSettingsScreen(Screen parent) {
        super(Text.literal("§bDialogue Frequency"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        int w = POPUP_WIDTH - PAD * 2;
        int y = cy + 40;
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                    .dimensions(cx + PAD, cy + POPUP_HEIGHT - 28, w, 20).build());
            return;
        }
        addRow(cx + PAD, y, w, "Scripted lines", cfg::getDialogueScriptedRate, cfg::setDialogueScriptedRate,
                rate -> String.format(Locale.ROOT, "cooldowns ×%.2f", DialoguePacing.multiplier(rate)),
                "Pet, weather, gear, inventory, wake-up and enchanting remarks. Left = rarer, right = chattier.");
        y += ROW_H + ROW_GAP;
        addRow(cx + PAD, y, w, "Idle banter", cfg::getSoulBanterIdleRate, cfg::setSoulBanterIdleRate,
                rate -> DialoguePacing.describe(rate, 8 * 60_000L, 15 * 60_000L),
                "LLM banter when things are calm (Banter toggle). The first scene of a session is always 60–150 s in.");
        y += ROW_H + ROW_GAP;
        addRow(cx + PAD, y, w, "Active banter", cfg::getSoulBanterActiveRate, cfg::setSoulBanterActiveRate,
                rate -> DialoguePacing.describe(rate, 4 * 60_000L, 8 * 60_000L),
                "LLM banter while a companion is working or following you (Active toggle).");
        y += ROW_H + ROW_GAP;
        addRow(cx + PAD, y, w, "Local chime-ins", cfg::getSoulLocalRate, cfg::setSoulLocalRate,
                rate -> DialoguePacing.describe(rate, 6 * 60_000L, 12 * 60_000L),
                "Reactions to chat you type near them that wasn't addressed to anyone (Local toggle).");

        int btnY = cy + POPUP_HEIGHT - 28;
        int btnW = (w - PAD) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset defaults"), b -> resetDefaults())
                .dimensions(cx + PAD, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + PAD + btnW + PAD, btnY, btnW, 20).build());
    }

    private void addRow(int x, int y, int w, String label, IntSupplier get, IntConsumer set,
                        IntFunction<String> caption, String tooltip) {
        RateSlider slider = new RateSlider(x, y, w, ROW_H, label, get.getAsInt(), set, caption);
        slider.setTooltip(Tooltip.of(Text.literal(tooltip)));
        addDrawableChild(slider);
    }

    private void resetDefaults() {
        ManualConfig cfg = Frens.CONFIG;
        if (cfg == null) {
            return;
        }
        cfg.setDialogueScriptedRate(DialoguePacing.DEFAULT_RATE);
        cfg.setSoulBanterIdleRate(DialoguePacing.DEFAULT_RATE);
        cfg.setSoulBanterActiveRate(DialoguePacing.DEFAULT_RATE);
        cfg.setSoulLocalRate(DialoguePacing.DEFAULT_RATE);
        persist();
        this.clearChildren();
        this.init();
    }

    private static void persist() {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.save();
        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());
    }

    /** One slider: label + rate on the knob, resulting cadence as a grey caption. */
    private static final class RateSlider extends SliderWidget {
        private final String label;
        private final IntConsumer set;
        private final IntFunction<String> caption;
        private int lastApplied;

        RateSlider(int x, int y, int w, int h, String label, int initial, IntConsumer set,
                   IntFunction<String> caption) {
            super(x, y, w, h, Text.literal(""), initial / 100.0);
            this.label = label;
            this.set = set;
            this.caption = caption;
            this.lastApplied = initial;
            updateMessage();
        }

        private int rate() {
            return (int) Math.round(this.value * 100.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + rate() + "   §7" + caption.apply(rate())));
        }

        @Override
        protected void applyValue() {
            int rate = rate();
            if (rate == lastApplied) {
                return;
            }
            lastApplied = rate;
            set.accept(rate);
            persist();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xCC222222);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§750 = shipped cadence. Toggles stay the on/off switches."),
                this.width / 2, cy + 24, 0xFFFFFFFF);
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
