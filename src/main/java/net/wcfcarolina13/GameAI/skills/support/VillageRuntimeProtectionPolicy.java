package net.wcfcarolina13.GameAI.skills.support;

import java.util.Collection;
import java.util.function.Predicate;

final class VillageRuntimeProtectionPolicy {

    private VillageRuntimeProtectionPolicy() {
    }

    /** Same as the five-argument form with no Spawn-base protection. */
    static TreeDetector.WoodcutProtectionDecision decideWoodcutProtection(boolean explicitProtectedZone,
                                                                          boolean nearSavedBase,
                                                                          boolean insideFortificationZone,
                                                                          boolean insideMappedVillage) {
        return decideWoodcutProtection(explicitProtectedZone, nearSavedBase, false,
                insideFortificationZone, insideMappedVillage);
    }

    /**
     * Woodcut protection verdict. {@code explicitProtectedZone} arrives with SERVER-owned base
     * auto-zones already filtered out by the caller, and {@code nearSavedBase} counts only
     * player-owned bases. {@code insideBlockedSpawnBase} is the server Spawn base (zone or radius)
     * when the admin toggle has not opened it to woodcut; it is reported as its own reason so the
     * bot can say why it leaves spawn. Player claims win the tie, since their line is more useful.
     */
    static TreeDetector.WoodcutProtectionDecision decideWoodcutProtection(boolean explicitProtectedZone,
                                                                          boolean nearSavedBase,
                                                                          boolean insideBlockedSpawnBase,
                                                                          boolean insideFortificationZone,
                                                                          boolean insideMappedVillage) {
        if (explicitProtectedZone) {
            return new TreeDetector.WoodcutProtectionDecision(true, "admin-zone");
        }
        if (nearSavedBase) {
            return new TreeDetector.WoodcutProtectionDecision(true, "base-radius");
        }
        if (insideBlockedSpawnBase) {
            return new TreeDetector.WoodcutProtectionDecision(true, WoodcutBaseAccessPolicy.SPAWN_BASE_REASON);
        }
        if (insideFortificationZone) {
            return new TreeDetector.WoodcutProtectionDecision(true, "fort-buffer");
        }
        if (insideMappedVillage) {
            return new TreeDetector.WoodcutProtectionDecision(true, "mapped-village");
        }
        return new TreeDetector.WoodcutProtectionDecision(false, "none");
    }

    /**
     * True when any of the explicit zones containing a position is not exempt. Overlapping
     * zones: one non-exempt zone is enough to block, whatever exempt zones sit alongside it.
     * A null element is treated as non-exempt (conservative).
     */
    static <T> boolean anyNonExemptZone(Collection<T> containingZones, Predicate<T> exempt) {
        if (containingZones == null || containingZones.isEmpty()) {
            return false;
        }
        for (T zone : containingZones) {
            if (zone == null || !exempt.test(zone)) {
                return true;
            }
        }
        return false;
    }
}
