package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Built-in soul profiles parse from their shipped JSON resources and stay addressable by name. */
class SoulProfileRegistryTest {

    @Test
    void allBuiltInProfilesLoad() {
        List<String> ids = SoulProfileRegistry.registeredIds();
        assertTrue(ids.contains("frens:jake"), ids.toString());
        assertTrue(ids.contains("frens:bob"), ids.toString());
        assertTrue(ids.contains("frens:silas"), ids.toString());
    }

    @Test
    void silasHasEveryRequiredField() {
        SoulTypes.SoulProfile silas = SoulProfileRegistry.require("frens:silas");
        assertEquals("frens:silas", silas.id());
        assertEquals("Silas", silas.displayName());
        assertFalse(silas.identity().isEmpty(), "identity lines drive the system prompt");
        assertFalse(silas.values().isEmpty());
        assertFalse(silas.boundaries().isEmpty());
        assertFalse(silas.examples().isEmpty());
        for (SoulTypes.Message example : silas.examples()) {
            assertEquals(SoulTypes.Role.ASSISTANT, example.role());
            assertFalse(example.content().isBlank());
        }
        // No authored voice block: Silas falls through to the global/assigned voice, like Jake and Bob.
        assertEquals(SoulTypes.VoiceSpec.EMPTY, silas.voice());
    }

    @Test
    void silasIsAddressableByName() {
        assertEquals("frens:silas", SoulProfileRegistry.profileIdForBotName("Silas").orElseThrow());
        assertEquals("frens:silas", SoulProfileRegistry.profileIdForBotName(" silas ").orElseThrow());
        assertTrue(SoulProfileRegistry.profileIdForBotName("Nobody").isEmpty());
    }
}
