package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.ChatUtils.VoiceLineCategory;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.network.configNetworkManager;

/**
 * Per-category mute menu for the pre-baked voiced dialogue lines, opened from the
 * "Adv…" chip on the global Voice toggle row in {@link BotControlScreen}.
 *
 * Muting is audio-only: overhead text, subtitles, and chat fallback still show for
 * muted categories. State is the global {@code mutedVoiceCategories} list in
 * settings.json5, written directly (checked = audible) and saved on every toggle,
 * so closing the screen any way never loses a change.
 */
public class ConfigureVoiceCategoriesScreen extends Screen {

    private static final int POPUP_WIDTH = 380;
    private static final int POPUP_HEIGHT = 210;

    // Designed size when it fits the screen, else clamped so 320x240 stays usable.
    private int popupW() { return UiFitPolicy.popupExtent(POPUP_WIDTH, this.width, 16); }
    private int popupH() { return UiFitPolicy.popupExtent(POPUP_HEIGHT, this.height, 8); }

    private static final int COLS = 2;
    private static final int ROW_HEIGHT = 22;
    private static final int CELL_PAD = 6;

    private final Screen parent;

    public ConfigureVoiceCategoriesScreen(Screen parent) {
        super(Text.literal("§bScripted Voice Categories"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = (this.width - popupW()) / 2;
        int cy = UiFitPolicy.popupOrigin(this.height, popupH());

        VoiceLineCategory[] categories = VoiceLineCategory.values();
        int gridTop = cy + 42;
        int colWidth = (popupW() - CELL_PAD * (COLS + 1)) / COLS;
        for (int i = 0; i < categories.length; i++) {
            VoiceLineCategory cat = categories[i];
            int col = i % COLS;
            int row = i / COLS;
            int bx = cx + CELL_PAD + col * (colWidth + CELL_PAD);
            int by = gridTop + row * (ROW_HEIGHT + 2);
            ButtonWidget btn = ButtonWidget.builder(buttonText(cat), b -> toggle(cat))
                    .dimensions(bx, by, colWidth, ROW_HEIGHT)
                    .build();
            btn.setTooltip(Tooltip.of(Text.literal(cat.description())));
            addDrawableChild(btn);
        }

        // Bottom row: Enable All / Mute All / Done
        int btnY = cy + popupH() - 28;
        int btnW = (popupW() - CELL_PAD * 4) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("Enable All"),
                        b -> bulkSet(true))
                .dimensions(cx + CELL_PAD, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Mute All"),
                        b -> bulkSet(false))
                .dimensions(cx + CELL_PAD * 2 + btnW, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + CELL_PAD * 3 + btnW * 2, btnY, btnW, 20).build());
    }

    private boolean isAudible(VoiceLineCategory cat) {
        return Frens.CONFIG == null || !Frens.CONFIG.isVoiceCategoryMuted(cat.id());
    }

    private Text buttonText(VoiceLineCategory cat) {
        String prefix = isAudible(cat) ? "§a[x] " : "§7[ ] ";
        return Text.literal(prefix + cat.displayName());
    }

    private void toggle(VoiceLineCategory cat) {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.setVoiceCategoryMuted(cat.id(), isAudible(cat));
        persist();
        rebuild();
    }

    private void bulkSet(boolean audible) {
        if (Frens.CONFIG == null) {
            return;
        }
        for (VoiceLineCategory cat : VoiceLineCategory.values()) {
            Frens.CONFIG.setVoiceCategoryMuted(cat.id(), !audible);
        }
        persist();
        rebuild();
    }

    private void persist() {
        Frens.CONFIG.save();
        // Voice-category muting is per-player: push only this client's mask, never the whole
        // config (which would rewrite the server's shared globals).
        configNetworkManager.sendVoiceMuteMask(Frens.CONFIG.getMutedVoiceCategories());
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = (this.width - popupW()) / 2;
        int cy = UiFitPolicy.popupOrigin(this.height, popupH());
        // Border + interior
        context.fill(cx - 1, cy - 1, cx + popupW() + 1, cy + popupH() + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + popupW(), cy + popupH(), 0xCC222222);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 10, 0xFFFFFFFF);
        // One line wherever it fits the popup (as before), two on narrow screens. Gray is the
        // old §7, passed as a colour so a wrapped second line keeps it.
        int subtitleY = cy + 24;
        for (String line : UiFitPolicy.wrapIfNeeded("Scripted lines only. Unchecked = no audio. Text and soul TTS unaffected.", popupW() - 4, this.textRenderer::getWidth)) {
            context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, subtitleY, 0xFFAAAAAA);
            subtitleY += 10;
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
