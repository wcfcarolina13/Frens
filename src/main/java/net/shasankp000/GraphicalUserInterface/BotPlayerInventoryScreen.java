package net.shasankp000.GraphicalUserInterface;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.shasankp000.ui.BotPlayerInventoryScreenHandler;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BotPlayerInventoryScreen extends HandledScreen<BotPlayerInventoryScreenHandler> {
    private static final Identifier BACKGROUND_TEXTURE = Identifier.of("minecraft", "textures/gui/container/inventory.png");
    private static final int SECTION_WIDTH = 176;
    private static final int SECTION_HEIGHT = 166;
    private static final int BLOCK_GAP = 12;
    private static final int STATS_AREA_HEIGHT = 48;
    private OtherClientPlayerEntity fallbackBot;
    private final String botAlias;
    private float lastMouseX;
    private float lastMouseY;

    public BotPlayerInventoryScreen(BotPlayerInventoryScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = SECTION_WIDTH * 2 + BLOCK_GAP;
        this.backgroundHeight = SECTION_HEIGHT + STATS_AREA_HEIGHT;
        this.titleX = 8;
        this.titleY = 6;
        this.playerInventoryTitleX = SECTION_WIDTH + BLOCK_GAP + 8;
        this.playerInventoryTitleY = 6;
        this.botAlias = extractAlias(title.getString());
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (this.width - this.backgroundWidth) / 2;
        int y = (this.height - this.backgroundHeight) / 2;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, BACKGROUND_TEXTURE, x + SECTION_WIDTH + BLOCK_GAP, y, 0f, 0f,
                SECTION_WIDTH, SECTION_HEIGHT, 256, 256);

        int statsTop = y + SECTION_HEIGHT + 2;
        context.fill(x, statsTop, x + SECTION_WIDTH, statsTop + STATS_AREA_HEIGHT - 4, 0xC0101010);
        drawBotStats(context, x + 6, statsTop + 6);
    }

    private void drawBotStats(DrawContext context, int x, int y) {
        BotPlayerInventoryScreenHandler handler = this.handler;
        float health = handler.getBotHealth();
        float maxHealth = handler.getBotMaxHealth();
        int hunger = handler.getBotHunger();
        int level = handler.getBotLevel();
        float xpProgress = handler.getBotXpProgress();
        int totalXp = handler.getBotTotalExperience();

        String healthLabel = String.format("Health: %.1f / %.1f", health, maxHealth);
        context.drawText(this.textRenderer, healthLabel, x, y, 0xFFEFEFEF, false);
        drawBar(context, x, y + 10, 120, 6, health / maxHealth, 0xFFB83E3E);
        String hungerLabel = "Hunger: " + hunger;
        context.drawText(this.textRenderer, hungerLabel, x, y + 20, 0xFFEFEFEF, false);
        drawBar(context, x, y + 30, 120, 6, MathHelper.clamp(hunger / 20f, 0.0f, 1.0f), 0xFFE3C05C);

        String xpLabel = String.format("XP: L%d (%d)  %.0f%%", level, totalXp, xpProgress * 100.0F);
        context.drawText(this.textRenderer, xpLabel, x + 130, y, 0xFFEFEFEF, false);
        drawBar(context, x + 130, y + 10, 120, 6, xpProgress, 0xFF4FA3E3);
    }

    private void drawBar(DrawContext context, int x, int y, int width, int height, float value, int color) {
        int border = 0xFF000000;
        context.fill(x, y, x + width, y + height, 0xFF1A1A1A);
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
        context.fill(x, y, x + 1, y + height, border);
        context.fill(x + width - 1, y, x + width, y + height, border);
        int filled = Math.round((width - 2) * MathHelper.clamp(value, 0.0f, 1.0f));
        context.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, this.botAlias, this.titleX, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, "Level: " + this.handler.getBotLevel(), this.titleX + 90, this.titleY, 0x404040, false);
        context.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawEntities(context, mouseX, mouseY);
        this.drawMouseoverTooltip(context, mouseX, mouseY);
    }

    private void drawEntities(DrawContext context, int mouseX, int mouseY) {
        int leftX1 = this.x + 26;
        int leftY1 = this.y + 8;
        int leftX2 = this.x + 75;
        int leftY2 = this.y + 78;
        int rightX1 = this.x + SECTION_WIDTH + BLOCK_GAP + 26;
        int rightY1 = this.y + 8;
        int rightX2 = this.x + SECTION_WIDTH + BLOCK_GAP + 75;
        int rightY2 = this.y + 78;
        LivingEntity botEntity = findBotEntity();
        if (botEntity != null) {
            InventoryScreen.drawEntity(context, leftX1, leftY1, leftX2, leftY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, botEntity);
        }
        if (this.client != null && this.client.player != null) {
            InventoryScreen.drawEntity(context, rightX1, rightY1, rightX2, rightY2,
                    30, 0.0625f, this.lastMouseX, this.lastMouseY, this.client.player);
        }
    }

    private LivingEntity findBotEntity() {
        if (this.client == null || this.client.world == null) return null;
        String raw = this.title.getString();
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return null;
        String name = raw.substring(0, idx);
        for (AbstractClientPlayerEntity player : this.client.world.getPlayers()) {
            if (player.getGameProfile().name().equals(name)) {
                return player;
            }
        }
        if (this.client.player == null) return null;
        if (fallbackBot == null || !fallbackBot.getGameProfile().name().equals(name)) {
            GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("bot:" + name).getBytes(StandardCharsets.UTF_8)), name);
            fallbackBot = new OtherClientPlayerEntity(this.client.world, profile);
            fallbackBot.copyPositionAndRotation(this.client.player);
        }
        fallbackBot.tick();
        return fallbackBot;
    }

    private static String extractAlias(String raw) {
        int idx = raw.indexOf("'s Inventory");
        if (idx <= 0) return raw;
        return raw.substring(0, idx);
    }
}
