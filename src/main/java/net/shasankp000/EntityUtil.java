package net.shasankp000;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.Monster;

public final class EntityUtil {
    private EntityUtil() {}

    public static boolean isHostile(Entity entity) {
        if (entity == null) {
            return false;
        }

        if (entity instanceof HostileEntity) {
            return true;
        }

        if (entity instanceof Monster) {
            return true;
        }

        try {
            return entity.getType().getSpawnGroup() == SpawnGroup.MONSTER;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
