package net.wcfcarolina13;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.Monster;
import net.wcfcarolina13.GameAI.services.EndermanSafetyService;

public final class EntityUtil {
    private EntityUtil() {}

    // ── Display-name safety ──────────────────────────────────────────────
    /** Maximum characters to show for a bot name in UI before truncating. */
    private static final int DISPLAY_NAME_MAX = 24;

    /**
     * Truncates a bot alias for safe UI display. Returns the name unchanged
     * if it's already within limits; otherwise truncates and appends "…".
     * Also strips Minecraft formatting codes (§) as a safety net.
     */
    public static String safeDisplayName(String name) {
        if (name == null || name.isEmpty()) return "Bot";
        // Strip § formatting codes (§ followed by any char)
        String clean = name.replaceAll("\u00A7.", "").trim();
        if (clean.isEmpty()) return "Bot";
        if (clean.length() <= DISPLAY_NAME_MAX) return clean;
        return clean.substring(0, DISPLAY_NAME_MAX - 1) + "\u2026"; // ellipsis
    }

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
