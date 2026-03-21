package net.wcfcarolina13.Entity;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.services.EndermanSafetyService;

import java.util.List;

public class FaceClosestEntity {

    public static void faceClosestEntity(ServerPlayerEntity bot, List<Entity> entities) {
        if (bot == null || entities == null || entities.isEmpty()) {
            return;
        }

        Entity closestEntity = null;
        double closestDistance = Double.MAX_VALUE;

        // Find the closest entity
        for (Entity entity : entities) {
            if (entity == null || entity.isRemoved() || !entity.isAlive()) {
                continue;
            }
            if (!EndermanSafetyService.isSafeLookTarget(entity)) {
                continue;
            }
            double distance = bot.squaredDistanceTo(entity);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestEntity = entity;
            }
        }

        if (closestEntity != null) {
            // Calculate the direction to the closest entity
        Vec3d botPos = bot.getEntityPos();
        Vec3d entityPos = closestEntity.getEntityPos();
            Vec3d direction = entityPos.subtract(botPos).normalize();

            // Calculate yaw and pitch
            double yaw = Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90;
            double pitch = Math.toDegrees(-Math.atan2(direction.y, Math.sqrt(direction.x * direction.x + direction.z * direction.z)));

            // Set the bot's rotation
            bot.setYaw((float) yaw);
            bot.setHeadYaw((float) yaw);
            bot.setBodyYaw((float) yaw);
            bot.setPitch((float) Math.max(-90.0, Math.min(90.0, pitch)));

        }
    }
}
