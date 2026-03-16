package net.wcfcarolina13.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.network.StoreTargetPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HUD overlay for block-targeting store deposits.
 * Activated from the Actions menu "Store Here" button.
 * Player points at a container block and left-clicks to direct the bot to deposit items.
 * Right-click cancels.
 */
public final class StoreTargetPickerOverlay {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final double MAX_RANGE = 6.0;

    private static boolean active = false;
    private static String botName = null;
    private static String mode = "store"; // "store" or "fetch"

    private static boolean lastAttackDown = false;
    private static boolean lastUseDown = false;

    private static BlockPos hoveredContainer = null;
    private static boolean hoveredIsContainer = false;

    private StoreTargetPickerOverlay() {}

    public static void activate(String bot, String actionMode) {
        active = true;
        botName = bot;
        mode = actionMode != null ? actionMode : "store";
        lastAttackDown = false;
        lastUseDown = false;
        hoveredContainer = null;
        hoveredIsContainer = false;
    }

    public static void deactivate() {
        active = false;
        botName = null;
        mode = "store";
        hoveredContainer = null;
        hoveredIsContainer = false;
        lastAttackDown = false;
        lastUseDown = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void onClientTick(MinecraftClient client) {
        if (!active || client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        boolean attackDown = client.options.attackKey.isPressed();
        boolean useDown = client.options.useKey.isPressed();

        // Left-click edge: select container
        if (attackDown && !lastAttackDown) {
            if (hoveredContainer != null && hoveredIsContainer) {
                sendTargetPayload(hoveredContainer);
                deactivate();
            }
        }

        // Right-click edge: cancel
        if (useDown && !lastUseDown) {
            deactivate();
        }

        lastAttackDown = attackDown;
        lastUseDown = useDown;
    }

    public static void render(DrawContext context) {
        if (!active) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) return;

        int cx = context.getScaledWindowWidth() / 2;
        int cy = context.getScaledWindowHeight() / 2;

        // Instructions below crosshair
        String cleanName = botName != null ? botName.replace("\"", "") : "bot";
        String line1 = "fetch".equals(mode)
                ? "Point at a chest and click to direct " + cleanName + " to take items"
                : "Point at a chest and click to direct " + cleanName + " to deposit items";
        String line2 = "Right-click to cancel";
        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int textY = cy + 16;

        context.fill(cx - maxW / 2 - 6, textY - 4,
                cx + maxW / 2 + 6, textY + client.textRenderer.fontHeight * 2 + 6,
                0xAA1a1a1a);
        context.drawCenteredTextWithShadow(client.textRenderer, line1, cx, textY, 0xFFA8D86A);
        context.drawCenteredTextWithShadow(client.textRenderer, line2, cx, textY + client.textRenderer.fontHeight + 2, 0xFF90C890);

        // Raycast for block under crosshair
        updateHoveredBlock(client);

        if (hoveredContainer != null) {
            String label;
            int labelColor;
            int bgColor;
            if (hoveredIsContainer) {
                label = "\u25B6 Container at " + hoveredContainer.getX() + ", " + hoveredContainer.getY() + ", " + hoveredContainer.getZ();
                labelColor = 0xFFA8D86A;
                bgColor = 0xCC1a3a1a;
            } else {
                label = "\u25B6 Not a container";
                labelColor = 0xFFAA7777;
                bgColor = 0xCC3a1a1a;
            }
            int labelW = client.textRenderer.getWidth(label);
            int labelX = cx + 12;
            int labelY = cy - client.textRenderer.fontHeight / 2;
            context.fill(labelX - 3, labelY - 2, labelX + labelW + 3, labelY + client.textRenderer.fontHeight + 2, bgColor);
            context.drawTextWithShadow(client.textRenderer, label, labelX, labelY, labelColor);
        }
    }

    private static void updateHoveredBlock(MinecraftClient client) {
        hoveredContainer = null;
        hoveredIsContainer = false;
        if (client.world == null || client.player == null) return;

        HitResult hit = client.player.raycast(MAX_RANGE, 1.0F, false);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        hoveredContainer = pos;

        BlockEntity be = client.world.getBlockEntity(pos);
        hoveredIsContainer = be instanceof Inventory;
    }

    private static void sendTargetPayload(BlockPos pos) {
        if (pos == null || botName == null) return;
        if (!ClientPlayNetworking.canSend(StoreTargetPayload.ID)) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botName", botName);
        out.put("mode", mode);
        out.put("x", pos.getX());
        out.put("y", pos.getY());
        out.put("z", pos.getZ());
        ClientPlayNetworking.send(new StoreTargetPayload(GSON.toJson(out)));
    }
}
