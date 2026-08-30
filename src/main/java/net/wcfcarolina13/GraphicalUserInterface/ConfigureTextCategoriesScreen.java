package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.wcfcarolina13.ChatUtils.VoiceLineCategory;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.network.ConfigJsonUtil;
import net.wcfcarolina13.network.configNetworkManager;

/**
 * Per-category text muting, opened from the "Adv…" chip on the global Scripted Text row in
 * {@link BotControlScreen}.
 *
 * Same semantics as the voice category screen, deliberately (Bradley flagged the earlier
 * inverse model as conflicting): the Scripted Text master is a hard kill switch, and while it
 * is ON, checked categories show their text and unchecked ones are hidden (audio
 * unaffected). State is {@code mutedTextCategories} in settings.json5, saved on every
 * toggle.
 */
public class ConfigureTextCategoriesScreen extends Screen {

    private static final int POPUP_WIDTH = 380;
    private static final int POPUP_HEIGHT = 210;
    private static final int COLS = 2;
    private static final int ROW_HEIGHT = 22;
    private static final int CELL_PAD = 6;

    private final Screen parent;

    public ConfigureTextCategoriesScreen(Screen parent) {
        super(Text.literal("§bScripted Text Categories"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;

        VoiceLineCategory[] categories = VoiceLineCategory.values();
        int gridTop = cy + 42;
        int colWidth = (POPUP_WIDTH - CELL_PAD * (COLS + 1)) / COLS;
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
        int btnY = cy + POPUP_HEIGHT - 28;
        int btnW = (POPUP_WIDTH - CELL_PAD * 4) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("Enable All"),
                        b -> bulkSet(true))
                .dimensions(cx + CELL_PAD, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Mute All"),
                        b -> bulkSet(false))
                .dimensions(cx + CELL_PAD * 2 + btnW, btnY, btnW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx + CELL_PAD * 3 + btnW * 2, btnY, btnW, 20).build());
    }

    private boolean isShown(VoiceLineCategory cat) {
        return Frens.CONFIG == null || !Frens.CONFIG.isTextCategoryMuted(cat.id());
    }

    private Text buttonText(VoiceLineCategory cat) {
        String prefix = isShown(cat) ? "§a[x] " : "§7[ ] ";
        return Text.literal(prefix + cat.displayName());
    }

    private void toggle(VoiceLineCategory cat) {
        if (Frens.CONFIG == null) {
            return;
        }
        Frens.CONFIG.setTextCategoryMuted(cat.id(), isShown(cat));
        persist();
        rebuild();
    }

    private void bulkSet(boolean shown) {
        if (Frens.CONFIG == null) {
            return;
        }
        for (VoiceLineCategory cat : VoiceLineCategory.values()) {
            Frens.CONFIG.setTextCategoryMuted(cat.id(), !shown);
        }
        persist();
        rebuild();
    }

    private void persist() {
        Frens.CONFIG.save();
        configNetworkManager.sendSaveConfigPacket(ConfigJsonUtil.configToJson());
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = (this.width - POPUP_WIDTH) / 2;
        int cy = (this.height - POPUP_HEIGHT) / 2;
        // Border + interior
        context.fill(cx - 1, cy - 1, cx + POPUP_WIDTH + 1, cy + POPUP_HEIGHT + 1, 0xFF00CCCC);
        context.fill(cx, cy, cx + POPUP_WIDTH, cy + POPUP_HEIGHT, 0xCC222222);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, cy + 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7Scripted lines only. Unchecked = no text; audio and soul chat unaffected."),
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
