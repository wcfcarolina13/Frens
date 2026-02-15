package net.shasankp000;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.Monster;
import net.shasankp000.GameAI.services.EndermanSafetyService;

public final class EntityUtil {
    private EntityUtil() {}

    public static boolean isHostile(Entity entity) {
        if (entity == null) {
            return false;
        }

        if (entity instanceof EndermanEntity enderman) {
            return EndermanSafetyService.isHostileEnderman(enderman);
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
