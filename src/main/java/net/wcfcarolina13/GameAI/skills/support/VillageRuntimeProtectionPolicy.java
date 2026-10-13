package net.wcfcarolina13.GameAI.skills.support;

import java.util.Collection;
import java.util.function.Predicate;

final class VillageRuntimeProtectionPolicy {

    private VillageRuntimeProtectionPolicy() {
    }

    /**
     * Woodcut protection verdict. Saved-base radius is deliberately NOT an input: base
     * protection exists to stop structure destruction, and trees are resources (see
     * {@link TreeDetector#isProtectedForWoodcut}). The base auto-zones that mirror each base
     * into {@code ProtectedZoneService} are filtered out of {@code explicitProtectedZone} by
     * the caller via {@link #anyNonBaseZone}, so only user-drawn zones block here.
     */
    static TreeDetector.WoodcutProtectionDecision decideWoodcutProtection(boolean explicitProtectedZone,
                                                                          boolean insideFortificationZone,
                                                                          boolean insideMappedVillage) {
        if (explicitProtectedZone) {
            return new TreeDetector.WoodcutProtectionDecision(true, "admin-zone");
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
     * True when any of the explicit zones containing a position is not a base auto-zone.
     * Overlapping zones: one user-drawn zone is enough to block, whatever base zones sit
     * alongside it. A null or blank label is treated as user-drawn (conservative).
     */
    static boolean anyNonBaseZone(Collection<String> containingZoneLabels, Predicate<String> isBaseAutoZoneLabel) {
        if (containingZoneLabels == null || containingZoneLabels.isEmpty()) {
            return false;
        }
        for (String label : containingZoneLabels) {
            if (label == null || label.isBlank() || !isBaseAutoZoneLabel.test(label)) {
                return true;
            }
        }
        return false;
    }
}
