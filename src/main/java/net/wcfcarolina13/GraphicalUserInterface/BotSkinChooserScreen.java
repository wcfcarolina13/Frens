package net.wcfcarolina13.GraphicalUserInterface;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.client.input.KeyInput;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.wcfcarolina13.GameAI.services.BotSkinService;
import net.wcfcarolina13.GameAI.services.BotSkinService.SkinPreset;
import net.wcfcarolina13.network.BotSkinPayload;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Client-side screen for choosing a bot's skin.  Accessible from the
 * Admin tab in {@link BotPlayerInventoryScreen}.
 *
 * <p>Layout: bot entity preview on the left, scrollable preset list on
 * the right, "Random" button at top of list, "Done" at the bottom.
 */
public final class BotSkinChooserScreen extends Screen {

    // ── parent / context ────────────────────────────────────────────────
    private final Screen parent;
    private final String botAlias;

    // ── preview entity ──────────────────────────────────────────────────
    private OtherClientPlayerEntity fallbackBot;
    private float lastMouseX;
    private float lastMouseY;

    // ── presets ─────────────────────────────────────────────────────────
    private final List<SkinPreset> presets;
    private String selectedSkinId;           // null until user clicks

    // ── scroll state ────────────────────────────────────────────────────
    private double scrollOffset;
    private boolean scrollDragging;
    private double scrollDragAnchorY;
    private double scrollDragAnchorOffset;

    // ── layout (computed in init()) ─────────────────────────────────────
    private int panelX, panelY, panelW, panelH;
    private int previewX, previewY, previewW, previewH;
    private int listX, listY, listW, listH;

    // ── colours (matching BotControlScreen / BotGuideScreen palette) ────
    private static final int COL_BG            = 0xD0101010;
    private static final int COL_PANEL         = 0xFF121212;
    private static final int COL_BORDER        = 0xFF2A2A2A;
    private static final int COL_TITLE         = 0xFFFFE08A;
    private static final int COL_LABEL         = 0xFFEFEFEF;
    private static final int COL_DESC          = 0xFF7F7F7F;
    private static final int COL_ACCENT        = 0xFFB8A76A;
    private static final int COL_SEC_LINE      = 0x606B522C;
    private static final int COL_SELECTED      = 0xFF3A2C14;
    private static final int COL_HOVER         = 0xFF282828;
    private static final int COL_SELECTED_HOVER = 0xFF4A3720;
    private static final int COL_ITEM_BG       = 0xFF1A1A1A;
    private static final int COL_TRACK         = 0xFF171717;
    private static final int COL_THUMB         = 0xFF7A6240;
    private static final int COL_THUMB_HL      = 0xFFB08C40;
    private static final int COL_RND_BTN       = 0xFF2E4A2E;
    private static final int COL_RND_BTN_HOVER = 0xFF3E6A3E;
    private static final int COL_PREVIEW_BG    = 0xFF0A0A0A;

    // ── sizes ───────────────────────────────────────────────────────────
    private static final int ITEM_H       = 22;
    private static final int SCROLLBAR_W  = 5;
    private static final int THUMB_MIN_H  = 16;
    private static final int GAP          = 8;
    private static final int TITLE_H      = 22;
    private static final int BTN_ROW_H    = 24;
    private static final int RND_BTN_H    = 18;

    // ═════════════════════════════════════════════════════════════════════

    public BotSkinChooserScreen(Screen parent, String botAlias) {
        super(Text.literal("Change Skin"));
        this.parent   = parent;
        this.botAlias = botAlias;
        this.presets  = BotSkinService.presets();
    }

    // ── init ────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();

        panelW = Math.min(300, this.width - 20);
        panelH = Math.min(230, this.height - 30);
        panelX = (this.width  - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        // Left column: entity preview
        previewW = 90;
        previewX = panelX + GAP;
        previewY = panelY + TITLE_H;
        previewH = panelH - TITLE_H - BTN_ROW_H;

        // Right column: list of presets
        listX = previewX + previewW + GAP;
        listY = previewY;
        listW = panelX + panelW - GAP - listX;
        listH = previewH;

        scrollOffset = 0;
    }

    // ── entity lookup ───────────────────────────────────────────────────

    private LivingEntity findBotEntity() {
        if (this.client == null || this.client.world == null) return null;

        // Try the actual bot in the world
        for (AbstractClientPlayerEntity p : this.client.world.getPlayers()) {
            if (p.getGameProfile().name().equals(botAlias)) return p;
        }

        // Fallback: deterministic dummy (matches BotPlayerInventoryScreen)
        if (this.client.player == null) return null;
        if (fallbackBot == null || !fallbackBot.getGameProfile().name().equals(botAlias)) {
            GameProfile profile = new GameProfile(
                    UUID.nameUUIDFromBytes(("bot:" + botAlias).getBytes(StandardCharsets.UTF_8)),
                    botAlias);
            fallbackBot = new OtherClientPlayerEntity(this.client.world, profile);
            fallbackBot.copyPositionAndRotation(this.client.player);
        }
        fallbackBot.tick();
        return fallbackBot;
    }

