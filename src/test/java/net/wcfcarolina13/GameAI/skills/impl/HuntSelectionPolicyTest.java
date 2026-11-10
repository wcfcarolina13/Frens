package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuntSelectionPolicyTest {

    private static final List<String> TICKED = List.of("minecraft:cow", "minecraft:pig", "minecraft:chicken");

    @Test
    void huntButtonUsesEveryTickedAnimal() {
        assertEquals(TICKED, HuntSelectionPolicy.effectiveSelection(true, TICKED, null));
    }

    @Test
    void typedAutoAndHobbyHuntsIgnoreTheTicks() {
        assertEquals(List.of(), HuntSelectionPolicy.effectiveSelection(false, TICKED, null));
    }

    @Test
    void nothingTickedMeansAnythingEdible() {
        assertEquals(List.of(), HuntSelectionPolicy.effectiveSelection(true, List.of(), null));
        assertEquals(List.of(), HuntSelectionPolicy.effectiveSelection(true, null, null));
    }

    @Test
    void resumedHuntKeepsItsSavedSelection() {
        assertEquals(List.of("minecraft:pig"),
                HuntSelectionPolicy.effectiveSelection(false, TICKED, List.of("minecraft:pig")));
        assertEquals(List.of(), HuntSelectionPolicy.effectiveSelection(false, TICKED, List.of()));
    }

    @Test
    void huntButtonSendsTheTickedOptionNotOneAnimal() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/net/wcfcarolina13/GraphicalUserInterface/HuntablesScreen.java"));
        int at = src.indexOf("private void huntSelected()");
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertFalse(body.contains("iterator().next()"), "the Hunt button must not pick one ticked animal");
        assertTrue(body.contains("HuntSelectionPolicy.TICKED_OPTION"));
    }
}
