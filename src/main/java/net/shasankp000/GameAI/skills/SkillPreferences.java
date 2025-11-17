package net.shasankp000.GameAI.skills;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds lightweight, in-memory per-bot preferences that influence how skills behave. Currently
 * this tracks teleport settings, inventory pause behavior, and drop collection preferences.
 */
public final class SkillPreferences {

    private static final Map<UUID, Boolean> TELEPORT_PREFS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> PAUSE_ON_FULL_INV = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> TELEPORT_DROP_SWEEP = new ConcurrentHashMap<>();

    private SkillPreferences() {
    }

    public static boolean teleportDuringSkills(ServerPlayerEntity player) {
        if (player == null) {
            return true;
        }
        return teleportDuringSkills(player.getUuid());
    }

    public static boolean teleportDuringSkills(UUID uuid) {
        if (uuid == null) {
            return true;
        }
        return TELEPORT_PREFS.getOrDefault(uuid, Boolean.TRUE);
    }

    public static void setTeleportDuringSkills(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return;
        }
        if (enabled) {
            TELEPORT_PREFS.remove(uuid);
        } else {
            TELEPORT_PREFS.put(uuid, Boolean.FALSE);
        }
    }

    public static boolean pauseOnFullInventory(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return pauseOnFullInventory(player.getUuid());
    }

    public static boolean pauseOnFullInventory(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return PAUSE_ON_FULL_INV.getOrDefault(uuid, Boolean.FALSE);
    }

    public static void setPauseOnFullInventory(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return;
        }
        if (enabled) {
            PAUSE_ON_FULL_INV.put(uuid, Boolean.TRUE);
        } else {
            PAUSE_ON_FULL_INV.remove(uuid);
        }
    }

    public static boolean teleportDuringDropSweep(ServerPlayerEntity player) {
        if (player == null) {
            return false; // Default to no teleport for drop sweeps
        }
        return teleportDuringDropSweep(player.getUuid());
    }

    public static boolean teleportDuringDropSweep(UUID uuid) {
        if (uuid == null) {
            return false; // Default to no teleport for drop sweeps
        }
        return TELEPORT_DROP_SWEEP.getOrDefault(uuid, Boolean.FALSE);
    }

    public static void setTeleportDuringDropSweep(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return;
        }
        if (enabled) {
            TELEPORT_DROP_SWEEP.put(uuid, Boolean.TRUE);
        } else {
            TELEPORT_DROP_SWEEP.remove(uuid);
        }
    }
}