    // ── rendering ───────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, COL_BG);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        // Panel border + fill
        ctx.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, COL_BORDER);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, COL_PANEL);

        // Title
        String titleStr = "Change Skin";
        int titleW = this.textRenderer.getWidth(titleStr);
        ctx.drawText(this.textRenderer, titleStr,
                panelX + (panelW - titleW) / 2, panelY + 7, COL_TITLE, true);

        // Bot preview
        renderPreview(ctx, mouseX, mouseY);

        // Vertical divider
        int divX = previewX + previewW + GAP / 2;
        ctx.fill(divX, previewY, divX + 1, previewY + previewH, COL_SEC_LINE);

        // Preset list (random button + scrollable list)
        renderRandomButton(ctx, mouseX, mouseY);
        renderPresetList(ctx, mouseX, mouseY);

        // Done button
        renderDoneButton(ctx, mouseX, mouseY);
    }

    private void renderPreview(DrawContext ctx, int mouseX, int mouseY) {
        ctx.fill(previewX, previewY, previewX + previewW, previewY + previewH, COL_PREVIEW_BG);

        LivingEntity entity = findBotEntity();
        if (entity != null) {
            int pad = 5;
            int ex1 = previewX + pad;
            int ey1 = previewY + pad;
            int ex2 = previewX + previewW - pad;
            int ey2 = previewY + previewH - pad;
            int scale = Math.min((ex2 - ex1) / 2, (ey2 - ey1) / 3);
            InventoryScreen.drawEntity(ctx, ex1, ey1, ex2, ey2,
                    scale, 0.0625f, mouseX, mouseY, entity);
        } else {
            String na = "N/A";
            int nw = this.textRenderer.getWidth(na);
            ctx.drawText(this.textRenderer, na,
                    previewX + (previewW - nw) / 2,
                    previewY + previewH / 2 - 4, COL_DESC, false);
        }

        // Bot name below preview
        int nameW = this.textRenderer.getWidth(botAlias);
        ctx.drawText(this.textRenderer, botAlias,
                previewX + (previewW - nameW) / 2,
                previewY + previewH + 2, COL_ACCENT, true);
    }

    // ── Random button (top of right column) ─────────────────────────────

    private int rndBtnY() { return listY; }

    private void renderRandomButton(DrawContext ctx, int mouseX, int mouseY) {
        int by = rndBtnY();
        boolean hovered = mouseX >= listX && mouseX < listX + listW
                && mouseY >= by && mouseY < by + RND_BTN_H;
        ctx.fill(listX, by, listX + listW, by + RND_BTN_H,
                hovered ? COL_RND_BTN_HOVER : COL_RND_BTN);
        ctx.fill(listX, by, listX + listW, by + 1, COL_ACCENT);   // accent top edge

        String label = "Random";
        int lw = this.textRenderer.getWidth(label);
        ctx.drawText(this.textRenderer, label,
                listX + (listW - lw) / 2, by + (RND_BTN_H - 8) / 2,
                COL_LABEL, true);
    }

    // ── Scrollable preset list ──────────────────────────────────────────

    /** Y where the scrollable content starts (below random button). */
    private int contentTop() { return listY + RND_BTN_H + 3; }
    /** Height of the scrollable viewport. */
    private int contentH()   { return listH - RND_BTN_H - 3; }
    /** Total pixel height of all preset rows. */
    private int totalH()     { return presets.size() * ITEM_H; }
    /** Maximum scroll offset. */
    private int maxScroll()  { return Math.max(0, totalH() - contentH()); }

    private void renderPresetList(DrawContext ctx, int mouseX, int mouseY) {
        int cTop = contentTop();
        int cH   = contentH();
        int usableW = listW - SCROLLBAR_W - 2;   // room for scrollbar

        ctx.enableScissor(listX, cTop, listX + listW, cTop + cH);

        int y = cTop - (int) scrollOffset;
        for (SkinPreset preset : presets) {
            if (y + ITEM_H > cTop && y < cTop + cH) {
                boolean selected = preset.id().equals(selectedSkinId);
                boolean hovered  = mouseX >= listX && mouseX < listX + usableW
                        && mouseY >= y && mouseY < y + ITEM_H
                        && mouseY >= cTop && mouseY < cTop + cH;

                int bg;
                if (selected && hovered)     bg = COL_SELECTED_HOVER;
                else if (selected)           bg = COL_SELECTED;
                else if (hovered)            bg = COL_HOVER;
                else                         bg = COL_ITEM_BG;

                ctx.fill(listX, y, listX + usableW, y + ITEM_H - 1, bg);

                // Display name
                int textCol = selected ? COL_TITLE : COL_LABEL;
                ctx.drawText(this.textRenderer, preset.displayName(),
                        listX + 6, y + (ITEM_H - 8) / 2, textCol, false);

                // Model type tag (Slim / Classic)
                String model = preset.slim() ? "Slim" : "Classic";
                int mw = this.textRenderer.getWidth(model);
                ctx.drawText(this.textRenderer, model,
                        listX + usableW - 4 - mw, y + (ITEM_H - 8) / 2,
                        COL_DESC, false);
            }
            y += ITEM_H;
        }

        ctx.disableScissor();

        // Scrollbar
        if (totalH() > cH) {
            int trackX = listX + listW - SCROLLBAR_W;
            ctx.fill(trackX, cTop, trackX + SCROLLBAR_W, cTop + cH, COL_TRACK);

            double ratio  = (double) cH / totalH();
            int    thumbH = Math.max(THUMB_MIN_H, (int) (cH * ratio));
            int    maxTY  = cH - thumbH;
            int    thumbY = cTop + (maxScroll() > 0
                    ? (int) (maxTY * scrollOffset / maxScroll()) : 0);

            boolean thumbHovered = mouseX >= trackX && mouseX < trackX + SCROLLBAR_W
                    && mouseY >= thumbY && mouseY < thumbY + thumbH;
            ctx.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH,
                    scrollDragging || thumbHovered ? COL_THUMB_HL : COL_THUMB);
        }
    }

    // ── Done button (bottom-centre of panel) ────────────────────────────

    private static final int DONE_H = 18;

    private int doneBtnX() {
        String t = "Done";
        int tw = this.textRenderer.getWidth(t);
        return panelX + (panelW - tw - 16) / 2;
    }
    private int doneBtnY() {
        return panelY + panelH - DONE_H - 3;
    }
    private int doneBtnW() {
        return this.textRenderer.getWidth("Done") + 16;
    }

    private void renderDoneButton(DrawContext ctx, int mouseX, int mouseY) {
        int bx = doneBtnX(), by = doneBtnY(), bw = doneBtnW();
        boolean hovered = mouseX >= bx && mouseX < bx + bw
                && mouseY >= by && mouseY < by + DONE_H;
        ctx.fill(bx, by, bx + bw, by + DONE_H, hovered ? COL_HOVER : COL_ITEM_BG);
        ctx.fill(bx, by, bx + bw, by + 1, COL_BORDER);  // subtle top edge
        ctx.drawText(this.textRenderer, "Done",
                bx + 8, by + (DONE_H - 8) / 2, COL_LABEL, true);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Input
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean isInside) {
        double mx = click.x();
        double my = click.y();
        if (click.button() != 0) return super.mouseClicked(click, isInside);

        // ── Random button ──
        int by = rndBtnY();
        if (mx >= listX && mx < listX + listW && my >= by && my < by + RND_BTN_H) {
            sendSkinChange("random");
            selectedSkinId = null;
            playClick();
            return true;
        }

        // ── Preset list ──
        int cTop = contentTop();
        int cH   = contentH();
        int usableW = listW - SCROLLBAR_W - 2;
        if (mx >= listX && mx < listX + usableW && my >= cTop && my < cTop + cH) {
            int relY  = (int) (my - cTop + scrollOffset);
            int index = relY / ITEM_H;
            if (index >= 0 && index < presets.size()) {
                SkinPreset preset = presets.get(index);
                sendSkinChange(preset.id());
                selectedSkinId = preset.id();
                playClick();
                return true;
            }
        }

        // ── Scrollbar ──
        if (totalH() > cH) {
            int trackX = listX + listW - SCROLLBAR_W;
            if (mx >= trackX && mx < trackX + SCROLLBAR_W
                    && my >= cTop && my < cTop + cH) {
                scrollDragging        = true;
                scrollDragAnchorY     = my;
                scrollDragAnchorOffset = scrollOffset;
                return true;
            }
        }

        // ── Done button ──
        {
            int dx = doneBtnX(), dy = doneBtnY(), dw = doneBtnW();
            if (mx >= dx && mx < dx + dw && my >= dy && my < dy + DONE_H) {
                close();
                return true;
            }
        }

        return super.mouseClicked(click, isInside);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        if (scrollDragging && click.button() == 0) {
            scrollDragging = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (scrollDragging) {
            double my = click.y();
            int cH = contentH();
            double ratio  = (double) cH / totalH();
            int    thumbH = Math.max(THUMB_MIN_H, (int) (cH * ratio));
            double range  = cH - thumbH;
            if (range > 0) {
                double delta = my - scrollDragAnchorY;
                scrollOffset = MathHelper.clamp(
                        scrollDragAnchorOffset + delta * maxScroll() / range, 0, maxScroll());
            }
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hDelta, double vDelta) {
        scrollOffset = MathHelper.clamp(scrollOffset - vDelta * 10, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void sendSkinChange(String presetId) {
        ClientPlayNetworking.send(new BotSkinPayload(botAlias, presetId));
    }

    private void playClick() {
        if (this.client != null && this.client.player != null) {
            this.client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, 1.0f);
        }
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
