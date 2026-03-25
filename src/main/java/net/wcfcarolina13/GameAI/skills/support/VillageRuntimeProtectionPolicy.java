package net.wcfcarolina13.GameAI.skills.support;

final class VillageRuntimeProtectionPolicy {

    private VillageRuntimeProtectionPolicy() {
    }

    static TreeDetector.WoodcutProtectionDecision decideWoodcutProtection(boolean explicitProtectedZone,
                                                                          boolean nearSavedBase,
                                                                          boolean insideFortificationZone,
                                                                          boolean insideMappedVillage) {
        if (explicitProtectedZone) {
            return new TreeDetector.WoodcutProtectionDecision(true, "admin-zone");
        }
        if (nearSavedBase) {
            return new TreeDetector.WoodcutProtectionDecision(true, "base-radius");
        }
        if (insideFortificationZone) {
            return new TreeDetector.WoodcutProtectionDecision(true, "fort-buffer");
        }
        if (insideMappedVillage) {
            return new TreeDetector.WoodcutProtectionDecision(true, "mapped-village");
        }
        return new TreeDetector.WoodcutProtectionDecision(false, "none");
    }
}
