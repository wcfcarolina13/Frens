package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HuntConfigCopyTest {
    @Test void copiedSettingsDoNotShareMutableTargetListsInEitherDirection() {
        var original = new HuntConfigService.HuntConfig(false, HuntConfigService.HuntZone.EXPANDED,
                List.of("minecraft:cow"));
        var snapshot = original.copy();
        snapshot.selectedTargets.add("minecraft:pig");
        snapshot.depopulationEnabled = true;
        snapshot.zone = "SPRAWLING";
        assertEquals(List.of("minecraft:cow"), original.selectedTargets);
        assertFalse(original.depopulationEnabled);
        assertEquals("EXPANDED", original.zone);
        original.selectedTargets.add("minecraft:sheep");
        assertEquals(List.of("minecraft:cow", "minecraft:pig"), snapshot.selectedTargets);
    }

    @Test void copyingLoadedSettingsPreservesRawZoneAndHandlesMissingTargets() {
        var original = new HuntConfigService.HuntConfig();
        original.zone = "unrecognized legacy zone";
        original.selectedTargets = null;
        var snapshot = original.copy();
        assertEquals("unrecognized legacy zone", snapshot.zone);
        assertEquals(HuntConfigService.HuntZone.STANDARD, snapshot.huntZone());
        assertNotNull(snapshot.selectedTargets);
        snapshot.selectedTargets.add("minecraft:chicken");
        assertNull(original.selectedTargets);
    }

    @Test void importedConfigCanBeEditedWithoutChangingLegacyOrAnotherSave() {
        String legacy = "New World:minecraft:overworld";
        String first = "New World#aaaa";
        String second = "New World#bbbb";
        var original = new HuntConfigService.HuntConfig(false, HuntConfigService.HuntZone.EXPANDED,
                List.of("minecraft:cow"));
        Map<String, Map<String, HuntConfigService.HuntConfig>> worlds = new HashMap<>();
        worlds.put(legacy, new HashMap<>(Map.of("jake", original)));
        for (String fingerprint : List.of(first, second)) {
            var result = LegacyRegistryRecoveryPolicy.copyLegacy(worlds, legacy,
                    fingerprint + ":minecraft:overworld", fingerprint, fingerprint, source -> {
                        var copied = new HashMap<String, HuntConfigService.HuntConfig>();
                        source.forEach((alias, config) -> copied.put(alias, config.copy()));
                        return copied;
                    });
            assertEquals(LegacyRegistryRecoveryPolicy.Result.IMPORTED, result.result());
        }
        worlds.get(first + ":minecraft:overworld").get("jake").selectedTargets.clear();
        assertEquals(List.of("minecraft:cow"), original.selectedTargets);
        assertEquals(List.of("minecraft:cow"), worlds.get(second + ":minecraft:overworld").get("jake").selectedTargets);
    }

    @Test void constructorAndSavedSnapshotStayIndependentOfCallerOwnedLists() {
        var targets = new ArrayList<>(List.of("minecraft:rabbit"));
        var input = new HuntConfigService.HuntConfig(true, HuntConfigService.HuntZone.STANDARD, targets);
        var saved = input.copy();
        targets.clear();
        input.selectedTargets.clear();
        assertEquals(List.of("minecraft:rabbit"), saved.selectedTargets);
    }
}
