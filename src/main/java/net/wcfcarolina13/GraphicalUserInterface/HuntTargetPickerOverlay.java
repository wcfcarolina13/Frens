package net.wcfcarolina13.GraphicalUserInterface;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.network.HuntTargetModePayload;
import net.wcfcarolina13.network.HuntTargetPayload;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * HUD overlay for entity-picking hunt targeting.
 * Activated from the HuntablesScreen TARGET button.
 * Renders crosshair instructions and entity name label when hovering a valid target.
 * Left-click selects the entity; right-click cancels.
 */
public final class HuntTargetPickerOverlay {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Known food mob entity types (client-side mirror of HuntCatalog.FOOD_TYPES). */
    private static final Set<EntityType<?>> HUNTABLE_TYPES = Set.of(
            EntityType.COW, EntityType.MOOSHROOM, EntityType.PIG, EntityType.CHICKEN,
            EntityType.SHEEP, EntityType.RABBIT, EntityType.COD, EntityType.SALMON,
            EntityType.TROPICAL_FISH, EntityType.PUFFERFISH, EntityType.FOX,
            EntityType.TURTLE, EntityType.GOAT, EntityType.SQUID, EntityType.GLOW_SQUID,
            EntityType.ZOMBIE
    );

    private static boolean active = false;
    private static String botName = null;

    // Edge detection for mouse buttons
    private static boolean lastAttackDown = false;
    private static boolean lastUseDown = false;

    // Cached entity under crosshair (updated each render frame)
    private static LivingEntity hoveredEntity = null;

    private HuntTargetPickerOverlay() {}

    public static void activate(String bot) {
        active = true;
        botName = bot;
        lastAttackDown = false;
        lastUseDown = false;
        hoveredEntity = null;
        sendModePayload(true);
    }

    public static void deactivate() {
        if (active) {
            sendModePayload(false);
        }
        active = false;
        botName = null;
        hoveredEntity = null;
        lastAttackDown = false;
        lastUseDown = false;
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Called from a client tick callback. Checks for mouse click actions.
     */
    public static void onClientTick(MinecraftClient client) {
        if (!active || client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        boolean attackDown = client.options.attackKey.isPressed();
        boolean useDown = client.options.useKey.isPressed();

        // Left-click (attack key) edge: select entity
        if (attackDown && !lastAttackDown) {
            LivingEntity target = findTargetedHuntableEntity(client);
            if (target != null) {
                sendTargetPayload(target);
                deactivate();
            }
        }

        // Right-click (use key) edge: cancel
        if (useDown && !lastUseDown) {
            deactivate();
        }

        lastAttackDown = attackDown;
        lastUseDown = useDown;
    }

    /**
     * HUD render callback. Draws instructions and entity label.
     */
    public static void render(DrawContext context) {
        if (!active) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }

        int cx = context.getScaledWindowWidth() / 2;
        int cy = context.getScaledWindowHeight() / 2;

        // Instruction text below crosshair
        String line1 = "Click a mob to target it";
        String line2 = "Right-click to cancel";
        int w1 = client.textRenderer.getWidth(line1);
        int w2 = client.textRenderer.getWidth(line2);
        int maxW = Math.max(w1, w2);
        int textY = cy + 16;

        // Background box
        context.fill(cx - maxW / 2 - 6, textY - 4,
                cx + maxW / 2 + 6, textY + client.textRenderer.fontHeight * 2 + 6,
                0xAA1a1a1a);

        context.drawCenteredTextWithShadow(client.textRenderer, line1, cx, textY, 0xFFA8D86A);
        context.drawCenteredTextWithShadow(client.textRenderer, line2, cx, textY + client.textRenderer.fontHeight + 2, 0xFF90C890);

        // Raycast for entity under crosshair
        hoveredEntity = findTargetedHuntableEntity(client);
        if (hoveredEntity != null) {
            String name = hoveredEntity.getName().getString();
            if (name == null || name.isBlank()) {
                Identifier entityId = EntityType.getId(hoveredEntity.getType());
                name = entityId != null ? entityId.getPath() : "Unknown";
            }

            String label = "\u25B6 " + name;
            int labelW = client.textRenderer.getWidth(label);
            int labelX = cx + 12;
            int labelY = cy - client.textRenderer.fontHeight / 2;

            // Background for label
            context.fill(labelX - 3, labelY - 2, labelX + labelW + 3, labelY + client.textRenderer.fontHeight + 2, 0xCC1a3a1a);
            context.drawTextWithShadow(client.textRenderer, label, labelX, labelY, 0xFFA8D86A);
        }
    }

    private static LivingEntity findTargetedHuntableEntity(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        final double maxDistance = 32.0;
        Vec3d eyePos = client.player.getEyePos();
        Vec3d lookVec = client.player.getRotationVec(1.0F);
        Vec3d rayEnd = eyePos.add(lookVec.multiply(maxDistance));

        LivingEntity found = null;
        double closestDistSq = maxDistance * maxDistance;

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || entity == client.player) {
                continue;
            }
            if (living.isDead() || living.isRemoved()) {
                continue;
            }
            if (!isHuntableType(living)) {
                continue;
            }
            if (entity.squaredDistanceTo(client.player) > maxDistance * maxDistance) {
                continue;
            }

            Box entityBox = entity.getBoundingBox().expand(0.3);
            Optional<Vec3d> intersect = entityBox.raycast(eyePos, rayEnd);
            if (intersect.isPresent()) {
                double distSq = eyePos.squaredDistanceTo(intersect.get());
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    found = living;
                }
            }
        }
        return found;
    }

    private static boolean isHuntableType(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        return HUNTABLE_TYPES.contains(entity.getType());
    }

    private static void sendTargetPayload(LivingEntity target) {
        if (target == null || botName == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(HuntTargetPayload.ID)) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botName", botName);
        out.put("entityUuid", target.getUuidAsString());
        Identifier entityId = EntityType.getId(target.getType());
        out.put("entityType", entityId != null ? entityId.toString() : "");
        ClientPlayNetworking.send(new HuntTargetPayload(GSON.toJson(out)));
    }

    private static void sendModePayload(boolean active) {
        if (!ClientPlayNetworking.canSend(HuntTargetModePayload.ID)) {
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("botName", botName != null ? botName : "");
        out.put("active", active);
        ClientPlayNetworking.send(new HuntTargetModePayload(GSON.toJson(out)));
    }
}
