package net.wcfcarolina13.FilingSystem;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAvailabilityPolicyTest {
    @Test
    void stripsLegacyAndBlankEntriesOnLoad() {
        assertEquals(List.of("llama3.2", "qwen3:4b"),
                ModelAvailabilityPolicy.sanitizeModelList(Arrays.asList(
                        "Ollama is not reachable!", "", " \t", null, "llama3.2", "qwen3:4b")));
        assertEquals(List.of(), ModelAvailabilityPolicy.sanitizeModelList(null));
    }

    @Test
    void stripsLegacySelectionOnLoadAndPreservesGenuineNames() {
        assertEquals("", ModelAvailabilityPolicy.sanitizeSelection("Ollama is not reachable!"));
        assertEquals("", ModelAvailabilityPolicy.sanitizeSelection(null));
        assertEquals("", ModelAvailabilityPolicy.sanitizeSelection(" \t"));
        assertEquals("llama3.2", ModelAvailabilityPolicy.sanitizeSelection("llama3.2"));
    }

    @Test
    void unavailableOrInvalidSelectionKeepsPreviousGenuineModel() {
        for (String invalid : Arrays.asList(null, "", " ", "Ollama is not reachable!")) {
            assertEquals("llama3.2", ModelAvailabilityPolicy.selectionToSave(invalid, "llama3.2"));
        }
        assertEquals("qwen3:4b", ModelAvailabilityPolicy.selectionToSave("qwen3:4b", "llama3.2"));
        assertEquals("", ModelAvailabilityPolicy.selectionToSave("", "Ollama is not reachable!"));
    }

    @Test
    void emptyOrLegacyOnlyResultIsUnavailable() {
        assertEquals(ModelAvailabilityPolicy.Status.UNAVAILABLE,
                ModelAvailabilityPolicy.statusForResult(List.of()));
        assertEquals(ModelAvailabilityPolicy.Status.UNAVAILABLE,
                ModelAvailabilityPolicy.statusForResult(List.of("Ollama is not reachable!", " ")));
        assertEquals(ModelAvailabilityPolicy.Status.READY,
                ModelAvailabilityPolicy.statusForResult(List.of("llama3.2")));
    }

    @Test
    void unavailableIsInfoOnlyOnce() {
        assertTrue(ModelAvailabilityPolicy.shouldLogUnavailableAtInfo(false));
        assertFalse(ModelAvailabilityPolicy.shouldLogUnavailableAtInfo(true));
    }
}
