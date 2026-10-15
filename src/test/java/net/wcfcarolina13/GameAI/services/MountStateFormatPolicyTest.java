package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountStateFormatPolicyTest {

    @Test
    void entryWithMountFieldsIsPreWorldFormat() {
        assertTrue(MountStateFormatPolicy.isPreWorldFormat(Map.of("mountUuid", "abc", "x", 1.0)));
        assertTrue(MountStateFormatPolicy.isPreWorldFormat(Map.of("worldId", "minecraft:overworld")));
    }

    @Test
    void entryNestedByWorldKeyIsCurrentFormat() {
        Map<String, Object> nested = Map.of(
                "Nirn", Map.of("mountUuid", "abc", "worldId", "minecraft:overworld"),
                "_legacy", Map.of("mountUuid", "def"));
        assertFalse(MountStateFormatPolicy.isPreWorldFormat(nested));
    }

    @Test
    void nullOrEmptyEntryIsNotPreWorldFormat() {
        assertFalse(MountStateFormatPolicy.isPreWorldFormat(null));
        assertFalse(MountStateFormatPolicy.isPreWorldFormat(Map.of()));
    }
}
