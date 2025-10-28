package net.shasankp000.Entity;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.EntityUtil;

import java.io.Serial;
import java.io.Serializable;

public class EntityDetails implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final double x, y, z;
    private final boolean isHostile;
    private final String directionToBot;

    // Backward-compatible constructor (callers can still pass isHostile/direction explicitly)
    public EntityDetails(String name, double x, double y, double z, boolean isHostile, String directionToBot) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.isHostile = isHostile;
        this.directionToBot = directionToBot;
    }

    /**
     * Preferred factory: derives hostility via EntityUtil and direction relative to the bot.
     */
    public static EntityDetails from(ServerPlayerEntity bot, Entity e) {
        String name = e.getName().getString();
        double ex = e.getX();
        double ey = e.getY();
        double ez = e.getZ();
        boolean hostile = EntityUtil.isHostile(e);
        String direction = determineDirectionToBot(bot, e);
        return new EntityDetails(name, ex, ey, ez, hostile, direction);
    }

    public String getName() { return name; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public boolean isHostile() { return isHostile; }
    public String getDirectionToBot() { return directionToBot; }

    @Override
    public String toString() {
        return "EntityDetails{" +
                "name='" + name + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", z=" + z +
                ", isHostile=" + isHostile +
                ", directionToBot='" + directionToBot + '\'' +
                '}';
    }

    // --- Helpers ---

    private static String determineDirectionToBot(ServerPlayerEntity bot, Entity target) {
        double relativeAngle = getRelativeAngle(bot, target);
        if (relativeAngle <= 45 || relativeAngle > 315) return "front";
        else if (relativeAngle > 45 && relativeAngle <= 135) return "right";
        else if (relativeAngle > 135 && relativeAngle <= 225) return "behind";
        else return "left";
    }

    private static double getRelativeAngle(Entity bot, Entity target) {
        double botX = bot.getX();
        double botZ = bot.getZ();
        double targetX = target.getX();
        double targetZ = target.getZ();

        float botYaw = bot.getYaw(); // 0 = south, 90 = west, etc.

        double deltaX = targetX - botX;
        double deltaZ = targetZ - botZ;
        double angleToEntity = Math.toDegrees(Math.atan2(deltaZ, deltaX));

        double botFacing = (botYaw + 360) % 360;
        return (angleToEntity - botFacing + 360) % 360;
    }
}