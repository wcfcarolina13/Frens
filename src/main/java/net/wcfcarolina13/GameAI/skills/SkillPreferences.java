package net.wcfcarolina13.GameAI.skills;

import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Frens;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds lightweight, in-memory per-bot preferences that influence how skills behave. Currently
 * this tracks teleport settings, inventory pause behavior, and drop collection preferences.
 */
public final class SkillPreferences {

    private static final Map<UUID, Boolean> TELEPORT_PREFS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> FOLLOW_TELEPORT_PREFS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> PAUSE_ON_FULL_INV = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> TELEPORT_DROP_SWEEP = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> EMERGENCY_TACTICS = new ConcurrentHashMap<>();

    private SkillPreferences() {
    }

    public static boolean teleportDuringSkills(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return teleportDuringSkills(player.getUuid());
    }

    public static boolean teleportDuringSkills(UUID uuid) {
        // Global override takes precedence over all per-bot settings.
        Boolean global = Frens.CONFIG.getGlobalTeleportDuringSkills();
        if (global != null) return global;
        // Per-bot setting, default false.
        if (uuid == null) {
            return false;
        }
        return TELEPORT_PREFS.getOrDefault(uuid, Boolean.FALSE);
    }

    public static void setTeleportDuringSkills(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return;
        }
        TELEPORT_PREFS.put(uuid, enabled);
    }

    public static boolean followTeleport(ServerPlayerEntity player) {
        return player != null && followTeleport(player.getUuid());
    }

    public static boolean followTeleport(UUID uuid) {
        if (uuid == null) return false;
        return FOLLOW_TELEPORT_PREFS.getOrDefault(uuid, Boolean.FALSE);
    }

    public static void setFollowTeleport(UUID uuid, boolean enabled) {
        if (uuid == null) return;
        FOLLOW_TELEPORT_PREFS.put(uuid, enabled);
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

    /**
     * Whether the bot may use emergency combat tactics: creeper block-and-shield,
     * emergency pillar-up, or dig-down bunker when overwhelmed. Default: ON.
     */
    public static boolean emergencyTactics(ServerPlayerEntity player) {
        if (player == null) return true;
        return emergencyTactics(player.getUuid());
    }

    public static boolean emergencyTactics(UUID uuid) {
        if (uuid == null) return true;
        return EMERGENCY_TACTICS.getOrDefault(uuid, Boolean.TRUE);
    }

    public static void setEmergencyTactics(UUID uuid, boolean enabled) {
        if (uuid == null) return;
        if (enabled) {
            EMERGENCY_TACTICS.remove(uuid);
        } else {
            EMERGENCY_TACTICS.put(uuid, Boolean.FALSE);
        }
    }
}
