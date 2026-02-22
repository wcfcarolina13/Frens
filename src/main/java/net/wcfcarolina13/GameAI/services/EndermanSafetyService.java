package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.EndermanEntity;

/**
 * Centralized enderman aggression checks used by targeting/look systems.
 *
 * <p>Passive endermen should be treated as neutral to avoid accidental aggro from eye contact.</p>
 */
public final class EndermanSafetyService {

    private EndermanSafetyService() {
    }

    public static boolean shouldAvoidLookingAt(Entity entity) {
        if (!(entity instanceof EndermanEntity enderman)) {
            return false;
        }
        return !isHostileEnderman(enderman);
    }

    public static boolean isSafeLookTarget(Entity entity) {
        return entity != null && !shouldAvoidLookingAt(entity);
    }

    public static boolean isHostileEnderman(EndermanEntity enderman) {
        if (enderman == null || enderman.isRemoved() || !enderman.isAlive()) {
            return false;
        }
        LivingEntity target = enderman.getTarget();
        if (target != null && target.isAlive()) {
            return true;
        }
        LivingEntity attacker = enderman.getAttacker();
        return attacker != null && attacker.isAlive();
    }
}
